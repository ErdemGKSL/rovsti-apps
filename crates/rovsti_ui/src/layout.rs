//! Shared layout tokens and pure geometry for every Rovsti screen.
//!
//! Everything here is in dp (logical px). The `geom` functions use plain `f32`
//! and no gpui types, so they are unit-tested on any host. Only [`safe_area`]
//! touches a `Window`.

use gpui::Window;

/// Spacing scale on the 4 dp grid.
pub mod space {
    pub const XS: f32 = 4.0;
    pub const S: f32 = 8.0;
    pub const M: f32 = 12.0;
    pub const L: f32 = 16.0;
    pub const XL: f32 = 20.0;
    pub const XXL: f32 = 24.0;
    pub const XXXL: f32 = 32.0;
}

/// Screen side margin.
pub const MARGIN: f32 = 16.0;
/// Minimum list row height.
pub const ROW_MIN_H: f32 = 72.0;
/// List avatar diameter.
pub const AVATAR: f32 = 48.0;
/// Circular glass action button (visual size).
pub const BUTTON: f32 = 40.0;
/// Minimum touch target.
pub const HIT: f32 = 44.0;
/// Filter chip visual height (hit area is [`HIT`]).
pub const CHIP_H: f32 = 32.0;
/// Source badge on an avatar corner (the disc; the ring is added outside).
pub const SOURCE_BADGE: f32 = 16.0;
/// Background ring around a source badge.
pub const SOURCE_RING: f32 = 2.0;
/// Overlap of neighbouring source badges on a merged contact.
pub const SOURCE_OVERLAP: f32 = 6.0;
/// Smallest disc of an avatar stack.
pub const STACK_MIN_DISC: f32 = 24.0;
/// Most discs drawn in a stack; past it the last one is the "+n" disc.
pub const STACK_MAX: usize = 3;
/// Icon beside a row title (muted, pinned) and in front of a subtitle (tick).
pub const ROW_ICON: f32 = 14.0;
/// Smallest trailing badge side.
pub const BADGE_MIN: f32 = 20.0;
/// Glyph size inside a header action button.
pub const HEADER_ICON: f32 = 22.0;
/// Header actions shown at most; more belong in a menu.
pub const HEADER_MAX_ACTIONS: usize = 2;
/// Gap between the glass circles of neighbouring header actions.
pub const HEADER_ACTION_GAP: f32 = space::S;
/// Empty-state icon size.
pub const EMPTY_ICON: f32 = 48.0;
/// Empty-state widest text column.
pub const EMPTY_MAX_W: f32 = 320.0;
/// Section header gap above and below its text.
pub const SECTION_TOP: f32 = space::XXL;
pub const SECTION_BOTTOM: f32 = space::S;

/// Floating tab bar height; mirrors `liquid_glass_kit::tokens::metrics::TAB_BAR_HEIGHT`.
pub const TAB_BAR_H: f32 = 56.0;
/// Gap between the bar and the system bottom inset.
pub const TAB_BAR_GAP: f32 = space::S;
/// Breathing room between the last row and the bar.
pub const CONTENT_GAP: f32 = space::L;
/// Gap between the top safe area and the first content.
pub const TOP_GAP: f32 = space::S;

/// Pure geometry, no gpui types.
pub mod geom {
    use super::*;

    /// Width of `count` avatars of `diameter` where each overlaps the previous
    /// by `overlap`. Zero for no avatars; never narrower than one avatar.
    pub fn avatar_stack_width(count: usize, diameter: f32, overlap: f32) -> f32 {
        if count == 0 {
            return 0.0;
        }
        let step = (diameter - overlap).max(0.0);
        diameter + (count - 1) as f32 * step
    }

    /// Disc size and step (distance between disc left edges) so that `n`
    /// discs fill `slot` exactly: `size + (n - 1) * step == slot`. The disc
    /// is `2 * slot / (n + 1)` (a lone disc fills the slot, three in 48 are
    /// 24 + 12 + 12) but never below [`STACK_MIN_DISC`], and never above the
    /// slot. `n == 0` gives `(0, 0)`.
    pub fn stack_fit(n: usize, slot: f32) -> (f32, f32) {
        if n == 0 {
            return (0.0, 0.0);
        }
        let size = (2.0 * slot / (n as f32 + 1.0))
            .max(STACK_MIN_DISC)
            .min(slot);
        let step = if n == 1 {
            0.0
        } else {
            (slot - size) / (n - 1) as f32
        };
        (size, step)
    }

    /// Font size of the monogram in an avatar of `avatar` dp: 36 %, rounded
    /// (17 dp at 48, 14 dp at 40), so two letters keep clear of the rim.
    pub fn initials_size(avatar: f32) -> f32 {
        (avatar * 0.36).round()
    }

