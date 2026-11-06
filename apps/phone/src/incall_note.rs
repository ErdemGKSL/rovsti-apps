//! The in-call note (U17): a small "Note" capsule under the status line, a bottom
//! note card with a text field, and the long-press that copies the caller's number.
//!
//! What is allowed and what is copied is decided by pure helpers in `incall.rs`
//! (`note_allowed`, `note_call_ms`, `copy_number_text`). The note is stored under
//! the number and the time the call rang or was placed, which is how the call log
//! matches it to the logged call afterwards (`call_notes::MATCH_WINDOW_MS`).

use gpui::{
    AnyElement, AppContext as _, ClipboardItem, Context, Entity, HapticFeedback, Hsla,
    InteractiveElement, IntoElement, MouseButton, ParentElement, Role, SharedString,
    StatefulInteractiveElement, Styled, Subscription, TextInputAction, Window, div, px, svg,
};
use rovsti_core::call_notes::SetOutcome;
use rovsti_ui::{
    Icon,
    components::flat_glass,
    motion::press_scale,
    motion_tokens::scale,
    theme,
    tokens::{Theme, typography},
    touch::touch_surface,
};

use crate::{
    PhoneApp, backend,
    field::{SearchField, input_layer, on_backspace},
    incall::{CallView, copy_number_text, note_allowed, note_call_ms},
};

pub(crate) const CAPSULE: &str = "ic-note";
pub(crate) const DONE: &str = "ic-note-done";
pub(crate) const SCRIM: &str = "ic-note-scrim";
pub(crate) const FIELD: &str = "ic-note-field";
/// The caller's name and number block: held, it copies the number.
pub(crate) const WHO: &str = "ic-who";

const FIELD_H: f32 = 52.0;

/// The open note card.
pub(crate) struct InCallNote {
    number: SharedString,
    at_ms: i64,
    pub(crate) field: Entity<SearchField>,
    seen_submits: u64,
    _field_sub: Subscription,
}

/// Whether `id` belongs to the open note card.
pub(crate) fn owns(id: &str) -> bool {
    matches!(id, DONE | SCRIM | FIELD)
}

impl PhoneApp {
    /// Opens the note card for the foreground call.
    fn incall_note_open(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.incall_note.is_some() {
            return;
        }
        let Some(view) = self.incall.view.clone().filter(note_allowed) else {
            return;
        };
        let at_ms = note_call_ms(&view);
        let current = self
            .notes
            .get(&view.number, at_ms)
            .map(|n| n.text.clone())
            .unwrap_or_default();
        let field = cx.new(|cx| SearchField::new(cx).with_action(TextInputAction::Done));
        field.update(cx, |f, cx| f.set(&current, cx));
        let sub = cx.observe_in(&field, window, |this, _, window, cx| {
            this.incall_note_field_changed(window, cx);
        });
        let seen_submits = field.read(cx).submits;
        let focus = field.read(cx).focus_handle();
        window.focus(&focus, cx);
        window.show_soft_keyboard();
        self.incall_note = Some(InCallNote {
            number: view.number.clone(),
            at_ms,
            field,
            seen_submits,
            _field_sub: sub,
        });
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        self.presses.release_all();
        cx.notify();
    }

