//! The one place apps read colours from.
//!
//! `liquid_glass_kit` stays untouched and keeps its iOS blue. Apps call
//! [`set_accent`] once at start-up; [`colors`] then returns the kit palette with
//! the app's accent swapped in. Without `set_accent` it equals
//! `Theme::colors()`, so apps that never call it look as before.
//! `apps/phone` and `rovsti_ui` ban `Theme::colors` with clippy's
//! `disallowed-methods`, so every read goes through here.

use std::sync::OnceLock;

use gpui::{Hsla, Rgba, rgb};
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
    if theme == Theme::Dark {
        colors.tertiary_label.a = 0.55; // icons only; the kit's 30 % is ~2.3:1
    }
    if theme == Theme::Light {
        // The iOS secondary label (60 % of #3C3C43) is about 3.4:1 on white;
        // 82 % reaches 4.5:1 on every surface incl. the backdrop wash.
        colors.secondary_label.a = 0.82;
        // Tertiary is icons only; the iOS 30 % is about 1.7:1, 62 % reaches 3:1.
        colors.tertiary_label.a = 0.62;
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

/// Alpha of the backdrop wash at its strongest point (top of the gradient).
pub fn wash_alpha(theme: Theme) -> f32 {
    if theme.is_dark() { 0.28 } else { 0.18 }
}

/// `fg` composited over `under` (straight alpha), as an opaque colour.
pub fn flatten(fg: Hsla, under: Hsla) -> Hsla {
    let (f, u): (Rgba, Rgba) = (fg.into(), under.into());
    let mixc = |a: f32, b: f32| a * f.a + b * u.a * (1.0 - f.a);
    let a = f.a + u.a * (1.0 - f.a);
    let norm = |c: f32| if a > 0.0 { c / a } else { 0.0 };
    Rgba {
        r: norm(mixc(f.r, u.r)),
        g: norm(mixc(f.g, u.g)),
        b: norm(mixc(f.b, u.b)),
        a,
    }
    .into()
}

/// The page background under the strongest point of the backdrop wash.
pub fn wash_peak(theme: Theme) -> Hsla {
    let glow = Hsla {
        a: wash_alpha(theme),
        ..accent_glow(theme)
    };
    flatten(glow, colors(theme).background)
}

/// `accent` adjusted until it reads as text (4.5:1) on every surface text sits
/// on. Fills keep using the plain accent.
pub fn accent_text_on(accent: Hsla, surfaces: &[Hsla]) -> Hsla {
    let a: Rgba = accent.into();
    let mut ink = [a.r, a.g, a.b];
    for surface in surfaces {
        let s: Rgba = (*surface).into();
        ink = crate::layout::geom::ensure_contrast(ink, [s.r, s.g, s.b], 4.5);
    }
    Hsla::from(Rgba {
        r: ink[0],
        g: ink[1],
        b: ink[2],
        a: a.a,
    })
}

/// The accent for text and links (light about `#0068DA`, dark unchanged for
/// the iOS blue). Use this wherever the accent is a glyph colour, not a fill.
pub fn accent_text(theme: Theme) -> Hsla {
    let c = colors(theme);
    accent_text_on(
        c.accent,
        &[
            c.background,
            c.grouped_background,
            c.secondary_background,
            wash_peak(theme),
        ],
    )
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

    /// `set_accent` is process-global: tests that read colours take this.
    static GLOBAL: std::sync::Mutex<()> = std::sync::Mutex::new(());

    // One test: `set_accent` is process-global.
    #[test]
    fn accent_replaces_only_the_accent() {
        let _g = GLOBAL.lock().unwrap_or_else(|e| e.into_inner());
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

    fn rgb3(c: Hsla) -> [f32; 3] {
        let c: Rgba = c.into();
        [c.r, c.g, c.b]
    }

    /// Every text role against every surface it sits on, both themes, alpha
    /// blended first. `tertiary_label` is icons only (3:1).
    #[test]
    fn text_roles_reach_4_5_on_every_surface() {
        let _g = GLOBAL.lock().unwrap_or_else(|e| e.into_inner());
        use crate::chips::{chip_badge_colors, chip_colors};
        use crate::layout::geom::contrast_ratio;
        for theme in [Theme::Light, Theme::Dark] {
            let c = colors(theme);
            let wash = wash_peak(theme);
            let pages = [
                ("background", c.background),
                ("grouped_background", c.grouped_background),
                ("secondary_background", c.secondary_background),
                ("wash peak", wash),
            ];
            let accent_text = accent_text(theme);
            let mut cases: Vec<(String, Hsla, Hsla)> = Vec::new();
            for (surface, bg) in pages {
                for (role, fg) in [
                    ("label", c.label),
                    ("secondary_label", c.secondary_label),
                    ("accent_text", accent_text),
                ] {
                    cases.push((format!("{role} on {surface}"), fg, bg));
                }
                let icon = contrast_ratio(rgb3(flatten(c.tertiary_label, bg)), rgb3(bg));
                assert!(
                    icon >= 3.0,
                    "{theme:?} tertiary_label icon on {surface}: {icon:.2}"
                );
            }
            for (surface, bg) in [("background", c.background), ("wash peak", wash)] {
                for selected in [false, true] {
                    let (ink, fill) = chip_colors(theme, selected);
                    let under = flatten(fill, bg);
                    cases.push((
                        format!("chip label sel={selected} on {surface}"),
                        ink,
                        under,
                    ));
                    let (bink, bfill) = chip_badge_colors(theme, selected);
                    cases.push((
                        format!("chip count sel={selected} on {surface}"),
                        bink,
                        flatten(bfill, under),
                    ));
                }
            }
            for (name, fg, bg) in cases {
                let bg = flatten(bg, c.background);
                let r = contrast_ratio(rgb3(flatten(fg, bg)), rgb3(bg));
                assert!(r >= 4.5, "{theme:?} {name}: {r:.2}");
            }
        }
    }

    #[test]
    fn accent_text_darkens_in_light_and_lightens_at_most_in_dark() {
        let blue: Hsla = rgb(0x007AFF).into();
        let light = accent_text_on(blue, &[gpui::white()]);
        assert!(light.l < blue.l);
        assert!(contrast_between(light, gpui::white()) >= 4.5);
        let kept = accent_text_on(rgb(0x0A84FF).into(), &[gpui::black()]);
        assert_eq!(kept, Hsla::from(rgb(0x0A84FF)));
    }

    fn contrast_between(a: Hsla, b: Hsla) -> f32 {
        crate::layout::geom::contrast_ratio(rgb3(a), rgb3(b))
    }
}
