//! The one button set. Four styles, three sizes, an optional 18 dp icon.
//!
//! ```ignore
//! Button::new("save", "Save")
//!     .style(ButtonStyle::Filled)
//!     .icon(Icon::LucideCheck)
//!     .full_width()
//!     .build(theme)
//!     .on_click(|_, _, _| {})
//! ```
//!
//! [`Button::build`] returns a `Stateful<Div>`, so callers attach `on_click`.
//! A disabled button is dimmed; the caller
//! must not attach a handler to it. Rows of buttons use equal widths and an
//! 8 dp gap ([`crate::layout::geom::buttons_row_widths`]); more than three
//! secondary actions belong in a menu.
//!
//! Ink: `Filled` uses `readable_on` the (contrast-safe) accent fill, `Tinted`
//! the accent at 0.16 alpha with `accent_text`, `Plain` `accent_text`, `Gray`
//! the secondary background with the label colour. `destructive()` swaps the
//! accent for red in any style.

use gpui::{
    Div, ElementId, Hsla, InteractiveElement as _, ParentElement as _, SharedString, Stateful,
    StatefulInteractiveElement as _, Styled as _, div, px, svg,
};
use gpui_icons::GpuiIcons;
use liquid_glass_kit::tokens::{Theme, typography};

pub use crate::layout::geom::ButtonSize;
use crate::layout::geom::{BUTTON_ICON, BUTTON_ICON_GAP};

/// Fill treatment of a [`Button`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ButtonStyle {
    Filled,
    Tinted,
    Gray,
    Plain,
}

/// Alpha of the `Tinted` fill.
pub const TINT_ALPHA: f32 = 0.16;

/// Ink and fill of a button; the fill is translucent for `Tinted`.
/// `fill.a == 0` for `Plain`.
pub fn button_colors(theme: Theme, style: ButtonStyle, destructive: bool) -> (Hsla, Hsla) {
    let colors = crate::theme::colors(theme);
    let none = Hsla {
        a: 0.0,
        ..colors.background
    };
    let (accent, text) = if destructive {
        (colors.red, destructive_text(theme))
    } else {
        (colors.accent, crate::theme::accent_text(theme))
    };
    match style {
        ButtonStyle::Filled => {
            let fill = crate::chips::solid_under_white(accent);
            (crate::row::readable_on(fill), fill)
        }
        ButtonStyle::Tinted => {
            let fill = accent.opacity(TINT_ALPHA);
            (tinted_ink(accent, fill, &colors), fill)
        }
        ButtonStyle::Gray => (
            if destructive { text } else { colors.label },
            gray_fill(theme),
        ),
        ButtonStyle::Plain => (text, none),
    }
}

/// Fill of a `Gray` button: one step off the page, in both themes (the light
/// page and its `secondary_background` are both white, so light uses the
/// grouped grey).
pub(crate) fn gray_fill(theme: Theme) -> Hsla {
    let c = crate::theme::colors(theme);
    if theme.is_dark() {
        c.secondary_background
    } else {
        c.grouped_background
    }
}

/// `accent` darkened or lightened until it reads at 4.5:1 on its own tint
/// over every page surface.
fn tinted_ink(accent: Hsla, fill: Hsla, c: &liquid_glass_kit::tokens::Colors) -> Hsla {
    let under: Vec<Hsla> = [c.background, c.grouped_background, c.secondary_background]
        .iter()
        .map(|s| crate::theme::flatten(fill, *s))
        .collect();
    crate::theme::accent_text_on(accent, &under)
}

/// Red adjusted to read as text on the page surfaces.
pub(crate) fn destructive_text(theme: Theme) -> Hsla {
    let c = crate::theme::colors(theme);
    crate::theme::accent_text_on(
        c.red,
        &[c.background, c.grouped_background, c.secondary_background],
    )
}

/// Builder for a button.
pub struct Button {
    id: ElementId,
    label: SharedString,
    style: ButtonStyle,
    size: ButtonSize,
    icon: Option<GpuiIcons>,
    destructive: bool,
    full_width: bool,
    disabled: bool,
}

impl Button {
    /// A `Filled`, `Regular` button.
    pub fn new(id: impl Into<ElementId>, label: impl Into<SharedString>) -> Self {
        Self {
            id: id.into(),
            label: label.into(),
            style: ButtonStyle::Filled,
            size: ButtonSize::Regular,
            icon: None,
            destructive: false,
            full_width: false,
            disabled: false,
        }
    }

    pub fn style(mut self, style: ButtonStyle) -> Self {
        self.style = style;
        self
    }

