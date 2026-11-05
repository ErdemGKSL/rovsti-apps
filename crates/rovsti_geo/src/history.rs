//! Finished trips and the statistics shown from them.

use std::collections::HashMap;

use rovsti_core::search::fold;
use serde::{Deserialize, Serialize};

use crate::{ActiveTrip, AlarmRule, Coord};

/// Records kept; older ones are dropped.
pub const MAX_RECORDS: usize = 500;
/// Destinations closer than this count as the same one in "recent".
pub const RECENT_DEDUPE_M: f64 = 100.0;
const DAY_MS: i64 = 86_400_000;
const TOP_LEN: usize = 3;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum Outcome {
    #[default]
    Arrived,
    Cancelled,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct TripRecord {
    pub id: u64,
    pub label: String,
    pub destination: Coord,
    pub rule: AlarmRule,
    pub started_ms: i64,
    pub ended_ms: i64,
    pub outcome: Outcome,
    pub travelled_m: f64,
    pub snoozes: u8,
    pub notice_sent: bool,
}

impl Default for TripRecord {
    fn default() -> Self {
        Self {
            id: 0,
            label: String::new(),
            destination: Coord::new(0.0, 0.0),
            rule: AlarmRule::Radius { metres: 500.0 },
            started_ms: 0,
            ended_ms: 0,
            outcome: Outcome::default(),
            travelled_m: 0.0,
            snoozes: 0,
            notice_sent: false,
        }
    }
}

impl TripRecord {
    /// Summarises a trip that just ended.
    pub fn from_session(trip: &ActiveTrip, ended_ms: i64, outcome: Outcome) -> Self {
        Self {
            id: trip.id,
            label: trip.label.clone(),
            destination: trip.trip.destination,
            rule: trip.trip.rule,
            started_ms: trip.started_ms,
            ended_ms,
            outcome,
            travelled_m: trip.track.travelled_m,
            snoozes: trip.snoozes,
            notice_sent: trip.notice.as_ref().is_some_and(|n| n.sent),
        }
    }

    /// Trip length in milliseconds (never negative).
    pub fn duration_ms(&self) -> u64 {
        u64::try_from(self.ended_ms.saturating_sub(self.started_ms)).unwrap_or(0)
    }
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct History {
    /// Newest first.
    pub records: Vec<TripRecord>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Stats {
    pub trips: u32,
    pub arrived: u32,
    pub cancelled: u32,
    pub total_m: f64,
    pub total_ms: u64,
    /// Mean trip length; 0 without trips.
    pub avg_ms: u64,
    /// Trips that ended within the 30 days before `now_ms`.
    pub last_30_days: u32,
    /// Most visited destinations by label, most first (at most three).
    pub top: Vec<(String, u32)>,
}

impl Stats {
    /// Share of trips that ended with the alarm, rounded; 0 without trips.
    pub fn arrived_percent(&self) -> u32 {
        if self.trips == 0 {
            return 0;
        }
        let share = f64::from(self.arrived) * 100.0 / f64::from(self.trips);
        share.round() as u32
    }
}

impl History {
    /// Adds a record at the front and drops the oldest beyond the cap.
    pub fn push(&mut self, record: TripRecord) {
        self.records.insert(0, record);
        self.records.truncate(MAX_RECORDS);
    }

    /// Distinct recent destinations, newest first.
    pub fn recent_destinations(&self, limit: usize) -> Vec<(String, Coord)> {
        let mut out: Vec<(String, Coord)> = Vec::new();
        for r in &self.records {
            if out.len() >= limit {
                break;
            }
            if out
                .iter()
                .all(|(_, c)| c.distance_m(r.destination) > RECENT_DEDUPE_M)
            {
                out.push((r.label.clone(), r.destination));
            }
        }
        out
    }

    pub fn stats(&self, now_ms: i64) -> Stats {
        let mut s = Stats::default();
        let since = now_ms.saturating_sub(30 * DAY_MS);
        // Folded label -> (display label of the newest record, count).
        let mut counts: HashMap<String, (String, u32)> = HashMap::new();
        for r in &self.records {
            s.trips += 1;
            match r.outcome {
                Outcome::Arrived => s.arrived += 1,
                Outcome::Cancelled => s.cancelled += 1,
            }
            s.total_m += r.travelled_m.max(0.0);
            s.total_ms = s.total_ms.saturating_add(r.duration_ms());
            if r.ended_ms >= since && r.ended_ms <= now_ms {
                s.last_30_days += 1;
            }
            let label = r.label.trim();
            if !label.is_empty() {
                counts
                    .entry(fold(label))
                    .and_modify(|e| e.1 += 1)
                    .or_insert_with(|| (label.to_owned(), 1));
            }
        }
        if s.trips > 0 {
            s.avg_ms = s.total_ms / u64::from(s.trips);
        }
        let mut top: Vec<(String, u32)> = counts.into_values().collect();
        top.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        top.truncate(TOP_LEN);
        s.top = top;
        s
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::{Trip, session::NoticeState};

    fn rec(id: u64, label: &str, dest: Coord, ended_ms: i64, outcome: Outcome) -> TripRecord {
        TripRecord {
            id,
            label: label.into(),
            destination: dest,
            started_ms: ended_ms - 600_000,
            ended_ms,
            outcome,
            travelled_m: 1_000.0,
            ..TripRecord::default()
        }
    }

    const A: Coord = Coord::new(41.0370, 28.9850);
    const B: Coord = Coord::new(40.9917, 29.0233);

    #[test]
    fn push_is_newest_first_and_capped() {
        let mut h = History::default();
        for i in 0..(MAX_RECORDS as u64 + 20) {
            h.push(rec(i, "x", A, i as i64, Outcome::Arrived));
        }
        assert_eq!(h.records.len(), MAX_RECORDS);
        assert_eq!(h.records[0].id, MAX_RECORDS as u64 + 19);
        assert_eq!(h.records.last().unwrap().id, 20);
    }

    #[test]
    fn recent_destinations_dedupe_within_100_m() {
        let mut h = History::default();
        h.push(rec(1, "Old A", A, 1, Outcome::Arrived));
        h.push(rec(2, "B", B, 2, Outcome::Cancelled));
        // About 55 m from A.
        h.push(rec(
            3,
            "New A",
            Coord::new(A.lat + 0.0005, A.lon),
            3,
            Outcome::Arrived,
        ));
        let recent = h.recent_destinations(10);
        assert_eq!(recent.len(), 2);
        assert_eq!(recent[0].0, "New A");
        assert_eq!(recent[1].0, "B");
        assert_eq!(h.recent_destinations(1).len(), 1);
        assert!(h.recent_destinations(0).is_empty());
    }

    #[test]
    fn stats_sums_and_window() {
        let now = 100 * DAY_MS;
        let mut h = History::default();
        h.push(rec(1, "Kadıköy", B, now - 40 * DAY_MS, Outcome::Arrived));
        h.push(rec(2, "Kadikoy", B, now - 29 * DAY_MS, Outcome::Arrived));
        h.push(rec(3, "Taksim", A, now - DAY_MS, Outcome::Cancelled));
        h.push(rec(4, "Taksim", A, now - 1000, Outcome::Arrived));
        h.push(rec(5, "Home", A, now - 500, Outcome::Arrived));
        let s = h.stats(now);
        assert_eq!((s.trips, s.arrived, s.cancelled), (5, 4, 1));
        assert_eq!(s.total_m, 5_000.0);
        assert_eq!(s.total_ms, 5 * 600_000);
        assert_eq!(s.avg_ms, 600_000);
        assert_eq!(s.last_30_days, 4);
        // Ties break alphabetically; folded labels merge.
        assert_eq!(
            s.top,
            vec![
                ("Kadikoy".to_owned(), 2),
                ("Taksim".to_owned(), 2),
                ("Home".to_owned(), 1)
            ]
        );
    }

    #[test]
    fn arrived_percent_rounds() {
        let mut s = Stats::default();
        assert_eq!(s.arrived_percent(), 0);
        s.trips = 3;
        s.arrived = 2;
        assert_eq!(s.arrived_percent(), 67);
        s.arrived = 3;
        assert_eq!(s.arrived_percent(), 100);
    }

    #[test]
    fn stats_of_nothing() {
        let s = History::default().stats(5);
        assert_eq!(s, Stats::default());
    }

    #[test]
    fn from_session_copies_the_trip() {
        let mut t = ActiveTrip::new(
            7,
            "Home",
            Trip::new(A, AlarmRule::Radius { metres: 300.0 }),
            1_000,
        );
        t.snoozes = 2;
        t.track.travelled_m = 1234.0;
        t.notice = Some(NoticeState {
            chat: "c".into(),
            text_template: "t".into(),
            rule: AlarmRule::Radius { metres: 900.0 },
            sent: true,
            attempts: 1,
        });
        let r = TripRecord::from_session(&t, 61_000, Outcome::Arrived);
        assert_eq!((r.id, r.label.as_str(), r.snoozes), (7, "Home", 2));
        assert_eq!(r.destination, A);
        assert_eq!(r.rule, AlarmRule::Radius { metres: 300.0 });
        assert_eq!(r.duration_ms(), 60_000);
        assert_eq!(r.travelled_m, 1234.0);
        assert!(r.notice_sent);
    }

    #[test]
    fn negative_duration_is_zero_and_json_defaults() {
        let r = TripRecord {
            started_ms: 10,
            ended_ms: 5,
            ..TripRecord::default()
        };
        assert_eq!(r.duration_ms(), 0);
        let h: History = serde_json::from_str("{}").unwrap();
        assert!(h.records.is_empty());
        let h: History = serde_json::from_str(r#"{"records":[{"label":"x"}]}"#).unwrap();
        assert_eq!(h.records[0].label, "x");
    }
}
