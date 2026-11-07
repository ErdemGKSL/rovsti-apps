//! Finished trips and the statistics shown from them.

use std::collections::HashMap;

use rovsti_core::search::fold;
use serde::{Deserialize, Serialize};

use crate::{ActiveTrip, Coord, Endpoint};

/// Records kept; older ones are dropped.
pub const MAX_RECORDS: usize = 500;
/// Points closer than this count as the same one in "recent".
pub const RECENT_DEDUPE_M: f64 = 100.0;
/// A finished trip is offered as the "last trip" card for this long.
pub const LAST_TRIP_CARD_MS: i64 = 12 * 3_600_000;
/// One day in milliseconds.
pub const DAY_MS: i64 = 86_400_000;
/// Ages offered by "delete trips older than".
pub const PRUNE_CHOICES_DAYS: [u32; 2] = [30, 90];
const TOP_LEN: usize = 3;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum Outcome {
    #[default]
    Arrived,
    Cancelled,
}

/// Which finished trips the history list shows.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum OutcomeFilter {
    #[default]
    All,
    Arrived,
    Cancelled,
}

impl OutcomeFilter {
    pub fn label(self) -> &'static str {
        match self {
            Self::All => "All",
            Self::Arrived => "Arrived",
            Self::Cancelled => "Cancelled",
        }
    }

    /// The next filter in the chip cycle.
    pub fn next(self) -> Self {
        match self {
            Self::All => Self::Arrived,
            Self::Arrived => Self::Cancelled,
            Self::Cancelled => Self::All,
        }
    }

    fn admits(self, outcome: Outcome) -> bool {
        match self {
            Self::All => true,
            Self::Arrived => outcome == Outcome::Arrived,
            Self::Cancelled => outcome == Outcome::Cancelled,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct TripRecord {
    pub id: u64,
    pub label: String,
    /// Both ends of the diameter. Absent in records from before the crossing
    /// trigger.
    pub a: Option<Endpoint>,
    pub b: Option<Endpoint>,
    /// The single destination of a legacy record; never written any more.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub destination: Option<Coord>,
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
            a: None,
            b: None,
            destination: None,
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
            label: trip.label(),
            a: Some(trip.a.clone()),
            b: Some(trip.b.clone()),
            destination: None,
            started_ms: trip.started_ms,
            ended_ms,
            outcome,
            travelled_m: trip.track.travelled_m,
            snoozes: trip.snoozes,
            notice_sent: trip.notice.as_ref().is_some_and(|n| n.sent),
        }
    }

    /// Both ends, when the record has them (not for legacy records).
    pub fn pair(&self) -> Option<(Endpoint, Endpoint)> {
        Some((self.a.clone()?, self.b.clone()?))
    }

    /// Trip length in milliseconds (never negative).
    pub fn duration_ms(&self) -> u64 {
        u64::try_from(self.ended_ms.saturating_sub(self.started_ms)).unwrap_or(0)
    }
}

/// "under 1 min", "18 min", "1 h 05 min".
pub fn format_duration(ms: u64) -> String {
    let minutes = ms / 60_000;
    match minutes {
        0 => "under 1 min".to_owned(),
        1..=59 => format!("{minutes} min"),
        _ => format!("{} h {:02} min", minutes / 60, minutes % 60),
    }
}

/// "1 snooze", "3 snoozes"; `None` without snoozes.
pub fn snoozes_text(n: u8) -> Option<String> {
    match n {
        0 => None,
        1 => Some("1 snooze".to_owned()),
        n => Some(format!("{n} snoozes")),
    }
}

impl TripRecord {
    /// "A – B" from the end labels (empty ones read Point A / Point B), the
    /// label of a legacy record, or "Trip".
    pub fn title(&self) -> String {
        if self.a.is_some() || self.b.is_some() {
            let name = |e: &Option<Endpoint>, fallback: &str| {
                e.as_ref()
                    .map(|e| e.label.trim())
                    .filter(|l| !l.is_empty())
                    .unwrap_or(fallback)
                    .to_owned()
            };
            return format!(
                "{} \u{2013} {}",
                name(&self.a, "Point A"),
                name(&self.b, "Point B")
            );
        }
        let label = self.label.trim();
        if label.is_empty() {
            "Trip".to_owned()
        } else {
            label.to_owned()
        }
    }

