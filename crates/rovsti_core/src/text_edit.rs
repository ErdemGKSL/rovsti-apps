//! Pure edit model behind every text field: text, selection and IME marked range.
//!
//! All offsets are UTF-8 byte offsets that sit on grapheme boundaries. Android
//! input methods speak UTF-16, so the `*_utf16` functions convert at the edge and
//! clamp whatever the keyboard sends.

use std::borrow::Cow;
use std::ops::Range;
use unicode_segmentation::UnicodeSegmentation;

/// UTF-16 offset of the UTF-8 byte offset `byte` in `text` (clamped to the text).
pub fn utf8_to_utf16(text: &str, byte: usize) -> usize {
    let mut byte = byte.min(text.len());
    while !text.is_char_boundary(byte) {
        byte -= 1;
    }
    text[..byte].encode_utf16().count()
}

/// UTF-8 byte offset of the UTF-16 offset `units`. Clamped to the text; an offset
/// inside a surrogate pair rounds down to the start of that character.
pub fn utf16_to_utf8(text: &str, units: usize) -> usize {
    let mut seen = 0;
    for (i, c) in text.char_indices() {
        let next = seen + c.len_utf16();
        if next > units {
            return i;
        }
        seen = next;
    }
    text.len()
}

/// Like [`utf16_to_utf8`] but an offset inside a surrogate pair rounds up.
fn utf16_to_utf8_up(text: &str, units: usize) -> usize {
    let b = utf16_to_utf8(text, units);
    if utf8_to_utf16(text, b) < units {
        text[b..].chars().next().map_or(b, |c| b + c.len_utf8())
    } else {
        b
    }
}

/// Editable text with a selection (`anchor` to `head`) and an optional IME marked range.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct EditBuffer {
    text: String,
    anchor: usize,
    head: usize,
    marked: Option<Range<usize>>,
    multiline: bool,
}

impl EditBuffer {
    pub fn new(multiline: bool) -> Self {
        Self {
            multiline,
            ..Self::default()
        }
    }

    /// Buffer holding `text` with the caret at the end.
    pub fn with_text(text: &str, multiline: bool) -> Self {
        let mut b = Self::new(multiline);
        b.set_text(text);
        b
    }

    pub fn text(&self) -> &str {
        &self.text
    }
    pub fn is_empty(&self) -> bool {
        self.text.is_empty()
    }
    pub fn multiline(&self) -> bool {
        self.multiline
    }
    pub fn anchor(&self) -> usize {
        self.anchor
    }
    /// The moving end of the selection, where the caret is drawn.
    pub fn head(&self) -> usize {
        self.head
    }
    pub fn marked(&self) -> Option<Range<usize>> {
        self.marked.clone()
    }
    /// Selection as an ordered byte range (empty when it is just a caret).
    pub fn selection(&self) -> Range<usize> {
        self.anchor.min(self.head)..self.anchor.max(self.head)
    }
    pub fn has_selection(&self) -> bool {
        self.anchor != self.head
    }
    pub fn reversed(&self) -> bool {
        self.head < self.anchor
    }
    pub fn selected_text(&self) -> &str {
        &self.text[self.selection()]
    }

    /// Replace everything, caret at the end, no marked range.
    pub fn set_text(&mut self, text: &str) {
        self.text = self.sanitize(text).into_owned();
        self.anchor = self.text.len();
        self.head = self.anchor;
        self.marked = None;
    }

