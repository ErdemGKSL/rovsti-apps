//! `stops.*` and `view.*`: the Stops app's domain logic.
//!
//! The trip in progress is the one stateful object (`stops.trip_*`, a handle over [`rovsti_geo::ActiveTrip`]); every
//! other op is stateless: the request carries the data (saved stops, history, preferences, A and B) and the answer
//! the new data, so the Kotlin ViewModels keep ordinary immutable models. The files of the data directory
//! (`trip.json`, `saved.json`, `history.json`, `stops-prefs.json`, `draft.json`, `gtfs.json`) are read and written
//! through `rovsti_app_core::stops::store`; a request may name `dir`, else `core.init`'s data dir is used. Every
//! read-modify-write of `trip.json` holds the store's trip lock, the same one the headless service takes.

mod history;
mod misc;
mod pick;
mod places;
mod store;
mod trip;
mod view;

use super::registry::OpSpec;
use crate::envelope::FfiError;
use crate::state;
use rovsti_geo::Coord;
use serde::Serialize;
use serde_json::{Value, json};
use std::path::PathBuf;

/// The op tables of the submodules, in the order the registry lists them.
pub(super) const TABLES: &[&[OpSpec]] = &[
    trip::OPS,
    pick::OPS,
    places::OPS,
    history::OPS,
    store::OPS,
    misc::OPS,
    view::OPS,
];

/// "now" of every fixture: 2026-09-21T17:33:20Z.
pub(super) const NOW: i64 = 1_790_000_000_000;
/// Istanbul in summer.
pub(super) const TZ: i32 = 10_800;

#[derive(Serialize)]
pub(super) struct Empty {}

pub(super) fn data_dir(dir: &Option<String>) -> Result<PathBuf, FfiError> {
    match dir {
        Some(dir) => Ok(dir.into()),
        None => state::dirs()
            .map(|d| d.data_dir.clone())
            .ok_or_else(|| FfiError::invalid("init was not called")),
    }
}

pub(super) fn coord(c: Coord) -> Value {
    json!({"lat": c.lat, "lon": c.lon})
}

/// The Istanbul gate the fixtures share: Home (41.0, 28.994) to Work (41.0, 29.006), about 1 km.
pub(super) fn gate() -> Value {
    json!({
        "a": {"label": "Home", "coord": {"lat": 41.0, "lon": 28.994}},
        "b": {"label": "Work", "coord": {"lat": 41.0, "lon": 29.006}}
    })
}
