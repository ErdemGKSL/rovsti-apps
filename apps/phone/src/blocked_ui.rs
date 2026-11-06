//! The Blocked numbers page (stories 17.5, 17.6): a second-level Settings page
//! with a "Numbers | Blocked calls" segmented control, an add field and two
//! virtualised lists of fixed 58 px rows.
//!
//! Row view-models are built once per snapshot (when the `Arc` behind the
//! blocklist or the blocked calls changes), never per frame; the row ids the
//! press springs use are built with them. Insertions fade in with no layout
//! spring, and a removal fades out for 120 ms before the list re-lays.

use std::{ops::Range, sync::Arc, time::Duration};

use gpui::{
    AnyElement, AppContext as _, Context, Entity, HapticFeedback, Hsla, InteractiveElement,
    IntoElement, MouseButton, ParentElement, Role, SharedString, StatefulInteractiveElement,
    Styled, Task, UniformListScrollHandle, Window, div, prelude::*, px, svg, uniform_list,
};
use rovsti_core::{
    blocklist::{self, BlockInputError},
    call_log::relative_time,
    recents::LocaleTime,
    wire::EmergencyList,
};
use rovsti_ui::{
    Icon,
    components::flat_glass,
    glass::{GlassSpec, glass},
    motion::press_scale,
    motion_tokens::{self, scale},
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, metrics, shadow, typography},
    touch::touch_surface,
};

use crate::{
    PhoneApp, SheetKind,
    backend::{self, Access, Group, Load},
    block_ui::PillAction,
    field::{SearchField, input_layer, on_backspace},
    recents::{ROW_H, state_view},
};

const SHARE_ID: &str = "set-bk-share";
const UNALL_ID: &str = "set-bk-unall";
const EXPORT_ID: &str = "set-bk-export";
const IMPORT_ID: &str = "set-bk-import";
/// Longest file the Import picker accepts.
const IMPORT_MAX_BYTES: u64 = 256 * 1024;
const UNALL_YES: &str = "sheet-bk-yes";
const UNALL_NO: &str = "sheet-bk-no";
const FIELD_H: f32 = 44.0;
const HEADER_H: f32 = 44.0;
const SEG_H: f32 = 32.0;
const SKELETON_DELAY: Duration = Duration::from_millis(150);
/// A removed row fades this long, then the list re-lays at once.
const LEAVE_SECS: f32 = 0.12;
/// A new row fades in this long (no layout spring).
const FRESH_SECS: f32 = 0.12;

/// A Numbers row, ready to draw.
#[derive(Clone)]
struct NumRow {
    /// Press id of the Unblock pill.
    id: SharedString,
    raw: SharedString,
    title: SharedString,
    subtitle: SharedString,
    aria: SharedString,
}

/// A Blocked calls row, ready to draw.
#[derive(Clone)]
struct CallRow {
    id: SharedString,
    raw: SharedString,
    title: SharedString,
    subtitle: SharedString,
    time: SharedString,
    aria: SharedString,
    named: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Shown {
    Loading,
    Ready,
    Denied,
    Unavailable,
}

pub(crate) struct BlockedUi {
    pub segment: usize,
    seg: Spring,
    pub field: Entity<SearchField>,
    seen_edits: u64,
    seen_submits: u64,
    typing: bool,
    error: Option<&'static str>,
    numbers: Arc<[NumRow]>,
    numbers_ptr: usize,
    numbers_state: Shown,
    calls: Arc<[CallRow]>,
    calls_ptr: usize,
    calls_state: Shown,
    scroll_n: UniformListScrollHandle,
    scroll_c: UniformListScrollHandle,
    /// The row being unblocked and how long it has been fading.
    leaving: Option<(SharedString, f32)>,
    /// A new row at the top fading in.
    fresh: Option<f32>,
    skeleton_on: bool,
    skeleton_task: Option<Task<()>>,
    /// The file picker was opened by Import; the next `import_messages` answer is ours.
    pub import_pending: bool,
}

impl BlockedUi {
    pub fn new(cx: &mut Context<PhoneApp>) -> Self {
        Self {
            segment: 0,
            seg: Spring::new(0.0, motion_tokens::select()).with_epsilon(0.002),
            // The keyboard's action key blocks the typed number, like the Block button.
            field: cx.new(|cx| SearchField::new(cx).with_action(gpui::TextInputAction::Go)),
            seen_edits: 0,
            seen_submits: 0,
            typing: false,
            error: None,
            numbers: Arc::from(Vec::new()),
            numbers_ptr: 0,
            numbers_state: Shown::Loading,
            calls: Arc::from(Vec::new()),
            calls_ptr: 0,
            calls_state: Shown::Loading,
            scroll_n: UniformListScrollHandle::new(),
            scroll_c: UniformListScrollHandle::new(),
            leaving: None,
            fresh: None,
            skeleton_on: false,
            skeleton_task: None,
            import_pending: false,
        }
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.seg.tick(dt);
        if let Some((_, t)) = &mut self.leaving {
            *t += dt.as_secs_f32();
            moving = true;
        }
        if let Some(t) = &mut self.fresh {
            *t += dt.as_secs_f32();
            if *t >= FRESH_SECS {
                self.fresh = None;
            }
            moving = true;
        }
        moving
    }

    fn fresh_alpha(&self) -> f32 {
        self.fresh.map_or(1.0, |t| (t / FRESH_SECS).clamp(0.0, 1.0))
    }

    fn leave_alpha(&self, raw: &SharedString) -> f32 {
        match &self.leaving {
            Some((leaving, t)) if leaving == raw => 1.0 - (t / LEAVE_SECS).clamp(0.0, 1.0),
            _ => 1.0,
        }
    }
}

/// The text of the add field's inline error.
fn error_text(error: BlockInputError) -> &'static str {
    match error {
        BlockInputError::Invalid => "Not a valid number",
        BlockInputError::Emergency => "Emergency numbers can't be blocked",
    }
}

