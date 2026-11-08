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

#[test]
fn the_whatsapp_link_fixture_loads_and_saves_byte_for_byte() {
    use rovsti_app_core::wa_link::link::{LINK_FILE, StoredLink};
    let dir = tmp("fx-wa-link");
    fs::copy(fixture("whatsapp-link.json"), dir.join(LINK_FILE)).expect("copy");
    let link = StoredLink::load(&dir).expect("link");
    assert_eq!(link.instance_id, "inst-7f3a");
    assert_eq!(link.gateway_url, "https://rovsti.erdemdev.tr");
    link.save(&dir).expect("save");
    assert_eq!(
        fs::read(dir.join(LINK_FILE)).expect("read"),
        fs::read(fixture("whatsapp-link.json")).expect("fixture"),
    );
    StoredLink::remove(&dir).expect("remove");
    assert!(StoredLink::load(&dir).is_none());
}

#[test]
fn the_messages_prefs_fixture_loads_with_spam_rules_and_voice_speed() {
    use rovsti_app_core::messages::prefs::{MessagesPrefs, PREFS_FILE};
    use rovsti_core::{spam::SpamRule, voice_player::Speed};

    let dir = store::test_support::tmp("fx-msg-prefs");
    fs::copy(fixture("messages-prefs.json"), dir.join(PREFS_FILE)).expect("copy");
    let prefs = MessagesPrefs::load(&dir);
    assert_eq!(prefs.voice_speed, Speed::X2);
    assert!(prefs.spam.rules.contains(&SpamRule::UnknownWithLink));
    assert!(
        prefs
            .spam
            .rules
            .contains(&SpamRule::Keyword { text: "win".into() })
    );
    assert!(prefs.is_muted("sms:7"));
    prefs.save(&dir).expect("save");
    assert_eq!(
        fs::read(dir.join(PREFS_FILE)).expect("read"),
        fs::read(fixture("messages-prefs.json")).expect("fixture"),
        "saving rewrites the file the old code wrote"
    );
}

#[test]
fn the_drafts_fixture_loads_and_saves_byte_for_byte() {
    use rovsti_app_core::messages::drafts::{DRAFTS_FILE, Drafts};
    use rovsti_core::ThreadId;
    use std::collections::BTreeMap;

    let dir = store::test_support::tmp("fx-drafts");
    fs::copy(fixture("drafts.json"), dir.join(DRAFTS_FILE)).expect("copy");
    let map: BTreeMap<String, String> = storage::load_json(&dir, DRAFTS_FILE).expect("map");
    let drafts = Drafts::from_map(&map);
    assert_eq!(drafts.get(&ThreadId::Sms(3)), Some("running late"));
    assert_eq!(
        drafts.get(&ThreadId::WhatsApp("1@s.whatsapp.net".into())),
        Some("hello")
    );
    storage::save_json(&dir, DRAFTS_FILE, &drafts.to_map()).expect("save");
    assert_eq!(
        fs::read(dir.join(DRAFTS_FILE)).expect("read"),
        fs::read(fixture("drafts.json")).expect("fixture"),
    );
}

#[test]
fn the_contacts_trash_fixture_loads_purges_and_saves_byte_for_byte() {
    use rovsti_app_core::contacts::trash_store;

    let name = "contacts-trash.json";
    let dir = store::test_support::tmp("fx-contacts-trash");
    fs::copy(fixture(name), dir.join(name)).expect("copy");
    let trash = trash_store::trash_load(&dir);
    assert_eq!(trash.entries.len(), 2);
    assert_eq!(trash.entries[0].draft.given, "Burak");
    trash_store::trash_save(&dir, &trash).expect("save");
    assert_eq!(
        fs::read(dir.join(name)).expect("read"),
        fs::read(fixture(name)).expect("fixture"),
    );
    // Thirty days after the older entry only the newer one is left.
    let day = 86_400_000;
    let purged = trash_store::trash_load_purged(&dir, 1_700_000_050_000 + 30 * day);
    assert_eq!(purged.entries.len(), 1);
    assert_eq!(purged.entries[0].draft.given, "Burak");
}

#[test]
fn the_contacts_prefs_fixture_loads_and_saves_byte_for_byte() {
    use rovsti_app_core::contacts::prefs;
    use rovsti_core::{settings::SortBy, wire::NameFormat};

    let name = "contacts-prefs.json";
    let dir = store::test_support::tmp("fx-contacts-prefs");
    fs::copy(fixture(name), dir.join(name)).expect("copy");
    let loaded = prefs::load(&dir);
    assert_eq!(loaded.name_format, NameFormat::LastFirst);
    assert_eq!(loaded.sort_by, SortBy::LastName);
    assert_eq!(loaded.save_to, "com.google|a@b.com");
    prefs::save(&dir, loaded).expect("save");
    assert_eq!(
        fs::read(dir.join(name)).expect("read"),
        fs::read(fixture(name)).expect("fixture"),
    );
}

#[test]
fn the_call_notes_fixture_loads_and_saves_byte_for_byte() {
    use rovsti_app_core::phone::notes::{self, NotesStore};

    let name = "call-notes.json";
    let dir = store::test_support::tmp("fx-call-notes");
    fs::copy(fixture(name), dir.join(name)).expect("copy");
    let stored = notes::load(&dir).expect("notes");
    let mut store = NotesStore::default();
    store.loaded(stored);
    assert_eq!(store.len(), 2);
    assert_eq!(
        store
            .get("0555 111 22 33", 1_700_000_000_000)
            .map(|n| n.text.as_str()),
        Some("call back")
    );
    notes::save(&dir, &store.snapshot()).expect("save");
    assert_eq!(
        fs::read(dir.join(name)).expect("read"),
        fs::read(fixture(name)).expect("fixture"),
    );
}

#[test]
fn the_wa_calls_fixtures_load_and_save_byte_for_byte() {
    use rovsti_app_core::phone::wa_call::{extra_calls, load_history, save_history};

    let dir = store::test_support::tmp("fx-wa-calls");
    for name in ["wa-calls.json", "wa-calls-forgotten.json"] {
        fs::copy(fixture(name), dir.join(name)).expect("copy");
    }
    let history = load_history(&dir).expect("history");
    assert_eq!(history.iter().count(), 2);
    assert!(history.knows("c"), "the deleted call stays deleted");
    let extras = extra_calls(&history);
    assert_eq!(extras[0].number_raw, "+905550000000");
    assert_eq!(extras[1].label.as_deref(), Some("Ayşe"));
    save_history(&dir, &history);
    for name in ["wa-calls.json", "wa-calls-forgotten.json"] {
        assert_eq!(
            fs::read(dir.join(name)).expect("read"),
            fs::read(fixture(name)).expect("fixture"),
            "{name}"
        );
    }
}