    /// Newline policy: single line turns `\r\n`, `\n` and `\r` into one space each
    /// (a `\r\n` pair counts once); multiline keeps `\n` and turns `\r\n` and a
    /// lone `\r` into `\n`.
    pub fn sanitize<'a>(&self, text: &'a str) -> Cow<'a, str> {
        if !text.contains(['\n', '\r']) {
            return Cow::Borrowed(text);
        }
        let unified = text.replace("\r\n", "\r");
        if self.multiline {
            Cow::Owned(unified.replace('\r', "\n"))
        } else {
            Cow::Owned(unified.replace(['\r', '\n'], " "))
        }
    }

    // ---- boundaries ------------------------------------------------------

    /// Nearest grapheme boundary at or before `byte`.
    fn floor(&self, byte: usize) -> usize {
        let byte = byte.min(self.text.len());
        if byte == self.text.len() {
            return byte;
        }
        self.text
            .grapheme_indices(true)
            .map(|(i, _)| i)
            .take_while(|&i| i <= byte)
            .last()
            .unwrap_or(0)
    }

    /// Nearest grapheme boundary at or after `byte`.
    fn ceil(&self, byte: usize) -> usize {
        let byte = byte.min(self.text.len());
        let f = self.floor(byte);
        if f == byte {
            byte
        } else {
            self.next_boundary(f)
        }
    }

    /// Clamp `byte` onto a grapheme boundary (down).
    pub fn snap(&self, byte: usize) -> usize {
        self.floor(byte)
    }

    fn prev_boundary(&self, at: usize) -> usize {
        self.text[..at]
            .grapheme_indices(true)
            .next_back()
            .map_or(0, |(i, _)| i)
    }

    fn next_boundary(&self, at: usize) -> usize {
        self.text[at..]
            .graphemes(true)
            .next()
            .map_or(at, |g| at + g.len())
    }

    fn line_start(&self, at: usize) -> usize {
        if self.multiline {
            self.text[..at].rfind('\n').map_or(0, |i| i + 1)
        } else {
            0
        }
    }

    fn line_end(&self, at: usize) -> usize {
        if self.multiline {
            self.text[at..]
                .find('\n')
                .map_or(self.text.len(), |i| at + i)
        } else {
            self.text.len()
        }
    }

    // ---- selection -------------------------------------------------------

    /// Select from `anchor` to `head` (bytes, clamped, widened to whole graphemes).
    pub fn set_selection(&mut self, anchor: usize, head: usize) {
        let (lo, hi) = (anchor.min(head), anchor.max(head));
        let (lo, hi) = (self.floor(lo), self.ceil(hi));
        if anchor <= head {
            (self.anchor, self.head) = (lo, hi);
        } else {
            (self.anchor, self.head) = (hi, lo);
        }
    }

    pub fn set_caret(&mut self, byte: usize) {
        let b = self.floor(byte);
        self.anchor = b;
        self.head = b;
    }

    /// Selection in UTF-16 units, as the keyboard wants it, plus the reversed flag.
    pub fn selection_utf16(&self) -> (Range<usize>, bool) {
        (self.utf16_of(self.selection()), self.reversed())
    }

    /// Apply a keyboard selection; out-of-range values clamp to the text.
    pub fn set_selection_utf16(&mut self, range: Range<usize>, reversed: bool) {
        let a = utf16_to_utf8(&self.text, range.start);
        let b = utf16_to_utf8_up(&self.text, range.end.max(range.start));
        if reversed {
            self.set_selection(b, a);
        } else {
            self.set_selection(a, b);
        }
    }

    pub fn select_all(&mut self) {
        self.anchor = 0;
        self.head = self.text.len();
    }

    fn move_head(&mut self, to: usize, extend: bool) {
        self.head = to;
        if !extend {
            self.anchor = to;
        }
    }

    pub fn move_left(&mut self, extend: bool) {
        if !extend && self.has_selection() {
            self.move_head(self.selection().start, false);
        } else {
            self.move_head(self.prev_boundary(self.head), extend);
        }
    }

    pub fn move_right(&mut self, extend: bool) {
        if !extend && self.has_selection() {
            self.move_head(self.selection().end, false);
        } else {
            self.move_head(self.next_boundary(self.head), extend);
        }
    }

    /// Start of the previous word (or the text start).
    pub fn word_left(&mut self, extend: bool) {
        let to = self
            .text
            .unicode_word_indices()
            .map(|(i, _)| i)
            .rfind(|&i| i < self.head)
            .unwrap_or(0);
        self.move_head(to, extend);
    }

    /// End of the next word (or the text end).
    pub fn word_right(&mut self, extend: bool) {
        let to = self
            .text
            .unicode_word_indices()
            .map(|(i, w)| i + w.len())
            .find(|&e| e > self.head)
            .unwrap_or(self.text.len());
        self.move_head(to, extend);
    }

    /// Start of the line (the text start on a single line).
    pub fn home(&mut self, extend: bool) {
        self.move_head(self.line_start(self.head), extend);
    }

    /// End of the line (the text end on a single line).
    pub fn end(&mut self, extend: bool) {
        self.move_head(self.line_end(self.head), extend);
    }

    /// Word under `byte` for double-tap; a caret right after a word counts as in it.
    /// Falls back to the grapheme at `byte`, or an empty range at the end.
    pub fn word_at(&self, byte: usize) -> Range<usize> {
        let byte = self.floor(byte);
        for (i, w) in self.text.unicode_word_indices() {
            if i <= byte && byte <= i + w.len() {
                return i..i + w.len();
            }
        }
        if byte >= self.text.len() {
            return self.text.len()..self.text.len();
        }
        byte..self.next_boundary(byte)
    }

    // ---- editing ---------------------------------------------------------

    fn clamp_range(&self, r: Range<usize>) -> Range<usize> {
        let (a, b) = (r.start.min(r.end), r.start.max(r.end));
        if a == b {
            let f = self.floor(a);
            return f..f;
        }
        self.floor(a)..self.ceil(b)
    }

    /// Splice `text` in and keep the marked range consistent. Selection is the caller's job.
    fn edit(&mut self, r: Range<usize>, s: &str) {
        let (a, b) = (r.start, r.end);
        self.text.replace_range(a..b, s);
        let delta = s.len() as isize - (b - a) as isize;
        let shift = |x: usize| (x as isize + delta) as usize;
        self.marked = self.marked.take().and_then(|m| {
            let whole = a == m.start && b == m.end;
            let inside =
                a >= m.start && b <= m.end && !whole && (a < b || (a > m.start && a < m.end));
            let out = if whole {
                return None;
            } else if inside {
                m.start..shift(m.end)
            } else if b <= m.start {
                shift(m.start)..shift(m.end)
            } else if a >= m.end {
                m
            } else {
                return None;
            };
            (out.start < out.end).then_some(out)
        });
    }

    /// Replace `range` (clamped) with `text`; caret lands after it. An edit that
    /// replaces the whole marked range commits it; one inside it keeps it marked.
    pub fn replace(&mut self, range: Range<usize>, text: &str) {
        let r = self.clamp_range(range);
        let t = self.sanitize(text).into_owned();
        self.edit(r.clone(), &t);
        self.set_caret(r.start + t.len());
    }

    /// Replace the selection (the usual typing and paste path).
    pub fn replace_selection(&mut self, text: &str) {
        self.replace(self.selection(), text);
    }

    /// Replace `range` and mark the new text as composing. `sel_in_new` is the
    /// selection inside the new text in UTF-16 units (default: caret at its end).
    pub fn replace_and_mark(
        &mut self,
        range: Range<usize>,
        text: &str,
        sel_in_new: Option<Range<usize>>,
    ) {
        let r = self.clamp_range(range);
        let t = self.sanitize(text).into_owned();
        self.edit(r.clone(), &t);
        let end = r.start + t.len();
        self.marked = (!t.is_empty()).then_some(r.start..end);
        match sel_in_new {
            Some(s) => {
                let a = r.start + utf16_to_utf8(&t, s.start);
                let b = r.start + utf16_to_utf8(&t, s.end.max(s.start));
                self.set_selection(a, b);
            }
            None => self.set_caret(end),
        }
    }

    /// Same as [`replace`](Self::replace) with a UTF-16 range, as the keyboard sends it.
    pub fn replace_utf16(&mut self, range: Range<usize>, text: &str) {
        self.replace(self.bytes_of(range), text);
    }

    /// Same as [`replace_and_mark`](Self::replace_and_mark) with a UTF-16 range.
    pub fn replace_and_mark_utf16(
        &mut self,
        range: Range<usize>,
        text: &str,
        sel_in_new: Option<Range<usize>>,
    ) {
        self.replace_and_mark(self.bytes_of(range), text, sel_in_new);
    }

    /// Mark an existing range as composing (setComposingRegion), clamped.
    pub fn mark_utf16(&mut self, range: Range<usize>) {
        let r = self.clamp_range(self.bytes_of(range));
        self.marked = (r.start < r.end).then_some(r);
    }

    pub fn unmark(&mut self) {
        self.marked = None;
    }

    /// Byte range of a UTF-16 range, clamped.
    pub fn bytes_of(&self, range: Range<usize>) -> Range<usize> {
        let a = utf16_to_utf8(&self.text, range.start);
        let b = utf16_to_utf8_up(&self.text, range.end.max(range.start));
        a..b
    }

    /// UTF-16 range of a byte range.
    pub fn utf16_of(&self, range: Range<usize>) -> Range<usize> {
        utf8_to_utf16(&self.text, range.start)..utf8_to_utf16(&self.text, range.end)
    }

    /// Backspace: delete the selection, else the grapheme before the caret.
    pub fn delete_backward(&mut self) -> bool {
        let sel = self.selection();
        let r = if sel.is_empty() {
            self.prev_boundary(sel.start)..sel.start
        } else {
            sel
        };
        self.delete(r)
    }

    /// Delete key: delete the selection, else the grapheme after the caret.
    pub fn delete_forward(&mut self) -> bool {
        let sel = self.selection();
        let r = if sel.is_empty() {
            sel.start..self.next_boundary(sel.start)
        } else {
            sel
        };
        self.delete(r)
    }

    fn delete(&mut self, r: Range<usize>) -> bool {
        if r.is_empty() {
            return false;
        }
        self.edit(r.clone(), "");
        self.set_caret(r.start);
        true
    }

    /// Delete `before16`/`after16` UTF-16 units around the selection (whole graphemes
    /// only). The selection itself is kept. Returns whether anything was deleted.
    pub fn delete_surrounding(&mut self, before16: usize, after16: usize) -> bool {
        let sel = self.selection();
        let reversed = self.reversed();
        let s16 = utf8_to_utf16(&self.text, sel.start);
        let e16 = utf8_to_utf16(&self.text, sel.end);
        let new_start = self.floor(utf16_to_utf8(&self.text, s16.saturating_sub(before16)));
        let new_end = self.ceil(utf16_to_utf8_up(&self.text, e16.saturating_add(after16)));
        if new_start == sel.start && new_end == sel.end {
            return false;
        }
        self.edit(sel.end..new_end, "");
        self.edit(new_start..sel.start, "");
        let len = sel.len();
        if reversed {
            (self.anchor, self.head) = (new_start + len, new_start);
        } else {
            (self.anchor, self.head) = (new_start, new_start + len);
        }
        true
    }

    /// Text plus the selection in bytes, for the keyboard's surrounding-text query.
    pub fn surrounding(&self) -> (&str, Range<usize>) {
        (&self.text, self.selection())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn buf(text: &str) -> EditBuffer {
        EditBuffer::with_text(text, false)
    }

    #[test]
    fn utf16_helpers_handle_surrogate_pairs() {
        let t = "a😀b";
        assert_eq!(utf8_to_utf16(t, 0), 0);
        assert_eq!(utf8_to_utf16(t, 1), 1);
        assert_eq!(utf8_to_utf16(t, 5), 3);
        assert_eq!(utf8_to_utf16(t, 99), 4);
        assert_eq!(utf16_to_utf8(t, 1), 1);
        assert_eq!(utf16_to_utf8(t, 2), 1, "inside a pair rounds down");
        assert_eq!(utf16_to_utf8_up(t, 2), 5, "or up");
        assert_eq!(utf16_to_utf8(t, 3), 5);
        assert_eq!(utf16_to_utf8(t, 99), 6);
    }

    #[test]
    fn turkish_text_moves_and_deletes_by_character() {
        let mut b = buf("İğş");
        assert_eq!(b.head(), "İğş".len());
        b.move_left(false);
        assert_eq!(b.head(), "İğ".len());
        b.delete_backward();
        assert_eq!(b.text(), "İş");
        b.delete_forward();
        assert_eq!(b.text(), "İ");
        assert_eq!(b.selection_utf16().0, 1..1);
    }

    #[test]
    fn combining_dot_is_one_grapheme() {
        // "I" + U+0307 (combining dot above): decomposed Turkish İ.
        let mut b = buf("aI\u{0307}");
        b.delete_backward();
        assert_eq!(b.text(), "a");
        let mut b = buf("i\u{0307}x");
        b.set_caret(1);
        assert_eq!(b.head(), 0, "snaps off the middle of a grapheme");
    }

    #[test]
    fn zwj_family_and_flags_are_single_steps() {
        let family = "👨‍👩‍👧";
        let flag = "🇹🇷";
        let mut b = buf(&format!("x{family}{flag}"));
        b.move_left(false);
        assert_eq!(b.head(), 1 + family.len());
        b.move_left(false);
        assert_eq!(b.head(), 1);
        b.move_right(false);
        b.move_right(false);
        assert_eq!(b.head(), b.text().len());
        b.delete_backward();
        assert_eq!(b.text(), format!("x{family}"));
        b.delete_backward();
        assert_eq!(b.text(), "x");
    }

    #[test]
    fn selection_utf16_roundtrip_with_emoji() {
        let mut b = buf("a😀b");
        b.set_selection_utf16(1..3, false);
        assert_eq!(b.selected_text(), "😀");
        assert_eq!(b.selection_utf16(), (1..3, false));
        // Inside the pair: widens to the whole emoji.
        b.set_selection_utf16(2..3, false);
        assert_eq!(b.selected_text(), "😀");
    }

    #[test]
    fn reversed_selection_keeps_direction() {
        let mut b = buf("hello world");
        b.set_selection_utf16(2..5, true);
        assert_eq!((b.anchor(), b.head()), (5, 2));
        assert!(b.reversed());
        assert_eq!(b.selection_utf16(), (2..5, true));
        b.move_right(false);
        assert_eq!((b.anchor(), b.head()), (5, 5), "collapses to the end");
        b.set_selection_utf16(2..5, true);
        b.move_left(false);
        assert_eq!((b.anchor(), b.head()), (2, 2), "collapses to the start");
        b.set_selection_utf16(2..5, true);
        b.move_left(true);
        assert_eq!((b.anchor(), b.head()), (5, 1), "extends the moving end");
    }

    #[test]
    fn out_of_range_values_clamp() {
        let mut b = buf("abc");
        b.set_selection_utf16(10..20, false);
        assert_eq!(b.selection(), 3..3);
        #[allow(clippy::reversed_empty_ranges)]
        b.set_selection_utf16(2..1, false);
        assert_eq!(b.selection(), 2..2);
        b.replace(1..99, "Z");
        assert_eq!(b.text(), "aZ");
        b.replace(50..60, "!");
        assert_eq!(b.text(), "aZ!");
        b.mark_utf16(0..99);
        assert_eq!(b.marked(), Some(0..3));
        assert!(!b.delete_surrounding(0, 0));
        assert!(b.delete_surrounding(99, usize::MAX));
        assert_eq!(b.text(), "");
        assert_eq!(b.marked(), None);
    }

    #[test]
    fn replace_snaps_to_graphemes() {
        let mut b = buf("e\u{0301}x");
        b.replace(1..1, "Z");
        assert_eq!(b.text(), "Ze\u{0301}x");
        assert_eq!(b.head(), 1);
    }

    #[test]
    fn word_motion_and_word_at() {
        let mut b = buf("merhaba güzel dünya");
        b.word_left(false);
        assert_eq!(&b.text()[b.head()..], "dünya");
        b.word_left(false);
        assert_eq!(&b.text()[b.head()..], "güzel dünya");
        b.word_right(false);
        assert_eq!(&b.text()[..b.head()], "merhaba güzel");
        b.home(true);
        assert_eq!(b.selection(), 0..b.head().max(b.anchor()));
        assert_eq!(b.selected_text(), "merhaba güzel");
        b.end(false);
        assert_eq!(b.head(), b.text().len());
        let r = b.word_at(10);
        assert_eq!(&b.text()[r], "güzel");
        let r = b.word_at(7);
        assert_eq!(&b.text()[r], "merhaba", "caret right after a word");
        assert_eq!(b.word_at(999), 15..21, "end of text after a word");
        assert_eq!(buf("ab ").word_at(3), 3..3);
        let b = buf("a  -b");
        assert_eq!(b.word_at(2), 2..3);
    }

    #[test]
    fn newline_policy() {
        let mut s = EditBuffer::new(false);
        s.replace(0..0, "a\r\nb\nc\rd");
        assert_eq!(s.text(), "a b c d");
        let mut m = EditBuffer::new(true);
        m.replace(0..0, "a\r\nb\nc\rd");
        assert_eq!(m.text(), "a\nb\nc\nd");
        m.set_caret(3);
        m.home(false);
        assert_eq!(m.head(), 2);
        m.end(false);
        assert_eq!(m.head(), 3);
        m.select_all();
        assert_eq!(m.selection(), 0..m.text().len());
    }

    #[test]
    fn typing_replaces_selection_and_backspace_uses_caret() {
        let mut b = buf("kar");
        b.set_selection_utf16(2..2, false);
        b.replace_selection("ç");
        assert_eq!(b.text(), "kaçr");
        assert_eq!(b.head(), "kaç".len());
        b.delete_backward();
        assert_eq!(b.text(), "kar");
        b.select_all();
        b.replace_selection("x");
        assert_eq!(b.text(), "x");
        let mut b = buf("abc");
        b.select_all();
        b.delete_backward();
        assert_eq!(b.text(), "");
        assert!(!b.delete_backward());
        assert!(!b.delete_forward());
    }

    #[test]
    fn marked_range_lifecycle() {
        let mut b = buf("hi ");
        b.replace_and_mark(3..3, "kar", None);
        assert_eq!(b.marked(), Some(3..6));
        assert_eq!(b.head(), 6);
        // The keyboard extends the composition.
        b.replace_and_mark(3..6, "karpuz", Some(2..2));
        assert_eq!(b.marked(), Some(3..9));
        assert_eq!(b.head(), 5);
        // An edit inside the marked range keeps it marked and resizes it.
        b.replace(4..5, "XY");
        assert_eq!(b.text(), "hi kXYrpuz");
        assert_eq!(b.marked(), Some(3..10));
        b.delete_backward();
        assert_eq!(b.marked(), Some(3..9));
        // An edit before it shifts it.
        b.replace(0..0, ">>");
        assert_eq!(b.marked(), Some(5..11));
        // An edit across its edge drops it.
        b.replace(4..6, "");
        assert_eq!(b.marked(), None);
        // Replacing exactly the marked range commits it.
        b.replace_and_mark(0..0, "ab", None);
        b.replace(0..2, "done");
        assert_eq!(b.marked(), None);
        assert!(b.text().starts_with("done"));
    }

    #[test]
    fn mark_existing_region_keeps_selection() {
        let mut b = buf("merhaba dünya");
        b.set_caret(4);
        b.mark_utf16(0..7);
        assert_eq!(b.marked(), Some(0..7));
        assert_eq!(b.head(), 4);
        b.mark_utf16(5..5);
        assert_eq!(b.marked(), None);
    }

    #[test]
    fn delete_surrounding_both_sides() {
        let mut b = buf("abcdef");
        b.set_selection_utf16(2..4, false);
        assert!(b.delete_surrounding(1, 1));
        assert_eq!(b.text(), "acdf");
        assert_eq!(b.selected_text(), "cd");

        // Caret after "a😀b": one unit before removes "b", one unit after lands inside
        // the second emoji and removes all of it.
        let mut b = buf("a😀b😀c");
        b.set_caret("a😀b".len());
        assert!(b.delete_surrounding(1, 1));
        assert_eq!(b.text(), "a😀c");
        assert_eq!(b.head(), "a😀".len());
    }

    #[test]
    fn delete_surrounding_only_after_and_reversed() {
        let mut b = buf("abcdef");
        b.set_selection_utf16(1..3, true);
        assert!(b.delete_surrounding(0, 2));
        assert_eq!(b.text(), "abcf");
        assert!(b.reversed());
        assert_eq!(b.selected_text(), "bc");
        assert!(b.delete_surrounding(5, 0));
        assert_eq!(b.text(), "bcf");
        assert_eq!(b.selection(), 0..2);
    }

    #[test]
    fn surrounding_reports_selection() {
        let mut b = buf("İyi");
        b.set_selection_utf16(1..2, false);
        let (t, sel) = b.surrounding();
        assert_eq!(t, "İyi");
        assert_eq!(&t[sel], "y");
    }

    #[test]
    fn set_text_resets_state() {
        let mut b = EditBuffer::new(true);
        b.replace_and_mark(0..0, "abc", None);
        b.set_text("x\r\ny");
        assert_eq!(b.text(), "x\ny");
        assert_eq!(b.marked(), None);
        assert_eq!(b.selection(), 3..3);
    }
}
