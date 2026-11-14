//! Page-level building blocks: the page header, section headers and empty
//! states. Sizes come from [`crate::layout`] (4 dp grid, 16 dp margin), the
//! pure geometry is tested there.

use std::rc::Rc;

use gpui::{
    AnyElement, App, Div, ElementId, InteractiveElement as _, IntoElement, ParentElement as _,
    SharedString, StatefulInteractiveElement as _, Styled as _, Window, div, px, svg,
};
use gpui_icons::GpuiIcons;
use liquid_glass_kit::{
    glass::{GlassInteraction, GlassSpec, glass_interactive},
    motion_tokens::scale,
    tokens::{Theme, shadow, typography},
};

use crate::{
    layout::{self, geom, space},
    menu::AnchorSlot,
    motion::press_scale,
};

type ActionHandler = Rc<dyn Fn(&mut Window, &mut App)>;

/// One round glass button of a [`PageHeader`].
pub struct HeaderAction {
    id: ElementId,
    icon: GpuiIcons,
    label: SharedString,
    badge: bool,
    anchor: Option<AnchorSlot>,
    on_click: Option<ActionHandler>,
}

impl HeaderAction {
    /// `label` is the accessibility label.
    pub fn new(id: impl Into<ElementId>, icon: GpuiIcons, label: impl Into<SharedString>) -> Self {
        Self {
            id: id.into(),
            icon,
            label: label.into(),
            badge: false,
            anchor: None,
            on_click: None,
        }
    }

    /// Records where the button is painted, so a [`crate::menu::Menu`] can
    /// hang off it.
    pub fn anchor(mut self, slot: &AnchorSlot) -> Self {
        self.anchor = Some(slot.clone());
        self
    }

    /// Shows the small accent dot (something new behind this action).
    pub fn badge(mut self, on: bool) -> Self {
        self.badge = on;
        self
    }

    pub fn on_click(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_click = Some(Rc::new(f));
        self
    }
}

/// Large title with up to [`layout::HEADER_MAX_ACTIONS`] glass action buttons.
///
/// The title clears the status bar (`content_top(safe.top)`), sits on the
/// 16 dp margin, and the buttons are centred on it. Each button is a 40 dp
/// glass circle with a 22 dp glyph in a 44 dp hit box; the circles are 8 dp
/// apart and the last one ends on the margin. Extra actions are dropped:
/// put them in a menu.
pub struct PageHeader {
    title: SharedString,
    subtitle: Option<SharedString>,
    actions: Vec<HeaderAction>,
}

impl PageHeader {
    pub fn new(title: impl Into<SharedString>) -> Self {
        Self {
            title: title.into(),
            subtitle: None,
            actions: Vec::new(),
        }
    }

    /// A Footnote line in the secondary colour under the title.
    pub fn subtitle(mut self, text: impl Into<SharedString>) -> Self {
        self.subtitle = Some(text.into());
        self
    }

    pub fn action(mut self, action: HeaderAction) -> Self {
        self.actions.push(action);
        self
    }

    pub fn render(self, window: &Window, theme: Theme) -> Div {
        let colors = crate::theme::colors(theme);
        let shown = geom::header_visible_actions(self.actions.len());
        debug_assert!(
            self.actions.len() <= layout::HEADER_MAX_ACTIONS,
            "a page header shows at most {} actions",
            layout::HEADER_MAX_ACTIONS
        );
        let title = div()
            .flex_1()
            .min_w_0()
            .flex()
            .flex_col()
            .child(
                // A 44 dp band, like the buttons beside it, so a subtitle
                // below never shifts the buttons off the title.
                div()
                    .min_h(px(layout::HIT))
                    .flex()
                    .items_center()
                    .text_size(typography::LARGE_TITLE.size)
                    .line_height(typography::LARGE_TITLE.line)
                    .font_weight(typography::LARGE_TITLE.weight)
                    .text_color(colors.label)
                    .child(self.title),
            )
            .children(self.subtitle.map(|s| {
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .line_height(typography::FOOTNOTE.line)
                    .text_color(colors.secondary_label)
                    .child(s)
            }));
        let buttons = div()
            .flex_none()
            .flex()
            .items_center()
            .gap(px(geom::header_hit_gap()))
            .children(
                self.actions
                    .into_iter()
                    .take(shown)
                    .map(|a| action_button(theme, a)),
            );
        div()
            .flex()
            .items_start()
            .gap(px(space::S))
            .pl(px(layout::MARGIN))
            .pr(px(geom::header_right_pad()))
            .pt(px(geom::content_top(layout::safe_area(window).top)))
            .pb(px(space::S))
            .child(title)
            .child(buttons)
    }
}

