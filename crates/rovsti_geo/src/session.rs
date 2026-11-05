//! A trip in progress: the alarm state machine plus travelled distance, the
//! optional "arriving soon" notice and snoozing. Pure data, serialisable so
//! the platform layer can persist it across process death.

use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::notice;
use crate::trip::MAX_ACCURACY_M;
use crate::{AlarmRule, Coord, Fix, Trip, TripState, TripUpdate};

/// Notice delivery attempts before giving up.
pub const MAX_NOTICE_ATTEMPTS: u8 = 3;
/// Snoozes allowed before the caller must dismiss.
pub const MAX_SNOOZES: u8 = 5;
const MAX_SNOOZE_MINUTES: u8 = 15;
/// "High accuracy" location requests are worth the battery below this.
const HIGH_ACCURACY_BELOW: Duration = Duration::from_secs(15);

/// Distance actually travelled, from accurate fixes only.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Track {
    pub last: Option<Coord>,
    pub travelled_m: f64,
    pub fixes: u32,
    /// Accuracy of `last`, so GPS jitter is not counted as movement.
    #[serde(default)]
    last_accuracy_m: f64,
}

impl Track {
    fn add(&mut self, fix: &Fix) {
        if fix.accuracy_m > MAX_ACCURACY_M {
            return;
        }
        self.fixes += 1;
        let Some(last) = self.last else {
            self.last = Some(fix.at);
            self.last_accuracy_m = fix.accuracy_m;
            return;
        };
        let moved = last.distance_m(fix.at);
        // Within the combined uncertainty the phone may as well be standing
        // still. The anchor stays put so slow real movement still adds up.
        if moved >= self.last_accuracy_m + fix.accuracy_m {
            self.travelled_m += moved;
            self.last = Some(fix.at);
            self.last_accuracy_m = fix.accuracy_m;
        }
    }
}

/// The "arriving soon" message to a chat.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct NoticeState {
    pub chat: String,
    pub text_template: String,
    /// When to send; may be earlier than the trip's own alarm.
    pub rule: AlarmRule,
    pub sent: bool,
    pub attempts: u8,
}

/// What the platform layer reports after trying to send the notice.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NoticeDelivery {
    Sent,
    /// Try again on a later fix (counts as an attempt).
    RetryLater,
    /// Permanent failure (no such chat, not linked): stop trying.
    GiveUp,
}

#[derive(Clone, Debug, PartialEq)]
pub struct FixOutcome {
    pub update: TripUpdate,
    /// The fix that produced `update`.
    pub fix: Fix,
    /// Rendered notice text to send now.
    pub send_notice: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ActiveTrip {
    pub id: u64,
    pub label: String,
    pub trip: Trip,
    pub started_ms: i64,
    pub snoozes: u8,
    /// When a snoozed alarm rings again.
    pub ring_at_ms: Option<i64>,
    pub track: Track,
    pub notice: Option<NoticeState>,
}

/// A location fix as delivered by the Android side.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct FixIn {
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: f64,
    pub speed_mps: Option<f64>,
    pub at_ms: i64,
}

impl FixIn {
    pub fn fix(&self) -> Fix {
        Fix {
            at: Coord::new(self.lat, self.lon),
            accuracy_m: self.accuracy_m,
            speed_mps: self.speed_mps,
        }
    }
}

/// What the Android side needs after a fix.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct FixOut {
    pub state: TripState,
    pub distance_m: f64,
    pub next_fix_ms: u64,
    pub high_accuracy: bool,
    pub ring: bool,
    pub title: String,
    pub text: String,
    pub notice_sent: bool,
    /// Where the phone was, for the map. Absent in answers from older builds.
    #[serde(default)]
    pub at: Option<FixPos>,
}

/// A position with its uncertainty, as shown on the map.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct FixPos {
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: f64,
}

impl ActiveTrip {
    pub fn new(id: u64, label: impl Into<String>, trip: Trip, started_ms: i64) -> Self {
        Self {
            id,
            label: label.into(),
            trip,
            started_ms,
            snoozes: 0,
            ring_at_ms: None,
            track: Track::default(),
            notice: None,
        }
    }

    pub fn on_fix(&mut self, fix: Fix, _at_ms: i64) -> FixOutcome {
        let update = self.trip.update(fix);
        self.track.add(&fix);

        let usable = fix.accuracy_m <= MAX_ACCURACY_M;
        let destination = self.trip.destination;
        let send_notice = match &self.notice {
            Some(n)
                if usable
                    && !n.sent
                    && n.attempts < MAX_NOTICE_ATTEMPTS
                    && self.trip.state() != TripState::Dismissed
                    && n.rule.is_met_for(destination, update.distance_m, &fix) =>
            {
                Some(notice::render(&n.text_template, &self.label, update.eta))
            }
            _ => None,
        };
        FixOutcome {
            update,
            fix,
            send_notice,
        }
    }

