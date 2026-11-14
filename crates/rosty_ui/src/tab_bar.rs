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
    StatefulInteractiveElement, Styled, TouchPhase, Window, div, px, svg,
};
use gpui_icons::GpuiIcons;
use liquid_glass_kit::{
    glass::{GlassInteraction, GlassSpec, glass, glass_interactive},
    motion_tokens::{self, gesture, scale},
    spring::Spring,
    tokens::{Theme, metrics, shadow},
};

use crate::layout;

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
    /// Count capsule on the icon ("3", "99+"); `None` hides it.
    pub badge: Option<SharedString>,
}

impl Tab {
    pub fn new(icon: GpuiIcons, label: impl Into<SharedString>) -> Self {
        Self {
            icon,
            label: label.into(),
            badge: None,
        }
    }
}

/// 8 degrees (S13).
const WIGGLE_RADIANS: f32 = 8.0 * std::f32::consts::PI / 180.0;

pub struct TabBar {
    tabs: Vec<Tab>,
    selected: usize,
    /// Puck position in tab units (0.0 = first tab).
    puck: Spring,
    /// 0 = resting, 1 = finger down (puck grows into a lens).
    press: Spring,
    puck_sx: Spring,
    puck_sy: Spring,
    /// Scale of the badge capsules: 1 at rest, kicked above 1 by [`TabBar::pop_badge`].
    badge_pop: Spring,
    /// S13: the tilt of one tab's icon (index, spring): 1 is 8 degrees, settling through 0.
    wiggle: (usize, Spring),
    /// S5: the selected icon's hop, 0 to 1 and back (the spring turns around
    /// on its own once it is nearly there).
    hop: Spring,
    /// Touch input seen: the press comes from [`TabBar::touch`], not from the
    /// mouse-down that Android synthesises when the finger lifts.
    touch_seen: bool,
    /// Bumped when a label or badge changes, so a cached copy knows to refresh.
    revision: u32,
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
            badge_pop: Spring::new(1.0, motion_tokens::release()).with_epsilon(0.002),
            wiggle: (
                0,
                Spring::new(0.0, motion_tokens::glow()).with_epsilon(0.002),
            ),
            hop: Spring::new(0.0, motion_tokens::press()).with_epsilon(0.002),
            touch_seen: false,
            revision: 0,
        }
    }

    /// Renames a tab for screen readers (Recents appends its missed count).
    pub fn set_label(&mut self, index: usize, label: impl Into<SharedString>) {
        if let Some(tab) = self.tabs.get_mut(index) {
            tab.label = label.into();
            self.revision = self.revision.wrapping_add(1);
        }
    }

    /// Shows `text` in the badge capsule on a tab, or hides it. Setting the
    /// text the tab already has does nothing (no re-render, no pop).
    pub fn set_badge(&mut self, index: usize, text: Option<SharedString>) {
        if let Some(tab) = self.tabs.get_mut(index)
            && tab.badge != text
        {
            tab.badge = text;
            self.revision = self.revision.wrapping_add(1);
        }
    }

    /// Kicks the badge capsules: they swell and spring back, and the icon of
    /// tab `index` wiggles 8 degrees once (`glow` spring). The hook for the
    /// badge-pop moment (S13); first loads and unchanged counts never call it.
    pub fn pop_badge(&mut self, index: usize) {
        self.badge_pop.snap(1.25);
        self.badge_pop.set_target(1.0);
        self.wiggle.0 = index;
        self.wiggle.1.snap(1.0);
        self.wiggle.1.set_target(0.0);
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
        // The icon hops (a retap of another tab mid-hop just goes again).
        self.hop.retarget(1.0, motion_tokens::press());
        true
    }

    /// Selects without animation (initial tab, restored state).
    pub fn jump_to(&mut self, index: usize) {
        if index < self.tabs.len() {
            self.selected = index;
            self.puck.snap(index as f32);
        }
    }

    /// Mouse press on the bar. Ignored once touch input has been seen.
    pub fn press(&mut self) {
        if !self.touch_seen {
            self.press.retarget(1.0, motion_tokens::press());
        }
    }

    /// Raw touch from the `on_touch` callback of [`TabBar::render`]: swell and
    /// lens while the finger is down, let go when it lifts.
    pub fn touch(&mut self, phase: TouchPhase) {
        match phase {
            TouchPhase::Started => {
                self.touch_seen = true;
                self.press.retarget(1.0, motion_tokens::press());
            }
            TouchPhase::Ended | TouchPhase::Cancelled => self.release(),
            TouchPhase::Moved => {}
        }
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
        // The hop rises on `press` and settles on `release`.
        if self.hop.target > 0.5 && self.hop.value > 0.9 {
            self.hop.retarget(0.0, motion_tokens::release());
        }
        let mut moving = false;
        for spring in [
            &mut self.hop,
            &mut self.puck,
            &mut self.press,
            &mut self.puck_sx,
            &mut self.puck_sy,
            &mut self.badge_pop,
            &mut self.wiggle.1,
        ] {
            moving |= spring.tick(dt);
        }
        moving
    }

    /// Everything [`TabBar::render`] draws, as plain numbers: two equal frames
    /// draw the same bar, which is what lets [`TabBarView`] skip a frame.
    pub fn frame(&self, theme: Theme, safe_bottom: f32) -> TabBarFrame {
        TabBarFrame {
            theme,
            safe_bottom,
            selected: self.selected,
            puck: self.puck.value,
            press: self.press.value,
            puck_sx: self.puck_sx.value,
            puck_sy: self.puck_sy.value,
            hop: self.hop.value,
            wiggle_tab: self.wiggle.0,
            wiggle: self.wiggle.1.value,
            badge_pop: self.badge_pop.value,
            revision: self.revision,
        }
    }

    /// `on_select` receives the tapped index; the owner calls [`Self::select`],
    /// [`Self::press`] and `cx.notify()`. `on_touch` receives the finger
    /// landing on and leaving the bar; the owner calls [`Self::touch`] and
    /// `cx.notify()`.
    pub fn render(
        &self,
        theme: Theme,
        safe_bottom: f32,
        on_select: impl Fn(usize, &mut Window, &mut App) + 'static,
        on_touch: impl Fn(TouchPhase, &mut Window, &mut App) + 'static,
    ) -> Stateful<Div> {
        div()
            .id("tab-bar-anchor")
            .absolute()
            .bottom_0()
            .left_0()
            .right_0()
            .h(px(layout::geom::tab_bar_slot_h(safe_bottom)))
            .child(bar_body(
                &self.tabs,
                &self.frame(theme, safe_bottom),
                std::rc::Rc::new(on_select),
                on_touch,
            ))
    }
}

