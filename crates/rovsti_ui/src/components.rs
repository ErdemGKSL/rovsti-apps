//! Small stateless building blocks. Each returns an element styled from the
//! kit's iOS-like tokens so the three apps read as one family.

use gpui::{AnyElement, Div, Hsla, IntoElement, ParentElement, SharedString, Styled, div, px};
use liquid_glass_kit::{
    glass::{GlassSpec, glass},
    tokens::{Theme, metrics, radius, shadow, typography},
};

/// Large title at the top of a screen, with an optional trailing accessory.
pub fn large_title(
    theme: Theme,
    title: impl Into<SharedString>,
    trailing: Option<AnyElement>,
) -> Div {
    let colors = theme.colors();
    div()
        .flex()
        .items_end()
        .justify_between()
        .px(metrics::ROW_INSET)
        .pt(px(56.0))
        .pb(px(8.0))
        .child(
            div()
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
    let colors = theme.colors();
    div()
        .size(px(size))
        .flex_none()
        .rounded_full()
        .bg(tint)
        .flex()
        .items_center()
        .justify_center()
        .text_size(px(size * 0.4))
        .font_weight(typography::HEADLINE.weight)
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
    let colors = theme.colors();
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
    let colors = theme.colors();
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
    let colors = theme.colors();
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

/// Centred empty-state message.
pub fn empty_state(
    theme: Theme,
    title: impl Into<SharedString>,
    body: impl Into<SharedString>,
) -> impl IntoElement {
    let colors = theme.colors();
    div()
        .flex()
        .flex_col()
        .items_center()
        .gap(px(6.0))
        .px(px(32.0))
        .py(px(48.0))
        .child(
            div()
                .text_size(typography::TITLE3.size)
                .font_weight(typography::TITLE3.weight)
                .text_color(colors.label)
                .child(title.into()),
        )
        .child(
            div()
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .text_center()
                .child(body.into()),
        )
}

/// Screen background with a soft colour wash, so glass chrome has something
/// to refract and tint (glass over a flat fill reads as a grey plate).
pub fn backdrop(theme: Theme, accent: Hsla) -> Div {
    let colors = theme.colors();
    let wash = Hsla {
        a: if theme.is_dark() { 0.28 } else { 0.18 },
        ..accent
    };
    div()
        .absolute()
        .inset_0()
        .bg(colors.background)
        .child(div().absolute().inset_0().bg(gpui::linear_gradient(
            160.0,
            gpui::linear_color_stop(wash, 0.0),
            gpui::linear_color_stop(Hsla { a: 0.0, ..accent }, 0.55),
        )))
}
