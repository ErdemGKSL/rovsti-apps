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
    /// Ring when within `metres` of `trigger`, a point on the route before the
    /// destination (e.g. the stop before), or once the trip has already got
    /// closer to the destination than `trigger` is (the trigger was skipped
    /// between two fixes).
    BeforeStop { trigger: Coord, metres: f64 },
}

impl AlarmRule {
    /// Threshold test for a fix `distance_m` from the destination. The fix's
    /// uncertainty is counted in the user's favour: waking up a little early
    /// beats sleeping past the stop.
    ///
    /// `BeforeStop` needs the destination to detect a skipped trigger; this
    /// method only checks the fix against the trigger itself. Use
    /// [`AlarmRule::is_met_for`] when the destination is known.
    pub fn is_met(&self, distance_m: f64, fix: &Fix) -> bool {
        let effective = (distance_m - fix.accuracy_m).max(0.0);
        match *self {
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
            AlarmRule::BeforeStop { trigger, metres } => {
                (fix.at.distance_m(trigger) - fix.accuracy_m).max(0.0) <= metres
            }
        }
    }

    /// Like [`AlarmRule::is_met`], but also recognises a `BeforeStop` trigger
    /// that was passed between fixes.
    pub fn is_met_for(&self, destination: Coord, distance_m: f64, fix: &Fix) -> bool {
        if let AlarmRule::BeforeStop { trigger, .. } = *self
            && distance_m <= trigger.distance_m(destination)
        {
            return true;
        }
        self.is_met(distance_m, fix)
    }
}

/// One location fix from the platform.
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Fix {
    pub at: Coord,
    /// Horizontal accuracy radius, metres (Android `Location.getAccuracy`).
    pub accuracy_m: f64,
    /// Ground speed, m/s, when the provider reports one.
    pub speed_mps: Option<f64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
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
    /// Distance / speed, when the fix carries a usable speed.
    pub eta: Option<Duration>,
}

/// Fixes worse than this are ignored: a 2 km cell-tower fix must never ring.
pub(crate) const MAX_ACCURACY_M: f64 = 150.0;
/// Below this speed the ETA is meaningless (waiting at a stop, traffic jam).
const MIN_SPEED_MPS: f64 = 1.5;
/// Assumed top speed for scheduling fixes: a fast intercity train.
const MAX_SPEED_MPS: f64 = 60.0;

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
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

        let ring = self.state == TripState::Tracking
            && usable
            && self.rule.is_met_for(self.destination, distance_m, &fix);
        if ring {
            self.state = TripState::Arrived;
        }

        TripUpdate {
            state: self.state,
            distance_m,
            ring,
            next_fix_in: self.next_fix_in(distance_m),
            eta: fix
                .speed_mps
                .filter(|speed| *speed >= MIN_SPEED_MPS)
                .map(|speed| Duration::from_secs_f64(distance_m / speed)),
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
            AlarmRule::BeforeStop { trigger, metres } => {
                trigger.distance_m(self.destination) + metres
            }
        };
        let slack = (distance_m - threshold).max(0.0);
        // Spend at most half the worst-case time-to-threshold asleep.
        let secs = (slack / MAX_SPEED_MPS / 2.0).clamp(3.0, 300.0);
        Duration::from_secs_f64(secs)
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
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
    fn is_met_matches_old_radius_and_eta_behaviour() {
        let f = |m, acc, speed| fix(m, acc, speed);
        let radius = AlarmRule::Radius { metres: 500.0 };
        assert!(radius.is_met(450.0, &f(450.0, 0.0, None)));
        assert!(!radius.is_met(3_000.0, &f(3_000.0, 10.0, None)));
        // Accuracy counts in the user's favour.
        assert!(radius.is_met(600.0, &f(600.0, 150.0, None)));
        let eta = AlarmRule::Eta {
            before: Duration::from_secs(120),
            min_metres: 200.0,
        };
        assert!(!eta.is_met(3_000.0, &f(3_000.0, 10.0, Some(20.0))));
        assert!(eta.is_met(2_000.0, &f(2_000.0, 10.0, Some(20.0))));
        assert!(!eta.is_met(1_000.0, &f(1_000.0, 10.0, Some(0.0))));
        assert!(eta.is_met(150.0, &f(150.0, 10.0, Some(0.0))));
    }

    fn before_stop() -> AlarmRule {
        AlarmRule::BeforeStop {
            trigger: north(2_000.0),
            metres: 300.0,
        }
    }

    #[test]
    fn before_stop_rings_at_trigger() {
        let mut trip = Trip::new(DEST, before_stop());
        assert!(!trip.update(fix(5_000.0, 10.0, None)).ring);
        assert!(!trip.update(fix(2_500.0, 10.0, None)).ring);
        let hit = trip.update(fix(2_200.0, 10.0, None));
        assert!(hit.ring);
        assert_eq!(hit.state, TripState::Arrived);
    }

    #[test]
    fn before_stop_rings_when_trigger_was_skipped() {
        let mut trip = Trip::new(DEST, before_stop());
        assert!(!trip.update(fix(5_000.0, 10.0, None)).ring);
        // Jumped from 5 km to 1 km: never within 300 m of the trigger.
        assert!(trip.update(fix(1_000.0, 10.0, None)).ring);
    }

    #[test]
    fn before_stop_polls_by_trigger_distance() {
        let mut trip = Trip::new(DEST, before_stop());
        let up = trip.update(fix(80_000.0, 10.0, None));
        assert_eq!(up.next_fix_in, Duration::from_secs(300));
        // 2.5 km: 200 m of slack beyond 2.3 km -> clamped to 3 s.
        let near = trip.update(fix(2_500.0, 10.0, None));
        assert_eq!(near.next_fix_in, Duration::from_secs(3));
    }

    #[test]
    fn eta_is_distance_over_speed() {
        let mut trip = Trip::new(DEST, AlarmRule::Radius { metres: 100.0 });
        let up = trip.update(fix(3_000.0, 10.0, Some(10.0)));
        let eta = up.eta.expect("eta");
        assert!((eta.as_secs_f64() - 300.0).abs() < 5.0);
        assert!(trip.update(fix(3_000.0, 10.0, Some(0.5))).eta.is_none());
        assert!(trip.update(fix(3_000.0, 10.0, None)).eta.is_none());
    }

    #[test]
    fn trip_serde_round_trip_keeps_state() {
        let mut trip = Trip::new(DEST, before_stop());
        trip.update(fix(5_000.0, 10.0, None));
        trip.update(fix(2_200.0, 10.0, None));
        let json = serde_json::to_string(&trip).unwrap();
        let back: Trip = serde_json::from_str(&json).unwrap();
        assert_eq!(back.state(), TripState::Arrived);
        assert_eq!(back.last_distance_m(), trip.last_distance_m());
        assert_eq!(back.rule, trip.rule);
        assert_eq!(back.destination, DEST);
    }

    #[test]
    fn dismissed_trip_stays_quiet() {
        let mut trip = Trip::new(DEST, AlarmRule::Radius { metres: 500.0 });
        trip.dismiss();
        assert!(!trip.update(fix(0.0, 5.0, None)).ring);
    }
}