/// A snapshot of a [`TabBar`] for drawing (see [`TabBar::frame`]).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TabBarFrame {
    theme: Theme,
    safe_bottom: f32,
    selected: usize,
    puck: f32,
    press: f32,
    puck_sx: f32,
    puck_sy: f32,
    hop: f32,
    wiggle_tab: usize,
    wiggle: f32,
    badge_pop: f32,
    revision: u32,
}

type SelectFn = std::rc::Rc<dyn Fn(usize, &mut Window, &mut App)>;
type TouchFn = std::rc::Rc<dyn Fn(TouchPhase, &mut Window, &mut App)>;

/// The tab bar as its own view, so a frame in which the bar did not change
/// (a list scrolling, a key ripple) replays the bar's last frame instead of
/// building, laying out and painting it again (`Entity::cached`; AD-21 1).
/// Call [`TabBarView::sync`] once per render of the owner, then place
/// `view.cached(..)` where the bar goes: it fills the box it is given (give it
/// [`slot_height`]) and anchors the bar `safe_bottom + 8` above the box's bottom.
pub struct TabBarView {
    tabs: Vec<Tab>,
    frame: Option<TabBarFrame>,
    on_select: SelectFn,
    on_touch: TouchFn,
}

impl TabBarView {
    pub fn new(
        on_select: impl Fn(usize, &mut Window, &mut App) + 'static,
        on_touch: impl Fn(TouchPhase, &mut Window, &mut App) + 'static,
    ) -> Self {
        Self {
            tabs: Vec::new(),
            frame: None,
            on_select: std::rc::Rc::new(on_select),
            on_touch: std::rc::Rc::new(on_touch),
        }
    }

