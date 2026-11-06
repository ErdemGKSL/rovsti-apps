//! A single-line text field for forms: the text, the focus handle and the
//! platform input-method plumbing (`EntityInputHandler`).
//!
//! Generalised from the apps' search fields: the keyboard layout
//! ([`TextInputPurpose`]) and the action key ([`TextInputAction`]) are set per
//! field. [`field_box`] draws the glass box around it.

use std::ops::Range;

use gpui::{
    App, Bounds, Context, Entity, EntityInputHandler, FocusHandle, Focusable, IntoElement,
    KeyDownEvent, Pixels, Point, Styled, SurroundingText, TextInputAction, TextInputMode,
    TextInputPurpose, UTF16Selection, Window, canvas, div, px,
};
use liquid_glass_kit::tokens::Theme;

pub struct TextField {
    focus: FocusHandle,
    purpose: TextInputPurpose,
    action: TextInputAction,
    content: String,
    /// The input method's composing text (bytes into `content`).
    marked: Option<Range<usize>>,
    /// Bumped on every edit; the page compares it instead of the text.
    pub edits: u64,
}

impl TextField {
    pub fn new(cx: &mut Context<Self>) -> Self {
        Self::with_input(TextInputPurpose::Text, TextInputAction::Done, cx)
    }

    /// A field that asks the keyboard for `purpose` (URL, phone, ...) and
    /// shows `action` on its enter key.
    pub fn with_input(
        purpose: TextInputPurpose,
        action: TextInputAction,
        cx: &mut Context<Self>,
    ) -> Self {
        Self {
            focus: cx.focus_handle(),
            purpose,
            action,
            content: String::new(),
            marked: None,
            edits: 0,
        }
    }

    /// Sets the text (a pasted value, or a preview typing a query).
    pub fn set(&mut self, text: &str, cx: &mut Context<Self>) {
        self.content = text.replace(['\n', '\r'], " ");
        self.marked = None;
        self.edits += 1;
        cx.notify();
    }

    pub fn text(&self) -> &str {
        &self.content
    }

    pub fn focus_handle(&self) -> FocusHandle {
        self.focus.clone()
    }

    /// Empties the field and keeps focus (the clear button).
    pub fn clear(&mut self, cx: &mut Context<Self>) {
        if !self.content.is_empty() || self.marked.is_some() {
            self.content.clear();
            self.marked = None;
            self.edits += 1;
            cx.notify();
        }
    }

    /// The delete key: removes the last character (shrinking the composing
    /// text if the caret is in it). Keyboards send it as a `backspace` key
    /// event, rather than an input-method edit, when they hold no text.
    pub fn backspace(&mut self, cx: &mut Context<Self>) {
        let Some((start, _)) = self.content.char_indices().next_back() else {
            return;
        };
        let end = self.content.len();
        self.splice(start..end, "");
        self.marked = self
            .marked
            .take()
            .map(|m| m.start..start)
            .filter(|m| !m.is_empty());
        cx.notify();
    }

    fn utf16_to_utf8(&self, offset: usize) -> usize {
        let mut utf16 = 0;
        for (utf8, c) in self.content.char_indices() {
            if utf16 >= offset {
                return utf8;
            }
            utf16 += c.len_utf16();
        }
        self.content.len()
    }

    fn utf8_to_utf16(&self, offset: usize) -> usize {
        self.content[..offset.min(self.content.len())]
            .chars()
            .map(char::len_utf16)
            .sum()
    }

    fn range_from_utf16(&self, range: &Range<usize>) -> Range<usize> {
        self.utf16_to_utf8(range.start)..self.utf16_to_utf8(range.end)
    }

    fn range_to_utf16(&self, range: &Range<usize>) -> Range<usize> {
        self.utf8_to_utf16(range.start)..self.utf8_to_utf16(range.end)
    }

    fn splice(&mut self, range: Range<usize>, text: &str) {
        let range = range.start.min(self.content.len())..range.end.min(self.content.len());
        self.content.replace_range(range, text);
        self.edits += 1;
    }
}

