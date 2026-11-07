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
/// Source badge on an avatar corner.
pub const SOURCE_BADGE: f32 = 16.0;
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

    /// WCAG contrast ratio between two sRGB colours (1.0 to 21.0).
    pub fn contrast_ratio(a: [f32; 3], b: [f32; 3]) -> f32 {
        let (la, lb) = (luminance(a), luminance(b));
        let (hi, lo) = if la >= lb { (la, lb) } else { (lb, la) };
        (hi + 0.05) / (lo + 0.05)
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
}
