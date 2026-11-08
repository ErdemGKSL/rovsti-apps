//! `stops.history_*` and `stops.stats`: the trip history (a `{records}` value, the content of `history.json`),
//! its list rules, pruning, usage lines, statistics and exports.

use super::{NOW, TZ};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_geo::history::{self, format_duration, snoozes_text};
use rovsti_geo::{
    Coord, DAY_MS, History, OutcomeFilter, PRUNE_CHOICES_DAYS, TripRecord, history_io, prune_label,
    usage_text,
};
use serde::Deserialize;
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!("stops.history_list", ListReq, Value, list, list_cases),
    op!("stops.history_edit", EditReq, Value, edit, edit_cases),
    op!(
        "stops.history_prune_plan",
        PruneReq,
        Value,
        prune_plan,
        prune_cases
    ),
    op!("stops.history_usage", UsageReq, Value, usage, usage_cases),
    op!(
        "stops.history_recent",
        RecentReq,
        Value,
        recent,
        recent_cases
    ),
    op!(
        "stops.history_export",
        ExportReq,
        Value,
        export,
        export_cases
    ),
    op!("stops.history_text", TextReq, Value, text, text_cases),
    op!("stops.stats", StatsReq, Value, stats, stats_cases),
];

fn filter_of(name: Option<&str>) -> Result<OutcomeFilter, FfiError> {
    match name {
        None | Some("all") => Ok(OutcomeFilter::All),
        Some("arrived") => Ok(OutcomeFilter::Arrived),
        Some("cancelled") => Ok(OutcomeFilter::Cancelled),
        Some(other) => Err(FfiError::invalid(format!("unknown filter {other:?}"))),
    }
}

fn filter_name(f: OutcomeFilter) -> &'static str {
    match f {
        OutcomeFilter::All => "all",
        OutcomeFilter::Arrived => "arrived",
        OutcomeFilter::Cancelled => "cancelled",
    }
}

fn row(index: usize, r: &TripRecord) -> Value {
    json!({
        "index": index,
        "record": r,
        "title": r.title(),
        "summary_line": r.summary_line(),
        "duration_text": format_duration(r.duration_ms()),
        "snoozes_text": snoozes_text(r.snoozes),
    })
}

pub(super) fn sample() -> Value {
    let rec = |id: u64,
               label: &str,
               a: (f64, f64),
               b: (f64, f64),
               ended: i64,
               out: &str,
               m: f64,
               sn: u8| {
        json!({"id": id, "label": label,
            "a": {"label": "Home", "coord": {"lat": a.0, "lon": a.1}},
            "b": {"label": label, "coord": {"lat": b.0, "lon": b.1}},
            "started_ms": ended - 1_080_000, "ended_ms": ended, "outcome": out,
            "travelled_m": m, "snoozes": sn, "notice_sent": sn == 0})
    };
    json!({"records": [
        rec(4, "Work", (41.0, 28.994), (41.0, 29.006), NOW - 3_600_000, "Arrived", 3400.0, 0),
        rec(3, "Ferry", (41.0, 28.994), (41.02, 29.0), NOW - 2 * DAY_MS, "Cancelled", 120.0, 1),
        rec(2, "Work", (41.0, 28.994), (41.0, 29.006), NOW - 40 * DAY_MS, "Arrived", 3500.0, 0),
        rec(1, "Moda", (41.0, 28.994), (40.98, 29.03), NOW - 100 * DAY_MS, "Arrived", 5200.0, 2),
    ]})
}

#[derive(Deserialize)]
struct ListReq {
    history: History,
    #[serde(default)]
    query: String,
    #[serde(default)]
    filter: Option<String>,
    now_ms: i64,
}

fn list(req: ListReq) -> Result<Value, FfiError> {
    let filter = filter_of(req.filter.as_deref())?;
    let rows: Vec<Value> = req
        .history
        .matching(&req.query, filter)
        .into_iter()
        .filter_map(|i| req.history.records.get(i).map(|r| row(i, r)))
        .collect();
    let last = req.history.last_finished(req.now_ms).map(|r| {
        let i = req
            .history
            .records
            .iter()
            .position(|x| x.id == r.id)
            .unwrap_or(0);
        row(i, r)
    });
    Ok(json!({
        "rows": rows,
        "total": req.history.records.len(),
        "filter": filter_name(filter),
        "next_filter": filter_name(filter.next()),
        "filter_label": filter.label(),
        "next_filter_label": filter.next().label(),
        "last_trip": last,
        "max_records": history::MAX_RECORDS,
    }))
}

