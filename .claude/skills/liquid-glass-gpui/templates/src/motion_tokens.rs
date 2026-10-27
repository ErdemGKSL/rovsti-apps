//! Motion vocabulary for iOS-style interfaces.
//!
//! Values are expressed as (damping ratio, stiffness) with mass 1, the same
//! convention Jetpack Compose uses. Convert with [`SpringParams::stiffness`].
//! Approximate SwiftUI equivalent: `response = 2π / sqrt(stiffness)`.

use crate::spring::SpringParams;

/// Selection indicators: tab puck, segmented thumb. Slight overshoot.
/// (ζ 0.76, k 750 → response ≈ 0.23 s)
pub fn select() -> SpringParams {
    SpringParams::stiffness(0.76, 750.0)
}

/// Finger-down feedback. Fast, a hint of bounce.
pub fn press() -> SpringParams {
    SpringParams::stiffness(0.75, 1220.0)
}

/// Finger-up return. Softer than press so the release is visible.
pub fn release() -> SpringParams {
    SpringParams::stiffness(0.6, 420.0)
}

/// Sheets, menus, chips appearing. Critically damped.
pub fn appear() -> SpringParams {
    SpringParams::stiffness(1.0, 450.0)
}

/// Shape morphs (bar ↔ mini player, button ↔ menu). Critically damped, quick.
pub fn morph() -> SpringParams {
    SpringParams::stiffness(1.0, 950.0)
}

/// Grid/list item pushes.
pub fn collection_push() -> SpringParams {
    SpringParams::stiffness(1.0, 380.0)
}

/// Sheet snapping to a detent after a fling.
pub fn sheet_snap() -> SpringParams {
    SpringParams::stiffness(0.86, 380.0)
}

/// Sheet dismissal.
pub fn sheet_dismiss() -> SpringParams {
    SpringParams::stiffness(1.0, 400.0)
}

/// Tab-puck squash/stretch axes: X is looser than Y so the drop "wobbles".
pub fn puck_scale_x() -> SpringParams {
    SpringParams::stiffness(0.6, 250.0)
}
pub fn puck_scale_y() -> SpringParams {
    SpringParams::stiffness(0.7, 250.0)
}

/// Press glow and the glow's follow-the-finger position.
pub fn glow() -> SpringParams {
    SpringParams::stiffness(0.5, 300.0)
}

/// Optional animated window resize: floating chrome glides to its new anchor.
/// Critically damped so nothing overshoots past the window edge; soft enough
/// (≈ 0.31 s) to read as liquid during a live drag-resize.
pub fn resize() -> SpringParams {
    SpringParams::stiffness(1.0, 420.0)
}

/// A container that leans or grows under a held control (tab bar under the
/// puck) settles back with this: critically damped, soft.
pub fn container_settle() -> SpringParams {
    SpringParams::stiffness(1.0, 300.0)
}

/// Light ↔ dark cross-fade progress (0 = light, 1 = dark) for glass tint,
/// labels, backgrounds and shadows. A critically damped spring rather than a
/// fixed tween, so toggling again mid-fade reverses smoothly (≈ 0.26 s).
pub fn theme_fade() -> SpringParams {
    SpringParams::stiffness(1.0, 600.0)
}

/// Scale factors used with the springs above.
pub mod scale {
    /// Icon / small button pressed.
    pub const PRESS: f32 = 0.97;
    /// Card or large cell pressed (playful bounce click).
    pub const PRESS_CARD: f32 = 0.94;
    /// Tab-bar puck pressed: it *grows* into a lens.
    pub const PUCK_PRESSED: f32 = 1.39;
    /// Outgoing screen during push navigation.
    pub const PUSH_DIM: f32 = 0.85;
}

/// Duration-based curves (for GPUI's `Animation::with_easing`).
pub mod curve {
    use std::time::Duration;

    /// iOS navigation push: 400 ms, cubic-bezier(0.32, 0.72, 0, 1).
    pub const PUSH: (Duration, [f32; 4]) = (Duration::from_millis(400), [0.32, 0.72, 0.0, 1.0]);
    /// Card/zoom transitions: enter 340 ms, exit 260 ms, cubic-bezier(0.3, 0.5, 0.4, 1).
    pub const CARD_ENTER: (Duration, [f32; 4]) = (Duration::from_millis(340), [0.3, 0.5, 0.4, 1.0]);
    pub const CARD_EXIT: (Duration, [f32; 4]) = (Duration::from_millis(260), [0.3, 0.5, 0.4, 1.0]);
    /// UIKit default ease used by many system transitions.
    pub const CUPERTINO: (Duration, [f32; 4]) =
        (Duration::from_millis(400), [0.2833, 0.99, 0.31833, 0.99]);
    /// Release pulse: a ring that spreads out of a control and fades.
    pub const RELEASE_RING: (Duration, [f32; 4]) = (Duration::from_millis(460), [0.0, 0.0, 0.2, 1.0]);
    /// Tab bar ↔ mini player morph timeline.
    pub const MORPH: Duration = Duration::from_millis(500);

