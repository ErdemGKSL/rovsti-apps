//! The call details sheet (U11) with the call's note (U8).
//!
//! A tap on a line of "Recent calls" (Number or Contact sheet) opens it. It lists
//! when, how long, over which channel and with which number, shows the note, and
//! offers Copy details, Add or Edit note, Delete note (confirmed, with Undo) and
//! Delete this call (the same path as the Recents row menu, with Undo). Back
//! returns to the sheet it came from.
//!
//! The pure parts (line ids, routing, glyph flags, labels) are at the top with
//! their tests; the drawing and the actions follow.

use std::sync::Arc;

use gpui::{
    AnyElement, AppContext as _, ClipboardItem, Context, Entity, HapticFeedback, Hsla,
    InteractiveElement, IntoElement, MouseButton, ParentElement, Role, SharedString,
    StatefulInteractiveElement, Styled, Subscription, TextInputAction, Window, div, px, svg,
};
use rovsti_core::{
    call_log::EntryRef,
    call_notes::{self, CallDetails, SetOutcome},
    recents::HistoryLine,
};
use rovsti_ui::{
    Icon,
    motion::press_scale,
    motion_tokens::scale,
    theme::{self, call_colors},
    tokens::{Theme, typography},
    touch::touch_surface,
};

use crate::{
    PhoneApp, SheetKind, backend,
    block_ui::PillAction,
    field::{SearchField, input_layer, on_backspace},
    notes::NotesStore,
};

/// Id prefix of a Number sheet history line (a tap target).
pub(crate) const NUMBER_LINE: &str = "sheet-nhl-";
/// Id prefix of a Contact sheet history line (a tap target).
pub(crate) const CONTACT_LINE: &str = "sheet-chl-";

const BACK: &str = "sheet-dt-back";
const COPY: &str = "sheet-dt-copy";
const SHARE: &str = "sheet-dt-share";
const NOTE: &str = "sheet-dt-note";
const DONE: &str = "sheet-dt-done";
const DEL_NOTE: &str = "sheet-dt-delnote";
const DEL_CALL: &str = "sheet-dt-delcall";
const FIELD: &str = "sheet-dt-field";

/// Height of the note field row.
const NOTE_FIELD_H: f32 = 52.0;

/// The id of history line `index` on the Contact sheet (`contact`) or the Number sheet.
pub(crate) fn details_line_id(contact: bool, index: usize) -> String {
    format!(
        "{}{index}",
        if contact { CONTACT_LINE } else { NUMBER_LINE }
    )
}

/// `(on the Contact sheet, line index)` for a history line id.
pub(crate) fn details_line_index(id: &str) -> Option<(bool, usize)> {
    if let Some(i) = id.strip_prefix(CONTACT_LINE) {
        return i.parse().ok().map(|i| (true, i));
    }
    id.strip_prefix(NUMBER_LINE)?
        .parse()
        .ok()
        .map(|i| (false, i))
}

/// Whether a history control id belongs to the Contact sheet (its ids start with
/// `sheet-c`); the Number sheet's start with `sheet-n`.
pub(crate) fn is_contact_id(id: &str) -> bool {
    id.starts_with("sheet-c")
}

/// Whether each line has a note, in line order (one pass over the notes).
pub(crate) fn note_flags(store: &NotesStore, lines: &[HistoryLine]) -> Vec<bool> {
    let keys: Vec<(String, i64)> = lines
        .iter()
        .map(|l| (l.number.clone(), l.date_ms))
        .collect();
    store.flags(&keys)
}

/// The label of the note action.
pub(crate) fn note_action_label(has_note: bool) -> &'static str {
    if has_note { "Edit note" } else { "Add note" }
}

/// The label of the delete-note row, which asks for a second tap first.
pub(crate) fn delete_note_label(armed: bool) -> &'static str {
    if armed {
        "Tap again to delete"
    } else {
        "Delete note"
    }
}

/// The open call details.
pub(crate) struct DetailsSheet {
    pub(crate) details: CallDetails,
    pub(crate) entry: EntryRef,
    pub(crate) number: SharedString,
    /// The contact's name (or a saved name for the number), for Share.
    who: Option<SharedString>,
    pub(crate) at_ms: i64,
    /// The sheet Back returns to: `Number` or `Contact`.
    pub(crate) back_to: SheetKind,
    /// Opened from the Call notes page: Back closes the sheet.
    from_notes: bool,
    pub(crate) editing: bool,
    pub(crate) field: Entity<SearchField>,
    /// The call was blocked (it lives in the blocked list).
    blocked: bool,
    /// Delete note was tapped once.
    armed: bool,
    seen_submits: u64,
    _field_sub: Subscription,
}