impl Focusable for TextField {
    fn focus_handle(&self, _: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl EntityInputHandler for TextField {
    fn text_input_mode(&self, _: &mut Window, _: &mut Context<Self>) -> TextInputMode {
        TextInputMode::SingleLine
    }

    fn text_input_purpose(&self, _: &mut Window, _: &mut Context<Self>) -> TextInputPurpose {
        self.purpose
    }

    fn text_input_action(&self, _: &mut Window, _: &mut Context<Self>) -> Option<TextInputAction> {
        Some(self.action)
    }

    fn perform_text_input_action(
        &mut self,
        _: TextInputAction,
        window: &mut Window,
        _: &mut Context<Self>,
    ) -> bool {
        // The enter key puts the keyboard away; the text stays and the page
        // reads it when its own button is tapped.
        window.blur();
        true
    }

    fn text_for_range(
        &mut self,
        range: Range<usize>,
        actual: &mut Option<Range<usize>>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<String> {
        let range = self.range_from_utf16(&range);
        actual.replace(self.range_to_utf16(&range));
        Some(self.content[range].to_owned())
    }

    fn selected_text_range(
        &mut self,
        _: bool,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<UTF16Selection> {
        let end = self.utf8_to_utf16(self.content.len());
        Some(UTF16Selection {
            range: end..end,
            reversed: false,
        })
    }

    fn marked_text_range(&self, _: &mut Window, _: &mut Context<Self>) -> Option<Range<usize>> {
        self.marked.as_ref().map(|r| self.range_to_utf16(r))
    }

    fn unmark_text(&mut self, _: &mut Window, _: &mut Context<Self>) {
        self.marked = None;
    }

    fn replace_text_in_range(
        &mut self,
        range: Option<Range<usize>>,
        text: &str,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let range = range
            .as_ref()
            .map(|r| self.range_from_utf16(r))
            .or_else(|| self.marked.clone())
            .unwrap_or(self.content.len()..self.content.len());
        // A single line: line breaks never enter the text.
        let text = text.replace(['\n', '\r'], " ");
        self.splice(range, &text);
        self.marked = None;
        cx.notify();
    }

    fn replace_and_mark_text_in_range(
        &mut self,
        range: Option<Range<usize>>,
        text: &str,
        _: Option<Range<usize>>,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let range = range
            .as_ref()
            .map(|r| self.range_from_utf16(r))
            .or_else(|| self.marked.clone())
            .unwrap_or(self.content.len()..self.content.len());
        let text = text.replace(['\n', '\r'], " ");
        let start = range.start.min(self.content.len());
        self.splice(range, &text);
        self.marked = (!text.is_empty()).then_some(start..start + text.len());
        cx.notify();
    }

    fn surrounding_text(
        &mut self,
        max_bytes: usize,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<SurroundingText> {
        // Without this snapshot the keyboard believes the field is empty, so
        // it sends a bare delete key instead of deleting text.
        let end = self.content.len();
        SurroundingText::from_utf8(&self.content, end, end, self.marked.clone(), max_bytes)
    }

    fn delete_surrounding_text(
        &mut self,
        before_utf16: usize,
        after_utf16: usize,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) -> bool {
        // The caret is always at the end: nothing follows it.
        if self.marked.is_some() || after_utf16 > 0 {
            return false;
        }
        let end = self.content.len();
        let Some(start) = self.utf8_to_utf16(end).checked_sub(before_utf16) else {
            return false;
        };
        let start = self.utf16_to_utf8(start);
        if start < end {
            self.splice(start..end, "");
            cx.notify();
        }
        true
    }

    fn bounds_for_range(
        &mut self,
        _: Range<usize>,
        element_bounds: Bounds<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<Bounds<Pixels>> {
        Some(element_bounds)
    }

    fn character_index_for_point(
        &mut self,
        _: Point<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<usize> {
        Some(self.utf8_to_utf16(self.content.len()))
    }
}

/// A key-down listener for the element that tracks the field's focus:
/// handles the keyboard's delete key when it arrives as a key event.
pub fn on_backspace(
    field: Entity<TextField>,
) -> impl Fn(&KeyDownEvent, &mut Window, &mut App) + 'static {
    move |event, _, cx| {
        if event.keystroke.key == "backspace" {
            field.update(cx, |field, cx| field.backspace(cx));
            cx.stop_propagation();
        }
    }
}

/// An invisible layer that connects the platform keyboard to `field` while it
/// has focus. Put it inside the box that shows the text.
pub fn input_layer(field: Entity<TextField>, cx: &App) -> impl IntoElement {
    let focus = field.read(cx).focus.clone();
    canvas(
        |_, _, _| (),
        move |bounds, (), window, cx| {
            window.handle_input(
                &focus,
                gpui::ElementInputHandler::new(bounds, field.clone()),
                cx,
            );
        },
    )
    .absolute()
    .size_full()
}

/// The glass box around `field`: `placeholder` while it is empty and
/// unfocused, the text and a caret while typing, and a tap that focuses it
/// and raises the soft keyboard.
pub fn field_box(
    theme: Theme,
    id: impl Into<gpui::ElementId>,
    field: &Entity<TextField>,
    placeholder: &'static str,
    window: &Window,
    cx: &App,
) -> impl IntoElement {
    use gpui::{InteractiveElement, MouseButton, ParentElement};
    use liquid_glass_kit::{
        glass::{GlassSpec, glass},
        tokens::{shadow, typography},
    };

    let colors = crate::theme::colors(theme);
    let focus = field.read(cx).focus_handle();
    let text: gpui::SharedString = field.read(cx).text().to_owned().into();
    let typing = focus.is_focused(window);
    let shown = if text.is_empty() && !typing {
        div()
            .truncate()
            .text_color(colors.tertiary_label)
            .child(placeholder)
    } else if typing {
        // While typing, the end of the text is what the owner is looking at (a long
        // address, the last characters just typed): cut the start with the ellipsis,
        // never the end, which the caret follows.
        div()
            .overflow_hidden()
            .whitespace_nowrap()
            .text_ellipsis_start()
            .text_color(colors.label)
            .child(text)
    } else {
        div().truncate().text_color(colors.label).child(text)
    };
    div()
        .id(id)
        .track_focus(&focus)
        .on_key_down(on_backspace(field.clone()))
        .flex_none()
        .h(px(44.0))
        .on_mouse_down(MouseButton::Left, move |_, window, cx| {
            window.focus(&focus, cx);
            window.show_soft_keyboard();
        })
        .child(
            glass(GlassSpec::control(theme))
                .size_full()
                .rounded_full()
                .shadow(shadow::glass(theme.is_dark()))
                .flex()
                .items_center()
                .px(px(16.0))
                .child(
                    div()
                        .relative()
                        .flex_1()
                        .min_w_0()
                        .h_full()
                        .flex()
                        .items_center()
                        .overflow_hidden()
                        .text_size(typography::BODY.size)
                        .child(input_layer(field.clone(), cx))
                        .child(shown)
                        .children(typing.then(|| {
                            div()
                                .flex_none()
                                .ml(px(1.0))
                                .w(px(2.0))
                                .h(px(20.0))
                                .rounded_full()
                                .bg(colors.accent)
                        })),
                ),
        )
}

#[cfg(test)]
mod tests {
    use gpui::{AppContext as _, TestAppContext};

    use super::*;

    #[gpui::test]
    fn set_text_and_backspace(cx: &mut TestAppContext) {
        let field = cx.new(TextField::new);
        field.update(cx, |f, cx| f.set("https://a\nb", cx));
        field.read_with(cx, |f, _| assert_eq!(f.text(), "https://a b"));
        field.update(cx, |f, cx| f.backspace(cx));
        field.read_with(cx, |f, _| assert_eq!(f.text(), "https://a "));
        field.update(cx, |f, cx| f.clear(cx));
        field.read_with(cx, |f, _| assert_eq!(f.text(), ""));
    }
}
