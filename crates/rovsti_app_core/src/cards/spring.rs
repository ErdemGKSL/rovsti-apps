// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Ported from glisse, MIT, src/internal/spring.ts (`advance`, the three closed-form branches).

//! The analytic spring of glisse, for card choreography.
//!
//! A spring has unit mass, a stiffness `k` and a damping coefficient `c`; `ratio = c / (2 * sqrt(k))` is the Compose
//! style damping ratio the Rovsti motion tokens are written in (`references/motion.md` section 2.1). Because the
//! solution is closed form, the value at any time `t` is exact, so a frame renderer can ask for frame `n` without
//! stepping the frames before it.

/// A spring in glisse's terms: `stiffness` and the damping `coefficient`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Spring {
    /// Stiffness `k` (mass is 1).
    pub stiffness: f64,
    /// Damping coefficient `c`.
    pub damping: f64,
}

/// `sheetSettle`: ratio 0.86, stiffness 380. The panel drop.
pub const SHEET_SETTLE: Spring = Spring::from_ratio(0.86, 380.0);
/// `glassPressUp`: ratio 0.373, stiffness 250. Bounces past rest once; the jelly squash.
pub const GLASS_PRESS_UP: Spring = Spring::from_ratio(0.373, 250.0);
/// `menuMorphOpen`: ratio 0.939, stiffness 600. The lens sweep.
pub const MENU_MORPH_OPEN: Spring = Spring::from_ratio(0.939, 600.0);
/// `appear`: critically damped, stiffness 450. Alpha fades.
pub const APPEAR: Spring = Spring::from_ratio(1.0, 450.0);
/// `morph`: critically damped, stiffness 950. The contact panel.
pub const MORPH: Spring = Spring::from_ratio(1.0, 950.0);

impl Spring {
    /// A spring from a damping ratio and a stiffness (`c = 2 * ratio * sqrt(k)`).
    pub const fn from_ratio(ratio: f64, stiffness: f64) -> Self {
        Self {
            stiffness,
            damping: 2.0 * ratio * sqrt_const(stiffness),
        }
    }

    /// The damping ratio `c / (2 * sqrt(k))`.
    pub fn ratio(&self) -> f64 {
        self.damping / (2.0 * self.stiffness.sqrt())
    }

    /// Moves `(value, velocity)` towards `target` by `dt` seconds. Exactly glisse's `advance`.
    pub fn advance(&self, value: f64, velocity: f64, target: f64, dt: f64) -> (f64, f64) {
        let x = value - target;
        let v = velocity;
        let half = self.damping / 2.0;
        let discriminant = half * half - self.stiffness;
        if discriminant.abs() < 1e-4 {
            // Critically damped.
            let decay = (-half * dt).exp();
            let b = v + half * x;
            (target + decay * (x + b * dt), decay * (v - half * b * dt))
        } else if discriminant > 0.0 {
            // Over-damped.
            let root = discriminant.sqrt();
            let r1 = -half + root;
            let r2 = -half - root;
            let a = (v - r2 * x) / (r1 - r2);
            let b = x - a;
            (
                target + a * (r1 * dt).exp() + b * (r2 * dt).exp(),
                a * r1 * (r1 * dt).exp() + b * r2 * (r2 * dt).exp(),
            )
        } else {
            // Under-damped.
            let omega = (-discriminant).sqrt();
            let decay = (-half * dt).exp();
            let (sin, cos) = (omega * dt).sin_cos();
            (
                target + decay * (x * cos + ((v + half * x) / omega) * sin),
                decay * (v * cos - ((half * v + self.stiffness * x) / omega) * sin),
            )
        }
    }

    /// The step response: the value at time `t` seconds of a spring that starts at 0 at rest and is retargeted to 1.
    /// Before `t = 0` it is 0.
    pub fn response(&self, t: f64) -> f64 {
        if t <= 0.0 {
            return 0.0;
        }
        self.advance(0.0, 0.0, 1.0, t).0
    }

    /// The first time, in seconds, the step response reaches `level` (searched in 1 ms steps up to 10 s), if ever.
    pub fn first_reach(&self, level: f64) -> Option<f64> {
        (0..10_000)
            .map(|ms| f64::from(ms) / 1000.0)
            .find(|t| self.response(*t) >= level)
    }

    /// The time after which the step response stays within `tolerance` of 1 (searched in 1 ms steps), the settle time.
    pub fn settle_time(&self, tolerance: f64) -> f64 {
        (0..10_000)
            .rev()
            .map(|ms| f64::from(ms) / 1000.0)
            .find(|t| (self.response(*t) - 1.0).abs() > tolerance)
            .map_or(0.0, |t| t + 0.001)
    }
}

