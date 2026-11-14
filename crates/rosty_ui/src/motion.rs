//! Frame driving and reusable spring groups for app screens.
//!
//! One [`Motion`] per window ticks every spring with the same `dt` and keeps
//! animation frames coming only while something moves. Under the system
//! reduced-motion setting every spring snaps to its target instead.

use std::{collections::HashMap, time::Duration};

use crate::perf::SlowFrames;
use gpui::{SharedString, Window};
use liquid_glass_kit::{
    motion_tokens,
    spring::{FrameClock, Spring},
};

#[derive(Default)]
pub struct Motion {
    clock: FrameClock,
    slow: SlowFrames,
    /// The newest frame asked for another animation frame.
    requested: bool,
    /// Real time between the last two animation frames (zero for the first
    /// frame of a burst).
    interval: Duration,
}

impl Motion {
    /// Whether the newest animation frame took more than twice the frame budget.
    pub fn last_frame_over_budget(&self) -> bool {
        self.slow.last_over()
    }

    /// Whether the newest [`Motion::frame`] kept the animation going (it
    /// requested another frame). `false` means the screen went still.
    pub fn requested(&self) -> bool {
        self.requested
    }

    /// Real time between the last two animation frames; zero on the first
    /// frame of a burst. For the perf recorder.
    pub fn interval(&self) -> Duration {
        self.interval
    }

    /// The display period measured so far, in milliseconds.
    pub fn budget_ms(&self) -> f32 {
        self.slow.budget_ms()
    }

    /// Call once at the top of `render`. `tick` advances every spring with
    /// the frame's `dt` and returns whether any is still moving. Under reduced
    /// motion the closure runs repeatedly until everything settles, so every
    /// spring lands within the frame.
    pub fn frame(&mut self, window: &mut Window, mut tick: impl FnMut(Duration) -> bool) {
        self.frame_timed(window, |dt, _| tick(dt));
    }

    /// Like [`Motion::frame`], and the closure also gets the real time since
    /// the previous frame, which keeps counting under reduced motion. Timed
    /// things that must still play there (the 120 ms cross-fade) use it.
    pub fn frame_timed(
        &mut self,
        window: &mut Window,
        mut tick: impl FnMut(Duration, Duration) -> bool,
    ) {
        let real = self.clock.tick();
        self.interval = real;
        // Observe only: a streak of slow frames is logged, never acted on (AD-21).
        if let Some(ms) = self.slow.observe(real, std::time::Instant::now()) {
            self.slow.report(ms);
        }
        let moving = if window.prefers_reduced_motion() {
            // One spring-safe step per pass (springs clamp `dt` at 1/15 s).
            let step = Duration::from_millis(66);
            let mut moving = tick(step, real);
            let mut passes = 1;
            while moving && passes < SETTLE_PASSES {
                moving = tick(step, Duration::ZERO);
                passes += 1;
            }
            moving
        } else {
            tick(real, real)
        };
        self.requested = moving;
        if moving {
            window.request_animation_frame();
        } else {
            self.clock.reset();
            self.slow.idle();
        }
    }
}

/// Passes of the settle loop under reduced motion (enough for any spring).
const SETTLE_PASSES: usize = 24;

/// Press progress (0 = up, 1 = held) for any number of controls, keyed by id.
#[derive(Default)]
pub struct Presses {
    springs: HashMap<SharedString, Spring>,
    /// A real touch screen is in use: finger-down arrives via
    /// [`Presses::touch_down`], so the mouse-down that follows a tap on lift
    /// must not animate a second press.
    touch_seen: bool,
}

impl Presses {
    /// Mouse or keyboard press. Ignored once touch input has been seen, since
    /// touch screens report the press through [`Presses::touch_down`].
    pub fn down(&mut self, id: impl Into<SharedString>) {
        if !self.touch_seen {
            self.hold(id.into());
        }
    }

    /// The finger landed on the control (see [`crate::touch::touch_surface`]).
    pub fn touch_down(&mut self, id: impl Into<SharedString>) {
        self.touch_seen = true;
        self.hold(id.into());
    }

    /// Whether a touch screen has been used. Controls that act on finger-down
    /// ignore the synthesised mouse-down that follows a tap on lift.
    pub fn touch_seen(&self) -> bool {
        self.touch_seen
    }

