//! The look of select mode, shared by the WhatsApp and SMS conversations: a glass
//! bar (Close, count, Select all, bulk actions) and the round check in front of
//! a ticked row. The state and the decisions live in `rovsti_core::multi_select`.

use gpui::{
    AnyElement, ClickEvent, Div, InteractiveElement, IntoElement, ParentElement, Styled, Window,
    div, prelude::*, px,
};
use rovsti_ui::{
    Icon,
    components::{glass_icon_button, glass_pill},
    theme,
    tokens::Theme,
};

use crate::conversation::header_bar_with;

/// What a bar button does; built with `cx.listener`.
pub type Handler = Box<dyn Fn(&ClickEvent, &mut Window, &mut gpui::App) + 'static>;

/// One bulk action pill under the bar's title.
pub struct BarAction {
    pub id: &'static str,
    pub icon: Icon,
    pub label: String,
    pub danger: bool,
    pub on_click: Handler,
}

/// The two fixed buttons of the bar.
pub struct BarEnds {
    pub close: Handler,
    pub select_all: Handler,
}

/// Select mode's glass bar. `actions` is empty while nothing is ticked.
pub fn select_bar(
    theme: Theme,
    title: String,
    ends: BarEnds,
    actions: Vec<BarAction>,
) -> AnyElement {
    let close = glass_icon_button(theme, Icon::LucideX, 0.0, false)
        .id("sel-close")
        .on_click(ends.close)
        .into_any_element();
    let all = glass_pill(theme, "Select all")
        .id("sel-all")
        .on_click(ends.select_all)
        .into_any_element();
    let row = action_row(theme, actions);
    div()
        .flex_none()
        .flex()
        .flex_col()
        .child(header_bar_with(
            theme,
            close,
            None,
            title.into(),
            None,
            Some(all),
            None,
        ))
        .child(row)
        .into_any_element()
}

fn action_row(theme: Theme, actions: Vec<BarAction>) -> Div {
    let colors = theme::colors(theme);
    let mut row = div()
        .flex()
        .flex_wrap()
        .gap(px(6.0))
        .px(px(8.0))
        .pb(px(8.0));
    for action in actions {
        let color = if action.danger {
            colors.red
        } else {
            colors.label
        };
        row = row.child(
            glass_pill(theme, action.label)
                .id(action.id)
                .flex()
                .items_center()
                .gap(px(6.0))
                .text_color(color)
                .child(
                    gpui::svg()
                        .path(action.icon.path())
                        .size(px(14.0))
                        .text_color(color),
                )
                .on_click(action.on_click),
        );
    }
    row
}

/// The glass action bar at the bottom of the screen (in place of the tab bar)
/// while rows are ticked: the same pills as [`select_bar`]. `actions` is empty
/// while nothing is ticked, which leaves a hint.
pub fn bottom_bar(theme: Theme, safe_bottom: f32, actions: Vec<BarAction>) -> AnyElement {
    let colors = theme::colors(theme);
    let body = if actions.is_empty() {
        div()
            .py(px(10.0))
            .text_size(rovsti_ui::tokens::typography::SUBHEADLINE.size)
            .text_color(colors.secondary_label)
            .child("Tick chats to act on them")
            .into_any_element()
    } else {
        action_row(theme, actions).pt(px(8.0)).into_any_element()
    };
    div()
        .id("inbox-select-bar")
        .absolute()
        .bottom(px(rovsti_ui::layout::geom::tab_bar_bottom(safe_bottom)))
        .left(px(8.0))
        .right(px(8.0))
        .flex()
        .justify_center()
        .rounded(rovsti_ui::tokens::radius::CARD)
        .child(
            rovsti_ui::components::flat_glass(theme, None)
                .rounded(rovsti_ui::tokens::radius::CARD)
                .flex()
                .justify_center()
                .child(body),
        )
        .into_any_element()
}

/// The round check in front of a row: filled accent when ticked.
pub fn check_mark(theme: Theme, selected: bool) -> Div {
    let colors = theme::colors(theme);
    let ring = if selected {
        colors.accent
    } else {
        colors.tertiary_label
    };
    div()
        .flex_none()
        .size(px(22.0))
        .rounded_full()
        .border_1()
        .border_color(ring)
        .flex()
        .items_center()
        .justify_center()
        .when(selected, |m| {
            m.bg(colors.accent).child(
                gpui::svg()
                    .path(Icon::LucideCheck.path())
                    .size(px(14.0))
                    .text_color(gpui::white()),
            )
        })
}
