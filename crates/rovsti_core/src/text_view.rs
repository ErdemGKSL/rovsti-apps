//! View maths of a single-line text field: horizontal scroll that keeps the
//! caret in view, the caret blink phase, and the index mapping of a masked
//! (password) display string.
//!
//! Lengths are in device-independent pixels (`f32`), times in milliseconds.

/// How far the caret stays from the edges of the content box, dp.
pub const CARET_MARGIN: f32 = 12.0;
/// The caret stays solid this long after any edit or move, ms.
pub const SOLID_MS: u64 = 500;
/// The caret is on, then off, for this long each, ms.
pub const BLINK_MS: u64 = 530;
/// The bullet that masks a character.
pub const MASK: char = '\u{2022}';

/// The largest useful scroll offset: the text end plus the margin fits.
pub fn max_scroll(text_width: f32, view_width: f32, margin: f32) -> f32 {
    (text_width + margin - view_width).max(0.0)
}

/// Clamps a scroll offset into `0..=max_scroll`.
pub fn clamp_scroll(scroll: f32, text_width: f32, view_width: f32, margin: f32) -> f32 {
    scroll.clamp(0.0, max_scroll(text_width, view_width, margin))
}

/// The scroll offset after a caret move: unchanged while the caret is at least
/// `margin` inside the view, else the least change that restores it. The
/// margin shrinks in a view narrower than twice the margin.
pub fn scroll_for_caret(
    scroll: f32,
    caret_x: f32,
    text_width: f32,
    view_width: f32,
    margin: f32,
) -> f32 {
    let margin = margin.min(view_width / 2.0).max(0.0);
    let mut s = scroll;
    if caret_x - s < margin {
        s = caret_x - margin;
    } else if caret_x - s > view_width - margin {
        s = caret_x - (view_width - margin);
    }
    clamp_scroll(s, text_width, view_width, margin)
}

/// Whether the caret is drawn `elapsed_ms` after the last edit or move.
/// Solid for [`SOLID_MS`], then [`BLINK_MS`] on, [`BLINK_MS`] off, repeating.
/// Always on when `blink` is false.
pub fn caret_visible(elapsed_ms: u64, blink: bool) -> bool {
    if !blink || elapsed_ms < SOLID_MS {
        return true;
    }
    ((elapsed_ms - SOLID_MS) / BLINK_MS).is_multiple_of(2)
}

/// Milliseconds until [`caret_visible`] next changes.
pub fn next_toggle_ms(elapsed_ms: u64) -> u64 {
    if elapsed_ms < SOLID_MS {
        SOLID_MS - elapsed_ms
    } else {
        BLINK_MS - (elapsed_ms - SOLID_MS) % BLINK_MS
    }
}

/// The byte offset in the masked string of the original `byte` offset.
pub fn masked_index(text: &str, byte: usize) -> usize {
    let byte = byte.min(text.len());
    let end = (0..=byte)
        .rev()
        .find(|i| text.is_char_boundary(*i))
        .unwrap_or(0);
    text[..end].chars().count() * MASK.len_utf8()
}

/// The original byte offset of a byte offset in the masked string.
pub fn unmasked_index(text: &str, masked_byte: usize) -> usize {
    let n = masked_byte / MASK.len_utf8();
    text.char_indices().nth(n).map_or(text.len(), |(i, _)| i)
}

// ---- multiline ---------------------------------------------------------

/// Line height of a multiline field, dp.
pub const ML_LINE: f32 = 22.0;
/// Space above and below the text of a multiline field, dp.
pub const ML_PAD_V: f32 = 11.0;
/// A multiline capsule with more than one line has this corner radius, dp.
pub const ML_RADIUS: f32 = 22.0;

/// Lines a multiline field shows for `rows` wrapped rows: grows from `min`
/// to `max`, then scrolls.
pub fn visible_lines(rows: usize, min: usize, max: usize) -> usize {
    let min = min.max(1);
    rows.clamp(min, max.max(min))
}

/// The field's height for `lines` visible lines.
pub fn box_height(lines: usize) -> f32 {
    lines as f32 * ML_LINE + 2.0 * ML_PAD_V
}

/// The capsule's corner radius: half the height of a one-line field, then
/// [`ML_RADIUS`].
pub fn capsule_radius(lines: usize) -> f32 {
    if lines <= 1 {
        box_height(1) / 2.0
    } else {
        ML_RADIUS
    }
}

