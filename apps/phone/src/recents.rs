//! The Recents tab (stories 6.1 to 6.4).
//!
//! The list is a virtualised `uniform_list` of fixed 58 px rows, so scroll
//! offsets are arithmetic and only the rows on screen are built. Everything a
//! row says is prepared once per call-log snapshot ([`RowVm`]); rendering only
//! clones string handles. A whole list shares one pointer surface instead of
//! one handler per row: a touch or click is turned into a row with plain
//! arithmetic ([`row_at`]), which keeps the per-frame work flat however many
//! entries there are.

use std::{
    cell::Cell,
    ops::Range,
    rc::Rc,
    sync::Arc,
    time::{Duration, Instant},
};

use gpui::{
    AnyElement, Bounds, Context, HapticFeedback, InteractiveElement, IntoElement, MouseButton,
    ObjectFit, ParentElement, Pixels, Point, RenderImage, Role, SharedString,
    StatefulInteractiveElement, Styled, StyledImage, Task, TouchPhase, UniformListScrollHandle,
    Window, div, linear_color_stop, linear_gradient, prelude::*, px, svg, uniform_list,
};
use rovsti_core::{
    recents::{self, Direction, RowView},
    sim::SimInfo,
};
use rovsti_ui::{
    Icon,
    components::glass_pill,
    motion::{Entrance, press_scale},
    motion_tokens::scale,
    theme::{self, call_colors},
    tokens::{Theme, metrics, mix, typography},
    touch::{Pointer, TAP_SLOP, TapGuard, pointer_surface, touch_surface},
};

use crate::{
    PhoneApp, RECENTS_TAB,
    backend::{self, Access, CallLog, Group, Load, Placement},
    flight::{Home, Look},
    notice::Notice,
    wa_peer::CallerPhoto,
};

/// Fixed row height (UX-DR5): scroll offsets are `index * ROW_H`.
pub const ROW_H: f32 = 58.0;
/// Width of the info target at the right edge of a row.
const INFO_W: f32 = 44.0;
/// Extra empty rows at the end so the last call scrolls clear of the tab bar.
const TAIL_ROWS: usize = 2;
const AVATAR: f32 = 40.0;
/// A finger that travels further than this is scrolling, not tapping.
/// Skeleton rows appear only when loading takes longer than this.
const SKELETON_DELAY: Duration = Duration::from_millis(150);
/// The first rows must stay on screen this long before Recents counts as seen.
const VIEW_DELAY: Duration = Duration::from_secs(1);
/// Time labels are rebuilt when a snapshot is older than this at tab open.
const LABELS_STALE: Duration = Duration::from_secs(60);

/// Where a pointer landed in a row.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Zone {
    Row,
    Info,
}

/// Which row (and part of it) is at `y` px below the list's top, given how far
/// the list scrolled (`scrolled`, positive going down) and its `width`.
pub(crate) fn row_at(
    x: f32,
    y: f32,
    scrolled: f32,
    width: f32,
    count: usize,
) -> Option<(usize, Zone)> {
    if y < 0.0 || x < 0.0 || x > width {
        return None;
    }
    let index = ((y + scrolled) / ROW_H).floor();
    if index < 0.0 || index >= count as f32 {
        return None;
    }
    let zone = if x > width - INFO_W - 4.0 {
        Zone::Info
    } else {
        Zone::Row
    };
    Some((index as usize, zone))
}

/// One row, ready to draw.
#[derive(Clone)]
pub(crate) struct RowVm {
    title: SharedString,
    count: Option<SharedString>,
    subtitle: SharedString,
    time: SharedString,
    aria: SharedString,
    direction: Direction,
    missed: bool,
    callable: bool,
    number: SharedString,
    hue: Option<usize>,
    monogram: Option<SharedString>,
    named: bool,
    /// A WhatsApp call: the row shows a WhatsApp badge.
    whatsapp: bool,
    /// A WhatsApp row's picture: the contact's photo, then WhatsApp's.
    photo: CallerPhoto,
    /// The SIM the newest call used, resolved once per call-log swap (never per frame).
    /// `None` with one SIM or when the log's key matches no account (the slot stays empty).
    sim: Option<SimBadge>,
}

/// What a Recents row draws in its reserved SIM slot.
#[derive(Clone)]
pub(crate) struct SimBadge {
    label: SharedString,
    argb: i32,
}

impl RowVm {
    /// A row for a number that has no call-log row of its own (the Blocked
    /// calls view opens the number sheet with it).
    pub(crate) fn for_number(number: SharedString, title: SharedString, named: bool) -> Self {
        Self {
            sim: None,
            aria: title.clone(),
            title,
            count: None,
            subtitle: SharedString::default(),
            time: SharedString::default(),
            direction: Direction::Unknown,
            missed: false,
            callable: !number.trim().is_empty(),
            number,
            hue: None,
            monogram: None,
            named,
            whatsapp: false,
            photo: CallerPhoto::default(),
        }
    }

