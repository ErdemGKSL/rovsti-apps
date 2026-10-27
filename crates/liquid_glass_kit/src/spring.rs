//! Analytic damped spring, independent of frame rate and of any GPUI version.
//!
//! Works on gpui-fusion (which has no `with_spring`) and on upstream GPUI.
//! The solver is the closed-form solution of `x'' = -k(x - target) - c x'`
//! with mass 1, evaluated for the elapsed `dt` of each frame, so a 30 Hz and
//! a 120 Hz display produce the same curve and an interrupted spring keeps its
//! velocity when it is retargeted.
//!
//! Parameterisation follows SwiftUI/UIKit:
//! - `response` (seconds): period of the undamped oscillation, `2π / ω0`.
//! - `damping_fraction` (ζ): 1.0 = critically damped (no overshoot),
//!   < 1.0 = bouncy, > 1.0 = sluggish.
//!
//! Compose/Android uses `(dampingRatio, stiffness)` with mass 1:
//! `ω0 = sqrt(stiffness)`, so `response = 2π / sqrt(stiffness)`.

use std::f32::consts::TAU;
use std::time::Duration;

/// Spring parameters in canonical form.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SpringParams {
    /// Natural angular frequency ω0 in rad/s.
    pub omega: f32,
    /// Damping ratio ζ.
    pub zeta: f32,
}

impl SpringParams {
    /// SwiftUI-style `spring(response:dampingFraction:)`.
    pub const fn response(response_secs: f32, damping_fraction: f32) -> Self {
        Self {
            omega: TAU / response_secs,
            zeta: damping_fraction,
        }
    }

    /// Compose-style `spring(dampingRatio, stiffness)` with mass 1.
    pub fn stiffness(damping_ratio: f32, stiffness: f32) -> Self {
        Self {
            omega: stiffness.sqrt(),
            zeta: damping_ratio,
        }
    }

    /// Frequency in Hz plus damping ratio (gpui-fusion `ElasticOffset` style).
    pub fn frequency(hz: f32, damping_ratio: f32) -> Self {
        Self {
            omega: TAU * hz,
            zeta: damping_ratio,
        }
    }

    /// Physical `(stiffness, damping, mass)` for upstream GPUI's
    /// `SpringConfig::new(stiffness, damping, mass)`. Note that upstream's
    /// `damping` is the viscous coefficient `c = 2ζ·sqrt(k·m)`, not ζ.
    pub fn physical(self) -> (f32, f32, f32) {
        let stiffness = self.omega * self.omega;
        (stiffness, 2.0 * self.zeta * self.omega, 1.0)
    }

    /// Advances `(x, v)` toward `target` by `dt` seconds, exactly.
    pub fn step(self, x: f32, v: f32, target: f32, dt: f32) -> (f32, f32) {
        if dt <= 0.0 {
            return (x, v);
        }
        let w = self.omega;
        let z = self.zeta;
        let x0 = x - target;
        if (z - 1.0).abs() < 1e-4 {
            // Critically damped: x(t) = (x0 + (v0 + w x0) t) e^{-wt}
            let e = (-w * dt).exp();
            let b = v + w * x0;
            let nx = (x0 + b * dt) * e;
            let nv = (b - w * (x0 + b * dt)) * e;
            (target + nx, nv)
        } else if z < 1.0 {
            // Underdamped.
            let wd = w * (1.0 - z * z).sqrt();
            let e = (-z * w * dt).exp();
            let (s, c) = (wd * dt).sin_cos();
            let b = (v + z * w * x0) / wd;
            let nx = e * (x0 * c + b * s);
            let nv = e * ((b * wd - z * w * x0) * c - (x0 * wd + z * w * b) * s);
            (target + nx, nv)
        } else {
            // Overdamped: two real roots r1, r2.
            let root = w * (z * z - 1.0).sqrt();
            let r1 = -z * w + root;
            let r2 = -z * w - root;
            let c2 = (v - r1 * x0) / (r2 - r1);
            let c1 = x0 - c2;
            let (e1, e2) = ((r1 * dt).exp(), (r2 * dt).exp());
            (target + c1 * e1 + c2 * e2, c1 * r1 * e1 + c2 * r2 * e2)
        }
    }
}

/// One animated scalar. Keep it in your view/entity state, call [`Spring::tick`]
/// once per frame, and call `window.request_animation_frame()` while it
/// returns `true`.
#[derive(Clone, Copy, Debug)]
pub struct Spring {
    pub value: f32,
    pub velocity: f32,
    pub target: f32,
    pub params: SpringParams,
    /// Settle tolerance in value units (pixels for positions, ~0.001 for 0..1 progress).
    pub epsilon: f32,
}

impl Spring {
    pub fn new(value: f32, params: SpringParams) -> Self {
        Self {
            value,
            velocity: 0.0,
            target: value,
            params,
            epsilon: 0.001,
        }
    }