    pub fn notice_result(&mut self, delivered: NoticeDelivery) {
        let Some(n) = self.notice.as_mut() else {
            return;
        };
        match delivered {
            NoticeDelivery::Sent => n.sent = true,
            NoticeDelivery::RetryLater => n.attempts = n.attempts.saturating_add(1),
            NoticeDelivery::GiveUp => n.attempts = MAX_NOTICE_ATTEMPTS,
        }
    }

    /// Silences the alarm for `minutes` (clamped to 1..=15) and returns when it
    /// should ring again, or `None` once snoozed [`MAX_SNOOZES`] times.
    pub fn snooze(&mut self, now_ms: i64, minutes: u8) -> Option<i64> {
        if self.snoozes >= MAX_SNOOZES {
            return None;
        }
        self.snoozes += 1;
        let minutes = minutes.clamp(1, MAX_SNOOZE_MINUTES);
        let at = now_ms.saturating_add(i64::from(minutes) * 60_000);
        self.ring_at_ms = Some(at);
        Some(at)
    }

    pub fn dismiss(&mut self) {
        self.trip.dismiss();
        self.ring_at_ms = None;
    }

    pub fn fix_out(&self, outcome: &FixOutcome) -> FixOut {
        let u = &outcome.update;
        let ringing = u.state == TripState::Arrived;
        let mut text = format!("{} to {}", format_distance(u.distance_m), self.label);
        if let Some(eta) = u.eta {
            text.push_str(&format!(
                ", about {} min",
                (eta.as_secs_f64() / 60.0).ceil().max(1.0)
            ));
        }
        FixOut {
            state: u.state,
            distance_m: u.distance_m,
            next_fix_ms: u.next_fix_in.as_millis().try_into().unwrap_or(u64::MAX),
            high_accuracy: u.next_fix_in <= HIGH_ACCURACY_BELOW,
            ring: u.ring,
            title: if ringing {
                format!("Arriving at {}", self.label)
            } else {
                self.label.clone()
            },
            text,
            notice_sent: self.notice.as_ref().is_some_and(|n| n.sent),
            at: Some(FixPos {
                lat: outcome.fix.at.lat,
                lon: outcome.fix.at.lon,
                accuracy_m: outcome.fix.accuracy_m,
            }),
        }
    }
}

