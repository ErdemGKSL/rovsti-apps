//! Adaptive location-polling curve: the closer to the A-B diameter, the more
//! often we ask for a fix.
//!
//! The interval is the time the fastest plausible traveller ([`MAX_SPEED_MPS`])
//! needs to cover *half* of the remaining slack, so the diameter can never be
//! skipped between two fixes. The slack is the distance to the line minus the
//! fix accuracy minus the effective dead band (`max(DEAD_BAND_M, accuracy)`):
//!
//! ```text
//! slack    = max(0, line - acc - max(DEAD_BAND_M, acc))
//! interval = clamp(slack / (2 * MAX_SPEED_MPS), MIN_POLL, MAX_POLL)
//! ```
//!
//! Unknown inputs are treated pessimistically for accuracy (150 m, the worst
//! usable value) and as "far away" for distance (300 s, the cap). Worked
//! examples (accuracy 5 m):
//!
//! | distance to line | interval |
//! |------------------|----------|
//! | 0 .. ~100 m      | 3 s      |
//! | 500 m            | 3 s      |
//! | 1 km             | 8 s      |
//! | 5 km             | 42 s     |
//! | 10 km            | 83 s     |
//! | 36 km and beyond | 300 s    |
//! | unknown          | 300 s    |

use std::time::Duration;

use crate::crossing::DEAD_BAND_M;
use crate::trip::{MAX_ACCURACY_M, MAX_SPEED_MPS};

/// Shortest interval between fixes (right at the diameter).
pub const MIN_POLL: Duration = Duration::from_secs(3);
/// Longest interval between fixes, never exceeded.
pub const MAX_POLL: Duration = Duration::from_secs(300);
/// Interval when the trip is not tracking (arrived or dismissed).
pub const IDLE_POLL: Duration = Duration::from_secs(60);

/// Seconds until the next fix is wanted, given the distance to the diameter
/// and the accuracy of the latest fix (either may be unknown).
pub fn poll_interval(line_m: Option<f64>, accuracy_m: Option<f64>) -> Duration {
    let Some(line) = line_m.filter(|l| !l.is_nan()) else {
        return MAX_POLL;
    };
    let acc = accuracy_m
        .filter(|a| a.is_finite() && *a <= MAX_ACCURACY_M)
        .map_or(MAX_ACCURACY_M, |a| a.max(0.0));
    let slack = (line - acc - DEAD_BAND_M.max(acc)).max(0.0);
    let secs = slack / (2.0 * MAX_SPEED_MPS);
    Duration::from_secs_f64(secs.clamp(MIN_POLL.as_secs_f64(), MAX_POLL.as_secs_f64()))
}

/// How long to wait for a fix before assuming it is missing and re-requesting.
pub fn retry_after(interval: Duration) -> Duration {
    interval + (interval / 4).clamp(Duration::from_secs(5), Duration::from_secs(30))
}

#[cfg(test)]
mod tests {
    use super::*;

    const ACCS: [Option<f64>; 6] = [
        Some(0.0),
        Some(5.0),
        Some(50.0),
        Some(150.0),
        Some(f64::NAN),
        None,
    ];

    #[test]
    fn monotonic_in_distance() {
        for acc in ACCS {
            let mut prev = Duration::ZERO;
            for i in 0..=2_000 {
                let d = poll_interval(Some(f64::from(i) * 50.0), acc);
                assert!(d >= prev, "acc {acc:?} at {} m: {d:?} < {prev:?}", i * 50);
                prev = d;
            }
        }
    }

    #[test]
    fn capped_at_exactly_300s() {
        for acc in ACCS {
            for line in [50_000.0, 1e6, 1e12, f64::MAX, f64::INFINITY] {
                assert_eq!(poll_interval(Some(line), acc), MAX_POLL);
            }
        }
        assert_eq!(MAX_POLL, Duration::from_secs(300));
        for i in 0..=2_000 {
            assert!(poll_interval(Some(f64::from(i) * 50.0), Some(0.0)) <= MAX_POLL);
        }
    }

    #[test]
    fn floor_is_three_seconds() {
        for acc in ACCS {
            for line in [0.0, -1.0, -1e9, f64::NEG_INFINITY, 1.0] {
                assert_eq!(poll_interval(Some(line), acc), MIN_POLL);
            }
        }
    }

    #[test]
    fn nan_distance_is_unknown() {
        assert_eq!(poll_interval(Some(f64::NAN), Some(5.0)), MAX_POLL);
    }

    #[test]
    fn diameter_cannot_be_skipped() {
        for acc in [0.0, 5.0, 50.0, 150.0] {
            for i in 0..=2_000 {
                let line = f64::from(i) * 50.0;
                let slack = (line - acc - DEAD_BAND_M.max(acc)).max(0.0);
                let d = poll_interval(Some(line), Some(acc)).as_secs_f64();
                if d > MIN_POLL.as_secs_f64() && d < MAX_POLL.as_secs_f64() {
                    assert!(
                        d * MAX_SPEED_MPS <= slack / 2.0 + 1e-6,
                        "{line} m acc {acc}"
                    );
                }
            }
        }
    }

    #[test]
    fn unknown_distance_is_max() {
        for acc in ACCS {
            assert_eq!(poll_interval(None, acc), MAX_POLL);
        }
    }

    #[test]
    fn unknown_accuracy_never_longer_than_150() {
        for i in 0..=2_000 {
            let line = Some(f64::from(i) * 50.0);
            let worst = poll_interval(line, Some(150.0));
            for acc in [None, Some(f64::NAN), Some(f64::INFINITY), Some(1_000.0)] {
                assert!(poll_interval(line, acc) <= worst);
            }
        }
    }

    #[test]
    fn example_table() {
        let s = |m| poll_interval(Some(m), Some(5.0)).as_secs_f64().round() as u64;
        assert_eq!(s(100.0), 3);
        assert_eq!(s(1_000.0), 8);
        assert_eq!(s(5_000.0), 42);
        assert_eq!(s(10_000.0), 83);
        assert_eq!(s(36_000.0), 300);
    }

    #[test]
    fn retry_bounds_and_monotonic() {
        assert_eq!(retry_after(Duration::from_secs(3)), Duration::from_secs(8));
        assert_eq!(
            retry_after(Duration::from_secs(300)),
            Duration::from_secs(330)
        );
        let mut prev = Duration::ZERO;
        for s in 0..=300 {
            let i = Duration::from_secs(s);
            let r = retry_after(i);
            assert!(r > i && r >= prev);
            prev = r;
        }
    }
}
