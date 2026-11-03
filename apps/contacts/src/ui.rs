//! Small pieces the Contacts pages share: a form field box, a tappable row
//! and the confirmation card.

use gpui::{
    AnyElement, App, Div, ElementId, Entity, Hsla, InteractiveElement, IntoElement, MouseButton,
    ParentElement, SharedString, Stateful, Styled, Window, div, prelude::*, px, svg,
};
use rovsti_ui::{
    Icon,
    components::glass_pill,
    glass::{GlassSpec, glass},
    theme,
    tokens::{Theme, metrics, shadow, typography},
};

use crate::field::{TextField, input_layer, on_backspace};

/// How a tappable row reads.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Tone {
    Plain,
    Accent,
    Danger,
}

/// A row that can be tapped: an optional icon and a label, with the list
/// separator under it. Callers add `.on_click`.
pub(crate) fn tap_row(
    theme: Theme,
    id: impl Into<ElementId>,
    icon: Option<Icon>,
    label: impl Into<SharedString>,
    tone: Tone,
) -> Stateful<Div> {
    let colors = theme::colors(theme);
    let color = match tone {
        Tone::Plain => colors.label,
        Tone::Accent => colors.accent,
        Tone::Danger => colors.red,
    };
    div()
        .id(id)
        .flex()
        .items_center()
        .gap(px(10.0))
        .min_h(metrics::MIN_HIT)
        .px(metrics::ROW_INSET)
        .border_b(metrics::SEPARATOR)
        .border_color(colors.separator)
        .text_size(typography::BODY.size)
        .text_color(color)
        .children(icon.map(|icon| svg().path(icon.path()).size(px(18.0)).text_color(color)))
        .child(label.into())
}

/// One row of a form section: icons, boxes and buttons side by side, with the
/// list separator under it.
pub(crate) fn form_row(theme: Theme) -> Div {
    let colors = theme::colors(theme);
    div()
        .flex()
        .items_center()
        .gap(px(8.0))
        .min_h(metrics::MIN_HIT)
        .px(metrics::ROW_INSET)
        .border_b(metrics::SEPARATOR)
        .border_color(colors.separator)
}

/// A one-line (or, for a note, several-line) text box. Put it in a
/// [`form_row`].
///
/// Draws the text, a placeholder while the box is empty and untouched, and the
/// caret while it has focus; a tap focuses it and raises the keyboard.
pub(crate) fn field_box(
    theme: Theme,
    id: impl Into<ElementId>,
    field: &Entity<TextField>,
    placeholder: &'static str,
    multiline: bool,
    window: &Window,
    cx: &App,
) -> Stateful<Div> {
    let colors = theme::colors(theme);
    let focus = field.read(cx).focus_handle();
    let text: SharedString = field.read(cx).text().to_owned().into();
    let typing = focus.is_focused(window);
    let shown = if text.is_empty() && !typing {
        div()
            .text_color(colors.tertiary_label)
            .child(SharedString::from(placeholder))
    } else if multiline {
        div().text_color(colors.label).child(text)
    } else {
        div().truncate().text_color(colors.label).child(text)
    };
    let caret = typing.then(|| {
        div()
            .flex_none()
            .ml(px(1.0))
            .w(px(2.0))
            .h(px(20.0))
            .rounded_full()
            .bg(colors.accent)
    });
    let content = div()
        .relative()
        .flex_1()
        .min_w_0()
        .flex()
        .items_center()
        .text_size(typography::BODY.size)
        .when(multiline, |d| d.min_h(px(96.0)).items_start().py(px(12.0)))
        .when(!multiline, |d| d.overflow_hidden())
        .child(input_layer(field.clone(), cx))
        .child(shown)
        .children(caret);
    let tap_focus = focus.clone();
    div()
        .id(id)
        .track_focus(&focus)
        .on_key_down(on_backspace(field.clone()))
        .on_mouse_down(MouseButton::Left, move |_, window, cx| {
            window.focus(&tap_focus, cx);
            window.show_soft_keyboard();
        })
        .flex_1()
        .min_w_0()
        .flex()
        .items_center()
        .min_h(metrics::MIN_HIT)
        .child(content)
}

/// A dimmed screen with a centred glass card: a title, a sentence and the
/// buttons (already built, left to right).
pub(crate) fn confirm_card(
    theme: Theme,
    title: impl Into<SharedString>,
    body: impl Into<SharedString>,
    buttons: Vec<AnyElement>,
) -> AnyElement {
    let colors = theme::colors(theme);
    div()
        .id("confirm-scrim")
        .absolute()
        .top_0()
        .left_0()
        .size_full()
        .flex()
        .items_center()
        .justify_center()
        .px(px(28.0))
        .bg(Hsla {
            a: 0.4,
            ..gpui::black()
        })
        // The scrim swallows touches so nothing underneath reacts.
        .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
        .child(
            glass(GlassSpec::control(theme))
                .w_full()
                .max_w(px(340.0))
                .rounded(px(26.0))
                .shadow(shadow::glass(theme.is_dark()))
                .flex()
                .flex_col()
                .gap(px(10.0))
                .p(px(20.0))
                .child(
                    div()
                        .text_size(typography::HEADLINE.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.label)
                        .child(title.into()),
                )
                .child(
                    div()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child(body.into()),
                )
                .child(
                    div()
                        .flex()
                        .justify_end()
                        .gap(px(10.0))
                        .pt(px(8.0))
                        .children(buttons),
                ),
        )
        .into_any_element()
}

/// A glass pill button with an id, coloured as a destructive choice when
/// `danger` is set.
pub(crate) fn pill(
    theme: Theme,
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    danger: bool,
) -> Stateful<Div> {
    let button = glass_pill(theme, label).id(id);
    if danger {
        button.text_color(theme::colors(theme).red)
    } else {
        button
    }
}