fn list_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"history": sample(), "now_ms": NOW})),
        Case::named(
            "arrived_work",
            json!({"history": sample(), "query": "work", "filter": "arrived", "now_ms": NOW}),
        ),
        Case::named("none", json!({"history": {"records": []}, "now_ms": NOW})),
        Case::named(
            "bad_filter",
            json!({"history": sample(), "filter": "x", "now_ms": NOW}),
        ),
    ]
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Edit {
    Push { record: TripRecord },
    Take { index: usize },
    PutBack { index: usize, record: TripRecord },
    Prune { days: u32, now_ms: i64 },
    Clear,
}

#[derive(Deserialize)]
struct EditReq {
    history: History,
    edit: Edit,
}

fn edit(req: EditReq) -> Result<Value, FfiError> {
    let mut h = req.history;
    let mut taken = None;
    let mut removed = 0usize;
    match req.edit {
        Edit::Push { record } => h.push(record),
        Edit::Take { index } => taken = h.take(index),
        Edit::PutBack { index, record } => h.put_back(index, record),
        Edit::Prune { days, now_ms } => removed = h.remove_older_than(now_ms, days),
        Edit::Clear => {
            removed = h.records.len();
            h.records.clear();
        }
    }
    Ok(json!({"history": h, "taken": taken, "removed": removed}))
}

fn edit_cases() -> Vec<Case> {
    let s = sample();
    vec![
        Case::new(json!({"history": s, "edit": {"kind": "take", "index": 1}})),
        Case::named(
            "take_missing",
            json!({"history": s, "edit": {"kind": "take", "index": 9}}),
        ),
        Case::named(
            "put_back",
            json!({"history": {"records": []}, "edit": {"kind": "put_back", "index": 0, "record": s["records"][0]}}),
        ),
        Case::named(
            "push",
            json!({"history": {"records": []}, "edit": {"kind": "push", "record": s["records"][1]}}),
        ),
        Case::named(
            "prune",
            json!({"history": s, "edit": {"kind": "prune", "days": 30, "now_ms": NOW}}),
        ),
        Case::named("clear", json!({"history": s, "edit": {"kind": "clear"}})),
    ]
}

#[derive(Deserialize)]
struct PruneReq {
    history: History,
    now_ms: i64,
}

/// The "Delete N trips older than D days" choices with their counts.
fn prune_plan(req: PruneReq) -> Result<Value, FfiError> {
    let choices: Vec<Value> = PRUNE_CHOICES_DAYS
        .iter()
        .map(|&days| {
            let n = req.history.older_than(req.now_ms, days);
            json!({"days": days, "count": n, "label": prune_label(n, days), "enabled": n > 0})
        })
        .collect();
    Ok(json!({"choices": choices}))
}

fn prune_cases() -> Vec<Case> {
    vec![Case::new(json!({"history": sample(), "now_ms": NOW}))]
}

#[derive(Deserialize)]
struct UsageReq {
    history: History,
    a: Coord,
    b: Coord,
    now_ms: i64,
    tz_offset_s: i32,
}

/// "Used 4 times, last 5 Nov" for a gate, when it was used.
fn usage(req: UsageReq) -> Result<Value, FfiError> {
    Ok(match req.history.usage_of(req.a, req.b) {
        Some(u) => json!({"count": u.count, "last_ms": u.last_ms,
                          "text": usage_text(u, req.now_ms, req.tz_offset_s)}),
        None => json!({"count": 0, "last_ms": null, "text": null}),
    })
}

fn usage_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"history": sample(), "a": {"lat": 41.0, "lon": 28.994}, "b": {"lat": 41.0, "lon": 29.006},
                         "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "unused",
            json!({"history": sample(), "a": {"lat": 10.0, "lon": 10.0},
                         "b": {"lat": 10.0, "lon": 10.01}, "now_ms": NOW, "tz_offset_s": TZ}),
        ),
    ]
}

