//! The Notice pill (S28): one tidy transient message surface for confirmations,
//! reasons and Undo, shared by every Rovsti app.
//!
//! A 48 px capsule, 16 px from both sides, one line of text and an optional
//! trailing accent button. A 2 px hairline along its bottom edge shrinks over
//! the pill's life (info 2 s, Undo 5 s, 10 s with TalkBack). It is an overlay:
//! the owner gives its `bottom` offset (12 px above the Tab Bar, or above the
//! End button) and nothing else moves. One pill at a time; a new one replaces
//! the old in place, and the owner commits (does not run) the old one's Undo.
//!
//! Frames are requested only while the pill is showing or leaving ([`tick`]
//! returns `false` once it is gone). A finger resting on the pill holds its
//! timer. The Undo button acts on finger-up through the [`TapGuard`].
//!
//! [`tick`]: NoticePill::tick

use std::{cell::Cell, rc::Rc, time::Duration};

use gpui::{
    AnyElement, App, Bounds, InteractiveElement, IntoElement, ParentElement, Pixels, Point, Role,
    SharedString, StatefulInteractiveElement, Styled, TouchPhase, div, px,
};
use liquid_glass_kit::{
    glass::{GlassSpec, glass},
    motion_tokens,
    spring::Spring,
    tokens::{Theme, shadow, typography},
};

use crate::{
    theme,
    touch::{Pointer, TapGuard, pointer_surface},
};

/// Height of the capsule.
pub const HEIGHT: f32 = 48.0;
/// Side inset.
pub const INSET: f32 = 16.0;
/// How far the pill rises while it appears.
const RISE: f32 = 16.0;
/// Width of the trailing button's touch target.
const ACTION_W: f32 = 96.0;
/// Lifetimes in seconds.
pub const INFO_SECS: f32 = 2.0;
pub const UNDO_SECS: f32 = 5.0;
pub const TALKBACK_SECS: f32 = 10.0;

/// What a touch on the pill meant.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PillEvent {
    None,
    /// The trailing button (Undo, Fix, ...) was tapped.
    Action,
}

pub struct NoticePill {
    text: SharedString,
    action: Option<SharedString>,
    wants: bool,
    life: f32,
    elapsed: f32,
    held: bool,
    reduced: bool,
    token: u64,
    /// 0 = gone, 1 = in place.
    appear: Spring,
    bounds: Rc<Cell<Bounds<Pixels>>>,
    tap: TapGuard,
    action_down: bool,
    /// A touch screen is in use: the click that follows a lift adds nothing.
    touch_seen: bool,
}

impl Default for NoticePill {
    fn default() -> Self {
        Self::new()
    }
}

impl NoticePill {
    pub fn new() -> Self {
        Self {
            text: SharedString::default(),
            action: None,
            wants: false,
            life: INFO_SECS,
            elapsed: 0.0,
            held: false,
            reduced: false,
            token: 0,
            appear: Spring::new(0.0, motion_tokens::appear()).with_epsilon(0.002),
            bounds: Rc::default(),
            tap: TapGuard::default(),
            action_down: false,
            touch_seen: false,
        }
    }

    /// Shows `text`, replacing any pill in place. `undo` selects the 5 s life;
    /// `screen_reader` the 10 s one. Returns the new pill's token: the owner
    /// compares it to know whether the pill it showed is still the one up.
    pub fn show(
        &mut self,
        text: impl Into<SharedString>,
        action: Option<SharedString>,
        undo: bool,
        screen_reader: bool,
    ) -> u64 {
        self.text = text.into();
        self.action = action;
        self.life = if screen_reader {
            TALKBACK_SECS
        } else if undo {
            UNDO_SECS
        } else {
            INFO_SECS
        };
        self.elapsed = 0.0;
        self.held = false;
        self.action_down = false;
        self.token += 1;
        if !self.wants && self.appear.value < 0.5 {
            self.appear.snap(0.0);
        }
        self.wants = true;
        self.appear.retarget(1.0, motion_tokens::appear());
        self.token
    }

    /// Leaves now (on `sheet_dismiss`).
    pub fn dismiss(&mut self) {
        if self.wants {
            self.wants = false;
            self.appear.retarget(0.0, motion_tokens::sheet_dismiss());
        }
    }

    /// The system asked for reduced motion: a plain fade, no hairline.
    pub fn set_reduced(&mut self, reduced: bool) {
        self.reduced = reduced;
    }

    pub fn is_visible(&self) -> bool {
        self.wants || self.appear.value > 0.002 || !self.appear.is_settled()
    }

