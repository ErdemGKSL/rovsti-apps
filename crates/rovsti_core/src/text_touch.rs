//! Touch rules of a text field, free of any UI toolkit: what a tap does, which
//! edit-menu items show, where the menu goes, the selection handles' shape and
//! the drag maths. Lengths are device-independent pixels (`f32`), indices are
//! UTF-8 byte offsets.

use std::ops::Range;

/// A finger-sized handle's drawn size, dp.
pub const HANDLE_SIZE: f32 = 22.0;
/// The handle's touch target, dp.
pub const HANDLE_HIT: f32 = 44.0;
/// The insertion handle stays this long after a tap, ms.
pub const INSERTION_MS: u64 = 3000;
/// Edit-menu height, dp.
pub const MENU_H: f32 = 40.0;
/// Gap between the field and the menu, dp.
pub const MENU_GAP: f32 = 8.0;
/// Horizontal padding of one menu item, dp.
pub const MENU_ITEM_PAD: f32 = 14.0;
/// Extra room at the capsule's rounded ends, dp.
pub const MENU_END_PAD: f32 = 4.0;
/// The menu keeps this far from the window's side edges, dp.
pub const MENU_EDGE: f32 = 8.0;
/// A tap this close to the caret counts as on it, dp.
pub const CARET_HIT: f32 = 14.0;
/// Dragging within this distance of a content edge scrolls the text, dp.
pub const EDGE_ZONE: f32 = 28.0;
/// The fastest auto-scroll, dp per tick.
pub const EDGE_MAX: f32 = 10.0;
/// Taps this soon after a drag or long-press ended are the lift's echo, ms.
pub const ECHO_MS: u64 = 350;

/// What a tap on a text field does.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TapAction {
    /// Focus (if needed) and put the caret at the tapped index.
    PlaceCaret,
    /// Open the edit menu, or close it when it is open.
    ToggleMenu,
    /// Select the word under the tap.
    SelectWord,
}

/// Decides a tap. `on_target` is true when the tap landed on the caret or
/// inside a non-empty selection.
pub fn resolve_tap(click_count: usize, focused: bool, on_target: bool) -> TapAction {
    if click_count >= 2 {
        TapAction::SelectWord
    } else if focused && on_target {
        TapAction::ToggleMenu
    } else {
        TapAction::PlaceCaret
    }
}

/// Whether a tap at `tap_x` is on a caret drawn at `caret_x`.
pub fn hits_caret(tap_x: f32, caret_x: f32) -> bool {
    (tap_x - caret_x).abs() <= CARET_HIT
}

/// Whether `byte` lies in the non-empty selection (edges included).
pub fn in_selection(byte: usize, sel: &Range<usize>) -> bool {
    sel.start < sel.end && sel.start <= byte && byte <= sel.end
}

/// The x offset inside the unscrolled line of a tap at window x `tap_x`.
pub fn line_x(tap_x: f32, content_left: f32, scroll: f32) -> f32 {
    tap_x - content_left + scroll
}

/// The text byte under a tap: `closest` maps a line x to the nearest index in
/// the displayed string, which is `text` itself or, when `masked`, one bullet
/// per character. The result sits on a character boundary of `text`.
pub fn tap_byte(
    text: &str,
    masked: bool,
    tap_x: f32,
    content_left: f32,
    scroll: f32,
    closest: impl FnOnce(f32) -> usize,
) -> usize {
    let idx = closest(line_x(tap_x, content_left, scroll));
    if masked {
        crate::text_view::unmasked_index(text, idx)
    } else {
        let mut i = idx.min(text.len());
        while !text.is_char_boundary(i) {
            i -= 1;
        }
        i
    }
}

/// An edit-menu item.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MenuItem {
    Cut,
    Copy,
    Paste,
    SelectAll,
}

impl MenuItem {
    pub fn label(self) -> &'static str {
        match self {
            Self::Cut => "Cut",
            Self::Copy => "Copy",
            Self::Paste => "Paste",
            Self::SelectAll => "Select all",
        }
    }
}