fn format_distance(metres: f64) -> String {
    if metres < 1_000.0 {
        format!("{} m", (metres / 10.0).round() as i64 * 10)
    } else {
        format!("{:.1} km", metres / 1_000.0)
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    const DEST: Coord = Coord::new(41.0, 29.0);

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

    fn trip(rule: AlarmRule) -> ActiveTrip {
        ActiveTrip::new(1, "Kadikoy", Trip::new(DEST, rule), 0)
    }

    fn with_notice(t: &mut ActiveTrip, rule: AlarmRule) {
        t.notice = Some(NoticeState {
            chat: "chat".into(),
            text_template: notice::DEFAULT_TEMPLATE.into(),
            rule,
            sent: false,
            attempts: 0,
        });
    }

    #[test]
    fn track_ignores_jitter_and_bad_fixes() {
        let mut t = trip(AlarmRule::Radius { metres: 100.0 });
        t.on_fix(fix(5_000.0, 10.0, None), 0);
        // 8 m wobble, under the 20 m combined accuracy.
        t.on_fix(fix(5_008.0, 10.0, None), 1);
        // A 2 km cell-tower fix is not counted at all.
        t.on_fix(fix(9_000.0, 2_000.0, None), 2);
        assert_eq!(t.track.fixes, 2);
        assert_eq!(t.track.travelled_m, 0.0);
        t.on_fix(fix(4_000.0, 10.0, None), 3);
        assert!((t.track.travelled_m - 1_000.0).abs() < 10.0);
        assert_eq!(t.track.fixes, 3);
    }

    #[test]
    fn notice_fires_once_before_the_alarm() {
        let mut t = trip(AlarmRule::Radius { metres: 300.0 });
        with_notice(&mut t, AlarmRule::Radius { metres: 1_000.0 });
        assert!(t.on_fix(fix(3_000.0, 10.0, None), 0).send_notice.is_none());
        let o = t.on_fix(fix(900.0, 10.0, None), 1);
        assert!(!o.update.ring);
        assert_eq!(
            o.send_notice.as_deref(),
            Some("Arriving at Kadikoy in about a few min.")
        );
        t.notice_result(NoticeDelivery::Sent);
        assert!(t.on_fix(fix(800.0, 10.0, None), 2).send_notice.is_none());
        let o = t.on_fix(fix(200.0, 10.0, None), 3);
        assert!(o.update.ring);
        assert!(o.send_notice.is_none());
        assert!(t.fix_out(&o).notice_sent);
    }

    #[test]
    fn notice_uses_eta_in_text() {
        let mut t = trip(AlarmRule::Radius { metres: 100.0 });
        with_notice(&mut t, AlarmRule::Radius { metres: 5_000.0 });
        let o = t.on_fix(fix(3_000.0, 10.0, Some(10.0)), 0);
        assert_eq!(
            o.send_notice.as_deref(),
            Some("Arriving at Kadikoy in about 5 min.")
        );
    }

    #[test]
    fn notice_retries_stop_after_three_attempts() {
        let mut t = trip(AlarmRule::Radius { metres: 100.0 });
        with_notice(&mut t, AlarmRule::Radius { metres: 1_000.0 });
        for i in 0..3 {
            assert!(t.on_fix(fix(900.0, 10.0, None), i).send_notice.is_some());
            t.notice_result(NoticeDelivery::RetryLater);
        }
        assert!(t.on_fix(fix(900.0, 10.0, None), 3).send_notice.is_none());
        assert!(!t.notice.as_ref().unwrap().sent);
    }

    #[test]
    fn notice_stops_on_give_up() {
        let mut t = trip(AlarmRule::Radius { metres: 100.0 });
        with_notice(&mut t, AlarmRule::Radius { metres: 1_000.0 });
        assert!(t.on_fix(fix(900.0, 10.0, None), 0).send_notice.is_some());
        t.notice_result(NoticeDelivery::GiveUp);
        assert!(t.on_fix(fix(800.0, 10.0, None), 1).send_notice.is_none());
    }

    #[test]
    fn inaccurate_fix_sends_no_notice() {
        let mut t = trip(AlarmRule::Radius { metres: 100.0 });
        with_notice(&mut t, AlarmRule::Radius { metres: 1_000.0 });
        assert!(t.on_fix(fix(500.0, 2_000.0, None), 0).send_notice.is_none());
    }

    #[test]
    fn snooze_clamps_and_caps() {
        let mut t = trip(AlarmRule::Radius { metres: 100.0 });
        assert_eq!(t.snooze(1_000, 0), Some(1_000 + 60_000));
        assert_eq!(t.snooze(1_000, 99), Some(1_000 + 15 * 60_000));
        assert_eq!(t.ring_at_ms, Some(1_000 + 15 * 60_000));
        for _ in 0..3 {
            assert!(t.snooze(0, 5).is_some());
        }
        assert_eq!(t.snooze(0, 5), None);
        t.dismiss();
        assert_eq!(t.ring_at_ms, None);
        assert_eq!(t.trip.state(), TripState::Dismissed);
    }

    #[test]
    fn fix_out_describes_progress() {
        let mut t = trip(AlarmRule::Radius { metres: 100.0 });
        let o = t.on_fix(fix(1_200.0, 10.0, Some(5.0)), 0);
        let out = t.fix_out(&o);
        assert_eq!(out.text, "1.2 km to Kadikoy, about 4 min");
        assert_eq!(out.title, "Kadikoy");
        assert!(!out.ring);
        assert!(out.high_accuracy || out.next_fix_ms > 15_000);
        let near = t.on_fix(fix(50.0, 5.0, None), 1);
        let out = t.fix_out(&near);
        assert!(out.ring && !out.high_accuracy);
        assert_eq!(out.title, "Arriving at Kadikoy");
        assert_eq!(out.text, "50 m to Kadikoy");
    }

    #[test]
    fn fix_out_carries_the_position_and_reads_old_answers() {
        let mut t = trip(AlarmRule::Radius { metres: 100.0 });
        let f = fix(700.0, 12.0, None);
        let o = t.on_fix(f, 0);
        let out = t.fix_out(&o);
        let at = out.at.unwrap();
        assert_eq!((at.lat, at.lon, at.accuracy_m), (f.at.lat, f.at.lon, 12.0));
        // An answer without `at` (an older build) still parses.
        let mut json: serde_json::Value = serde_json::to_value(&out).unwrap();
        json.as_object_mut().unwrap().remove("at");
        let old: FixOut = serde_json::from_value(json).unwrap();
        assert_eq!(old.at, None);
    }

    #[test]
    fn fix_in_wire_round_trip() {
        let json = r#"{"lat":41.0,"lon":29.0,"accuracy_m":12.5,"speed_mps":null,"at_ms":7}"#;
        let f: FixIn = serde_json::from_str(json).unwrap();
        assert_eq!(f.fix().at, DEST);
        assert_eq!(f.fix().speed_mps, None);
    }

    #[test]
    fn active_trip_serde_round_trip() {
        let mut t = trip(AlarmRule::Radius { metres: 300.0 });
        with_notice(&mut t, AlarmRule::Radius { metres: 1_000.0 });
        t.on_fix(fix(5_000.0, 10.0, None), 0);
        t.on_fix(fix(4_000.0, 10.0, None), 1);
        let back: ActiveTrip = serde_json::from_str(&serde_json::to_string(&t).unwrap()).unwrap();
        assert_eq!(back.track, t.track);
        assert_eq!(back.notice, t.notice);
        assert_eq!(back.trip.last_distance_m(), t.trip.last_distance_m());
    }
}