    fn build(v: RowView, sims: &SimInfo) -> Self {
        let account = v
            .account_key
            .as_deref()
            .and_then(|key| sims.index_of(key))
            .and_then(|i| sims.accounts().get(i));
        let sim = account.map(|a| SimBadge {
            label: a.chip.to_string().into(),
            argb: a.color_argb,
        });
        let aria: SharedString = match account {
            Some(a) => format!("{}, {}", v.aria, crate::sim_ui::chip_aria(a)).into(),
            None => v.aria.into(),
        };
        Self {
            sim,
            aria,
            title: v.title.into(),
            count: v.count.map(Into::into),
            subtitle: v.subtitle.into(),
            time: v.time.into(),
            direction: v.direction,
            missed: v.missed,
            callable: v.callable,
            number: v.number.into(),
            hue: v.hue,
            monogram: v.monogram.map(|c| c.to_string().into()),
            named: v.named,
            whatsapp: v.whatsapp,
            photo: CallerPhoto::default(),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum State {
    Loading,
    Denied,
    Unavailable,
    Ready,
}

/// A finger that is down on the list.
#[derive(Clone, Copy)]
struct Touch {
    start: Point<Pixels>,
}

pub(crate) struct RecentsView {
    scroll: UniformListScrollHandle,
    /// Where the list was last painted (written by the pointer surface).
    bounds: Rc<Cell<Bounds<Pixels>>>,
    state: State,
    rows: Arc<[RowVm]>,
    log: Option<Arc<CallLog>>,
    /// The SIMs the rows were resolved against.
    sims: SimInfo,
    /// The Name format the titles were built with.
    format: rovsti_core::wire::NameFormat,
    built_at: Option<Instant>,
    /// Loading has taken longer than [`SKELETON_DELAY`].
    skeleton_on: bool,
    skeleton_in: Entrance,
    skeleton_task: Option<Task<()>>,
    view_task: Option<Task<()>>,
    /// This view cycle already reported "seen".
    marked: bool,
    touch: Option<Touch>,
    /// The row whose press spring may still be moving (drawn tinted).
    last_pressed: Option<(usize, Zone)>,
    /// The row the Number sheet shows.
    pub(crate) number: Option<RowVm>,
    /// The calls with that number, built when the sheet opened.
    pub(crate) number_history: Arc<[HistoryVm]>,
    long_task: Option<Task<()>>,
    /// Unseen missed calls (the Recents tab's spoken label).
    missed: u32,
    /// A call log has been counted already: only then may a bigger count pop the badge (S13).
    missed_counted: bool,
    /// The WhatsApp call history revision the rows were built from.
    wa_history_seen: u64,
    /// WhatsApp rows titled without a contact or a WhatsApp profile name: a
    /// profile that arrives for one of them rebuilds the rows.
    wa_unnamed: Vec<SharedString>,
    /// Their profiles, still to be asked for.
    wa_ask: Vec<SharedString>,
    /// A long press already opened the info sheet: swallow the tap after it.
    suppress_click: bool,
    tap: TapGuard,
    /// Live refresh (story 10.3): the rows above the first old one, how many
    /// of the leading rows are new or newer, and the shared 0 to 1 progress.
    /// Only transforms and opacity move; the list never relayouts.
    inserted: usize,
    fresh: usize,
    fresh_in: Entrance,
}

impl RecentsView {
    pub(crate) fn new() -> Self {
        Self {
            scroll: UniformListScrollHandle::new(),
            bounds: Rc::new(Cell::new(Bounds::default())),
            state: State::Loading,
            rows: Arc::from(Vec::new()),
            log: None,
            sims: SimInfo::default(),
            format: rovsti_core::wire::NameFormat::default(),
            built_at: None,
            skeleton_on: false,
            skeleton_in: Entrance::default(),
            skeleton_task: None,
            view_task: None,
            marked: false,
            touch: None,
            last_pressed: None,
            number: None,
            number_history: Arc::from(Vec::new()),
            long_task: None,
            missed: 0,
            missed_counted: false,
            wa_history_seen: 0,
            wa_unnamed: Vec::new(),
            wa_ask: Vec::new(),
            suppress_click: false,
            tap: TapGuard::default(),
            inserted: 0,
            fresh: 0,
            fresh_in: Entrance::default(),
        }
    }

    pub(crate) fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.skeleton_in.tick(dt);
        moving |= self.fresh_in.tick(dt);
        moving
    }

    /// Grouped rows in the list.
    #[cfg(test)]
    pub(crate) fn row_count(&self) -> usize {
        self.rows.len()
    }

    pub(crate) fn scroll_to_top(&self) {
        self.scroll.scroll_to_item(0, gpui::ScrollStrategy::Top);
    }

    /// How far the list is scrolled down, in px (never negative).
    /// Where row `index`'s avatar is painted now (window coordinates), if the
    /// row is inside the list.
    pub(crate) fn avatar_rect(&self, index: usize) -> Option<Bounds<Pixels>> {
        let list = self.bounds.get();
        let top = f32::from(list.origin.y) + index as f32 * ROW_H - self.scrolled();
        let rect = Bounds::new(
            gpui::point(
                list.origin.x + metrics::ROW_INSET,
                px(top + (ROW_H - AVATAR) / 2.0),
            ),
            gpui::size(px(AVATAR), px(AVATAR)),
        );
        list.contains(&rect.center()).then_some(rect)
    }

    /// Perf runs: put the list `y` px down.
    #[cfg(any(feature = "perf", test))]
    pub(crate) fn perf_scroll(&self, y: f32) {
        let handle = self.scroll.0.borrow().base_handle.clone();
        handle.set_offset(gpui::point(px(0.0), px(-y)));
    }

    pub(crate) fn scrolled(&self) -> f32 {
        -f32::from(self.scroll.0.borrow().base_handle.offset().y).min(0.0)
    }
}

// Rows the list asked for since the test last reset it (story 12.5).
#[cfg(test)]
thread_local! {
    pub(crate) static ROWS_BUILT: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

fn press_id(index: usize, zone: Zone) -> SharedString {
    press_key(index, zone).as_str().to_owned().into()
}

/// The press id as text on the stack: reading a press spring every frame must
/// not allocate (AD-21 2). `press_id` is for the tap, which keeps the id.
pub(crate) fn press_key(index: usize, zone: Zone) -> rovsti_core::small_text::SmallText<16> {
    let mut id = rovsti_core::small_text::SmallText::<16>::new();
    id.push_str(match zone {
        Zone::Row => "rc-",
        Zone::Info => "ri-",
    });
    id.push_padded(index as u64, 1);
    id
}

impl PhoneApp {
    // ---- Data ---------------------------------------------------------

    /// Pulls the backend's call log into the view. Rebuilds the row
    /// view-models only when the snapshot actually changed.
    pub(crate) fn sync_recents(&mut self, cx: &mut Context<Self>) {
        // S27 is playing over a row: the list holds still until it ends.
        if self.row_menu.s27.is_some() {
            return;
        }
        let load = self.backend.read(cx).call_log();
        match load {
            Load::Ready(log) => {
                let same = self
                    .recents
                    .log
                    .as_ref()
                    .is_some_and(|known| Arc::ptr_eq(known, &log));
                if same
                    && (self.recents.sims != self.backend.read(cx).sims()
                        || self.recents.format != self.backend.read(cx).settings().name_format)
                {
                    // A SIM came or went: the chips are resolved again, nothing animates.
                    self.rebuild_recents(log.clone(), cx);
                }
                if !same {
                    let old = self.recents.log.clone();
                    self.rebuild_recents(log, cx);
                    self.animate_refresh(old);
                }
                self.recents.state = State::Ready;
                self.recents.skeleton_on = false;
                self.recents.skeleton_task = None;
            }
            Load::Loading => {
                if self.recents.state != State::Loading || self.recents.skeleton_task.is_none() {
                    self.recents.state = State::Loading;
                    self.recents.skeleton_on = false;
                    self.recents.skeleton_task = Some(cx.spawn(async move |this, cx| {
                        cx.background_executor().timer(SKELETON_DELAY).await;
                        this.update(cx, |this, cx| {
                            if this.recents.state == State::Loading {
                                this.recents.skeleton_on = true;
                                this.recents.skeleton_in.restart();
                                cx.notify();
                            }
                        })
                        .ok();
                    }));
                }
            }
            Load::PermissionDenied => self.recents_failed(State::Denied),
            Load::Unavailable => self.recents_failed(State::Unavailable),
        }
        self.sync_missed_label(cx);
        self.arm_view_timer(cx);
        self.sync_wa_names(cx);
    }

    /// Asks for the profiles of unnamed WhatsApp rows, and rebuilds the rows
    /// once one of them has a WhatsApp name.
    pub(crate) fn sync_wa_names(&mut self, cx: &mut Context<Self>) {
        if self.wa_linked(cx) {
            for jid in std::mem::take(&mut self.recents.wa_ask) {
                self.want_wa_profile(&jid, cx);
            }
        }
        let avatars = self.wa_avatars.read(cx);
        let named = self
            .recents
            .wa_unnamed
            .iter()
            .any(|jid| avatars.name(jid).is_some());
        if named && let Some(log) = self.recents.log.clone() {
            self.rebuild_recents(log, cx);
            cx.notify();
        }
    }

    /// Story 10.3: a new snapshot replaced `old`. New calls fade in at the top
    /// and the rows under them glide down by the room they took. A reader who
    /// scrolled down keeps their place (the offset moves with the rows) and
    /// sees no animation at all.
    fn animate_refresh(&mut self, old: Option<Arc<CallLog>>) {
        let (Some(old), Some(new)) = (old, self.recents.log.clone()) else {
            return;
        };
        let delta = recents::refresh_delta(&old.rows, &new.rows);
        let r = &mut self.recents;
        r.inserted = 0;
        r.fresh = 0;
        if delta.fresh == 0 && delta.shift == 0 {
            return;
        }
        if r.scrolled() > 1.0 {
            if delta.shift > 0 {
                let handle = r.scroll.0.borrow().base_handle.clone();
                let mut offset = handle.offset();
                offset.y -= px(delta.shift as f32 * ROW_H);
                handle.set_offset(offset);
            }
        } else if self.tabs.selected() == RECENTS_TAB && self.window_active {
            r.inserted = delta.shift;
            r.fresh = delta.fresh;
            r.fresh_in.restart();
        }
    }

    /// The Recents tab announces its missed calls: "Recents, 2 missed calls".
    fn sync_missed_label(&mut self, cx: &mut Context<Self>) {
        let missed = match &self.recents.log {
            Some(log) => {
                let seen = self.backend.read(cx).prefs().ui.missed_seen_ms;
                rovsti_core::call_log::missed_badge(&log.entries, seen).saturating_add(
                    recents::missed_extras(&self.wa_call.read(cx).recents_extras(), seen),
                )
            }
            None => 0,
        };
        let counted = self.recents.log.is_some();
        // S13: only a count that grows after the first load pops.
        let grew = counted && self.recents.missed_counted && missed > self.recents.missed;
        self.recents.missed_counted = counted;
        if missed != self.recents.missed {
            self.recents.missed = missed;
            let label = match missed {
                0 => "Recents".to_owned(),
                1 => "Recents, 1 missed call".to_owned(),
                n => format!("Recents, {n} missed calls"),
            };
            self.tabs.set_label(RECENTS_TAB, label);
            // The capsule on the tab icon. It pops only when the count grew
            // after the first load; a first load or an equal count stays still.
            self.tabs.set_badge(
                RECENTS_TAB,
                rovsti_core::call_log::badge_label(missed)
                    .map(|text| SharedString::from(text.as_str().to_owned())),
            );
            if grew {
                self.tabs.pop_badge(RECENTS_TAB);
            }
        }
    }

    /// The WhatsApp call history changed: its rows and the missed count follow.
    pub(crate) fn sync_wa_history(&mut self, cx: &mut Context<Self>) {
        if self.wa_call.read(cx).history_rev() == self.recents.wa_history_seen {
            return;
        }
        if let Some(log) = self.recents.log.clone() {
            self.rebuild_recents(log, cx);
            self.sync_missed_label(cx);
            self.sync_wa_names(cx);
        } else {
            self.recents.wa_history_seen = self.wa_call.read(cx).history_rev();
        }
        cx.notify();
    }

    fn recents_failed(&mut self, state: State) {
        self.recents.state = state;
        self.recents.rows = Arc::from(Vec::new());
        self.recents.log = None;
        self.recents.skeleton_on = false;
        self.recents.skeleton_task = None;
    }

    fn rebuild_recents(&mut self, log: Arc<CallLog>, cx: &gpui::App) {
        let started = Instant::now();
        let wa = self.wa_call.read(cx);
        self.recents.wa_history_seen = wa.history_rev();
        let sims = self.backend.read(cx).sims();
        let format = self.backend.read(cx).settings().name_format;
        let index = match self.backend.read(cx).contacts() {
            backend::Load::Ready(index) => Some(index),
            _ => None,
        };
        // WhatsApp calls are titled by the contact, then WhatsApp's profile name.
        let avatars = self.wa_avatars.read(cx);
        let mut unnamed = Vec::new();
        let extras: Vec<_> = wa
            .recents_extras()
            .into_iter()
            .map(|mut extra| {
                let contact = index
                    .as_ref()
                    .and_then(|i| i.match_number(&extra.number_raw));
                if let Some(contact) = contact {
                    extra.label = Some(rovsti_core::names::display(contact, format).into_owned());
                } else if let Ok(jid) = rovsti_wa_proto::user_jid(&extra.number_raw) {
                    match avatars.name(&jid) {
                        Some(name) => extra.label = Some(name.to_owned()),
                        None => unnamed.push(SharedString::from(jid)),
                    }
                }
                extra
            })
            .collect();
        unnamed.sort();
        unnamed.dedup();
        let merged = recents::merge_extras(&log.rows, &extras);
        let views = recents::build(&merged, backend::now_ms(), log.locale);
        let last_first = format == rovsti_core::wire::NameFormat::LastFirst;
        let rows: Vec<RowVm> = views
            .into_iter()
            .map(|mut v| {
                // Last, First: the title of a known person follows the setting (story 16.9).
                if last_first
                    && v.named
                    && !v.whatsapp
                    && let Some(index) = &index
                    && let Some(contact) = index.match_number(&v.number)
                {
                    v.title = rovsti_core::names::display(contact, format).into_owned();
                }
                let photo = if v.whatsapp {
                    wa_row_photo(index.as_deref(), &v.number)
                } else {
                    CallerPhoto::default()
                };
                let mut row = RowVm::build(v, &sims);
                row.photo = photo;
                row
            })
            .collect();
        self.recents.wa_ask.clone_from(&unnamed);
        self.recents.wa_unnamed = unnamed;
        self.recents.format = format;
        self.recents.sims = sims;
        log::debug!(
            "recents: {} row view-models in {:?}",
            rows.len(),
            started.elapsed()
        );
        self.recents.rows = rows.into();
        self.recents.built_at = Some(Instant::now());
        self.recents.log = Some(log);
        self.recents.last_pressed = None;
        self.recents.inserted = 0;
        self.recents.fresh = 0;
    }

    // ---- Tab lifecycle ------------------------------------------------

    /// The selected tab changed (or the app was reopened on one).
    pub(crate) fn recents_tab_changed(&mut self, cx: &mut Context<Self>) {
        self.recents.view_task = None;
        self.recents.marked = false;
        self.recents.touch = None;
        self.call_note = None;
        if self.tabs.selected() == RECENTS_TAB {
            // Relative times ("3:14 pm", "Yesterday") age while the app sits.
            if let (Some(log), Some(built)) = (self.recents.log.clone(), self.recents.built_at)
                && built.elapsed() > LABELS_STALE
            {
                self.rebuild_recents(log, cx);
            }
            self.arm_view_timer(cx);
        }
    }

    pub(crate) fn recents_window_active(&mut self, active: bool, cx: &mut Context<Self>) {
        self.window_active = active;
        self.recents.view_task = None;
        self.recents.marked = false;
        if active {
            self.recents_tab_changed(cx);
        }
    }

    /// Story 6.4: after the first rows were on screen for a second, tell the
    /// backend Recents was viewed (clears the badge and the system
    /// notification). Once per view cycle.
    fn arm_view_timer(&mut self, cx: &mut Context<Self>) {
        let r = &mut self.recents;
        if self.tabs.selected() != RECENTS_TAB
            || !self.window_active
            || r.state != State::Ready
            || r.marked
            || r.view_task.is_some()
        {
            return;
        }
        r.view_task = Some(cx.spawn(async move |this, cx| {
            cx.background_executor().timer(VIEW_DELAY).await;
            this.update(cx, |this, cx| {
                this.recents.view_task = None;
                if this.tabs.selected() == RECENTS_TAB
                    && this.window_active
                    && this.recents.state == State::Ready
                    && !this.incall.covers()
                    && !this.incoming.covers()
                {
                    this.recents.marked = true;
                    this.backend
                        .update(cx, |backend, cx| backend.mark_missed_seen(cx));
                }
            })
            .ok();
        }));
    }

    // ---- Input --------------------------------------------------------

    /// True while something else owns the screen or the touch.
    fn recents_blocked(&self) -> bool {
        self.tabs.selected() != RECENTS_TAB
            || self.sheet.is_visible()
            || self.incall.covers()
            || self.incoming.covers()
            || self.row_menu.is_visible()
            || self.recents.state != State::Ready
    }

    fn recents_hit(&self, position: Point<Pixels>, window: &Window) -> Option<(usize, Zone)> {
        // The floating tab bar sits over the bottom of the list; a tap on it
        // must never reach the rows behind.
        let limit = window.viewport_size().height - rovsti_ui::tab_bar::content_bottom_inset()
            + metrics::TAB_BAR_MARGIN;
        if position.y > limit
            || self
                .overlay_band_top(window)
                .is_some_and(|top| position.y > top)
        {
            return None;
        }
        let bounds = self.recents.bounds.get();
        row_at(
            f32::from(position.x - bounds.origin.x),
            f32::from(position.y - bounds.origin.y),
            self.recents.scrolled(),
            f32::from(bounds.size.width),
            self.recents.rows.len(),
        )
        .filter(|(index, zone)| {
            let row = &self.recents.rows[*index];
            row.callable || *zone == Zone::Info && !row.number.is_empty()
        })
    }

    fn recents_pointer(
        &mut self,
        pointer: Pointer,
        position: Point<Pixels>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.recents_blocked() {
            return;
        }
        match pointer {
            Pointer::Touch(TouchPhase::Started) => {
                self.recents.tap.begin(
                    f32::from(position.x),
                    f32::from(position.y),
                    self.recents.scrolled(),
                );
                if let Some((index, zone)) = self.recents_hit(position, window) {
                    self.recents.touch = Some(Touch { start: position });
                    self.recents.last_pressed = Some((index, zone));
                    self.presses.touch_down(press_id(index, zone));
                    self.recents.suppress_click = false;
                    if zone == Zone::Row {
                        // Holding a row opens the same sheet as its info button.
                        self.recents.long_task =
                            Some(cx.spawn_in(window, async move |this, cx| {
                                cx.background_executor()
                                    .timer(crate::contacts::LONG_PRESS)
                                    .await;
                                this.update_in(cx, |this, window, cx| {
                                    this.recents.long_task = None;
                                    if this.recents.touch.take().is_some() {
                                        this.recents.suppress_click = true;
                                        this.recents.tap.spoil();
                                        rovsti_ui::haptics::perform(
                                            window,
                                            HapticFeedback::LongPress,
                                        );
                                        this.presses.release_all();
                                        this.recents_menu_open(index, window, cx);
                                    }
                                })
                                .ok();
                            }));
                    }
                    cx.notify();
                }
            }
            Pointer::Touch(TouchPhase::Moved) => {
                self.recents
                    .tap
                    .moved(f32::from(position.x), f32::from(position.y));
                if let Some(touch) = self.recents.touch {
                    let dx = f32::from(position.x - touch.start.x);
                    let dy = f32::from(position.y - touch.start.y);
                    if dx.hypot(dy) > TAP_SLOP {
                        // Scrolling: let the row go without calling it.
                        self.recents.touch = None;
                        self.recents.long_task = None;
                        self.presses.release_all();
                        cx.notify();
                    }
                }
            }
            Pointer::Touch(_) => {
                self.recents
                    .tap
                    .finish(f32::from(position.x), f32::from(position.y));
                self.recents.touch = None;
                self.recents.long_task = None;
                self.presses.release_all();
                cx.notify();
            }
            Pointer::Click => {
                // A flick must never call, however the host synthesises taps.
                let tapped = self.recents.tap.allow_click(self.recents.scrolled());
                if std::mem::take(&mut self.recents.suppress_click) || !tapped {
                    return;
                }
                let Some((index, zone)) = self.recents_hit(position, window) else {
                    return;
                };
                // On a touch screen the press was shown on finger-down; the
                // mouse press that follows a tap on lift only performs it.
                self.recents.last_pressed = Some((index, zone));
                self.presses.down(press_id(index, zone));
                match zone {
                    Zone::Row => self.recents_call(index, window, cx),
                    Zone::Info => self.recents_info(index, window, cx),
                }
            }
        }
    }

    /// Desktop preview: tap the first Recents row.
    #[cfg(not(target_os = "android"))]
    pub(crate) fn preview_tap_recent(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.recents.last_pressed = Some((0, Zone::Row));
        self.recents_call(0, window, cx);
    }

    /// Story 6.3: a tap on a row calls its number through the one call path.
    fn recents_call(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(row) = self.recents.rows.get(index) else {
            return;
        };
        if !row.callable {
            return;
        }
        let number = row.number.clone();
        let home = Home {
            tab: crate::RECENTS_TAB,
            index,
            look: Look {
                hue: row.hue,
                monogram: row.monogram.clone(),
                photo: self.caller_photo(&row.photo, cx).map(|(image, _)| image),
            },
        };
        let from = self.recents.avatar_rect(index);
        if self.place_call_from(&number, window, cx)
            && let Some(from) = from
        {
            self.begin_list_flight(home, from, window);
        }
    }

    /// Places `number` and shows the outcome in the notice slot.
    pub(crate) fn place_call_from(
        &mut self,
        number: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> bool {
        self.place_call_from_on(number, None, window, cx)
    }

    /// [`Self::place_call_from`] on a chosen SIM. With "ask every time" and no
    /// choice yet, the chooser opens first and nothing is placed.
    pub(crate) fn place_call_from_on(
        &mut self,
        number: &str,
        account: Option<&str>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> bool {
        if account.is_none() && self.sim_needs_choice(number, cx) {
            self.open_sim_chooser(
                crate::sim_ui::ChooserFor::List,
                number.to_owned().into(),
                None,
                cx,
            );
            return false;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        self.flights.home = None;
        let placement = self
            .backend
            .update(cx, |backend, cx| backend.place_call_on(number, account, cx));
        let default_dialer = self.backend.read(cx).is_default_dialer();
        self.call_note = match placement {
            Placement::Placed | Placement::Requesting if !default_dialer => Some(Notice::System),
            Placement::Placed | Placement::Requesting | Placement::Debounced => None,
            Placement::Blocked => {
                self.last_dialed = number.into();
                Some(Notice::NeedsPhone)
            }
            Placement::Failed => Some(Notice::Failed),
        };
        cx.notify();
        // Only a call the In-Call screen will show gets a flight.
        default_dialer && matches!(placement, Placement::Placed | Placement::Requesting)
    }

    /// A row's number, the name to show for it and its top edge in window
    /// coordinates (for the row menu).
    pub(crate) fn recents_row_facts(
        &self,
        index: usize,
    ) -> Option<(SharedString, SharedString, f32)> {
        let row = self.recents.rows.get(index)?;
        let bounds = self.recents.bounds.get();
        let top = f32::from(bounds.origin.y) + index as f32 * ROW_H - self.recents.scrolled();
        Some((row.number.clone(), row.title.clone(), top))
    }

    fn recents_info(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(row) = self.recents.rows.get(index) else {
            return;
        };
        // A known person opens their Contact sheet (story 8.8); anyone else
        // gets the Number sheet.
        if !row.number.is_empty()
            && let Some(contact) = self.contact_for_number(&row.number, cx)
        {
            self.open_contact_sheet(contact, false, window, cx);
            return;
        }
        if !self.can_open_sheet() {
            return;
        }
        self.recents.number_history = self.history_for(&[row.number.as_ref()], cx);
        self.recents.number = Some(row.clone());
        self.setup_note = None;
        self.sheet_kind = crate::SheetKind::Number;
        self.sheet.open();
        cx.notify();
    }

    /// A pill on a state screen (Recents, Contacts, Favorites) was pressed.
    pub(crate) fn state_pill(&mut self, id: &str, cx: &mut Context<Self>) {
        match id {
            "rc-grant" => self.recents_grant(cx),
            "rc-retry" => self.backend.update(cx, |b, cx| b.retry_call_log(cx)),
            "set-bk-retry" | "set-bk-grant" => {
                self.blocked_tap_state(id, cx);
            }
            other => self.contacts_pill(other, cx),
        }
    }

    /// "Grant access" on the permission state.
    fn recents_grant(&mut self, cx: &mut Context<Self>) {
        let access = self.backend.read(cx).permissions().phone;
        let outcome = if access == Access::PermanentlyDenied {
            backend::Outcome::Declined
        } else {
            backend::request_permission(Group::Phone)
        };
        if outcome == backend::Outcome::Declined {
            backend::open_app_details();
        }
        cx.notify();
    }

    // ---- Rendering ----------------------------------------------------

    /// The tab's body under the title and notice.
    pub(crate) fn recents_body(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let r = &self.recents;
        match r.state {
            State::Ready if r.rows.is_empty() => {
                let preview = !self.backend.read(cx).is_default_dialer();
                state_view(
                    theme,
                    Icon::LucideClock,
                    false,
                    "No recent calls",
                    if preview {
                        "Calls you make and receive show up here. Make Rovsti Phone your default phone app to see everything."
                    } else {
                        "Calls you make and receive show up here."
                    },
                    None,
                    cx,
                )
            }
            State::Ready => self.recents_list(cx),
            State::Denied => {
                let permanent =
                    self.backend.read(cx).permissions().phone == Access::PermanentlyDenied;
                state_view(
                    theme,
                    Icon::LucideTriangleAlert,
                    true,
                    "Allow call log access",
                    if permanent {
                        "Rovsti Phone needs it to show your recent calls. Turn on Phone, Call logs and Contacts under Permissions."
                    } else {
                        "Rovsti Phone needs it to show your recent calls."
                    },
                    Some((
                        "rc-grant",
                        if permanent {
                            "Open settings"
                        } else {
                            "Grant access"
                        },
                        self.presses.value("rc-grant"),
                    )),
                    cx,
                )
            }
            State::Unavailable => state_view(
                theme,
                Icon::LucideTriangleAlert,
                true,
                "Can't load recent calls",
                "Something went wrong reading the call log. It will retry when it changes.",
                Some(("rc-retry", "Try again", self.presses.value("rc-retry"))),
                cx,
            ),
            State::Loading => self.skeleton_rows(theme, window),
        }
    }

    fn recents_list(&self, cx: &mut Context<Self>) -> AnyElement {
        let r = &self.recents;
        let list = uniform_list(
            "recents",
            r.rows.len() + TAIL_ROWS,
            cx.processor(move |this, range: Range<usize>, window, cx| {
                let theme = rovsti_ui::current_theme(window);
                this.want_recent_photos(range.clone(), cx);
                this.recents_rows(theme, range, cx)
            }),
        )
        .track_scroll(&r.scroll)
        .size_full();

        let entity = cx.entity();
        div()
            .relative()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(list)
            .child(pointer_surface(
                Rc::clone(&r.bounds),
                move |pointer, position, window, cx| {
                    entity.update(cx, |this, cx| {
                        this.recents_pointer(pointer, position, window, cx);
                    });
                },
            ))
            .into_any_element()
    }

    /// Starts loading the pictures of the WhatsApp rows in `range`.
    fn want_recent_photos(&mut self, range: Range<usize>, cx: &mut Context<Self>) {
        let rows = Arc::clone(&self.recents.rows);
        for vm in range.filter_map(|i| rows.get(i)) {
            if !vm.photo.is_empty() {
                self.want_caller_photo(&vm.photo, cx);
            }
        }
    }

    /// The rows for `range`; indices past the data are the empty tail.
    fn recents_rows(&self, theme: Theme, range: Range<usize>, cx: &gpui::App) -> Vec<AnyElement> {
        #[cfg(test)]
        ROWS_BUILT.with(|built| built.set(built.get() + range.len()));
        let r = &self.recents;
        let pressed = r.last_pressed;
        let progress = r.fresh_in.progress();
        let live = progress < 1.0 && (r.fresh > 0 || r.inserted > 0);
        range
            .map(|i| match r.rows.get(i) {
                Some(vm) => {
                    let (row_press, info_press) = match pressed {
                        Some((p, Zone::Row)) if p == i => {
                            (self.presses.value(press_key(i, Zone::Row).as_str()), 0.0)
                        }
                        Some((p, Zone::Info)) if p == i => {
                            (0.0, self.presses.value(press_key(i, Zone::Info).as_str()))
                        }
                        _ => (0.0, 0.0),
                    };
                    let fx = self.row_fx(i);
                    let photo = if vm.photo.is_empty() {
                        None
                    } else {
                        self.caller_photo(&vm.photo, cx)
                    };
                    let row = recents_row(
                        theme,
                        i,
                        vm,
                        (row_press, info_press),
                        r.sims.visible(),
                        fx,
                        photo,
                    );
                    if live {
                        live_row(row, i, r.inserted, r.fresh, progress)
                    } else {
                        row
                    }
                }
                None => div().h(px(ROW_H)).into_any_element(),
            })
            .collect()
    }

    fn skeleton_rows(&self, theme: Theme, window: &Window) -> AnyElement {
        if !self.recents.skeleton_on {
            return div().flex_1().into_any_element();
        }
        let colors = theme::colors(theme);
        let count = ((f32::from(window.viewport_size().height) - 170.0) / ROW_H)
            .ceil()
            .clamp(4.0, 14.0) as usize;
        let bar = |w: f32, h: f32| div().w(px(w)).h(px(h)).rounded_full().bg(colors.fill);
        div()
            .flex_1()
            .w_full()
            .overflow_hidden()
            .opacity(self.recents.skeleton_in.progress())
            .children((0..count).map(|_| {
                div()
                    .h(px(ROW_H))
                    .w_full()
                    .flex()
                    .items_center()
                    .gap(px(12.0))
                    .px(metrics::ROW_INSET)
                    .child(div().size(px(AVATAR)).rounded_full().bg(colors.fill))
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap(px(7.0))
                            .child(bar(120.0, 14.0))
                            .child(bar(80.0, 12.0)),
                    )
            }))
            .into_any_element()
    }

    // ---- The Number sheet ------------------------------------------------

    /// Info on a row: the number, "Call" and "Add to contacts" (UX-DR9).
    pub(crate) fn number_sheet_body(
        &self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let row = self.recents.number.as_ref()?;
        let colors = theme::colors(theme);
        let reveal = |index: usize, el: AnyElement| {
            let (alpha, lift) = self.sheet.rise(index);
            div()
                .relative()
                .top(px(lift))
                .when(alpha < 1.0, |d| d.opacity(alpha))
                .child(el)
        };
        let header = div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(4.0))
            .pb(px(8.0))
            .child(avatar_view(
                theme,
                row,
                72.0,
                self.caller_photo(&row.photo, cx),
            ))
            .child(
                div()
                    .pt(px(8.0))
                    .text_size(typography::TITLE2.size)
                    .line_height(typography::TITLE2.line)
                    .font_weight(typography::TITLE2.weight)
                    .text_color(colors.label)
                    .child(row.title.clone()),
            )
            .children((row.named && !row.number.is_empty()).then(|| {
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.secondary_label)
                    .child(row.number.clone())
            }));
        let answer = call_colors(theme).answer;
        let mut children = vec![reveal(0, header.into_any_element())];
        if !row.number.is_empty() {
            let backend = self.backend.read(cx);
            let blocked = backend.is_blocked(&row.number);
            let refusal = backend.block_refusal(&row.number);
            children.push(reveal(
                1,
                crate::actions_ui::action_strip(
                    self,
                    theme,
                    crate::actions_ui::NUMBER_IDS,
                    blocked,
                    false,
                    refusal.is_some() && !blocked,
                    cx,
                ),
            ));
        }
        if row.callable {
            children.push(reveal(
                2,
                sheet_pill(
                    self,
                    "sheet-ncall",
                    Icon::LucidePhone,
                    "Call",
                    answer,
                    gpui::white(),
                    cx,
                )
                .into_any_element(),
            ));
        }
        if row.callable && self.whatsapp.read(cx).is_connected() {
            children.push(reveal(
                3,
                sheet_pill(
                    self,
                    "sheet-nwa",
                    Icon::LucideMessageCircle,
                    "WhatsApp",
                    colors.fill,
                    colors.label,
                    cx,
                )
                .into_any_element(),
            ));
        }
        if let Some(section) = history_section(theme, &self.recents.number_history) {
            children.push(reveal(4, section));
        }
        if !row.named && !row.number.is_empty() {
            children.push(reveal(
                5,
                sheet_pill(
                    self,
                    "sheet-nadd",
                    Icon::LucideUserPlus,
                    "Add to contacts",
                    colors.fill,
                    colors.label,
                    cx,
                )
                .into_any_element(),
            ));
        }
        Some(
            div()
                .flex()
                .flex_col()
                .gap(px(10.0))
                .px(px(18.0))
                .pb(px(18.0))
                .children(children)
                .into_any_element(),
        )
    }

    /// A Number sheet button was pressed.
    pub(crate) fn number_sheet_act(
        &mut self,
        id: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if id.starts_with("sheet-c") {
            return self.contact_sheet_act(id, window, cx);
        }
        let Some(row) = self.recents.number.clone() else {
            return;
        };
        if let Some(action) = crate::actions_ui::StripAction::from_id(id) {
            if !row.number.is_empty() {
                let number = row.number.clone();
                let who = row.title.clone();
                self.run_strip_action(action, number, who, window, cx);
            }
            return;
        }
        match id {
            "sheet-ncall" => {
                self.close_sheet(cx);
                let _ = self.place_call_from(&row.number, window, cx);
            }
            "sheet-nwa" => {
                self.close_sheet(cx);
                self.place_whatsapp(&row.number, cx);
            }
            "sheet-nadd" => {
                let outcome = backend::add_contact(&row.number);
                if outcome == backend::Outcome::Done {
                    self.close_sheet(cx);
                } else {
                    self.setup_note =
                        Some(outcome.why_not("Couldn't open the Contacts app").into());
                }
            }
            _ => {}
        }
        cx.notify();
    }
}

/// A full-width pill in the Number sheet; acts on the mouse press that
/// follows finger-up, with raw touch feedback while held.
pub(crate) fn sheet_pill(
    app: &PhoneApp,
    id: &'static str,
    icon: Icon,
    label: &'static str,
    fill: gpui::Hsla,
    ink: gpui::Hsla,
    cx: &mut Context<PhoneApp>,
) -> impl IntoElement {
    let press = app.presses.value(id).clamp(0.0, 1.0);
    let k = press_scale(press, scale::PRESS);
    div()
        .h(px(56.0))
        .flex()
        .items_center()
        .justify_center()
        .child(
            div()
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .relative()
                .w_full()
                .h(px(52.0 * k))
                .rounded_full()
                .bg(mix(fill, gpui::white(), 0.16 * press))
                .flex()
                .items_center()
                .justify_center()
                .gap(px(10.0))
                .text_size(typography::HEADLINE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(ink)
                .child(svg().path(icon.path()).size(px(20.0)).text_color(ink))
                .child(label)
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id);
                        this.number_sheet_act(id, window, cx);
                    }),
                )
                .child(touch_surface(PhoneApp::touch_listener(cx, Some(id.into())))),
        )
}

