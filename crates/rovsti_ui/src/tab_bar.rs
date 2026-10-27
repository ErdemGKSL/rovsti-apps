//! Floating glass tab bar with a spring-driven puck.
//!
//! Owners keep a [`TabBar`] in their view, advance it with [`TabBar::tick`]
//! from their [`crate::motion::Motion::frame`], call [`TabBar::release`] from
//! the root's mouse-up handler, and place [`TabBar::render`] as the last
//! child of a `relative()` root so it floats over the scrolling content.
//!
//! Tabs are Lucide icons only; each keeps its name as the accessibility label
//! so TalkBack still reads "Recents", "Keypad", …
//!
//! Motion: the puck springs to the tapped tab, stretches along its travel and
//! thins across it (squash and stretch), grows into a lens while the finger is
//! down, and the whole bar swells slightly under the press.

use std::time::Duration;

use gpui::{
    App, Div, InteractiveElement, MouseButton, ParentElement, Role, SharedString, Stateful,
    StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use gpui_icons::GpuiIcons;
use liquid_glass_kit::{
    glass::{GlassInteraction, GlassSpec, glass, glass_interactive},
    motion_tokens::{self, gesture, scale},
    spring::Spring,
    tokens::{Theme, metrics, shadow},
};

const BAR_PAD: f32 = 4.0;
const TAB_WIDTH: f32 = 64.0;
const ICON_SIZE: f32 = 22.0;
/// Puck speed (px/s) that maps to full squash and stretch.
const STRETCH_SPEED: f32 = 1500.0;

/// One tab: its icon and the name screen readers announce.
#[derive(Clone, Debug)]
pub struct Tab {
    pub icon: GpuiIcons,
    pub label: SharedString,
}

impl Tab {
    pub fn new(icon: GpuiIcons, label: impl Into<SharedString>) -> Self {
        Self {
            icon,
            label: label.into(),
        }
    }
}

pub struct TabBar {
    tabs: Vec<Tab>,
    selected: usize,
    /// Puck position in tab units (0.0 = first tab).
    puck: Spring,
    /// 0 = resting, 1 = finger down (puck grows into a lens).
    press: Spring,
    puck_sx: Spring,
    puck_sy: Spring,
}

impl TabBar {
    pub fn new(tabs: impl IntoIterator<Item = Tab>) -> Self {
        Self {
            tabs: tabs.into_iter().collect(),
            selected: 0,
            puck: Spring::new(0.0, motion_tokens::select()).with_epsilon(0.002),
            press: Spring::new(0.0, motion_tokens::press()).with_epsilon(0.002),
            puck_sx: Spring::new(1.0, motion_tokens::puck_scale_x()).with_epsilon(0.002),
            puck_sy: Spring::new(1.0, motion_tokens::puck_scale_y()).with_epsilon(0.002),
        }
    }

    pub fn selected(&self) -> usize {
        self.selected
    }

    /// Moves the selection; returns `true` if it changed.
    pub fn select(&mut self, index: usize) -> bool {
        if index >= self.tabs.len() || index == self.selected {
            return false;
        }
        self.selected = index;
        self.puck.set_target(index as f32);
        true
    }

    /// Selects without animation (initial tab, restored state).
    pub fn jump_to(&mut self, index: usize) {
        if index < self.tabs.len() {
            self.selected = index;
            self.puck.snap(index as f32);
        }
    }

    /// Finger down on the bar.
    pub fn press(&mut self) {
        self.press.retarget(1.0, motion_tokens::press());
    }

    /// Finger up anywhere.
    pub fn release(&mut self) {
        self.press.retarget(0.0, motion_tokens::release());
    }

    /// Advances every spring; returns `true` while anything still moves.
    pub fn tick(&mut self, dt: Duration) -> bool {
        let normalised = (self.puck.velocity * TAB_WIDTH / STRETCH_SPEED).clamp(-1.0, 1.0);
        let (sx, sy) = gesture::squash_stretch(normalised);
        self.puck_sx.set_target(sx);
        self.puck_sy.set_target(sy);
        let mut moving = false;
        for spring in [
            &mut self.puck,
            &mut self.press,
            &mut self.puck_sx,
            &mut self.puck_sy,
        ] {
            moving |= spring.tick(dt);
        }
        moving
    }

    /// `on_select` receives the tapped index; the owner calls [`Self::select`],
    /// [`Self::press`] and `cx.notify()`.
    pub fn render(
        &self,
        theme: Theme,
        on_select: impl Fn(usize, &mut Window, &mut App) + 'static,
    ) -> Stateful<Div> {
        let colors = theme.colors();
        let count = self.tabs.len().max(1) as f32;
        let press = self.press.value.max(0.0);
        // The bar swells a few px under the finger; the puck grows past it.
        let swell = 1.0 + 0.04 * press;
        let bar_w = (TAB_WIDTH * count + BAR_PAD * 2.0) * swell;
        let bar_h = f32::from(metrics::TAB_BAR_HEIGHT) * swell;
        let tab_w = (bar_w - BAR_PAD * 2.0) / count;

        let grow = 1.0 + (scale::PUCK_PRESSED - 1.0) * press.min(1.2) * 0.35;
        let puck_w = tab_w * grow * self.puck_sx.value;
        let puck_h = (bar_h - BAR_PAD * 2.0) * grow * self.puck_sy.value;
        let puck_cx = BAR_PAD + (self.puck.value + 0.5) * tab_w;
        let puck_cy = bar_h / 2.0;
        let puck_spec = GlassSpec::chrome(theme).lerp(GlassSpec::lens(theme), press.min(1.0));

        let on_select = std::rc::Rc::new(on_select);
        let tabs = self.tabs.iter().enumerate().map(|(i, tab)| {
            let on_select = on_select.clone();
            // Icons near the puck pick up the accent and grow a little as it
            // passes over them, so the selection reads mid-flight too.
            let near = (1.0 - (self.puck.value - i as f32).abs()).clamp(0.0, 1.0);
            let icon = ICON_SIZE * (1.0 + 0.12 * near);
            div()
                .id(("tab", i))
                .role(Role::Tab)
                .aria_label(tab.label.clone())
                .w(px(tab_w))
                .h_full()
                .flex()
                .items_center()
                .justify_center()
                .child(svg().path(tab.icon.path()).size(px(icon)).text_color(
                    liquid_glass_kit::tokens::mix(colors.label, colors.accent, near),
                ))
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
            .h(metrics::TAB_BAR_HEIGHT * 1.1)
            .flex()
            .items_center()
            .justify_center()
            .child(
                glass(GlassSpec::chrome(theme))
                    .relative()
                    .w(px(bar_w))
                    .h(px(bar_h))
                    .rounded_full()
                    .shadow(shadow::glass(theme.is_dark()))
                    // Puck first so the icons paint on top of it.
                    .child(
                        glass_interactive(puck_spec, move || GlassInteraction {
                            press,
                            focus: None,
                        })
                        .absolute()
                        .left(px(puck_cx - puck_w / 2.0))
                        .top(px(puck_cy - puck_h / 2.0))
                        .w(px(puck_w))
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

#[cfg(test)]
mod tests {
    use super::*;

    fn tabs(n: usize) -> Vec<Tab> {
        (0..n)
            .map(|i| Tab::new(GpuiIcons::LucideStar, format!("tab {i}")))
            .collect()
    }

    #[test]
    fn puck_stretches_in_flight_and_settles() {
        let mut bar = TabBar::new(tabs(3));
        assert!(bar.select(2));
        let mut widest: f32 = 1.0;
        for _ in 0..240 {
            bar.tick(Duration::from_secs_f32(1.0 / 120.0));
            widest = widest.max(bar.puck_sx.value);
        }
        assert!(widest > 1.05, "puck never stretched: {widest}");
        assert!((bar.puck.value - 2.0).abs() < 0.01);
        assert!((bar.puck_sx.value - 1.0).abs() < 0.01);
        assert!(!bar.tick(Duration::from_millis(8)));
    }

    #[test]
    fn reselecting_is_a_no_op() {
        let mut bar = TabBar::new(tabs(2));
        assert!(!bar.select(0));
        assert!(!bar.select(9));
    }
}