    fn hold(&mut self, id: SharedString) {
        self.springs
            .entry(id)
            .or_insert_with(|| Spring::new(0.0, motion_tokens::press()).with_epsilon(0.002))
            .retarget(1.0, motion_tokens::press());
    }

    /// Releases every held control (call from the root's mouse-up handler, so
    /// a finger that slides off a key still lets go).
    pub fn release_all(&mut self) {
        for spring in self.springs.values_mut() {
            spring.retarget(0.0, motion_tokens::release());
        }
    }

    pub fn value(&self, id: &str) -> f32 {
        self.springs.get(id).map_or(0.0, |s| s.value)
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        // A spring that came to rest up (target 0) is forgotten: its value
        // reads 0 either way, and the map stops growing with every row the
        // owner ever tapped. Springs held down (target 1) stay.
        self.springs.retain(|_, spring| {
            let still = spring.tick(dt);
            moving |= still;
            still || spring.target != 0.0
        });
        moving
    }

    /// Springs currently tracked (for tests).
    pub fn tracked(&self) -> usize {
        self.springs.len()
    }
}

/// Scale for a pressed control: `1.0` at rest, `rest_scale` fully held.
/// Press springs overshoot a little, so this dips slightly past `rest_scale`.
pub fn press_scale(press: f32, rest_scale: f32) -> f32 {
    1.0 - (1.0 - rest_scale) * press
}

/// A screen's entrance: 0 → 1 with [`motion_tokens::appear`]. Restart it when
/// the content changes (tab switch) to fade and lift the new page in.
pub struct Entrance(Spring);

impl Default for Entrance {
    fn default() -> Self {
        Self(Spring::new(1.0, motion_tokens::appear()).with_epsilon(0.002))
    }
}

impl Entrance {
    pub fn restart(&mut self) {
        self.0.snap(0.0);
        self.0.set_target(1.0);
    }

    /// Starts again without a jump: a page that is mid-entrance keeps its
    /// progress (a rapid second tap retargets instead of replaying).
    pub fn restart_soft(&mut self) {
        if self.0.value >= 0.98 {
            self.0.snap(0.0);
        }
        self.0.set_target(1.0);
    }

    pub fn progress(&self) -> f32 {
        self.0.value.clamp(0.0, 1.0)
    }

    /// Vertical offset in px: starts `lift` below and settles at 0.
    pub fn offset(&self, lift: f32) -> f32 {
        (1.0 - self.0.value) * lift
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        self.0.tick(dt)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settle(presses: &mut Presses) {
        for _ in 0..240 {
            presses.tick(Duration::from_secs_f32(1.0 / 120.0));
        }
    }

    #[test]
    fn press_and_release() {
        let mut presses = Presses::default();
        presses.down("5");
        settle(&mut presses);
        assert!((presses.value("5") - 1.0).abs() < 0.01);
        presses.release_all();
        settle(&mut presses);
        assert!(presses.value("5").abs() < 0.01);
        assert_eq!(presses.value("unknown"), 0.0);
    }

    #[test]
    fn released_springs_are_forgotten_held_ones_stay() {
        let mut presses = Presses::default();
        presses.down("a");
        presses.down("b");
        settle(&mut presses);
        assert_eq!(presses.tracked(), 2, "held springs stay");
        presses.release_all();
        settle(&mut presses);
        assert_eq!(presses.tracked(), 0, "settled at rest: dropped");
        assert_eq!(presses.value("a"), 0.0);
        // Pressing again works as the first time.
        presses.down("a");
        settle(&mut presses);
        assert!((presses.value("a") - 1.0).abs() < 0.01);
    }

    #[test]
    fn entrance_runs_from_zero_to_one() {
        let mut entrance = Entrance::default();
        assert_eq!(entrance.progress(), 1.0);
        entrance.restart();
        assert_eq!(entrance.progress(), 0.0);
        assert!(entrance.tick(Duration::from_millis(16)));
        for _ in 0..240 {
            entrance.tick(Duration::from_secs_f32(1.0 / 120.0));
        }
        assert_eq!(entrance.progress(), 1.0);
        assert_eq!(entrance.offset(24.0), 0.0);
    }

    #[test]
    fn press_scale_bounds() {
        assert_eq!(press_scale(0.0, 0.94), 1.0);
        assert!((press_scale(1.0, 0.94) - 0.94).abs() < 1e-6);
    }
}