#[derive(Deserialize)]
struct RecentReq {
    history: History,
    limit: usize,
}

fn recent(req: RecentReq) -> Result<Value, FfiError> {
    let pairs: Vec<Value> = req
        .history
        .recent_pairs(req.limit)
        .into_iter()
        .map(|(a, b)| json!({"a": a, "b": b}))
        .collect();
    Ok(json!({"pairs": pairs, "points": req.history.recent_points(req.limit)}))
}

fn recent_cases() -> Vec<Case> {
    vec![Case::new(json!({"history": sample(), "limit": 3}))]
}

#[derive(Deserialize)]
struct ExportReq {
    history: History,
    /// `csv` or `gpx`.
    format: String,
    /// The records to export (indices); absent for all.
    #[serde(default)]
    indices: Option<Vec<usize>>,
    tz_offset_s: i32,
    today: String,
}

fn export(req: ExportReq) -> Result<Value, FfiError> {
    let records: Vec<TripRecord> = match &req.indices {
        Some(ix) => ix
            .iter()
            .filter_map(|&i| req.history.records.get(i).cloned())
            .collect(),
        None => req.history.records.clone(),
    };
    let text = match req.format.as_str() {
        "csv" => history_io::to_csv(&records, req.tz_offset_s),
        "gpx" => history_io::to_gpx(&records),
        other => return Err(FfiError::invalid(format!("unknown format {other:?}"))),
    };
    Ok(json!({"text": text, "count": records.len(),
              "file_name": history_io::export_file_name(&req.format, &req.today)}))
}

fn export_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"history": sample(), "format": "csv", "tz_offset_s": TZ, "today": "2026-09-21"}),
        ),
        Case::named(
            "gpx_two",
            json!({"history": sample(), "format": "gpx", "indices": [0, 2],
                                      "tz_offset_s": TZ, "today": "2026-09-21"}),
        ),
        Case::named(
            "bad_format",
            json!({"history": sample(), "format": "pdf", "tz_offset_s": 0, "today": "x"}),
        ),
    ]
}

#[derive(Deserialize)]
struct TextReq {
    #[serde(default)]
    record: Option<TripRecord>,
    #[serde(default)]
    tz_offset_s: i32,
    /// A duration in milliseconds to write as "18 min".
    #[serde(default)]
    duration_ms: Option<u64>,
    #[serde(default)]
    prune_count: Option<usize>,
    #[serde(default)]
    prune_days: Option<u32>,
}

/// Small texts: a record's share text, a duration, a prune label.
fn text(req: TextReq) -> Result<Value, FfiError> {
    Ok(json!({
        "share": req.record.as_ref().map(|r| history_io::share_text(r, req.tz_offset_s)),
        "title": req.record.as_ref().map(TripRecord::title),
        "duration": req.duration_ms.map(format_duration),
        "prune": match (req.prune_count, req.prune_days) {
            (Some(n), Some(d)) => Some(prune_label(n, d)),
            _ => None,
        },
    }))
}

fn text_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"record": sample()["records"][0], "tz_offset_s": TZ, "duration_ms": 3_900_000,
                         "prune_count": 1, "prune_days": 90}),
        ),
        Case::named("empty", json!({})),
    ]
}

#[derive(Deserialize)]
struct StatsReq {
    history: History,
    now_ms: i64,
}

fn stats(req: StatsReq) -> Result<Value, FfiError> {
    let s = req.history.stats(req.now_ms);
    Ok(json!({
        "trips": s.trips,
        "arrived": s.arrived,
        "cancelled": s.cancelled,
        "total_m": s.total_m,
        "total_ms": s.total_ms,
        "avg_ms": s.avg_ms,
        "last_30_days": s.last_30_days,
        "arrived_percent": s.arrived_percent(),
        "total_text": rovsti_geo::format_distance(s.total_m),
        "avg_text": format_duration(s.avg_ms),
        "top": s.top.iter().map(|(label, n)| json!({"label": label, "count": n})).collect::<Vec<_>>(),
    }))
}

fn stats_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"history": sample(), "now_ms": NOW})),
        Case::named("empty", json!({"history": {"records": []}, "now_ms": NOW})),
    ]
}
