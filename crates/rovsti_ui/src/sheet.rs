//! A floating glass bottom sheet.
//!
//! The owner keeps a [`Sheet`] in its view, ticks it from
//! [`crate::motion::Motion::frame`], and places [`Sheet::render`] as the last
//! child of a `relative()` root, above the tab bar. Opening never changes the
//! layout underneath: the sheet is an overlay.
//!
//! Motion (S16): the panel rises on `appear` (critically damped, k 450) while a
//! scrim dims the page to 0.85; the contents settle in on a clock that starts
//! at `open()`: the avatar scales 0.8 to 1.0 after 60 ms and each row fades up
//! 8 px, staggered 30 ms. Under reduced motion the panel stays put and
//! everything fades in over 120 ms of real time.
//!
//! In detail: a spring slides the panel up (a touch of overshoot) while a scrim
//! fades in; dragging the grabber follows the finger (rubber-banding upwards),
//! and a fling or a long drag dismisses with the release velocity carried
//! into the spring. The blur is never animated (it is the costly part), only
//! the panel's position and the scrim's alpha move.
//!
//! Back: the owner calls `window.set_back_enabled(sheet.is_open())` and
//! closes the sheet from `Window::on_system_back`.

use std::time::{Duration, Instant};

use crate::moments::Fade;

use gpui::{
    AnyElement, App, Div, InteractiveElement, IntoElement, MouseButton, ParentElement, Role, Size,
    StatefulInteractiveElement, Styled, TouchEvent, TouchPhase, Window, black, canvas, div, px,
};
use liquid_glass_kit::{
    glass::{GlassSpec, glass},
    motion_tokens::{self, gesture},
    spring::Spring,
    tokens::{Theme, radius, shadow},
};

/// Gap between the panel and the screen edges.
const MARGIN: f32 = 10.0;
/// Space kept free above a full-height panel (status bar and a peek of the page).
const TOP_GAP: f32 = 56.0;
const GRABBER_ZONE: f32 = 30.0;
/// Release velocity (px/s) past which a downward flick dismisses.
const DISMISS_VELOCITY: f32 = 700.0;
/// A finger that rested this long before lifting is not flicking.
const STALE_SAMPLE: Duration = Duration::from_millis(80);
/// Fraction of the travel past which a slow drag dismisses.
const DISMISS_DISTANCE: f32 = 0.14;
/// Scrim alpha when fully open: black at 0.15 leaves the page at 0.85 (S16).
const SCRIM_ALPHA: f32 = 0.15;
/// Content timing after `open()` (S16).
const AVATAR_DELAY: f32 = 0.06;
const ROW_STAGGER: f32 = 0.03;
const ROW_FIRST: f32 = 0.05;
const SETTLE: f32 = 0.24;
/// Rows lift this far while they fade in.
const ROW_LIFT: f32 = 8.0;

pub struct Sheet {
    /// 0 = hidden, 1 = open (overshoots a little).
    open: Spring,
    /// Extra downward offset in px while dragging; springs back on release.
    drag: Spring,
    wants_open: bool,
    dragging: bool,
    /// `finger y - drag offset` at the last sample, so the panel does not jump.
    grab: f32,
    last_sample: Option<(f32, Instant)>,
    velocity: f32,
    /// Distance in px the panel travels between hidden and open.
    travel: f32,
    /// Seconds since `open()`; drives the content settling in.
    since_open: f32,
    /// Reduced motion: the 120 ms fade that replaces the rise.
    reduced: bool,
    fade: Fade,
}

impl Default for Sheet {
    fn default() -> Self {
        Self::new()
    }
}

impl Sheet {
    pub fn new() -> Self {
        Self {
            open: Spring::new(0.0, motion_tokens::sheet_snap()).with_epsilon(0.001),
            drag: Spring::new(0.0, motion_tokens::sheet_snap()).with_epsilon(0.3),
            wants_open: false,
            dragging: false,
            grab: 0.0,
            last_sample: None,
            velocity: 0.0,
            travel: 800.0,
            since_open: 1.0,
            reduced: false,
            fade: Fade::default(),
        }
    }

