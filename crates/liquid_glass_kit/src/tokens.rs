//! iOS-flavoured design tokens: colour, material, shape, type, metrics.
//! Values follow Apple's public HIG system palette and common iOS metrics.

use gpui::{FontWeight, Hsla, Pixels, Rgba, WindowAppearance, hsla, px, rgb, rgba};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum Theme {
    #[default]
    Light,
    Dark,
}

impl Theme {
    pub fn from_appearance(a: WindowAppearance) -> Self {
        match a {
            WindowAppearance::Dark | WindowAppearance::VibrantDark => Theme::Dark,
            _ => Theme::Light,
        }
    }

    pub fn is_dark(self) -> bool {
        self == Theme::Dark
    }

    /// Glass surface fill. Dark mode glass is a *white lift* (not black smoke);
    /// light mode glass is a pale grey veil.
    pub fn glass_fill(self) -> Hsla {
        match self {
            Theme::Dark => hsla(0.0, 0.0, 1.0, 0.15),
            Theme::Light => with_alpha(rgb(0xF5F5F7), 0.40),
        }
    }

    pub fn colors(self) -> Colors {
        match self {
            Theme::Light => Colors {
                accent: rgb(0x007AFF).into(),
                red: rgb(0xFF3B30).into(),
                green: rgb(0x34C759).into(),
                orange: rgb(0xFF9500).into(),
                label: rgb(0x000000).into(),
                secondary_label: rgba(0x3C3C4399).into(),
                tertiary_label: rgba(0x3C3C434D).into(),
                separator: rgba(0x3C3C4349).into(),
                background: rgb(0xFFFFFF).into(),
                grouped_background: rgb(0xF2F2F7).into(),
                secondary_background: rgb(0xFFFFFF).into(),
                fill: rgba(0x78788033).into(),
                hairline: hsla(0.0, 0.0, 0.0, 0.08),
            },
            Theme::Dark => Colors {
                accent: rgb(0x0A84FF).into(),
                red: rgb(0xFF453A).into(),
                green: rgb(0x30D158).into(),
                orange: rgb(0xFF9F0A).into(),
                label: rgb(0xFFFFFF).into(),
                secondary_label: rgba(0xEBEBF5B3).into(),
                tertiary_label: rgba(0xEBEBF54D).into(),
                separator: rgba(0x54545899).into(),
                background: rgb(0x000000).into(),
                grouped_background: rgb(0x000000).into(),
                secondary_background: rgb(0x1C1C1E).into(),
                fill: rgba(0x7878805C).into(),
                hairline: hsla(0.0, 0.0, 1.0, 0.12),
            },
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Colors {
    pub accent: Hsla,
    pub red: Hsla,
    pub green: Hsla,
    pub orange: Hsla,
    pub label: Hsla,
    pub secondary_label: Hsla,
    pub tertiary_label: Hsla,
    pub separator: Hsla,
    pub background: Hsla,
    pub grouped_background: Hsla,
    pub secondary_background: Hsla,
    pub fill: Hsla,
    pub hairline: Hsla,
}

/// Mixes two colours in straight-alpha RGB space. Use this, not an HSL lerp,
/// for cross-fades: interpolating hue swings through unrelated colours.
pub fn mix(a: Hsla, b: Hsla, t: f32) -> Hsla {
    let (a, b): (Rgba, Rgba) = (a.into(), b.into());
    let f = |x: f32, y: f32| x + (y - x) * t;
    Rgba {
        r: f(a.r, b.r),
        g: f(a.g, b.g),
        b: f(a.b, b.b),
        a: f(a.a, b.a),
    }
    .into()
}

impl Colors {
    /// Per-token cross-fade, `t` = 0 → `a`, 1 → `b`.
    pub fn lerp(a: &Colors, b: &Colors, t: f32) -> Colors {
        let m = |x: Hsla, y: Hsla| mix(x, y, t);
        Colors {
            accent: m(a.accent, b.accent),
            red: m(a.red, b.red),
            green: m(a.green, b.green),
            orange: m(a.orange, b.orange),
            label: m(a.label, b.label),
            secondary_label: m(a.secondary_label, b.secondary_label),
            tertiary_label: m(a.tertiary_label, b.tertiary_label),
            separator: m(a.separator, b.separator),
            background: m(a.background, b.background),
            grouped_background: m(a.grouped_background, b.grouped_background),
            secondary_background: m(a.secondary_background, b.secondary_background),
            fill: m(a.fill, b.fill),
            hairline: m(a.hairline, b.hairline),
        }
    }
}

pub fn with_alpha(c: Rgba, a: f32) -> Hsla {
    let mut h: Hsla = c.into();
    h.a = a;
    h
}

/// Material thickness (fill alpha over blurred backdrop) for non-refracting
/// frosted surfaces.
pub mod material {
    pub const ULTRA_THIN: f32 = 0.28;
    pub const THIN: f32 = 0.45;
    pub const REGULAR: f32 = 0.65;
    pub const THICK: f32 = 0.85;
}

/// Corner radii. Nested shapes must stay concentric: see [`concentric`].
pub mod radius {
    use gpui::{Pixels, px};
    pub const ICON_TILE: Pixels = px(7.0);
    pub const BUTTON: Pixels = px(12.0);
    pub const MENU: Pixels = px(14.0);
    pub const CARD: Pixels = px(20.0);
    pub const GROUPED_LIST: Pixels = px(26.0);
    pub const SHEET: Pixels = px(38.0);
}

/// Inner radius for a shape inset inside another: `outer - inset`, floored
/// so small pills never become sharp rectangles.
pub fn concentric(outer: Pixels, inset: Pixels) -> Pixels {
    (outer - inset).max(px(4.0))
}

/// Layout metrics (logical px).
pub mod metrics {
    use gpui::{Pixels, px};
    pub const ROW_HEIGHT: Pixels = px(44.0);
    pub const ROW_HEIGHT_SUBTITLE: Pixels = px(58.0);
    pub const ROW_INSET: Pixels = px(16.0);
    pub const SECTION_SPACING: Pixels = px(35.0);
    pub const SEPARATOR: Pixels = px(0.33);
    pub const TAB_BAR_HEIGHT: Pixels = px(56.0);
    pub const TAB_BAR_MARGIN: Pixels = px(12.0);
    pub const ICON_TILE: Pixels = px(29.0);
    pub const GRABBER: (Pixels, Pixels) = (px(36.0), px(5.0));
    pub const MIN_HIT: Pixels = px(44.0);
}

/// One text style. Apply with `.text_size(s.size).line_height(s.line).font_weight(s.weight)`
/// (tracking needs per-run letter spacing, if your GPUI build exposes it).
#[derive(Clone, Copy, Debug)]
pub struct TextStyle {
    pub size: Pixels,
    pub line: Pixels,
    pub weight: FontWeight,
    /// Letter spacing in px (negative = tighter, iOS display sizes).
    pub tracking: f32,
}

const fn ts(size: f32, line: f32, weight: f32, tracking: f32) -> TextStyle {
    TextStyle {
        size: px(size),
        line: px(line),
        weight: FontWeight(weight),
        tracking,
    }
}

/// iOS-like type ramp, heavier display weights as used by modern Apple apps.
pub mod typography {
    use super::{TextStyle, ts};
    pub const LARGE_TITLE: TextStyle = ts(34.0, 41.0, 800.0, -0.8);
    pub const TITLE: TextStyle = ts(28.0, 34.0, 800.0, -0.7);
    pub const TITLE2: TextStyle = ts(22.0, 28.0, 700.0, -0.4);
    pub const TITLE3: TextStyle = ts(20.0, 25.0, 700.0, -0.3);
    pub const HEADLINE: TextStyle = ts(16.0, 21.0, 600.0, -0.2);
    pub const BODY: TextStyle = ts(16.0, 22.0, 400.0, -0.2);
    pub const SUBHEADLINE: TextStyle = ts(15.0, 20.0, 400.0, -0.24);
    pub const FOOTNOTE: TextStyle = ts(13.0, 18.0, 400.0, -0.08);
    pub const CAPTION: TextStyle = ts(12.0, 16.0, 400.0, 0.0);
    pub const CAPTION2: TextStyle = ts(11.0, 13.0, 600.0, 0.06);
}

/// Shadows that sit *under* glass (paint with `.shadow(..)` on the same div).
pub mod shadow {
    use gpui::{BoxShadow, hsla, point, px};

    /// Cross-fade between the light (`dark_t` = 0) and dark (1) glass shadows.
    pub fn glass_mix(dark_t: f32) -> Vec<BoxShadow> {
        let f = |a: f32, b: f32| a + (b - a) * dark_t;
        vec![BoxShadow {
            color: hsla(0.0, 0.0, 0.0, f(0.12, 0.35)),
            offset: point(px(0.0), px(f(4.0, 8.0))),
            blur_radius: px(f(16.0, 24.0)),
            spread_radius: px(0.0),
            inset: false,
        }]
    }

    pub fn glass(dark: bool) -> Vec<BoxShadow> {
        if dark {
            vec![BoxShadow {
                color: hsla(0.0, 0.0, 0.0, 0.35),
                offset: point(px(0.0), px(8.0)),
                blur_radius: px(24.0),
                spread_radius: px(0.0),
                inset: false,
            }]
        } else {
            vec![BoxShadow {
                color: hsla(0.0, 0.0, 0.0, 0.12),
                offset: point(px(0.0), px(4.0)),
                blur_radius: px(16.0),
                spread_radius: px(0.0),
                inset: false,
            }]
        }
    }
}