    pub fn with_epsilon(mut self, epsilon: f32) -> Self {
        self.epsilon = epsilon;
        self
    }

    /// Changes the destination; position and velocity are preserved.
    pub fn set_target(&mut self, target: f32) {
        self.target = target;
    }

    /// Changes the destination and the curve (e.g. press → release springs).
    pub fn retarget(&mut self, target: f32, params: SpringParams) {
        self.target = target;
        self.params = params;
    }

    /// Jumps without animation (reduced motion, first layout, drag tracking).
    pub fn snap(&mut self, value: f32) {
        self.value = value;
        self.target = value;
        self.velocity = 0.0;
    }

    /// Adds an impulse, e.g. the release velocity of a drag gesture.
    pub fn kick(&mut self, velocity: f32) {
        self.velocity = velocity;
    }

    pub fn is_settled(&self) -> bool {
        (self.value - self.target).abs() <= self.epsilon
            && self.velocity.abs() <= self.epsilon * 10.0
    }

    /// Advances by `dt`. Returns `true` while still moving.
    pub fn tick(&mut self, dt: Duration) -> bool {
        if self.is_settled() {
            self.snap(self.target);
            return false;
        }
        // Clamp huge gaps (window was hidden, debugger pause) so we do not
        // teleport, but still integrate exactly for normal frame times.
        let dt = dt.as_secs_f32().min(1.0 / 15.0);
        let (x, v) = self.params.step(self.value, self.velocity, self.target, dt);
        self.value = x;
        self.velocity = v;
        if self.is_settled() {
            self.snap(self.target);
            return false;
        }
        true
    }
}

/// A rectangle `[x, y, width, height]` that glides toward layout targets.
///
/// Used for optional animated window resize: compute the layout every frame
/// as usual, feed it to [`SpringRect::follow`], and paint at [`SpringRect::value`].
/// Each axis can be pinned (snapped) independently, e.g. pin `y` of a bar that
/// is anchored to the bottom edge so it never lags off-screen.
#[derive(Clone, Copy, Debug)]
pub struct SpringRect {
    pub axes: [Spring; 4],
    initialised: bool,
}

impl SpringRect {
    pub fn new(params: SpringParams) -> Self {
        Self {
            axes: [Spring::new(0.0, params).with_epsilon(0.1); 4],
            initialised: false,
        }
    }

    /// Retargets to `target`. The first call, or `animate == false`, snaps.
    /// `pinned[i] == true` snaps that axis even when animating.
    pub fn follow(&mut self, target: [f32; 4], animate: bool, pinned: [bool; 4]) {
        let first = !self.initialised;
        self.initialised = true;
        for ((axis, t), pin) in self.axes.iter_mut().zip(target).zip(pinned) {
            if first || !animate || pin {
                axis.snap(t);
            } else {
                axis.set_target(t);
            }
        }
    }

    pub fn snap(&mut self, target: [f32; 4]) {
        self.follow(target, false, [true; 4]);
    }

    pub fn value(&self) -> [f32; 4] {
        self.axes.map(|a| a.value)
    }

    /// Advances all four axes. Returns `true` while any of them moves.
    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        for axis in &mut self.axes {
            moving |= axis.tick(dt);
        }
        moving
    }
}

/// A fixed-duration timeline for choreographed motion: theme cross-fades,
/// release rings, multi-property morphs. Pass `now` explicitly so it is
/// testable; call `window.request_animation_frame()` while [`Timeline::running`].
#[derive(Clone, Copy, Debug)]
pub struct Timeline {
    start: Option<std::time::Instant>,
    pub duration: Duration,
}

impl Timeline {
    pub const fn new(duration: Duration) -> Self {
        Self {
            start: None,
            duration,
        }
    }

    pub fn start(&mut self, now: std::time::Instant) {
        self.start = Some(now);
    }

    /// Linear progress 0..=1; 1 when never started or finished.
    pub fn progress(&self, now: std::time::Instant) -> f32 {
        match self.start {
            None => 1.0,
            Some(start) => {
                let t = now.saturating_duration_since(start).as_secs_f32();
                (t / self.duration.as_secs_f32().max(1e-6)).clamp(0.0, 1.0)
            }
        }
    }

    pub fn running(&self, now: std::time::Instant) -> bool {
        self.start.is_some() && self.progress(now) < 1.0
    }

    /// Jump to the end (reduced motion).
    pub fn finish(&mut self) {
        self.start = None;
    }
}

/// Measures frame-to-frame `dt` for a group of springs.
#[derive(Clone, Copy, Debug, Default)]
pub struct FrameClock {
    last: Option<std::time::Instant>,
}

impl FrameClock {
    /// Returns the time since the previous call (zero on the first call).
    pub fn tick(&mut self) -> Duration {
        let now = std::time::Instant::now();
        let dt = self.last.map_or(Duration::ZERO, |last| now - last);
        self.last = Some(now);
        dt
    }

