//! Pure layout budget for the dial keypad screen.
//!
//! The view places one clipped box at each rect computed here, so the sections
//! are disjoint by construction and typing never moves the keys. Nothing here
//! allocates or touches GPUI; all sizes are logical pixels.

use crate::dial;

/// Largest key diameter (matches the in-call pad).
pub const KEY_MAX: f32 = 78.0;
/// Below this the layout switches to [`KeypadMode::Compact`].
pub const KEY_MIN: f32 = 56.0;
/// Smallest key the layout tries to keep by dropping optional rows.
pub const KEY_FLOOR: f32 = 36.0;
/// Letters under digits are hidden below this key size.
pub const LETTERS_MIN: f32 = 60.0;
/// Smallest number-field font.
pub const NUMBER_FONT_MIN: f32 = 22.0;
/// Average glyph advance as a fraction of the font size.
pub const CHAR_W: f32 = 0.6;
/// Height of one match row.
pub const MATCH_ROW_H: f32 = 48.0;
/// Gap between match rows (and before the "N more" line).
pub const MATCH_GAP: f32 = 6.0;
/// Height of the "N more" line.
pub const MORE_H: f32 = 36.0;

const SIDE_PAD: f32 = 16.0;
const BAR_GAP: f32 = 8.0;
const COLUMN_MAX_W: f32 = 420.0;
const LANDSCAPE_HEADER_MAX: f32 = 48.0;
/// Share of the free width the landscape key grid may take.
const LANDSCAPE_GRID_SHARE: f32 = 0.55;
const MATCHES_CAP: f32 = 3.0 * MATCH_ROW_H + 2.0 * MATCH_GAP + MATCH_GAP + MORE_H;
const COL_GAP_RATIO: f32 = 24.0 / 78.0;
const ROW_GAP_RATIO: f32 = 12.0 / 78.0;

#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Rect {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

impl Rect {
    pub fn right(&self) -> f32 {
        self.x + self.w
    }

    pub fn bottom(&self) -> f32 {
        self.y + self.h
    }

    fn is_empty(&self) -> bool {
        self.w <= 0.0 || self.h <= 0.0
    }

    /// Strict overlap: touching edges do not overlap, empty rects never do.
    pub fn overlaps(&self, other: &Rect) -> bool {
        if self.is_empty() || other.is_empty() {
            return false;
        }
        self.x < other.right()
            && other.x < self.right()
            && self.y < other.bottom()
            && other.y < self.bottom()
    }

