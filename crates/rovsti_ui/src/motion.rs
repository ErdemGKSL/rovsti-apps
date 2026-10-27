//! Frame driving and reusable spring groups for app screens.
//!
//! One [`Motion`] per window ticks every spring with the same `dt` and keeps
//! animation frames coming only while something moves. Under the system
//! reduced-motion setting every spring snaps to its target instead.

use std::{collections::HashMap, time::Duration};

use gpui::{SharedString, Window};
use liquid_glass_kit::{
    motion_tokens,
    spring::{FrameClock, Spring},
};

#[derive(Default)]
pub struct Motion {
    clock: FrameClock,
}

impl Motion {
    /// Call once at the top of `render`. `tick` advances every spring with
    /// the frame's `dt` and returns whether any is still moving. Under reduced
    /// motion `dt` is a full second, which settles every spring at once.
    pub fn frame(&mut self, window: &mut Window, tick: impl FnOnce(Duration) -> bool) {
        let dt = if window.prefers_reduced_motion() {
            Duration::from_secs(1)
        } else {
            self.clock.tick()
        };
        if tick(dt) {
            window.request_animation_frame();
        } else {
            self.clock.reset();
        }
    }
}

/// Press progress (0 = up, 1 = held) for any number of controls, keyed by id.
#[derive(Default)]
pub struct Presses {
    springs: HashMap<SharedString, Spring>,
}

impl Presses {
    pub fn down(&mut self, id: impl Into<SharedString>) {
        self.springs
            .entry(id.into())
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
        for spring in self.springs.values_mut() {
            moving |= spring.tick(dt);
        }
        moving
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
