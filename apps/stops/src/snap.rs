//! Pure snap decision for pin drops (no gpui). A slow, precise drag never snaps; a quick
//! flick snaps within a larger radius; a nudge of a pin that started snapped keeps it.
//! All distances are screen px, times are ms.

/// Samples older than this (relative to "now") are ignored for the release speed.
pub const VELOCITY_WINDOW_MS: u64 = 90;
/// Below this release speed the pin never snaps by radius.
pub const SLOW_PX_S: f32 = 250.0;
/// At or above this release speed the full radius applies.
pub const FAST_PX_S: f32 = 900.0;
/// Snap radius at fast release.
pub const FAST_RADIUS_PX: f32 = 56.0;
/// Radius for the "fast move, then stopped right at a stop" rule.
pub const NEAR_RADIUS_PX: f32 = 16.0;
/// How far back the peak speed looks.
pub const PEAK_WINDOW_MS: u64 = 300;
/// A pin that started snapped and moved no more than this stays snapped.
pub const HOLD_PX: f32 = 10.0;
/// Radius for plain taps on the map.
pub const TAP_RADIUS_PX: f32 = 40.0;

const RING: usize = 16;

/// Recent pointer samples `(t_ms, x, y)` in a fixed ring.
#[derive(Clone, Debug, Default)]
pub struct VelocityTracker {
    samples: [(u64, f32, f32); RING],
    head: usize,
    len: usize,
}

impl VelocityTracker {
    pub fn reset(&mut self) {
        self.head = 0;
        self.len = 0;
    }

    pub fn push(&mut self, t_ms: u64, (x, y): (f32, f32)) {
        self.samples[self.head] = (t_ms, x, y);
        self.head = (self.head + 1) % RING;
        self.len = (self.len + 1).min(RING);
    }

    /// Sample `i` in chronological order (0 = oldest).
    fn at(&self, i: usize) -> (u64, f32, f32) {
        self.samples[(self.head + RING - self.len + i) % RING]
    }

    /// Speed over the samples in the `VELOCITY_WINDOW_MS` ending at `end_ms`, using only
    /// samples not newer than `end_ms`. First-to-last displacement over elapsed time.
    fn speed_ending_at(&self, end_ms: u64) -> f32 {
        let mut first: Option<(u64, f32, f32)> = None;
        let mut last: Option<(u64, f32, f32)> = None;
        for i in 0..self.len {
            let s = self.at(i);
            if s.0 > end_ms || end_ms - s.0 > VELOCITY_WINDOW_MS {
                continue;
            }
            if first.is_none() {
                first = Some(s);
            }
            last = Some(s);
        }
        match (first, last) {
            (Some(a), Some(b)) if b.0 > a.0 => {
                let dist = (b.1 - a.1).hypot(b.2 - a.2);
                dist / ((b.0 - a.0) as f32 / 1000.0)
            }
            _ => 0.0,
        }
    }

    /// Smoothed speed at `now_ms`; a pause before release reads as slow.
    pub fn speed_px_s(&self, now_ms: u64) -> f32 {
        self.speed_ending_at(now_ms)
    }

    /// Highest windowed speed seen within the last `PEAK_WINDOW_MS`.
    pub fn peak_px_s(&self, now_ms: u64) -> f32 {
        (0..self.len)
            .map(|i| self.at(i).0)
            .filter(|&t| t <= now_ms && now_ms - t <= PEAK_WINDOW_MS)
            .map(|t| self.speed_ending_at(t))
            .fold(0.0, f32::max)
    }
}

