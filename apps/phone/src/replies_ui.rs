//! Declining with a message (stories 18.2, 18.3, 18.7).
//!
//! The reply sheet rises over the ringing screen, or over the call when a
//! second caller waits, and lists the owner's quick replies. Choosing one
//! (finger-up) declines the call through `Backend::reject_with_message`,
//! plays S31 (the text, as a flat bubble, flies to the avatar and fades) and
//! says what happened in a label ("Sent", "Opened Messages", "Declined").
//!
//! The same module owns the Quick replies editor, a second-level Settings
//! page: up to 6 text fields, drag-to-reorder by their handle, Clear,
//! Reset to defaults behind a confirm sheet, autosaved through `update_prefs`.

use std::time::Duration;

use gpui::{
    AnyElement, AppContext as _, Context, Entity, HapticFeedback, Hsla, InteractiveElement,
    IntoElement, MouseButton, ParentElement, Role, SharedString, StatefulInteractiveElement,
    Styled, TouchPhase, Window, div, prelude::*, px, svg,
};
use rovsti_core::{
    call::CallState,
    settings::{self, DEFAULT_REPLIES, MAX_REPLIES, MAX_REPLY_CHARS, REPLY_COUNTER_AT},
};
use rovsti_ui::{
    Icon,
    components::flat_glass,
    field::{Bare, TextField, bare_field},
    motion::press_scale,
    motion_tokens::{self, scale},
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, radius, shadow, typography},
    touch::{drag_surface, touch_surface},
};

use crate::{PhoneApp, SheetKind, backend::ReplyOutcome};

const ROW_H: f32 = 56.0;
const ROW_IDS: [&str; 7] = ["rp-0", "rp-1", "rp-2", "rp-3", "rp-4", "rp-5", "rp-own"];
/// How long the bubble of S31 travels.
const S31_SECS: f32 = 0.4;
const S31_REDUCED_SECS: f32 = 0.12;

// ---- the reply sheet ---------------------------------------------------------------

/// Signature moment S31: the chosen text flies to the avatar and fades.
#[derive(Clone, Debug)]
pub(crate) struct S31 {
    text: SharedString,
    from: (f32, f32),
    to: (f32, f32),
    t: f32,
    reduced: bool,
}

impl S31 {
    fn total(&self) -> f32 {
        if self.reduced {
            S31_REDUCED_SECS
        } else {
            S31_SECS
        }
    }

    /// `(x, y, scale, alpha)` of the bubble now.
    pub fn pose(&self) -> (f32, f32, f32, f32) {
        let p = (self.t / self.total()).clamp(0.0, 1.0);
        if self.reduced {
            // A fade with no travel.
            return (self.from.0, self.from.1, 1.0, 1.0 - p);
        }
        let e = 1.0 - (1.0 - p).powi(3);
        (
            self.from.0 + (self.to.0 - self.from.0) * e,
            self.from.1 + (self.to.1 - self.from.1) * e,
            1.0 - 0.65 * e,
            1.0 - ((p - 0.55) / 0.45).clamp(0.0, 1.0),
        )
    }

    pub fn done(&self) -> bool {
        self.t >= self.total()
    }
}

/// One row of the reply sheet.
struct ReplyRow {
    index: usize,
    text: SharedString,
    aria: SharedString,
    own: bool,
    last: bool,
}

#[derive(Default)]
pub(crate) struct ReplyUi {
    /// The ringing call the sheet declines.
    pub call_id: Option<i32>,
    /// The sheet is over the In-Call screen (a waiting caller), not the Incoming one.
    pub over_call: bool,
    pub rows: Vec<SharedString>,
    /// What TalkBack reads for each row, built once when the sheet opens.
    pub aria: Vec<SharedString>,
    /// "Decline with message, {n} replies".
    pub title_aria: SharedString,
    pub s31: Option<S31>,
}

impl ReplyUi {
    pub fn tick(&mut self, dt: Duration) -> bool {
        match &mut self.s31 {
            Some(s) => {
                s.t += dt.as_secs_f32();
                if s.done() {
                    self.s31 = None;
                }
                true
            }
            None => false,
        }
    }
}

/// Where a reply row's centre is, from the sheet's fixed geometry: the panel is
/// 10 px off the bottom, rows are 56 px and the last one has 16 px under it.
pub(crate) fn row_center_y(viewport_h: f32, rows_total: usize, index: usize) -> f32 {
    viewport_h - 10.0 - 16.0 - (rows_total - index) as f32 * ROW_H + ROW_H / 2.0
}

impl PhoneApp {
    /// The reply sheet is up.
    pub(crate) fn reply_is_open(&self) -> bool {
        self.reply.call_id.is_some() && self.sheet_kind == SheetKind::Reply && self.sheet.is_open()
    }