    /// The system asked for reduced motion (read each frame by the owner).
    pub fn set_reduced(&mut self, reduced: bool) {
        self.reduced = reduced;
    }

    /// Call each render with the window's viewport so the slide covers the
    /// whole screen whatever the content height.
    pub fn set_viewport(&mut self, viewport: Size<gpui::Pixels>) {
        self.travel = f32::from(viewport.height).max(1.0);
    }

    pub fn open(&mut self) {
        self.wants_open = true;
        self.dragging = false;
        self.drag.snap(0.0);
        self.since_open = 0.0;
        if self.reduced {
            self.fade.restart();
        }
        self.open.retarget(1.0, motion_tokens::appear());
    }

    pub fn close(&mut self) {
        self.wants_open = false;
        self.dragging = false;
        self.open.retarget(0.0, motion_tokens::sheet_dismiss());
    }

    pub fn toggle(&mut self) {
        if self.wants_open {
            self.close();
        } else {
            self.open();
        }
    }

    /// Whether the sheet is (or is animating towards) open. Drives Back.
    pub fn is_open(&self) -> bool {
        self.wants_open
    }

    /// Whether anything is on screen: open, or still sliding away.
    pub fn is_visible(&self) -> bool {
        self.wants_open || self.open.value > 0.002 || !self.open.is_settled()
    }

    /// 0..1 open progress for staggering content.
    pub fn progress(&self) -> f32 {
        self.open.value.clamp(0.0, 1.0)
    }

    /// Opacity of the reduced-motion fade (1 at rest).
    pub fn fade_value(&self) -> f32 {
        self.fade.value()
    }

    /// Advances the 120 ms reduced-motion fade by real time.
    pub fn tick_real(&mut self, real: Duration) -> bool {
        self.fade.tick(real)
    }

    /// Fade and lift of the `index`th row of content: `(opacity, y offset in px)`.
    pub fn rise(&self, index: usize) -> (f32, f32) {
        if self.reduced {
            return (self.fade.value(), 0.0);
        }
        rise_at(self.since_open, index)
    }

    /// Scale of the sheet's avatar: 0.8 to 1.0, starting 60 ms in, with a
    /// touch of overshoot (`release`).
    pub fn avatar_scale(&self) -> f32 {
        if self.reduced {
            return 1.0;
        }
        let t = ((self.since_open - AVATAR_DELAY) / SETTLE).clamp(0.0, 1.0);
        0.8 + 0.2 * ease_out_back(t)
    }