    /// Top-left `(left, top)` of a source badge box of `badge` dp (disc plus
    /// ring), relative to the avatar's top-left, so that the box centre sits
    /// on the avatar rim at 45 degrees towards the bottom-right. The badge
    /// then overlaps the rim, not the letters inside the disc.
    pub fn source_badge_offset(avatar: f32, badge: f32) -> (f32, f32) {
        let r = avatar / 2.0;
        let c = r + r * std::f32::consts::FRAC_1_SQRT_2;
        (c - badge / 2.0, c - badge / 2.0)
    }

    /// Left edge of the text column of a standard row (separators start here).
    pub fn row_text_inset(margin: f32, avatar: f32, gap: f32) -> f32 {
        margin + avatar + gap
    }

    /// Width of a filter chip: label, optional count badge, and side padding.
    /// Never narrower than the chip height (a circle).
    pub fn chip_width(label_w: f32, badge_w: Option<f32>, pad: f32, gap: f32) -> f32 {
        let badge = badge_w.map_or(0.0, |b| gap + b);
        (label_w + badge + pad * 2.0).max(CHIP_H)
    }

    /// Press growth and squash/stretch factors of the puck (1.0 = rest).
    #[derive(Clone, Copy, Debug)]
    pub struct PuckScale {
        pub grow: f32,
        pub sx: f32,
        pub sy: f32,
    }

    impl PuckScale {
        pub const REST: Self = Self {
            grow: 1.0,
            sx: 1.0,
            sy: 1.0,
        };
    }

    /// The tab-bar puck as `(x, y, w, h)` inside a bar of `bar_w` x `bar_h`.
    ///
    /// `index` is the (fractional) tab position, `scale` the press growth and
    /// squash and stretch. The result is clamped inside the
    /// bar's inner box (the bar minus `pad` on each side), so it never pokes
    /// past the glass edge.
    pub fn puck_rect(
        bar_w: f32,
        bar_h: f32,
        pad: f32,
        count: usize,
        index: f32,
        scale: PuckScale,
    ) -> (f32, f32, f32, f32) {
        let PuckScale { grow, sx, sy } = scale;
        let inner_w = (bar_w - pad * 2.0).max(0.0);
        let inner_h = (bar_h - pad * 2.0).max(0.0);
        let tab_w = inner_w / count.max(1) as f32;
        let w = (tab_w * grow * sx).clamp(0.0, inner_w);
        let h = (inner_h * grow * sy).clamp(0.0, inner_h);
        let cx = pad + (index + 0.5) * tab_w;
        let x = (cx - w / 2.0).clamp(pad, (bar_w - pad - w).max(pad));
        let y = (bar_h / 2.0 - h / 2.0).clamp(pad, (bar_h - pad - h).max(pad));
        (x, y, w, h)
    }

    /// Bottom inset a scrollable view must reserve to clear the floating tab
    /// bar: system bottom inset, bar gap, bar, content gap, plus the keyboard.
    pub fn content_bottom(safe_bottom: f32, keyboard: f32) -> f32 {
        safe_bottom + TAB_BAR_GAP + TAB_BAR_H + CONTENT_GAP + keyboard
    }

    /// Distance from the window bottom to the resting bar's bottom edge.
    pub fn tab_bar_bottom(safe_bottom: f32) -> f32 {
        safe_bottom + TAB_BAR_GAP
    }

    /// Height of the slot that hosts the bar: the bar plus 10% for its press
    /// swell, sitting above the bottom inset and gap.
    pub fn tab_bar_slot_h(safe_bottom: f32) -> f32 {
        tab_bar_bottom(safe_bottom) + TAB_BAR_H * 1.1
    }

    /// How many of `count` header actions are shown (at most
    /// [`HEADER_MAX_ACTIONS`]).
    pub fn header_visible_actions(count: usize) -> usize {
        count.min(HEADER_MAX_ACTIONS)
    }

    /// Gap between the 44 dp hit boxes of neighbouring header actions so the
    /// 40 dp glass circles inside are [`HEADER_ACTION_GAP`] apart.
    pub fn header_hit_gap() -> f32 {
        HEADER_ACTION_GAP - (HIT - BUTTON)
    }

    /// Width of the action cluster (hit boxes plus gaps); 0 for none.
    pub fn header_actions_width(count: usize) -> f32 {
        let n = header_visible_actions(count);
        if n == 0 {
            return 0.0;
        }
        n as f32 * HIT + (n - 1) as f32 * header_hit_gap()
    }