/// Byte offset where each `\n`-separated paragraph starts (one entry for an
/// empty text, one more after a trailing newline).
pub fn paragraph_starts(text: &str) -> Vec<usize> {
    let mut starts = vec![0];
    starts.extend(text.match_indices('\n').map(|(i, _)| i + 1));
    starts
}

/// The paragraph holding `byte` and the offset inside it.
pub fn locate_paragraph(starts: &[usize], byte: usize) -> (usize, usize) {
    let p = starts.partition_point(|s| *s <= byte).saturating_sub(1);
    (p, byte.saturating_sub(starts.get(p).copied().unwrap_or(0)))
}

/// The paragraph holding visual `row`, given the first row of each paragraph.
pub fn paragraph_for_row(rows_before: &[usize], row: usize) -> usize {
    rows_before.partition_point(|r| *r <= row).saturating_sub(1)
}

/// Clamps a vertical scroll offset into `0..=content - view`.
pub fn clamp_scroll_y(scroll: f32, content_h: f32, view_h: f32) -> f32 {
    scroll.clamp(0.0, (content_h - view_h).max(0.0))
}

/// The vertical scroll after a caret move: the least change that shows the
/// caret row (`caret_top`, `line_h` tall) completely.
pub fn scroll_y_for_caret(
    scroll: f32,
    caret_top: f32,
    line_h: f32,
    content_h: f32,
    view_h: f32,
) -> f32 {
    let mut s = scroll;
    if caret_top < s {
        s = caret_top;
    } else if caret_top + line_h > s + view_h {
        s = caret_top + line_h - view_h;
    }
    clamp_scroll_y(s, content_h, view_h)
}