    /// Takes the bar's current look. Notifies (and so invalidates the cached
    /// frame) only when something it draws actually changed.
    pub fn sync(
        &mut self,
        bar: &TabBar,
        theme: Theme,
        safe_bottom: f32,
        cx: &mut gpui::Context<Self>,
    ) {
        let frame = bar.frame(theme, safe_bottom);
        if self.frame == Some(frame) {
            return;
        }
        if self.frame.map(|f| f.revision) != Some(frame.revision) {
            self.tabs.clone_from(&bar.tabs);
        }
        self.frame = Some(frame);
        cx.notify();
    }
}

impl gpui::Render for TabBarView {
    fn render(
        &mut self,
        _window: &mut Window,
        _cx: &mut gpui::Context<Self>,
    ) -> impl gpui::IntoElement {
        let on_touch = std::rc::Rc::clone(&self.on_touch);
        div()
            .id("tab-bar-anchor")
            .size_full()
            .children(self.frame.map(|frame| {
                bar_body(
                    &self.tabs,
                    &frame,
                    std::rc::Rc::clone(&self.on_select),
                    move |phase, window, cx| on_touch(phase, window, cx),
                )
            }))
    }
}

/// Backdrop blur of the bar body, in px. Strong enough that a list row under
/// the bar is not readable, unlike the 2 px of ordinary chrome.
const BAR_BLUR: f32 = 12.0;

/// Strength of the page-coloured wash over the blurred backdrop.
fn bar_tint_alpha(theme: Theme) -> f32 {
    match theme {
        Theme::Light => 0.72,
        Theme::Dark => 0.60,
    }
}

/// The frosted bar: chrome glass with a heavier blur and a page-coloured tint
/// so rows scrolling under it dissolve while the icons and puck stay crisp.
fn bar_spec(theme: Theme) -> GlassSpec {
    let mut tint = crate::theme::colors(theme).background;
    tint.a = bar_tint_alpha(theme);
    GlassSpec {
        blur: px(BAR_BLUR),
        tint,
        ..GlassSpec::chrome(theme)
    }
}

/// `(bar_w, bar_h, tab_w)` for `count` tabs under `press` (0 = at rest). The
/// bar swells a few px under the finger; the puck grows past it.
fn bar_dims(count: usize, press: f32) -> (f32, f32, f32) {
    let count = count.max(1) as f32;
    let swell = 1.0 + 0.04 * press;
    let bar_w = (TAB_WIDTH * count + BAR_PAD * 2.0) * swell;
    let bar_h = f32::from(metrics::TAB_BAR_HEIGHT) * swell;
    (bar_w, bar_h, (bar_w - BAR_PAD * 2.0) / count)
}

