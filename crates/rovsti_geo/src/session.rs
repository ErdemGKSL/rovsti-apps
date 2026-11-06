//! A trip in progress: the alarm state machine plus travelled distance, the
//! optional "arriving soon" notice and snoozing. Pure data, serialisable so
//! the platform layer can persist it across process death.

use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::notice;
use crate::trip::MAX_ACCURACY_M;
use crate::{
    Coord, CrossingTrip, CrossingUpdate, Diameter, DiameterError, Endpoint, Fix, NoticeRule, Side,
    TripState,
};

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
    pub rule: NoticeRule,
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
    pub update: CrossingUpdate,
    /// The fix that produced `update`.
    pub fix: Fix,
    /// Rendered notice text to send now.
    pub send_notice: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ActiveTrip {
    pub id: u64,
    pub a: Endpoint,
    pub b: Endpoint,
    pub trip: CrossingTrip,
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
    /// Metres from the fix to the line A-B.
    pub distance_m: f64,
    pub next_fix_ms: u64,
    pub high_accuracy: bool,
    pub ring: bool,
    pub title: String,
    pub text: String,
    pub notice_sent: bool,
    /// The side of the line the phone is on, when known.
    #[serde(default)]
    pub side: Option<Side>,
    /// Inside the circle.
    #[serde(default)]
    pub inside: bool,
    /// Metres from the fix to the circle (0 inside). Absent in older answers.
    #[serde(default)]
    pub circle_m: Option<f64>,
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
    /// Fails when A and B cannot form a diameter.
    pub fn new(id: u64, a: Endpoint, b: Endpoint, started_ms: i64) -> Result<Self, DiameterError> {
        let diameter = Diameter::new(a.coord, b.coord)?;
        Ok(Self {
            id,
            a,
            b,
            trip: CrossingTrip::new(diameter),
            started_ms,
            snoozes: 0,
            ring_at_ms: None,
            track: Track::default(),
            notice: None,
        })
    }

    /// "A – B".
    pub fn label(&self) -> String {
        format!("{} – {}", self.a.label, self.b.label)
    }

    pub fn on_fix(&mut self, fix: Fix, _at_ms: i64) -> FixOutcome {
        let update = self.trip.update(fix);
        self.track.add(&fix);

        let usable = fix.accuracy_m <= MAX_ACCURACY_M;
        let send_notice = match &self.notice {
            Some(n)
                if usable
                    && !n.sent
                    && n.attempts < MAX_NOTICE_ATTEMPTS
                    && self.trip.state() != TripState::Dismissed
                    && n.rule.is_met(&update, &fix) =>
            {
                Some(notice::render(&n.text_template, &self.b.label, update.eta))
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
        let label = self.label();
        let mut text = if !u.inside {
            format!("Outside the circle, {} away", format_distance(u.circle_m))
        } else {
            match u.side {
                Some(side) => format!(
                    "{} side, {} to the line {label}",
                    side.label(),
                    format_distance(u.line_m)
                ),
                None => "On the line".to_owned(),
            }
        };
        if let Some(eta) = u.eta {
            text.push_str(&format!(
                ", about {} min",
                (eta.as_secs_f64() / 60.0).ceil().max(1.0)
            ));
        }
        FixOut {
            state: u.state,
            distance_m: u.line_m,
            next_fix_ms: u.next_fix_in.as_millis().try_into().unwrap_or(u64::MAX),
            high_accuracy: u.next_fix_in <= HIGH_ACCURACY_BELOW,
            ring: u.ring,
            title: if ringing {
                format!("Crossed {label}")
            } else {
                label
            },
            text,
            notice_sent: self.notice.as_ref().is_some_and(|n| n.sent),
            side: u.side,
            inside: u.inside,
            circle_m: Some(u.circle_m),
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

    const MID: Coord = Coord::new(41.0, 29.0);

    /// Local metres east/north of MID.
    fn at(east: f64, north: f64) -> Coord {
        let lat = MID.lat + (north / crate::EARTH_RADIUS_M).to_degrees();
        let lon =
            MID.lon + (east / (crate::EARTH_RADIUS_M * MID.lat.to_radians().cos())).to_degrees();
        Coord::new(lat, lon)
    }

    fn ep(label: &str, east: f64) -> Endpoint {
        Endpoint {
            label: label.into(),
            coord: at(east, 0.0),
        }
    }

    /// A at 500 m west, B at 500 m east: left is north, right is south.
    fn trip() -> ActiveTrip {
        ActiveTrip::new(1, ep("Home", -500.0), ep("Work", 500.0), 0).unwrap()
    }

    fn fix(east: f64, north: f64, accuracy_m: f64, speed_mps: Option<f64>) -> Fix {
        Fix {
            at: at(east, north),
            accuracy_m,
            speed_mps,
        }
    }

    fn with_notice(t: &mut ActiveTrip, rule: NoticeRule) {
        t.notice = Some(NoticeState {
            chat: "chat".into(),
            text_template: notice::DEFAULT_TEMPLATE.into(),
            rule,
            sent: false,
            attempts: 0,
        });
    }

    #[test]
    fn new_rejects_degenerate_pairs() {
        let a = ep("A", 0.0);
        assert_eq!(
            ActiveTrip::new(1, a.clone(), a, 0).unwrap_err(),
            DiameterError::SamePoint
        );
        assert_eq!(
            ActiveTrip::new(1, ep("A", 0.0), ep("B", 5.0), 0).unwrap_err(),
            DiameterError::TooShort
        );
    }

    #[test]
    fn label_joins_both_ends() {
        assert_eq!(trip().label(), "Home – Work");
    }

    #[test]
    fn track_ignores_jitter_and_bad_fixes() {
        let mut t = trip();
        t.on_fix(fix(0.0, 5_000.0, 10.0, None), 0);
        t.on_fix(fix(0.0, 5_008.0, 10.0, None), 1);
        t.on_fix(fix(0.0, 9_000.0, 2_000.0, None), 2);
        assert_eq!(t.track.fixes, 2);
        assert_eq!(t.track.travelled_m, 0.0);
        t.on_fix(fix(0.0, 4_000.0, 10.0, None), 3);
        assert!((t.track.travelled_m - 1_000.0).abs() < 10.0);
        assert_eq!(t.track.fixes, 3);
    }

    #[test]
    fn with_alarm_notice_goes_out_with_the_ring_only() {
        let mut t = trip();
        with_notice(&mut t, NoticeRule::WithAlarm);
        assert!(
            t.on_fix(fix(0.0, 300.0, 10.0, None), 0)
                .send_notice
                .is_none()
        );
        assert!(
            t.on_fix(fix(0.0, 100.0, 10.0, None), 1)
                .send_notice
                .is_none()
        );
        let o = t.on_fix(fix(0.0, -100.0, 10.0, None), 2);
        assert!(o.update.ring);
        assert_eq!(
            o.send_notice.as_deref(),
            Some("Arriving at Work in about a few min.")
        );
        t.notice_result(NoticeDelivery::Sent);
        assert!(t.fix_out(&o).notice_sent);
    }

    #[test]
    fn before_circle_notice_goes_out_early_once() {
        let mut t = trip();
        with_notice(
            &mut t,
            NoticeRule::BeforeCircle {
                before: Duration::from_secs(600),
            },
        );
        // 3 km out at 10 m/s: 2.5 km to the circle edge = 250 s.
        let o = t.on_fix(fix(0.0, 3_000.0, 10.0, Some(10.0)), 0);
        assert!(!o.update.ring);
        assert!(o.send_notice.is_some());
        t.notice_result(NoticeDelivery::Sent);
        assert!(
            t.on_fix(fix(0.0, 200.0, 10.0, None), 1)
                .send_notice
                .is_none()
        );
    }

    #[test]
    fn notice_retries_stop_after_three_attempts() {
        let mut t = trip();
        with_notice(
            &mut t,
            NoticeRule::BeforeCircle {
                before: Duration::from_secs(600),
            },
        );
        for i in 0..3 {
            assert!(
                t.on_fix(fix(0.0, 100.0, 10.0, None), i)
                    .send_notice
                    .is_some()
            );
            t.notice_result(NoticeDelivery::RetryLater);
        }
        assert!(
            t.on_fix(fix(0.0, 100.0, 10.0, None), 3)
                .send_notice
                .is_none()
        );
        assert!(!t.notice.as_ref().unwrap().sent);
    }

    #[test]
    fn notice_stops_on_give_up() {
        let mut t = trip();
        with_notice(
            &mut t,
            NoticeRule::BeforeCircle {
                before: Duration::from_secs(600),
            },
        );
        assert!(
            t.on_fix(fix(0.0, 100.0, 10.0, None), 0)
                .send_notice
                .is_some()
        );
        t.notice_result(NoticeDelivery::GiveUp);
        assert!(
            t.on_fix(fix(0.0, 100.0, 10.0, None), 1)
                .send_notice
                .is_none()
        );
    }

    #[test]
    fn inaccurate_fix_sends_no_notice() {
        let mut t = trip();
        with_notice(
            &mut t,
            NoticeRule::BeforeCircle {
                before: Duration::from_secs(600),
            },
        );
        assert!(
            t.on_fix(fix(0.0, 100.0, 2_000.0, None), 0)
                .send_notice
                .is_none()
        );
    }

    #[test]
    fn snooze_clamps_and_caps() {
        let mut t = trip();
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
        let mut t = trip();
        let o = t.on_fix(fix(0.0, 120.0, 5.0, None), 0);
        let out = t.fix_out(&o);
        assert_eq!(out.text, "Left side, 120 m to the line Home – Work");
        assert_eq!(out.title, "Home – Work");
        assert_eq!(out.side, Some(Side::Left));
        assert!(out.inside && !out.ring);
        assert_eq!(out.distance_m.round(), 120.0);
    }

    #[test]
    fn fix_out_outside_the_circle_and_on_the_line() {
        let mut t = trip();
        let o = t.on_fix(fix(0.0, 1_700.0, 5.0, None), 0);
        let out = t.fix_out(&o);
        assert_eq!(out.text, "Outside the circle, 1.2 km away");
        assert!(!out.inside);
        let mut t = trip();
        let o = t.on_fix(fix(0.0, 2.0, 5.0, None), 0);
        assert_eq!(t.fix_out(&o).text, "On the line");
    }

    #[test]
    fn fix_out_rings_in_both_directions() {
        for (from, to) in [(150.0, -150.0), (-150.0, 150.0)] {
            let mut t = trip();
            t.on_fix(fix(0.0, from, 5.0, None), 0);
            let o = t.on_fix(fix(0.0, to, 5.0, None), 1);
            let out = t.fix_out(&o);
            assert!(out.ring);
            assert_eq!(out.state, TripState::Arrived);
            assert_eq!(out.title, "Crossed Home – Work");
        }
    }

    #[test]
    fn fix_out_carries_the_position_and_reads_old_answers() {
        let mut t = trip();
        let f = fix(0.0, 700.0, 12.0, None);
        let o = t.on_fix(f, 0);
        let out = t.fix_out(&o);
        let at = out.at.unwrap();
        assert_eq!((at.lat, at.lon, at.accuracy_m), (f.at.lat, f.at.lon, 12.0));
        let mut json: serde_json::Value = serde_json::to_value(&out).unwrap();
        let obj = json.as_object_mut().unwrap();
        obj.remove("at");
        obj.remove("side");
        obj.remove("inside");
        obj.remove("circle_m");
        let old: FixOut = serde_json::from_value(json).unwrap();
        assert_eq!((old.at, old.side, old.inside), (None, None, false));
        assert_eq!(old.circle_m, None);
    }

    #[test]
    fn fix_in_wire_round_trip() {
        let json = r#"{"lat":41.0,"lon":29.0,"accuracy_m":12.5,"speed_mps":null,"at_ms":7}"#;
        let f: FixIn = serde_json::from_str(json).unwrap();
        assert_eq!(f.fix().at, MID);
        assert_eq!(f.fix().speed_mps, None);
    }

    #[test]
    fn active_trip_serde_round_trip() {
        let mut t = trip();
        with_notice(&mut t, NoticeRule::WithAlarm);
        t.on_fix(fix(0.0, 5_000.0, 10.0, None), 0);
        t.on_fix(fix(0.0, 4_000.0, 10.0, None), 1);
        t.on_fix(fix(0.0, 100.0, 10.0, None), 2);
        let back: ActiveTrip = serde_json::from_str(&serde_json::to_string(&t).unwrap()).unwrap();
        assert_eq!(back.track, t.track);
        assert_eq!(back.notice, t.notice);
        assert_eq!(back.trip, t.trip);
        assert_eq!(back.trip.side(), Some(Side::Left));
        assert_eq!(back.a, t.a);
    }

    #[test]
    fn old_single_destination_trip_json_does_not_parse() {
        let json = r#"{"id":1,"label":"Kadikoy",
            "trip":{"destination":{"lat":41.0,"lon":29.0},"rule":{"Radius":{"metres":300.0}},
                    "state":"Tracking","last_distance_m":null},
            "started_ms":0,"snoozes":0,"ring_at_ms":null,
            "track":{"last":null,"travelled_m":0.0,"fixes":0},"notice":null}"#;
        assert!(serde_json::from_str::<ActiveTrip>(json).is_err());
    }
}