impl PhoneApp {
    // ---- lifecycle -------------------------------------------------------------

    pub(crate) fn blocked_opened(&mut self, cx: &mut Context<Self>) {
        self.blocked.segment = 0;
        self.blocked.seg.snap(0.0);
        self.blocked.error = None;
        self.blocked.numbers_ptr = 0;
        self.blocked.calls_ptr = 0;
        self.backend
            .update(cx, |backend, cx| backend.want_blocked_calls(true, cx));
        self.blocked_sync(cx);
    }

    pub(crate) fn blocked_closed(&mut self, cx: &mut Context<Self>) {
        self.backend
            .update(cx, |backend, cx| backend.want_blocked_calls(false, cx));
        self.blocked.field.update(cx, |f, cx| f.clear(cx));
        self.blocked.leaving = None;
        self.blocked.fresh = None;
    }

    /// "Grant access" on the Settings row or the Blocked calls state.
    pub(crate) fn blocked_grant(&mut self, cx: &mut Context<Self>) {
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

    /// The add field's text or focus changed.
    pub(crate) fn blocked_field_changed(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let (edits, submits, focused) = {
            let field = self.blocked.field.read(cx);
            (
                field.edits,
                field.submits,
                field.focus_handle().is_focused(window),
            )
        };
        if submits != self.blocked.seen_submits {
            self.blocked.seen_submits = submits;
            let typed = !self.blocked.field.read(cx).text().trim().is_empty();
            if typed && self.settings.sub == Some(crate::settings_ui::Sub::Blocked) {
                self.blocked_add(window, cx);
            }
        }
        let mut changed = false;
        if edits != self.blocked.seen_edits {
            self.blocked.seen_edits = edits;
            // Typing again takes the inline error away.
            self.blocked.error = None;
            changed = true;
        }
        if focused != self.blocked.typing {
            self.blocked.typing = focused;
            changed = true;
        }
        if changed {
            cx.notify();
        }
    }

    // ---- data -------------------------------------------------------------------

    /// Rebuilds the row view-models when the snapshots behind them changed.
    /// A row that is fading out holds the list until it has gone.
    pub(crate) fn blocked_sync(&mut self, cx: &mut Context<Self>) {
        if self
            .blocked
            .leaving
            .as_ref()
            .is_some_and(|(_, t)| *t < LEAVE_SECS)
        {
            return;
        }
        self.blocked.leaving = None;
        let (list, calls, contacts, format, locale) = {
            let backend = self.backend.read(cx);
            let locale = match backend.call_log() {
                Load::Ready(log) => log.locale,
                _ => LocaleTime::default(),
            };
            (
                backend.blocklist(),
                backend.blocked_calls(),
                backend.contacts(),
                backend.settings().name_format,
                locale,
            )
        };
        let name_of = |raw: &str| -> Option<String> {
            let Load::Ready(index) = &contacts else {
                return None;
            };
            index
                .match_number(raw)
                .map(|c| rovsti_core::names::display(c, format).into_owned())
        };
        match list {
            Load::Ready(state) => {
                let ptr = Arc::as_ptr(&state) as usize;
                if ptr != self.blocked.numbers_ptr || self.blocked.numbers_state != Shown::Ready {
                    let rows: Vec<NumRow> = state
                        .rows
                        .iter()
                        .enumerate()
                        .map(|(i, row)| {
                            let line =
                                blocklist::numbers_line(row, name_of(&row.number_raw).as_deref());
                            NumRow {
                                id: format!("set-bk-un-{i}").into(),
                                raw: row.number_raw.clone().into(),
                                title: line.title.into(),
                                subtitle: line.subtitle.into(),
                                aria: line.aria.into(),
                            }
                        })
                        .collect();
                    // A new row at the top fades in (only after a list was already showing).
                    if self.blocked.numbers_state == Shown::Ready
                        && rows.len() > self.blocked.numbers.len()
                    {
                        self.blocked.fresh = Some(0.0);
                    }
                    self.blocked.numbers = rows.into();
                    self.blocked.numbers_ptr = ptr;
                    self.blocked.numbers_state = Shown::Ready;
                }
            }
            Load::Loading => self.blocked.numbers_state = Shown::Loading,
            Load::PermissionDenied => self.blocked.numbers_state = Shown::Denied,
            Load::Unavailable => self.blocked.numbers_state = Shown::Unavailable,
        }
        match calls {
            Load::Ready(entries) => {
                let ptr = Arc::as_ptr(&entries) as usize;
                if ptr != self.blocked.calls_ptr || self.blocked.calls_state != Shown::Ready {
                    let now = backend::now_ms();
                    let rows: Vec<CallRow> = entries
                        .iter()
                        .enumerate()
                        .map(|(i, e)| {
                            let name = name_of(&e.number_raw);
                            let time =
                                relative_time(now, e.date_ms, locale.tz_offset_s, locale.format);
                            let line = blocklist::call_line(e, name.as_deref(), time.as_str());
                            CallRow {
                                id: format!("set-bk-call-{i}").into(),
                                raw: e.number_raw.clone().into(),
                                named: name.is_some(),
                                title: line.title.into(),
                                subtitle: line.subtitle.into(),
                                time: time.as_str().to_owned().into(),
                                aria: line.aria.into(),
                            }
                        })
                        .collect();
                    self.blocked.calls = rows.into();
                    self.blocked.calls_ptr = ptr;
                    self.blocked.calls_state = Shown::Ready;
                }
            }
            Load::Loading => self.blocked.calls_state = Shown::Loading,
            Load::PermissionDenied => self.blocked.calls_state = Shown::Denied,
            Load::Unavailable => self.blocked.calls_state = Shown::Unavailable,
        }
        // A list that takes longer than 150 ms to load shows its skeleton.
        let loading = match self.blocked.segment {
            0 => self.blocked.numbers_state == Shown::Loading,
            _ => self.blocked.calls_state == Shown::Loading,
        };
        if loading && self.blocked.skeleton_task.is_none() {
            self.blocked.skeleton_on = false;
            self.blocked.skeleton_task = Some(cx.spawn(async move |this, cx| {
                cx.background_executor().timer(SKELETON_DELAY).await;
                this.update(cx, |this, cx| {
                    this.blocked.skeleton_on = true;
                    this.blocked.skeleton_task = None;
                    cx.notify();
                })
                .ok();
            }));
        } else if !loading {
            self.blocked.skeleton_task = None;
            self.blocked.skeleton_on = false;
        }
    }

    // ---- input --------------------------------------------------------------------

    /// A control of the page was tapped (finger-up).
    pub(crate) fn blocked_tap(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        match id {
            "set-bk-seg-0" => self.blocked_segment(0, window, cx),
            "set-bk-seg-1" => self.blocked_segment(1, window, cx),
            "set-bk-add" => self.blocked_add(window, cx),
            "set-bk-retry" => self.backend.update(cx, |b, cx| b.retry_blocklist(cx)),
            "set-bk-grant" => self.blocked_grant(cx),
            SHARE_ID => self.blocked_share(window, cx),
            UNALL_ID => self.blocked_unall_ask(window, cx),
            EXPORT_ID => self.blocked_export(window, cx),
            IMPORT_ID => self.blocked_import(window, cx),
            _ => {
                if let Some(i) = id
                    .strip_prefix("set-bk-un-")
                    .and_then(|n| n.parse::<usize>().ok())
                {
                    self.blocked_unblock(i, window, cx);
                } else if let Some(i) = id
                    .strip_prefix("set-bk-call-")
                    .and_then(|n| n.parse::<usize>().ok())
                {
                    self.blocked_call_tap(i, window, cx);
                }
            }
        }
        cx.notify();
    }

    /// The Numbers list as text through the system share sheet.
    fn blocked_share(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let (rows, contacts, format) = {
            let backend = self.backend.read(cx);
            let Load::Ready(state) = backend.blocklist() else {
                return;
            };
            (
                state.rows.clone(),
                backend.contacts(),
                backend.settings().name_format,
            )
        };
        let text = blocklist::list_text(&rows, |raw| {
            let Load::Ready(index) = &contacts else {
                return None;
            };
            index
                .match_number(raw)
                .map(|c| rovsti_core::names::display(c, format).into_owned())
        });
        if text.is_empty() {
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
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

    /// "Unblock all": opens the confirm sheet.
    fn blocked_unall_ask(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.blocked.numbers.is_empty()
            || !self.backend.read(cx).can_block_here()
            || self.sheet.is_visible()
        {
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        self.sheet_kind = SheetKind::UnblockAll;
        self.sheet.open();
        cx.notify();
    }

    /// The confirm sheet's buttons.
    pub(crate) fn blocked_unall_act(
        &mut self,
        id: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if id == UNALL_YES {
            let raws: Vec<SharedString> = self
                .blocked
                .numbers
                .iter()
                .map(|r| r.raw.clone())
                .filter(|r| !r.trim().is_empty())
                .collect();
            rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
            self.close_sheet(cx);
            if raws.is_empty() {
                return;
            }
            // Each write runs on the background executor (`Backend::unblock`).
            let tasks: Vec<_> = raws
                .iter()
                .map(|raw| self.backend.update(cx, |b, cx| b.unblock(raw, cx)))
                .collect();
            cx.spawn(async move |this, cx| {
                let mut done: Vec<SharedString> = Vec::new();
                for (raw, task) in raws.into_iter().zip(tasks) {
                    if task.await {
                        done.push(raw);
                    }
                }
                this.update(cx, |this, cx| {
                    if done.is_empty() {
                        this.show_notice_pill("Couldn't unblock the numbers", None, false, cx);
                    } else {
                        let text = blocklist::unblocked_all_text(done.len());
                        let done: Arc<[SharedString]> = done.into();
                        this.show_pill_action(text, "Undo", PillAction::BlockAll(done), cx);
                    }
                    cx.notify();
                })
                .ok();
            })
            .detach();
        } else {
            self.close_sheet(cx);
        }
    }

    /// Undo of "Unblock all": blocks every number again.
    pub(crate) fn blocked_block_all(
        &mut self,
        raws: Arc<[SharedString]>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        let tasks: Vec<_> = raws
            .iter()
            .map(|raw| self.backend.update(cx, |b, cx| b.block(raw, cx)))
            .collect();
        cx.spawn(async move |this, cx| {
            let mut n = 0;
            for task in tasks {
                if task.await {
                    n += 1;
                }
            }
            this.update(cx, |this, cx| {
                let text = if n == 0 {
                    "Couldn't block the numbers".to_owned()
                } else {
                    blocklist::blocked_again_text(n)
                };
                this.show_notice_pill(text, None, false, cx);
            })
            .ok();
        })
        .detach();
    }

    /// The confirm sheet of Unblock all.
    pub(crate) fn blocked_unall_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let colors = theme::colors(theme);
        let (title, text) = blocklist::unblock_all_text(self.blocked.numbers.len());
        let button = |id: &'static str,
                      label: &'static str,
                      fill: Hsla,
                      ink: Hsla,
                      cx: &mut Context<Self>| {
            let press = self.presses.value(id).clamp(0.0, 1.0);
            div()
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .relative()
                .h(px(52.0))
                .rounded_full()
                .bg(rovsti_ui::tokens::mix(fill, gpui::white(), 0.16 * press))
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
                        this.blocked_unall_act(id, window, cx);
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
                UNALL_YES,
                "Unblock all",
                call_colors(theme).danger,
                gpui::white(),
                cx,
            ))
            .child(button(UNALL_NO, "Cancel", colors.fill, colors.label, cx))
            .into_any_element();
        self.modal_sheet(theme, window, "Unblock all numbers", body, cx)
    }

    /// The Share and Unblock all pills above the Numbers list.
    fn blocked_header(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let pill = |id: &'static str, label: &'static str, ink: Hsla, cx: &mut Context<Self>| {
            let press = self.presses.value(id).clamp(0.0, 1.0);
            div()
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .relative()
                .flex_1()
                .h(px(36.0))
                .rounded_full()
                .bg(Hsla {
                    a: 0.7 + 0.2 * press,
                    ..colors.fill
                })
                .flex()
                .items_center()
                .justify_center()
                .text_size(typography::SUBHEADLINE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(ink)
                .child(label)
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id);
                        this.blocked_tap(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
        };
        let any = !self.blocked.numbers.is_empty();
        let row = || div().flex().gap(px(8.0)).h(px(36.0));
        let rows = if any { 2.0 } else { 1.0 };
        div()
            .flex_none()
            .h(px(rows * HEADER_H))
            .mx(px(16.0))
            .pb(px(8.0))
            .flex()
            .flex_col()
            .gap(px(8.0))
            .children(any.then(|| {
                row()
                    .child(pill(SHARE_ID, "Share", colors.accent, cx))
                    .child(pill(UNALL_ID, "Unblock all", call_colors(theme).danger, cx))
            }))
            .child(
                row()
                    .children(any.then(|| pill(EXPORT_ID, "Export", colors.accent, cx)))
                    .child(pill(IMPORT_ID, "Import", colors.accent, cx)),
            )
            .into_any_element()
    }

    fn blocked_segment(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        if self.blocked.segment == index {
            return;
        }
        self.blocked.segment = index;
        if window.prefers_reduced_motion() {
            self.blocked.seg.snap(index as f32);
        } else {
            self.blocked.seg.set_target(index as f32);
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        window.blur();
        self.blocked_sync(cx);
    }

    /// The trailing "Block" of the add field.
    fn blocked_add(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.backend.read(cx).can_block_here() {
            return;
        }
        let typed = self.blocked.field.read(cx).text().to_owned();
        let emergency = EmergencyList::default();
        let many = {
            let backend = self.backend.read(cx);
            blocklist::parse_many(&typed, &emergency, &backend.blocked_numbers_set())
        };
        let listed = many.to_block.len() + many.already_blocked + many.skipped_emergency;
        if listed > 1 && many.to_block.is_empty() {
            // Several numbers, none of them new: the whole text is not one number.
            self.blocked.error = Some(if many.already_blocked > 0 {
                "Already blocked"
            } else {
                "Emergency numbers can't be blocked"
            });
            rovsti_ui::haptics::perform(window, HapticFeedback::Reject);
            return;
        }
        if listed > 1 {
            self.blocked.field.update(cx, |f, cx| f.clear(cx));
            window.blur();
            self.blocked_block_many(many, window, cx);
            return;
        }
        match blocklist::parse_input(&typed, &emergency) {
            Err(error) => {
                self.blocked.error = Some(error_text(error));
                rovsti_ui::haptics::perform(window, HapticFeedback::Reject);
            }
            Ok(number) => {
                if self.backend.read(cx).is_blocked(&number) {
                    self.blocked.error = Some("Already blocked");
                    rovsti_ui::haptics::perform(window, HapticFeedback::Reject);
                    return;
                }
                self.blocked.field.update(cx, |f, cx| f.clear(cx));
                window.blur();
                let who: SharedString = crate::block_ui::shown_number(&number).into();
                self.start_block(number.into(), who, None, window, cx);
            }
        }
    }

    /// Blocks several pasted numbers in the background, then offers Undo.
    fn blocked_block_many(
        &mut self,
        many: blocklist::BulkParse,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        self.bulk_block_run(many, blocklist::bulk_text, cx);
    }

    /// Blocks `many.to_block` one by one, then shows `text_of(blocked ones)` with
    /// Undo. Shared by the pasted list and the file import.
    pub(crate) fn bulk_block_run(
        &mut self,
        many: blocklist::BulkParse,
        text_of: impl FnOnce(&blocklist::BulkParse) -> String + 'static,
        cx: &mut Context<Self>,
    ) {
        let raws: Vec<SharedString> = many.to_block.iter().cloned().map(Into::into).collect();
        let tasks: Vec<_> = raws
            .iter()
            .map(|raw| self.backend.update(cx, |b, cx| b.block(raw, cx)))
            .collect();
        cx.spawn(async move |this, cx| {
            let mut done: Vec<SharedString> = Vec::new();
            for (raw, task) in raws.into_iter().zip(tasks) {
                if task.await {
                    done.push(raw);
                }
            }
            this.update(cx, |this, cx| {
                if done.is_empty() {
                    this.show_notice_pill("Couldn't block the numbers", None, false, cx);
                } else {
                    let shown = blocklist::BulkParse {
                        to_block: done.iter().map(ToString::to_string).collect(),
                        ..many
                    };
                    let text = text_of(&shown);
                    let done: Arc<[SharedString]> = done.into();
                    this.show_pill_action(text, "Undo", PillAction::UnblockAll(done), cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Export: the list as CSV through "save as".
    fn blocked_export(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let (rows, contacts, format, tz) = {
            let backend = self.backend.read(cx);
            let Load::Ready(state) = backend.blocklist() else {
                return;
            };
            (
                state.rows.clone(),
                backend.contacts(),
                backend.settings().name_format,
                match backend.call_log() {
                    Load::Ready(log) => log.locale.tz_offset_s,
                    _ => 0,
                },
            )
        };
        if rows.is_empty() {
            return;
        }
        let csv = blocklist::to_csv(&rows, |raw| {
            let Load::Ready(index) = &contacts else {
                return None;
            };
            index
                .match_number(raw)
                .map(|c| rovsti_core::names::display(c, format).into_owned())
        });
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        self.backup.export_kind = crate::backup_ui::ExportKind::Blocked;
        let outcome = backend::export_calls(&blocklist::csv_file_name(backend::now_ms(), tz), &csv);
        if outcome != backend::Outcome::Done {
            self.show_notice_pill(outcome.why_not("Couldn't export"), None, false, cx);
        }
    }

    /// Import: opens the file picker; `sync_documents` hands the answer back.
    fn blocked_import(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.backend.read(cx).can_block_here() {
            self.show_notice_pill("Not available on this platform", None, false, cx);
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        self.blocked.import_pending = true;
        let outcome = backend::import_file(IMPORT_MAX_BYTES);
        if outcome != backend::Outcome::Done {
            self.blocked.import_pending = false;
            self.show_notice_pill(
                outcome.why_not("Couldn't open the file picker"),
                None,
                false,
                cx,
            );
        }
    }

    /// The picker answered an Import: reads the file, blocks what it lists.
    pub(crate) fn blocked_file_picked(
        &mut self,
        result: rovsti_core::wire::DocumentResult,
        cx: &mut Context<Self>,
    ) {
        if !result.ok {
            if result.error.is_some() {
                self.show_notice_pill("Couldn't read the file", None, false, cx);
            }
            return; // otherwise the picker was cancelled
        }
        let Some(path) = result.path else {
            self.show_notice_pill("Couldn't read the file", None, false, cx);
            return;
        };
        cx.spawn(async move |this, cx| {
            let text = cx
                .background_executor()
                .spawn(async move {
                    let text = std::fs::read_to_string(&path);
                    std::fs::remove_file(&path).ok();
                    text
                })
                .await;
            this.update(cx, |this, cx| {
                let Ok(text) = text else {
                    this.show_notice_pill("Couldn't read the file", None, false, cx);
                    return;
                };
                let file = {
                    let backend = this.backend.read(cx);
                    blocklist::parse_file(
                        &text,
                        &EmergencyList::default(),
                        &backend.blocked_numbers_set(),
                    )
                };
                if file.bulk.to_block.is_empty() {
                    this.show_notice_pill(blocklist::import_text(&file), None, false, cx);
                    return;
                }
                let blocklist::FileImport {
                    bulk,
                    skipped_lines,
                    truncated,
                } = file;
                this.bulk_block_run(
                    bulk,
                    move |shown| {
                        blocklist::import_text(&blocklist::FileImport {
                            bulk: shown.clone(),
                            skipped_lines,
                            truncated,
                        })
                    },
                    cx,
                );
            })
            .ok();
        })
        .detach();
    }

    /// Undo of a bulk block: unblocks every one of the numbers.
    pub(crate) fn blocked_unblock_bulk(
        &mut self,
        raws: Arc<[SharedString]>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        let tasks: Vec<_> = raws
            .iter()
            .map(|raw| self.backend.update(cx, |b, cx| b.unblock(raw, cx)))
            .collect();
        cx.spawn(async move |this, cx| {
            let mut n = 0;
            for task in tasks {
                if task.await {
                    n += 1;
                }
            }
            this.update(cx, |this, cx| {
                let text = if n == 0 {
                    "Couldn't unblock the numbers".to_owned()
                } else {
                    blocklist::unblocked_all_text(n)
                };
                this.show_notice_pill(text, None, false, cx);
            })
            .ok();
        })
        .detach();
    }

    /// The row's Unblock pill: the row fades for 120 ms, the list re-lays, the
    /// pill reads "Unblocked {number}" with Undo.
    fn blocked_unblock(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(row) = self.blocked.numbers.get(index).cloned() else {
            return;
        };
        if self.blocked.leaving.is_some() {
            return;
        }
        let who = if row.subtitle.as_ref() == "Blocked number" {
            row.title.clone()
        } else {
            row.subtitle.clone()
        };
        self.blocked.leaving = if window.prefers_reduced_motion() {
            None
        } else {
            Some((row.raw.clone(), 0.0))
        };
        self.run_unblock(row.raw, who, true, window, cx);
    }

    /// A Blocked calls row opens the existing number sheet (Call details replaces it in 21.1).
    fn blocked_call_tap(&mut self, index: usize, _window: &mut Window, cx: &mut Context<Self>) {
        let Some(row) = self.blocked.calls.get(index).cloned() else {
            return;
        };
        if row.raw.trim().is_empty() || self.sheet.is_visible() {
            return;
        }
        self.recents.number_history = self.blocked_history_for(&[row.raw.as_str()], cx);
        self.recents.number_history_edit = false;
        self.recents.number_blocked = true;
        self.recents.number = Some(crate::recents::RowVm::for_number(
            row.raw.clone(),
            row.title.clone(),
            row.named,
        ));
        self.setup_note = None;
        self.sheet_kind = crate::SheetKind::Number;
        self.sheet.open();
        cx.notify();
    }

    // ---- drawing ---------------------------------------------------------------------

    fn blocked_segments(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let pos = self.blocked.seg.value;
        let selected = pos.round().clamp(0.0, 1.0) as usize;
        div()
            .relative()
            .flex_none()
            .mx(px(16.0))
            .h(px(SEG_H))
            .rounded_full()
            .bg(Hsla {
                a: 0.55,
                ..colors.fill
            })
            .child(
                // The thumb: half the track, minus the 2 px inset on each side.
                div()
                    .absolute()
                    .top(px(2.0))
                    .bottom(px(2.0))
                    .left(gpui::relative(0.0))
                    .w(gpui::relative(0.5))
                    .child(
                        div()
                            .relative()
                            .left(gpui::relative(pos))
                            .mx(px(2.0))
                            .h_full()
                            .rounded_full()
                            .bg(rovsti_ui::tokens::mix(
                                colors.background,
                                colors.label,
                                if theme.is_dark() { 0.22 } else { 0.0 },
                            ))
                            .shadow(vec![gpui::BoxShadow {
                                color: gpui::black().opacity(0.16),
                                offset: gpui::point(px(0.0), px(1.0)),
                                blur_radius: px(3.0),
                                spread_radius: px(0.0),
                                inset: false,
                            }]),
                    ),
            )
            .child(
                div().absolute().inset_0().flex().children(
                    ["Numbers", "Blocked calls"]
                        .into_iter()
                        .enumerate()
                        .map(|(i, label)| {
                            let id: &'static str = if i == 0 {
                                "set-bk-seg-0"
                            } else {
                                "set-bk-seg-1"
                            };
                            div()
                                .id(id)
                                .role(Role::Tab)
                                .aria_selected(i == selected)
                                .aria_label(label)
                                .relative()
                                .flex_1()
                                .h_full()
                                .flex()
                                .items_center()
                                .justify_center()
                                .text_size(px(13.0))
                                .font_weight(if i == selected {
                                    gpui::FontWeight::SEMIBOLD
                                } else {
                                    gpui::FontWeight::NORMAL
                                })
                                .text_color(colors.label)
                                .child(label)
                                .on_mouse_down(
                                    MouseButton::Left,
                                    cx.listener(move |this, _, window, cx| {
                                        this.presses.down(id);
                                        this.blocked_tap(id, window, cx);
                                    }),
                                )
                                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
                        }),
                ),
            )
            .into_any_element()
    }

    /// The add field in the search-field shape: `LucideBan`, the placeholder and a
    /// trailing accent "Block" button.
    fn blocked_field(&self, theme: Theme, enabled: bool, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let field = self.blocked.field.clone();
        let focus = field.read(cx).focus_handle();
        let text: SharedString = field.read(cx).text().to_owned().into();
        let typing = self.blocked.typing;
        let add_press = self.presses.value("set-bk-add").clamp(0.0, 1.0);
        let can_add = enabled && !text.trim().is_empty();
        let focus_for_tap = focus.clone();
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
            .child(if text.is_empty() && !(typing && enabled) {
                div()
                    .text_color(colors.tertiary_label)
                    .child("Add a number to block")
                    .into_any_element()
            } else if typing && enabled {
                // While typing the ellipsis cuts the start, never the digits just typed.
                div()
                    .overflow_hidden()
                    .whitespace_nowrap()
                    .text_ellipsis_start()
                    .text_color(colors.label)
                    .child(text.clone())
                    .into_any_element()
            } else {
                div()
                    .truncate()
                    .text_color(colors.label)
                    .child(text.clone())
                    .into_any_element()
            })
            .children((typing && enabled).then(|| {
                div()
                    .flex_none()
                    .ml(px(1.0))
                    .w(px(2.0))
                    .h(px(20.0))
                    .rounded_full()
                    .bg(colors.accent)
            }));
        let mut shell = div()
            .id("set-bk-field")
            .role(Role::TextInput)
            .aria_label("Add a number to block")
            .aria_disabled(!enabled)
            .relative()
            .flex_none()
            .h(px(FIELD_H))
            .mx(px(16.0))
            .opacity(if enabled { 1.0 } else { 0.5 });
        if enabled {
            shell = shell
                .track_focus(&focus)
                .on_key_down(on_backspace(field))
                .on_mouse_down(MouseButton::Left, {
                    let focus = focus_for_tap;
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down("set-bk-field");
                        window.focus(&focus, cx);
                        window.show_soft_keyboard();
                        this.blocked_field_changed(window, cx);
                    })
                })
                .child(touch_surface(Self::touch_listener(
                    cx,
                    Some("set-bk-field".into()),
                )));
        }
        shell
            .child(
                glass(GlassSpec::control(theme))
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
                            .path(Icon::LucideBan.path())
                            .size(px(18.0))
                            .flex_none()
                            .text_color(if typing {
                                colors.accent
                            } else {
                                colors.secondary_label
                            }),
                    )
                    .child(text_box)
                    .child(
                        div()
                            .id("set-bk-add")
                            .role(Role::Button)
                            .aria_label("Block")
                            .aria_disabled(!can_add)
                            .relative()
                            .flex_none()
                            .h(px(36.0 * press_scale(add_press, scale::PRESS)))
                            .px(px(16.0))
                            .rounded_full()
                            .bg(rovsti_ui::tokens::mix(
                                colors.accent,
                                gpui::white(),
                                0.2 * add_press,
                            ))
                            .opacity(if can_add { 1.0 } else { 0.4 })
                            .flex()
                            .items_center()
                            .justify_center()
                            .text_size(typography::SUBHEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(gpui::white())
                            .child("Block")
                            .when(can_add, |el| {
                                el.on_mouse_down(
                                    MouseButton::Left,
                                    cx.listener(|this, _, window, cx| {
                                        this.presses.down("set-bk-add");
                                        this.blocked_tap("set-bk-add", window, cx);
                                    }),
                                )
                                .child(touch_surface(
                                    Self::touch_listener(cx, Some("set-bk-add".into())),
                                ))
                            }),
                    ),
            )
            .into_any_element()
    }

    fn blocked_number_rows(
        &self,
        theme: Theme,
        range: Range<usize>,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        let colors = theme::colors(theme);
        let fresh = self.blocked.fresh_alpha();
        range
            .map(|i| {
                let Some(row) = self.blocked.numbers.get(i) else {
                    return div().h(px(ROW_H)).into_any_element();
                };
                let press = self.presses.value(&row.id).clamp(0.0, 1.0);
                let alpha = self.blocked.leave_alpha(&row.raw) * if i == 0 { fresh } else { 1.0 };
                div()
                    .id(("bk-num", i))
                    .role(Role::Row)
                    .aria_label(row.aria.clone())
                    .relative()
                    .h(px(ROW_H))
                    .w_full()
                    .px(metrics::ROW_INSET)
                    .flex()
                    .items_center()
                    .gap(px(12.0))
                    .opacity(alpha)
                    .child(
                        div()
                            .size(px(40.0))
                            .flex_none()
                            .rounded_full()
                            .bg(Hsla {
                                a: 0.7,
                                ..colors.fill
                            })
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(
                                svg()
                                    .path(Icon::LucideBan.path())
                                    .size(px(20.0))
                                    .text_color(colors.secondary_label),
                            ),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .child(
                                div()
                                    .truncate()
                                    .text_size(typography::HEADLINE.size)
                                    .font_weight(typography::HEADLINE.weight)
                                    .text_color(colors.label)
                                    .child(row.title.clone()),
                            )
                            .child(
                                div()
                                    .truncate()
                                    .text_size(typography::SUBHEADLINE.size)
                                    .text_color(colors.secondary_label)
                                    .child(row.subtitle.clone()),
                            ),
                    )
                    .child(self.unblock_pill(theme, row, press, cx))
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
            })
            .collect()
    }

    /// The trailing glass pill (36 px, press 0.94). Flat glass: a list of rows
    /// must not each cost a refractive pass. Acts on finger-up.
    fn unblock_pill(
        &self,
        theme: Theme,
        row: &NumRow,
        press: f32,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let id = row.id.clone();
        div()
            .id(id.clone())
            .role(Role::Button)
            .aria_label(SharedString::from(format!("Unblock {}", row.title)))
            .relative()
            .flex_none()
            .h(px(metrics::MIN_HIT.into()))
            .flex()
            .items_center()
            .child(
                flat_glass(theme, None)
                    .h(px(36.0 * press_scale(press, scale::PRESS)))
                    .px(px(14.0))
                    .rounded_full()
                    .flex()
                    .items_center()
                    .text_size(typography::SUBHEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.accent)
                    .child("Unblock"),
            )
            .on_mouse_down(MouseButton::Left, {
                let id = id.clone();
                cx.listener(move |this, _, window, cx| {
                    this.presses.down(id.clone());
                    this.blocked_tap(&id, window, cx);
                })
            })
            .child(touch_surface(Self::touch_listener(cx, Some(id))))
            .into_any_element()
    }

    fn blocked_call_rows(
        &self,
        theme: Theme,
        range: Range<usize>,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        let colors = theme::colors(theme);
        range
            .map(|i| {
                let Some(row) = self.blocked.calls.get(i) else {
                    return div().h(px(ROW_H)).into_any_element();
                };
                let press = self.presses.value(&row.id).clamp(0.0, 1.0);
                div()
                    .id(("bk-call", i))
                    .role(Role::Row)
                    .aria_label(row.aria.clone())
                    .relative()
                    .h(px(ROW_H))
                    .w_full()
                    .px(metrics::ROW_INSET)
                    .flex()
                    .items_center()
                    .gap(px(12.0))
                    .bg(Hsla {
                        a: 0.9 * press,
                        ..colors.fill
                    })
                    .child(
                        div()
                            .size(px(40.0))
                            .flex_none()
                            .rounded_full()
                            .bg(Hsla {
                                a: 0.7,
                                ..colors.fill
                            })
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(
                                svg()
                                    .path(Icon::LucideBan.path())
                                    .size(px(20.0))
                                    .text_color(colors.secondary_label),
                            ),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .child(
                                div()
                                    .truncate()
                                    .text_size(typography::HEADLINE.size)
                                    .font_weight(typography::HEADLINE.weight)
                                    .text_color(colors.label)
                                    .child(row.title.clone()),
                            )
                            .children((!row.subtitle.is_empty()).then(|| {
                                div()
                                    .truncate()
                                    .text_size(typography::SUBHEADLINE.size)
                                    .text_color(colors.secondary_label)
                                    .child(row.subtitle.clone())
                            })),
                    )
                    .child(
                        div()
                            .flex_none()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.tertiary_label)
                            .child(row.time.clone()),
                    )
                    .child(
                        div()
                            .absolute()
                            .bottom_0()
                            .left(px(68.0))
                            .right_0()
                            .h(px(0.5))
                            .bg(colors.separator),
                    )
                    .on_mouse_down(MouseButton::Left, {
                        let id = row.id.clone();
                        cx.listener(move |this, _, window, cx| {
                            this.presses.down(id.clone());
                            this.blocked_tap(&id, window, cx);
                        })
                    })
                    .child(touch_surface(Self::touch_listener(
                        cx,
                        Some(row.id.clone()),
                    )))
                    .into_any_element()
            })
            .collect()
    }

    fn blocked_skeleton(&self, theme: Theme) -> AnyElement {
        let colors = theme::colors(theme);
        let bar = |w: f32, h: f32| div().w(px(w)).h(px(h)).rounded_full().bg(colors.fill);
        div()
            .flex_1()
            .w_full()
            .overflow_hidden()
            .children((0..8).map(|_| {
                div()
                    .h(px(ROW_H))
                    .w_full()
                    .flex()
                    .items_center()
                    .gap(px(12.0))
                    .px(metrics::ROW_INSET)
                    .child(div().size(px(40.0)).rounded_full().bg(colors.fill))
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap(px(7.0))
                            .child(bar(130.0, 14.0))
                            .child(bar(90.0, 12.0)),
                    )
            }))
            .into_any_element()
    }

    /// The page under the large title.
    pub(crate) fn blocked_body(
        &mut self,
        theme: Theme,
        _window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        self.blocked_sync(cx);
        let colors = theme::colors(theme);
        let can_block = self.backend.read(cx).can_block_here();
        let segment = self.blocked.segment;
        let (state, count) = if segment == 0 {
            (self.blocked.numbers_state, self.blocked.numbers.len())
        } else {
            (self.blocked.calls_state, self.blocked.calls.len())
        };
        let list: AnyElement = match (segment, state) {
            (_, Shown::Loading) => {
                if self.blocked.skeleton_on {
                    self.blocked_skeleton(theme)
                } else {
                    div().flex_1().into_any_element()
                }
            }
            (0, Shown::Unavailable) if !can_block => div().flex_1().into_any_element(),
            (0, Shown::Unavailable) | (0, Shown::Denied) => state_view(
                theme,
                Icon::LucideTriangleAlert,
                true,
                "Couldn't read blocked numbers",
                "Something went wrong reading the blocked numbers.",
                Some((
                    "set-bk-retry",
                    "Try again",
                    self.presses.value("set-bk-retry"),
                )),
                cx,
            ),
            (1, Shown::Denied) => state_view(
                theme,
                Icon::LucideTriangleAlert,
                true,
                "Allow call log access",
                "Rovsti Phone needs it to show your blocked calls.",
                Some((
                    "set-bk-grant",
                    "Grant access",
                    self.presses.value("set-bk-grant"),
                )),
                cx,
            ),
            (1, Shown::Unavailable) => state_view(
                theme,
                Icon::LucideTriangleAlert,
                true,
                "Couldn't read blocked calls",
                "Something went wrong reading the call log.",
                Some((
                    "set-bk-retry",
                    "Try again",
                    self.presses.value("set-bk-retry"),
                )),
                cx,
            ),
            (0, Shown::Ready) if count == 0 => state_view(
                theme,
                Icon::LucideBan,
                false,
                "No blocked numbers",
                "Block spam from Recents or by typing a number above.",
                None,
                cx,
            ),
            (_, Shown::Ready) if count == 0 => state_view(
                theme,
                Icon::LucideBan,
                false,
                "No blocked calls",
                "Calls from blocked numbers show up here.",
                None,
                cx,
            ),
            (0, Shown::Ready) => uniform_list(
                "bk-numbers",
                count,
                cx.processor(move |this, range: Range<usize>, window, cx| {
                    let theme = rovsti_ui::current_theme(window);
                    this.blocked_number_rows(theme, range, cx)
                }),
            )
            .track_scroll(&self.blocked.scroll_n)
            .size_full()
            .into_any_element(),
            _ => uniform_list(
                "bk-calls",
                count,
                cx.processor(move |this, range: Range<usize>, window, cx| {
                    let theme = rovsti_ui::current_theme(window);
                    this.blocked_call_rows(theme, range, cx)
                }),
            )
            .track_scroll(&self.blocked.scroll_c)
            .size_full()
            .into_any_element(),
        };
        // One fixed line under the field: the error, or why blocking is off. Nothing moves.
        let note: Option<(&'static str, bool)> = if !can_block {
            Some(("Blocking isn't available for this user", false))
        } else {
            self.blocked.error.map(|e| (e, true))
        };
        div()
            .flex_1()
            .min_h_0()
            .flex()
            .flex_col()
            .pt(px(4.0))
            .child(self.blocked_segments(theme, cx))
            .child(
                div()
                    .h(px(FIELD_H + 12.0))
                    .flex_none()
                    .pt(px(12.0))
                    .child(self.blocked_field(theme, can_block, cx)),
            )
            .child(
                div()
                    .h(px(24.0))
                    .flex_none()
                    .px(px(32.0))
                    .flex()
                    .items_center()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(match note {
                        Some((_, true)) => colors.red,
                        _ => colors.secondary_label,
                    })
                    .children(note.map(|(text, _)| {
                        div()
                            .id("set-bk-note")
                            .role(Role::Alert)
                            .aria_label(text)
                            .child(text)
                    })),
            )
            .child(
                div()
                    .relative()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .flex()
                    .flex_col()
                    .children(
                        (segment == 0 && state == Shown::Ready)
                            .then(|| self.blocked_header(theme, cx)),
                    )
                    .child(list),
            )
            .child(
                div()
                    .flex_none()
                    .h(px(44.0))
                    .px(px(32.0))
                    .flex()
                    .items_center()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child("Xiaomi's own block list may be separate"),
            )
            .into_any_element()
    }
}

impl PhoneApp {
    /// A pill on one of the page's state screens.
    pub(crate) fn blocked_tap_state(&mut self, id: &str, cx: &mut Context<Self>) {
        match id {
            "set-bk-retry" => self.backend.update(cx, |b, cx| b.retry_blocklist(cx)),
            "set-bk-grant" => self.blocked_grant(cx),
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::settings_ui::Sub;

    #[test]
    fn ids_belong_to_the_blocked_page_and_sheet() {
        assert!(SHARE_ID.starts_with(Sub::Blocked.prefix()));
        assert!(UNALL_ID.starts_with(Sub::Blocked.prefix()));
        assert!(EXPORT_ID.starts_with(Sub::Blocked.prefix()));
        assert!(IMPORT_ID.starts_with(Sub::Blocked.prefix()));
        assert!(UNALL_YES.starts_with("sheet-") && UNALL_NO.starts_with("sheet-"));
        // The row ids keep their own prefixes, so the list never mistakes a pill for a row.
        for id in [SHARE_ID, UNALL_ID, EXPORT_ID, IMPORT_ID] {
            assert!(
                !id.starts_with("set-bk-un-") && !id.starts_with("set-bk-call-"),
                "{id}"
            );
        }
    }

    #[test]
    fn bulk_block_and_undo_labels() {
        let b = blocklist::BulkParse {
            to_block: vec!["+905551112233".into(), "+905324445566".into()],
            skipped_emergency: 1,
            already_blocked: 1,
        };
        assert_eq!(
            blocklist::bulk_text(&b),
            "Blocked 2 numbers, 1 already blocked"
        );
        assert_eq!(blocklist::unblocked_all_text(2), "Unblocked 2 numbers");
    }

    #[test]
    fn confirm_copy_comes_from_core() {
        let (title, text) = blocklist::unblock_all_text(2);
        assert_eq!(title, "Unblock all numbers?");
        assert!(text.contains('2'));
    }
}