    /// Saves the typed note and closes the card. Also called when the call ends.
    pub(crate) fn incall_note_commit(&mut self, cx: &mut Context<Self>) {
        let Some(note) = self.incall_note.take() else {
            return;
        };
        let text = note.field.read(cx).text().to_owned();
        match self
            .notes
            .set(&note.number, note.at_ms, &text, backend::now_ms())
        {
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

    fn incall_note_field_changed(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(note) = self.incall_note.as_mut() else {
            return;
        };
        let submits = note.field.read(cx).submits;
        if submits != note.seen_submits {
            note.seen_submits = submits;
            self.incall_note_commit(cx);
            window.blur();
        }
        cx.notify();
    }

    /// A control of the note card or the capsule was pressed.
    pub(crate) fn incall_note_act(
        &mut self,
        id: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match id {
            CAPSULE => self.incall_note_open(window, cx),
            DONE | SCRIM => {
                self.incall_note_commit(cx);
                window.blur();
            }
            FIELD => {
                if let Some(note) = &self.incall_note {
                    let focus = note.field.read(cx).focus_handle();
                    window.focus(&focus, cx);
                    window.show_soft_keyboard();
                }
            }
            _ => {}
        }
        cx.notify();
    }

    /// The caller block was held: copy the number as shown.
    pub(crate) fn incall_copy_number(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(view) = self.incall.view.as_ref() else {
            return;
        };
        match copy_number_text(view) {
            Some(text) => {
                if backend::copy_text(&text) != backend::Outcome::Done {
                    cx.write_to_clipboard(ClipboardItem::new_string(text));
                }
                rovsti_ui::haptics::perform(window, HapticFeedback::LongPress);
                self.show_notice_pill("Number copied", None, false, cx);
            }
            None => {
                rovsti_ui::haptics::perform(window, HapticFeedback::Reject);
                self.show_notice_pill("No number to copy", None, false, cx);
            }
        }
    }

    /// The Note capsule: a pencil and "Note", or a check and "Note" once saved.
    pub(crate) fn incall_note_capsule(
        &self,
        theme: Theme,
        view: &CallView,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !note_allowed(view) {
            return None;
        }
        let colors = theme::colors(theme);
        let saved = self.notes.get(&view.number, note_call_ms(view)).is_some();
        let press = self.presses.value(CAPSULE).clamp(0.0, 1.0);
        let k = press_scale(press, scale::PRESS);
        let (icon, tint, aria) = if saved {
            (Icon::LucideCheck, colors.green, "Edit note, saved")
        } else {
            (Icon::LucidePencil, colors.label, "Add a note")
        };
        Some(
            flat_glass(theme, None)
                .id(CAPSULE)
                .role(Role::Button)
                .aria_label(aria)
                .relative()
                .h(px(26.0 * k))
                .px(px(12.0))
                .rounded_full()
                .flex()
                .items_center()
                .gap(px(6.0))
                .child(
                    svg()
                        .path(icon.path())
                        .size(px(14.0))
                        .text_color(Hsla { a: 1.0, ..tint }),
                )
                .child(
                    div()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.label)
                        .child("Note"),
                )
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(|this, _, window, cx| this.mouse_down(CAPSULE, window, cx)),
                )
                .child(touch_surface(Self::touch_listener(
                    cx,
                    Some(CAPSULE.into()),
                )))
                .into_any_element(),
        )
    }

    /// The bottom note card over a scrim.
    pub(crate) fn incall_note_card(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let note = self.incall_note.as_ref()?;
        let colors = theme::colors(theme);
        let field = note.field.clone();
        let focus = field.read(cx).focus_handle();
        let typed: SharedString = field.read(cx).text().to_owned().into();
        let focused = focus.is_focused(window);
        let press = self.presses.value(DONE).clamp(0.0, 1.0);
        let done = div()
            .id(DONE)
            .role(Role::Button)
            .aria_label("Done")
            .relative()
            .flex_none()
            .h(px(FIELD_H * press_scale(press, scale::PRESS)))
            .px(px(6.0))
            .flex()
            .items_center()
            .text_size(typography::BODY.size)
            .text_color(colors.accent)
            .child("Done")
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, _, window, cx| this.mouse_down(DONE, window, cx)),
            )
            .child(touch_surface(Self::touch_listener(cx, Some(DONE.into()))));
        let row = div()
            .pl(px(16.0))
            .pr(px(10.0))
            .rounded(px(22.0))
            .bg(colors.fill)
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
                    .h(px(FIELD_H))
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
                        cx.listener(|this, _, window, cx| this.mouse_down(FIELD, window, cx)),
                    )
                    .child(touch_surface(Self::touch_listener(cx, Some(FIELD.into())))),
            )
            .child(done);
        Some(
            div()
                .id("ic-note-layer")
                .absolute()
                .inset_0()
                .occlude()
                .flex()
                .flex_col()
                .justify_end()
                .child(
                    div()
                        .id(SCRIM)
                        .absolute()
                        .inset_0()
                        .bg(Hsla {
                            a: 0.35,
                            ..gpui::black()
                        })
                        .on_mouse_down(
                            MouseButton::Left,
                            cx.listener(|this, _, window, cx| this.mouse_down(SCRIM, window, cx)),
                        )
                        .child(touch_surface(Self::touch_listener(cx, Some(SCRIM.into())))),
                )
                .child(
                    div()
                        .relative()
                        .mx(px(16.0))
                        .mb(px(16.0))
                        .p(px(8.0))
                        .rounded(px(30.0))
                        .bg(colors.background)
                        .child(row),
                )
                .into_any_element(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_card_owns_only_its_own_controls() {
        assert!(owns(DONE) && owns(SCRIM) && owns(FIELD));
        assert!(!owns(CAPSULE) && !owns("ic-end") && !owns(WHO));
    }
}