    /// Advances the springs; `true` while anything moves.
    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.open.tick(dt);
        if self.wants_open && self.since_open < 1.0 {
            self.since_open += dt.as_secs_f32();
            moving = true;
        }
        if !self.dragging {
            moving |= self.drag.tick(dt);
        }
        moving || self.dragging
    }

    /// Raw touch on the grabber zone (see [`Sheet::render`]'s `on_drag`).
    /// `y` is the finger's window y.
    pub fn drag_touch(&mut self, phase: TouchPhase, y: f32) {
        self.drag_touch_at(phase, y, Instant::now());
    }

    fn drag_touch_at(&mut self, phase: TouchPhase, y: f32, now: Instant) {
        match phase {
            TouchPhase::Started => {
                if !self.wants_open {
                    return;
                }
                self.dragging = true;
                self.grab = y - self.drag.value;
                self.velocity = 0.0;
                self.last_sample = Some((y, now));
                self.drag.snap(self.drag.value);
            }
            TouchPhase::Moved if self.dragging => {
                let raw = y - self.grab;
                // Downward follows the finger; upward resists.
                let offset = if raw >= 0.0 {
                    raw
                } else {
                    gesture::rubber_band(raw, 36.0)
                };
                self.drag.snap(offset);
                if let Some((last_y, at)) = self.last_sample.replace((y, now)) {
                    let elapsed = now.saturating_duration_since(at).as_secs_f32().max(0.004);
                    let instant = (y - last_y) / elapsed;
                    self.velocity = self.velocity * 0.6 + instant * 0.4;
                }
            }
            TouchPhase::Ended | TouchPhase::Cancelled if self.dragging => {
                self.dragging = false;
                // A pause before the lift leaves an old flick speed behind.
                if self
                    .last_sample
                    .is_none_or(|(_, at)| now.saturating_duration_since(at) > STALE_SAMPLE)
                {
                    self.velocity = 0.0;
                }
                self.last_sample = None;
                let projected = gesture::project(self.drag.value, self.velocity, 0.998);
                let dismiss = self.velocity > DISMISS_VELOCITY
                    || projected > self.travel * DISMISS_DISTANCE * 2.0
                    || self.drag.value > self.travel * DISMISS_DISTANCE;
                if dismiss {
                    // Hand the drag over to the open spring so the slide
                    // continues from where the finger left it.
                    self.open.value = (1.0 - self.drag.value / self.travel).clamp(0.0, 1.2);
                    self.open.velocity = -self.velocity / self.travel;
                    self.drag.snap(0.0);
                    self.close();
                } else {
                    self.drag.retarget(0.0, motion_tokens::sheet_snap());
                    self.drag.velocity = self.velocity;
                }
            }
            _ => {}
        }
    }

    /// The overlay (scrim and panel), or `None` when nothing is visible.
    ///
    /// * `label` is announced as the dialog's name.
    /// * `body` scrolls inside the panel.
    /// * `on_dismiss` runs when the scrim is tapped.
    /// * `on_drag` receives raw touches on the grabber zone as
    ///   `(phase, window y)`; forward them to [`Sheet::drag_touch`].
    pub fn render(
        &self,
        theme: Theme,
        window: &Window,
        label: &'static str,
        body: impl IntoElement,
        on_dismiss: impl Fn(&mut Window, &mut App) + 'static,
        on_drag: impl Fn(TouchPhase, f32, &mut Window, &mut App) + 'static,
    ) -> Option<AnyElement> {
        if !self.is_visible() {
            return None;
        }
        let colors = crate::theme::colors(theme);
        let viewport = window.viewport_size();
        let open = self.open.value.min(1.04);
        let still = self.reduced && self.wants_open;
        let offset = if still {
            self.drag.value.max(0.0)
        } else {
            (1.0 - open) * self.travel + self.drag.value
        };
        let shown = if still {
            self.fade.value()
        } else {
            (1.0 - offset / self.travel).clamp(0.0, 1.0)
        };
        let max_h = (f32::from(viewport.height) - TOP_GAP - MARGIN).max(200.0);

        let scrim = div()
            .id("sheet-scrim")
            .absolute()
            .inset_0()
            .occlude()
            .bg(gpui::Hsla {
                a: SCRIM_ALPHA * shown,
                ..black()
            })
            .on_mouse_down(MouseButton::Left, move |_, window, cx| {
                on_dismiss(window, cx)
            });

        let grabber = div()
            .relative()
            .h(px(GRABBER_ZONE))
            .w_full()
            .flex_none()
            .flex()
            .items_center()
            .justify_center()
            .child(drag_surface(on_drag))
            .child(
                div()
                    .w(px(38.0 + if self.dragging { 6.0 } else { 0.0 }))
                    .h(px(5.0))
                    .rounded_full()
                    .bg(colors.tertiary_label),
            );

        let panel = glass(GlassSpec::sheet(theme))
            .id("sheet-panel")
            .role(Role::Dialog)
            .aria_label(label)
            .occlude()
            .absolute()
            .left(px(MARGIN))
            .right(px(MARGIN))
            .bottom(px(MARGIN - offset))
            .max_h(px(max_h))
            .rounded(radius::SHEET)
            .shadow(shadow::glass(theme.is_dark()))
            .flex()
            .flex_col()
            .child(grabber)
            .child(
                div()
                    .id("sheet-body")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .child(body),
            );

        Some(
            div()
                .absolute()
                .inset_0()
                .child(scrim)
                .child(panel)
                .into_any_element(),
        )
    }
}

