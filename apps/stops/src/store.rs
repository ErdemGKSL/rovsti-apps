//! The Stops files in the app data directory, and the one lock around the
//! trip file.
//!
//! The trip is written by the foreground service's headless calls (a fix every
//! few seconds) and by the UI (start, stop, alarm buttons), possibly on
//! different threads. Every read-modify-write of `trip.json` holds [`lock`]
//! for its whole length; the functions here do not take it themselves, so a
//! caller can combine several of them in one critical section.

use std::{
    io,
    path::Path,
    sync::{Mutex, MutexGuard},
};

use rovsti_geo::{ActiveTrip, History, Outcome, TripRecord};
use rovsti_ui::storage;

/// The trip in progress. Absent when no trip runs.
pub const TRIP_FILE: &str = "trip.json";
/// Ended trips, newest first.
pub const HISTORY_FILE: &str = "history.json";
/// Saved places.
pub const SAVED_FILE: &str = "saved.json";
/// Stops preferences.
pub const PREFS_FILE: &str = "stops-prefs.json";
/// The imported GTFS feed.
pub const GTFS_FILE: &str = "gtfs.json";

/// Serialises every change of [`TRIP_FILE`] in this process.
static TRIP_LOCK: Mutex<()> = Mutex::new(());

/// Takes the trip lock. A panic in another holder does not poison it: the
/// file on disk is always whole (atomic writes), so carrying on is safe.
pub fn lock() -> MutexGuard<'static, ()> {
    TRIP_LOCK.lock().unwrap_or_else(|e| e.into_inner())
}

/// The trip in progress; `None` when there is none or the file is unreadable.
pub fn load_trip(dir: &Path) -> Option<ActiveTrip> {
    storage::load_json(dir, TRIP_FILE)
}

pub fn save_trip(dir: &Path, trip: &ActiveTrip) -> io::Result<()> {
    storage::save_json(dir, TRIP_FILE, trip)
}

/// Moves the trip in progress into the history with `outcome` and deletes
/// `trip.json`. Returns the new record, or `None` when no trip was running.
///
/// The history is written first: a crash in between leaves a trip file whose
/// id is already recorded, which is skipped here, never a lost trip.
pub fn end_trip(dir: &Path, outcome: Outcome, now_ms: i64) -> io::Result<Option<TripRecord>> {
    let Some(trip) = load_trip(dir) else {
        return Ok(None);
    };
    let mut history: History = storage::load_json(dir, HISTORY_FILE).unwrap_or_default();
    let record = TripRecord::from_session(&trip, now_ms, outcome);
    let recorded = history.records.iter().any(|r| r.id == record.id);
    if !recorded {
        history.push(record.clone());
        storage::save_json(dir, HISTORY_FILE, &history)?;
    }
    match std::fs::remove_file(dir.join(TRIP_FILE)) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::NotFound => {}
        Err(e) => return Err(e),
    }
    Ok((!recorded).then_some(record))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
pub(crate) mod tests {
    use super::*;
    use rovsti_geo::{AlarmRule, Coord, Trip};

    pub fn tmp(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("rovsti-stops-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    pub fn trip(id: u64) -> ActiveTrip {
        let trip = Trip::new(Coord::new(41.0, 29.0), AlarmRule::Radius { metres: 500.0 });
        ActiveTrip::new(id, "Kadikoy", trip, 1_000)
    }

    #[test]
    fn no_trip_loads_none_and_ends_nothing() {
        let dir = tmp("store-none");
        assert!(load_trip(&dir).is_none());
        assert!(end_trip(&dir, Outcome::Cancelled, 5).unwrap().is_none());
        assert!(!dir.join(HISTORY_FILE).exists());
    }

    #[test]
    fn ending_moves_the_trip_into_the_history() {
        let dir = tmp("store-end");
        save_trip(&dir, &trip(7)).unwrap();
        assert_eq!(load_trip(&dir).unwrap().id, 7);

        let record = end_trip(&dir, Outcome::Arrived, 9_000).unwrap().unwrap();
        assert_eq!((record.id, record.ended_ms), (7, 9_000));
        assert!(load_trip(&dir).is_none());
        let history: History = storage::load_json(&dir, HISTORY_FILE).unwrap();
        assert_eq!(history.records.len(), 1);
        assert_eq!(history.records[0].outcome, Outcome::Arrived);
    }

    #[test]
    fn a_trip_already_in_the_history_is_not_recorded_twice() {
        let dir = tmp("store-twice");
        save_trip(&dir, &trip(3)).unwrap();
        end_trip(&dir, Outcome::Cancelled, 10).unwrap();
        // A crash between the history write and the file removal.
        save_trip(&dir, &trip(3)).unwrap();
        assert!(end_trip(&dir, Outcome::Cancelled, 20).unwrap().is_none());
        assert!(load_trip(&dir).is_none());
        let history: History = storage::load_json(&dir, HISTORY_FILE).unwrap();
        assert_eq!(history.records.len(), 1);
    }
}