    /// Ids of the reply sheet's own controls: they work over the Incoming screen and the call.
    pub(crate) fn reply_owns(&self, id: &str) -> bool {
        id.starts_with("rp-") && self.reply_is_open()
    }

    /// Opens the sheet for ringing call `call_id`. With no replies nothing opens
    /// (over a call the pill says where to add some).
    pub(crate) fn reply_open(
        &mut self,
        call_id: i32,
        over_call: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let rows: Vec<SharedString> = self
            .backend
            .read(cx)
            .prefs()
            .quick_replies
            .iter()
            .map(|r| SharedString::from(r.clone()))
            .collect();
        if rows.is_empty() {
            if over_call {
                self.show_notice_pill("Add replies in Settings", None, false, cx);
            }
            return;
        }
        if self.reply_is_open() {
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        self.reply = ReplyUi {
            call_id: Some(call_id),
            over_call,
            aria: rows
                .iter()
                .map(|t| SharedString::from(format!("{t}. Double tap to decline and send")))
                .collect(),
            title_aria: format!("Decline with message, {} replies", rows.len()).into(),
            rows,
            s31: None,
        };
        self.sheet_kind = SheetKind::Reply;
        self.sheet.open();
        self.presses.release_all();
        self.update_back(window);
        cx.notify();
    }

    /// Dragged away, scrim tap or Back: back to the ringing card, nothing declined.
    pub(crate) fn reply_close(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.sheet.close();
        self.presses.release_all();
        self.update_back(window);
        cx.notify();
    }

    /// A row of the sheet was tapped (finger-up).
    pub(crate) fn reply_tap(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        if !self.reply_is_open() {
            return;
        }
        let Some(call_id) = self.reply.call_id else {
            return;
        };
        let rows = self.reply.rows.len();
        let own = id == "rp-own";
        let index = ROW_IDS
            .iter()
            .position(|r| *r == id)
            .filter(|i| own || *i < rows);
        let Some(index) = index else { return };
        let text = (!own).then(|| self.reply.rows[index].clone());
        let outcome = self.backend.update(cx, |backend, cx| match &text {
            Some(text) => backend.reject_with_message(call_id, text, cx),
            None => backend.reject_and_compose(call_id, cx),
        });
        if outcome == ReplyOutcome::Failed {
            // The call is gone or Android refused: nothing was declined.
            self.reply_close(window, cx);
            self.show_notice_pill("Couldn't decline the call", None, false, cx);
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        let viewport = window.viewport_size();
        let (vw, vh) = (f32::from(viewport.width), f32::from(viewport.height));
        let over_call = self.reply.over_call;
        if let Some(text) = text {
            let to = if over_call {
                (
                    crate::zone::slots::WAITING_INSET + 16.0 + 20.0,
                    48.0 + crate::zone::slots::WAITING_TOP + 16.0 + 20.0,
                )
            } else {
                (vw / 2.0, 186.0)
            };
            self.reply.s31 = Some(S31 {
                text,
                from: (vw / 2.0, row_center_y(vh, rows + 1, index)),
                to,
                t: 0.0,
                reduced: window.prefers_reduced_motion(),
            });
        }
        let label = outcome.label();
        if over_call {
            // The call underneath is untouched; only the waiting caller was declined.
            self.incall.waiting_acted = true;
            self.show_notice_pill(label, None, false, cx);
        } else {
            self.incoming_reply_label(label);
        }
        self.sheet.close();
        self.presses.release_all();
        self.update_back(window);
        cx.notify();
    }

    /// Once per frame: the sheet closes when its call stops ringing, and the
    /// state is forgotten once nothing of it is on screen.
    pub(crate) fn sync_reply(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(id) = self.reply.call_id else {
            return;
        };
        let ringing = self
            .backend
            .read(cx)
            .calls()
            .call(id)
            .is_some_and(|c| c.call_state() == CallState::Ringing);
        if !ringing && self.reply_is_open() {
            self.reply_close(window, cx);
        }
        if !self.sheet.is_visible() && self.reply.s31.is_none() && !self.reply_is_open() {
            self.reply = ReplyUi::default();
        }
    }

    /// The notification's Message action (story 18.7): open the sheet on the
    /// Incoming screen for that call, as soon as the screen has it.
    pub(crate) fn sync_ui_request(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        use rovsti_core::wire::UiRequestKind;
        let Some(request) = self.backend.read(cx).peek_ui_request().copied() else {
            return;
        };
        if request.kind != UiRequestKind::ShowReply {
            // Kinds that Rust shows elsewhere are taken and ignored here.
            self.backend.update(cx, |b, _| b.take_ui_request());
            return;
        }
        let Some(id) = request.call_id else {
            self.backend.update(cx, |b, _| b.take_ui_request());
            return;
        };
        let gone = self
            .backend
            .read(cx)
            .calls()
            .call(id)
            .is_none_or(|c| c.call_state() != CallState::Ringing);
        if gone {
            self.backend.update(cx, |b, _| b.take_ui_request());
        } else if self.incoming.call_id() == Some(id) && self.incoming.is_ringing() {
            self.backend.update(cx, |b, _| b.take_ui_request());
            self.reply_open(id, false, window, cx);
        }
    }

    // ---- drawing --------------------------------------------------------------------

    fn reply_row(&self, theme: Theme, row: ReplyRow, cx: &mut Context<Self>) -> AnyElement {
        let ReplyRow {
            index,
            text,
            aria,
            own,
            last,
        } = row;
        let colors = theme::colors(theme);
        let id = if own { ROW_IDS[6] } else { ROW_IDS[index] };
        let press = self.presses.value(id).clamp(0.0, 1.0);
        let (alpha, lift) = self.sheet.rise(index + 1);
        let ink = if own { colors.accent } else { colors.label };
        div()
            .relative()
            .top(px(lift))
            .opacity(alpha)
            .child(
                div()
                    .id(id)
                    .role(Role::Button)
                    .aria_label(aria)
                    .relative()
                    .h(px(ROW_H))
                    .w_full()
                    .px(px(16.0))
                    .rounded(px(18.0))
                    .bg(Hsla {
                        a: 0.12 * press,
                        ..colors.label
                    })
                    .flex()
                    .items_center()
                    .gap(px(14.0))
                    .child(
                        svg()
                            .path(
                                if own {
                                    Icon::LucidePencil
                                } else {
                                    Icon::LucideMessageCircle
                                }
                                .path(),
                            )
                            .size(px(22.0 * press_scale(press, scale::PRESS_CARD)))
                            .flex_none()
                            .text_color(if own {
                                colors.accent
                            } else {
                                colors.secondary_label
                            }),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .line_clamp(2)
                            .text_size(typography::SUBHEADLINE.size)
                            .line_height(typography::SUBHEADLINE.line)
                            .font_weight(if own {
                                typography::HEADLINE.weight
                            } else {
                                gpui::FontWeight::NORMAL
                            })
                            .text_color(ink)
                            .child(text),
                    )
                    .children((!last).then(|| {
                        div()
                            .absolute()
                            .bottom_0()
                            .left(px(52.0))
                            .right(px(8.0))
                            .h(px(0.5))
                            .bg(colors.separator)
                    }))
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, window, cx| {
                            this.presses.down(id);
                            this.reply_tap(id, window, cx);
                        }),
                    )
                    .child(touch_surface(Self::touch_listener(cx, Some(id.into())))),
            )
            .into_any_element()
    }