    /// Right padding of the header row so the last glass circle's edge sits on
    /// the screen margin (the hit box extends past it by half the difference).
    pub fn header_right_pad() -> f32 {
        MARGIN - (HIT - BUTTON) / 2.0
    }

    /// Width available to the header title beside `count` actions.
    pub fn header_title_width(screen_w: f32, count: usize) -> f32 {
        let actions = header_actions_width(count);
        let gap = if actions > 0.0 { space::S } else { 0.0 };
        (screen_w - MARGIN - header_right_pad() - actions - gap).max(0.0)
    }

    /// Width of the empty-state text column in `available` width.
    pub fn empty_state_width(available: f32) -> f32 {
        (available - MARGIN * 2.0).clamp(0.0, EMPTY_MAX_W)
    }

    /// Top inset for content below the status bar / cutout.
    pub fn content_top(safe_top: f32) -> f32 {
        safe_top + TOP_GAP
    }

    /// How many avatars a stack of `total` draws and how many it folds into
    /// the "+n" disc. When folding, the disc takes the last of `max` slots,
    /// so a stack never exceeds `max` discs.
    pub fn stack_split(total: usize, max: usize) -> (usize, usize) {
        if total <= max {
            (total, 0)
        } else {
            let shown = max.saturating_sub(1);
            (shown, total - shown)
        }
    }

    /// Discs drawn for `total` avatars, the "+n" disc included.
    pub fn stack_discs(total: usize, max: usize) -> usize {
        let (shown, hidden) = stack_split(total, max);
        shown + usize::from(hidden > 0)
    }

    /// Width of a trailing count badge with `chars` characters: a circle
    /// for one digit, a pill that grows with the text after that.
    pub fn badge_width(chars: usize) -> f32 {
        const CHAR_W: f32 = 7.0;
        const PAD: f32 = 12.0;
        (chars as f32 * CHAR_W + PAD).max(BADGE_MIN)
    }

    /// Left edge of a row separator: under the text column, or the screen
    /// edge when the row has no inset. `leading_w` is 0 without a leading.
    pub fn separator_left(inset_text: bool, leading_w: f32, gap: f32) -> f32 {
        if !inset_text {
            return 0.0;
        }
        if leading_w <= 0.0 {
            MARGIN
        } else {
            row_text_inset(MARGIN, leading_w, gap)
        }
    }