/// A row during a live refresh. A new row (above the old first one) fades in
/// while sliding down from just above its place; a row whose call merged into
/// it dips and returns; every older row glides down from where it was. It is
/// all `top` and `opacity` on a fixed-height box, so nothing relayouts.
fn live_row(
    row: AnyElement,
    index: usize,
    inserted: usize,
    fresh: usize,
    progress: f32,
) -> AnyElement {
    let rest = 1.0 - progress;
    let (lift, alpha) = if index < inserted {
        (-ROW_H * rest, progress)
    } else if index < fresh {
        (0.0, 0.4 + 0.6 * progress)
    } else {
        (-(inserted as f32) * ROW_H * rest, 1.0)
    };
    div()
        .relative()
        .top(px(lift))
        .h(px(ROW_H))
        .w_full()
        .opacity(alpha)
        .child(row)
        .into_any_element()
}

/// One call in the "Recent calls" list of a sheet, ready to draw.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct HistoryVm {
    title: SharedString,
    detail: SharedString,
    icon: Icon,
    missed: bool,
    whatsapp: bool,
}

impl HistoryVm {
    /// Built once when a sheet opens, never per frame.
    fn new(line: &recents::HistoryLine, now_ms: i64, locale: recents::LocaleTime) -> Self {
        let (title, detail) = recents::history_text(line, now_ms, locale);
        let direction = Direction::of(line.call_type);
        Self {
            title: title.into(),
            detail: detail.into(),
            icon: direction_icon(direction),
            missed: direction == Direction::Missed,
            whatsapp: line.whatsapp,
        }
    }

