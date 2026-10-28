//! Fixture tests for `rovsti_core::settings`: the v1 upgrade, v2 round trips,
//! files from a newer build and the backup file. Kotlin does not read these
//! (the prefs file is Rust-only, AD-30).

use rovsti_core::settings::{
    BackupFile, DEFAULT_REPLIES, ImportError, ImportPreview, PrefsV1, PrefsV2, SortBy,
};
use rovsti_core::wire::{HapticsLevel, NameFormat};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::Value;
use std::path::PathBuf;

fn text(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/prefs")
        .join(name);
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
}

fn parse<T: DeserializeOwned>(name: &str) -> T {
    serde_json::from_str(&text(name)).unwrap_or_else(|e| panic!("parse {name}: {e}"))
}

fn round_trip<T: DeserializeOwned + Serialize>(name: &str) -> (T, Value) {
    let typed: T = parse(name);
    let value = serde_json::to_value(&typed).expect("serialise");
    (typed, value)
}

#[test]
fn v1_typical_upgrades_without_losing_anything() {
    let v1: PrefsV1 = parse("v1_typical.json");
    let v2 = PrefsV2::from(v1);
    assert_eq!(v2.ui.last_tab, Some(2));
    assert_eq!(v2.ui.missed_seen_ms, 1_790_000_000_000);
    assert_eq!(v2.ui.app_version, "0.1.0");
    assert_eq!(v2.ui.xiaomi_confirmed.len(), 2);
    assert_eq!(v2.settings.haptics, HapticsLevel::Normal);
    assert!(v2.settings.proximity);
    // The upgraded file round-trips as v2.
    let json = serde_json::to_string(&v2).expect("serialise");
    let again: PrefsV2 = serde_json::from_str(&json).expect("parse");
    assert_eq!(again, v2);
    assert_eq!(again.schema_version, 2);
}

#[test]
fn v1_minimal_is_all_defaults() {
    let v2 = PrefsV2::from(parse::<PrefsV1>("v1_minimal.json"));
    assert_eq!(v2, PrefsV2::default());
}

#[test]
fn a_v1_file_is_also_a_loadable_v2_file() {
    // A flat v1 file read as v2 loses the fields that moved under `ui`; this
    // is why storage probes `schema_version` first.
    let wrong: PrefsV2 = parse("v1_typical.json");
    assert_eq!(wrong.ui.last_tab, None);
    assert_eq!(wrong.quick_replies, DEFAULT_REPLIES);
}

#[test]
fn v2_typical_round_trips_exactly() {
    let original: Value = serde_json::from_str(&text("v2_typical.json")).expect("json");
    let (typed, again): (PrefsV2, Value) = round_trip("v2_typical.json");
    assert_eq!(again, original);
    assert_eq!(typed.settings.sort_by, SortBy::LastName);
    assert_eq!(typed.settings.name_format, NameFormat::LastFirst);
    assert_eq!(typed.settings.keypad_tones, Some(false));
    assert_eq!(typed.speed_dial.len(), 2);
    assert_eq!(typed.speed_dial[&9].name, "Özgür");
    let mut checked = typed.clone();
    checked.validate();
    assert_eq!(checked, typed, "a typical file is already valid");
}

#[test]
fn a_newer_file_with_unknown_fields_loads_best_effort() {
    let mut p: PrefsV2 = parse("v2_unknown_field.json");
    assert_eq!(p.schema_version, 3);
    assert_eq!(p.settings.haptics, HapticsLevel::Normal, "unknown value");
    assert_eq!(p.settings.sort_by, SortBy::FirstName, "unknown value");
    assert_eq!(p.quick_replies, ["Hi"]);
    p.validate();
    assert_eq!(p.schema_version, 2, "saved back as the version we write");
    assert_eq!(p.favorites_order, ["a", "b"]);
    assert_eq!(p.speed_dial[&3].number_raw, "112");
}

#[test]
fn an_empty_quick_replies_list_stays_empty() {
    let p: PrefsV2 = serde_json::from_str(r#"{"quick_replies":[]}"#).expect("parse");
    assert!(p.quick_replies.is_empty());
    let q: PrefsV2 = serde_json::from_str("{}").expect("parse");
    assert_eq!(q.quick_replies, DEFAULT_REPLIES);
}

#[test]
fn the_backup_fixture_imports() {
    let b: BackupFile = parse("backup_valid.json");
    let preview = ImportPreview::from(&b, &PrefsV2::default()).expect("preview");
    assert_eq!(preview.quick_replies, 2);
    assert_eq!(preview.speed_dial, 1);
    assert_eq!(preview.favorites, 1);
    assert_eq!(preview.settings_changed, 1);
}

#[test]
fn a_foreign_file_is_not_a_backup() {
    let b: BackupFile = parse("backup_foreign.json");
    assert_eq!(
        ImportPreview::from(&b, &PrefsV2::default()),
        Err(ImportError::NotABackup)
    );
    // Not even close to a backup: still parses as defaults, still refused.
    let junk: BackupFile = serde_json::from_str("{}").expect("parse");
    assert_eq!(junk.kind, "rovsti-phone-backup", "serde default");
    let junk: BackupFile = serde_json::from_str(r#"{"kind":"x"}"#).expect("parse");
    assert!(ImportPreview::from(&junk, &PrefsV2::default()).is_err());
}

#[test]
fn an_export_reads_back_as_the_same_sections() {
    let mut prefs: PrefsV2 = parse("v2_typical.json");
    prefs.validate();
    let file = BackupFile::from_prefs(&prefs, 1, "0.2.0");
    let json = serde_json::to_string(&file).expect("serialise");
    assert!(!json.contains("xiaomi"), "no Xiaomi confirmations");
    assert!(!json.contains("missed_seen_ms"), "no ui state");
    let back: BackupFile = serde_json::from_str(&json).expect("parse");
    assert_eq!(back, file);
    let mut fresh = PrefsV2::default();
    ImportPreview::from(&back, &fresh)
        .expect("preview")
        .apply_to(&mut fresh);
    assert_eq!(fresh.settings, prefs.settings);
    assert_eq!(fresh.speed_dial, prefs.speed_dial);
    assert_eq!(fresh.favorites_order, prefs.favorites_order);
}