    /// WCAG relative luminance of an sRGB colour with channels in `0..=1`.
    fn luminance(rgb: [f32; 3]) -> f32 {
        let lin = |c: f32| {
            let c = c.clamp(0.0, 1.0);
            if c <= 0.039_28 {
                c / 12.92
            } else {
                ((c + 0.055) / 1.055).powf(2.4)
            }
        };
        0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])
    }

    /// `fg` with its hue and saturation kept and its lightness stepped by 1 %
    /// (away from `bg`) until the contrast against `bg` is at least `min`. A
    /// colour that already passes is returned unchanged; if even black or
    /// white cannot reach `min`, the extreme is returned.
    pub fn ensure_contrast(fg: [f32; 3], bg: [f32; 3], min: f32) -> [f32; 3] {
        if contrast_ratio(fg, bg) >= min {
            return fg;
        }
        let (h, s, l) = rgb_to_hsl(fg);
        let darker = luminance(bg) > 0.5;
        let mut out = fg;
        for step in 1..=100 {
            let delta = step as f32 * 0.01;
            let l2 = if darker { l - delta } else { l + delta };
            out = hsl_to_rgb(h, s, l2.clamp(0.0, 1.0));
            if contrast_ratio(out, bg) >= min || !(0.0..=1.0).contains(&l2) {
                break;
            }
        }
        out
    }

    fn rgb_to_hsl([r, g, b]: [f32; 3]) -> (f32, f32, f32) {
        let (max, min) = (r.max(g).max(b), r.min(g).min(b));
        let l = (max + min) / 2.0;
        let d = max - min;
        if d < 1e-6 {
            return (0.0, 0.0, l);
        }
        let s = d / (1.0 - (2.0 * l - 1.0).abs());
        let h = if max == r {
            ((g - b) / d).rem_euclid(6.0)
        } else if max == g {
            (b - r) / d + 2.0
        } else {
            (r - g) / d + 4.0
        };
        (h, s, l)
    }

    fn hsl_to_rgb(h: f32, s: f32, l: f32) -> [f32; 3] {
        let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
        let x = c * (1.0 - (h.rem_euclid(2.0) - 1.0).abs());
        let m = l - c / 2.0;
        let (r, g, b) = match h as u32 {
            0 => (c, x, 0.0),
            1 => (x, c, 0.0),
            2 => (0.0, c, x),
            3 => (0.0, x, c),
            4 => (x, 0.0, c),
            _ => (c, 0.0, x),
        };
        [r + m, g + m, b + m]
    }

    /// WCAG contrast ratio between two sRGB colours (1.0 to 21.0).
    pub fn contrast_ratio(a: [f32; 3], b: [f32; 3]) -> f32 {
        let (la, lb) = (luminance(a), luminance(b));
        let (hi, lo) = if la >= lb { (la, lb) } else { (lb, la) };
        (hi + 0.05) / (lo + 0.05)
    }

    /// Plain rectangle in dp (x, y = top-left).
    #[derive(Clone, Copy, Debug, PartialEq, Default)]
    pub struct Rect {
        pub x: f32,
        pub y: f32,
        pub w: f32,
        pub h: f32,
    }

    impl Rect {
        pub const fn new(x: f32, y: f32, w: f32, h: f32) -> Self {
            Self { x, y, w, h }
        }
        pub fn right(&self) -> f32 {
            self.x + self.w
        }
        pub fn bottom(&self) -> f32 {
            self.y + self.h
        }
    }

    /// Plain size in dp.
    #[derive(Clone, Copy, Debug, PartialEq, Default)]
    pub struct Size {
        pub w: f32,
        pub h: f32,
    }

    impl Size {
        pub const fn new(w: f32, h: f32) -> Self {
            Self { w, h }
        }
    }

    /// Where a popover goes.
    #[derive(Clone, Copy, Debug, PartialEq)]
    pub struct Placement {
        /// Final rectangle; its height is capped to `max_h`.
        pub rect: Rect,
        /// True when the menu hangs below the anchor.
        pub below: bool,
        /// Room available on the chosen side; content taller than this scrolls.
        pub max_h: f32,
    }

    /// Row height inside a menu.
    pub const MENU_ROW_H: f32 = 44.0;
    /// Band between two menu groups.
    pub const MENU_GROUP_GAP: f32 = 8.0;
    /// Vertical padding inside a menu (top + bottom).
    pub const MENU_PAD_V: f32 = 12.0;
    /// Narrowest and widest popover.
    pub const POPOVER_MIN_W: f32 = 220.0;
    pub const POPOVER_MAX_W: f32 = 280.0;
    /// Leading pad + icon + gap + trailing pad around a menu label.
    pub const POPOVER_CHROME_W: f32 = 16.0 + 20.0 + 12.0 + 16.0;

    /// Popover width: the longest label plus chrome, clamped to 220..280 and
    /// never wider than the viewport minus two side margins.
    pub fn popover_width(longest_label_dp: f32, viewport_w: f32) -> f32 {
        let want = (longest_label_dp + POPOVER_CHROME_W).clamp(POPOVER_MIN_W, POPOVER_MAX_W);
        want.min((viewport_w - 2.0 * MARGIN).max(0.0))
    }

    /// Menu height for groups of the given item counts: 44 per item, an 8 dp
    /// band between groups, 12 dp padding. Empty groups are ignored.
    pub fn menu_height(groups: &[usize]) -> f32 {
        let groups: Vec<usize> = groups.iter().copied().filter(|n| *n > 0).collect();
        let items: usize = groups.iter().sum();
        if items == 0 {
            return 0.0;
        }
        MENU_ROW_H * items as f32 + MENU_GROUP_GAP * (groups.len() - 1) as f32 + MENU_PAD_V
    }

    /// Places a popover of `menu` size next to `anchor` (a zero-size anchor is
    /// a long-press point). It goes below when it fits above `bottom`, else
    /// above when it fits under `top`, else on the larger side with its
    /// height capped. Horizontally it is right-aligned to an anchor in the
    /// right half, left-aligned otherwise, and clamped to `margin`.
    pub fn place_popover(
        anchor: Rect,
        menu: Size,
        viewport: Size,
        top: f32,
        bottom: f32,
        margin: f32,
        gap: f32,
    ) -> Placement {
        let w = menu.w.min((viewport.w - 2.0 * margin).max(0.0));
        let anchor_cx = anchor.x + anchor.w / 2.0;
        let x = if anchor_cx >= viewport.w / 2.0 {
            anchor.right() - w
        } else {
            anchor.x
        };
        let x = x.min(viewport.w - margin - w).max(margin);

        let below_room = (bottom - (anchor.bottom() + gap)).max(0.0);
        let above_room = ((anchor.y - gap) - top).max(0.0);
        let below = menu.h <= below_room || (menu.h > above_room && below_room >= above_room);
        let max_h = if below { below_room } else { above_room };
        let h = menu.h.min(max_h);
        let y = if below {
            anchor.bottom() + gap
        } else {
            anchor.y - gap - h
        };
        // An anchor already past the limits still never pushes the menu out.
        let y = y.min(bottom - h).max(top);
        Placement {
            rect: Rect::new(x, y, w, h),
            below,
            max_h,
        }
    }
}