impl PhoneApp {
    /// Opens the details of history line `index` of the Contact or Number sheet.
    pub(crate) fn open_call_details(
        &mut self,
        contact: bool,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let vm = if contact {
            self.contact_sheet
                .as_ref()
                .and_then(|s| s.history.get(index))
        } else {
            self.recents.number_history.get(index)
        };
        let Some(vm) = vm.cloned() else {
            return;
        };
        let who = if contact {
            self.contact_sheet.as_ref().map(|s| s.contact.name.clone())
        } else {
            self.recents
                .number
                .as_ref()
                .map(|r| r.title.clone())
                .filter(|t| t.trim() != vm.number.trim())
        };
        let back_to = if contact {
            SheetKind::Contact
        } else {
            SheetKind::Number
        };
        self.open_details_for(&vm, who, back_to, false, window, cx);
    }

    /// Opens the details of one call from a view-model (the entry point of
    /// the history lines and of the Call notes page). With `from_notes` Back
    /// closes the sheet instead of showing an earlier one.
    pub(crate) fn open_details_for(
        &mut self,
        vm: &crate::recents::HistoryVm,
        who: Option<SharedString>,
        back_to: SheetKind,
        from_notes: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let locale = match self.backend.read(cx).call_log() {
            backend::Load::Ready(log) => log.locale,
            _ => Default::default(),
        };
        let details = call_notes::details(&vm.line, &vm.number, None, locale);
        let field = cx.new(|cx| SearchField::new(cx).with_action(TextInputAction::Done));
        let sub = cx.observe_in(&field, window, |this, _, window, cx| {
            this.details_field_changed(window, cx);
        });
        window.blur();
        self.details = Some(DetailsSheet {
            details,
            entry: vm.entry.clone(),
            number: vm.number.clone(),
            who,
            at_ms: vm.date_ms,
            back_to,
            from_notes,
            editing: false,
            field,
            blocked: vm.blocked,
            armed: false,
            seen_submits: 0,
            _field_sub: sub,
        });
        self.sheet_kind = SheetKind::CallDetails;
        self.presses.release_all();
        cx.notify();
    }

    /// Back: saves a note being typed, then shows the earlier sheet again.
    pub(crate) fn details_back(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.details_commit(cx);
        let Some((back_to, from_notes)) = self.details.as_ref().map(|d| (d.back_to, d.from_notes))
        else {
            return;
        };
        window.blur();
        if from_notes {
            self.close_sheet(cx);
            return;
        }
        self.sheet_kind = back_to;
        self.details = None;
        self.presses.release_all();
        // Its lines follow the note and the call log as they are now.
        self.refresh_sheet_history(cx);
        cx.notify();
    }

    /// Saves the note being typed (if any) and leaves editing. Also called when
    /// the sheet closes by a swipe, so typed text is never lost.
    pub(crate) fn details_commit(&mut self, cx: &mut Context<Self>) {
        let Some(d) = self.details.as_mut().filter(|d| d.editing) else {
            return;
        };
        d.editing = false;
        let text = d.field.read(cx).text().to_owned();
        let (number, at_ms) = (d.number.clone(), d.at_ms);
        match self.notes.set(&number, at_ms, &text, backend::now_ms()) {
            SetOutcome::Added | SetOutcome::Updated => {
                self.save_notes(cx);
                self.show_notice_pill("Note saved", None, false, cx);
            }
            SetOutcome::Removed => {
                self.save_notes(cx);
                self.show_notice_pill("Note deleted", None, false, cx);
            }
            SetOutcome::Unchanged => {}
        }
        cx.notify();
    }

    /// The note field changed: the keyboard's Done saves the note.
    fn details_field_changed(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(d) = self.details.as_mut() else {
            return;
        };
        let submits = d.field.read(cx).submits;
        if submits != d.seen_submits {
            d.seen_submits = submits;
            self.details_commit(cx);
            window.blur();
        }
        cx.notify();
    }