    /// Call when every spring has settled so the next animation does not see
    /// the idle gap as one giant frame.
    pub fn reset(&mut self) {
        self.last = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(mut s: Spring, secs: f32) -> (Spring, f32) {
        let mut peak = s.value;
        let frames = (secs * 120.0) as usize;
        for _ in 0..frames {
            s.tick(Duration::from_secs_f32(1.0 / 120.0));
            peak = peak.max(s.value);
        }
        (s, peak)
    }

    #[test]
    fn settles_at_target() {
        for zeta in [0.5, 0.76, 1.0, 1.4] {
            let mut s = Spring::new(0.0, SpringParams::stiffness(zeta, 750.0));
            s.set_target(100.0);
            let (s, _) = run(s, 3.0);
            assert_eq!(s.value, 100.0, "zeta {zeta}");
            assert!(s.is_settled());
        }
    }

    #[test]
    fn critical_damping_never_overshoots() {
        let mut s = Spring::new(0.0, SpringParams::response(0.35, 1.0));
        s.set_target(1.0);
        let (_, peak) = run(s, 2.0);
        assert!(peak <= 1.0 + 1e-4, "peak {peak}");
    }

    #[test]
    fn underdamped_overshoots() {
        let mut s = Spring::new(0.0, SpringParams::stiffness(0.5, 300.0));
        s.set_target(1.0);
        let (_, peak) = run(s, 2.0);
        assert!(peak > 1.05, "peak {peak}");
    }

    #[test]
    fn frame_rate_independent() {
        let p = SpringParams::stiffness(0.7, 400.0);
        let (mut a, mut b) = ((0.0, 0.0), (0.0, 0.0));
        for _ in 0..30 {
            a = p.step(a.0, a.1, 1.0, 1.0 / 30.0);
        }
        for _ in 0..120 {
            b = p.step(b.0, b.1, 1.0, 1.0 / 120.0);
        }
        assert!((a.0 - b.0).abs() < 1e-3, "{} vs {}", a.0, b.0);
    }

    #[test]
    fn retarget_preserves_velocity() {
        let mut s = Spring::new(0.0, SpringParams::stiffness(1.0, 750.0));
        s.set_target(100.0);
        for _ in 0..6 {
            s.tick(Duration::from_secs_f32(1.0 / 120.0));
        }
        let v = s.velocity;
        assert!(v > 0.0);
        s.set_target(-100.0);
        assert_eq!(s.velocity, v);
    }

    #[test]
    fn spring_rect_snaps_first_then_glides_and_pins() {
        let p = SpringParams::stiffness(1.0, 420.0);
        let mut r = SpringRect::new(p);
        r.follow([10.0, 20.0, 300.0, 56.0], true, [false; 4]);
        assert_eq!(
            r.value(),
            [10.0, 20.0, 300.0, 56.0],
            "first layout must not fly in"
        );

        // Window grew: x and width glide, y is pinned to the bottom edge.
        r.follow(
            [110.0, 420.0, 360.0, 56.0],
            true,
            [false, true, false, false],
        );
        r.tick(Duration::from_secs_f32(1.0 / 60.0));
        let v = r.value();
        assert!(v[0] > 10.0 && v[0] < 110.0, "x glides: {}", v[0]);
        assert_eq!(v[1], 420.0, "pinned y snaps");
        let mut frames = 0;
        while r.tick(Duration::from_secs_f32(1.0 / 60.0)) {
            frames += 1;
            assert!(frames < 240, "must settle");
        }
        assert_eq!(r.value(), [110.0, 420.0, 360.0, 56.0]);

        // Animation off: everything snaps.
        r.follow([0.0, 0.0, 100.0, 50.0], false, [false; 4]);
        assert_eq!(r.value(), [0.0, 0.0, 100.0, 50.0]);
    }

    #[test]
    fn timeline_progresses_and_finishes() {
        let t0 = std::time::Instant::now();
        let mut tl = Timeline::new(Duration::from_millis(400));
        assert_eq!(tl.progress(t0), 1.0, "idle timeline reads as finished");
        assert!(!tl.running(t0));
        tl.start(t0);
        assert_eq!(tl.progress(t0), 0.0);
        assert!((tl.progress(t0 + Duration::from_millis(100)) - 0.25).abs() < 1e-4);
        assert!(tl.running(t0 + Duration::from_millis(399)));
        assert_eq!(tl.progress(t0 + Duration::from_secs(1)), 1.0);
        assert!(!tl.running(t0 + Duration::from_secs(1)));
    }

    #[test]
    fn physical_round_trip() {
        let p = SpringParams::stiffness(0.76, 750.0);
        let (k, c, m) = p.physical();
        assert!((k - 750.0).abs() < 1e-2);
        let zeta = c / (2.0 * (k * m).sqrt());
        assert!((zeta - 0.76).abs() < 1e-4);
    }
}