    fn aria(&self) -> SharedString {
        format!("{}, {}", self.title, self.detail).into()
    }
}

/// Most calls a sheet lists.
const HISTORY_LIMIT: usize = 10;
/// Fixed height of one history line.
const HISTORY_ROW_H: f32 = 44.0;

impl PhoneApp {
    /// The newest calls (cellular and WhatsApp) with any of `numbers`. Empty
    /// until the call log is ready.
    pub(crate) fn history_for(&self, numbers: &[&str], cx: &gpui::App) -> Arc<[HistoryVm]> {
        let Load::Ready(log) = self.backend.read(cx).call_log() else {
            return Arc::from(Vec::new());
        };
        let extras = self.wa_call.read(cx).recents_extras();
        let now = backend::now_ms();
        recents::history(&log.source, &extras, numbers, HISTORY_LIMIT)
            .iter()
            .map(|line| HistoryVm::new(line, now, log.locale))
            .collect()
    }
}

/// The "Recent calls" block of a sheet; `None` when there are no calls.
pub(crate) fn history_section(theme: Theme, lines: &[HistoryVm]) -> Option<AnyElement> {
    if lines.is_empty() {
        return None;
    }
    let colors = theme::colors(theme);
    let danger = call_colors(theme).danger;
    let rows = lines.iter().enumerate().map(|(i, line)| {
        let ink = if line.missed { danger } else { colors.label };
        div()
            .id(SharedString::from(format!("sheet-hist-{i}")))
            .aria_label(line.aria())
            .h(px(HISTORY_ROW_H))
            .w_full()
            .flex()
            .items_center()
            .gap(px(10.0))
            .px(px(16.0))
            .child(if line.whatsapp {
                whatsapp_badge(theme)
            } else {
                svg()
                    .path(line.icon.path())
                    .size(px(16.0))
                    .flex_none()
                    .text_color(ink)
                    .into_any_element()
            })
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .truncate()
                    .text_size(typography::BODY.size)
                    .text_color(ink)
                    .child(line.title.clone()),
            )
            .child(
                div()
                    .flex_none()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(line.detail.clone()),
            )
    });
    Some(
        div()
            .flex()
            .flex_col()
            .pt(px(6.0))
            .child(
                div()
                    .px(px(16.0))
                    .pb(px(4.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child("Recent calls"),
            )
            .child(
                div()
                    .flex()
                    .flex_col()
                    .rounded(px(22.0))
                    .overflow_hidden()
                    .bg(gpui::Hsla {
                        a: 0.5,
                        ..colors.fill
                    })
                    .children(rows),
            )
            .into_any_element(),
    )
}

/// The icon in a row's subtitle.
fn direction_icon(direction: Direction) -> Icon {
    match direction {
        Direction::Incoming => Icon::LucidePhoneIncoming,
        Direction::Outgoing => Icon::LucidePhoneOutgoing,
        Direction::Missed => Icon::LucidePhoneMissed,
        Direction::Declined => Icon::LucidePhoneOff,
        Direction::Voicemail => Icon::LucideVoicemail,
        Direction::Unknown => Icon::LucidePhone,
    }
}

/// A round avatar: the contact's hue pair as a diagonal gradient with the
/// monogram, or the person glyph on a neutral fill for unknown numbers;
/// `photo` (a WhatsApp row's picture and its opacity) fades in over it.
pub(crate) fn avatar_view(
    theme: Theme,
    row: &RowVm,
    size: f32,
    photo: Option<(Arc<RenderImage>, f32)>,
) -> gpui::Div {
    let covered = |base: gpui::Div, inset: f32| {
        base.relative().children(photo.map(|(image, opacity)| {
            gpui::img(image)
                .absolute()
                .top(px(-inset))
                .left(px(-inset))
                .size(px(size))
                .rounded_full()
                .object_fit(ObjectFit::Cover)
                .opacity(opacity)
        }))
    };
    let colors = theme::colors(theme);
    let base = div()
        .size(px(size))
        .flex_none()
        .rounded_full()
        .flex()
        .items_center()
        .justify_center();
    if !row.named {
        return covered(
            base.bg(colors.fill).child(
                svg()
                    .path(Icon::LucideUser.path())
                    .size(px(size * 0.5))
                    .text_color(colors.secondary_label),
            ),
            0.0,
        );
    }
    let (top, bottom) = theme::hue_pair(theme, row.hue);
    let base = base
        .border(px(1.5))
        .border_color(gpui::Hsla {
            a: 0.3,
            ..gpui::white()
        })
        .bg(linear_gradient(
            135.0,
            linear_color_stop(top, 0.0),
            linear_color_stop(bottom, 1.0),
        ));
    let base = match &row.monogram {
        Some(letter) => base
            .text_size(px(size * 0.42))
            .font_weight(typography::HEADLINE.weight)
            .text_color(gpui::white())
            .child(letter.clone()),
        None => base.child(
            svg()
                .path(Icon::LucideUser.path())
                .size(px(size * 0.5))
                .text_color(gpui::white()),
        ),
    };
    // The 1.5 px border is inside the box: the photo covers it.
    covered(base, 1.5)
}

/// Where a WhatsApp row's picture comes from: the matched contact's photo,
/// then the WhatsApp picture of its number.
fn wa_row_photo(index: Option<&rovsti_core::search::ContactIndex>, number: &str) -> CallerPhoto {
    let contact = index.and_then(|i| i.match_number(number));
    CallerPhoto {
        contact: contact.and_then(|c| {
            c.photo_uri
                .as_deref()
                .map(|uri| (c.id, SharedString::from(uri.to_owned())))
        }),
        wa_jid: rovsti_wa_proto::user_jid(number)
            .ok()
            .map(SharedString::from),
    }
}

/// The small "WhatsApp" pill in front of a WhatsApp call's subtitle.
fn whatsapp_badge(theme: Theme) -> AnyElement {
    let green = call_colors(theme).answer;
    div()
        .flex_none()
        .px(px(5.0))
        .rounded_full()
        .bg(gpui::Hsla { a: 0.16, ..green })
        .text_size(typography::CAPTION2.size)
        .font_weight(typography::CAPTION2.weight)
        .text_color(green)
        .child("WhatsApp")
        .into_any_element()
}

/// The SIM slot: a 36 px column holding the dot and the short label (or nothing).
fn sim_slot_el(theme: Theme, vm: &RowVm) -> AnyElement {
    let colors = theme::colors(theme);
    let slot = div().flex_none().w(px(36.0)).h(px(20.0)).overflow_hidden();
    match &vm.sim {
        Some(badge) => slot
            .flex()
            .items_center()
            .justify_end()
            .gap(px(3.0))
            .child(
                div()
                    .flex_none()
                    .size(px(6.0))
                    .rounded_full()
                    .bg(crate::sim_ui::chip_dot(theme, badge.argb)),
            )
            .child(
                div()
                    .min_w_0()
                    .truncate()
                    .text_size(typography::CAPTION2.size)
                    .font_weight(typography::CAPTION2.weight)
                    .text_color(colors.secondary_label)
                    .child(badge.label.clone()),
            )
            .into_any_element(),
        None => slot.into_any_element(),
    }
}

/// One Recents row: a purely visual element; the list's pointer surface does
/// the touching.
fn recents_row(
    theme: Theme,
    index: usize,
    vm: &RowVm,
    (row_press, info_press): (f32, f32),
    sim_slot: bool,
    fx: Option<crate::block_ui::RowFx>,
    photo: Option<(Arc<RenderImage>, f32)>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let danger = call_colors(theme).danger;
    let ink = if vm.missed { danger } else { colors.label };
    let held = row_press.clamp(0.0, 1.0);
    let info_held = info_press.clamp(0.0, 1.0);
    let sub_ink = if vm.missed {
        danger
    } else {
        colors.secondary_label
    };

    div()
        .id(index)
        .role(Role::Button)
        .aria_label(vm.aria.clone())
        .relative()
        .h(px(ROW_H))
        .w_full()
        .flex()
        .items_center()
        .opacity(if vm.callable { 1.0 } else { 0.6 } * fx.map_or(1.0, |fx| fx.alpha))
        // Press tint: inset, rounded, springs in with the finger.
        .when(held > 0.002, |row| {
            row.child(
                div()
                    .absolute()
                    .top(px(2.0))
                    .bottom(px(2.0))
                    .left(px(6.0))
                    .right(px(6.0))
                    .rounded(px(16.0))
                    .bg(gpui::Hsla {
                        a: 0.9 * held,
                        ..colors.fill
                    }),
            )
        })
        .child(
            div()
                .flex_1()
                .min_w_0()
                .h_full()
                .flex()
                .items_center()
                .gap(px(12.0))
                .pl(metrics::ROW_INSET)
                .pr(px(4.0))
                .child(match fx {
                    // S27: the avatar greys and a ring with a slash settles over it.
                    Some(fx) if fx.ring > 0.0 => div()
                        .relative()
                        .flex_none()
                        .size(px(AVATAR))
                        .child(avatar_view(theme, vm, AVATAR, photo))
                        .child(
                            div()
                                .absolute()
                                .inset_0()
                                .rounded_full()
                                .bg(gpui::Hsla {
                                    a: 0.85 * fx.ring,
                                    ..colors.fill
                                })
                                .flex()
                                .items_center()
                                .justify_center()
                                .child(
                                    svg()
                                        .path(Icon::LucideBan.path())
                                        .size(px(AVATAR * (1.4 - 0.4 * fx.ring)))
                                        .opacity(fx.ring)
                                        .text_color(PhoneApp::s27_ink(theme)),
                                ),
                        )
                        .into_any_element(),
                    _ => avatar_view(theme, vm, AVATAR, photo).into_any_element(),
                })
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .flex()
                        .flex_col()
                        .child(
                            div()
                                .flex()
                                .items_baseline()
                                .gap(px(5.0))
                                .text_size(typography::HEADLINE.size)
                                .text_color(ink)
                                .child(
                                    div()
                                        .min_w_0()
                                        .truncate()
                                        .font_weight(typography::HEADLINE.weight)
                                        .child(vm.title.clone()),
                                )
                                .children(vm.count.clone().map(|c| {
                                    div()
                                        .flex_none()
                                        .font_weight(gpui::FontWeight::NORMAL)
                                        .child(c)
                                })),
                        )
                        .child(
                            div()
                                .flex()
                                .items_center()
                                .gap(px(5.0))
                                .text_size(typography::SUBHEADLINE.size)
                                .text_color(sub_ink)
                                .child(
                                    svg()
                                        .path(direction_icon(vm.direction).path())
                                        .size(px(16.0))
                                        .flex_none()
                                        .text_color(sub_ink),
                                )
                                .children(vm.whatsapp.then(|| whatsapp_badge(theme)))
                                .child(
                                    div()
                                        .min_w_0()
                                        .flex_1()
                                        .truncate()
                                        .child(vm.subtitle.clone()),
                                )
                                // Reserved 36 px at the trailing end: only with two SIMs, and empty
                                // when the log's key matches no account.
                                .children(sim_slot.then(|| sim_slot_el(theme, vm))),
                        ),
                )
                .child(
                    div()
                        .flex_none()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.tertiary_label)
                        .child(vm.time.clone()),
                ),
        )
        .child(
            div()
                .id(("info", index))
                .role(Role::Button)
                .aria_label("Details")
                .flex_none()
                .w(px(INFO_W))
                .h_full()
                .flex()
                .items_center()
                .justify_center()
                .children((vm.callable || !vm.number.is_empty()).then(|| {
                    div()
                        .size(px(34.0 * press_scale(info_held, scale::PRESS_CARD)))
                        .rounded_full()
                        .flex()
                        .items_center()
                        .justify_center()
                        .bg(gpui::Hsla {
                            a: 0.9 * info_held,
                            ..colors.fill
                        })
                        .child(
                            svg()
                                .path(Icon::LucideInfo.path())
                                .size(px(20.0))
                                .text_color(colors.secondary_label),
                        )
                })),
        )
        // The hairline starts under the text, not under the avatar.
        .child(
            div()
                .absolute()
                .bottom_0()
                .left(px(68.0))
                .right_0()
                .h(px(0.5))
                .bg(colors.separator),
        )
        .into_any_element()
}

