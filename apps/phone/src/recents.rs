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
    cell::{Cell, RefCell},
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
    blocklist,
    call_log::RecentsRow,
    call_log_query::{self, Query, RecentsFilter},
    call_notes::{self, Notes},
    recents::{self, Direction, RowView},
    sim::SimInfo,
    wire::EmergencyList,
};
use rovsti_ui::{
    Icon,
    components::{flat_glass, glass_pill},
    glass::{GlassSpec, glass},
    moments::Fade,
    motion::{Entrance, press_scale},
    motion_tokens::scale,
    theme::{self, call_colors},
    tokens::{Theme, metrics, mix, shadow, typography},
    touch::{Pointer, TAP_SLOP, TapGuard, pointer_surface, touch_surface},
};

use crate::{
    PhoneApp, RECENTS_TAB,
    backend::{self, Access, CallLog, Group, Load, Placement},
    field::{SearchField, input_layer, on_backspace},
    flight::{Home, Look},
    notice::Notice,
    wa_peer::CallerPhoto,
};

/// Block and Share work on callable numbers only: dimmed and inert with none.
pub(crate) fn select_tools_enabled(numbers: usize) -> bool {
    numbers > 0
}

/// Height of the search field under the title.
const FIELD_H: f32 = 44.0;
/// Height of the filter chip capsule.
const CHIP_H: f32 = 36.0;
/// Height of the sticky day capsule.
const CAP_H: f32 = 24.0;
/// The capsule shows once the list is scrolled this far.
const CAP_MIN_SCROLL: f32 = 4.0;

/// How far the sticky capsule moves (px, 0 or negative): the next section's
/// first row at `next_start_px` pushes it up once it is within `h` of the top
/// edge (`first_visible_px` is the scroll offset).
pub(crate) fn capsule_offset(first_visible_px: f32, next_start_px: Option<f32>, h: f32) -> f32 {
    match next_start_px {
        Some(next) => -(h - (next - first_visible_px)).clamp(0.0, h),
        None => 0.0,
    }
}

/// The capsule's label for one (first row, rebuild revision), and the section
/// starts of that revision, so a frame does no string work unless the row changes.
#[derive(Default)]
struct CapCache {
    rev: u64,
    starts_rev: Option<u64>,
    starts: Vec<usize>,
    first: Option<usize>,
    label: Option<SharedString>,
}

/// The capsule's fade state: wanted on or off, and the last frame's time.
struct CapFade {
    fade: Fade,
    on: bool,
    at: Option<Instant>,
}

/// Select mode's pills and the floating bar.
pub(crate) const SEL_ALL: &str = "rc-sel-all";
pub(crate) const SEL_DONE: &str = "rc-sel-done";
pub(crate) const SEL_DEL: &str = "rc-sel-del";
pub(crate) const SEL_BLOCK: &str = "rc-sel-block";
pub(crate) const SEL_SHARE: &str = "rc-sel-share";
/// The block confirm sheet's buttons.
pub(crate) const BS_YES: &str = "sheet-bs-yes";
pub(crate) const BS_NO: &str = "sheet-bs-no";
/// Side of the Block and Share buttons in the select bar.
const SEL_TOOL: f32 = 36.0;
const SELECT_BAR_H: f32 = 56.0;
/// Gap between the select bar and the tab bar.
const SELECT_BAR_GAP: f32 = 6.0;
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

/// Whether tapping a row calls back over WhatsApp: the row came from a
/// WhatsApp call and the account is linked (otherwise the carrier is used).
pub(crate) fn call_back_on_whatsapp(row_whatsapp: bool, connected: bool) -> bool {
    row_whatsapp && connected
}

/// One row, ready to draw.
#[derive(Clone)]
pub(crate) struct RowVm {
    pub(crate) title: SharedString,
    count: Option<SharedString>,
    subtitle: SharedString,
    time: SharedString,
    aria: SharedString,
    direction: Direction,
    missed: bool,
    callable: bool,
    pub(crate) number: SharedString,
    hue: Option<usize>,
    monogram: Option<SharedString>,
    named: bool,
    /// A WhatsApp call: the row shows a WhatsApp badge.
    whatsapp: bool,
    /// A WhatsApp row's picture: the contact's photo, then WhatsApp's.
    photo: CallerPhoto,
    /// The call-log and WhatsApp entries this row stands for (Delete acts on them).
    refs: Arc<[rovsti_core::call_log::EntryRef]>,
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

/// Chip label size; 11.5 px so six chips (with "WhatsApp") fit 360 dp.
const CHIP_TEXT: f32 = 11.5;

/// Whether the visible list reads call notes (a search text or the Notes chip).
fn notes_affect_list(query: &Query) -> bool {
    !query.text.trim().is_empty() || query.filter == RecentsFilter::WithNotes
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
            refs: Arc::from(Vec::new()),
        }
    }

    /// The subtitle of a row: an unnamed row from abroad gains its country
    /// (`Incoming \u{b7} Germany`); contacts, home numbers and private numbers keep it.
    fn subtitle_with_country(
        subtitle: String,
        named: bool,
        number: &str,
        home_cc: Option<&str>,
    ) -> String {
        if named {
            return subtitle;
        }
        let Some(country) = rovsti_core::calling_code::country_line(number, home_cc) else {
            return subtitle;
        };
        crate::details_ui::header_caption(Some(&subtitle), Some(&country)).unwrap_or(subtitle)
    }

    fn build(v: RowView, sims: &SimInfo, home_cc: Option<&str>) -> Self {
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
            subtitle: Self::subtitle_with_country(v.subtitle, v.named, &v.number, home_cc).into(),
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
            refs: Arc::from(Vec::new()),
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
    /// The merged call-log rows `rows` was built from (what the query reads).
    merged: Arc<[RecentsRow]>,
    /// Search text and filter chip (the filter resets on launch).
    query: Query,
    /// Indices into `rows` that pass `query`, in order. The list, hit tests and
    /// every row lookup go through it; rebuilt on a new snapshot or a query
    /// change, never per frame.
    visible: Vec<usize>,
    /// The call notes the search reads, and the store revision they came from.
    notes: Notes,
    notes_rev: u64,
    /// Rows (indices into `rows`) that pass only through their note, with its preview.
    note_hits: std::collections::HashMap<usize, SharedString>,
    /// The search field under the title.
    pub(crate) field: gpui::Entity<SearchField>,
    seen_edits: u64,
    field_text: SharedString,
    /// The field has focus (caret drawn).
    pub(crate) typing: bool,
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
    /// The Number sheet's "Recent calls" shows a delete button on each line.
    pub(crate) number_history_edit: bool,
    /// The Number sheet was opened from Blocked calls: it lists and deletes blocked calls.
    pub(crate) number_blocked: bool,
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
    /// Select mode: the rows chosen so far (`None` = normal mode).
    pub(crate) select: Option<call_log_query::Selection>,
    /// Bumped whenever `visible` is recomputed (the capsule cache key).
    rev: u64,
    cap_cache: RefCell<CapCache>,
    cap_fade: RefCell<CapFade>,
}

impl RecentsView {
    pub(crate) fn new(cx: &mut gpui::App) -> Self {
        Self {
            scroll: UniformListScrollHandle::new(),
            bounds: Rc::new(Cell::new(Bounds::default())),
            state: State::Loading,
            rows: Arc::from(Vec::new()),
            merged: Arc::from(Vec::new()),
            query: Query::default(),
            visible: Vec::new(),
            notes: Notes::default(),
            notes_rev: 0,
            note_hits: std::collections::HashMap::new(),
            field: cx.new(SearchField::new),
            seen_edits: 0,
            field_text: SharedString::default(),
            typing: false,
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
            number_history_edit: false,
            number_blocked: false,
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
            select: None,
            rev: 0,
            cap_cache: RefCell::new(CapCache::default()),
            cap_fade: RefCell::new(CapFade {
                fade: Fade::default(),
                on: false,
                at: None,
            }),
        }
    }