    /// CSS-style cubic-bezier easing `(x1, y1, x2, y2)`. Returns a closure
    /// usable as `Animation::new(d).with_easing(cubic_bezier(p))`.
    pub fn cubic_bezier(p: [f32; 4]) -> impl Fn(f32) -> f32 + Clone {
        let [x1, y1, x2, y2] = p;
        move |t: f32| {
            if t <= 0.0 {
                return 0.0;
            }
            if t >= 1.0 {
                return 1.0;
            }
            let bx = |s: f32| 3.0 * (1.0 - s) * (1.0 - s) * s * x1 + 3.0 * (1.0 - s) * s * s * x2 + s * s * s;
            let by = |s: f32| 3.0 * (1.0 - s) * (1.0 - s) * s * y1 + 3.0 * (1.0 - s) * s * s * y2 + s * s * s;
            // Solve bx(s) = t by bisection; monotonic for x1, x2 in 0..=1.
            let (mut lo, mut hi) = (0.0f32, 1.0f32);
            for _ in 0..24 {
                let mid = 0.5 * (lo + hi);
                if bx(mid) < t { lo = mid } else { hi = mid }
            }
            by(0.5 * (lo + hi))
        }
    }

    /// A spring's step response squeezed into exactly `0..=1` so several
    /// properties can share one duration and finish on the same frame.
    ///
    /// `zeta` < 1 overshoots; `omega_t` is the angular frequency over the
    /// normalised time (≈ 7.8 gives a response that is visually settled at
    /// t = 1). The residual at t = 1 is removed linearly so the curve ends at 1.
    pub fn spring_easing(zeta: f32, omega_t: f32) -> impl Fn(f32) -> f32 + Clone {
        let raw = move |t: f32| {
            if zeta >= 1.0 {
                let wt = omega_t * t;
                return 1.0 - (1.0 + wt) * (-wt).exp();
            }
            let root = (1.0 - zeta * zeta).sqrt();
            let wd = omega_t * root;
            1.0 - (-zeta * omega_t * t).exp() * ((wd * t).cos() + zeta / root * (wd * t).sin())
        };
        let end = raw(1.0);
        move |t: f32| {
            let t = t.clamp(0.0, 1.0);
            raw(t) + (1.0 - end) * t
        }
    }

    /// Critically damped version: no overshoot. Use it for *positions* while
    /// width/height use a bouncy `spring_easing` — the shape wobbles, its
    /// centre does not.
    pub fn critical_easing(omega_t: f32) -> impl Fn(f32) -> f32 + Clone {
        spring_easing(1.0, omega_t)
    }

    /// Delays the start of an easing within a shared timeline, e.g.
    /// `staggered(67.0 / 500.0, spring_easing(0.62, 7.8))` starts the second
    /// axis 67 ms into a 500 ms transition.
    pub fn staggered(
        delay_fraction: f32,
        easing: impl Fn(f32) -> f32 + Clone,
    ) -> impl Fn(f32) -> f32 + Clone {
        move |t: f32| {
            let local = ((t - delay_fraction) / (1.0 - delay_fraction).max(1e-3)).clamp(0.0, 1.0);
            easing(local)
        }
    }

    /// Per-axis progress (0..=1, may overshoot on sizes) for a two-shape morph
    /// such as tab bar ↔ mini player, sampled at linear time `t` of [`MORPH`].
    ///
    /// Centres move critically damped; only widths overshoot. The axes are
    /// staggered like iOS: collapsing slides sideways first and drops 67 ms
    /// later; expanding rises first and widens 33 ms later.
    pub fn morph_axes(t: f32, collapsing: bool) -> MorphAxes {
        let total = MORPH.as_secs_f32() * 1000.0;
        let pos = critical_easing(7.8);
        if collapsing {
            MorphAxes {
                x: pos(t),
                y: staggered(67.0 / total, critical_easing(7.8))(t),
                width: spring_easing(0.62, 7.8)(t),
                bar_width: spring_easing(0.74, 7.8)(t),
            }
        } else {
            MorphAxes {
                y: pos(t),
                x: staggered(33.0 / total, critical_easing(7.8))(t),
                width: staggered(33.0 / total, spring_easing(0.58, 7.8))(t),
                bar_width: spring_easing(0.74, 7.8)(t),
            }
        }
    }

    #[derive(Clone, Copy, Debug, PartialEq)]
    pub struct MorphAxes {
        pub x: f32,
        pub y: f32,
        /// Width of the morphing shape (mini player).
        pub width: f32,
        /// Width of the companion shape (tab bar body).
        pub bar_width: f32,
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn morph_axes_start_end_and_stagger() {
            for collapsing in [true, false] {
                let a = morph_axes(0.0, collapsing);
                assert_eq!((a.x, a.y, a.width, a.bar_width), (0.0, 0.0, 0.0, 0.0));
                let b = morph_axes(1.0, collapsing);
                for v in [b.x, b.y, b.width, b.bar_width] {
                    assert!((v - 1.0).abs() < 1e-3, "{collapsing}: {b:?}");
                }
            }
            // Collapsing: sideways leads, the drop lags.
            let c = morph_axes(0.1, true);
            assert!(c.x > c.y, "{c:?}");
            // Expanding: rise leads, sideways lags.
            let e = morph_axes(0.1, false);
            assert!(e.y > e.x, "{e:?}");
            // Sizes overshoot, positions never do.
            let peak_w = (0..=100).map(|i| morph_axes(i as f32 / 100.0, true).width).fold(0.0, f32::max);
            let peak_x = (0..=100).map(|i| morph_axes(i as f32 / 100.0, true).x).fold(0.0, f32::max);
            assert!(peak_w > 1.01, "width should overshoot: {peak_w}");
            assert!(peak_x <= 1.0 + 1e-4, "position must not overshoot: {peak_x}");
        }