/// Fade and lift for the `index`th piece of sheet content while the sheet
/// opens: returns `(opacity, y offset in px)`.
pub fn stagger(progress: f32, index: usize) -> (f32, f32) {
    let t = ((progress - 0.12 - 0.06 * index as f32) / 0.5).clamp(0.0, 1.0);
    (t, (1.0 - t) * ROW_LIFT)
}

/// Row `index` `elapsed` seconds after the sheet started to open: waits
/// 50 ms, then 30 ms more per row, then eases in over 240 ms.
fn rise_at(elapsed: f32, index: usize) -> (f32, f32) {
    let start = ROW_FIRST + ROW_STAGGER * index as f32;
    let t = ((elapsed - start) / SETTLE).clamp(0.0, 1.0);
    let eased = 1.0 - (1.0 - t).powi(3);
    (eased, (1.0 - eased) * ROW_LIFT)
}

/// Ease-out with a small overshoot (c1 = 1.2).
fn ease_out_back(t: f32) -> f32 {
    const C1: f32 = 1.2;
    let u = t - 1.0;
    1.0 + (C1 + 1.0) * u * u * u + C1 * u * u
}

/// Invisible layer that reports raw touches as `(phase, window y)`. Started
/// is reported only inside the layer; Moved and Ended always, so a finger that
/// leaves the grabber keeps dragging.
fn drag_surface(on_drag: impl Fn(TouchPhase, f32, &mut Window, &mut App) + 'static) -> Div {
    div().absolute().size_full().child(
        canvas(
            |_, _, _| (),
            move |bounds, (), window, _| {
                window.on_mouse_event(move |event: &TouchEvent, phase, window, cx| {
                    if !phase.bubble() {
                        return;
                    }
                    if event.phase == TouchPhase::Started && !bounds.contains(&event.position) {
                        return;
                    }
                    on_drag(event.phase, f32::from(event.position.y), window, cx);
                });
            },
        )
        .absolute()
        .size_full(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(sheet: &mut Sheet, frames: usize) {
        for _ in 0..frames {
            sheet.tick(Duration::from_secs_f32(1.0 / 120.0));
        }
    }

    #[test]
    fn opens_and_settles() {
        let mut sheet = Sheet::new();
        assert!(!sheet.is_visible());
        sheet.open();
        assert!(sheet.is_open() && sheet.is_visible());
        run(&mut sheet, 240);
        assert!((sheet.progress() - 1.0).abs() < 0.01);
        assert!(!sheet.tick(Duration::from_millis(8)));
    }

    #[test]
    fn close_hides_after_the_slide() {
        let mut sheet = Sheet::new();
        sheet.open();
        run(&mut sheet, 240);
        sheet.close();
        assert!(!sheet.is_open());
        assert!(sheet.is_visible(), "still sliding away");
        run(&mut sheet, 240);
        assert!(!sheet.is_visible());
    }

    #[test]
    fn long_drag_dismisses_and_short_drag_returns() {
        let mut sheet = Sheet::new();
        sheet.set_viewport(gpui::size(px(400.0), px(800.0)));
        sheet.open();
        run(&mut sheet, 240);

        let t0 = Instant::now();
        let at = |ms| t0 + Duration::from_millis(ms);
        sheet.drag_touch_at(TouchPhase::Started, 500.0, at(0));
        sheet.drag_touch_at(TouchPhase::Moved, 505.0, at(100));
        sheet.drag_touch_at(TouchPhase::Moved, 510.0, at(200));
        sheet.drag_touch_at(TouchPhase::Ended, 510.0, at(300));
        run(&mut sheet, 240);
        assert!(sheet.is_open(), "20 px is a nudge");
        assert!(sheet.drag.value.abs() < 0.5);

        sheet.drag_touch_at(TouchPhase::Started, 500.0, at(0));
        sheet.drag_touch_at(TouchPhase::Moved, 600.0, at(300));
        sheet.drag_touch_at(TouchPhase::Moved, 700.0, at(600));
        sheet.drag_touch_at(TouchPhase::Ended, 700.0, at(900));
        assert!(!sheet.is_open(), "200 px of 800 dismisses");
        run(&mut sheet, 240);
        assert!(!sheet.is_visible());
    }

    #[test]
    fn a_pause_before_lifting_cancels_the_flick() {
        let mut sheet = Sheet::new();
        sheet.set_viewport(gpui::size(px(400.0), px(800.0)));
        sheet.open();
        run(&mut sheet, 240);
        let t0 = Instant::now();
        let at = |ms| t0 + Duration::from_millis(ms);
        // A fast flick of only 30 px, then the finger rests before lifting.
        sheet.drag_touch_at(TouchPhase::Started, 500.0, at(0));
        sheet.drag_touch_at(TouchPhase::Moved, 515.0, at(10));
        sheet.drag_touch_at(TouchPhase::Moved, 530.0, at(20));
        sheet.drag_touch_at(TouchPhase::Ended, 530.0, at(600));
        assert!(sheet.is_open(), "a rested finger is not a flick");

        // The same flick lifted at once still dismisses.
        sheet.drag_touch_at(TouchPhase::Started, 500.0, at(0));
        sheet.drag_touch_at(TouchPhase::Moved, 515.0, at(10));
        sheet.drag_touch_at(TouchPhase::Moved, 530.0, at(20));
        sheet.drag_touch_at(TouchPhase::Ended, 530.0, at(25));
        assert!(!sheet.is_open());
    }

    #[test]
    fn touches_while_closed_are_ignored() {
        let mut sheet = Sheet::new();
        sheet.drag_touch(TouchPhase::Started, 10.0);
        sheet.drag_touch(TouchPhase::Moved, 400.0);
        assert!(!sheet.dragging);
        assert_eq!(sheet.drag.value, 0.0);
    }

    #[test]
    fn rows_rise_in_order_thirty_ms_apart() {
        assert_eq!(rise_at(0.0, 0), (0.0, ROW_LIFT));
        let (a, _) = rise_at(0.10, 0);
        let (b, _) = rise_at(0.10, 1);
        assert!(a > b && b > 0.0);
        assert_eq!(rise_at(ROW_FIRST + ROW_STAGGER, 1).0, 0.0);
        assert_eq!(rise_at(1.0, 5), (1.0, 0.0));
    }

    #[test]
    fn the_avatar_grows_from_eighty_percent_after_sixty_ms() {
        let mut sheet = Sheet::new();
        sheet.open();
        assert!((sheet.avatar_scale() - 0.8).abs() < 1e-5);
        sheet.tick(Duration::from_millis(50));
        assert!(
            (sheet.avatar_scale() - 0.8).abs() < 1e-5,
            "not before 60 ms"
        );
        run(&mut sheet, 120);
        assert!((sheet.avatar_scale() - 1.0).abs() < 1e-3);
        assert!(ease_out_back(0.7) > 1.0, "a visible overshoot on the way");
    }

    #[test]
    fn reduced_motion_fades_for_120_ms_without_moving() {
        let mut sheet = Sheet::new();
        sheet.set_reduced(true);
        sheet.open();
        assert_eq!(sheet.rise(0), (0.0, 0.0));
        assert_eq!(sheet.avatar_scale(), 1.0);
        sheet.fade.restart();
        sheet.tick_real(Duration::from_millis(60));
        let half = sheet.fade_value();
        assert!((0.4..0.6).contains(&half), "{half}");
        sheet.tick_real(Duration::from_millis(80));
        assert!((sheet.fade_value() - 1.0).abs() < 1e-5);
    }

    #[test]
    fn stagger_runs_zero_to_one_in_order() {
        assert_eq!(stagger(0.0, 0), (0.0, ROW_LIFT));
        assert_eq!(stagger(1.0, 0).0, 1.0);
        assert!(stagger(0.5, 0).0 > stagger(0.5, 4).0);
    }
}
