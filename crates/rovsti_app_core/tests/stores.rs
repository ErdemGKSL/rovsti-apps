//! Files written by the code before it moved into this crate must still load
//! and round-trip. `tests/fixtures/{trip,trip.legacy,history}.json` were written
//! by the pre-move Stops store; the prefs golden is shared with `rovsti_core`.

use std::{fs, path::PathBuf};

use rovsti_app_core::{
    stops::store::{self, HISTORY_FILE, LEGACY_TRIP_FILE, TRIP_FILE, test_support::tmp},
    storage::{self, Origin},
};
use rovsti_geo::{History, Outcome};

fn fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}

fn prefs_fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../rovsti_core/tests/fixtures/prefs")
        .join(name)
}

#[test]
fn the_trip_fixture_loads_and_saves_byte_for_byte() {
    let dir = tmp("fx-trip");
    fs::copy(fixture("trip.json"), dir.join(TRIP_FILE)).expect("copy");
    let trip = store::load_trip(&dir).expect("trip");
    assert_eq!(trip.id, 7);
    store::save_trip(&dir, &trip).expect("save");
    assert_eq!(
        fs::read(dir.join(TRIP_FILE)).expect("read"),
        fs::read(fixture("trip.json")).expect("fixture"),
    );
}

#[test]
fn the_legacy_fixture_is_moved_aside() {
    let dir = tmp("fx-legacy");
    fs::copy(fixture("trip.legacy.json"), dir.join(TRIP_FILE)).expect("copy");
    assert!(store::load_trip(&dir).is_none());
    assert!(!dir.join(TRIP_FILE).exists());
    assert_eq!(
        fs::read(dir.join(LEGACY_TRIP_FILE)).expect("read"),
        fs::read(fixture("trip.legacy.json")).expect("fixture"),
    );
}

#[test]
fn the_history_fixture_loads_and_round_trips() {
    let dir = tmp("fx-history");
    fs::copy(fixture("history.json"), dir.join(HISTORY_FILE)).expect("copy");
    let history: History = storage::load_json(&dir, HISTORY_FILE).expect("history");
    assert_eq!(history.records.len(), 2);
    assert_eq!(history.records[0].outcome, Outcome::Cancelled);
    assert_eq!(history.records[1].outcome, Outcome::Arrived);
    storage::save_json(&dir, HISTORY_FILE, &history).expect("save");
    assert_eq!(
        fs::read(dir.join(HISTORY_FILE)).expect("read"),
        fs::read(fixture("history.json")).expect("fixture"),
    );
}

#[test]
fn the_shared_prefs_goldens_load_through_the_moved_loader() {
    let dir = tmp("fx-prefs");
    fs::copy(
        prefs_fixture("v2_typical.json"),
        dir.join(storage::PREFS_FILE),
    )
    .expect("copy");
    assert_eq!(storage::load_prefs(&dir).origin, Origin::Current);
    fs::copy(
        prefs_fixture("v1_typical.json"),
        dir.join(storage::PREFS_FILE),
    )
    .expect("copy");
    assert_eq!(storage::load_prefs(&dir).origin, Origin::UpgradedFromV1);
}

#[test]
fn the_shared_backup_goldens_parse() {
    let current = storage::Prefs::default();
    let valid = fs::read_to_string(prefs_fixture("backup_valid.json")).expect("read");
    assert!(storage::parse_backup(&valid, &current).is_ok());
    let foreign = fs::read_to_string(prefs_fixture("backup_foreign.json")).expect("read");
    assert_eq!(
        storage::parse_backup(&foreign, &current).err(),
        Some(storage::BackupRefusal::NotABackup)
    );
}