    /// "18 min \u{b7} 3.4 km \u{b7} 1 snooze" (no snooze part at 0).
    pub fn summary_line(&self) -> String {
        let mut parts = vec![
            format_duration(self.duration_ms()),
            crate::session::format_distance(self.travelled_m.max(0.0)),
        ];
        parts.extend(snoozes_text(self.snoozes));
        parts.join(" \u{b7} ")
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

/// How often a route was driven and when it last ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Usage {
    pub count: u32,
    pub last_ms: i64,
}

/// "Used once, today", "Used 4 times, last 5 Nov" (year added when it is not
/// the current one). Local time is `tz_offset_s` east of UTC.
pub fn usage_text(u: Usage, now_ms: i64, tz_offset_s: i32) -> String {
    use rovsti_core::contact_dates::{local_day, local_ymd};
    let times = if u.count == 1 {
        "once".to_owned()
    } else {
        format!("{} times", u.count)
    };
    let age = local_day(now_ms, tz_offset_s) - local_day(u.last_ms, tz_offset_s);
    let last = match age {
        0 => "today".to_owned(),
        1 => "yesterday".to_owned(),
        _ => {
            let (y, m, d) = local_ymd(u.last_ms, tz_offset_s);
            let month = crate::history_io::month_name(m);
            if y == local_ymd(now_ms, tz_offset_s).0 {
                format!("{d} {month}")
            } else {
                format!("{d} {month} {y}")
            }
        }
    };
    if u.count == 1 {
        format!("Used {times}, {last}")
    } else {
        format!("Used {times}, last {last}")
    }
}

/// "Delete 12 trips older than 30 days", "Delete 1 trip older than 90 days".
pub fn prune_label(n: usize, days: u32) -> String {
    let noun = if n == 1 { "trip" } else { "trips" };
    format!("Delete {n} {noun} older than {days} days")
}

impl History {
    fn cutoff_ms(now_ms: i64, days: u32) -> i64 {
        now_ms.saturating_sub(i64::from(days).saturating_mul(DAY_MS))
    }

    /// Records that ended strictly before `days` days ago.
    pub fn older_than(&self, now_ms: i64, days: u32) -> usize {
        let cutoff = Self::cutoff_ms(now_ms, days);
        self.records.iter().filter(|r| r.ended_ms < cutoff).count()
    }

    /// Drops those records, keeping the order of the rest; returns how many.
    pub fn remove_older_than(&mut self, now_ms: i64, days: u32) -> usize {
        let cutoff = Self::cutoff_ms(now_ms, days);
        let before = self.records.len();
        self.records.retain(|r| r.ended_ms >= cutoff);
        before - self.records.len()
    }

    /// How often the pair a -> b (either direction, each end within
    /// [`RECENT_DEDUPE_M`]) was driven. Legacy records never match.
    pub fn usage_of(&self, a: Coord, b: Coord) -> Option<Usage> {
        let near = |x: Coord, y: Coord| x.distance_m(y) <= RECENT_DEDUPE_M;
        let mut count = 0u32;
        let mut last_ms = i64::MIN;
        for r in &self.records {
            let Some((ra, rb)) = r.pair() else { continue };
            let (ra, rb) = (ra.coord, rb.coord);
            if (near(ra, a) && near(rb, b)) || (near(ra, b) && near(rb, a)) {
                count += 1;
                last_ms = last_ms.max(r.ended_ms);
            }
        }
        (count > 0).then_some(Usage { count, last_ms })
    }

    /// The newest record when it ended at most [`LAST_TRIP_CARD_MS`] before
    /// `now_ms` (a future end time does not count).
    pub fn last_finished(&self, now_ms: i64) -> Option<&TripRecord> {
        let r = self.records.first()?;
        let age = now_ms.checked_sub(r.ended_ms)?;
        (0..=LAST_TRIP_CARD_MS).contains(&age).then_some(r)
    }

    /// Adds a record at the front and drops the oldest beyond the cap.
    pub fn push(&mut self, record: TripRecord) {
        self.records.insert(0, record);
        self.records.truncate(MAX_RECORDS);
    }

    /// Removes and returns the record at `index`; `None` when out of range.
    pub fn take(&mut self, index: usize) -> Option<TripRecord> {
        (index < self.records.len()).then(|| self.records.remove(index))
    }