    fn reply_body(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let n = self.reply.rows.len();
        let mut rows: Vec<AnyElement> = (0..n)
            .map(|i| {
                let row = ReplyRow {
                    index: i,
                    text: self.reply.rows[i].clone(),
                    aria: self.reply.aria[i].clone(),
                    own: false,
                    last: false,
                };
                self.reply_row(theme, row, cx)
            })
            .collect();
        let own = ReplyRow {
            index: n,
            text: "Write your own…".into(),
            aria: "Write your own. Double tap to decline and write a message".into(),
            own: true,
            last: true,
        };
        rows.push(self.reply_row(theme, own, cx));
        div()
            .flex()
            .flex_col()
            .px(px(10.0))
            .pb(px(16.0))
            .child(
                div()
                    .id("rp-title")
                    .role(Role::Heading)
                    .aria_label(self.reply.title_aria.clone())
                    .h(px(34.0))
                    .px(px(16.0))
                    .flex()
                    .items_center()
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child("Decline with message"),
            )
            .children(rows)
            .into_any_element()
    }

    /// The sheet, drawn over everything the ringing screen or the call draws.
    pub(crate) fn reply_overlay(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if self.sheet_kind != SheetKind::Reply || !self.sheet.is_visible() {
            return None;
        }
        let body = self.reply_body(theme, cx);
        let entity = cx.entity();
        let drag_entity = entity.clone();
        self.sheet.render(
            theme,
            window,
            "Decline with message",
            body,
            move |window, cx| {
                entity.update(cx, |this, cx| this.reply_close(window, cx));
            },
            move |phase, y, _, cx| {
                drag_entity.update(cx, |this, cx| {
                    this.sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }

    /// S31: the chosen text in flight.
    pub(crate) fn reply_bubble(&self, theme: Theme) -> Option<AnyElement> {
        let s = self.reply.s31.as_ref()?;
        let colors = theme::colors(theme);
        let (x, y, scale, alpha) = s.pose();
        Some(
            div()
                .absolute()
                .left(px(x - 130.0))
                .top(px(y - 20.0))
                .w(px(260.0))
                .h(px(40.0))
                .flex()
                .items_center()
                .justify_center()
                .opacity(alpha)
                .child(
                    flat_glass(theme, Some((call_colors(theme).answer, 0.12)))
                        .max_w(px(260.0 * scale))
                        .px(px(14.0 * scale))
                        .h(px(36.0 * scale))
                        .rounded_full()
                        .flex()
                        .items_center()
                        .overflow_hidden()
                        .text_size(px(14.0 * scale))
                        .text_color(colors.label)
                        .child(s.text.clone()),
                )
                .into_any_element(),
        )
    }
}

// ---- the Quick replies editor ------------------------------------------------------------

const EDIT_ROW_H: f32 = 56.0;
const FIELD_ARIA: [&str; MAX_REPLIES] = [
    "Reply 1", "Reply 2", "Reply 3", "Reply 4", "Reply 5", "Reply 6",
];
const GRIP_IDS: [&str; MAX_REPLIES] = [
    "set-qr-grip-0",
    "set-qr-grip-1",
    "set-qr-grip-2",
    "set-qr-grip-3",
    "set-qr-grip-4",
    "set-qr-grip-5",
];
const FIELD_IDS: [&str; MAX_REPLIES] = [
    "set-qr-f-0",
    "set-qr-f-1",
    "set-qr-f-2",
    "set-qr-f-3",
    "set-qr-f-4",
    "set-qr-f-5",
];
const CLEAR_IDS: [&str; MAX_REPLIES] = [
    "set-qr-clear-0",
    "set-qr-clear-1",
    "set-qr-clear-2",
    "set-qr-clear-3",
    "set-qr-clear-4",
    "set-qr-clear-5",
];
const UP_IDS: [&str; MAX_REPLIES] = [
    "set-qr-up-0",
    "set-qr-up-1",
    "set-qr-up-2",
    "set-qr-up-3",
    "set-qr-up-4",
    "set-qr-up-5",
];
const DOWN_IDS: [&str; MAX_REPLIES] = [
    "set-qr-down-0",
    "set-qr-down-1",
    "set-qr-down-2",
    "set-qr-down-3",
    "set-qr-down-4",
    "set-qr-down-5",
];

/// A row being dragged by its handle.
#[derive(Clone, Copy, Debug)]
struct RowDrag {
    slot: usize,
    start_y: f32,
    dy: f32,
}

pub(crate) struct ReplyEditor {
    pub fields: Vec<Entity<TextField>>,
    /// Field index shown in each slot, top to bottom.
    order: Vec<usize>,
    seen: [u64; MAX_REPLIES],
    drag: Option<RowDrag>,
    /// Slot offsets while a row is dragged (the list reflows by transform on `select`).
    shift: [Spring; MAX_REPLIES],
    /// Which slot had focus at the last frame.
    focused: Option<usize>,
    pub confirm_reset: bool,
}

impl ReplyEditor {
    pub fn new(cx: &mut Context<PhoneApp>) -> Self {
        Self {
            fields: (0..MAX_REPLIES)
                .map(|_| {
                    cx.new(|cx| {
                        let mut f = TextField::with_input(
                            gpui::TextInputPurpose::Text,
                            gpui::TextInputAction::Done,
                            cx,
                        );
                        f.set_max_chars(Some(MAX_REPLY_CHARS));
                        f
                    })
                })
                .collect(),
            order: Vec::new(),
            seen: [0; MAX_REPLIES],
            drag: None,
            shift: std::array::from_fn(|_| {
                Spring::new(0.0, motion_tokens::select()).with_epsilon(0.05)
            }),
            focused: None,
            confirm_reset: false,
        }
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        for s in &mut self.shift {
            moving |= s.tick(dt);
        }
        moving
    }

    /// The slot the dragged row would land in.
    fn target_slot(&self) -> Option<usize> {
        let d = self.drag?;
        let last = self.order.len().saturating_sub(1) as f32;
        Some((d.slot as f32 + d.dy / EDIT_ROW_H).round().clamp(0.0, last) as usize)
    }
}

impl PhoneApp {
    pub(crate) fn replies_opened(&mut self, cx: &mut Context<Self>) {
        self.replies_load(cx);
    }

    pub(crate) fn replies_closed(&mut self, cx: &mut Context<Self>) {
        self.replies_ed.drag = None;
        self.replies_ed.confirm_reset = false;
        for s in &mut self.replies_ed.shift {
            s.snap(0.0);
        }
        self.replies_save(cx);
    }

    /// Fills the fields from the stored replies.
    fn replies_load(&mut self, cx: &mut Context<Self>) {
        let texts = self.backend.read(cx).prefs().quick_replies.clone();
        let ed = &mut self.replies_ed;
        ed.order = (0..texts.len().min(MAX_REPLIES)).collect();
        for (i, field) in ed.fields.iter().enumerate() {
            let text = texts.get(i).map_or("", String::as_str);
            field.update(cx, |f, cx| f.set(text, cx));
        }
        for (i, field) in ed.fields.iter().enumerate() {
            ed.seen[i] = field.read(cx).edits;
        }
        ed.drag = None;
        ed.confirm_reset = false;
    }

    /// Undo of Clear: puts `text` back in the field at `slot`.
    pub(crate) fn replies_restore(
        &mut self,
        slot: usize,
        text: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if let Some(&f) = self.replies_ed.order.get(slot) {
            self.replies_ed.fields[f].update(cx, |f, cx| f.set(text, cx));
            self.replies_field_changed(window, cx);
            cx.notify();
        }
    }

    /// One of the fields changed: autosave (the field itself caps at the maximum).
    pub(crate) fn replies_field_changed(&mut self, _window: &Window, cx: &mut Context<Self>) {
        if !self.settings.sub_open() || self.settings.sub != Some(crate::settings_ui::Sub::Replies)
        {
            return;
        }
        let mut changed = false;
        for i in 0..MAX_REPLIES {
            let edits = self.replies_ed.fields[i].read(cx).edits;
            if edits == self.replies_ed.seen[i] {
                continue;
            }
            self.replies_ed.seen[i] = edits;
            changed = true;
        }
        if changed {
            self.replies_save(cx);
            cx.notify();
        }
    }

    /// Autosave: the non-empty rows in order, through `update_prefs` (the writer
    /// coalesces to one write per 250 ms).
    fn replies_save(&mut self, cx: &mut Context<Self>) {
        let texts: Vec<String> = self
            .replies_ed
            .order
            .iter()
            .filter_map(|&i| {
                let t = self.replies_ed.fields[i].read(cx).text().trim().to_owned();
                (!t.is_empty()).then_some(t)
            })
            .collect();
        self.backend
            .update(cx, |b, cx| b.update_prefs(cx, |p| p.quick_replies = texts));
        // The "Quick replies" row under the page shows the count.
        self.sync_settings_rows(cx, false);
    }

    pub(crate) fn replies_tap(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        match id {
            "set-qr-add" => self.replies_add(window, cx),
            "set-qr-reset" => {
                self.replies_ed.confirm_reset = true;
                self.sheet_kind = SheetKind::ResetReplies;
                self.sheet.open();
            }
            _ => {
                let slot_of = |ids: &[&str]| ids.iter().position(|i| *i == id);
                if let Some(slot) = slot_of(&FIELD_IDS) {
                    self.replies_focus(slot, window, cx);
                } else if let Some(slot) = slot_of(&CLEAR_IDS) {
                    if let Some(&f) = self.replies_ed.order.get(slot) {
                        let old = self.replies_ed.fields[f].read(cx).text().to_owned();
                        self.replies_ed.fields[f].update(cx, |f, cx| f.set("", cx));
                        self.replies_field_changed(window, cx);
                        if !old.trim().is_empty() {
                            self.show_pill_action(
                                "Reply cleared",
                                "Undo",
                                crate::block_ui::PillAction::RestoreReply(slot, old.into()),
                                cx,
                            );
                        }
                    }
                } else if let Some(slot) = slot_of(&UP_IDS) {
                    self.replies_move(slot, slot.saturating_sub(1), window, cx);
                } else if let Some(slot) = slot_of(&DOWN_IDS) {
                    self.replies_move(slot, slot + 1, window, cx);
                }
            }
        }
        cx.notify();
    }

    /// The confirm sheet's buttons.
    pub(crate) fn replies_sheet_act(
        &mut self,
        id: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if id == "sheet-qr-yes" {
            self.backend.update(cx, |b, cx| {
                b.update_prefs(cx, |p| {
                    p.quick_replies = DEFAULT_REPLIES.iter().map(|s| (*s).to_owned()).collect();
                });
            });
            self.replies_load(cx);
            self.sync_settings_rows(cx, false);
            rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        }
        self.replies_ed.confirm_reset = false;
        self.close_sheet(cx);
    }

    fn replies_focus(&mut self, slot: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(&f) = self.replies_ed.order.get(slot) else {
            return;
        };
        let focus = self.replies_ed.fields[f].read(cx).focus_handle();
        window.focus(&focus, cx);
        window.show_soft_keyboard();
    }

    fn replies_add(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !settings::can_add_reply(self.replies_ed.order.len()) {
            return;
        }
        let free = (0..MAX_REPLIES).find(|i| !self.replies_ed.order.contains(i));
        let Some(free) = free else { return };
        self.replies_ed.fields[free].update(cx, |f, cx| f.set("", cx));
        self.replies_ed.seen[free] = self.replies_ed.fields[free].read(cx).edits;
        self.replies_ed.order.push(free);
        let slot = self.replies_ed.order.len() - 1;
        self.replies_focus(slot, window, cx);
    }

    fn replies_move(
        &mut self,
        from: usize,
        to: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let n = self.replies_ed.order.len();
        if from >= n || to >= n || from == to {
            return;
        }
        settings::move_reply(&mut self.replies_ed.order, from, to);
        for s in &mut self.replies_ed.shift {
            s.snap(0.0);
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        self.replies_save(cx);
    }

    /// Raw touches on a row's handle: the row follows the finger, the others
    /// make room by transform on `select`, and the order changes on release.
    pub(crate) fn replies_drag(
        &mut self,
        slot: usize,
        phase: TouchPhase,
        y: f32,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match phase {
            TouchPhase::Started => {
                if self.replies_ed.drag.is_none() {
                    self.replies_ed.drag = Some(RowDrag {
                        slot,
                        start_y: y,
                        dy: 0.0,
                    });
                    rovsti_ui::haptics::perform(window, HapticFeedback::LongPress);
                }
            }
            TouchPhase::Moved => {
                let Some(d) = &mut self.replies_ed.drag else {
                    return;
                };
                d.dy = y - d.start_y;
                let from = d.slot;
                let to = self.replies_ed.target_slot().unwrap_or(from);
                let reduced = window.prefers_reduced_motion();
                for j in 0..self.replies_ed.order.len() {
                    let shift = if j == from {
                        0.0
                    } else if from < j && j <= to {
                        -EDIT_ROW_H
                    } else if to <= j && j < from {
                        EDIT_ROW_H
                    } else {
                        0.0
                    };
                    let s = &mut self.replies_ed.shift[j];
                    if reduced {
                        s.snap(shift);
                    } else if (s.target - shift).abs() > f32::EPSILON {
                        s.set_target(shift);
                    }
                }
            }
            TouchPhase::Ended | TouchPhase::Cancelled => {
                let Some(d) = self.replies_ed.drag.take() else {
                    return;
                };
                let to = if phase == TouchPhase::Ended {
                    let last = self.replies_ed.order.len().saturating_sub(1) as f32;
                    (d.slot as f32 + d.dy / EDIT_ROW_H).round().clamp(0.0, last) as usize
                } else {
                    d.slot
                };
                for s in &mut self.replies_ed.shift {
                    s.snap(0.0);
                }
                if to != d.slot {
                    self.replies_move(d.slot, to, window, cx);
                }
            }
        }
        cx.notify();
    }

    fn edit_row(
        &self,
        theme: Theme,
        slot: usize,
        focused: bool,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let ed = &self.replies_ed;
        let n = ed.order.len();
        let field = ed.fields[ed.order[slot]].clone();
        let text: SharedString = field.read(cx).text().to_owned().into();
        let count = text.chars().count();
        let dragging = ed.drag.is_some_and(|d| d.slot == slot);
        let offset = match ed.drag {
            Some(d) if d.slot == slot => d.dy.clamp(
                -(slot as f32) * EDIT_ROW_H,
                (n - 1 - slot) as f32 * EDIT_ROW_H,
            ),
            _ => ed.shift[slot].value,
        };
        let grip_press = self.presses.value(GRIP_IDS[slot]).clamp(0.0, 1.0);
        let button = |id: &'static str, icon: Icon, label: &'static str, cx: &mut Context<Self>| {
            let press = self.presses.value(id).clamp(0.0, 1.0);
            div()
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .relative()
                .flex_none()
                .size(px(32.0))
                .flex()
                .items_center()
                .justify_center()
                .child(
                    svg()
                        .path(icon.path())
                        .size(px(18.0 * press_scale(press, scale::PRESS_CARD)))
                        .text_color(colors.secondary_label),
                )
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id);
                        this.replies_tap(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
        };
        let text_box = div()
            .id(FIELD_IDS[slot])
            .role(Role::TextInput)
            .aria_label(FIELD_ARIA[slot])
            .relative()
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .items_center()
            .text_size(typography::BODY.size)
            .child(bare_field(
                theme,
                FIELD_IDS[slot],
                &field,
                "Write a reply",
                Bare {
                    height: None,
                    pad_v: 0.0,
                },
                window,
                cx,
            ))
            .on_mouse_down(MouseButton::Left, {
                cx.listener(move |this, _, window, cx| {
                    this.presses.down(FIELD_IDS[slot]);
                    this.replies_tap(FIELD_IDS[slot], window, cx);
                })
            })
            .child(touch_surface(Self::touch_listener(
                cx,
                Some(FIELD_IDS[slot].into()),
            )));
        div()
            .id(("qr-row", slot))
            .relative()
            .top(px(offset))
            .h(px(EDIT_ROW_H))
            .w_full()
            .px(px(16.0))
            .flex()
            .items_center()
            .gap(px(8.0))
            .when(dragging, |row| {
                row.bg(Hsla {
                    a: 0.9,
                    ..colors.fill
                })
                .shadow(shadow::glass(theme.is_dark()))
            })
            .child(text_box)
            .children((count >= REPLY_COUNTER_AT).then(|| {
                div()
                    .flex_none()
                    .text_size(typography::CAPTION.size)
                    .text_color(if count >= MAX_REPLY_CHARS {
                        call_colors(theme).danger
                    } else {
                        colors.secondary_label
                    })
                    .child(SharedString::from(format!("{count}/{MAX_REPLY_CHARS}")))
            }))
            .children(
                (focused && slot > 0)
                    .then(|| button(UP_IDS[slot], Icon::LucideChevronUp, "Move up", cx)),
            )
            .children(
                (focused && slot + 1 < n)
                    .then(|| button(DOWN_IDS[slot], Icon::LucideChevronDown, "Move down", cx)),
            )
            .children(focused.then(|| {
                let press = self.presses.value(CLEAR_IDS[slot]).clamp(0.0, 1.0);
                div()
                    .id(CLEAR_IDS[slot])
                    .role(Role::Button)
                    .aria_label("Clear")
                    .relative()
                    .flex_none()
                    .h(px(32.0))
                    .px(px(8.0))
                    .flex()
                    .items_center()
                    .text_size(typography::SUBHEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(Hsla {
                        a: 1.0 - 0.3 * press,
                        ..colors.accent
                    })
                    .child("Clear")
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, window, cx| {
                            this.presses.down(CLEAR_IDS[slot]);
                            this.replies_tap(CLEAR_IDS[slot], window, cx);
                        }),
                    )
                    .child(touch_surface(Self::touch_listener(
                        cx,
                        Some(CLEAR_IDS[slot].into()),
                    )))
            }))
            .child(
                div()
                    .id(GRIP_IDS[slot])
                    .role(Role::Button)
                    .aria_label("Reorder")
                    .relative()
                    .flex_none()
                    .size(px(36.0))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        svg()
                            .path(Icon::LucideGripVertical.path())
                            .size(px(20.0 * press_scale(grip_press, scale::PRESS_CARD)))
                            .text_color(colors.tertiary_label),
                    )
                    .child(drag_surface({
                        let entity = cx.entity();
                        move |phase, position, window, cx| {
                            let y = f32::from(position.y);
                            entity.update(cx, |this, cx| {
                                if phase == TouchPhase::Started {
                                    this.presses.touch_down(GRIP_IDS[slot]);
                                }
                                this.replies_drag(slot, phase, y, window, cx);
                                if phase != TouchPhase::Started && phase != TouchPhase::Moved {
                                    this.presses.release_all();
                                }
                            });
                        }
                    })),
            )
            .children((slot + 1 < n).then(|| {
                div()
                    .absolute()
                    .bottom_0()
                    .left(px(16.0))
                    .right_0()
                    .h(px(0.33))
                    .bg(colors.separator)
            }))
            .into_any_element()
    }

    /// The Quick replies page under its large title.
    pub(crate) fn replies_body(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let n = self.replies_ed.order.len();
        let focused: Option<usize> = (0..n).find(|&s| {
            self.replies_ed.fields[self.replies_ed.order[s]]
                .read(cx)
                .focus_handle()
                .is_focused(window)
        });
        self.replies_ed.focused = focused;
        let can_add = settings::can_add_reply(n);
        let rows: Vec<AnyElement> = (0..n)
            .map(|s| self.edit_row(theme, s, focused == Some(s), window, cx))
            .collect();
        let action = |id: &'static str,
                      label: &'static str,
                      note: Option<&'static str>,
                      ink: Hsla,
                      enabled: bool,
                      cx: &mut Context<Self>| {
            let press = self.presses.value(id).clamp(0.0, 1.0);
            let mut el = div()
                .id(id)
                .role(Role::Button)
                .aria_label(match note {
                    Some(note) => SharedString::from(format!("{label}. {note}")),
                    None => SharedString::from(label),
                })
                .aria_disabled(!enabled)
                .relative()
                .mx(px(16.0))
                .h(px(52.0))
                .rounded(radius::GROUPED_LIST)
                .bg(Hsla {
                    a: 0.72,
                    ..colors.secondary_background
                })
                .px(px(16.0))
                .flex()
                .items_center()
                .justify_between()
                .opacity(if enabled { 1.0 } else { 0.5 })
                .child(
                    div()
                        .text_size(typography::HEADLINE.size * press_scale(press, scale::PRESS))
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(ink)
                        .child(label),
                )
                .children(note.map(|note| {
                    div()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child(note)
                }));
            if enabled {
                el = el
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, window, cx| {
                            this.presses.down(id);
                            this.replies_tap(id, window, cx);
                        }),
                    )
                    .child(touch_surface(Self::touch_listener(cx, Some(id.into()))));
            }
            el.into_any_element()
        };
        let add = action(
            "set-qr-add",
            "Add reply",
            (!can_add).then_some("Up to 6 replies"),
            colors.accent,
            can_add,
            cx,
        );
        let reset = action(
            "set-qr-reset",
            "Reset to defaults",
            None,
            call_colors(theme).danger,
            true,
            cx,
        );
        div()
            .id("qr-scroll")
            .flex_1()
            .min_h_0()
            .overflow_y_scroll()
            .pb(px(48.0))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(16.0))
                    .pt(px(8.0))
                    .child(
                        div()
                            .mx(px(16.0))
                            .rounded(radius::GROUPED_LIST)
                            .overflow_hidden()
                            .bg(Hsla {
                                a: 0.72,
                                ..colors.secondary_background
                            })
                            .flex()
                            .flex_col()
                            .when(n == 0, |list| {
                                list.child(
                                    div()
                                        .h(px(EDIT_ROW_H))
                                        .px(px(16.0))
                                        .flex()
                                        .items_center()
                                        .text_size(typography::BODY.size)
                                        .text_color(colors.tertiary_label)
                                        .child("No replies"),
                                )
                            })
                            .children(rows),
                    )
                    .children((n == 0).then(|| {
                        div()
                            .px(px(32.0))
                            .text_size(typography::CAPTION.size)
                            .text_color(colors.secondary_label)
                            .child("With no replies, Message is hidden")
                    }))
                    .child(add)
                    .child(reset),
            )
            .into_any_element()
    }

    /// The confirm sheet of "Reset to defaults".
    pub(crate) fn replies_reset_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let colors = theme::colors(theme);
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
                .h(px(52.0 * press_scale(press, scale::PRESS)))
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
                        this.replies_sheet_act(id, window, cx);
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
                    .child("Reset quick replies?"),
            )
            .child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.secondary_label)
                    .child("Your replies are replaced by the three defaults."),
            )
            .child(button(
                "sheet-qr-yes",
                "Reset",
                call_colors(theme).danger,
                gpui::white(),
                cx,
            ))
            .child(button(
                "sheet-qr-no",
                "Cancel",
                colors.fill,
                colors.label,
                cx,
            ))
            .into_any_element();
        self.modal_sheet(theme, window, "Reset quick replies", body, cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn row_centres_follow_the_sheet_geometry() {
        // Three replies and "Write your own": the last row is 16 px above the panel's end.
        let h = 874.0;
        let last = row_center_y(h, 4, 3);
        assert!((last - (h - 10.0 - 16.0 - 28.0)).abs() < 1e-4);
        assert!((row_center_y(h, 4, 2) - (last - ROW_H)).abs() < 1e-4);
    }

    #[test]
    fn s31_flies_to_the_avatar_and_fades() {
        let mut s = S31 {
            text: "Hi".into(),
            from: (200.0, 700.0),
            to: (200.0, 186.0),
            t: 0.0,
            reduced: false,
        };
        let (_, y0, k0, a0) = s.pose();
        assert!((y0 - 700.0).abs() < 1e-3 && (k0 - 1.0).abs() < 1e-3 && (a0 - 1.0).abs() < 1e-3);
        s.t = S31_SECS * 0.5;
        let (_, y, k, _) = s.pose();
        assert!(y < 700.0 && y > 186.0 && k < 1.0);
        s.t = S31_SECS;
        let (_, y1, _, a1) = s.pose();
        assert!((y1 - 186.0).abs() < 1e-3 && a1 <= 0.001);
        assert!(s.done());
    }

    #[test]
    fn reduced_motion_fades_in_place() {
        let s = S31 {
            text: "Hi".into(),
            from: (200.0, 700.0),
            to: (200.0, 186.0),
            t: S31_REDUCED_SECS / 2.0,
            reduced: true,
        };
        let (x, y, k, a) = s.pose();
        assert_eq!((x, y, k), (200.0, 700.0, 1.0));
        assert!((a - 0.5).abs() < 1e-4);
    }

    #[test]
    fn the_drag_target_slot_clamps_to_the_list() {
        let mut ed_order: Vec<usize> = vec![0, 1, 2];
        let drag = RowDrag {
            slot: 0,
            start_y: 0.0,
            dy: 120.0,
        };
        let last = ed_order.len().saturating_sub(1) as f32;
        let target = (drag.slot as f32 + drag.dy / EDIT_ROW_H)
            .round()
            .clamp(0.0, last) as usize;
        assert_eq!(target, 2);
        settings::move_reply(&mut ed_order, 0, target);
        assert_eq!(ed_order, [1, 2, 0]);
    }
}