/// Icon, title, body and an optional pill `(id, label, press)`, centred in the
/// free space. The pill acts on finger-up through [`PhoneApp::state_pill`].
pub(crate) fn state_view(
    theme: Theme,
    icon: Icon,
    warn: bool,
    title: &'static str,
    body: &'static str,
    pill: Option<(&'static str, &'static str, f32)>,
    cx: &mut Context<PhoneApp>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let tint = if warn {
        call_colors(theme).warning
    } else {
        colors.secondary_label
    };
    div()
        .flex_1()
        .w_full()
        .flex()
        .flex_col()
        .items_center()
        .gap(px(8.0))
        .px(px(32.0))
        .pt(px(56.0))
        .child(
            div()
                .size(px(72.0))
                .rounded_full()
                .bg(gpui::Hsla {
                    a: 0.7,
                    ..colors.fill
                })
                .flex()
                .items_center()
                .justify_center()
                .mb(px(8.0))
                .child(svg().path(icon.path()).size(px(30.0)).text_color(tint)),
        )
        .child(
            div()
                .text_size(typography::TITLE3.size)
                .font_weight(typography::TITLE3.weight)
                .text_color(colors.label)
                .child(title),
        )
        .child(
            div()
                .max_w(px(300.0))
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .text_center()
                .child(body),
        )
        .children(pill.map(|(id, label, press)| {
            let press = press.clamp(0.0, 1.0);
            div()
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .relative()
                .mt(px(12.0))
                .child(
                    glass_pill(theme, label)
                        .text_color(colors.accent)
                        .opacity(1.0 - 0.25 * press),
                )
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, _, cx| {
                        this.presses.down(id);
                        this.state_pill(id, cx);
                    }),
                )
                .child(touch_surface(PhoneApp::touch_listener(cx, Some(id.into()))))
        }))
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rows_are_found_by_arithmetic() {
        // Unscrolled: y 0..58 is row 0, 58..116 row 1.
        assert_eq!(row_at(100.0, 10.0, 0.0, 400.0, 10), Some((0, Zone::Row)));
        assert_eq!(row_at(100.0, 58.0, 0.0, 400.0, 10), Some((1, Zone::Row)));
        // Scrolled by 120 px: the list's top edge is inside row 2.
        assert_eq!(row_at(100.0, 0.0, 120.0, 400.0, 10), Some((2, Zone::Row)));
        assert_eq!(row_at(100.0, 57.0, 120.0, 400.0, 10), Some((3, Zone::Row)));
    }

    #[test]
    fn the_right_edge_is_the_info_target() {
        assert_eq!(row_at(380.0, 10.0, 0.0, 400.0, 3), Some((0, Zone::Info)));
        assert_eq!(row_at(340.0, 10.0, 0.0, 400.0, 3), Some((0, Zone::Row)));
    }

    #[test]
    fn outside_the_data_is_nothing() {
        assert_eq!(row_at(100.0, -1.0, 0.0, 400.0, 3), None);
        assert_eq!(row_at(100.0, 3.0 * ROW_H, 0.0, 400.0, 3), None);
        assert_eq!(row_at(500.0, 10.0, 0.0, 400.0, 3), None);
        assert_eq!(row_at(100.0, 10.0, 0.0, 400.0, 0), None);
    }

    fn line(
        call_type: rovsti_core::call_log::CallType,
        whatsapp: bool,
        duration_s: u64,
    ) -> recents::HistoryLine {
        recents::HistoryLine {
            call_type,
            whatsapp,
            date_ms: 0,
            duration_s,
        }
    }

    #[test]
    fn history_vm_marks_missed_and_keeps_the_duration_out_of_missed_calls() {
        use rovsti_core::call_log::CallType;
        let now = 3_600_000;
        let missed = HistoryVm::new(&line(CallType::Missed, true, 0), now, Default::default());
        assert!(missed.missed && missed.whatsapp);
        assert_eq!(missed.icon, Icon::LucidePhoneMissed);
        assert!(!missed.detail.contains('\u{b7}'));
        assert_eq!(
            missed.aria().as_ref(),
            format!("{}, {}", missed.title, missed.detail)
        );
        let answered = HistoryVm::new(
            &line(CallType::Incoming, false, 65),
            now,
            Default::default(),
        );
        assert!(!answered.missed && !answered.whatsapp);
        assert!(answered.detail.ends_with("1:05"), "{}", answered.detail);
    }
}