/// The glass bar itself: puck, icons, badges and the touch surface.
fn bar_body(
    tab_list: &[Tab],
    f: &TabBarFrame,
    on_select: SelectFn,
    on_touch: impl Fn(TouchPhase, &mut Window, &mut App) + 'static,
) -> Div {
    let theme = f.theme;
    let colors = crate::theme::colors(theme);
    let press = f.press.max(0.0);
    let (bar_w, bar_h, tab_w) = bar_dims(tab_list.len(), press);

    let grow = 1.0 + (scale::PUCK_PRESSED - 1.0) * press.min(1.2) * 0.35;
    let (puck_x, puck_y, puck_w, puck_h) = layout::geom::puck_rect(
        bar_w,
        bar_h,
        BAR_PAD,
        tab_list.len(),
        f.puck,
        layout::geom::PuckScale {
            grow,
            sx: f.puck_sx,
            sy: f.puck_sy,
        },
    );
    let puck_spec = GlassSpec::chrome(theme).lerp(GlassSpec::lens(theme), press.min(1.0));

    let tabs = tab_list.iter().enumerate().map(|(i, tab)| {
        let on_select = on_select.clone();
        // Icons near the puck pick up the accent and grow a little as it
        // passes over them, so the selection reads mid-flight too.
        let near = (1.0 - (f.puck - i as f32).abs()).clamp(0.0, 1.0);
        let hop = if i == f.selected {
            0.08 * f.hop.max(0.0)
        } else {
            0.0
        };
        let icon = ICON_SIZE * (1.0 + 0.12 * near + hop);
        div()
            .id(("tab", i))
            .role(Role::Tab)
            .aria_label(tab.label.clone())
            .w(px(tab_w))
            .h_full()
            .flex()
            .items_center()
            .justify_center()
            .child(
                div()
                    .relative()
                    .flex()
                    .items_center()
                    .justify_center()
                    .child({
                        let icon_el = svg().path(tab.icon.path()).size(px(icon)).text_color(
                            liquid_glass_kit::tokens::mix(colors.label, colors.accent, near),
                        );
                        if i == f.wiggle_tab && f.wiggle.abs() > 0.002 {
                            icon_el.with_transformation(gpui::Transformation::rotate(
                                gpui::radians(f.wiggle * WIGGLE_RADIANS),
                            ))
                        } else {
                            icon_el
                        }
                    })
                    // 18 px danger capsule, 6 px up and right of the icon;
                    // absolute, so a count appearing never moves the bar.
                    .children(tab.badge.clone().map(|text| {
                        let pop = f.badge_pop.max(0.0);
                        div()
                            .absolute()
                            .top(px(-9.0 * pop))
                            .left(px(icon - 6.0))
                            .h(px(18.0 * pop))
                            .min_w(px(18.0 * pop))
                            .px(px(5.0))
                            .rounded_full()
                            .bg(crate::theme::call_colors(theme).danger)
                            .flex()
                            .items_center()
                            .justify_center()
                            .text_size(crate::tokens::typography::CAPTION2.size)
                            .font_weight(crate::tokens::typography::HEADLINE.weight)
                            .text_color(gpui::white())
                            .child(text)
                    })),
            )
            .on_mouse_down(MouseButton::Left, move |_, window, cx| {
                on_select(i, window, cx)
            })
    });

    let bar = glass(bar_spec(theme))
        .relative()
        // The bar is above the page: a tap on it must not also reach the rows and buttons
        // that happen to sit under it.
        .occlude()
        .w(px(bar_w))
        .h(px(bar_h))
        .rounded_full()
        .shadow(shadow::glass(theme.is_dark()))
        // Puck first so the icons paint on top of it.
        .child(
            glass_interactive(puck_spec, move || GlassInteraction { press, focus: None })
                .absolute()
                .left(px(puck_x))
                .top(px(puck_y))
                .w(px(puck_w))
                .h(px(puck_h))
                .rounded_full(),
        )
        .child(crate::touch::touch_surface(on_touch))
        .child(
            div()
                .absolute()
                .top_0()
                .left(px(BAR_PAD))
                .h_full()
                .flex()
                .children(tabs),
        );

    // Anchored `safe_bottom + 8` above the window bottom; the slot is taller
    // than the bar so the press swell has room to grow upwards.
    div()
        .size_full()
        .flex()
        .items_end()
        .justify_center()
        .pb(px(layout::geom::tab_bar_bottom(f.safe_bottom)))
        .child(bar)
}

/// Height of the box that hosts the bar (a [`TabBarView`] or [`TabBar::render`]):
/// the bar with room for its press swell, above the bottom inset.
pub fn slot_height(safe_bottom: f32) -> gpui::Pixels {
    px(layout::geom::tab_bar_slot_h(safe_bottom))
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
    fn puck_is_centred_on_its_icon_at_rest() {
        for count in [3usize, 4] {
            let (bar_w, bar_h, tab_w) = bar_dims(count, 0.0);
            for i in 0..count {
                let (x, y, w, h) = layout::geom::puck_rect(
                    bar_w,
                    bar_h,
                    BAR_PAD,
                    count,
                    i as f32,
                    layout::geom::PuckScale::REST,
                );
                // Icons are centred in a tab_w slot starting at BAR_PAD, and
                // vertically in the bar.
                let icon = (BAR_PAD + (i as f32 + 0.5) * tab_w, bar_h / 2.0);
                assert!(
                    (x + w / 2.0 - icon.0).abs() < 0.5,
                    "{count} tabs, tab {i}: x"
                );
                assert!(
                    (y + h / 2.0 - icon.1).abs() < 0.5,
                    "{count} tabs, tab {i}: y"
                );
            }
        }
    }

    #[test]
    fn bar_tint_is_frosted() {
        assert!((bar_tint_alpha(Theme::Light) - 0.72).abs() < 1e-6);
        assert!((bar_tint_alpha(Theme::Dark) - 0.60).abs() < 1e-6);
        assert_eq!(bar_spec(Theme::Light).blur, px(12.0));
    }

    #[test]
    fn reselecting_is_a_no_op() {
        let mut bar = TabBar::new(tabs(2));
        assert!(!bar.select(0));
        assert!(!bar.select(9));
    }
}
