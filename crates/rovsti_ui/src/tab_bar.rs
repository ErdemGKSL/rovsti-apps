//! Floating glass tab bar with a spring-driven puck.
//!
//! Owners keep a [`TabBar`] in their view, call [`TabBar::tick`] at the top
//! of `render`, and place [`TabBar::render`] as the last child of a
//! `relative()` root so it floats over the scrolling content.

use gpui::{
    App, Div, InteractiveElement, MouseButton, ParentElement, SharedString, Stateful, Styled,
    Window, div, px,
};
use liquid_glass_kit::{
    glass::{GlassSpec, glass},
    motion_tokens,
    spring::{FrameClock, Spring},
    tokens::{Theme, metrics, shadow, typography},
};

const BAR_PAD: f32 = 4.0;
const TAB_WIDTH: f32 = 84.0;

pub struct TabBar {
    labels: Vec<SharedString>,
    selected: usize,
    clock: FrameClock,
    /// Puck position in tab units (0.0 = first tab).
    puck: Spring,
}

impl TabBar {
    pub fn new(labels: impl IntoIterator<Item = impl Into<SharedString>>) -> Self {
        Self {
            labels: labels.into_iter().map(Into::into).collect(),
            selected: 0,
            clock: FrameClock::default(),
            puck: Spring::new(0.0, motion_tokens::select()).with_epsilon(0.002),
        }
    }

    pub fn selected(&self) -> usize {
        self.selected
    }

    pub fn select(&mut self, index: usize) {
        if index < self.labels.len() {
            self.selected = index;
            self.puck.set_target(index as f32);
        }
    }

    /// Advances the puck spring; keeps frames coming only while it moves.
    pub fn tick(&mut self, window: &mut Window) {
        let dt = self.clock.tick();
        if self.puck.tick(dt) {
            window.request_animation_frame();
        } else {
            self.clock.reset();
        }
    }

    /// `on_select` receives the tapped index; the owner calls [`Self::select`]
    /// and `cx.notify()` so the puck springs over.
    pub fn render(
        &self,
        theme: Theme,
        on_select: impl Fn(usize, &mut Window, &mut App) + 'static,
    ) -> Stateful<Div> {
        let colors = theme.colors();
        let count = self.labels.len().max(1) as f32;
        let bar_w = TAB_WIDTH * count + BAR_PAD * 2.0;
        let bar_h = metrics::TAB_BAR_HEIGHT;
        let puck_h = f32::from(bar_h) - BAR_PAD * 2.0;
        let on_select = std::rc::Rc::new(on_select);

        let tabs = self.labels.iter().enumerate().map(|(i, label)| {
            let on_select = on_select.clone();
            div()
                .id(("tab", i))
                .w(px(TAB_WIDTH))
                .h_full()
                .flex()
                .items_center()
                .justify_center()
                .whitespace_nowrap()
                .text_size(typography::CAPTION2.size)
                .font_weight(typography::CAPTION2.weight)
                .text_color(if i == self.selected {
                    colors.accent
                } else {
                    colors.label
                })
                .child(label.clone())
                .on_mouse_down(MouseButton::Left, move |_, window, cx| {
                    on_select(i, window, cx)
                })
        });

        div()
            .id("tab-bar-anchor")
            .absolute()
            .bottom(metrics::TAB_BAR_MARGIN)
            .left_0()
            .right_0()
            .flex()
            .justify_center()
            .child(
                glass(GlassSpec::chrome(theme))
                    .relative()
                    .w(px(bar_w))
                    .h(bar_h)
                    .rounded_full()
                    .shadow(shadow::glass(theme.is_dark()))
                    // Puck first so the labels paint on top of it.
                    .child(
                        glass(GlassSpec::lens(theme))
                            .absolute()
                            .top(px(BAR_PAD))
                            .left(px(BAR_PAD + self.puck.value * TAB_WIDTH))
                            .w(px(TAB_WIDTH))
                            .h(px(puck_h))
                            .rounded_full(),
                    )
                    .child(
                        div()
                            .absolute()
                            .top_0()
                            .left(px(BAR_PAD))
                            .h_full()
                            .flex()
                            .children(tabs),
                    ),
            )
    }
}

/// Bottom padding a scrolling page needs so its last row clears the bar.
pub fn content_bottom_inset() -> gpui::Pixels {
    metrics::TAB_BAR_HEIGHT + metrics::TAB_BAR_MARGIN * 2.0
}