fn action_button(theme: Theme, action: HeaderAction) -> impl IntoElement {
    let HeaderAction {
        id,
        icon,
        label,
        badge,
        anchor,
        on_click,
    } = action;
    let mut slot = header_button(theme, icon, 0.0, badge)
        .id(id)
        .aria_label(label);
    if let Some(anchor) = anchor {
        slot = slot.child(anchor.track());
    }
    match on_click {
        Some(f) => slot
            .on_click(move |_, window, cx| f(window, cx))
            .into_any_element(),
        None => slot.into_any_element(),
    }
}

/// The visual of a header action: a 44 dp slot holding a 40 dp glass circle
/// with a 22 dp glyph, plus an optional accent dot. `press` is 0 at rest.
pub fn header_button(theme: Theme, icon: GpuiIcons, press: f32, badge: bool) -> Div {
    let colors = crate::theme::colors(theme);
    let held = press.clamp(0.0, 1.0);
    let side = layout::BUTTON * press_scale(press, scale::PRESS_CARD);
    let spec = GlassSpec::control(theme).lerp(GlassSpec::lens(theme), held * 0.6);
    div()
        .relative()
        .size(px(layout::HIT))
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
                .child(
                    svg()
                        .path(icon.path())
                        .size(px(layout::HEADER_ICON))
                        .text_color(colors.label),
                ),
        )
        .children(badge.then(|| {
            div()
                .absolute()
                .top(px(5.0))
                .right(px(5.0))
                .size(px(10.0))
                .rounded_full()
                .bg(colors.accent)
                .border_2()
                .border_color(colors.background)
        }))
}

/// A list section title: Footnote, weight 600, secondary colour, on the 16 dp
/// margin with 24 dp above and 8 dp below.
pub fn section_header(theme: Theme, title: impl Into<SharedString>) -> Div {
    let colors = crate::theme::colors(theme);
    div()
        .px(px(layout::MARGIN))
        .pt(px(layout::SECTION_TOP))
        .pb(px(layout::SECTION_BOTTOM))
        .text_size(typography::FOOTNOTE.size)
        .line_height(typography::FOOTNOTE.line)
        .font_weight(gpui::FontWeight(600.0))
        .text_color(colors.secondary_label)
        .child(title.into())
}

/// A centred empty state: optional 48 dp icon, Title3 title, Subhead body and
/// an optional action (for example a glass pill), in a column at most 320 dp
/// wide.
pub fn empty_state(
    theme: Theme,
    icon: Option<GpuiIcons>,
    title: impl Into<SharedString>,
    body: impl Into<SharedString>,
    action: Option<AnyElement>,
) -> Div {
    let colors = crate::theme::colors(theme);
    div().w_full().flex().justify_center().child(
        div()
            .w_full()
            .max_w(px(layout::EMPTY_MAX_W))
            .px(px(layout::MARGIN))
            .py(px(space::XXXL))
            .flex()
            .flex_col()
            .items_center()
            .gap(px(space::S))
            .children(icon.map(|icon| {
                div().pb(px(space::S)).child(
                    svg()
                        .path(icon.path())
                        .size(px(layout::EMPTY_ICON))
                        .text_color(colors.tertiary_label),
                )
            }))
            .child(
                div()
                    .text_size(typography::TITLE3.size)
                    .line_height(typography::TITLE3.line)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .text_center()
                    .child(title.into()),
            )
            .child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .line_height(typography::SUBHEADLINE.line)
                    .text_color(colors.secondary_label)
                    .text_center()
                    .child(body.into()),
            )
            .children(action.map(|a| div().pt(px(space::S)).child(a))),
    )
}