/// System insets in dp.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SafeArea {
    pub top: f32,
    pub bottom: f32,
    pub left: f32,
    pub right: f32,
}

thread_local! {
    /// Simulated inset for the screenshot rig and tests. Per thread, so
    /// parallel tests cannot see each other's choice.
    static SIMULATED: std::cell::Cell<Option<SafeArea>> = const { std::cell::Cell::new(None) };
}

/// Pretends the window has these insets (`None` restores the real ones).
pub fn set_simulated_safe_area(area: Option<SafeArea>) {
    SIMULATED.with(|c| c.set(area));
}

/// The window's safe area: the larger of the platform's insets and any
/// simulated override, per edge.
pub fn safe_area(window: &Window) -> SafeArea {
    let e = window.insets().safe_area;
    let real = SafeArea {
        top: f32::from(e.top),
        bottom: f32::from(e.bottom),
        left: f32::from(e.left),
        right: f32::from(e.right),
    };
    merge_safe_area(real, SIMULATED.with(std::cell::Cell::get))
}

/// Keyboard height still covering the window (`ime - consumed`), in dp.
pub fn keyboard_inset(window: &Window) -> f32 {
    let i = window.insets();
    f32::from((i.ime.bottom - i.consumed.bottom).max(gpui::px(0.0)))
}

/// The one bottom padding every scrollable view reserves so its last row
/// clears the floating tab bar: [`safe_area`] bottom, bar, gaps and keyboard.
pub fn content_bottom_inset(window: &Window) -> gpui::Pixels {
    gpui::px(geom::content_bottom(
        safe_area(window).bottom,
        keyboard_inset(window),
    ))
}

