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
}