/// Snap radius for a release speed: 0 when slow, `FAST_RADIUS_PX` when fast, linear between.
pub fn snap_radius_px(release_speed: f32) -> f32 {
    if release_speed < SLOW_PX_S {
        0.0
    } else if release_speed >= FAST_PX_S {
        FAST_RADIUS_PX
    } else {
        FAST_RADIUS_PX * (release_speed - SLOW_PX_S) / (FAST_PX_S - SLOW_PX_S)
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DropInput {
    pub release_px_s: f32,
    pub peak_px_s: f32,
    /// Distance in px to the nearest snap candidate, if any.
    pub candidate_px: Option<f32>,
    /// Finger travel since the drag began.
    pub travel_px: f32,
    pub started_on_candidate: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DropSnap {
    Snap,
    Free,
}

pub fn decide(input: &DropInput) -> DropSnap {
    if input.started_on_candidate && input.travel_px <= HOLD_PX {
        return DropSnap::Snap;
    }
    let snaps = input.candidate_px.is_some_and(|d| {
        d <= snap_radius_px(input.release_px_s)
            || (input.peak_px_s >= FAST_PX_S && d <= NEAR_RADIUS_PX)
    });
    if snaps {
        DropSnap::Snap
    } else {
        DropSnap::Free
    }
}

/// Taps (not drags) snap to a candidate within `TAP_RADIUS_PX`.
pub fn tap_snaps(candidate_px: Option<f32>) -> bool {
    candidate_px.is_some_and(|d| d <= TAP_RADIUS_PX)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(release: f32, peak: f32, cand: Option<f32>, travel: f32, started: bool) -> DropInput {
        DropInput {
            release_px_s: release,
            peak_px_s: peak,
            candidate_px: cand,
            travel_px: travel,
            started_on_candidate: started,
        }
    }

    #[test]
    fn evenly_spaced_samples_give_1000_px_s() {
        let mut t = VelocityTracker::default();
        for i in 0..6u64 {
            t.push(i * 10, (i as f32 * 10.0, 0.0));
        }
        assert!((t.speed_px_s(50) - 1000.0).abs() < 1.0);
        assert!((t.peak_px_s(50) - 1000.0).abs() < 1.0);
    }

    #[test]
    fn stale_samples_are_ignored() {
        let mut t = VelocityTracker::default();
        t.push(0, (0.0, 0.0));
        t.push(10, (50.0, 0.0));
        assert_eq!(t.speed_px_s(500), 0.0);
        assert!(t.speed_px_s(10) > 0.0);
        assert!(t.peak_px_s(500) == 0.0);
    }

    #[test]
    fn fewer_than_two_samples_is_zero() {
        let mut t = VelocityTracker::default();
        assert_eq!(t.speed_px_s(0), 0.0);
        t.push(5, (1.0, 1.0));
        assert_eq!(t.speed_px_s(5), 0.0);
        t.push(5, (9.0, 1.0));
        assert_eq!(t.speed_px_s(5), 0.0, "zero dt");
        t.reset();
        assert_eq!(t.peak_px_s(5), 0.0);
    }

    #[test]
    fn ring_wraps() {
        let mut t = VelocityTracker::default();
        for i in 0..40u64 {
            t.push(i * 10, (i as f32 * 10.0, 0.0));
        }
        assert!((t.speed_px_s(390) - 1000.0).abs() < 1.0);
    }

    #[test]
    fn radius_curve() {
        assert_eq!(snap_radius_px(200.0), 0.0);
        assert!((snap_radius_px(575.0) - 28.0).abs() < 0.01);
        assert_eq!(snap_radius_px(1000.0), FAST_RADIUS_PX);
    }

    #[test]
    fn slow_release_next_to_stop_is_free() {
        assert_eq!(
            decide(&input(100.0, 100.0, Some(2.0), 80.0, false)),
            DropSnap::Free
        );
    }

    #[test]
    fn fast_flick_snaps_40px_away() {
        assert_eq!(
            decide(&input(1000.0, 1000.0, Some(40.0), 120.0, false)),
            DropSnap::Snap
        );
    }

    #[test]
    fn fast_then_stop_snaps_via_peak() {
        assert_eq!(
            decide(&input(0.0, 1200.0, Some(10.0), 120.0, false)),
            DropSnap::Snap
        );
        assert_eq!(
            decide(&input(0.0, 1200.0, Some(30.0), 120.0, false)),
            DropSnap::Free
        );
    }

    #[test]
    fn started_snapped_hysteresis() {
        assert_eq!(
            decide(&input(50.0, 50.0, Some(6.0), 6.0, true)),
            DropSnap::Snap
        );
        assert_eq!(
            decide(&input(50.0, 50.0, Some(30.0), 30.0, true)),
            DropSnap::Free
        );
    }

    #[test]
    fn tap_radius() {
        assert!(tap_snaps(Some(39.0)));
        assert!(!tap_snaps(Some(41.0)));
        assert!(!tap_snaps(None));
    }
}
