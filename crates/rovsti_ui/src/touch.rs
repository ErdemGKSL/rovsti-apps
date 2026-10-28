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