    pub fn size(mut self, size: ButtonSize) -> Self {
        self.size = size;
        self
    }

    /// An 18 dp icon before the label, 8 dp apart.
    pub fn icon(mut self, icon: GpuiIcons) -> Self {
        self.icon = Some(icon);
        self
    }

    /// Red instead of the accent, in any style.
    pub fn destructive(mut self) -> Self {
        self.destructive = true;
        self
    }

    pub fn full_width(mut self) -> Self {
        self.full_width = true;
        self
    }

    pub fn disabled(mut self) -> Self {
        self.disabled = true;
        self
    }

    /// The element. Add `on_click` to the result.
    pub fn build(self, theme: Theme) -> Stateful<Div> {
        let (ink, fill) = button_colors(theme, self.style, self.destructive);
        let size = self.size;
        let (text, radius) = match size {
            ButtonSize::Large => (typography::HEADLINE, 14.0),
            ButtonSize::Regular => (typography::BODY, 12.0),
            ButtonSize::Small => (typography::SUBHEADLINE, 0.0),
        };
        let weight = match size {
            ButtonSize::Large => typography::HEADLINE.weight,
            _ => gpui::FontWeight(600.0),
        };
        let mut visual = div()
            .h(px(size.height()))
            .px(px(size.pad()))
            .flex()
            .items_center()
            .justify_center()
            .gap(px(BUTTON_ICON_GAP))
            .bg(fill)
            .text_size(text.size)
            .line_height(text.line)
            .font_weight(weight)
            .text_color(ink)
            .whitespace_nowrap();
        visual = if size == ButtonSize::Small {
            visual.rounded_full()
        } else {
            visual.rounded(px(radius))
        };
        if self.full_width || size == ButtonSize::Small {
            // Small keeps its natural width unless full width was asked for.
            if self.full_width {
                visual = visual.w_full();
            }
        } else {
            visual = visual.min_w(px(size.height()));
        }
        if let Some(icon) = self.icon {
            visual = visual.child(
                svg()
                    .path(icon.path())
                    .size(px(BUTTON_ICON))
                    .flex_none()
                    .text_color(ink),
            );
        }
        visual = visual.child(self.label.clone());

        // The outer box is the touch target: the visual itself, or for Small a
        // transparent 44 dp band around it.
        let mut outer = div()
            .id(self.id)
            .aria_label(self.label)
            .min_h(px(size.hit_height()))
            .flex()
            .items_center()
            .child(visual);
        outer = if self.full_width {
            outer.w_full()
        } else {
            outer.flex_none()
        };
        if self.disabled {
            outer.opacity(0.4)
        } else {
            outer.active(|s| s.opacity(0.7))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::layout::geom::contrast_ratio;
    use gpui::Rgba;

    fn flat(fg: Hsla, bg: Hsla) -> [f32; 3] {
        let c = crate::theme::flatten(fg, bg);
        let c: Rgba = c.into();
        [c.r, c.g, c.b]
    }

    fn rgb3(c: Hsla) -> [f32; 3] {
        let c: Rgba = c.into();
        [c.r, c.g, c.b]
    }

    #[test]
    fn every_style_reads_in_both_themes() {
        for theme in [Theme::Light, Theme::Dark] {
            let page = crate::theme::colors(theme).background;
            for destructive in [false, true] {
                for style in [
                    ButtonStyle::Filled,
                    ButtonStyle::Tinted,
                    ButtonStyle::Gray,
                    ButtonStyle::Plain,
                ] {
                    let (ink, fill) = button_colors(theme, style, destructive);
                    let under = crate::theme::flatten(fill, page);
                    let r = contrast_ratio(flat(ink, under), rgb3(under));
                    assert!(
                        r >= 4.5,
                        "{theme:?} {style:?} destructive={destructive}: {r:.2}"
                    );
                }
            }
        }
    }

    #[test]
    fn plain_has_no_fill_and_tinted_is_16_percent() {
        let (_, plain) = button_colors(Theme::Dark, ButtonStyle::Plain, false);
        assert_eq!(plain.a, 0.0);
        let (_, tint) = button_colors(Theme::Light, ButtonStyle::Tinted, false);
        assert!((tint.a - TINT_ALPHA).abs() < 1e-6);
    }

    #[test]
    fn destructive_switches_to_red() {
        let red = crate::theme::colors(Theme::Dark).red;
        let (_, fill) = button_colors(Theme::Dark, ButtonStyle::Tinted, true);
        assert_eq!((fill.h, fill.s), (red.h, red.s));
    }
}