/// Square root usable in a `const fn` (Newton, 40 iterations; exact enough for token values).
const fn sqrt_const(x: f64) -> f64 {
    let mut guess = if x > 1.0 { x / 2.0 } else { 1.0 };
    let mut i = 0;
    while i < 40 {
        guess = 0.5 * (guess + x / guess);
        i += 1;
    }
    guess
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Local maxima of the response above 1 and minima below it, sampled every millisecond for 3 s.
    fn extrema(spring: Spring) -> (Vec<f64>, Vec<f64>) {
        let samples: Vec<f64> = (0..3000)
            .map(|ms| spring.response(f64::from(ms) / 1000.0))
            .collect();
        let (mut peaks, mut troughs) = (Vec::new(), Vec::new());
        for w in samples.windows(3) {
            if w[1] > w[0] && w[1] >= w[2] && w[1] > 1.0 {
                peaks.push(w[1] - 1.0);
            }
            if w[1] < w[0] && w[1] <= w[2] && w[1] < 1.0 {
                troughs.push(1.0 - w[1]);
            }
        }
        (peaks, troughs)
    }

    #[test]
    fn ratio_round_trips_the_token() {
        assert!((GLASS_PRESS_UP.ratio() - 0.373).abs() < 1e-9);
        // glisse's own pair for the press release: stiffness 250, damping 11.8.
        assert!(
            (GLASS_PRESS_UP.damping - 11.8).abs() < 0.05,
            "{}",
            GLASS_PRESS_UP.damping
        );
        assert!((MORPH.ratio() - 1.0).abs() < 1e-9);
    }

    #[test]
    fn critical_springs_never_overshoot() {
        for spring in [APPEAR, MORPH] {
            let mut previous = 0.0;
            for ms in 0..4000 {
                let v = spring.response(f64::from(ms) / 1000.0);
                assert!(v <= 1.0 + 1e-9, "{spring:?} overshoots: {v}");
                assert!(v >= previous - 1e-12, "monotonic");
                previous = v;
            }
            assert!((previous - 1.0).abs() < 1e-3);
        }
    }

    #[test]
    fn press_up_overshoots_once() {
        let (peaks, troughs) = extrema(GLASS_PRESS_UP);
        // Overshoot of a ratio 0.373 spring: exp(-pi z / sqrt(1 - z^2)).
        let expected = (-std::f64::consts::PI * 0.373 / (1.0 - 0.373f64 * 0.373).sqrt()).exp();
        assert!(
            (peaks[0] - expected).abs() < 0.005,
            "{} vs {expected}",
            peaks[0]
        );
        // One overshoot that matters (above 5 percent), then one dip below rest, then the ringing is under 3 percent.
        assert_eq!(peaks.iter().filter(|p| **p > 0.05).count(), 1, "{peaks:?}");
        assert_eq!(
            troughs.iter().filter(|p| **p > 0.05).count(),
            1,
            "{troughs:?}"
        );
        assert!(peaks[1] < 0.03, "{peaks:?}");
    }

    #[test]
    fn sheet_settle_overshoots_less_than_one_percent() {
        let (peaks, _) = extrema(SHEET_SETTLE);
        assert!(peaks.iter().all(|p| *p < 0.01), "{peaks:?}");
        let settle = SHEET_SETTLE.settle_time(0.002);
        assert!((0.3..0.9).contains(&settle), "settles in {settle} s");
    }

    #[test]
    fn advance_in_two_steps_equals_one() {
        for spring in [
            SHEET_SETTLE,
            GLASS_PRESS_UP,
            MENU_MORPH_OPEN,
            MORPH,
            Spring::from_ratio(1.265, 1000.0),
        ] {
            let whole = spring.advance(0.0, 0.0, 1.0, 0.5);
            let (x, v) = spring.advance(0.0, 0.0, 1.0, 0.2);
            let split = spring.advance(x, v, 1.0, 0.3);
            assert!(
                (whole.0 - split.0).abs() < 1e-9 && (whole.1 - split.1).abs() < 1e-9,
                "{spring:?}"
            );
        }
    }

    #[test]
    fn retargeting_keeps_velocity() {
        let (x, v) = GLASS_PRESS_UP.advance(0.0, 0.0, 1.0, 0.1);
        assert!(v.abs() > 1.0);
        // Retarget back to 0: the first step still moves the way it was going.
        let (x2, _) = GLASS_PRESS_UP.advance(x, v, 0.0, 0.01);
        assert!(x2 > x, "velocity survives the retarget");
    }
}
