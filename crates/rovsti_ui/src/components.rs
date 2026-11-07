//! Small stateless building blocks. Each returns an element styled from the
//! kit's iOS-like tokens so the three apps read as one family.

use gpui::{AnyElement, Div, Hsla, IntoElement, ParentElement, SharedString, Styled, div, px, svg};
use gpui_icons::GpuiIcons;
use liquid_glass_kit::{
    glass::{GlassInteraction, GlassSpec, glass, glass_interactive},
    motion_tokens::scale,
    tokens::{Theme, metrics, radius, shadow, typography},
};

use crate::motion::press_scale;

/// Large title at the top of a screen, with an optional trailing accessory.
/// Its top padding clears the status bar / cutout (`content_top(safe.top)`).
/// Screens with action buttons use [`crate::page::PageHeader`]; this stays for
/// callers that bring their own accessory.
pub fn large_title(
    window: &gpui::Window,
    theme: Theme,
    title: impl Into<SharedString>,
    trailing: Option<AnyElement>,
) -> Div {
    let colors = crate::theme::colors(theme);
    div()
        .flex()
        .items_end()
        .justify_between()
        .px(metrics::ROW_INSET)
        .pt(px(crate::layout::geom::content_top(
            crate::layout::safe_area(window).top,
        )))
        .pb(px(crate::layout::space::S))
        .child(
            // Shrinks beside the accessory, so a long title (a group's or a
            // contact's name) wraps instead of running off the screen.
            div()
                .flex_1()
                .min_w_0()
                .text_size(typography::LARGE_TITLE.size)
                .line_height(typography::LARGE_TITLE.line)
                .font_weight(typography::LARGE_TITLE.weight)
                .text_color(colors.label)
                .child(title.into()),
        )
        .children(trailing)
}

/// Circular monogram avatar.
pub fn avatar(theme: Theme, initials: impl Into<SharedString>, size: f32, tint: Hsla) -> Div {
    let colors = crate::theme::colors(theme);
    div()
        .size(px(size))
        .flex_none()
        .rounded_full()
        .bg(tint)
        .flex()
        .items_center()
        .justify_center()
        .text_size(px(crate::layout::geom::initials_size(size)))
        .font_weight(gpui::FontWeight(600.0))
        .text_color(colors.background)
        .child(initials.into())
}

/// A two-line list row: leading element, title, subtitle, trailing detail.
pub fn list_row(
    theme: Theme,
    leading: Option<AnyElement>,
    title: impl Into<SharedString>,
    subtitle: Option<SharedString>,
    trailing: Option<SharedString>,
) -> Div {
    let colors = crate::theme::colors(theme);
    div()
        .flex()
        .items_center()
        .gap(px(12.0))
        .min_h(metrics::ROW_HEIGHT_SUBTITLE)
        .px(metrics::ROW_INSET)
        .py(px(8.0))
        .border_b(metrics::SEPARATOR)
        .border_color(colors.separator)
        .children(leading)
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .child(
                    div()
                        .text_size(typography::HEADLINE.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.label)
                        .truncate()
                        .child(title.into()),
                )
                .children(subtitle.map(|s| {
                    div()
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(colors.secondary_label)
                        .truncate()
                        .child(s)
                })),
        )
        .children(trailing.map(|t| {
            div()
                .flex_none()
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.tertiary_label)
                .child(t)
        }))
}

/// Grouped, rounded section card (Settings-style).
pub fn section(
    theme: Theme,
    header: Option<SharedString>,
    rows: impl IntoIterator<Item = AnyElement>,
) -> Div {
    let colors = crate::theme::colors(theme);
    div()
        .flex()
        .flex_col()
        .gap(px(6.0))
        .px(metrics::ROW_INSET)
        .children(header.map(|h| {
            div()
                .px(metrics::ROW_INSET)
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.secondary_label)
                .child(h)
        }))
        .child(
            div()
                .rounded(radius::GROUPED_LIST)
                .bg(colors.secondary_background)
                .overflow_hidden()
                .children(rows),
        )
}

/// A floating glass pill button with a centred label.
pub fn glass_pill(theme: Theme, label: impl Into<SharedString>) -> Div {
    let colors = crate::theme::colors(theme);
    glass(GlassSpec::control(theme))
        .h(metrics::MIN_HIT)
        .px(px(20.0))
        .rounded_full()
        .shadow(shadow::glass(theme.is_dark()))
        .flex()
        .items_center()
        .justify_center()
        .text_size(typography::HEADLINE.size)
        .font_weight(typography::HEADLINE.weight)
        .text_color(colors.label)
        .child(label.into())
}

/// Centred empty-state message without icon or action; see
/// [`crate::page::empty_state`] for the full form.
pub fn empty_state(
    theme: Theme,
    title: impl Into<SharedString>,
    body: impl Into<SharedString>,
) -> impl IntoElement {
    crate::page::empty_state(theme, None, title, body, None)
}

/// Height of the header wash below the status bar, in dp.
const WASH_REACH: f32 = 200.0;
/// Status-bar height assumed for the wash extent (the backdrop has no window).
const WASH_SAFE_TOP: f32 = 48.0;

/// Height of the header wash: `content_top + 200` dp.
pub fn wash_height() -> f32 {
    crate::layout::geom::content_top(WASH_SAFE_TOP) + WASH_REACH
}

/// Wash alphas at the three stops: peak, peak / 3, 0.
pub fn wash_stops(theme: Theme) -> [f32; 3] {
    let peak = crate::theme::wash_alpha(theme);
    [peak, peak / 3.0, 0.0]
}