    pub fn token(&self) -> u64 {
        self.token
    }

    pub fn text(&self) -> &SharedString {
        &self.text
    }

    /// Advances the pill; `true` while a frame is needed.
    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.appear.tick(dt);
        if self.wants {
            if !self.held {
                self.elapsed += dt.as_secs_f32();
                if self.elapsed >= self.life {
                    self.dismiss();
                }
            }
            // The hairline (and the countdown) need a frame each tick.
            moving = true;
        }
        moving
    }

    /// Fraction of the life left (1 = full), for the hairline.
    fn remaining(&self) -> f32 {
        (1.0 - self.elapsed / self.life).clamp(0.0, 1.0)
    }

    /// Rewinds the hairline (an Undo was tapped).
    fn rewind(&mut self) {
        self.elapsed = 0.0;
    }

    fn action_hit(&self, position: Point<Pixels>) -> bool {
        let b = self.bounds.get();
        self.action.is_some()
            && b.contains(&position)
            && f32::from(position.x) >= f32::from(b.right()) - ACTION_W
    }

    /// Feeds a touch or click from the pill's [`pointer_surface`].
    pub fn pointer(&mut self, pointer: Pointer, position: Point<Pixels>) -> PillEvent {
        match pointer {
            Pointer::Touch(TouchPhase::Started) => {
                self.touch_seen = true;
                self.held = true;
                self.tap
                    .begin(f32::from(position.x), f32::from(position.y), 0.0);
                self.action_down = self.action_hit(position);
                PillEvent::None
            }
            Pointer::Touch(TouchPhase::Moved) => {
                self.tap.moved(f32::from(position.x), f32::from(position.y));
                PillEvent::None
            }
            Pointer::Touch(_) => {
                self.held = false;
                self.tap
                    .finish(f32::from(position.x), f32::from(position.y));
                let down = std::mem::take(&mut self.action_down);
                // Undo acts on finger-up, on the same target the finger landed on.
                if down && self.tap.allow_click(0.0) && self.action_hit(position) {
                    self.rewind();
                    return PillEvent::Action;
                }
                PillEvent::None
            }
            // The synthesised click after a lift: the touch already decided.
            Pointer::Click => {
                if self.touch_seen || !self.action_hit(position) {
                    return PillEvent::None;
                }
                self.rewind();
                PillEvent::Action
            }
        }
    }

    /// The pill, or `None` once it is gone. `bottom` is the gap between the
    /// pill and the bottom of the window; `on_pointer` forwards touches to
    /// [`NoticePill::pointer`].
    pub fn render(
        &self,
        theme: Theme,
        bottom: f32,
        on_pointer: impl Fn(Pointer, Point<Pixels>, &mut gpui::Window, &mut App) + 'static,
    ) -> Option<AnyElement> {
        if !self.is_visible() {
            return None;
        }
        let colors = theme::colors(theme);
        let t = self.appear.value.clamp(0.0, 1.0);
        let lift = if self.reduced { 0.0 } else { (1.0 - t) * RISE };
        let mut capsule = glass(GlassSpec::chrome(theme))
            .relative()
            .h(px(HEIGHT))
            .w_full()
            .rounded_full()
            .overflow_hidden()
            .shadow(shadow::glass(theme.is_dark()))
            .flex()
            .items_center()
            .px(px(18.0))
            .gap(px(8.0))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .truncate()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.label)
                    .child(self.text.clone()),
            );
        if let Some(action) = &self.action {
            capsule = capsule.child(
                div()
                    .h(px(44.0))
                    .flex_none()
                    .flex()
                    .items_center()
                    .justify_end()
                    .text_size(typography::SUBHEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.accent)
                    .child(action.clone()),
            );
        }
        if !self.reduced {
            capsule = capsule.child(
                div()
                    .absolute()
                    .bottom_0()
                    .left_0()
                    .h(px(2.0))
                    .w(gpui::relative(self.remaining()))
                    .bg(gpui::Hsla {
                        a: 0.6,
                        ..colors.accent
                    }),
            );
        }
        capsule = capsule.child(pointer_surface(Rc::clone(&self.bounds), on_pointer));
        Some(
            div()
                .id("notice-pill")
                .role(Role::Status)
                .aria_label(self.text.clone())
                .absolute()
                .left(px(INSET))
                .right(px(INSET))
                .bottom(px(bottom - lift))
                .h(px(HEIGHT))
                // A tap on the pill (its Undo) must not also reach what is under it.
                .occlude()
                .opacity(t)
                .child(capsule)
                .into_any_element(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ms(n: u64) -> Duration {
        Duration::from_millis(n)
    }

    #[test]
    fn an_info_pill_lives_two_seconds_then_stops_requesting_frames() {
        let mut pill = NoticePill::new();
        assert!(!pill.tick(ms(16)), "an idle pill needs no frames");
        pill.show("Copied", None, false, false);
        assert!(pill.is_visible());
        for _ in 0..140 {
            assert!(pill.tick(ms(16)));
        }
        // 2.2 s passed: dismissed, leaving on its spring, then gone.
        assert!(!pill.wants);
        for _ in 0..200 {
            pill.tick(ms(16));
        }
        assert!(!pill.is_visible());
        assert!(!pill.tick(ms(16)), "no frames after the pill is gone");
    }

    #[test]
    fn undo_and_screen_reader_lives() {
        let mut pill = NoticePill::new();
        pill.show("Deleted", Some("Undo".into()), true, false);
        assert_eq!(pill.life, UNDO_SECS);
        pill.show("Deleted", Some("Undo".into()), true, true);
        assert_eq!(pill.life, TALKBACK_SECS);
    }

    #[test]
    fn a_finger_on_the_pill_holds_the_timer() {
        let mut pill = NoticePill::new();
        pill.show("Hello", None, false, false);
        pill.held = true;
        for _ in 0..400 {
            pill.tick(ms(16));
        }
        assert!(pill.wants, "held pills stay");
        assert!(pill.elapsed < 0.01);
    }

    #[test]
    fn a_new_pill_replaces_the_old_one_in_place() {
        let mut pill = NoticePill::new();
        let first = pill.show("One", Some("Undo".into()), true, false);
        pill.tick(ms(1000));
        let second = pill.show("Two", None, false, false);
        assert_ne!(first, second);
        assert_eq!(pill.text().as_ref(), "Two");
        assert_eq!(pill.elapsed, 0.0);
        assert!(pill.action.is_none());
    }

    #[test]
    fn the_hairline_shrinks_and_rewinds() {
        let mut pill = NoticePill::new();
        pill.show("x", Some("Undo".into()), true, false);
        pill.tick(ms(2500));
        assert!((pill.remaining() - 0.5).abs() < 0.02);
        pill.rewind();
        assert_eq!(pill.remaining(), 1.0);
    }

    fn at(x: f32, y: f32) -> Point<Pixels> {
        gpui::point(px(x), px(y))
    }

    fn with_bounds(pill: &NoticePill) {
        pill.bounds.set(Bounds::new(
            at(16.0, 700.0),
            gpui::size(px(370.0), px(48.0)),
        ));
    }

    #[test]
    fn undo_acts_on_finger_up_once() {
        let mut pill = NoticePill::new();
        pill.show("Deleted", Some("Undo".into()), true, false);
        with_bounds(&pill);
        let down = pill.pointer(Pointer::Touch(TouchPhase::Started), at(350.0, 720.0));
        assert_eq!(down, PillEvent::None, "nothing happens on finger-down");
        assert!(pill.held);
        let up = pill.pointer(Pointer::Touch(TouchPhase::Ended), at(351.0, 721.0));
        assert_eq!(up, PillEvent::Action);
        assert!(!pill.held);
        // The click the host synthesises after the lift must not fire again.
        assert_eq!(
            pill.pointer(Pointer::Click, at(351.0, 721.0)),
            PillEvent::None
        );
    }

    #[test]
    fn sliding_off_or_landing_elsewhere_does_nothing() {
        let mut pill = NoticePill::new();
        pill.show("Deleted", Some("Undo".into()), true, false);
        with_bounds(&pill);
        pill.pointer(Pointer::Touch(TouchPhase::Started), at(350.0, 720.0));
        assert_eq!(
            pill.pointer(Pointer::Touch(TouchPhase::Ended), at(100.0, 720.0)),
            PillEvent::None
        );
        pill.pointer(Pointer::Touch(TouchPhase::Started), at(60.0, 720.0));
        assert_eq!(
            pill.pointer(Pointer::Touch(TouchPhase::Ended), at(60.0, 720.0)),
            PillEvent::None
        );
    }

    #[test]
    fn a_desktop_click_acts_once() {
        let mut pill = NoticePill::new();
        pill.show("Deleted", Some("Undo".into()), true, false);
        with_bounds(&pill);
        assert_eq!(
            pill.pointer(Pointer::Click, at(350.0, 720.0)),
            PillEvent::Action
        );
        assert_eq!(
            pill.pointer(Pointer::Click, at(60.0, 720.0)),
            PillEvent::None
        );
    }
}