    /// A control of the details sheet was pressed.
    pub(crate) fn details_act(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        let Some(d) = self.details.as_mut() else {
            return;
        };
        if id != DEL_NOTE {
            d.armed = false;
        }
        match id {
            BACK => self.details_back(window, cx),
            COPY => {
                let note = self.notes.get(&d.number, d.at_ms).map(|n| n.text.clone());
                let text = call_notes::details_text(&d.details, note.as_deref());
                if backend::copy_text(&text) != backend::Outcome::Done {
                    cx.write_to_clipboard(ClipboardItem::new_string(text));
                }
                rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
                self.show_notice_pill("Details copied", None, false, cx);
            }
            SHARE => {
                let note = self.notes.get(&d.number, d.at_ms).map(|n| n.text.clone());
                let who = d.who.clone();
                let text = call_notes::share_text(&d.details, who.as_deref(), note.as_deref());
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
            NOTE => {
                let current = self
                    .notes
                    .get(&d.number, d.at_ms)
                    .map(|n| n.text.clone())
                    .unwrap_or_default();
                d.editing = true;
                d.seen_submits = d.field.read(cx).submits;
                d.field.update(cx, |f, cx| f.set(&current, cx));
                let focus = d.field.read(cx).focus_handle();
                window.focus(&focus, cx);
                window.show_soft_keyboard();
            }
            DONE => {
                self.details_commit(cx);
                window.blur();
            }
            FIELD => {
                let focus = d.field.read(cx).focus_handle();
                window.focus(&focus, cx);
                window.show_soft_keyboard();
            }
            DEL_NOTE => {
                if !d.armed {
                    d.armed = true;
                } else {
                    d.armed = false;
                    let (number, at_ms) = (d.number.clone(), d.at_ms);
                    if let Some(note) = self.notes.remove(&number, at_ms) {
                        self.save_notes(cx);
                        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
                        self.show_pill_action(
                            "Note deleted",
                            "Undo",
                            PillAction::RestoreNote(Box::new(note)),
                            cx,
                        );
                    }
                }
            }
            DEL_CALL => {
                let entry = d.entry.clone();
                let blocked = d.blocked;
                self.details_back(window, cx);
                if blocked {
                    self.delete_blocked_refs(&[entry], window, cx);
                } else {
                    self.delete_row_calls(Arc::from([entry]), window, cx);
                }
            }
            _ => {}
        }
        cx.notify();
    }

    /// Undo of Delete note.
    pub(crate) fn details_restore_note(
        &mut self,
        note: call_notes::CallNote,
        cx: &mut Context<Self>,
    ) {
        self.notes.restore(note);
        self.save_notes(cx);
        self.refresh_sheet_history(cx);
        self.show_notice_pill("Note restored", None, false, cx);
    }

    /// The sheet.
    pub(crate) fn details_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let d = self.details.as_ref()?;
        let colors = theme::colors(theme);
        let danger = call_colors(theme).danger;
        let note_text = self.notes.get(&d.number, d.at_ms).map(|n| n.text.clone());

        let back = {
            let press = self.presses.value(BACK).clamp(0.0, 1.0);
            div()
                .id(BACK)
                .role(Role::Button)
                .aria_label("Back")
                .relative()
                .flex_none()
                .h(px(36.0 * press_scale(press, scale::PRESS)))
                .flex()
                .items_center()
                .gap(px(2.0))
                .text_size(typography::BODY.size)
                .text_color(colors.accent)
                .child(
                    svg()
                        .path(Icon::LucideChevronLeft.path())
                        .size(px(20.0))
                        .text_color(colors.accent),
                )
                .child("Back")
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(|this, _, window, cx| {
                        this.presses.down(BACK);
                        this.details_act(BACK, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(BACK.into()))))
        };
        let header = div()
            .flex()
            .flex_col()
            .gap(px(4.0))
            .px(px(18.0))
            .child(div().flex().child(back))
            .child(
                div()
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(d.details.title.clone()),
            );

        let info_row = |label: &'static str, value: String| {
            div()
                .min_h(px(44.0))
                .px(px(16.0))
                .py(px(8.0))
                .flex()
                .items_center()
                .justify_between()
                .gap(px(12.0))
                .child(
                    div()
                        .flex_none()
                        .text_size(typography::BODY.size)
                        .text_color(colors.secondary_label)
                        .child(label),
                )
                .child(
                    div()
                        .min_w_0()
                        .truncate()
                        .text_size(typography::BODY.size)
                        .text_color(colors.label)
                        .child(value),
                )
        };
        let mut info = vec![info_row("When", d.details.when.clone())];
        if let Some(duration) = &d.details.duration {
            info.push(info_row("Duration", duration.clone()));
        }
        info.push(info_row("Channel", d.details.channel.clone()));
        if !d.details.number.is_empty() {
            info.push(info_row("Number", d.details.number.clone()));
        }
        let card_fill = Hsla {
            a: 0.5,
            ..colors.fill
        };
        let info_card = div()
            .mx(px(16.0))
            .flex()
            .flex_col()
            .rounded(px(22.0))
            .overflow_hidden()
            .bg(card_fill)
            .children(info);