/// Screen background with a faint vertical colour wash behind the header, so
/// glass chrome has something to refract and tint. `linear_gradient` takes two
/// stops, so two stacked gradient divs make the three: peak, peak / 3, 0.
pub fn backdrop(theme: Theme, accent: Hsla) -> Div {
    let colors = crate::theme::colors(theme);
    let [a0, a1, a2] = wash_stops(theme);
    let at = |a: f32| Hsla { a, ..accent };
    let h = wash_height();
    let split = h * 0.4;
    div()
        .absolute()
        .inset_0()
        .bg(colors.background)
        .child(
            div()
                .absolute()
                .top_0()
                .left_0()
                .right_0()
                .h(px(split))
                .bg(gpui::linear_gradient(
                    180.0,
                    gpui::linear_color_stop(at(a0), 0.0),
                    gpui::linear_color_stop(at(a1), 1.0),
                )),
        )
        .child(
            div()
                .absolute()
                .top(px(split))
                .left_0()
                .right_0()
                .h(px(h - split))
                .bg(gpui::linear_gradient(
                    180.0,
                    gpui::linear_color_stop(at(a1), 0.0),
                    gpui::linear_color_stop(at(a2), 1.0),
                )),
        )
}

/// A round glass icon button in a fixed 44 px slot (so a press scale never
/// moves neighbours), with an optional accent dot badge. The caller adds `id`,
/// `aria_label`, the mouse handler and a [`crate::touch::touch_surface`]
/// child; `press` is the control's [`crate::motion::Presses`] value.
pub fn glass_icon_button(theme: Theme, icon: GpuiIcons, press: f32, badge: bool) -> Div {
    glass_icon_button_scaled(theme, icon, press, badge, 1.0)
}

/// [`glass_icon_button`] with the glyph drawn at `icon_scale` (S14: the check
/// pops from 0.6). The slot and the glass keep their size, so nothing shifts.
pub fn glass_icon_button_scaled(
    theme: Theme,
    icon: GpuiIcons,
    press: f32,
    badge: bool,
    icon_scale: f32,
) -> Div {
    glass_icon_button_turned(theme, icon, press, badge, icon_scale, 0.0)
}

/// [`glass_icon_button_scaled`] with the glyph turned by `turn` radians (S24:
/// the Settings gear). The slot and the glass keep their place.
pub fn glass_icon_button_turned(
    theme: Theme,
    icon: GpuiIcons,
    press: f32,
    badge: bool,
    icon_scale: f32,
    turn: f32,
) -> Div {
    let colors = crate::theme::colors(theme);
    let held = press.clamp(0.0, 1.0);
    let side = f32::from(metrics::MIN_HIT) * press_scale(press, scale::PRESS_CARD);
    let spec = GlassSpec::control(theme).lerp(GlassSpec::lens(theme), held * 0.6);
    div()
        .relative()
        .size(metrics::MIN_HIT)
        .flex_none()
        .flex()
        .items_center()
        .justify_center()
        .child(
            glass_interactive(spec, move || GlassInteraction { press, focus: None })
                .size(px(side))
                .rounded_full()
                .shadow(shadow::glass(theme.is_dark()))
                .flex()
                .items_center()
                .justify_center()
                .child({
                    let glyph = svg()
                        .path(icon.path())
                        .size(px(20.0 * icon_scale))
                        .text_color(colors.label);
                    if turn == 0.0 {
                        glyph
                    } else {
                        glyph.with_transformation(gpui::Transformation::rotate(gpui::radians(turn)))
                    }
                }),
        )
        .children(badge.then(|| {
            div()
                .absolute()
                .top(px(5.0))
                .right(px(5.0))
                .size(px(11.0))
                .rounded_full()
                .bg(colors.accent)
                .border_2()
                .border_color(colors.background)
        }))
}

/// The flat glass look (DESIGN.md "Flat variants"): the tint, a hairline rim and the
/// shadow of a glass surface, with no backdrop sample, for surfaces that must not
/// cost a refractive pass (Held card, conference panel, waiting card). At rest it
/// reads like the refractive version. `tint` is mixed over the glass fill
/// (`warning` at 12% for the Held card).
pub fn flat_glass(theme: Theme, tint: Option<(Hsla, f32)>) -> Div {
    let fill = theme.glass_fill();
    // A flat plate has nothing behind it to refract, so its veil is a little denser.
    let base = Hsla {
        a: (fill.a + if theme.is_dark() { 0.04 } else { 0.18 }).min(0.9),
        ..fill
    };
    let plate = match tint {
        Some((color, amount)) => crate::tokens::mix(base, color, amount),
        None => base,
    };
    let rim = if theme.is_dark() {
        Hsla {
            a: 0.20,
            ..gpui::white()
        }
    } else {
        Hsla {
            a: 0.65,
            ..gpui::white()
        }
    };
    div()
        .bg(plate)
        .border(px(0.8))
        .border_color(rim)
        .shadow(crate::tokens::shadow::glass(theme.is_dark()))
}

#[cfg(test)]
mod wash_tests {
    use super::*;

    #[test]
    fn wash_covers_content_top_plus_200() {
        assert_eq!(
            wash_height(),
            crate::layout::geom::content_top(48.0) + 200.0
        );
    }

    #[test]
    fn wash_peaks_low_and_fades_in_three_stops() {
        let [p, m, z] = wash_stops(Theme::Dark);
        assert_eq!(p, 0.14);
        assert!((m - p / 3.0).abs() < 1e-6);
        assert_eq!(z, 0.0);
        assert_eq!(wash_stops(Theme::Light)[0], 0.10);
    }
}
