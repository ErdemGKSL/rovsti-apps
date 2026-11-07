//! The "Jump to date" sheet shared by both conversation pages: a day picker
//! (month and day steppers), quick chips and Go. The pages own the state.

use gpui::{AnyElement, Context, IntoElement, ParentElement, Styled, div, prelude::*, px};
use rovsti_core::date_jump::{DayPick, quick_picks};
use rovsti_ui::{
    Icon, theme,
    tokens::{Theme, metrics, typography},
};

/// What the sheet does on the page `V`.
pub struct Actions<V> {
    /// A new day was picked.
    pub pick: fn(&mut V, DayPick, &mut Context<V>),
    /// Go.
    pub go: fn(&mut V, &mut Context<V>),
    /// Dismissed.
    pub close: fn(&mut V, &mut Context<V>),
}

impl<V> Clone for Actions<V> {
    fn clone(&self) -> Self {
        *self
    }
}
impl<V> Copy for Actions<V> {}

fn stepper<V: 'static>(
    theme: Theme,
    id: &'static str,
    icon: Icon,
    enabled: bool,
    next: DayPick,
    actions: Actions<V>,
    cx: &mut Context<V>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let color = if enabled {
        colors.accent
    } else {
        colors.secondary_label.opacity(0.4)
    };
    let button = div()
        .id(id)
        .flex_none()
        .flex()
        .items_center()
        .justify_center()
        .size(metrics::MIN_HIT)
        .rounded_full()
        .child(
            gpui::svg()
                .path(icon.path())
                .size(px(20.0))
                .text_color(color),
        );
    if enabled {
        button
            .hover(|s| s.bg(colors.label.opacity(0.06)))
            .on_click(cx.listener(move |this, _, _, cx| (actions.pick)(this, next, cx)))
            .into_any_element()
    } else {
        button.into_any_element()
    }
}

/// The sheet card; `pick` is the day now chosen.
pub fn sheet<V: 'static>(
    theme: Theme,
    pick: DayPick,
    now_ms: i64,
    tz: i32,
    actions: Actions<V>,
    cx: &mut Context<V>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let at_today = pick.is_today(now_ms, tz);
    let step = |id, icon, forward: bool, month: bool, cx: &mut Context<V>| {
        let d = if forward { 1 } else { -1 };
        let next = if month {
            pick.step_month(d, now_ms, tz)
        } else {
            pick.step_day(d, now_ms, tz)
        };
        stepper(theme, id, icon, !(forward && at_today), next, actions, cx)
    };
    let picker = div()
        .flex()
        .items_center()
        .justify_between()
        .px(px(4.0))
        .child(step(
            "date-month-back",
            Icon::LucideChevronsLeft,
            false,
            true,
            cx,
        ))
        .child(step(
            "date-day-back",
            Icon::LucideChevronLeft,
            false,
            false,
            cx,
        ))
        .child(
            div()
                .flex_1()
                .flex()
                .justify_center()
                .text_size(typography::HEADLINE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(colors.label)
                .child(pick.label(now_ms, tz)),
        )
        .child(step(
            "date-day-fwd",
            Icon::LucideChevronRight,
            true,
            false,
            cx,
        ))
        .child(step(
            "date-month-fwd",
            Icon::LucideChevronsRight,
            true,
            true,
            cx,
        ))
        .into_any_element();
    let chips =
        div()
            .flex()
            .flex_wrap()
            .gap(px(8.0))
            .px(px(12.0))
            .py(px(4.0))
            .children(quick_picks(now_ms, tz).into_iter().enumerate().map(
                |(index, (label, day))| {
                    div()
                        .id(("date-chip", index))
                        .px(px(14.0))
                        .h(px(34.0))
                        .flex()
                        .items_center()
                        .rounded_full()
                        .bg(colors.accent.opacity(0.15))
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.accent)
                        .child(label)
                        .on_click(cx.listener(move |this, _, _, cx| (actions.pick)(this, day, cx)))
                },
            ))
            .into_any_element();
    let go = crate::menu_item(
        theme,
        "date-go",
        Icon::LucideCalendarSearch,
        "Go".into(),
        colors.accent,
    )
    .on_click(cx.listener(move |this, _, _, cx| (actions.go)(this, cx)))
    .into_any_element();
    let cancel = crate::menu_item(
        theme,
        "date-cancel",
        Icon::LucideX,
        "Cancel".into(),
        colors.label,
    )
    .on_click(cx.listener(move |this, _, _, cx| (actions.close)(this, cx)))
    .into_any_element();
    crate::menu_card(
        theme,
        "date-sheet",
        "Jump to date".into(),
        vec![picker, chips, go, cancel],
        cx.listener(move |this, _, _, cx| (actions.close)(this, cx)),
    )
}