fn merge_safe_area(real: SafeArea, simulated: Option<SafeArea>) -> SafeArea {
    match simulated {
        None => real,
        Some(s) => SafeArea {
            top: real.top.max(s.top),
            bottom: real.bottom.max(s.bottom),
            left: real.left.max(s.left),
            right: real.right.max(s.right),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::geom::*;
    use super::*;

    #[test]
    fn initials_size_is_36_percent() {
        assert_eq!(initials_size(48.0), 17.0);
        assert_eq!(initials_size(40.0), 14.0);
        assert_eq!(initials_size(28.0), 10.0);
    }

    #[test]
    fn source_badge_centre_is_on_the_rim() {
        for (avatar, badge) in [(48.0_f32, 20.0_f32), (40.0, 20.0), (56.0, 16.0)] {
            let (l, t) = source_badge_offset(avatar, badge);
            let (cx, cy) = (
                l + badge / 2.0 - avatar / 2.0,
                t + badge / 2.0 - avatar / 2.0,
            );
            let dist = (cx * cx + cy * cy).sqrt();
            assert!(dist >= avatar / 2.0 * 0.95, "{dist}");
            assert!((dist - avatar / 2.0).abs() < 0.01);
            assert!(cx > 0.0 && cy > 0.0);
        }
    }

    #[test]
    fn stack_of_three_fits_its_width() {
        let w = avatar_stack_width(3, 26.0, 10.0);
        assert_eq!(w, 26.0 + 2.0 * 16.0);
        // Last ring's right edge is exactly the stack width.
        let last_left = 2.0 * (26.0 - 10.0);
        assert_eq!(last_left + 26.0, w);
        assert_eq!(avatar_stack_width(0, 26.0, 10.0), 0.0);
        assert_eq!(avatar_stack_width(1, 26.0, 10.0), 26.0);
        // Overlap larger than the diameter cannot shrink below one avatar.
        assert_eq!(avatar_stack_width(3, 26.0, 40.0), 26.0);
    }

    #[test]
    fn stack_fit_fills_the_slot() {
        assert_eq!(stack_fit(0, 48.0), (0.0, 0.0));
        assert_eq!(stack_fit(1, 48.0), (48.0, 0.0));
        assert_eq!(stack_fit(2, 48.0), (32.0, 16.0));
        assert_eq!(stack_fit(3, 48.0), (24.0, 12.0));
        // Four discs hit the 24 dp minimum and tighten the step instead.
        assert_eq!(stack_fit(4, 48.0), (24.0, 8.0));
        for n in 1..=4 {
            let (size, step) = stack_fit(n, 48.0);
            let width = avatar_stack_width(n, size, size - step);
            assert!(width <= 48.0 + 1e-3, "n={n} width={width}");
            assert!(size >= STACK_MIN_DISC);
        }
    }

    #[test]
    fn stack_folds_past_the_limit() {
        assert_eq!(stack_split(0, 3), (0, 0));
        assert_eq!(stack_split(3, 3), (3, 0));
        assert_eq!(stack_split(4, 3), (2, 2));
        assert_eq!(stack_split(9, 3), (2, 7));
        assert_eq!(stack_discs(3, 3), 3);
        assert_eq!(stack_discs(9, 3), 3);
        assert_eq!(stack_discs(9, 3), 3);
    }

    #[test]
    fn badge_is_a_circle_then_a_pill() {
        assert_eq!(badge_width(1), BADGE_MIN);
        assert_eq!(badge_width(2), 26.0);
        assert!(badge_width(3) > badge_width(2));
    }

    #[test]
    fn separator_starts_under_the_text() {
        assert_eq!(separator_left(true, AVATAR, space::M), 76.0);
        assert_eq!(separator_left(true, 0.0, space::M), MARGIN);
        assert_eq!(separator_left(false, AVATAR, space::M), 0.0);
    }

    #[test]
    fn bar_sits_above_bottom_inset() {
        assert_eq!(tab_bar_bottom(16.0), 24.0);
        assert!(tab_bar_slot_h(16.0) > tab_bar_bottom(16.0) + TAB_BAR_H);
        assert!(content_bottom(16.0, 0.0) >= tab_bar_bottom(16.0) + TAB_BAR_H);
        assert_eq!(tab_bar_slot_h(0.0), 8.0 + 56.0 * 1.1);
    }

    #[test]
    fn text_inset_is_margin_avatar_gap() {
        assert_eq!(row_text_inset(MARGIN, AVATAR, space::M), 76.0);
    }

    #[test]
    fn chip_width_adds_badge_and_pad() {
        assert_eq!(chip_width(40.0, None, 12.0, 6.0), 64.0);
        assert_eq!(chip_width(40.0, Some(20.0), 12.0, 6.0), 90.0);
        assert_eq!(chip_width(2.0, None, 4.0, 6.0), CHIP_H);
    }

    fn inside(r: (f32, f32, f32, f32), bar_w: f32, bar_h: f32, pad: f32) -> bool {
        let (x, y, w, h) = r;
        x >= pad - 1e-4
            && y >= pad - 1e-4
            && x + w <= bar_w - pad + 1e-4
            && y + h <= bar_h - pad + 1e-4
    }

    #[test]
    fn puck_rests_on_its_tab() {
        let (bw, bh, pad) = (4.0 * 64.0 + 8.0, 56.0, 4.0);
        let (x, y, w, h) = puck_rect(
            bw,
            bh,
            pad,
            4,
            1.0,
            PuckScale {
                grow: 1.0,
                sx: 1.0,
                sy: 1.0,
            },
        );
        assert_eq!((x, w), (pad + 64.0, 64.0));
        assert_eq!((y, h), (pad, bh - 2.0 * pad));
    }

    #[test]
    fn puck_stays_inside_bar_pressed_and_stretched() {
        let (bw, bh, pad) = (4.0 * 64.0 + 8.0, 56.0, 4.0);
        for index in [0.0, 0.5, 1.0, 2.5, 3.0] {
            for (grow, sx, sy) in [(1.2, 1.3, 0.9), (1.2, 1.0, 1.1), (1.0, 1.3, 1.0)] {
                let r = puck_rect(bw, bh, pad, 4, index, PuckScale { grow, sx, sy });
                assert!(inside(r, bw, bh, pad), "{index} {grow} {sx}: {r:?}");
            }
        }
    }

    #[test]
    fn puck_handles_overshoot_and_empty() {
        let r = puck_rect(
            100.0,
            56.0,
            4.0,
            2,
            -0.4,
            PuckScale {
                grow: 1.0,
                sx: 1.0,
                sy: 1.0,
            },
        );
        assert!(inside(r, 100.0, 56.0, 4.0));
        let r = puck_rect(
            100.0,
            56.0,
            4.0,
            2,
            1.6,
            PuckScale {
                grow: 1.2,
                sx: 1.3,
                sy: 1.0,
            },
        );
        assert!(inside(r, 100.0, 56.0, 4.0));
        let r = puck_rect(
            100.0,
            56.0,
            4.0,
            0,
            0.0,
            PuckScale {
                grow: 1.0,
                sx: 1.0,
                sy: 1.0,
            },
        );
        assert!(inside(r, 100.0, 56.0, 4.0));
    }

    #[test]
    fn content_insets_sum() {
        assert_eq!(content_bottom(0.0, 0.0), 8.0 + 56.0 + 16.0);
        assert_eq!(content_bottom(34.0, 0.0), 34.0 + 8.0 + 56.0 + 16.0);
        assert_eq!(content_bottom(34.0, 300.0), 34.0 + 80.0 + 300.0);
        assert_eq!(content_top(47.0), 55.0);
        assert_eq!(content_top(0.0), 8.0);
    }

    #[test]
    fn ensure_contrast_darkens_the_ios_blue_on_white() {
        let blue = [0.0, 122.0 / 255.0, 1.0];
        assert!(contrast_ratio(blue, [1.0; 3]) < 4.5);
        let fixed = ensure_contrast(blue, [1.0; 3], 4.5);
        assert!(contrast_ratio(fixed, [1.0; 3]) >= 4.5);
        // Same hue, so still blue: B dominant, R near zero.
        assert!(fixed[2] > fixed[1] && fixed[1] > fixed[0]);
        // Close to the #0068DA the theme uses.
        assert!(fixed[2] * 255.0 > 190.0 && fixed[2] * 255.0 < 235.0);
    }

    #[test]
    fn ensure_contrast_keeps_passing_colours_and_lightens_on_dark() {
        let navy = [0.0, 0.2, 0.5];
        assert_eq!(ensure_contrast(navy, [1.0; 3], 4.5), navy);
        let dim = [0.2, 0.2, 0.3];
        let fixed = ensure_contrast(dim, [0.0; 3], 4.5);
        assert!(contrast_ratio(fixed, [0.0; 3]) >= 4.5);
        assert!(fixed[2] > dim[2]);
    }

    #[test]
    fn contrast_known_values() {
        let black = [0.0; 3];
        let white = [1.0; 3];
        assert!((contrast_ratio(black, white) - 21.0).abs() < 1e-3);
        assert!((contrast_ratio(white, black) - 21.0).abs() < 1e-3);
        assert!((contrast_ratio(white, white) - 1.0).abs() < 1e-6);
        // #767676 on white is the classic AA boundary (about 4.54:1).
        let g = 118.0 / 255.0;
        let r = contrast_ratio([g; 3], white);
        assert!((4.5..4.6).contains(&r), "{r}");
    }

    #[test]
    fn simulated_safe_area_raises_real_insets() {
        let real = SafeArea {
            top: 10.0,
            bottom: 0.0,
            left: 0.0,
            right: 0.0,
        };
        assert_eq!(merge_safe_area(real, None), real);
        let sim = SafeArea {
            top: 4.0,
            bottom: 34.0,
            left: 0.0,
            right: 0.0,
        };
        let m = merge_safe_area(real, Some(sim));
        assert_eq!((m.top, m.bottom), (10.0, 34.0));
        set_simulated_safe_area(Some(sim));
        assert_eq!(SIMULATED.with(std::cell::Cell::get), Some(sim));
        set_simulated_safe_area(None);
        assert_eq!(SIMULATED.with(std::cell::Cell::get), None);
    }

    #[test]
    fn header_actions_cap_and_geometry() {
        assert_eq!(geom::header_visible_actions(0), 0);
        assert_eq!(geom::header_visible_actions(5), 2);
        assert_eq!(geom::header_hit_gap(), 4.0);
        assert_eq!(geom::header_actions_width(0), 0.0);
        assert_eq!(geom::header_actions_width(1), 44.0);
        assert_eq!(geom::header_actions_width(2), 92.0);
        // Three requested = two shown.
        assert_eq!(geom::header_actions_width(3), 92.0);
        // The last glass circle ends exactly on the margin: 14 + 2 inside the hit box.
        assert_eq!(geom::header_right_pad() + (HIT - BUTTON) / 2.0, MARGIN);
    }

    #[test]
    fn header_title_fits_beside_actions() {
        let w = 412.0;
        let none = geom::header_title_width(w, 0);
        let two = geom::header_title_width(w, 2);
        assert_eq!(none, w - MARGIN - 14.0);
        assert_eq!(two, none - 92.0 - space::S);
        assert_eq!(geom::header_title_width(50.0, 2), 0.0);
    }

    #[test]
    fn empty_state_width_is_capped() {
        assert_eq!(geom::empty_state_width(412.0), 320.0);
        assert_eq!(geom::empty_state_width(300.0), 268.0);
        assert_eq!(geom::empty_state_width(10.0), 0.0);
    }

    fn vp() -> Size {
        Size::new(400.0, 800.0)
    }
    const BOT: f32 = 700.0;

    #[test]
    fn popover_below_when_it_fits() {
        let p = place_popover(
            Rect::new(20.0, 100.0, 40.0, 40.0),
            Size::new(240.0, 200.0),
            vp(),
            50.0,
            BOT,
            16.0,
            8.0,
        );
        assert!(p.below);
        assert_eq!(p.rect, Rect::new(20.0, 148.0, 240.0, 200.0));
    }

    #[test]
    fn popover_flips_above_near_the_bottom() {
        let p = place_popover(
            Rect::new(20.0, 600.0, 40.0, 40.0),
            Size::new(240.0, 200.0),
            vp(),
            50.0,
            BOT,
            16.0,
            8.0,
        );
        assert!(!p.below);
        assert_eq!(p.rect.y, 600.0 - 8.0 - 200.0);
        assert_eq!(p.rect.h, 200.0);
    }

    #[test]
    fn popover_clamps_left_edge() {
        let p = place_popover(
            Rect::new(2.0, 100.0, 40.0, 40.0),
            Size::new(240.0, 100.0),
            vp(),
            50.0,
            BOT,
            16.0,
            8.0,
        );
        assert_eq!(p.rect.x, 16.0);
    }

    #[test]
    fn popover_clamps_right_edge() {
        let p = place_popover(
            Rect::new(390.0, 100.0, 40.0, 40.0),
            Size::new(240.0, 100.0),
            vp(),
            50.0,
            BOT,
            16.0,
            8.0,
        );
        assert_eq!(p.rect.right(), 384.0);
    }

    #[test]
    fn popover_right_aligns_for_right_half_anchor() {
        let p = place_popover(
            Rect::new(300.0, 100.0, 60.0, 40.0),
            Size::new(220.0, 100.0),
            vp(),
            50.0,
            BOT,
            16.0,
            8.0,
        );
        assert_eq!(p.rect.right(), 360.0);
        let q = place_popover(
            Rect::new(40.0, 100.0, 60.0, 40.0),
            Size::new(220.0, 100.0),
            vp(),
            50.0,
            BOT,
            16.0,
            8.0,
        );
        assert_eq!(q.rect.x, 40.0);
    }

    #[test]
    fn popover_caps_and_scrolls_when_neither_side_fits() {
        let p = place_popover(
            Rect::new(20.0, 380.0, 40.0, 40.0),
            Size::new(240.0, 900.0),
            vp(),
            50.0,
            BOT,
            16.0,
            8.0,
        );
        // below room 700-428=272, above room 372-50=322 -> above, capped
        assert!(!p.below);
        assert_eq!(p.max_h, 322.0);
        assert_eq!(p.rect.h, 322.0);
        assert_eq!(p.rect.y, 50.0);
    }

    #[test]
    fn popover_never_passes_the_bottom_limit() {
        for y in (60..760).step_by(20) {
            for h in [80.0, 300.0, 600.0] {
                let p = place_popover(
                    Rect::new(20.0, y as f32, 40.0, 40.0),
                    Size::new(240.0, h),
                    vp(),
                    50.0,
                    BOT,
                    16.0,
                    8.0,
                );
                assert!(p.rect.bottom() <= BOT + 0.001, "y={y} h={h}");
                assert!(p.rect.y >= 50.0 - 0.001, "y={y} h={h}");
            }
        }
    }

    #[test]
    fn popover_zero_size_anchor_point() {
        let p = place_popover(
            Rect::new(200.0, 300.0, 0.0, 0.0),
            Size::new(240.0, 200.0),
            vp(),
            50.0,
            BOT,
            16.0,
            8.0,
        );
        assert!(p.below);
        assert_eq!(p.rect.y, 308.0);
        // x = 200 is the right-half boundary: right-aligned, clamped to the margin
        assert_eq!(p.rect.x, 16.0);
    }

    #[test]
    fn popover_width_clamps() {
        assert_eq!(popover_width(10.0, 400.0), 220.0);
        assert_eq!(popover_width(120.0, 400.0), 220.0);
        assert_eq!(popover_width(200.0, 400.0), 264.0);
        assert_eq!(popover_width(500.0, 400.0), 280.0);
        assert_eq!(popover_width(500.0, 250.0), 218.0);
    }

    #[test]
    fn menu_height_formula() {
        assert_eq!(menu_height(&[]), 0.0);
        assert_eq!(menu_height(&[3]), 44.0 * 3.0 + 12.0);
        assert_eq!(menu_height(&[2, 3, 1]), 44.0 * 6.0 + 16.0 + 12.0);
        assert_eq!(menu_height(&[2, 0, 1]), 44.0 * 3.0 + 8.0 + 12.0);
    }
}