        let note_card = if d.editing {
            let field = d.field.clone();
            let focus = field.read(cx).focus_handle();
            let typed: SharedString = field.read(cx).text().to_owned().into();
            let focused = focus.is_focused(window);
            let done = {
                let press = self.presses.value(DONE).clamp(0.0, 1.0);
                div()
                    .id(DONE)
                    .role(Role::Button)
                    .aria_label("Done")
                    .relative()
                    .flex_none()
                    .h(px(NOTE_FIELD_H * press_scale(press, scale::PRESS)))
                    .px(px(6.0))
                    .flex()
                    .items_center()
                    .text_size(typography::BODY.size)
                    .text_color(colors.accent)
                    .child("Done")
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(|this, _, window, cx| {
                            this.presses.down(DONE);
                            this.details_act(DONE, window, cx);
                        }),
                    )
                    .child(touch_surface(Self::touch_listener(cx, Some(DONE.into()))))
            };
            div()
                .mx(px(16.0))
                .pl(px(16.0))
                .pr(px(10.0))
                .rounded(px(22.0))
                .bg(card_fill)
                .flex()
                .items_center()
                .child(
                    div()
                        .id(FIELD)
                        .role(Role::TextInput)
                        .aria_label("Note")
                        .track_focus(&focus)
                        .on_key_down(on_backspace(field.clone()))
                        .relative()
                        .flex_1()
                        .min_w_0()
                        .h(px(NOTE_FIELD_H))
                        .flex()
                        .items_center()
                        .overflow_hidden()
                        .text_size(typography::BODY.size)
                        .child(input_layer(field, cx))
                        .child(if typed.is_empty() && !focused {
                            div()
                                .text_color(colors.tertiary_label)
                                .child("Write a note")
                                .into_any_element()
                        } else {
                            div()
                                .overflow_hidden()
                                .whitespace_nowrap()
                                .text_ellipsis_start()
                                .text_color(colors.label)
                                .child(typed)
                                .into_any_element()
                        })
                        .on_mouse_down(
                            MouseButton::Left,
                            cx.listener(|this, _, window, cx| {
                                this.presses.down(FIELD);
                                this.details_act(FIELD, window, cx);
                            }),
                        )
                        .child(touch_surface(Self::touch_listener(cx, Some(FIELD.into())))),
                )
                .child(done)
                .into_any_element()
        } else {
            div()
                .mx(px(16.0))
                .px(px(16.0))
                .py(px(12.0))
                .rounded(px(22.0))
                .bg(card_fill)
                .flex()
                .flex_col()
                .gap(px(4.0))
                .child(
                    div()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child("Note"),
                )
                .child(match &note_text {
                    Some(text) => div()
                        .text_size(typography::BODY.size)
                        .text_color(colors.label)
                        .child(text.clone()),
                    None => div()
                        .text_size(typography::BODY.size)
                        .text_color(colors.tertiary_label)
                        .child("No note"),
                })
                .into_any_element()
        };

        let action = |id: &'static str,
                      icon: Icon,
                      label: &'static str,
                      ink: Hsla,
                      cx: &mut Context<Self>| {
            let press = self.presses.value(id).clamp(0.0, 1.0);
            div()
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .relative()
                .w_full()
                .h(px(50.0))
                .px(px(20.0))
                .flex()
                .items_center()
                .gap(px(12.0))
                .bg(Hsla {
                    a: 0.9 * press,
                    ..colors.fill
                })
                .text_size(typography::BODY.size)
                .text_color(ink)
                .child(
                    svg()
                        .path(icon.path())
                        .size(px(20.0 * press_scale(press, scale::PRESS_CARD)))
                        .flex_none()
                        .text_color(ink),
                )
                .child(label)
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id);
                        this.details_act(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
        };
        let mut actions: Vec<AnyElement> = vec![
            action(COPY, Icon::LucideCopy, "Copy details", colors.accent, cx).into_any_element(),
            action(SHARE, Icon::LucideShare2, "Share", colors.accent, cx).into_any_element(),
        ];
        if !d.editing {
            actions.push(
                action(
                    NOTE,
                    if note_text.is_some() {
                        Icon::LucidePencil
                    } else {
                        Icon::LucideStickyNote
                    },
                    note_action_label(note_text.is_some()),
                    colors.accent,
                    cx,
                )
                .into_any_element(),
            );
            if note_text.is_some() {
                actions.push(
                    action(
                        DEL_NOTE,
                        Icon::LucideTrash2,
                        delete_note_label(d.armed),
                        danger,
                        cx,
                    )
                    .into_any_element(),
                );
            }
        }
        actions.push(
            action(DEL_CALL, Icon::LucideTrash2, "Delete this call", danger, cx).into_any_element(),
        );

        let body = div()
            .flex()
            .flex_col()
            .gap(px(12.0))
            .pb(px(16.0))
            .child(header)
            .child(info_card)
            .child(note_card)
            .child(div().flex().flex_col().children(actions))
            .into_any_element();
        self.modal_sheet(theme, window, "Call details", body, cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::call_log::CallType;

    #[test]
    fn line_ids_round_trip_for_both_prefixes() {
        for i in [0usize, 1, 9, 10, 99] {
            let n = details_line_id(false, i);
            let c = details_line_id(true, i);
            assert_eq!(n, format!("sheet-nhl-{i}"));
            assert_eq!(c, format!("sheet-chl-{i}"));
            assert_eq!(details_line_index(&n), Some((false, i)));
            assert_eq!(details_line_index(&c), Some((true, i)));
        }
    }

    #[test]
    fn line_ids_reject_other_ids() {
        for id in [
            "sheet-nhl-",
            "sheet-nhl-x",
            "sheet-nhl--1",
            "sheet-nhx-3",
            "sheet-chx-3",
            "sheet-nhist-edit",
            "sheet-dt-back",
            "nhl-3",
        ] {
            assert_eq!(details_line_index(id), None, "{id}");
        }
    }

    #[test]
    fn line_ids_route_to_their_own_sheet() {
        assert!(!is_contact_id(&details_line_id(false, 3)));
        assert!(is_contact_id(&details_line_id(true, 3)));
        // The details sheet's own controls never reach either history router.
        for id in [BACK, COPY, SHARE, NOTE, DONE, DEL_NOTE, DEL_CALL, FIELD] {
            assert!(id.starts_with("sheet-dt-"));
            assert!(
                !id.starts_with("sheet-c") && !id.starts_with("sheet-n"),
                "{id}"
            );
            assert_eq!(details_line_index(id), None);
        }
    }

    fn line(number: &str, date_ms: i64) -> HistoryLine {
        HistoryLine {
            entry: EntryRef::Log(1),
            blocked: false,
            call_type: CallType::Incoming,
            whatsapp: false,
            date_ms,
            duration_s: 10,
            number: number.to_owned(),
        }
    }

    #[test]
    fn note_flags_line_up_with_lines() {
        const T: i64 = 1_700_000_000_000;
        let mut store = NotesStore::default();
        store.set("+905551112233", T - 3_600_000, "call back", T);
        let lines = [
            line("0555 111 22 33", T),
            line("0555 111 22 33", T - 3_600_000 + 5_000),
            line("+905559998877", T - 3_600_000),
            line("", T - 3_600_000),
        ];
        assert_eq!(note_flags(&store, &lines), [false, true, false, false]);
        assert_eq!(note_flags(&store, &[]), Vec::<bool>::new());
        assert_eq!(
            note_flags(&NotesStore::default(), &lines),
            [false, false, false, false]
        );
    }

    #[test]
    fn labels() {
        assert_eq!(note_action_label(false), "Add note");
        assert_eq!(note_action_label(true), "Edit note");
        assert_eq!(delete_note_label(false), "Delete note");
        assert_eq!(delete_note_label(true), "Tap again to delete");
    }
}