/// What decides the menu's items.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct MenuState {
    pub has_selection: bool,
    /// A passphrase: nothing may leave it.
    pub secret: bool,
    pub clipboard_empty: bool,
    pub text_empty: bool,
    /// The whole text is already selected.
    pub all_selected: bool,
}

/// The items to show, in order. Cut and Copy need a selection and a
/// non-secret field; Paste needs something on the clipboard; Select all needs
/// text that is not all selected already. May be empty.
pub fn menu_items(s: MenuState) -> Vec<MenuItem> {
    let mut items = Vec::with_capacity(4);
    if s.has_selection && !s.secret {
        items.push(MenuItem::Cut);
        items.push(MenuItem::Copy);
    }
    if !s.clipboard_empty {
        items.push(MenuItem::Paste);
    }
    if !s.text_empty && !s.all_selected {
        items.push(MenuItem::SelectAll);
    }
    items
}

/// The capsule's width for items whose labels measure `label_widths`.
pub fn menu_width(label_widths: &[f32]) -> f32 {
    label_widths
        .iter()
        .map(|w| w + 2.0 * MENU_ITEM_PAD)
        .sum::<f32>()
        + 2.0 * MENU_END_PAD
}

/// Window position (left, top) of the menu: centred on `anchor_x`, kept
/// `MENU_EDGE` inside the window's sides, `MENU_GAP` above the field, or below
/// it when there is no room above `safe_top`.
pub fn place_menu(
    anchor_x: f32,
    field_top: f32,
    field_bottom: f32,
    menu_w: f32,
    window_w: f32,
    safe_top: f32,
) -> (f32, f32) {
    let max_left = (window_w - MENU_EDGE - menu_w).max(MENU_EDGE);
    let left = (anchor_x - menu_w / 2.0).clamp(MENU_EDGE, max_left);
    let above = field_top - MENU_GAP - MENU_H;
    let top = if above >= safe_top {
        above
    } else {
        field_bottom + MENU_GAP
    };
    (left, top)
}

/// Narrows the menu's `left` so the capsule stays over its field: at least
/// `max(field_left, MENU_EDGE)` and at most `min(field_right, window_w -
/// MENU_EDGE)` on the right. Left as is when the field is narrower than the
/// menu.
pub fn keep_over_field(
    left: f32,
    menu_w: f32,
    field_left: f32,
    field_right: f32,
    window_w: f32,
) -> f32 {
    let lo = field_left.max(MENU_EDGE);
    let hi = field_right.min(window_w - MENU_EDGE);
    if hi - lo < menu_w {
        return left;
    }
    left.clamp(lo, hi - menu_w)
}

/// Horizontal scroll per tick while a finger at `x` drags near the content
/// edges `left`/`right`: negative to the left, positive to the right, zero
/// away from the edges. Grows to `max` at the edge and beyond.
pub fn edge_scroll(x: f32, left: f32, right: f32, zone: f32, max: f32) -> f32 {
    let zone = zone.min((right - left) / 2.0).max(1.0);
    if x < left + zone {
        -max * ((left + zone - x) / zone).min(1.0)
    } else if x > right - zone {
        max * ((x - (right - zone)) / zone).min(1.0)
    } else {
        0.0
    }
}

/// The selection while a long-press drag extends from `word`: pointing inside
/// the word keeps it, past an end grows from the other end. Returns
/// `(anchor, head)`.
pub fn drag_selection(word: &Range<usize>, pointer: usize) -> (usize, usize) {
    if pointer >= word.end {
        (word.start, pointer)
    } else if pointer <= word.start {
        (word.end, pointer)
    } else {
        (word.start, word.end)
    }
}

/// Whether a handle at `x` is inside the content box (it hides when scrolled out).
pub fn handle_visible(x: f32, left: f32, right: f32) -> bool {
    x >= left - 1.0 && x <= right + 1.0
}