        #[test]
        fn curves_hit_endpoints() {
            let curves: [&dyn Fn(f32) -> f32; 3] =
                [&spring_easing(0.62, 7.8), &spring_easing(0.34, 9.0), &critical_easing(7.8)];
            for f in curves {
                assert!(f(0.0).abs() < 1e-4);
                assert!((f(1.0) - 1.0).abs() < 1e-4);
            }
            let b = cubic_bezier(PUSH.1);
            assert!(b(0.0) == 0.0 && b(1.0) == 1.0);
            assert!(b(0.5) > 0.8, "push curve is front-loaded");
        }

        #[test]
        fn critical_is_monotonic() {
            let f = critical_easing(7.8);
            let mut prev = 0.0;
            for i in 0..=100 {
                let v = f(i as f32 / 100.0);
                assert!(v + 1e-5 >= prev);
                prev = v;
            }
        }
    }
}

/// Gesture helpers.
pub mod gesture {
    /// Where a fling would come to rest under UIKit's scroll deceleration
    /// (`rate` = 0.998 per millisecond, i.e. `UIScrollView.DecelerationRate.normal`).
    /// Pick the snap target (detent, page, tab) nearest to this, not to the
    /// release point.
    pub fn project(position: f32, velocity_per_sec: f32, rate: f32) -> f32 {
        let velocity_per_ms = velocity_per_sec / 1000.0;
        position + velocity_per_ms * rate / (1.0 - rate)
    }

    /// Rubber-band an overscroll or an off-axis drag: linear for small
    /// offsets, asymptotic to `limit` for large ones.
    pub fn rubber_band(offset: f32, limit: f32) -> f32 {
        if limit <= 0.0 {
            return 0.0;
        }
        limit * (offset / limit).tanh()
    }

    /// Elastic follow for a pressed button dragged by `offset` px: it moves
    /// with the finger at 5% at first and saturates at `max` (usually the
    /// button's smaller side), like a drop held by surface tension.
    pub fn elastic_offset(offset: f32, max: f32) -> f32 {
        if max <= 0.0 {
            return 0.0;
        }
        max * (0.05 * offset / max).tanh()
    }

    /// Stretch of a pressed, dragged button along the drag direction.
    /// `base` is the press scale; `max_drag_scale` ≈ 4 px / height.
    /// Returns `(scale_x, scale_y)`.
    pub fn elastic_scale(
        offset: (f32, f32),
        size: (f32, f32),
        base: f32,
        max_drag_scale: f32,
    ) -> (f32, f32) {
        let (ox, oy) = offset;
        let (w, h) = size;
        let max_dim = w.max(h).max(1.0);
        let angle = oy.atan2(ox);
        let sx = base + max_drag_scale * (angle.cos() * ox / max_dim).abs() * (w / h.max(1.0)).min(1.0);
        let sy = base + max_drag_scale * (angle.sin() * oy / max_dim).abs() * (h / w.max(1.0)).min(1.0);
        (sx, sy)
    }

    /// Squash & stretch from a normalised velocity (roughly -1..1): the
    /// shape stretches along the motion and thins across it, preserving
    /// apparent volume. Returns `(scale_x, scale_y)` for horizontal motion.
    pub fn squash_stretch(normalised_velocity: f32) -> (f32, f32) {
        let speed = normalised_velocity.abs();
        let sx = 1.0 / (1.0 - (speed * 0.75).min(0.2));
        let sy = 1.0 - (speed * 0.25).min(0.2);
        (sx, sy)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn elastic_offset_is_soft_then_saturates() {
            let max = 96.0;
            let small = elastic_offset(20.0, max);
            assert!((small - 1.0).abs() < 0.05, "≈5% near zero: {small}");
            assert!(elastic_offset(10_000.0, max) <= max);
            assert!(elastic_offset(-10_000.0, max) >= -max);
        }

        #[test]
        fn elastic_scale_stretches_along_drag() {
            let (sx, sy) = elastic_scale((60.0, 0.0), (96.0, 96.0), 1.0, 4.0 / 96.0);
            assert!(sx > 1.0 && (sy - 1.0).abs() < 1e-5, "{sx} {sy}");
            let (sx, sy) = elastic_scale((0.0, 60.0), (96.0, 96.0), 1.0, 4.0 / 96.0);
            assert!(sy > 1.0 && (sx - 1.0).abs() < 1e-5, "{sx} {sy}");
        }
    }
}
