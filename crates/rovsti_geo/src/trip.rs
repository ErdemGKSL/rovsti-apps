use std::time::Duration;

use crate::Coord;

/// When to wake the user.
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub enum AlarmRule {
    /// Ring once within this many metres of the destination.
    Radius { metres: f64 },
    /// Ring when the estimated time to arrival drops below this, falling back
    /// to `min_metres` when the speed estimate is unusable (stopped, no GPS).
    Eta { before: Duration, min_metres: f64 },
}

/// One location fix from the platform.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Fix {
    pub at: Coord,
    /// Horizontal accuracy radius, metres (Android `Location.getAccuracy`).
    pub accuracy_m: f64,
    /// Ground speed, m/s, when the provider reports one.
    pub speed_mps: Option<f64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TripState {
    Tracking,
    /// The alarm fired; the UI keeps ringing until the user dismisses it.
    Arrived,
    Dismissed,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TripUpdate {
    pub state: TripState,
    pub distance_m: f64,
    /// True exactly once: on the update that crossed the alarm threshold.
    pub ring: bool,
    /// When the location service should deliver the next fix.
    pub next_fix_in: Duration,
}

/// Fixes worse than this are ignored: a 2 km cell-tower fix must never ring.
const MAX_ACCURACY_M: f64 = 150.0;
/// Below this speed the ETA is meaningless (waiting at a stop, traffic jam).
const MIN_SPEED_MPS: f64 = 1.5;
/// Assumed top speed for scheduling fixes: a fast intercity train.
const MAX_SPEED_MPS: f64 = 60.0;

#[derive(Clone, Debug)]
pub struct Trip {
    pub destination: Coord,
    pub rule: AlarmRule,
    state: TripState,
    last_distance_m: Option<f64>,
}

impl Trip {
    pub fn new(destination: Coord, rule: AlarmRule) -> Self {
        Self {
            destination,
            rule,
            state: TripState::Tracking,
            last_distance_m: None,
        }
    }

    pub fn state(&self) -> TripState {
        self.state
    }

    pub fn last_distance_m(&self) -> Option<f64> {
        self.last_distance_m
    }

    pub fn dismiss(&mut self) {
        self.state = TripState::Dismissed;
    }

    pub fn update(&mut self, fix: Fix) -> TripUpdate {
        let distance_m = fix.at.distance_m(self.destination);
        let usable = fix.accuracy_m <= MAX_ACCURACY_M;
        if usable {
            self.last_distance_m = Some(distance_m);
        }

        let ring = self.state == TripState::Tracking && usable && self.should_ring(distance_m, fix);
        if ring {
            self.state = TripState::Arrived;
        }

        TripUpdate {
            state: self.state,
            distance_m,
            ring,
            next_fix_in: self.next_fix_in(distance_m),
        }
    }

    fn should_ring(&self, distance_m: f64, fix: Fix) -> bool {
        // Count the fix's uncertainty in the user's favour: waking up a little
        // early beats sleeping past the stop.
        let effective = (distance_m - fix.accuracy_m).max(0.0);
        match self.rule {
            AlarmRule::Radius { metres } => effective <= metres,
            AlarmRule::Eta { before, min_metres } => {
                if effective <= min_metres {
                    return true;
                }
                match fix.speed_mps {
                    Some(speed) if speed >= MIN_SPEED_MPS => {
                        effective / speed <= before.as_secs_f64()
                    }
                    _ => false,
                }
            }
        }
    }

    /// Far away → sleep long; close → poll every few seconds. Assumes the
    /// worst-case speed so the alarm radius can never be skipped between fixes.
    fn next_fix_in(&self, distance_m: f64) -> Duration {
        if self.state != TripState::Tracking {
            return Duration::from_secs(60);
        }
        let threshold = match self.rule {
            AlarmRule::Radius { metres } => metres,
            AlarmRule::Eta { before, min_metres } => {
                min_metres.max(before.as_secs_f64() * MAX_SPEED_MPS)
            }
        };
        let slack = (distance_m - threshold).max(0.0);
        // Spend at most half the worst-case time-to-threshold asleep.
        let secs = (slack / MAX_SPEED_MPS / 2.0).clamp(3.0, 300.0);
        Duration::from_secs_f64(secs)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DEST: Coord = Coord::new(41.0, 29.0);

    /// A point `metres` due north of `DEST`.
    fn north(metres: f64) -> Coord {
        Coord::new(
            DEST.lat + (metres / crate::EARTH_RADIUS_M).to_degrees(),
            DEST.lon,
        )
    }

    fn fix(metres: f64, accuracy_m: f64, speed_mps: Option<f64>) -> Fix {
        Fix {
            at: north(metres),
            accuracy_m,
            speed_mps,
        }
    }

    #[test]
    fn radius_rings_once() {
        let mut trip = Trip::new(DEST, AlarmRule::Radius { metres: 500.0 });
        assert!(!trip.update(fix(3_000.0, 10.0, None)).ring);
        let hit = trip.update(fix(450.0, 10.0, None));
        assert!(hit.ring);
        assert_eq!(hit.state, TripState::Arrived);
        assert!(!trip.update(fix(100.0, 10.0, None)).ring);
    }

    #[test]
    fn inaccurate_fix_never_rings() {
        let mut trip = Trip::new(DEST, AlarmRule::Radius { metres: 500.0 });
        assert!(!trip.update(fix(100.0, 2_000.0, None)).ring);
        assert_eq!(trip.state(), TripState::Tracking);
    }

    #[test]
    fn eta_rule_uses_speed() {
        let rule = AlarmRule::Eta {
            before: Duration::from_secs(120),
            min_metres: 200.0,
        };
        let mut trip = Trip::new(DEST, rule);
        // 3 km at 20 m/s = 150 s away: not yet.
        assert!(!trip.update(fix(3_000.0, 10.0, Some(20.0))).ring);
        // 2 km at 20 m/s = 100 s away: ring.
        assert!(trip.update(fix(2_000.0, 10.0, Some(20.0))).ring);
    }

    #[test]
    fn eta_rule_falls_back_to_radius_when_stopped() {
        let rule = AlarmRule::Eta {
            before: Duration::from_secs(120),
            min_metres: 200.0,
        };
        let mut trip = Trip::new(DEST, rule);
        assert!(!trip.update(fix(1_000.0, 10.0, Some(0.0))).ring);
        assert!(trip.update(fix(150.0, 10.0, Some(0.0))).ring);
    }

    #[test]
    fn polls_slowly_when_far_and_fast_when_close() {
        let mut trip = Trip::new(DEST, AlarmRule::Radius { metres: 500.0 });
        let far = trip.update(fix(80_000.0, 10.0, None)).next_fix_in;
        let near = trip.update(fix(700.0, 10.0, None)).next_fix_in;
        assert_eq!(far, Duration::from_secs(300));
        assert_eq!(near, Duration::from_secs(3));
    }

    #[test]
    fn dismissed_trip_stays_quiet() {
        let mut trip = Trip::new(DEST, AlarmRule::Radius { metres: 500.0 });
        trip.dismiss();
        assert!(!trip.update(fix(0.0, 5.0, None)).ring);
    }
}
