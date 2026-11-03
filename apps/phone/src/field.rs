//! The text behind the Contacts search field (story 8.2).
//!
//! `SearchField` is state only: the text, the focus handle and the platform
//! input-method plumbing (`EntityInputHandler`). The glass field itself is
//! drawn by `contacts.rs`, which also owns its touch feedback, so the pressable
//! stays on the one raw-touch path. The caret is always drawn after the text.

use std::ops::Range;

use gpui::{
    App, Bounds, Context, Entity, EntityInputHandler, FocusHandle, Focusable, IntoElement,
    KeyDownEvent, Pixels, Point, Styled, SurroundingText, TextInputAction, TextInputMode,
    UTF16Selection, Window, canvas,
};

pub(crate) struct SearchField {
    focus: FocusHandle,
    content: String,
    /// The input method's composing text (bytes into `content`).
    marked: Option<Range<usize>>,
    /// Bumped on every edit; the page compares it instead of the text.
    pub(crate) edits: u64,
}

impl SearchField {
    pub(crate) fn new(cx: &mut Context<Self>) -> Self {
        Self {
            focus: cx.focus_handle(),
            content: String::new(),
            marked: None,
            edits: 0,
        }
    }

    pub(crate) fn text(&self) -> &str {
        &self.content
    }

    pub(crate) fn focus_handle(&self) -> FocusHandle {
        self.focus.clone()
    }

    /// Sets the text (the desktop preview types a query this way; the quick
    /// replies editor loads its rows with it).
    pub(crate) fn set(&mut self, text: &str, cx: &mut Context<Self>) {
        self.content = text.to_owned();
        self.marked = None;
        self.edits += 1;
        cx.notify();
    }

    /// Empties the field and keeps focus (the clear button).
    pub(crate) fn clear(&mut self, cx: &mut Context<Self>) {
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
    pub(crate) fn backspace(&mut self, cx: &mut Context<Self>) {
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

impl Focusable for SearchField {
    fn focus_handle(&self, _: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl EntityInputHandler for SearchField {
    fn text_input_mode(&self, _: &mut Window, _: &mut Context<Self>) -> TextInputMode {
        TextInputMode::SingleLine
    }

    fn text_input_action(&self, _: &mut Window, _: &mut Context<Self>) -> Option<TextInputAction> {
        Some(TextInputAction::Search)
    }

    fn perform_text_input_action(
        &mut self,
        _: TextInputAction,
        window: &mut Window,
        _: &mut Context<Self>,
    ) -> bool {
        // "Search" on the keyboard: the list already follows every keystroke,
        // so it just puts the keyboard away (the text stays).
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
        // A single line: line breaks never enter the query.
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
pub(crate) fn on_backspace(
    field: Entity<SearchField>,
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
pub(crate) fn input_layer(field: Entity<SearchField>, cx: &App) -> impl IntoElement {
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
