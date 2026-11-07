//! The one place apps read colours from.
//!
//! `liquid_glass_kit` stays untouched and keeps its iOS blue. Apps call
//! [`set_accent`] once at start-up; [`colors`] then returns the kit palette with
//! the app's accent swapped in. Without `set_accent` it equals
//! `Theme::colors()`, so apps that never call it look as before.
//! `apps/phone` and `rovsti_ui` ban `Theme::colors` with clippy's
//! `disallowed-methods`, so every read goes through here.

use std::sync::OnceLock;

use gpui::{Hsla, rgb};
use liquid_glass_kit::tokens::{Colors, Theme};

/// The accent for both themes plus the bright mid-tone used for washes and
/// glows (never for text).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AccentPair {
    pub light: Hsla,
    pub dark: Hsla,
    pub glow: Hsla,
}

impl AccentPair {
    /// From `0xRRGGBB` values.
    pub fn from_hex(light: u32, dark: u32, glow: u32) -> Self {
        Self {
            light: rgb(light).into(),
            dark: rgb(dark).into(),
            glow: rgb(glow).into(),
        }
    }

    pub fn accent(&self, theme: Theme) -> Hsla {
        match theme {
            Theme::Light => self.light,
            Theme::Dark => self.dark,
        }
    }
}

static ACCENT: OnceLock<AccentPair> = OnceLock::new();

/// Sets the app accent. Call once in `main()`; later calls are ignored.
pub fn set_accent(pair: AccentPair) {
    let _ = ACCENT.set(pair);
}

/// The kit palette with the app accent applied.
#[allow(clippy::disallowed_methods)] // the single permitted call site
pub fn colors(theme: Theme) -> Colors {
    let mut colors = theme.colors();
    if let Some(pair) = ACCENT.get() {
        colors.accent = pair.accent(theme);
    }
    if theme == Theme::Light {
        // The iOS secondary label (60 % of #3C3C43) is about 3.4:1 on white;
        // 74 % reaches the 4.5:1 the layout spec asks for body-size text.
        colors.secondary_label.a = 0.74;
    }
    colors
}

/// The bright accent mid-tone for backdrop washes, auras and glows.
#[allow(clippy::disallowed_methods)]
pub fn accent_glow(theme: Theme) -> Hsla {
    ACCENT
        .get()
        .map_or_else(|| theme.colors().accent, |p| p.glow)
}

/// Semantic call colours (accessible variants, DESIGN.md): Answer, End and
/// Decline, and the on-hold tint.
#[derive(Clone, Copy, Debug)]
pub struct CallColors {
    pub answer: Hsla,
    pub danger: Hsla,
    pub warning: Hsla,
}

pub fn call_colors(theme: Theme) -> CallColors {
    match theme {
        Theme::Light => CallColors {
            answer: rgb(0x248A3D).into(),
            danger: rgb(0xD70015).into(),
            warning: rgb(0xB25000).into(),
        },
        Theme::Dark => CallColors {
            answer: rgb(0x30D158).into(),
            danger: rgb(0xFF453A).into(),
            warning: rgb(0xFF9F0A).into(),
        },
    }
}

/// The six muted hue pairs (DESIGN.md "Palette for atmosphere"): emerald-teal,
/// teal-sky, sky-indigo, violet-rose, amber-coral, lime-emerald.
const HUE_PAIRS: [(u32, u32); 6] = [
    (0x10B981, 0x14B8A6),
    (0x14B8A6, 0x0EA5E9),
    (0x0EA5E9, 0x6366F1),
    (0x8B5CF6, 0xF43F5E),
    (0xF59E0B, 0xFB7185),
    (0x84CC16, 0x10B981),
];

/// A contact's atmosphere: two opaque colours for its avatar, aura and ring.
/// `None` (unknown number) is the accent glow over a neutral grey.
pub fn hue_pair(theme: Theme, index: Option<usize>) -> (Hsla, Hsla) {
    match index {
        Some(i) => {
            let (a, b) = HUE_PAIRS[i % HUE_PAIRS.len()];
            (rgb(a).into(), rgb(b).into())
        }
        None => {
            let grey: Hsla = if theme.is_dark() {
                rgb(0x6B7280).into()
            } else {
                rgb(0x9CA3AF).into()
            };
            (accent_glow(theme), grey)
        }
    }
}

#[cfg(test)]
#[allow(clippy::disallowed_methods)] // compares against the kit palette
mod tests {
    use super::*;

    // One test: `set_accent` is process-global.
    #[test]
    fn accent_replaces_only_the_accent() {
        let before = colors(Theme::Dark);
        assert_eq!(before.accent, Theme::Dark.colors().accent);
        let pair = AccentPair::from_hex(0x08806E, 0x2DD4B4, 0x14C8A8);
        set_accent(pair);
        let (light, dark) = (colors(Theme::Light), colors(Theme::Dark));
        assert_eq!(light.accent, pair.light);
        assert_eq!(dark.accent, pair.dark);
        let kit = Theme::Light.colors();
        assert_eq!(light.red, kit.red);
        assert_eq!(light.label, kit.label);
        assert_eq!(light.background, kit.background);
        assert_eq!(accent_glow(Theme::Light), pair.glow);
        // A second call is ignored.
        set_accent(AccentPair::from_hex(0, 0, 0));
        assert_eq!(colors(Theme::Light).accent, pair.light);
    }

    #[test]
    fn secondary_text_reaches_4_5_on_white() {
        use gpui::Rgba;
        let c = colors(Theme::Light).secondary_label;
        let (f, w): (Rgba, Rgba) = (c.into(), gpui::white().into());
        let blend = |a: f32, b: f32| a * f.a + b * (1.0 - f.a);
        let ink = [blend(f.r, w.r), blend(f.g, w.g), blend(f.b, w.b)];
        assert!(crate::layout::geom::contrast_ratio(ink, [1.0; 3]) >= 4.5);
    }
}