    /// Re-inserts a record taken earlier (undo) at `index`, clamped to the
    /// end. Does nothing when an equal record is already present.
    pub fn put_back(&mut self, index: usize, record: TripRecord) {
        if self.records.contains(&record) {
            return;
        }
        self.records.insert(index.min(self.records.len()), record);
        self.records.truncate(MAX_RECORDS);
    }

    /// Indices of the records that pass `filter` and whose label or A/B
    /// labels contain the accent- and case-folded `query`; newest first
    /// (the storage order). A blank query matches every record.
    pub fn matching(&self, query: &str, filter: OutcomeFilter) -> Vec<usize> {
        let q = fold(query.trim());
        self.records
            .iter()
            .enumerate()
            .filter(|(_, r)| filter.admits(r.outcome))
            .filter(|(_, r)| {
                q.is_empty()
                    || [
                        Some(&r.label),
                        r.a.as_ref().map(|e| &e.label),
                        r.b.as_ref().map(|e| &e.label),
                    ]
                    .into_iter()
                    .flatten()
                    .any(|l| fold(l).contains(&q))
            })
            .map(|(i, _)| i)
            .collect()
    }

    /// Distinct recent (A, B) pairs, newest first. Legacy single-destination
    /// records have no pair and are skipped.
    pub fn recent_pairs(&self, limit: usize) -> Vec<(Endpoint, Endpoint)> {
        let mut out: Vec<(Endpoint, Endpoint)> = Vec::new();
        for r in &self.records {
            if out.len() >= limit {
                break;
            }
            let (Some(a), Some(b)) = (&r.a, &r.b) else {
                continue;
            };
            let same = |x: &Endpoint, y: &Endpoint| x.coord.distance_m(y.coord) <= RECENT_DEDUPE_M;
            if out.iter().all(|(oa, ob)| !(same(oa, a) && same(ob, b))) {
                out.push((a.clone(), b.clone()));
            }
        }
        out
    }