/// Outline of a teardrop handle: the tip first, then the circle of `radius`
/// whose centre is `radius * sqrt(2)` from the tip towards `(dx, dy)` (the
/// direction from the tip to the centre). A closed polygon of `steps + 2` points.
pub fn teardrop(tip: (f32, f32), dir: (f32, f32), radius: f32, steps: usize) -> Vec<(f32, f32)> {
    let len = dir.0.hypot(dir.1).max(f32::EPSILON);
    let (ux, uy) = (dir.0 / len, dir.1 / len);
    let reach = radius * std::f32::consts::SQRT_2;
    let (cx, cy) = (tip.0 + ux * reach, tip.1 + uy * reach);
    // Angle of the tip as seen from the centre; the circle runs the long way
    // round from 45 degrees past it to 45 degrees before it.
    let tip_angle = (-uy).atan2(-ux);
    let quarter = std::f32::consts::FRAC_PI_4;
    let sweep = 3.0 * std::f32::consts::FRAC_PI_2;
    let mut pts = Vec::with_capacity(steps + 2);
    pts.push(tip);
    for k in 0..=steps {
        let a = tip_angle + quarter + sweep * k as f32 / steps.max(1) as f32;
        pts.push((cx + radius * a.cos(), cy + radius * a.sin()));
    }
    pts
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state(sel: bool, secret: bool, clip_empty: bool, text_empty: bool, all: bool) -> MenuState {
        MenuState {
            has_selection: sel,
            secret,
            clipboard_empty: clip_empty,
            text_empty,
            all_selected: all,
        }
    }

    #[test]
    fn menu_with_a_selection_has_everything() {
        use MenuItem::*;
        assert_eq!(
            menu_items(state(true, false, false, false, false)),
            [Cut, Copy, Paste, SelectAll]
        );
    }

    #[test]
    fn menu_without_selection_hides_cut_and_copy() {
        use MenuItem::*;
        assert_eq!(
            menu_items(state(false, false, false, false, false)),
            [Paste, SelectAll]
        );
    }

    #[test]
    fn menu_in_a_secret_field_never_cuts_or_copies() {
        use MenuItem::*;
        assert_eq!(
            menu_items(state(true, true, false, false, false)),
            [Paste, SelectAll]
        );
    }

    #[test]
    fn menu_hides_paste_for_an_empty_clipboard() {
        use MenuItem::*;
        assert_eq!(
            menu_items(state(true, false, true, false, false)),
            [Cut, Copy, SelectAll]
        );
    }

    #[test]
    fn menu_of_an_empty_field_is_paste_only() {
        assert_eq!(
            menu_items(state(false, false, false, true, false)),
            [MenuItem::Paste]
        );
        assert!(menu_items(state(false, false, true, true, false)).is_empty());
    }

    #[test]
    fn menu_hides_select_all_when_everything_is_selected() {
        use MenuItem::*;
        assert_eq!(
            menu_items(state(true, false, false, false, true)),
            [Cut, Copy, Paste]
        );
    }

    #[test]
    fn labels() {
        assert_eq!(MenuItem::SelectAll.label(), "Select all");
        assert_eq!(MenuItem::Cut.label(), "Cut");
    }

    #[test]
    fn taps_resolve() {
        use TapAction::*;
        assert_eq!(resolve_tap(1, false, false), PlaceCaret);
        // The unfocused first tap never toggles the menu, even on a stale caret.
        assert_eq!(resolve_tap(1, false, true), PlaceCaret);
        assert_eq!(resolve_tap(1, true, false), PlaceCaret);
        assert_eq!(resolve_tap(1, true, true), ToggleMenu);
        assert_eq!(resolve_tap(2, false, false), SelectWord);
        assert_eq!(resolve_tap(2, true, true), SelectWord);
        assert_eq!(resolve_tap(3, true, false), SelectWord);
    }

    #[test]
    fn caret_and_selection_hits() {
        assert!(hits_caret(100.0, 108.0));
        assert!(hits_caret(100.0, 86.0));
        assert!(!hits_caret(100.0, 120.0));
        assert!(in_selection(3, &(2..5)));
        assert!(in_selection(5, &(2..5)));
        assert!(!in_selection(6, &(2..5)));
        assert!(!in_selection(2, &(2..2)));
    }

    /// A monospace stand-in for the shaped line: 10 dp per byte.
    fn closest(x: f32) -> usize {
        (x / 10.0).round().max(0.0) as usize
    }

    #[test]
    fn tap_index_follows_scroll_and_origin() {
        // Content starts at x=16; a tap at x=46 is 30 dp in: index 3.
        assert_eq!(tap_byte("abcdefgh", false, 46.0, 16.0, 0.0, closest), 3);
        // Scrolled by 40 dp the same tap hits index 7.
        assert_eq!(tap_byte("abcdefgh", false, 46.0, 16.0, 40.0, closest), 7);
        // Left of the content clamps to the start, far right to the end.
        assert_eq!(tap_byte("abc", false, 0.0, 16.0, 0.0, closest), 0);
        assert_eq!(tap_byte("abc", false, 900.0, 16.0, 0.0, closest), 3);
    }

    #[test]
    fn tap_index_snaps_to_a_character_boundary() {
        // "ş" is two bytes: index 1 is inside it and snaps down.
        assert_eq!(tap_byte("şa", false, 26.0, 16.0, 0.0, closest), 0);
        assert_eq!(tap_byte("şa", false, 36.0, 16.0, 0.0, closest), 2);
    }

    #[test]
    fn tap_index_in_a_masked_field_counts_characters() {
        // Three bullets of three bytes each; "şİa" has bytes 2, 2, 1.
        let text = "şİa";
        assert_eq!(tap_byte(text, true, 16.0 + 3.0, 16.0, 0.0, |_| 3), 2);
        assert_eq!(tap_byte(text, true, 0.0, 16.0, 0.0, |_| 6), 4);
        assert_eq!(tap_byte(text, true, 0.0, 16.0, 0.0, |_| 99), text.len());
    }

    #[test]
    fn word_selection_by_double_tap() {
        use crate::text_edit::EditBuffer;
        let b = EditBuffer::with_text("merhaba dünya ŞİŞLİ", false);
        assert_eq!(b.word_at(2), 0..7);
        assert_eq!(b.word_at(9), 8..14);
        // A tap right after a word still picks it.
        assert_eq!(b.word_at(7), 0..7);
        // Turkish capitals.
        let t = b.text();
        let w = b.word_at(t.len() - 1);
        assert_eq!(&t[w], "ŞİŞLİ");
        // Empty field: nothing to select.
        assert_eq!(EditBuffer::new(false).word_at(0), 0..0);
    }

    #[test]
    fn menu_placement_stays_on_screen() {
        // Room above: 8 dp over the field, centred.
        let (l, t) = place_menu(200.0, 300.0, 344.0, 180.0, 412.0, 40.0);
        assert_eq!((l, t), (110.0, 252.0));
        // Near the left edge: clamped.
        assert_eq!(place_menu(10.0, 300.0, 344.0, 180.0, 412.0, 40.0).0, 8.0);
        // Near the right edge: clamped.
        assert_eq!(place_menu(410.0, 300.0, 344.0, 180.0, 412.0, 40.0).0, 224.0);
        // No room above: flips below.
        let (_, t) = place_menu(200.0, 60.0, 104.0, 180.0, 412.0, 40.0);
        assert_eq!(t, 112.0);
        // Wider than the window: pinned to the left edge.
        assert_eq!(place_menu(200.0, 300.0, 344.0, 500.0, 412.0, 40.0).0, 8.0);
    }

    #[test]
    fn menu_stays_over_its_field() {
        // Field 16..396 in a 412 window: pushed in from the 8 dp edge.
        assert_eq!(keep_over_field(8.0, 120.0, 16.0, 396.0, 412.0), 16.0);
        assert_eq!(keep_over_field(300.0, 120.0, 16.0, 396.0, 412.0), 276.0);
        assert_eq!(keep_over_field(150.0, 120.0, 16.0, 396.0, 412.0), 150.0);
        // A field hugging the window edge still respects the 8 dp inset.
        assert_eq!(keep_over_field(8.0, 120.0, 0.0, 412.0, 412.0), 8.0);
        assert_eq!(keep_over_field(290.0, 120.0, 0.0, 412.0, 412.0), 284.0);
        // Narrower than the menu: unchanged.
        assert_eq!(keep_over_field(8.0, 180.0, 40.0, 160.0, 412.0), 8.0);
    }

    #[test]
    fn menu_width_sums_items() {
        assert_eq!(menu_width(&[30.0, 40.0]), 30.0 + 40.0 + 56.0 + 8.0);
        assert_eq!(menu_width(&[]), 8.0);
    }

    #[test]
    fn edge_scroll_only_near_edges() {
        assert_eq!(edge_scroll(200.0, 16.0, 396.0, 28.0, 10.0), 0.0);
        assert!(edge_scroll(20.0, 16.0, 396.0, 28.0, 10.0) < 0.0);
        assert!(edge_scroll(390.0, 16.0, 396.0, 28.0, 10.0) > 0.0);
        assert_eq!(edge_scroll(-500.0, 16.0, 396.0, 28.0, 10.0), -10.0);
        assert_eq!(edge_scroll(900.0, 16.0, 396.0, 28.0, 10.0), 10.0);
        // Faster closer to the edge.
        assert!(
            edge_scroll(18.0, 16.0, 396.0, 28.0, 10.0) < edge_scroll(30.0, 16.0, 396.0, 28.0, 10.0)
        );
    }

    #[test]
    fn long_press_drag_grows_from_the_word() {
        let word = 8..14;
        assert_eq!(drag_selection(&word, 10), (8, 14));
        assert_eq!(drag_selection(&word, 20), (8, 20));
        assert_eq!(drag_selection(&word, 3), (14, 3));
        assert_eq!(drag_selection(&word, 14), (8, 14));
        assert_eq!(drag_selection(&word, 8), (14, 8));
    }

    #[test]
    fn handles_hide_outside_the_content() {
        assert!(handle_visible(100.0, 16.0, 396.0));
        assert!(handle_visible(16.0, 16.0, 396.0));
        assert!(!handle_visible(5.0, 16.0, 396.0));
        assert!(!handle_visible(400.0, 16.0, 396.0));
    }

    #[test]
    fn teardrop_is_a_tip_over_a_circle() {
        // Tip at the top-right corner of a 22 dp square; centre down-left.
        let pts = teardrop((22.0, 0.0), (-1.0, 1.0), 11.0, 24);
        assert_eq!(pts.len(), 26);
        assert_eq!(pts[0], (22.0, 0.0));
        let (min_x, max_x) = pts.iter().fold((f32::MAX, f32::MIN), |(lo, hi), p| {
            (lo.min(p.0), hi.max(p.0))
        });
        let (min_y, max_y) = pts.iter().fold((f32::MAX, f32::MIN), |(lo, hi), p| {
            (lo.min(p.1), hi.max(p.1))
        });
        assert!((max_x - min_x - 22.0).abs() < 0.1, "{min_x} {max_x}");
        assert!((max_y - min_y - 22.0).abs() < 0.1, "{min_y} {max_y}");
        // The circle's ends meet the tip's edges: every point is on the circle
        // (11 dp from the centre at (11, 11)) except the tip.
        for p in &pts[1..] {
            let d = (p.0 - 11.0).hypot(p.1 - 11.0);
            assert!((d - 11.0).abs() < 0.01, "{p:?}");
        }
    }

    #[test]
    fn insertion_teardrop_points_down_from_the_tip() {
        let pts = teardrop((50.0, 10.0), (0.0, 1.0), 11.0, 24);
        let lowest = pts.iter().map(|p| p.1).fold(f32::MIN, f32::max);
        assert!((lowest - (10.0 + 11.0 * 2f32.sqrt() + 11.0)).abs() < 0.01);
        assert!(pts.iter().all(|p| p.1 >= 10.0 - 0.01));
    }
}