/// The rectangles of a selection from `start` to `end`, each point being
/// `(x, row)` in a view `width` wide: `(left, row, right)` per row.
pub fn selection_rects(
    start: (f32, usize),
    end: (f32, usize),
    width: f32,
) -> Vec<(f32, usize, f32)> {
    let ((x0, r0), (x1, r1)) = if (start.1, start.0) <= (end.1, end.0) {
        (start, end)
    } else {
        (end, start)
    };
    if r0 == r1 {
        return vec![(x0, r0, x1)];
    }
    let mut out = vec![(x0, r0, width)];
    out.extend((r0 + 1..r1).map(|r| (0.0, r, width)));
    out.push((0.0, r1, x1));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn short_text_never_scrolls() {
        assert_eq!(scroll_for_caret(0.0, 80.0, 80.0, 200.0, 12.0), 0.0);
        assert_eq!(max_scroll(100.0, 200.0, 12.0), 0.0);
    }

    #[test]
    fn caret_at_end_of_long_text_leaves_the_margin() {
        let s = scroll_for_caret(0.0, 600.0, 600.0, 200.0, 12.0);
        assert_eq!(s, 412.0);
        assert_eq!(600.0 - s, 188.0);
    }

    #[test]
    fn caret_moving_left_scrolls_back_minimally() {
        // Scrolled to the end (view 412..612); a caret inside it stays put.
        assert_eq!(scroll_for_caret(412.0, 500.0, 600.0, 200.0, 12.0), 412.0);
        // Caret at x = 410 would be off the left edge: back to 12 inside.
        assert_eq!(scroll_for_caret(412.0, 410.0, 600.0, 200.0, 12.0), 398.0);
        assert_eq!(scroll_for_caret(412.0, 300.0, 600.0, 200.0, 12.0), 288.0);
        // Home goes all the way to zero.
        assert_eq!(scroll_for_caret(412.0, 0.0, 600.0, 200.0, 12.0), 0.0);
    }

    #[test]
    fn tiny_view_shrinks_the_margin() {
        let s = scroll_for_caret(0.0, 50.0, 50.0, 10.0, 12.0);
        assert!(s.is_finite() && s >= 0.0);
        assert_eq!(scroll_for_caret(0.0, 0.0, 0.0, 0.0, 12.0), 0.0);
    }

    #[test]
    fn clamp_after_the_text_shrinks() {
        assert_eq!(clamp_scroll(400.0, 150.0, 200.0, 12.0), 0.0);
        assert_eq!(clamp_scroll(-5.0, 600.0, 200.0, 12.0), 0.0);
    }

    #[test]
    fn blink_phases() {
        assert!(caret_visible(0, true));
        assert!(caret_visible(499, true));
        assert!(caret_visible(500, true));
        assert!(caret_visible(1029, true));
        assert!(!caret_visible(1030, true));
        assert!(!caret_visible(1559, true));
        assert!(caret_visible(1560, true));
        assert!(caret_visible(99_999, false));
    }

    #[test]
    fn next_toggle_lands_on_a_change() {
        for t in [0u64, 100, 499, 500, 700, 1029, 1030, 1500, 4321] {
            let next = t + next_toggle_ms(t);
            assert!(next > t);
            if t >= SOLID_MS {
                assert_ne!(caret_visible(next, true), caret_visible(next - 1, true));
            }
        }
    }

    #[test]
    fn mask_indices_round_trip() {
        let t = "şİ😀a";
        for (i, _) in t.char_indices().chain([(t.len(), ' ')]) {
            assert_eq!(unmasked_index(t, masked_index(t, i)), i);
        }
        assert_eq!(masked_index(t, t.len()), 12);
        assert_eq!(masked_index(t, 1), 0); // inside "ş": snaps down
        assert_eq!(unmasked_index(t, 999), t.len());
    }

    #[test]
    fn composer_grows_one_to_six_lines_then_scrolls() {
        assert_eq!(visible_lines(0, 1, 6), 1);
        assert_eq!(visible_lines(1, 1, 6), 1);
        assert_eq!(visible_lines(4, 1, 6), 4);
        assert_eq!(visible_lines(9, 1, 6), 6);
        assert_eq!(visible_lines(2, 3, 6), 3);
        assert_eq!(visible_lines(9, 3, 2), 3);
        assert_eq!(box_height(1), 44.0);
        assert_eq!(box_height(6), 154.0);
    }

    #[test]
    fn capsule_radius_is_22_dp() {
        assert_eq!(capsule_radius(1), 22.0);
        assert_eq!(capsule_radius(2), 22.0);
        assert_eq!(capsule_radius(6), 22.0);
    }

    #[test]
    fn paragraphs_split_on_newlines() {
        assert_eq!(paragraph_starts(""), vec![0]);
        assert_eq!(paragraph_starts("ab"), vec![0]);
        assert_eq!(paragraph_starts("ab\ncdé\n"), vec![0, 3, 8]);
        let starts = paragraph_starts("ab\ncdé\n");
        assert_eq!(locate_paragraph(&starts, 0), (0, 0));
        assert_eq!(locate_paragraph(&starts, 2), (0, 2));
        assert_eq!(locate_paragraph(&starts, 3), (1, 0));
        assert_eq!(locate_paragraph(&starts, 8), (2, 0));
        assert_eq!(locate_paragraph(&starts, 99), (2, 91));
    }

    #[test]
    fn rows_map_to_paragraphs() {
        // Paragraph 0 wraps over 3 rows, paragraph 1 has 1, paragraph 2 has 2.
        let before = [0, 3, 4];
        assert_eq!(paragraph_for_row(&before, 0), 0);
        assert_eq!(paragraph_for_row(&before, 2), 0);
        assert_eq!(paragraph_for_row(&before, 3), 1);
        assert_eq!(paragraph_for_row(&before, 4), 2);
        assert_eq!(paragraph_for_row(&before, 50), 2);
    }

    #[test]
    fn vertical_scroll_follows_the_caret_row() {
        // 10 rows of 22 dp in a 132 dp (6 row) view.
        let (content, view) = (220.0, 132.0);
        assert_eq!(scroll_y_for_caret(0.0, 0.0, 22.0, content, view), 0.0);
        assert_eq!(scroll_y_for_caret(0.0, 110.0, 22.0, content, view), 0.0);
        // Caret on row 9 (top 198): the view ends at the caret row's bottom.
        assert_eq!(scroll_y_for_caret(0.0, 198.0, 22.0, content, view), 88.0);
        // Back up to row 2 (top 44): scrolls up just enough.
        assert_eq!(scroll_y_for_caret(88.0, 44.0, 22.0, content, view), 44.0);
        // Text shrank: clamps.
        assert_eq!(clamp_scroll_y(88.0, 100.0, 132.0), 0.0);
        assert_eq!(clamp_scroll_y(-3.0, 220.0, 132.0), 0.0);
    }

    #[test]
    fn selection_rects_cover_rows() {
        assert_eq!(
            selection_rects((10.0, 2), (40.0, 2), 200.0),
            vec![(10.0, 2, 40.0)]
        );
        assert_eq!(
            selection_rects((150.0, 1), (30.0, 3), 200.0),
            vec![(150.0, 1, 200.0), (0.0, 2, 200.0), (0.0, 3, 30.0)]
        );
        // Reversed endpoints give the same rectangles.
        assert_eq!(
            selection_rects((30.0, 3), (150.0, 1), 200.0),
            selection_rects((150.0, 1), (30.0, 3), 200.0)
        );
    }
}