    /// Distinct recent points (both ends of new records, destinations of
    /// legacy ones), newest first.
    pub fn recent_points(&self, limit: usize) -> Vec<Endpoint> {
        let mut out: Vec<Endpoint> = Vec::new();
        let mut add = |label: &str, coord: Coord| {
            if out.len() < limit
                && out
                    .iter()
                    .all(|p| p.coord.distance_m(coord) > RECENT_DEDUPE_M)
            {
                out.push(Endpoint {
                    label: label.to_owned(),
                    coord,
                });
            }
        };
        for r in &self.records {
            match (&r.a, &r.b, r.destination) {
                (Some(a), Some(b), _) => {
                    add(&a.label, a.coord);
                    add(&b.label, b.coord);
                }
                (_, _, Some(d)) => add(&r.label, d),
                _ => {}
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
    use crate::{NoticeRule, session::NoticeState};

    fn named(label: &str) -> Endpoint {
        Endpoint {
            label: label.into(),
            coord: Coord::new(41.0, 29.0),
        }
    }

    fn pair_record(a: &str, b: &str) -> TripRecord {
        TripRecord {
            a: Some(named(a)),
            b: Some(named(b)),
            started_ms: 0,
            ended_ms: 18 * 60_000,
            travelled_m: 3_400.0,
            ..TripRecord::default()
        }
    }

    #[test]
    fn format_duration_edges() {
        assert_eq!(format_duration(0), "under 1 min");
        assert_eq!(format_duration(59_999), "under 1 min");
        assert_eq!(format_duration(60_000), "1 min");
        assert_eq!(format_duration(59 * 60_000), "59 min");
        assert_eq!(format_duration(60 * 60_000), "1 h 00 min");
        assert_eq!(format_duration(65 * 60_000), "1 h 05 min");
    }

    #[test]
    fn snoozes_text_pluralises() {
        assert_eq!(snoozes_text(0), None);
        assert_eq!(snoozes_text(1).as_deref(), Some("1 snooze"));
        assert_eq!(snoozes_text(3).as_deref(), Some("3 snoozes"));
    }

    #[test]
    fn titles() {
        assert_eq!(pair_record("Home", "Work").title(), "Home \u{2013} Work");
        assert_eq!(pair_record("", " ").title(), "Point A \u{2013} Point B");
        let legacy = TripRecord {
            label: "Kadikoy".into(),
            destination: Some(Coord::new(41.0, 29.0)),
            ..TripRecord::default()
        };
        assert_eq!(legacy.title(), "Kadikoy");
        assert_eq!(TripRecord::default().title(), "Trip");
    }

    #[test]
    fn summary_lines() {
        let mut r = pair_record("A", "B");
        assert_eq!(r.summary_line(), "18 min \u{b7} 3.4 km");
        r.snoozes = 2;
        assert_eq!(r.summary_line(), "18 min \u{b7} 3.4 km \u{b7} 2 snoozes");
    }

    #[test]
    fn last_finished_window() {
        let now = 100 * 3_600_000;
        let rec = |ended_ms| TripRecord {
            ended_ms,
            ..TripRecord::default()
        };
        let mut h = History::default();
        assert!(h.last_finished(now).is_none());
        h.push(rec(now - 60_000));
        assert!(h.last_finished(now).is_some());
        h.records[0].ended_ms = now - 13 * 3_600_000;
        assert!(h.last_finished(now).is_none());
        h.records[0].ended_ms = now - LAST_TRIP_CARD_MS;
        assert!(h.last_finished(now).is_some());
        h.records[0].ended_ms = now + 1;
        assert!(h.last_finished(now).is_none());
    }

    #[test]
    fn take_and_put_back_round_trip_the_order() {
        let mut h = History::default();
        for id in 1..=3 {
            h.push(TripRecord {
                id,
                ..TripRecord::default()
            });
        }
        let before = h.clone();
        let r = h.take(1).unwrap();
        assert_eq!(h.records.len(), 2);
        h.put_back(1, r);
        assert_eq!(h, before);
    }

    #[test]
    fn take_out_of_range_is_none_and_put_back_clamps() {
        let mut h = History::default();
        assert!(h.take(0).is_none());
        h.push(TripRecord {
            id: 1,
            ..TripRecord::default()
        });
        assert!(h.take(5).is_none());
        h.put_back(
            99,
            TripRecord {
                id: 2,
                ..TripRecord::default()
            },
        );
        assert_eq!(h.records[1].id, 2);
    }

    #[test]
    fn double_put_back_is_a_no_op() {
        let mut h = History::default();
        let r = TripRecord {
            id: 7,
            ..TripRecord::default()
        };
        h.put_back(0, r.clone());
        h.put_back(0, r);
        assert_eq!(h.records.len(), 1);
    }

    #[test]
    fn take_drops_points_only_that_record_had() {
        let ep = |label: &str, lon: f64| Endpoint {
            label: label.into(),
            coord: Coord::new(41.0, lon),
        };
        let mut h = History::default();
        h.push(TripRecord {
            id: 1,
            a: Some(ep("Home", 29.0)),
            b: Some(ep("Work", 29.1)),
            ..TripRecord::default()
        });
        h.push(TripRecord {
            id: 2,
            a: Some(ep("Home", 29.0)),
            b: Some(ep("Gym", 29.2)),
            ..TripRecord::default()
        });
        assert_eq!(h.recent_points(10).len(), 3);
        let r = h.take(1).unwrap();
        assert_eq!(r.id, 1);
        let labels: Vec<_> = h.recent_points(10).into_iter().map(|p| p.label).collect();
        assert_eq!(labels, ["Home", "Gym"]);
        assert_eq!(h.recent_pairs(10).len(), 1);
    }

    fn rec(id: u64, label: &str, dest: Coord, ended_ms: i64, outcome: Outcome) -> TripRecord {
        TripRecord {
            id,
            label: label.into(),
            destination: Some(dest),
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

    fn ep(label: &str, coord: Coord) -> Endpoint {
        Endpoint {
            label: label.into(),
            coord,
        }
    }

    fn pair_rec(id: u64, a: Endpoint, b: Endpoint, ended_ms: i64) -> TripRecord {
        TripRecord {
            id,
            label: format!("{} – {}", a.label, b.label),
            a: Some(a),
            b: Some(b),
            ended_ms,
            ..TripRecord::default()
        }
    }

    #[test]
    fn recent_points_dedupe_within_100_m() {
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
        let recent = h.recent_points(10);
        assert_eq!(recent.len(), 2);
        assert_eq!(recent[0].label, "New A");
        assert_eq!(recent[1].label, "B");
        assert_eq!(h.recent_points(1).len(), 1);
        assert!(h.recent_points(0).is_empty());
    }

    #[test]
    fn recent_points_take_both_ends_of_new_records() {
        let mut h = History::default();
        h.push(rec(1, "Legacy", A, 1, Outcome::Arrived));
        h.push(pair_rec(
            2,
            ep("Home", B),
            ep("Work", Coord::new(41.1, 29.1)),
            2,
        ));
        let labels: Vec<_> = h.recent_points(10).into_iter().map(|p| p.label).collect();
        assert_eq!(labels, ["Home", "Work", "Legacy"]);
    }

    #[test]
    fn recent_pairs_skip_legacy_and_dedupe() {
        let mut h = History::default();
        let c = Coord::new(41.1, 29.1);
        h.push(rec(1, "Legacy", A, 1, Outcome::Arrived));
        h.push(pair_rec(2, ep("Home", A), ep("Work", c), 2));
        h.push(pair_rec(3, ep("Work", c), ep("Home", A), 3));
        h.push(pair_rec(4, ep("Home 2", A), ep("Work 2", c), 4));
        let pairs = h.recent_pairs(10);
        // The reverse pair is distinct; the repeat of Home-Work collapses.
        assert_eq!(pairs.len(), 2);
        assert_eq!(pairs[0].0.label, "Home 2");
        assert_eq!(pairs[1].0.label, "Work");
        assert_eq!(h.recent_pairs(1).len(), 1);
    }

    #[test]
    fn old_record_json_still_loads() {
        let json = r#"{"records":[{"id":3,"label":"Kadikoy",
            "destination":{"lat":40.99,"lon":29.02},
            "rule":{"Radius":{"metres":300.0}},
            "started_ms":1,"ended_ms":2,"outcome":"Arrived"}]}"#;
        let h: History = serde_json::from_str(json).unwrap();
        let r = &h.records[0];
        assert_eq!(r.destination, Some(Coord::new(40.99, 29.02)));
        assert!(r.a.is_none() && r.b.is_none());
        assert_eq!(h.recent_points(5)[0].label, "Kadikoy");
        assert!(h.recent_pairs(5).is_empty());
        // New records never write the legacy field.
        let new = pair_rec(1, ep("A", A), ep("B", B), 2);
        assert!(!serde_json::to_string(&new).unwrap().contains("destination"));
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
    fn matching_filters_and_folds() {
        let mut h = History::default();
        h.push(rec(1, "Kadıköy", B, 1, Outcome::Arrived));
        h.push(pair_rec(2, ep("Home", A), ep("İş Yeri", B), 2));
        let mut c = rec(3, "Taksim", A, 3, Outcome::Cancelled);
        c.a = Some(ep("Pier", A));
        c.b = Some(ep("Cafe Éclair", B));
        h.push(c);
        // Newest first: ids 3, 2, 1 at indices 0, 1, 2.
        assert_eq!(h.matching("", OutcomeFilter::All), [0, 1, 2]);
        assert_eq!(h.matching("  ", OutcomeFilter::All), [0, 1, 2]);
        assert_eq!(h.matching("", OutcomeFilter::Arrived), [1, 2]);
        assert_eq!(h.matching("", OutcomeFilter::Cancelled), [0]);
        assert_eq!(h.matching("kadikoy", OutcomeFilter::All), [2]);
        assert_eq!(h.matching("ECLAIR", OutcomeFilter::All), [0]);
        assert_eq!(h.matching("home", OutcomeFilter::All), [1]);
        assert_eq!(
            h.matching("home", OutcomeFilter::Cancelled),
            Vec::<usize>::new()
        );
        assert!(h.matching("zzz", OutcomeFilter::All).is_empty());
    }

    #[test]
    fn filter_cycles() {
        let mut f = OutcomeFilter::All;
        let mut seen = vec![f.label()];
        for _ in 0..3 {
            f = f.next();
            seen.push(f.label());
        }
        assert_eq!(seen, ["All", "Arrived", "Cancelled", "All"]);
    }

    #[test]
    fn pair_needs_both_ends() {
        let r = pair_rec(1, ep("A", A), ep("B", B), 1);
        assert_eq!(r.pair().unwrap().1.label, "B");
        assert!(rec(2, "x", A, 1, Outcome::Arrived).pair().is_none());
        let half = TripRecord {
            a: Some(ep("A", A)),
            ..TripRecord::default()
        };
        assert!(half.pair().is_none());
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
            ep("Home", A),
            ep("Work", Coord::new(41.0370, 28.9950)),
            1_000,
        )
        .unwrap();
        t.snoozes = 2;
        t.track.travelled_m = 1234.0;
        t.notice = Some(NoticeState {
            chat: "c".into(),
            text_template: "t".into(),
            rule: NoticeRule::WithAlarm,
            sent: true,
            attempts: 1,
        });
        let r = TripRecord::from_session(&t, 61_000, Outcome::Arrived);
        assert_eq!((r.id, r.label.as_str(), r.snoozes), (7, "Home – Work", 2));
        assert_eq!(r.a.as_ref().unwrap().coord, A);
        assert_eq!(r.b.as_ref().unwrap().label, "Work");
        assert_eq!(r.destination, None);
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

    #[test]
    fn older_than_and_remove() {
        let now = 100 * DAY_MS;
        let mut h = History::default();
        assert_eq!(h.older_than(now, 30), 0);
        assert_eq!(h.remove_older_than(now, 30), 0);
        let cutoff = now - 30 * DAY_MS;
        for (id, ended) in [(1, now + DAY_MS), (2, cutoff), (3, cutoff - 1), (4, 0)] {
            h.records.push(rec(id, "x", A, ended, Outcome::Arrived));
        }
        assert_eq!(h.older_than(now, 30), 2);
        assert_eq!(h.older_than(now, 90), 1);
        assert_eq!(h.remove_older_than(now, 30), 2);
        let ids: Vec<u64> = h.records.iter().map(|r| r.id).collect();
        assert_eq!(ids, [1, 2]);
        assert_eq!(h.older_than(i64::MIN, 90), 0);
    }

    #[test]
    fn usage_of_both_directions() {
        let far = Coord::new(41.0370 + 0.0018, 28.9850); // ~200 m off
        let mut h = History::default();
        h.push(pair_rec(1, ep("a", A), ep("b", B), 10));
        h.push(pair_rec(2, ep("b", B), ep("a", A), 50));
        h.push(pair_rec(3, ep("a", A), ep("b", B), 30));
        h.push(rec(4, "legacy", B, 99, Outcome::Arrived));
        assert_eq!(
            h.usage_of(A, B),
            Some(Usage {
                count: 3,
                last_ms: 50
            })
        );
        assert_eq!(h.usage_of(B, A), h.usage_of(A, B));
        assert_eq!(h.usage_of(far, B), None);
        assert_eq!(History::default().usage_of(A, B), None);
    }

    #[test]
    fn usage_text_variants() {
        let tz = 3 * 3600;
        let now = 1_762_500_000_000; // 2025-11-07 07:20 UTC
        let at = |days_ago: i64| now - days_ago * DAY_MS;
        let u = |count, last_ms| Usage { count, last_ms };
        assert_eq!(usage_text(u(1, now), now, tz), "Used once, today");
        assert_eq!(
            usage_text(u(4, at(1)), now, tz),
            "Used 4 times, last yesterday"
        );
        assert_eq!(usage_text(u(4, at(2)), now, tz), "Used 4 times, last 5 Nov");
        assert_eq!(
            usage_text(u(2, at(200)), now, tz),
            "Used 2 times, last 21 Apr"
        );
        assert_eq!(
            usage_text(u(2, at(400)), now, tz),
            "Used 2 times, last 3 Oct 2024"
        );
        // Local midnight: 23:30 UTC the day before is already today at +03:00.
        let late = now - 8 * 3_600_000 + 30 * 60_000; // 2025-11-06 23:50 UTC
        assert_eq!(usage_text(u(1, late), now, tz), "Used once, today");
    }

    #[test]
    fn prune_label_plurals() {
        assert_eq!(prune_label(12, 30), "Delete 12 trips older than 30 days");
        assert_eq!(prune_label(1, 90), "Delete 1 trip older than 90 days");
    }
}