    /// True when `self` lies within `outer`, with a 0.5 px epsilon.
    pub fn inside(&self, outer: &Rect) -> bool {
        const EPS: f32 = 0.5;
        self.x >= outer.x - EPS
            && self.y >= outer.y - EPS
            && self.right() <= outer.right() + EPS
            && self.bottom() <= outer.bottom() + EPS
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Insets {
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
    pub left: f32,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct KeypadInput {
    pub viewport_w: f32,
    pub viewport_h: f32,
    pub insets: Insets,
    /// Characters in the dialed number.
    pub text_len: usize,
    /// Height of the title block (large title and buttons).
    pub header_h: f32,
    /// Height reserved for the tab bar slot.
    pub tab_bar_h: f32,
    /// Gap between the tab bar and the bottom inset.
    pub tab_bar_margin: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeypadMode {
    Portrait,
    Compact,
    Landscape,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct KeyStyle {
    pub glyph: f32,
    pub caption_h: f32,
    pub letters: bool,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NumberFit {
    pub font: f32,
    /// Trailing characters to show after a leading ellipsis; `None` means
    /// everything fits.
    pub keep: Option<usize>,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct KeypadLayout {
    pub mode: KeypadMode,
    pub header: Rect,
    pub number: Rect,
    pub matches: Rect,
    pub actions: Rect,
    pub grid: Rect,
    pub call_row: Rect,
    pub tab_bar: Rect,
    pub key: f32,
    pub col_gap: f32,
    pub row_gap: f32,
    pub key_style: KeyStyle,
    /// Match rows that fit in `matches` (0 when the box is too short).
    pub match_rows: usize,
    pub more_inline: bool,
    pub number_fit: NumberFit,
}

impl KeypadLayout {
    pub fn rects(&self) -> [Rect; 7] {
        [
            self.header,
            self.number,
            self.matches,
            self.actions,
            self.grid,
            self.call_row,
            self.tab_bar,
        ]
    }
}

/// Glyph size, caption height and letter visibility for a key of `key` px.
pub fn key_style(key: f32) -> KeyStyle {
    KeyStyle {
        glyph: (key * 0.41).clamp(20.0, 32.0),
        caption_h: if key < KEY_MAX { 11.0 } else { 12.0 },
        letters: key >= LETTERS_MIN,
    }
}

/// Font size and tail window for `len` characters in a field `width` px wide.
pub fn number_fit(len: usize, width: f32) -> NumberFit {
    let width = width.max(0.0);
    let font = dial::font_step(len)
        .min(width / (len.max(1) as f32 * CHAR_W))
        .max(NUMBER_FONT_MIN);
    let keep = if len as f32 * CHAR_W * font > width {
        let slots = (width / (CHAR_W * font)).floor() as usize;
        Some(slots.saturating_sub(1))
    } else {
        None
    };
    NumberFit { font, keep }
}

/// Match rows (1..=3) for a box `h` px tall, and whether the "N more" count
/// must ride inline on the last row because there is no room for its own line.
pub fn match_plan(h: f32) -> (usize, bool) {
    let row_span = MATCH_ROW_H + MATCH_GAP;
    let with_more = ((h - MORE_H) / row_span).floor();
    if with_more >= 1.0 {
        return ((with_more as usize).min(3), false);
    }
    let rows = ((h + MATCH_GAP) / row_span).floor().clamp(1.0, 3.0);
    (rows as usize, true)
}

fn gaps(key: f32) -> (f32, f32) {
    (
        (key * COL_GAP_RATIO).clamp(10.0, 24.0),
        (key * ROW_GAP_RATIO).clamp(6.0, 12.0),
    )
}

/// Key size for `rows` rows that must fit in `budget` px of height.
fn key_from_height(budget: f32, rows: f32) -> f32 {
    let key = budget / (rows + (rows - 1.0) * ROW_GAP_RATIO);
    let (_, row_gap) = gaps(key);
    key.min((budget - (rows - 1.0) * row_gap) / rows).max(0.0)
}

/// Key size for three columns that must fit in `width` px.
fn key_from_width(width: f32) -> f32 {
    let key = width / (3.0 + 2.0 * COL_GAP_RATIO);
    let (col_gap, _) = gaps(key);
    key.min((width - 2.0 * col_gap) / 3.0).max(0.0)
}

#[derive(Clone, Copy)]
struct Sizes {
    number_h: f32,
    actions_h: f32,
    match_min: f32,
    gap: f32,
}

const NORMAL: Sizes = Sizes {
    number_h: 52.0,
    actions_h: 36.0,
    match_min: MATCH_ROW_H,
    gap: 8.0,
};
const COMPACT: Sizes = Sizes {
    number_h: 40.0,
    actions_h: 32.0,
    match_min: 40.0,
    gap: 4.0,
};

/// Which optional rows survive: (matches, actions).
type Show = (bool, bool);
const SHOW_ALL: Show = (true, true);
const DROP_STEPS: [Show; 3] = [(true, true), (false, true), (false, false)];

/// Height taken above the key block when it is anchored to the bottom.
fn fixed_above(sz: &Sizes, show: Show) -> f32 {
    let mut h = sz.number_h + sz.gap;
    if show.0 {
        h += sz.match_min + sz.gap;
    }
    if show.1 {
        h += sz.actions_h + sz.gap;
    }
    h
}

struct Column {
    number: Rect,
    matches: Rect,
    actions: Rect,
    match_rows: usize,
    more_inline: bool,
}

/// Stack number, matches and actions top-down inside `[top, limit]`; extra
/// height beyond the matches cap becomes spacing above the number.
fn stack(x: f32, w: f32, top: f32, limit: f32, sz: &Sizes, show: Show) -> Column {
    let m_gap = if show.0 { sz.gap } else { 0.0 };
    let (a_h, a_gap) = if show.1 {
        (sz.actions_h, sz.gap)
    } else {
        (0.0, 0.0)
    };
    let room = (limit - top - sz.number_h - m_gap - a_gap - a_h).max(0.0);
    let m_h = if show.0 { room.min(MATCHES_CAP) } else { 0.0 };
    let extra = room - m_h;
    let number = Rect {
        x,
        y: top + extra,
        w,
        h: sz.number_h,
    };
    let matches = Rect {
        x,
        y: number.bottom() + m_gap,
        w,
        h: m_h,
    };
    let actions = Rect {
        x,
        y: matches.bottom() + a_gap,
        w,
        h: a_h,
    };
    let (match_rows, more_inline) = if show.0 && m_h + 0.01 >= sz.match_min {
        match_plan(m_h)
    } else {
        (0, false)
    };
    Column {
        number,
        matches,
        actions,
        match_rows,
        more_inline,
    }
}

fn centred(x: f32, avail_w: f32, w: f32) -> f32 {
    x + (avail_w - w) / 2.0
}

/// Compute every section rect for the keypad screen.
pub fn keypad_layout(input: &KeypadInput) -> KeypadLayout {
    let ins = input.insets;
    let left = ins.left;
    let avail_w = (input.viewport_w - ins.left - ins.right).max(0.0);
    let bar_y = input.viewport_h - ins.bottom - input.tab_bar_margin - input.tab_bar_h;
    let bottom = bar_y - BAR_GAP;
    let tab_w = (avail_w - 2.0 * SIDE_PAD).max(0.0);
    let tab_bar = Rect {
        x: centred(left, avail_w, tab_w),
        y: bar_y,
        w: tab_w,
        h: input.tab_bar_h,
    };

    let landscape_probe = {
        let top = input.header_h.max(ins.top);
        avail_w > bottom - top
    };
    let header_h = if landscape_probe {
        input.header_h.min(LANDSCAPE_HEADER_MAX)
    } else {
        input.header_h
    };
    let top = header_h.max(ins.top);
    let avail_h = (bottom - top).max(0.0);
    let header = Rect {
        x: left,
        y: ins.top,
        w: avail_w,
        h: (top - ins.top).max(0.0),
    };
    let col_w = (avail_w - 2.0 * SIDE_PAD).clamp(0.0, COLUMN_MAX_W);

    if avail_w > avail_h {
        return landscape(input, header, tab_bar, top, bottom, avail_w, avail_h);
    }

    // Portrait, falling back to Compact when the keys would get too small.
    let width_key = key_from_width((avail_w - 2.0 * SIDE_PAD).max(0.0));
    let key_for = |sz: &Sizes, show: Show| {
        let budget = avail_h - fixed_above(sz, show);
        key_from_height(budget, 5.0).min(width_key).min(KEY_MAX)
    };
    let mut mode = KeypadMode::Portrait;
    let mut sz = NORMAL;
    let mut show = SHOW_ALL;
    let mut key = key_for(&sz, show);
    if key < KEY_MIN {
        mode = KeypadMode::Compact;
        sz = COMPACT;
        for step in DROP_STEPS {
            show = step;
            key = key_for(&sz, show);
            if key >= KEY_FLOOR {
                break;
            }
        }
    }
    let (col_gap, row_gap) = gaps(key);
    let grid_w = 3.0 * key + 2.0 * col_gap;
    let grid_h = 4.0 * key + 3.0 * row_gap;
    let grid_x = centred(left, avail_w, grid_w);
    let call_row = Rect {
        x: grid_x,
        y: bottom - key,
        w: grid_w,
        h: key,
    };
    let grid = Rect {
        x: grid_x,
        y: call_row.y - row_gap - grid_h,
        w: grid_w,
        h: grid_h,
    };
    let col = stack(
        centred(left, avail_w, col_w),
        col_w,
        top,
        grid.y - sz.gap,
        &sz,
        show,
    );
    finish(
        mode,
        input,
        header,
        col,
        grid,
        call_row,
        tab_bar,
        key,
        (col_gap, row_gap),
    )
}

fn landscape(
    input: &KeypadInput,
    header: Rect,
    tab_bar: Rect,
    top: f32,
    bottom: f32,
    avail_w: f32,
    avail_h: f32,
) -> KeypadLayout {
    let left = input.insets.left;
    let grid_budget = (avail_w - 3.0 * SIDE_PAD).max(0.0) * LANDSCAPE_GRID_SHARE;
    let key = key_from_width(grid_budget)
        .min(key_from_height(avail_h, 4.0))
        .min(KEY_MAX);
    let sz = if key < KEY_MIN { COMPACT } else { NORMAL };
    let (col_gap, row_gap) = gaps(key);
    let grid_w = 3.0 * key + 2.0 * col_gap;
    let grid_h = 4.0 * key + 3.0 * row_gap;
    let grid = Rect {
        x: left + avail_w - SIDE_PAD - grid_w,
        y: top + (avail_h - grid_h) / 2.0,
        w: grid_w,
        h: grid_h,
    };
    let left_w = (avail_w - grid_w - 3.0 * SIDE_PAD).clamp(0.0, COLUMN_MAX_W);
    let left_x = left + SIDE_PAD;
    let call_row = Rect {
        x: left_x,
        y: bottom - key,
        w: left_w,
        h: key,
    };
    let mut show = SHOW_ALL;
    if sz.number_h + sz.gap + sz.actions_h + sz.gap + key > avail_h {
        show = (true, false);
    }
    let col = stack(left_x, left_w, top, call_row.y - sz.gap, &sz, show);
    finish(
        KeypadMode::Landscape,
        input,
        header,
        col,
        grid,
        call_row,
        tab_bar,
        key,
        (col_gap, row_gap),
    )
}

#[allow(clippy::too_many_arguments)]
fn finish(
    mode: KeypadMode,
    input: &KeypadInput,
    header: Rect,
    col: Column,
    grid: Rect,
    call_row: Rect,
    tab_bar: Rect,
    key: f32,
    (col_gap, row_gap): (f32, f32),
) -> KeypadLayout {
    KeypadLayout {
        mode,
        header,
        number: col.number,
        matches: col.matches,
        actions: col.actions,
        grid,
        call_row,
        tab_bar,
        key,
        col_gap,
        row_gap,
        key_style: key_style(key),
        match_rows: col.match_rows,
        more_inline: col.more_inline,
        number_fit: number_fit(input.text_len, col.number.w - 48.0),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(w: f32, h: f32, insets: Insets, text_len: usize) -> KeypadInput {
        KeypadInput {
            viewport_w: w,
            viewport_h: h,
            insets,
            text_len,
            header_h: 105.0,
            tab_bar_h: 77.0,
            tab_bar_margin: 12.0,
        }
    }

    fn plain(w: f32, h: f32) -> KeypadInput {
        input(w, h, Insets::default(), 0)
    }

    fn assert_sane(l: &KeypadLayout, i: &KeypadInput) {
        let ctx = format!("{}x{} {:?}", i.viewport_w, i.viewport_h, l.mode);
        let rects = l.rects();
        for (a, ra) in rects.iter().enumerate() {
            for (b, rb) in rects.iter().enumerate().skip(a + 1) {
                assert!(
                    !ra.overlaps(rb),
                    "{ctx}: rect {a} overlaps {b}: {ra:?} {rb:?}"
                );
            }
        }
        let ins = i.insets;
        let safe = Rect {
            x: ins.left,
            y: ins.top,
            w: i.viewport_w - ins.left - ins.right,
            h: i.viewport_h - ins.top - ins.bottom,
        };
        let full = Rect {
            x: 0.0,
            y: 0.0,
            w: i.viewport_w,
            h: i.viewport_h,
        };
        for (n, r) in rects.iter().enumerate() {
            let bound = if n == 6 { &full } else { &safe };
            assert!(
                r.inside(bound),
                "{ctx}: rect {n} outside: {r:?} in {bound:?}"
            );
        }
        assert!(l.key > 0.0 && l.key <= KEY_MAX, "{ctx}: key {}", l.key);
        assert!(
            l.grid.w + 0.01 >= 3.0 * l.key + 2.0 * l.col_gap,
            "{ctx}: grid w"
        );
        assert!(
            l.grid.h + 0.01 >= 4.0 * l.key + 3.0 * l.row_gap,
            "{ctx}: grid h"
        );
        if l.mode != KeypadMode::Landscape {
            assert!(l.call_row.y >= l.grid.bottom() - 0.01, "{ctx}: call row");
        }
    }

    fn check(i: KeypadInput) -> KeypadLayout {
        let l = keypad_layout(&i);
        assert_sane(&l, &i);
        l
    }

    #[test]
    fn small_phone_with_insets() {
        let insets = Insets {
            top: 24.0,
            bottom: 48.0,
            ..Insets::default()
        };
        let l = check(input(360.0, 640.0, insets, 0));
        assert_ne!(l.mode, KeypadMode::Landscape);
        assert!(l.key >= KEY_FLOOR);
        assert!((l.tab_bar.bottom() - (640.0 - insets.bottom - 12.0)).abs() < 0.001);
    }

    #[test]
    fn zero_insets_tab_bar_sits_at_margin() {
        let l = check(plain(412.0, 915.0));
        assert!((l.tab_bar.bottom() - (915.0 - 12.0)).abs() < 0.001);
    }

    #[test]
    fn normal_phone() {
        let l = check(plain(412.0, 915.0));
        assert_eq!(l.mode, KeypadMode::Portrait);
        assert!(l.key >= KEY_MIN);
    }

    #[test]
    fn tall_dp_scaled_phones() {
        let a = check(plain(411.4, 914.3));
        let b = check(plain(360.0, 800.0));
        assert_eq!(a.mode, KeypadMode::Portrait);
        assert_eq!(b.mode, KeypadMode::Portrait);
    }

    #[test]
    fn raw_pixels_clamp_key() {
        let l = check(plain(1080.0, 2400.0));
        assert_eq!(l.key, KEY_MAX);
        let centre = l.grid.x + l.grid.w / 2.0;
        assert!((centre - 540.0).abs() < 0.5);
    }

    #[test]
    fn landscape_phones() {
        for (w, h) in [(915.0, 412.0), (800.0, 360.0)] {
            let l = check(plain(w, h));
            assert_eq!(l.mode, KeypadMode::Landscape);
            assert!(l.key >= KEY_FLOOR, "{w}x{h} key {}", l.key);
            assert!(l.grid.x >= l.number.right());
        }
    }

    #[test]
    fn tiny_phone_is_compact_without_letters() {
        let l = check(plain(320.0, 480.0));
        assert_eq!(l.mode, KeypadMode::Compact);
        assert!(!l.key_style.letters);
    }

    #[test]
    fn long_input_shrinks_then_keeps_the_tail() {
        let l = check(input(360.0, 800.0, Insets::default(), 40));
        assert_eq!(l.number_fit.font, 22.0);
        let keep = l.number_fit.keep.expect("40 chars must be cut");
        assert!((keep as f32 + 1.0) * CHAR_W * 22.0 <= l.number.w - 48.0);
    }

    #[test]
    fn typing_never_moves_the_keys() {
        for (w, h) in [(360.0, 640.0), (412.0, 915.0), (915.0, 412.0)] {
            let base = keypad_layout(&input(w, h, Insets::default(), 0));
            for len in [5, 15, 40] {
                let l = keypad_layout(&input(w, h, Insets::default(), len));
                assert_eq!(l.grid, base.grid);
                assert_eq!(l.call_row, base.call_row);
                assert_eq!(l.number, base.number);
            }
        }
    }

    #[test]
    fn sweep_of_viewports_stays_sane() {
        let with_insets = Insets {
            top: 24.0,
            right: 0.0,
            bottom: 34.0,
            left: 0.0,
        };
        let mut w = 280.0;
        while w <= 1400.0 {
            let mut h = 280.0;
            while h <= 2400.0 {
                check(plain(w, h));
                check(input(w, h, with_insets, 40));
                h += 40.0;
            }
            w += 40.0;
        }
    }

    #[test]
    fn key_style_scales_with_key() {
        let big = key_style(78.0);
        assert!((big.glyph - 31.98).abs() < 0.01);
        assert_eq!((big.caption_h, big.letters), (12.0, true));
        let mid = key_style(72.0);
        assert_eq!(
            (mid.glyph, mid.caption_h, mid.letters),
            (72.0 * 0.41, 11.0, true)
        );
        let small = key_style(40.0);
        assert_eq!(
            (small.glyph, small.caption_h, small.letters),
            (20.0, 11.0, false)
        );
        assert!(key_style(60.0).letters);
        assert!(!key_style(59.9).letters);
    }

    #[test]
    fn number_fit_never_grows_or_drops_below_minimum() {
        for width in [120.0, 280.0, 372.0] {
            let mut prev = f32::MAX;
            for len in 0..=60 {
                let fit = number_fit(len, width);
                assert!(fit.font <= prev, "len {len} width {width}");
                assert!(fit.font >= NUMBER_FONT_MIN);
                prev = fit.font;
                if let Some(keep) = fit.keep {
                    assert!((keep as f32 + 1.0) * CHAR_W * fit.font <= width + 0.01);
                }
            }
        }
        assert_eq!(
            number_fit(5, 300.0),
            NumberFit {
                font: 36.0,
                keep: None
            }
        );
    }

    #[test]
    fn match_plan_rows_and_more_line() {
        assert_eq!(match_plan(MATCHES_CAP), (3, false));
        assert_eq!(match_plan(90.0), (1, false));
        assert_eq!(match_plan(144.0), (2, false));
        assert_eq!(match_plan(60.0), (1, true));
        assert_eq!(match_plan(0.0), (1, true));
        assert_eq!(match_plan(160.0).0, 2);
        assert_eq!(match_plan(5000.0), (3, false));
    }
}