    pub(crate) fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.skeleton_in.tick(dt);
        moving |= self.fresh_in.tick(dt);
        moving
    }

    /// Rows in the list (after the search and the filter).
    #[cfg(test)]
    pub(crate) fn row_count(&self) -> usize {
        self.visible.len()
    }

    /// The row shown at list position `index`.
    pub(crate) fn row(&self, index: usize) -> Option<&RowVm> {
        self.rows.get(*self.visible.get(index)?)
    }

    /// The query is not the default one (rows are narrowed).
    pub(crate) fn narrowed(&self) -> bool {
        self.query != Query::default()
    }

    /// Recomputes `visible` from the current rows and query.
    fn reveal(&mut self) {
        self.rev = self.rev.wrapping_add(1);
        self.visible = call_log_query::apply_with_notes(&self.merged, &self.query, &self.notes);
        self.note_hits = note_only_hits(&self.merged, &self.visible, &self.query, &self.notes);
    }

    /// The note preview of list position `index`, when the row matched only by its note.
    pub(crate) fn note_hit(&self, index: usize) -> Option<&SharedString> {
        self.note_hits.get(self.visible.get(index)?)
    }

    /// Applies a new query: recomputes the visible rows and puts the list back
    /// at the top. False when nothing changed.
    pub(crate) fn set_query(&mut self, query: Query) -> bool {
        if query == self.query {
            return false;
        }
        self.query = query;
        self.reveal();
        let handle = self.scroll.0.borrow().base_handle.clone();
        handle.set_offset(gpui::point(px(0.0), px(0.0)));
        self.scroll_to_top();
        true
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

    /// The sticky day capsule: its label and its slide (px, 0 or negative), or
    /// `None` when the list is not far enough down or has nothing to show.
    fn capsule(&self, scrolled: f32) -> Option<(SharedString, f32)> {
        if self.visible.is_empty() || self.select.is_some() || scrolled < CAP_MIN_SCROLL {
            return None;
        }
        let log = self.log.as_ref()?;
        let tz = log.locale.tz_offset_s;
        let first = (scrolled / ROW_H).floor() as usize;
        let mut cache = self.cap_cache.borrow_mut();
        if cache.starts_rev != Some(self.rev) {
            cache.starts = recents::section_starts(&self.merged, &self.visible, tz);
            cache.starts_rev = Some(self.rev);
        }
        if cache.rev != self.rev || cache.first != Some(first) {
            cache.rev = self.rev;
            cache.first = Some(first);
            cache.label =
                recents::section_for(&self.merged, &self.visible, first, backend::now_ms(), tz)
                    .map(SharedString::from);
        }
        let next = cache
            .starts
            .get(cache.starts.partition_point(|&s| s <= first));
        let offset = capsule_offset(scrolled, next.map(|&n| n as f32 * ROW_H), CAP_H);
        Some((cache.label.clone()?, offset))
    }

    /// Advances the capsule's fade for this frame and returns its opacity.
    /// Under reduced motion it snaps. The flag is true while it plays.
    fn capsule_opacity(&self, wanted: bool, reduced: bool) -> (f32, bool) {
        let mut f = self.cap_fade.borrow_mut();
        let now = Instant::now();
        let mut dt = f.at.map_or(Duration::ZERO, |t| now.duration_since(t));
        f.at = Some(now);
        if wanted != f.on {
            f.on = wanted;
            dt = Duration::ZERO;
            if !reduced {
                f.fade.restart();
            }
        }
        let playing = !reduced && f.fade.tick(dt);
        let v = if reduced { 1.0 } else { f.fade.value() };
        (if f.on { v } else { 1.0 - v }, playing)
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

/// Rows in `visible` that fail the plain name/number match: they passed through
/// a note, so the row shows `Note: <preview>`.
fn note_only_hits(
    merged: &[RecentsRow],
    visible: &[usize],
    query: &Query,
    notes: &Notes,
) -> std::collections::HashMap<usize, SharedString> {
    let mut hits = std::collections::HashMap::new();
    let text = query.text.trim();
    if text.chars().count() < 2 {
        return hits;
    }
    let folded = rovsti_core::search::fold(text);
    for &i in visible {
        let row = &merged[i];
        if call_log_query::matches(row, &query.text) {
            continue;
        }
        if let Some(note) = notes.matching(&row.number_raw, row.first_ms, row.last_ms, &folded) {
            hits.insert(
                i,
                SharedString::from(format!("Note: {}", call_notes::preview(&note.text))),
            );
        }
    }
    hits
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
        let merged = Arc::clone(&self.recents.merged);
        if let Some(select) = self.recents.select.as_mut() {
            select.retain_present(&merged);
        }
        self.sync_missed_label(cx);
        self.arm_view_timer(cx);
        self.sync_wa_names(cx);
        self.sync_frequent(cx);
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
        // Positions in a narrowed list do not match the log's: no live refresh.
        if r.narrowed() {
            return;
        }
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

    /// The call notes changed: the search reads the new ones.
    pub(crate) fn sync_recents_notes(&mut self, cx: &mut Context<Self>) {
        if self.recents.notes_rev == self.notes.rev() {
            return;
        }
        self.recents.notes = self.notes.snapshot();
        self.recents.notes_rev = self.notes.rev();
        if !notes_affect_list(&self.recents.query) {
            return;
        }
        self.recents.reveal();
        cx.notify();
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
        self.refresh_sheet_history(cx);
        cx.notify();
    }

    /// The call log changed under an open sheet: its "Recent calls" follow.
    pub(crate) fn refresh_sheet_history(&mut self, cx: &gpui::App) {
        if self.sheet.is_visible()
            && self.sheet_kind == crate::SheetKind::Number
            && let Some(number) = self.recents.number.as_ref().map(|r| r.number.clone())
        {
            self.recents.number_history = if self.recents.number_blocked {
                self.blocked_history_for(&[number.as_ref()], cx)
            } else {
                self.history_for(&[number.as_ref()], cx)
            };
            if self.recents.number_history.is_empty() {
                self.recents.number_history_edit = false;
            }
        }
        if let Some(sheet) = &self.contact_sheet {
            let numbers: Vec<&str> = sheet
                .contact
                .numbers
                .iter()
                .map(|n| n.raw.as_str())
                .collect();
            let history = self.history_for(&numbers, cx);
            if let Some(sheet) = &mut self.contact_sheet {
                if history.is_empty() {
                    sheet.history_edit = false;
                }
                sheet.history = history;
            }
        }
    }

    fn recents_failed(&mut self, state: State) {
        self.recents.state = state;
        self.recents.rows = Arc::from(Vec::new());
        self.recents.merged = Arc::from(Vec::new());
        self.recents.visible.clear();
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
            .zip(merged.iter())
            .map(|(mut v, merged_row)| {
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
                let mut row = RowVm::build(v, &sims, log.home_cc.as_deref());
                row.photo = photo;
                row.refs = merged_row.refs.as_slice().into();
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
        self.recents.merged = merged.into();
        self.recents.notes = self.notes.snapshot();
        self.recents.notes_rev = self.notes.rev();
        self.recents.reveal();
        self.recents.built_at = Some(Instant::now());
        self.recents.log = Some(log);
        self.recents.last_pressed = None;
        self.recents.inserted = 0;
        self.recents.fresh = 0;
        self.refresh_sheet_history(cx);
    }

    // ---- Tab lifecycle ------------------------------------------------

    /// The selected tab changed (or the app was reopened on one).
    pub(crate) fn recents_tab_changed(&mut self, cx: &mut Context<Self>) {
        self.recents.view_task = None;
        self.close_field_menu();
        self.recents.marked = false;
        self.recents.touch = None;
        self.call_note = None;
        if self.tabs.selected() != RECENTS_TAB {
            self.recents.select = None;
        }
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
        if !active {
            // A speed-dial pick belongs to this visit: coming back later, a
            // contact's numbers call again instead of assigning a digit.
            self.speed_dial.pick = None;
        }
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
        let limit = window.viewport_size().height
            - rovsti_ui::tab_bar::content_bottom_inset_with(crate::safe_bottom(window))
            + metrics::TAB_BAR_MARGIN;
        if position.y > limit
            || self
                .overlay_band_top(window)
                .is_some_and(|top| position.y > top)
        {
            return None;
        }
        let selecting = self.recents.select.is_some();
        // The select bar floats over the bottom of the list.
        if selecting
            && f32::from(position.y)
                > f32::from(window.viewport_size().height) - self.select_bar_inset()
        {
            return None;
        }
        let bounds = self.recents.bounds.get();
        row_at(
            f32::from(position.x - bounds.origin.x),
            f32::from(position.y - bounds.origin.y),
            self.recents.scrolled(),
            f32::from(bounds.size.width),
            self.recents.visible.len(),
        )
        .filter(|(index, zone)| {
            // Select mode takes every row, callable or not.
            selecting
                || self.recents.row(*index).is_some_and(|row| {
                    row.callable || *zone == Zone::Info && !row.number.is_empty()
                })
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
                    if zone == Zone::Row && self.recents.select.is_none() {
                        // Holding a row opens its quick-action menu (copy, message, block).
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
                if self.recents.select.is_some() {
                    self.recents_toggle_select(index, window, cx);
                    return;
                }
                match zone {
                    Zone::Row => self.recents_call(index, window, cx),
                    Zone::Info => self.recents_info(index, window, cx),
                }
            }
        }
    }

    // ---- Select mode ----------------------------------------------------

    /// Enters select mode with the row at list position `index` chosen.
    pub(crate) fn recents_enter_select(
        &mut self,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        window.blur();
        self.recents.select = Some(call_log_query::Selection::default());
        self.recents_toggle_select(index, window, cx);
    }

    /// Flips the row at list position `index` (a light haptic).
    fn recents_toggle_select(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(&at) = self.recents.visible.get(index) else {
            return;
        };
        let Some(row) = self.recents.merged.get(at) else {
            return;
        };
        if let Some(select) = self.recents.select.as_mut() {
            select.toggle(row);
            rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
            cx.notify();
        }
    }

    /// Leaves select mode and forgets the choice.
    pub(crate) fn recents_leave_select(&mut self, cx: &mut Context<Self>) {
        if self.recents.select.take().is_some() {
            cx.notify();
        }
    }

    /// Whether every row now listed is chosen.
    fn recents_all_selected(&self) -> bool {
        let r = &self.recents;
        r.select.as_ref().is_some_and(|sel| {
            !r.visible.is_empty()
                && r.visible
                    .iter()
                    .all(|&i| r.merged.get(i).is_some_and(|row| sel.contains(row)))
        })
    }

    /// Select all / Deselect all, Done and Delete of the select bar.
    pub(crate) fn recents_select_act(
        &mut self,
        id: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match id {
            SEL_ALL => {
                let all = self.recents_all_selected();
                let (merged, visible) = (
                    Arc::clone(&self.recents.merged),
                    self.recents.visible.clone(),
                );
                if let Some(sel) = self.recents.select.as_mut() {
                    if all {
                        sel.clear();
                    } else {
                        sel.select_all(&merged, &visible);
                    }
                    rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
                }
            }
            SEL_DONE => {
                rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
                self.recents.select = None;
            }
            SEL_DEL => {
                let merged = Arc::clone(&self.recents.merged);
                let scope = self.recents.select.as_ref().and_then(|sel| {
                    (!sel.is_empty()).then(|| crate::history_ui::DeleteScope::Selected {
                        refs: sel.refs(&merged).into(),
                        rows: sel.len(),
                        calls: sel.call_count(&merged),
                    })
                });
                if let Some(scope) = scope {
                    self.open_confirm_delete(scope, cx);
                }
            }
            SEL_SHARE => self.recents_select_share(cx),
            SEL_BLOCK => self.recents_select_block_ask(window, cx),
            _ => {}
        }
        cx.notify();
    }

    /// The numbers behind the chosen rows (private and duplicate rows left out).
    fn recents_selected_numbers(&self) -> Vec<String> {
        let r = &self.recents;
        r.select
            .as_ref()
            .map(|sel| sel.numbers(&r.merged))
            .unwrap_or_default()
    }

    /// Share: one line per chosen number through the share sheet. Select mode stays on.
    fn recents_select_share(&mut self, cx: &mut Context<Self>) {
        let r = &self.recents;
        let Some(sel) = r.select.as_ref() else {
            return;
        };
        let text = call_log_query::select_share_text(&r.merged, sel, |raw| {
            r.merged
                .iter()
                .find(|row| row.number_raw.trim() == raw)
                .and_then(|row| row.name.clone())
        });
        if text.is_empty() {
            self.show_notice_pill(
                call_log_query::select_nothing_callable_text(),
                None,
                false,
                cx,
            );
            return;
        }
        let outcome = backend::share_text(&text);
        if outcome != backend::Outcome::Done {
            self.show_notice_pill(
                outcome.why_not("Couldn't open the share sheet"),
                None,
                false,
                cx,
            );
        }
    }

    /// Block: asks first. Select mode stays on until the owner says Yes.
    fn recents_select_block_ask(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.sheet.is_visible() {
            return;
        }
        let numbers = self.recents_selected_numbers();
        if !select_tools_enabled(numbers.len()) {
            self.show_notice_pill(
                call_log_query::select_nothing_callable_text(),
                None,
                false,
                cx,
            );
            return;
        }
        if !self.backend.read(cx).can_block_here() {
            self.show_notice_pill("Not available on this platform", None, false, cx);
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        self.confirm_block = numbers;
        self.sheet_kind = crate::SheetKind::ConfirmBlockSelected;
        self.sheet.open();
    }

    /// Yes or Cancel on the block confirm sheet.
    pub(crate) fn recents_block_sheet_act(
        &mut self,
        id: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let numbers = std::mem::take(&mut self.confirm_block);
        self.close_sheet(cx);
        if id != BS_YES || numbers.is_empty() {
            return;
        }
        let many = {
            let backend = self.backend.read(cx);
            blocklist::parse_many(
                &numbers.join("\n"),
                &EmergencyList::default(),
                &backend.blocked_numbers_set(),
            )
        };
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        self.recents_leave_select(cx);
        if many.to_block.is_empty() {
            let text = if many.already_blocked > 0 {
                "Already blocked"
            } else {
                call_log_query::select_nothing_callable_text()
            };
            self.show_notice_pill(text, None, false, cx);
            return;
        }
        self.bulk_block_run(many, blocklist::bulk_text, cx);
    }

    /// The confirm sheet of Block in select mode.
    pub(crate) fn recents_block_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let colors = theme::colors(theme);
        let (title, text) = call_log_query::select_block_text(self.confirm_block.len());
        let button = |id: &'static str,
                      label: &'static str,
                      fill: gpui::Hsla,
                      ink: gpui::Hsla,
                      cx: &mut Context<Self>| {
            let press = self.presses.value(id).clamp(0.0, 1.0);
            div()
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .relative()
                .h(px(52.0 * press_scale(press, scale::PRESS)))
                .rounded_full()
                .bg(mix(fill, gpui::white(), 0.16 * press))
                .flex()
                .items_center()
                .justify_center()
                .text_size(typography::HEADLINE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(ink)
                .child(label)
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id);
                        this.recents_block_sheet_act(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
        };
        let body = div()
            .flex()
            .flex_col()
            .gap(px(10.0))
            .px(px(18.0))
            .pb(px(18.0))
            .child(
                div()
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(title),
            )
            .child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.secondary_label)
                    .child(text),
            )
            .child(button(
                BS_YES,
                "Block",
                call_colors(theme).danger,
                gpui::white(),
                cx,
            ))
            .child(button(BS_NO, "Cancel", colors.fill, colors.label, cx))
            .into_any_element();
        self.modal_sheet(theme, window, "Block selected numbers", body, cx)
    }

    /// Height from the window bottom up to the select bar's top edge.
    fn select_bar_inset(&self) -> f32 {
        f32::from(rovsti_ui::tab_bar::content_bottom_inset_with(
            self.safe_bottom,
        )) + SELECT_BAR_GAP
            + SELECT_BAR_H
    }

    /// The floating glass bar over the tab bar: the count and a Delete pill.
    pub(crate) fn select_bar(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let select = self.recents.select.as_ref()?;
        if self.tabs.selected() != RECENTS_TAB {
            return None;
        }
        let colors = theme::colors(theme);
        let n = select.len();
        let press = self.presses.value(SEL_DEL).clamp(0.0, 1.0);
        let danger = call_colors(theme).danger;
        let mut del = div()
            .id(SEL_DEL)
            .role(Role::Button)
            .aria_label("Delete")
            .aria_disabled(n == 0)
            .relative()
            .flex_none()
            .h(px(36.0 * press_scale(press, scale::PRESS)))
            .px(px(18.0))
            .rounded_full()
            .bg(if n == 0 { colors.fill } else { danger })
            .flex()
            .items_center()
            .text_size(typography::HEADLINE.size)
            .font_weight(typography::HEADLINE.weight)
            .text_color(if n == 0 {
                colors.tertiary_label
            } else {
                gpui::white()
            })
            .child("Delete");
        if n > 0 {
            del = del
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(|this, _, window, cx| {
                        this.presses.down(SEL_DEL);
                        this.recents_select_act(SEL_DEL, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(
                    cx,
                    Some(SEL_DEL.into()),
                )));
        }
        let tools = n > 0 && select_tools_enabled(self.recents_selected_numbers().len());
        let tool = |id: &'static str, label: &'static str, icon: Icon, cx: &mut Context<Self>| {
            let press = self.presses.value(id).clamp(0.0, 1.0);
            let mut b = div()
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .aria_disabled(!tools)
                .relative()
                .flex_none()
                .size(px(SEL_TOOL * press_scale(press, scale::PRESS)))
                .rounded_full()
                .bg(colors.fill)
                .flex()
                .items_center()
                .justify_center()
                .child(svg().path(icon.path()).size(px(20.0)).text_color(if tools {
                    colors.label
                } else {
                    colors.tertiary_label
                }));
            if tools {
                b = b
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, window, cx| {
                            this.presses.down(id);
                            this.recents_select_act(id, window, cx);
                        }),
                    )
                    .child(touch_surface(Self::touch_listener(cx, Some(id.into()))));
            }
            b
        };
        let block = tool(SEL_BLOCK, "Block", Icon::LucideBan, cx);
        let share = tool(SEL_SHARE, "Share", Icon::LucideShare2, cx);
        Some(
            div()
                .id("rc-sel-bar")
                .absolute()
                .left(metrics::ROW_INSET)
                .right(metrics::ROW_INSET)
                .bottom(
                    rovsti_ui::tab_bar::content_bottom_inset_with(self.safe_bottom)
                        + px(SELECT_BAR_GAP),
                )
                .h(px(SELECT_BAR_H))
                .occlude()
                .child(
                    glass(GlassSpec::control(theme))
                        .size_full()
                        .rounded_full()
                        .shadow(shadow::glass(theme.is_dark()))
                        .flex()
                        .items_center()
                        .gap(px(10.0))
                        .pl(px(18.0))
                        .pr(px(10.0))
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .truncate()
                                .text_size(typography::HEADLINE.size)
                                .font_weight(typography::HEADLINE.weight)
                                .text_color(colors.label)
                                .child(call_log_query::select_title(n)),
                        )
                        .child(block)
                        .child(share)
                        .child(del),
                )
                .into_any_element(),
        )
    }

    /// Select mode's header row: Select all / Deselect all on the left, Done on the right.
    fn recents_select_header(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let all = self.recents_all_selected();
        let pill = |id: &'static str, label: &'static str, cx: &mut Context<Self>| {
            let press = self.presses.value(id).clamp(0.0, 1.0);
            glass_pill(theme, label)
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .relative()
                .flex_none()
                .h(px(FIELD_H * press_scale(press, scale::PRESS)))
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id);
                        this.recents_select_act(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
        };
        div()
            .flex_none()
            .h(px(FIELD_H))
            .mx(metrics::ROW_INSET)
            .mb(px(8.0))
            .flex()
            .items_center()
            .justify_between()
            .child(pill(
                SEL_ALL,
                if all { "Deselect all" } else { "Select all" },
                cx,
            ))
            .child(pill(SEL_DONE, "Done", cx))
    }

    /// Desktop preview: tap the first Recents row.
    #[cfg(not(target_os = "android"))]
    pub(crate) fn preview_tap_recent(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.recents.last_pressed = Some((0, Zone::Row));
        self.recents_call(0, window, cx);
    }

    /// Story 6.3: a tap on a row calls its number through the one call path.
    fn recents_call(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(row) = self.recents.row(index) else {
            return;
        };
        if !row.callable {
            return;
        }
        let number = row.number.clone();
        if call_back_on_whatsapp(row.whatsapp, self.whatsapp.read(cx).is_connected()) {
            self.place_whatsapp(&number, cx);
            return;
        }
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
        let note = match placement {
            Placement::Placed | Placement::Requesting if !default_dialer => Some(Notice::System),
            Placement::Placed | Placement::Requesting | Placement::Debounced => None,
            Placement::Blocked => {
                self.last_dialed = number.into();
                Some(Notice::NeedsPhone)
            }
            Placement::Failed | Placement::NoVoicemail => Some(Notice::Failed),
        };
        self.set_call_note(note, cx);
        cx.notify();
        // Only a call the In-Call screen will show gets a flight.
        default_dialer && matches!(placement, Placement::Placed | Placement::Requesting)
    }

    /// A row's number, the name to show for it and its top edge in window
    /// coordinates (for the row menu).
    pub(crate) fn recents_row_facts(
        &self,
        index: usize,
    ) -> Option<(SharedString, SharedString, bool, f32)> {
        let row = self.recents.row(index)?;
        let bounds = self.recents.bounds.get();
        let top = f32::from(bounds.origin.y) + index as f32 * ROW_H - self.recents.scrolled();
        Some((row.number.clone(), row.title.clone(), row.named, top))
    }

    /// The entries Recents row `index` stands for.
    pub(crate) fn recents_row_missed(&self, index: usize) -> bool {
        self.recents.row(index).is_some_and(|row| row.missed)
    }

    /// The entries Recents row `index` stands for.
    pub(crate) fn recents_row_refs(
        &self,
        index: usize,
    ) -> Option<Arc<[rovsti_core::call_log::EntryRef]>> {
        Some(Arc::clone(&self.recents.row(index)?.refs))
    }

    fn recents_info(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(row) = self.recents.row(index).cloned() else {
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
        self.recents.number_history_edit = false;
        self.recents.number_blocked = false;
        self.recents.number = Some(row);
        self.setup_note = None;
        self.sheet_kind = crate::SheetKind::Number;
        self.sheet.open();
        cx.notify();
    }

    /// A pill on a state screen (Recents, Contacts, Favorites) was pressed.
    pub(crate) fn state_pill(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        match id {
            "rc-grant" => self.recents_grant(cx),
            "rc-retry" => self.backend.update(cx, |b, cx| b.retry_call_log(cx)),
            "set-bk-retry" | "set-bk-grant" => {
                self.blocked_tap_state(id, cx);
            }
            other => self.contacts_pill(other, window, cx),
        }
    }

    /// "Grant access" on the permission state.
    pub(crate) fn recents_grant(&mut self, cx: &mut Context<Self>) {
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
            State::Ready => {
                let results = if r.visible.is_empty() {
                    let hint = if r.query.text.trim().is_empty() {
                        "Calls of this kind show up here."
                    } else {
                        "Try a name, a number or another filter."
                    };
                    state_view(
                        theme,
                        Icon::LucideSearch,
                        false,
                        call_log_query::empty_text(&r.query),
                        hint,
                        None,
                        cx,
                    )
                } else {
                    self.recents_list(theme, window.prefers_reduced_motion(), cx)
                };
                div()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .flex()
                    .flex_col()
                    .child(if r.select.is_some() {
                        self.recents_select_header(theme, cx).into_any_element()
                    } else {
                        self.recents_field(theme, cx).into_any_element()
                    })
                    .child(self.recents_chips(theme, cx))
                    .child(results)
                    .into_any_element()
            }
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

    fn recents_list(&self, theme: Theme, reduced: bool, cx: &mut Context<Self>) -> AnyElement {
        let r = &self.recents;
        let list = uniform_list(
            "recents",
            r.visible.len() + TAIL_ROWS,
            cx.processor(move |this, range: Range<usize>, window, cx| {
                let theme = rovsti_ui::current_theme(window);
                this.want_recent_photos(range.clone(), cx);
                this.recents_rows(theme, range, cx)
            }),
        )
        .track_scroll(&r.scroll)
        .size_full();

        let entity = cx.entity();
        let cap = r.capsule(r.scrolled());
        let (alpha, playing) = r.capsule_opacity(cap.is_some(), reduced);
        if playing {
            cx.notify();
        }
        let capsule = cap.filter(|_| alpha > 0.01).map(|(label, offset)| {
            let colors = theme::colors(theme);
            div()
                .absolute()
                .top(px(offset))
                .left_0()
                .w_full()
                .flex()
                .justify_center()
                .opacity(alpha)
                .child(
                    flat_glass(theme, None)
                        .id("rc-day")
                        .role(Role::Label)
                        .h(px(CAP_H))
                        .px(px(12.0))
                        .rounded_full()
                        .flex()
                        .items_center()
                        .text_size(typography::CAPTION2.size)
                        .text_color(colors.secondary_label)
                        .aria_label(label.clone())
                        .child(label),
                )
        });
        div()
            .relative()
            .flex_1()
            .min_h_0()
            .w_full()
            .overflow_hidden()
            .child(list)
            .children(capsule)
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

    /// The search text or focus changed: re-filter the list.
    pub(crate) fn recents_field_changed(&mut self, window: &Window, cx: &mut Context<Self>) {
        let (edits, text, focused) = {
            let field = self.recents.field.read(cx);
            (
                field.edits,
                field.text().to_owned(),
                field.focus_handle().is_focused(window),
            )
        };
        let mut changed = false;
        if edits != self.recents.seen_edits {
            self.recents.seen_edits = edits;
            self.recents.field_text = text.clone().into();
            let query = Query {
                text,
                filter: self.recents.query.filter,
            };
            self.recents.set_query(query);
            changed = true;
        }
        if focused != self.recents.typing {
            self.recents.typing = focused;
            changed = true;
        }
        if changed {
            cx.notify();
        }
    }

    /// A filter chip was chosen.
    pub(crate) fn recents_choose_filter(&mut self, filter: RecentsFilter, cx: &mut Context<Self>) {
        let query = Query {
            text: self.recents.query.text.clone(),
            filter,
        };
        if self.recents.set_query(query) {
            cx.notify();
        }
    }

    /// The glass search field under the title (44 px), like the Contacts one.
    fn recents_field(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = theme::colors(theme);
        let field = self.recents.field.clone();
        let focus = field.read(cx).focus_handle();
        let text = self.recents.field_text.clone();
        let typing = self.recents.typing;
        let press = self.presses.value("rc-field").clamp(0.0, 1.0);
        let clear_press = self.presses.value("rc-clear").clamp(0.0, 1.0);
        let focus_for_tap = focus.clone();
        let clear_field = field.clone();
        let spec = GlassSpec::control(theme).lerp(GlassSpec::lens(theme), press * 0.5);
        let text_box = div()
            .relative()
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .items_center()
            .overflow_hidden()
            .text_size(typography::BODY.size)
            .child(input_layer(field.clone(), cx))
            .child(if text.is_empty() && !typing {
                div()
                    .text_color(colors.tertiary_label)
                    .child("Search names, numbers, notes")
                    .into_any_element()
            } else {
                div()
                    .overflow_hidden()
                    .whitespace_nowrap()
                    .text_ellipsis_start()
                    .text_color(colors.label)
                    .child(text.clone())
                    .into_any_element()
            })
            .children(typing.then(|| {
                div()
                    .flex_none()
                    .ml(px(1.0))
                    .w(px(2.0))
                    .h(px(20.0))
                    .rounded_full()
                    .bg(colors.accent)
            }));
        div()
            .id("rc-field")
            .role(Role::TextInput)
            .aria_label("Search calls")
            .track_focus(&focus)
            .on_key_down(on_backspace(field))
            .relative()
            .flex_none()
            .h(px(FIELD_H))
            .mx(metrics::ROW_INSET)
            .mb(px(8.0))
            .on_mouse_down(MouseButton::Left, {
                let focus = focus_for_tap;
                cx.listener(move |this, _, window, cx| {
                    this.presses.down("rc-field");
                    window.focus(&focus, cx);
                    window.show_soft_keyboard();
                    this.recents_field_changed(window, cx);
                })
            })
            .child(
                glass(spec)
                    .size_full()
                    .rounded_full()
                    .shadow(shadow::glass(theme.is_dark()))
                    .flex()
                    .items_center()
                    .gap(px(8.0))
                    .pl(px(14.0))
                    .pr(px(4.0))
                    .child(
                        svg()
                            .path(Icon::LucideSearch.path())
                            .size(px(18.0))
                            .flex_none()
                            .text_color(if typing {
                                colors.accent
                            } else {
                                colors.secondary_label
                            }),
                    )
                    .child(text_box)
                    .children((!text.is_empty()).then(|| {
                        div()
                            .id("rc-clear")
                            .role(Role::Button)
                            .aria_label("Clear search")
                            .relative()
                            .flex_none()
                            .size(px(36.0))
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(
                                div()
                                    .size(px(22.0 * press_scale(clear_press, scale::PRESS_CARD)))
                                    .rounded_full()
                                    .bg(gpui::Hsla {
                                        a: 0.55 + 0.35 * clear_press,
                                        ..colors.fill
                                    })
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .child(
                                        svg()
                                            .path(Icon::LucideX.path())
                                            .size(px(14.0))
                                            .text_color(colors.secondary_label),
                                    ),
                            )
                            .on_mouse_down(MouseButton::Left, {
                                let field = clear_field;
                                cx.listener(move |this, _, window, cx| {
                                    this.presses.down("rc-clear");
                                    // Empties the field and keeps focus.
                                    field.update(cx, |f, cx| f.clear(cx));
                                    this.recents_field_changed(window, cx);
                                })
                            })
                            .child(touch_surface(Self::touch_listener(
                                cx,
                                Some("rc-clear".into()),
                            )))
                    })),
            )
            .child(touch_surface(Self::touch_listener(
                cx,
                Some("rc-field".into()),
            )))
    }

    /// The filter chips: a glass capsule split in equal segments, the chosen
    /// one filled (the segmented-control recipe).
    fn recents_chips(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = theme::colors(theme);
        let selected = self.recents.query.filter;
        let spec = GlassSpec::control(theme);
        let thumb = mix(
            colors.background,
            colors.label,
            if theme.is_dark() { 0.22 } else { 0.0 },
        );
        div()
            .relative()
            .flex_none()
            .h(px(CHIP_H))
            .mx(metrics::ROW_INSET)
            .mb(px(8.0))
            .child(
                glass(spec)
                    .size_full()
                    .rounded_full()
                    .shadow(shadow::glass(theme.is_dark()))
                    .p(px(2.0))
                    .flex()
                    .children(RecentsFilter::ALL.iter().map(|&filter| {
                        let id: SharedString = format!("rc-chip-{}", filter.label()).into();
                        let on = filter == selected;
                        let mut chip = div()
                            .id(id.clone())
                            .role(Role::Tab)
                            .aria_selected(on)
                            .aria_label(filter.label())
                            .relative()
                            .flex_1()
                            .min_w_0()
                            .h_full()
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .overflow_hidden()
                            .whitespace_nowrap()
                            .text_size(px(CHIP_TEXT))
                            .font_weight(if on {
                                gpui::FontWeight::SEMIBOLD
                            } else {
                                gpui::FontWeight::MEDIUM
                            })
                            .text_color(if on {
                                colors.label
                            } else {
                                colors.secondary_label
                            })
                            .child(filter.label());
                        if on {
                            chip = chip.bg(thumb).shadow(vec![gpui::BoxShadow {
                                color: gpui::black().opacity(0.16),
                                offset: gpui::point(px(0.0), px(1.0)),
                                blur_radius: px(3.0),
                                spread_radius: px(0.0),
                                inset: false,
                            }]);
                        }
                        chip.on_mouse_down(
                            MouseButton::Left,
                            cx.listener(move |this, _, _, cx| {
                                this.recents_choose_filter(filter, cx);
                            }),
                        )
                        .child(touch_surface(Self::touch_listener(cx, Some(id))))
                    })),
            )
    }

    /// Starts loading the pictures of the WhatsApp rows in `range`.
    fn want_recent_photos(&mut self, range: Range<usize>, cx: &mut Context<Self>) {
        let rows = Arc::clone(&self.recents.rows);
        for i in range {
            let Some(vm) = self.recents.visible.get(i).and_then(|&row| rows.get(row)) else {
                continue;
            };
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
        let live = progress < 1.0 && (r.fresh > 0 || r.inserted > 0) && !r.narrowed();
        range
            .map(|i| match r.row(i) {
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
                    let chosen = r.select.as_ref().map(|sel| {
                        r.visible
                            .get(i)
                            .and_then(|&at| r.merged.get(at))
                            .is_some_and(|row| sel.contains(row))
                    });
                    let row = recents_row(
                        theme,
                        i,
                        vm,
                        (row_press, info_press),
                        (r.sims.visible(), r.note_hit(i)),
                        fx,
                        (photo, chosen),
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

    /// Opens the Number sheet's picture in the photo viewer. Only a contact's
    /// own photo has a full-size version to load behind the thumbnail.
    pub(crate) fn open_number_photo(&mut self, cx: &mut Context<Self>) {
        let Some(row) = self.recents.number.as_ref() else {
            return;
        };
        let thumb = self.caller_photo(&row.photo, cx).map(|(image, _)| image);
        let uri = row
            .photo
            .contact
            .as_ref()
            .filter(|(id, _)| self.photos.get(*id).is_some())
            .map(|(_, uri)| uri.clone());
        let title = row.title.clone();
        let owner = row
            .photo
            .contact
            .as_ref()
            .map(|(id, _)| (*id, uri.is_some()));
        self.open_photo_viewer(title, thumb, uri, owner, cx);
    }

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
        // U1: a row with a picture opens it full screen from the header avatar.
        let photo = self.caller_photo(&row.photo, cx);
        let has_photo = photo.is_some();
        let avatar = avatar_view(theme, row, 72.0, photo);
        let avatar = if has_photo {
            avatar
                .id("sheet-photo")
                .role(Role::Button)
                .aria_label("View photo")
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(|this, _, _, cx| this.open_number_photo(cx)),
                )
                .into_any_element()
        } else {
            avatar.into_any_element()
        };
        let header = div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(4.0))
            .pb(px(8.0))
            .child(avatar)
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
            }))
            .children(
                (!row.named)
                    .then(|| {
                        let home_cc = match self.backend.read(cx).call_log() {
                            backend::Load::Ready(log) => log.home_cc.clone(),
                            _ => None,
                        };
                        crate::details_ui::header_caption(
                            None,
                            rovsti_core::calling_code::country_line(
                                &row.number,
                                home_cc.as_deref(),
                            )
                            .as_deref(),
                        )
                    })
                    .flatten()
                    .map(|c| {
                        div()
                            .text_size(typography::SUBHEADLINE.size)
                            .text_color(colors.secondary_label)
                            .child(c)
                    }),
            );
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
        let edit = HistoryEdit {
            editing: self.recents.number_history_edit,
            toggle_id: "sheet-nhist-edit",
            line_prefix: NUMBER_HISTORY_DELETE,
        };
        let lines = Arc::clone(&self.recents.number_history);
        if let Some(section) = history_section(theme, &lines, Some(edit), self, cx) {
            let del = self.history_delete_button(theme, crate::history_ui::NUMBER_SHEET_DELETE, cx);
            let csv = sheet_pill(
                self,
                crate::person_ui::NUMBER_SHEET_CSV,
                Icon::LucideDownload,
                "Export calls",
                colors.fill,
                colors.label,
                cx,
            );
            children.push(reveal(
                4,
                div()
                    .flex()
                    .flex_col()
                    .child(section)
                    .child(csv)
                    .child(del)
                    .into_any_element(),
            ));
        }
        if !row.number.is_empty()
            && let Some(vm) = self.stats_block_vm(&[row.number.as_ref()], &row.title, cx)
        {
            children.push(reveal(
                4,
                self.stats_section(theme, &vm, crate::stats_ui::NUMBER_SHARE, cx),
            ));
        }
        if !row.number.is_empty() {
            children.push(reveal(
                5,
                sheet_pill(
                    self,
                    "sheet-na-reply",
                    Icon::LucideMessageCircle,
                    "Send a quick reply",
                    colors.fill,
                    colors.label,
                    cx,
                )
                .into_any_element(),
            ));
            if crate::remind_ui::can_remind(&row.number) {
                let caption = crate::remind_ui::remind_caption(
                    self.backend.read(cx).reminders(),
                    &row.number,
                    self.tz_offset_s(cx),
                );
                children.push(reveal(
                    5,
                    div()
                        .flex()
                        .flex_col()
                        .child(sheet_pill(
                            self,
                            crate::remind_ui::NUMBER_REMIND,
                            Icon::LucideBell,
                            "Remind me",
                            colors.fill,
                            colors.label,
                            cx,
                        ))
                        .children(caption.map(|c| {
                            div()
                                .text_size(typography::CAPTION2.size)
                                .text_color(colors.secondary_label)
                                .flex()
                                .justify_center()
                                .child(c)
                        }))
                        .into_any_element(),
                ));
            }
            children.push(reveal(
                5,
                sheet_pill(
                    self,
                    "sheet-n-qr",
                    Icon::LucideQrCode,
                    "Share as QR code",
                    colors.fill,
                    colors.label,
                    cx,
                )
                .into_any_element(),
            ));
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
        if crate::details_ui::is_contact_id(id) {
            return self.contact_sheet_act(id, window, cx);
        }
        let Some(row) = self.recents.number.clone() else {
            return;
        };
        if let Some((false, i)) = crate::details_ui::details_line_index(id) {
            self.open_call_details(false, i, window, cx);
            return;
        }
        if id == "sheet-nhist-edit" {
            self.recents.number_history_edit = !self.recents.number_history_edit;
            cx.notify();
            return;
        }
        if let Some(i) = history_index(id, NUMBER_HISTORY_DELETE) {
            if let Some(vm) = self.recents.number_history.get(i) {
                let entry = vm.entry.clone();
                if self.recents.number_blocked {
                    self.delete_blocked_refs(&[entry], window, cx);
                } else {
                    self.delete_row_calls(Arc::from([entry]), window, cx);
                }
            }
            return;
        }
        if id == crate::person_ui::NUMBER_SHEET_CSV {
            self.export_person_calls(vec![row.number.to_string()], row.title.to_string(), cx);
            return;
        }
        if id == crate::stats_ui::NUMBER_SHARE {
            self.stats_share(&[row.number.to_string()], &row.title, cx);
            return;
        }
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
            crate::remind_ui::NUMBER_REMIND => {
                if crate::remind_ui::can_remind(&row.number) {
                    self.open_remind_pick(row.number.clone(), row.title.clone(), false, cx);
                }
            }
            "sheet-n-qr" => {
                if !row.number.is_empty() {
                    let numbers = vec![(row.number.to_string(), "Mobile".to_owned())];
                    self.open_qr_viewer(row.title.clone(), numbers, cx);
                }
            }
            "sheet-nadd" => {
                let outcome = backend::add_contact(&row.number);
                if outcome == backend::Outcome::Done {
                    self.close_sheet(cx);
                } else {
                    // The Number sheet has no note line: the pill says it.
                    let text = outcome.why_not("Couldn't open the Contacts app");
                    self.show_notice_pill(text, None, false, cx);
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
    /// The stored call this line stands for (deleting it, restoring it).
    pub(crate) entry: rovsti_core::call_log::EntryRef,
    /// A call the phone blocked.
    pub(crate) blocked: bool,
    /// The number as stored, and when the call started (the key of its note).
    pub(crate) number: SharedString,
    pub(crate) date_ms: i64,
    /// The call has a note: the line shows a small note glyph.
    pub(crate) note: bool,
    /// What the details sheet is built from.
    pub(crate) line: recents::HistoryLine,
}

impl HistoryVm {
    /// Built once when a sheet opens, never per frame.
    fn new(
        line: &recents::HistoryLine,
        now_ms: i64,
        locale: recents::LocaleTime,
        note: bool,
    ) -> Self {
        let (title, detail) = recents::history_text(line, now_ms, locale);
        let direction = Direction::of(line.call_type);
        Self {
            title: title.into(),
            detail: detail.into(),
            icon: direction_icon(direction),
            missed: direction == Direction::Missed,
            whatsapp: line.whatsapp,
            entry: line.entry.clone(),
            blocked: line.blocked,
            number: line.number.clone().into(),
            date_ms: line.date_ms,
            note,
            line: line.clone(),
        }
    }

    fn aria(&self) -> SharedString {
        format!("{}, {}", self.title, self.detail).into()
    }
}

/// Id prefix of a Number sheet history line's delete button.
pub(crate) const NUMBER_HISTORY_DELETE: &str = "sheet-nhx-";
/// Id prefix of a Contact sheet history line's delete button.
pub(crate) const CONTACT_HISTORY_DELETE: &str = "sheet-chx-";

/// Most calls a sheet lists.
const HISTORY_LIMIT: usize = 10;
/// Fixed height of one history line.
const HISTORY_ROW_H: f32 = 44.0;

impl PhoneApp {
    /// The newest blocked calls with any of `numbers`. Empty until they are pulled.
    pub(crate) fn blocked_history_for(&self, numbers: &[&str], cx: &gpui::App) -> Arc<[HistoryVm]> {
        let Load::Ready(blocked) = self.backend.read(cx).blocked_calls() else {
            return Arc::from(Vec::new());
        };
        let locale = match self.backend.read(cx).call_log() {
            Load::Ready(log) => log.locale,
            _ => recents::LocaleTime::default(),
        };
        let now = backend::now_ms();
        let lines = recents::blocked_history(&blocked, numbers, HISTORY_LIMIT);
        self.history_vms(&lines, now, locale)
    }

    /// The newest calls (cellular and WhatsApp) with any of `numbers`. Empty
    /// until the call log is ready.
    pub(crate) fn history_for(&self, numbers: &[&str], cx: &gpui::App) -> Arc<[HistoryVm]> {
        let Load::Ready(log) = self.backend.read(cx).call_log() else {
            return Arc::from(Vec::new());
        };
        let extras = self.wa_call.read(cx).recents_extras();
        let now = backend::now_ms();
        let lines = recents::history(&log.source, &extras, numbers, HISTORY_LIMIT);
        self.history_vms(&lines, now, log.locale)
    }

    /// The lines as view-models, with their note glyphs read in one pass.
    fn history_vms(
        &self,
        lines: &[recents::HistoryLine],
        now: i64,
        locale: recents::LocaleTime,
    ) -> Arc<[HistoryVm]> {
        let flags = crate::details_ui::note_flags(&self.notes, lines);
        lines
            .iter()
            .zip(flags)
            .map(|(line, note)| HistoryVm::new(line, now, locale, note))
            .collect()
    }
}

/// How a sheet's "Recent calls" block edits: the Edit/Done toggle and the per-line delete ids.
#[derive(Clone, Copy, Debug)]
pub(crate) struct HistoryEdit {
    pub editing: bool,
    pub toggle_id: &'static str,
    pub line_prefix: &'static str,
}

impl PhoneApp {
    /// A history Edit/Delete control was pressed: the Contact sheet's ids start with
    /// `sheet-c`, the Number sheet's with `sheet-n`.
    fn history_edit_act(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        if crate::details_ui::is_contact_id(id) {
            self.contact_sheet_act(id, window, cx);
        } else {
            self.number_sheet_act(id, window, cx);
        }
    }
}

/// Fill opacity of a tappable history line while pressed.
fn line_press(press: f32) -> f32 {
    0.9 * press.clamp(0.0, 1.0)
}

/// The line index a delete button id `{prefix}{i}` stands for.
pub(crate) fn history_index(id: &str, prefix: &str) -> Option<usize> {
    id.strip_prefix(prefix)?.parse().ok()
}

/// The "Recent calls" block of a sheet; `None` when there are no calls.
pub(crate) fn history_section(
    theme: Theme,
    lines: &[HistoryVm],
    edit: Option<HistoryEdit>,
    app: &PhoneApp,
    cx: &mut Context<PhoneApp>,
) -> Option<AnyElement> {
    if lines.is_empty() {
        return None;
    }
    let colors = theme::colors(theme);
    let danger = call_colors(theme).danger;
    let rows: Vec<AnyElement> = lines
        .iter()
        .enumerate()
        .map(|(i, line)| {
            let ink = if line.missed { danger } else { colors.label };
            let trash = edit.filter(|e| e.editing).map(|e| {
                let id: SharedString = format!("{}{i}", e.line_prefix).into();
                let press = app.presses.value(&id).clamp(0.0, 1.0);
                let k = press_scale(press, scale::PRESS);
                div()
                    .id(id.clone())
                    .role(Role::Button)
                    .aria_label(SharedString::from(format!("Delete {}", line.aria())))
                    .relative()
                    .flex_none()
                    .size(px(HISTORY_ROW_H * k))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        svg()
                            .path(Icon::LucideTrash2.path())
                            .size(px(18.0))
                            .text_color(danger),
                    )
                    .on_mouse_down(MouseButton::Left, {
                        let id = id.clone();
                        cx.listener(move |this, _, window, cx| {
                            this.presses.down(id.clone());
                            this.history_edit_act(&id, window, cx);
                        })
                    })
                    .child(touch_surface(PhoneApp::touch_listener(cx, Some(id))))
            });
            // Outside Edit mode a line opens the call details; in Edit mode only the
            // trash button acts, so a miss never opens a sheet by accident.
            let tap = edit.filter(|e| !e.editing).map(|e| {
                let id: SharedString =
                    crate::details_ui::details_line_id(e.line_prefix == CONTACT_HISTORY_DELETE, i)
                        .into();
                (id.clone(), line_press(app.presses.value(&id)))
            });
            let mut row = div()
                .id(SharedString::from(format!("sheet-hist-{i}")))
                .aria_label(line.aria())
                .relative()
                .h(px(HISTORY_ROW_H))
                .w_full()
                .flex()
                .items_center()
                .gap(px(10.0))
                .px(px(16.0))
                .bg(gpui::Hsla {
                    a: tap.as_ref().map_or(0.0, |t| t.1),
                    ..colors.fill
                });
            if let Some((id, _)) = tap.clone() {
                row = row
                    .role(Role::Button)
                    .on_mouse_down(MouseButton::Left, {
                        let id = id.clone();
                        cx.listener(move |this, _, window, cx| {
                            this.presses.down(id.clone());
                            this.history_edit_act(&id, window, cx);
                        })
                    })
                    .child(touch_surface(PhoneApp::touch_listener(cx, Some(id))));
            }
            row.child(if line.whatsapp {
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
            .children(line.note.then(|| {
                svg()
                    .path(Icon::LucideStickyNote.path())
                    .size(px(14.0))
                    .flex_none()
                    .text_color(colors.secondary_label)
            }))
            .child(
                div()
                    .flex_none()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(line.detail.clone()),
            )
            .children(trash)
            .into_any_element()
        })
        .collect();
    let toggle = edit.map(|e| {
        let id = e.toggle_id;
        let press = app.presses.value(id).clamp(0.0, 1.0);
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(if e.editing { "Done" } else { "Edit" })
            .relative()
            .flex_none()
            .h(px(28.0 * press_scale(press, scale::PRESS)))
            .px(px(4.0))
            .flex()
            .items_center()
            .text_size(typography::FOOTNOTE.size)
            .text_color(colors.accent)
            .child(if e.editing { "Done" } else { "Edit" })
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| {
                    this.presses.down(id);
                    this.history_edit_act(id, window, cx);
                }),
            )
            .child(touch_surface(PhoneApp::touch_listener(cx, Some(id.into()))))
    });
    Some(
        div()
            .flex()
            .flex_col()
            .pt(px(6.0))
            .child(
                div()
                    .flex()
                    .items_center()
                    .justify_between()
                    .px(px(16.0))
                    .pb(px(4.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child("Recent calls")
                    .children(toggle),
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
    (sim_slot, note): (bool, Option<&SharedString>),
    fx: Option<crate::block_ui::RowFx>,
    // The photo; `Some(chosen)` in select mode puts the selection circle in the avatar slot.
    (photo, select): (Option<(Arc<RenderImage>, f32)>, Option<bool>),
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
        .when_some(select, |row, chosen| row.aria_selected(chosen))
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
                    _ if select.is_some() => {
                        select_circle(theme, select == Some(true)).into_any_element()
                    }
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
                                        .child(note.unwrap_or(&vm.subtitle).clone()),
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

/// The 22 px selection circle in the avatar slot (centred in its 40 px box):
/// filled accent with a check when chosen, a hairline ring otherwise.
fn select_circle(theme: Theme, chosen: bool) -> gpui::Div {
    let colors = theme::colors(theme);
    let ring = div()
        .size(px(22.0))
        .rounded_full()
        .flex()
        .items_center()
        .justify_center();
    let ring = if chosen {
        ring.bg(colors.accent).child(
            svg()
                .path(Icon::LucideCheck.path())
                .size(px(14.0))
                .text_color(gpui::white()),
        )
    } else {
        ring.border_1().border_color(colors.tertiary_label)
    };
    div()
        .flex_none()
        .size(px(AVATAR))
        .flex()
        .items_center()
        .justify_center()
        .child(ring)
}

/// Icon, title, body and an optional pill `(id, label, press)`, centred in the
/// free space. The pill acts on finger-up through [`PhoneApp::state_pill`].
pub(crate) fn state_view(
    theme: Theme,
    icon: Icon,
    warn: bool,
    title: impl Into<SharedString>,
    body: impl Into<SharedString>,
    pill: Option<(&'static str, &'static str, f32)>,
    cx: &mut Context<PhoneApp>,
) -> AnyElement {
    let (title, body) = (title.into(), body.into());
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
        .children(pill.map(|(id, label, press)| state_pill_button(theme, id, label, press, cx)))
        .into_any_element()
}

/// One glass pill on a state screen; acts on finger-up through
/// [`PhoneApp::state_pill`].
pub(crate) fn state_pill_button(
    theme: Theme,
    id: &'static str,
    label: &'static str,
    press: f32,
    cx: &mut Context<PhoneApp>,
) -> AnyElement {
    let colors = theme::colors(theme);
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
            cx.listener(move |this, _, window, cx| {
                this.presses.down(id);
                this.state_pill(id, window, cx);
            }),
        )
        .child(touch_surface(PhoneApp::touch_listener(cx, Some(id.into()))))
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capsule_offset_edges() {
        assert_eq!(capsule_offset(100.0, None, 24.0), 0.0);
        assert_eq!(capsule_offset(100.0, Some(300.0), 24.0), 0.0);
        assert_eq!(capsule_offset(100.0, Some(124.0), 24.0), 0.0);
        assert_eq!(capsule_offset(100.0, Some(112.0), 24.0), -12.0);
        assert_eq!(capsule_offset(100.0, Some(100.0), 24.0), -24.0);
        assert_eq!(capsule_offset(100.0, Some(90.0), 24.0), -24.0);
    }

    #[test]
    fn whatsapp_rows_call_back_over_whatsapp_only_when_connected() {
        assert!(call_back_on_whatsapp(true, true));
        assert!(!call_back_on_whatsapp(true, false));
        assert!(!call_back_on_whatsapp(false, true));
        assert!(!call_back_on_whatsapp(false, false));
    }

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
            entry: rovsti_core::call_log::EntryRef::Log(1),
            blocked: false,
            call_type,
            whatsapp,
            date_ms: 0,
            duration_s,
            number: String::new(),
        }
    }

    #[test]
    fn history_vm_marks_missed_and_keeps_the_duration_out_of_missed_calls() {
        use rovsti_core::call_log::CallType;
        let now = 3_600_000;
        let missed = HistoryVm::new(
            &line(CallType::Missed, true, 0),
            now,
            Default::default(),
            false,
        );
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
            true,
        );
        assert!(answered.note && !missed.note);
        assert!(!answered.missed && !answered.whatsapp);
        assert!(answered.detail.ends_with("1:05"), "{}", answered.detail);
    }

    #[test]
    fn history_index_reads_only_its_own_prefix() {
        assert_eq!(history_index("sheet-nhx-3", NUMBER_HISTORY_DELETE), Some(3));
        assert_eq!(
            history_index("sheet-chx-9", CONTACT_HISTORY_DELETE),
            Some(9)
        );
        assert_eq!(history_index("sheet-chx-3", NUMBER_HISTORY_DELETE), None);
        assert_eq!(history_index("sheet-nhx-x", NUMBER_HISTORY_DELETE), None);
        assert_eq!(history_index("sheet-nhx-", NUMBER_HISTORY_DELETE), None);
        assert_eq!(
            history_index("sheet-nhx-10", NUMBER_HISTORY_DELETE),
            Some(10)
        );
        assert_eq!(
            history_index("sheet-nhist-edit", NUMBER_HISTORY_DELETE),
            None
        );
        assert!(!"sheet-nhist-edit".starts_with("sheet-del-"));
        assert!(!"sheet-chist-edit".starts_with("sheet-del-"));
    }

    #[test]
    fn note_only_matches_show_a_preview() {
        let row = |name: Option<&str>| RecentsRow {
            number_raw: "+905551112233".into(),
            normalized: "+905551112233".into(),
            call_type: rovsti_core::call_log::CallType::Incoming,
            count: 1,
            first_ms: 1_000,
            last_ms: 1_000,
            name: name.map(Into::into),
            label: None,
            contact_id: None,
            callable: true,
            account_key: None,
            whatsapp: false,
            refs: vec![],
        };
        let mut notes = Notes::default();
        notes.set("+905551112233", 1_000, "Bring the invoice", 1_000);
        let merged = [row(None), row(Some("Invoice Corp"))];
        let query = Query {
            text: "invoice".into(),
            ..Query::default()
        };
        let visible = call_log_query::apply_with_notes(&merged, &query, &notes);
        assert_eq!(visible, vec![0, 1]);
        let hits = note_only_hits(&merged, &visible, &query, &notes);
        assert_eq!(hits.len(), 1, "the name match shows no note");
        assert_eq!(hits[&0].as_ref(), "Note: Bring the invoice");
    }

    #[test]
    fn notes_chip_id_and_note_rebuild_rule() {
        assert_eq!(RecentsFilter::ALL.len(), 6);
        assert_eq!(
            format!("rc-chip-{}", RecentsFilter::WithNotes.label()),
            "rc-chip-Notes"
        );
        let mut q = Query::default();
        assert!(!notes_affect_list(&q));
        q.filter = RecentsFilter::WithNotes;
        assert!(notes_affect_list(&q));
        q.filter = RecentsFilter::All;
        q.text = "ann".into();
        assert!(notes_affect_list(&q));
    }

    #[test]
    fn select_tools_ids_and_gate() {
        let ids = [SEL_ALL, SEL_DONE, SEL_DEL, SEL_BLOCK, SEL_SHARE];
        for (i, a) in ids.iter().enumerate() {
            assert!(a.starts_with("rc-sel-"));
            assert!(ids[i + 1..].iter().all(|b| a != b));
        }
        for id in [BS_YES, BS_NO] {
            assert!(id.starts_with("sheet-bs-"));
            assert!(!id.starts_with("sheet-del-") && !id.starts_with("sheet-bk-"));
            assert!(!id.starts_with("sheet-n") && !id.starts_with("sheet-c"));
        }
        assert_ne!(BS_YES, BS_NO);
        assert!(!select_tools_enabled(0));
        assert!(select_tools_enabled(1) && select_tools_enabled(5));
    }

    fn row_view(title: &str, subtitle: &str, named: bool, number: &str) -> RowView {
        RowView {
            title: title.into(),
            count: None,
            subtitle: subtitle.into(),
            time: String::new(),
            aria: String::new(),
            direction: rovsti_core::recents::Direction::Incoming,
            missed: false,
            callable: !number.is_empty(),
            number: number.into(),
            hue: None,
            monogram: None,
            named,
            account_key: None,
            whatsapp: false,
        }
    }

    fn subtitle_of(v: RowView, home: Option<&str>) -> String {
        RowVm::build(v, &SimInfo::default(), home)
            .subtitle
            .to_string()
    }

    #[test]
    fn country_caption_only_for_unnamed_foreign_rows() {
        let named = row_view("Ada", "+49 30 1234567", true, "+49301234567");
        assert_eq!(subtitle_of(named, Some("90")), "+49 30 1234567");
        let home = row_view("+90 532 111 22 33", "Incoming", false, "+905321112233");
        assert_eq!(subtitle_of(home, Some("90")), "Incoming");
        let foreign = row_view("+49 30 1234567", "Incoming", false, "+49301234567");
        assert_eq!(subtitle_of(foreign, Some("90")), "Incoming \u{b7} Germany");
        let private = row_view("Private number", "Incoming", false, "");
        assert_eq!(subtitle_of(private, Some("90")), "Incoming");
    }
}
