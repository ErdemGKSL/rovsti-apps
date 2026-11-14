//! Filter chips: one horizontally scrolling row. Each chip is a 32 dp visual
//! pill inside a 44 dp hit box; the count is a small Caption2 badge. Sizes
//! come from [`crate::layout`] (chip width maths is tested in `geom`).
//!
//! The selected chip is solid accent with white text, the others a quiet
//! fill with primary text. Both pairs must keep a contrast of 4.5 (tested).

use std::rc::Rc;

use gpui::{
    AnyElement, App, Div, ElementId, Hsla, InteractiveElement as _, IntoElement,
    ParentElement as _, Rgba, SharedString, StatefulInteractiveElement as _, Styled as _, Window,
    div, px,
};
use liquid_glass_kit::tokens::{Theme, typography};

use crate::layout::{self, space};

/// Side padding of the whole row.
pub const ROW_PAD: f32 = layout::MARGIN;
/// Gap between two chips' hit boxes.
pub const CHIP_GAP: f32 = space::S;

/// One chip: label and an optional count text (already capped, "999+").
#[derive(Clone, Debug)]
pub struct ChipSpec {
    pub label: SharedString,
    pub badge: Option<SharedString>,
}

impl ChipSpec {
    pub fn new(label: impl Into<SharedString>, badge: Option<String>) -> Self {
        Self {
            label: label.into(),
            badge: badge.map(Into::into),
        }
    }
}

/// Text and fill of a chip: (ink, fill).
pub fn chip_colors(theme: Theme, selected: bool) -> (Hsla, Hsla) {
    let colors = crate::theme::colors(theme);
    if selected {
        let fill = solid_under_white(colors.accent);
        (crate::row::readable_on(fill), fill)
    } else {
        (colors.label, colors.fill)
    }
}

/// `fill`, darkened in steps until white text on it reaches 4.5:1 (the light
/// theme's accent is only about 4.0:1). Colours that already pass are kept.
pub(crate) fn solid_under_white(fill: Hsla) -> Hsla {
    let mut out = fill;
    for _ in 0..20 {
        let c: Rgba = out.into();
        if layout::geom::contrast_ratio([1.0; 3], [c.r, c.g, c.b]) >= 4.5 {
            break;
        }
        out.l *= 0.95;
    }
    out
}

/// Ink and (translucent) fill of a chip's count badge.
pub fn chip_badge_colors(theme: Theme, selected: bool) -> (Hsla, Hsla) {
    let colors = crate::theme::colors(theme);
    let (ink, _) = chip_colors(theme, selected);
    if selected {
        // Tint away from the ink so the ink gains contrast, never loses it.
        let tint = if ink.l > 0.5 {
            gpui::black()
        } else {
            gpui::white()
        };
        (ink, tint.opacity(0.22))
    } else {
        (
            // Opaque, so the count stays 4.5:1 on the chip fill over any wash.
            Hsla {
                a: 1.0,
                ..colors.secondary_label
            },
            colors.secondary_label.opacity(0.05),
        )
    }
}

fn chip(theme: Theme, spec: &ChipSpec, selected: bool) -> Div {
    let (ink, fill) = chip_colors(theme, selected);
    let (badge_ink, badge_fill) = chip_badge_colors(theme, selected);
    div()
        .h(px(layout::CHIP_H))
        .min_w(px(layout::CHIP_H))
        .px(px(space::M))
        .rounded_full()
        .bg(fill)
        .flex()
        .items_center()
        .justify_center()
        .gap(px(space::XS + 2.0))
        .text_size(typography::SUBHEADLINE.size)
        .line_height(typography::SUBHEADLINE.line)
        .font_weight(gpui::FontWeight(if selected { 600.0 } else { 500.0 }))
        .text_color(ink)
        .whitespace_nowrap()
        .child(spec.label.clone())
        .children(spec.badge.clone().map(|text| {
            div()
                .h(px(16.0))
                .min_w(px(16.0))
                .px(px(space::XS + 1.0))
                .rounded_full()
                .bg(badge_fill)
                .flex()
                .items_center()
                .justify_center()
                .text_size(typography::CAPTION2.size)
                .line_height(typography::CAPTION2.line)
                .font_weight(typography::CAPTION2.weight)
                .text_color(badge_ink)
                .child(text)
        }))
}

/// A single scrolling row of filter chips. `selected` indexes `chips`;
/// `on_select` gets the tapped index.
pub fn filter_chip_row(
    id: impl Into<ElementId>,
    theme: Theme,
    chips: &[ChipSpec],
    selected: usize,
    on_select: impl Fn(usize, &mut Window, &mut App) + 'static,
) -> AnyElement {
    let on_select = Rc::new(on_select);
    let id: ElementId = id.into();
    let items: Vec<AnyElement> = chips
        .iter()
        .enumerate()
        .map(|(i, spec)| {
            let on_select = on_select.clone();
            let accessible = match &spec.badge {
                Some(b) => format!("{}, {b}", spec.label),
                None => spec.label.to_string(),
            };
            div()
                .id(ElementId::NamedInteger("filter-chip".into(), i as u64))
                .aria_label(SharedString::from(accessible))
                .h(px(layout::HIT))
                .flex_none()
                .flex()
                .items_center()
                .child(chip(theme, spec, i == selected))
                .on_click(move |_, window, cx| on_select(i, window, cx))
                .into_any_element()
        })
        .collect();
    div()
        .id(id)
        .w_full()
        .flex_none()
        .flex()
        .items_center()
        .gap(px(CHIP_GAP))
        .px(px(ROW_PAD))
        .overflow_x_scroll()
        .children(items)
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::layout::geom::contrast_ratio;

    fn ratio(a: Hsla, b: Hsla) -> f32 {
        let (a, b): (Rgba, Rgba) = (a.into(), b.into());
        contrast_ratio([a.r, a.g, a.b], [b.r, b.g, b.b])
    }

    #[test]
    fn chip_text_is_readable_in_both_themes() {
        for theme in [Theme::Light, Theme::Dark] {
            for selected in [true, false] {
                let (ink, fill) = chip_colors(theme, selected);
                // Fills may be translucent: judge them over the page.
                let page = crate::theme::colors(theme).background;
                let blended = if fill.a < 1.0 {
                    let (f, p): (Rgba, Rgba) = (fill.into(), page.into());
                    Rgba {
                        r: f.r * f.a + p.r * (1.0 - f.a),
                        g: f.g * f.a + p.g * (1.0 - f.a),
                        b: f.b * f.a + p.b * (1.0 - f.a),
                        a: 1.0,
                    }
                    .into()
                } else {
                    fill
                };
                let r = ratio(ink, blended);
                assert!(r >= 4.5, "{theme:?} selected={selected}: {r:.2}");
            }
        }
    }
}
