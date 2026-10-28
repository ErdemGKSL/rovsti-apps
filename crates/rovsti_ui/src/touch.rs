//! Raw touch tracking for press feedback.
//!
//! On Android the host sends a `Touch` event when the finger lands and only
//! synthesises `MouseDown` + `MouseUp` (back to back) once it lifts. Press
//! animations driven from `on_mouse_down` therefore never see the finger
//! being held. [`touch_surface`] reports the real finger-down and finger-up so
//! controls can animate while held; the mouse path still performs the action.

use std::{cell::Cell, rc::Rc};

use gpui::{
    App, Bounds, IntoElement, MouseButton, MouseDownEvent, Pixels, Point, Styled, TouchEvent,
    TouchPhase, Window, canvas,
};

/// An invisible layer covering its parent. `on_touch` receives
/// [`TouchPhase::Started`] when a finger lands inside the parent's bounds, and
/// [`TouchPhase::Ended`] / [`TouchPhase::Cancelled`] when any finger lifts, so
/// a finger that slides off the control still releases it. Moves are ignored.
///
/// The parent must be a positioned box (it is for any flex `div`).
pub fn touch_surface(
    on_touch: impl Fn(TouchPhase, &mut Window, &mut App) + 'static,
) -> impl IntoElement {
    canvas(
        |_, _, _| (),
        move |bounds, (), window, _| {
            window.on_mouse_event(move |event: &TouchEvent, phase, window, cx| {
                if !phase.bubble() {
                    return;
                }
                match event.phase {
                    TouchPhase::Started if !bounds.contains(&event.position) => {}
                    TouchPhase::Moved => {}
                    other => on_touch(other, window, cx),
                }
            });
        },
    )
    .absolute()
    .size_full()
}

/// A drag surface: an invisible layer over its parent that reports a finger's
/// whole path. [`TouchPhase::Started`] only when the finger lands inside the
/// parent; `Moved`, `Ended` and `Cancelled` wherever they happen, each with the
/// window position, so a drag that leaves the surface is still followed. The
/// owner decides which finger it follows (it keeps the first `Started`).
///
/// The parent must be a positioned box.
pub fn drag_surface(
    on_touch: impl Fn(TouchPhase, Point<Pixels>, &mut Window, &mut App) + 'static,
) -> impl IntoElement {
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
                on_touch(event.phase, event.position, window, cx);
            });
        },
    )
    .absolute()
    .size_full()
}

/// What a [`pointer_surface`] saw.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Pointer {
    /// A raw touch. `Started` only when the finger landed inside the surface;
    /// `Moved`, `Ended` and `Cancelled` are reported wherever they happen.
    Touch(TouchPhase),
    /// A left mouse press inside the surface: on Android the synthesised tap
    /// that follows a finger lifting, on desktop the click itself.
    Click,
}

/// One invisible layer over a whole list that reports every touch and click
/// with its window position, so rows need no handler of their own (a list of
/// 500 rows would otherwise register hundreds of closures per frame). The
/// surface's last painted bounds are written to `bounds_out` so the caller can
/// turn a window position into a row.
pub fn pointer_surface(
    bounds_out: Rc<Cell<Bounds<Pixels>>>,
    on_event: impl Fn(Pointer, Point<Pixels>, &mut Window, &mut App) + 'static,
) -> impl IntoElement {
    let on_event = Rc::new(on_event);
    canvas(
        |_, _, _| (),
        move |bounds, (), window, _| {
            bounds_out.set(bounds);
            let touch = Rc::clone(&on_event);
            window.on_mouse_event(move |event: &TouchEvent, phase, window, cx| {
                if !phase.bubble() {
                    return;
                }
                if event.phase == TouchPhase::Started && !bounds.contains(&event.position) {
                    return;
                }
                touch(Pointer::Touch(event.phase), event.position, window, cx);
            });
            let click = Rc::clone(&on_event);
            window.on_mouse_event(move |event: &MouseDownEvent, phase, window, cx| {
                if phase.bubble()
                    && event.button == MouseButton::Left
                    && bounds.contains(&event.position)
                {
                    click(Pointer::Click, event.position, window, cx);
                }
            });
        },
    )
    .absolute()
    .top_0()
    .left_0()
    .size_full()
}

