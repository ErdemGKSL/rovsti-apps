//! `stops.store_*`: the files of the data directory (`rovsti_app_core::stops::store`).
//!
//! The trip file is shared with the foreground service, which reads and writes it through the headless kinds on
//! another thread. Every op that touches `trip.json` therefore holds `store::lock()` for its whole read-modify-write,
//! exactly like the headless handlers; the other files are written atomically and need no lock.

use super::{Empty, NOW, data_dir};
use crate::envelope::{ErrorKind, FfiError};
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_app_core::stops::store::{
    self, DRAFT_FILE, HISTORY_FILE, PREFS_FILE, SAVED_FILE, TRIP_FILE,
};
use rovsti_app_core::stops::types::Ends;
use rovsti_app_core::storage;
use rovsti_geo::saved_stops::load_saved_stops;
use rovsti_geo::{ActiveTrip, History, Outcome, SavedStops, StopsPrefs};
use serde::Deserialize;
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "stops.store_trip_load",
        DirReq,
        Value,
        trip_load,
        trip_load_cases
    ),
    op!(
        "stops.store_trip_save",
        TripSaveReq,
        Empty,
        trip_save,
        trip_save_cases
    ),
    op!(
        "stops.store_trip_end",
        TripEndReq,
        Value,
        trip_end,
        trip_end_cases
    ),
    op!("stops.store_read", ReadReq, Value, read, read_cases),
    op!("stops.store_write", WriteReq, Empty, write, write_cases),
    op!(
        "stops.prefs_load",
        DirReq,
        Value,
        prefs_load,
        prefs_load_cases
    ),
    op!(
        "stops.prefs_save",
        PrefsSaveReq,
        Empty,
        prefs_save,
        prefs_save_cases
    ),
];

#[derive(Deserialize)]
struct DirReq {
    #[serde(default)]
    dir: Option<String>,
}

fn trip_load(req: DirReq) -> Result<Value, FfiError> {
    let dir = data_dir(&req.dir)?;
    let _guard = store::lock();
    let trip = store::load_trip(&dir);
    Ok(json!({"trip": trip, "file": TRIP_FILE, "label": trip.as_ref().map(ActiveTrip::label)}))
}

fn trip_load_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("stops"),
        Case::named("none", json!({"dir": "$DIR/nothing-here"})).seeded("stops"),
    ]
}

#[derive(Deserialize)]
struct TripSaveReq {
    #[serde(default)]
    dir: Option<String>,
    trip: ActiveTrip,
}

fn trip_save(req: TripSaveReq) -> Result<Empty, FfiError> {
    let dir = data_dir(&req.dir)?;
    let _guard = store::lock();
    store::save_trip(&dir, &req.trip)?;
    Ok(Empty {})
}

fn trip_save_cases() -> Vec<Case> {
    let trip = std::fs::read_to_string(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../rovsti_app_core/tests/fixtures/dirs/stops/trip.json"),
    )
    .ok()
    .and_then(|t| serde_json::from_str::<Value>(&t).ok())
    .unwrap_or_default();
    vec![Case::new(json!({"dir": "$DIR", "trip": trip})).seeded("stops")]
}

#[derive(Deserialize)]
struct TripEndReq {
    #[serde(default)]
    dir: Option<String>,
    outcome: String,
    now_ms: i64,
}

/// Moves the trip in progress into the history. `record` is `null` when no trip ran (or it was already recorded).
fn trip_end(req: TripEndReq) -> Result<Value, FfiError> {
    let outcome = match req.outcome.as_str() {
        "arrived" => Outcome::Arrived,
        "cancelled" => Outcome::Cancelled,
        other => return Err(FfiError::invalid(format!("unknown outcome {other:?}"))),
    };
    let dir = data_dir(&req.dir)?;
    let _guard = store::lock();
    let record = store::end_trip(&dir, outcome, req.now_ms)?;
    Ok(json!({"record": record}))
}

fn trip_end_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "outcome": "arrived", "now_ms": NOW})).seeded("stops"),
        Case::named(
            "no_trip",
            json!({"dir": "$DIR/nothing-here", "outcome": "cancelled", "now_ms": NOW}),
        )
        .seeded("stops"),
        Case::named(
            "bad_outcome",
            json!({"dir": "$DIR", "outcome": "x", "now_ms": NOW}),
        )
        .seeded("stops"),
    ]
}