/// How far a finger may wander (px) and still count as a tap.
pub const TAP_SLOP: f32 = 10.0;

/// Decides whether the synthesised `Click` that follows a finger lifting is a
/// real tap. A finger that moved past [`TAP_SLOP`], or a list that scrolled
/// under it, is a scroll or flick and must never act, whatever the host does
/// with the touch afterwards. With no touch at all (a desktop mouse) every
/// click is allowed.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct TapGuard {
    start: Option<(f32, f32, f32)>,
    spoiled: bool,
}

impl TapGuard {
    /// A finger landed at `(x, y)` over a list scrolled by `scroll`.
    pub fn begin(&mut self, x: f32, y: f32, scroll: f32) {
        self.start = Some((x, y, scroll));
        self.spoiled = false;
    }

    /// The finger is now at `(x, y)`; true once it has gone beyond the slop.
    pub fn moved(&mut self, x: f32, y: f32) -> bool {
        if let Some((sx, sy, _)) = self.start
            && (x - sx).hypot(y - sy) > TAP_SLOP
        {
            self.spoiled = true;
        }
        self.spoiled
    }

    /// The finger lifted (or the touch was cancelled) at `(x, y)`.
    pub fn finish(&mut self, x: f32, y: f32) {
        // `start` stays until the click is judged: it holds the scroll offset.
        self.moved(x, y);
    }

    /// A press that has become something else (a long press) is not a tap.
    pub fn spoil(&mut self) {
        self.spoiled = true;
    }

    /// Consume the click: true when it may act. `scroll` is the list's
    /// current scroll offset.
    pub fn allow_click(&mut self, scroll: f32) -> bool {
        let scrolled = self
            .start
            .is_some_and(|(_, _, at)| (scroll - at).abs() > 0.5);
        let ok = !self.spoiled && !scrolled;
        *self = Self::default();
        ok
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_tap_is_allowed() {
        let mut g = TapGuard::default();
        g.begin(100.0, 200.0, 40.0);
        g.moved(102.0, 203.0);
        g.finish(102.0, 203.0);
        assert!(g.allow_click(40.0));
    }

    #[test]
    fn mouse_click_without_touch_is_allowed() {
        assert!(TapGuard::default().allow_click(0.0));
    }

    #[test]
    fn flick_beyond_slop_never_clicks() {
        let mut g = TapGuard::default();
        g.begin(100.0, 400.0, 0.0);
        assert!(g.moved(100.0, 360.0));
        // The host lifts and then synthesises the click.
        g.finish(100.0, 300.0);
        assert!(!g.allow_click(0.0));
        // The next tap is fine again.
        g.begin(100.0, 400.0, 0.0);
        g.finish(100.0, 400.0);
        assert!(g.allow_click(0.0));
    }

    #[test]
    fn lift_far_from_start_without_moves_never_clicks() {
        let mut g = TapGuard::default();
        g.begin(10.0, 10.0, 0.0);
        g.finish(10.0, 80.0);
        assert!(!g.allow_click(0.0));
    }

    #[test]
    fn scrolled_list_never_clicks() {
        let mut g = TapGuard::default();
        g.begin(10.0, 10.0, 0.0);
        g.finish(10.0, 10.0);
        assert!(!g.allow_click(120.0));
    }

    #[test]
    fn long_press_is_not_a_tap() {
        let mut g = TapGuard::default();
        g.begin(10.0, 10.0, 0.0);
        g.spoil();
        g.finish(10.0, 10.0);
        assert!(!g.allow_click(0.0));
    }
}