fn file_name(file: &str) -> Result<&'static str, FfiError> {
    match file {
        "saved" => Ok(SAVED_FILE),
        "history" => Ok(HISTORY_FILE),
        "draft" => Ok(DRAFT_FILE),
        other => Err(FfiError::invalid(format!(
            "unknown file {other:?} (saved, history, draft)"
        ))),
    }
}

#[derive(Deserialize)]
struct ReadReq {
    #[serde(default)]
    dir: Option<String>,
    /// `saved`, `history` or `draft`.
    file: String,
}

/// `value` is the file's content (`null` when absent). A saved-stops file in an old format reads as an empty list
/// with `discarded` set, so the caller overwrites it.
fn read(req: ReadReq) -> Result<Value, FfiError> {
    let name = file_name(&req.file)?;
    let dir = data_dir(&req.dir)?;
    if req.file == "saved" {
        let text = std::fs::read_to_string(dir.join(name)).ok();
        let loaded = load_saved_stops(text.as_deref());
        return Ok(json!({"file": name, "value": loaded.stops, "discarded": loaded.discarded}));
    }
    let value: Option<Value> = match req.file.as_str() {
        "history" => storage::load_json::<History>(&dir, name).map(|h| json!(h)),
        _ => storage::load_json::<Ends>(&dir, name).map(|e| json!(e)),
    };
    Ok(json!({"file": name, "value": value, "discarded": false}))
}

fn read_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "file": "saved"})).seeded("stops_files"),
        Case::named("history", json!({"dir": "$DIR", "file": "history"})).seeded("stops_files"),
        Case::named("draft", json!({"dir": "$DIR", "file": "draft"})).seeded("stops_files"),
        Case::named(
            "missing",
            json!({"dir": "$DIR/nothing-here", "file": "history"}),
        )
        .seeded("stops_files"),
        Case::named("unknown", json!({"dir": "$DIR", "file": "trip"})).seeded("stops_files"),
    ]
}

#[derive(Deserialize)]
struct WriteReq {
    #[serde(default)]
    dir: Option<String>,
    file: String,
    value: Value,
}

fn write(req: WriteReq) -> Result<Empty, FfiError> {
    let name = file_name(&req.file)?;
    let dir = data_dir(&req.dir)?;
    match req.file.as_str() {
        "saved" => storage::save_json(
            &dir,
            name,
            &serde_json::from_value::<SavedStops>(req.value)?,
        )?,
        "history" => {
            storage::save_json(&dir, name, &serde_json::from_value::<History>(req.value)?)?
        }
        _ => storage::save_json(&dir, name, &serde_json::from_value::<Ends>(req.value)?)?,
    }
    Ok(Empty {})
}

fn write_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "file": "saved", "value": super::pick::saved_sample()}))
            .seeded("stops_files"),
        Case::named(
            "history",
            json!({"dir": "$DIR", "file": "history", "value": super::history::sample()}),
        )
        .seeded("stops_files"),
        Case::named(
            "bad_value",
            json!({"dir": "$DIR", "file": "saved", "value": {"nope": 1}}),
        )
        .seeded("stops_files"),
    ]
}

// ---- prefs ----

fn prefs_load(req: DirReq) -> Result<Value, FfiError> {
    let dir = data_dir(&req.dir)?;
    let prefs: Option<StopsPrefs> = storage::load_json(&dir, PREFS_FILE);
    let found = prefs.is_some();
    Ok(json!({"prefs": prefs.unwrap_or_default(), "found": found}))
}

fn prefs_load_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("stops_files"),
        Case::named("missing", json!({"dir": "$DIR/nothing-here"})).seeded("stops_files"),
    ]
}

#[derive(Deserialize)]
struct PrefsSaveReq {
    #[serde(default)]
    dir: Option<String>,
    prefs: StopsPrefs,
}

fn prefs_save(req: PrefsSaveReq) -> Result<Empty, FfiError> {
    let dir = data_dir(&req.dir)?;
    storage::save_json(&dir, PREFS_FILE, &req.prefs)
        .map_err(|e| FfiError::new(ErrorKind::Io, e.to_string()))?;
    Ok(Empty {})
}

fn prefs_save_cases() -> Vec<Case> {
    vec![Case::new(json!({"dir": "$DIR", "prefs": StopsPrefs::default()})).seeded("stops_files")]
}
