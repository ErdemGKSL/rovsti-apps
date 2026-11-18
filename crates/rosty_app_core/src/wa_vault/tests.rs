use std::fs;
use std::path::{Path, PathBuf};

use rosty_core::vault::{DailyKey, DailyStamp, DataKey};
use rusqlite::Connection;

use super::wipe::{AppDirs, WipeReport, legacy_link, wipe_plaintext};
use super::*;

/// A secret no file may still hold after the wipe.
const SECRET: &str = "the-message-text-that-must-go";

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rosty-wa-vault-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

fn counter() -> impl FnMut(&mut [u8]) {
    let mut next = 0u8;
    move |out: &mut [u8]| {
        for byte in out {
            *byte = next;
            next = next.wrapping_add(1);
        }
    }
}

fn key() -> DataKey {
    DataKey::from_slice(&[0x42; 32]).unwrap()
}

#[test]
fn the_vault_file_round_trips_owner_only() {
    let dir = temp_dir("file");
    assert_eq!(VaultFile::load(&dir).unwrap(), None);
    assert!(!VaultFile::exists(&dir));
    let file = VaultFile::new("rosty.wa.k1", vec![1, 2, 3], vec![4, 5]);
    file.save(&dir).unwrap();
    assert!(VaultFile::exists(&dir));
    assert_eq!(VaultFile::load(&dir).unwrap(), Some(file));
    let text = fs::read_to_string(dir.join(VAULT_FILE)).unwrap();
    assert_eq!(
        text,
        r#"{"version":1,"hw_alias":"rosty.wa.k1","hw_sealed":"AQID","body":"BAU="}"#
    );
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        let mode = fs::metadata(dir.join(VAULT_FILE))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o777, 0o600);
    }
    assert!(!dir.join(format!("{VAULT_FILE}.tmp")).exists());
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn a_damaged_or_newer_vault_file_is_invalid_data_not_absent() {
    let dir = temp_dir("damaged");
    fs::write(dir.join(VAULT_FILE), "{not json").unwrap();
    let error = VaultFile::load(&dir).unwrap_err();
    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    fs::write(
        dir.join(VAULT_FILE),
        r#"{"version":2,"hw_alias":"a","hw_sealed":"","body":""}"#,
    )
    .unwrap();
    assert_eq!(
        VaultFile::load(&dir).unwrap_err().kind(),
        io::ErrorKind::InvalidData
    );
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn the_daily_file_round_trips_and_debug_hides_its_key() {
    let dir = temp_dir("daily");
    let daily = DailyKey::from_slice(&[9; 32]).unwrap();
    let stamp = DailyStamp {
        boot_count: 7,
        unlocked_ms: 1_800_000_000_000,
    };
    let file = DailyFile::new("rosty.wa.d1", vec![1, 2], &daily, stamp);
    file.save(&dir).unwrap();
    let loaded = DailyFile::load(&dir).unwrap().unwrap();
    assert_eq!(loaded, file);
    assert_eq!(loaded.key(), Some(daily));
    let text = format!("{loaded:?}");
    assert!(!text.contains("CQkJ") && !text.contains("9, 9"), "{text}");
    assert_eq!(
        DailyFile::remove(&dir).unwrap(),
        vec![dir.join(DAILY_FILE).to_string_lossy().into_owned()]
    );
    assert_eq!(DailyFile::load(&dir).unwrap(), None);
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn the_cache_opens_only_under_its_key() {
    let dir = temp_dir("cache");
    assert_eq!(VaultCache::load(&dir, &key()).unwrap(), None);
    let cache = VaultCache {
        identity: serde_json::json!({"pairs": [["1@lid", "100000000001@s.whatsapp.net"]]}),
        identity_cursor: Some("c1".into()),
        pictures: vec![CachedPicture {
            jid: "100000000001@s.whatsapp.net".into(),
            picture_id: "p1".into(),
            jpeg: vec![0xff, 0xd8, 0xff],
        }],
        id_book: Some(serde_json::json!({"version": 1, "instance": "inst-1"})),
    };
    cache.save(&dir, &key(), &mut counter()).unwrap();
    let raw = fs::read(dir.join(CACHE_FILE)).unwrap();
    assert_eq!(&raw[..4], b"RWVC");
    assert!(!String::from_utf8_lossy(&raw).contains("100000000001"));
    assert_eq!(VaultCache::load(&dir, &key()).unwrap(), Some(cache));
    let other = DataKey::from_slice(&[0x43; 32]).unwrap();
    assert_eq!(
        VaultCache::load(&dir, &other).unwrap_err().kind(),
        io::ErrorKind::InvalidData
    );
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn remove_all_deletes_the_vault_files_and_the_caches() {
    let dir = temp_dir("remove");
    VaultFile::new("a", vec![1], vec![2]).save(&dir).unwrap();
    DailyFile::new(
        "d",
        vec![1],
        &DailyKey::from_slice(&[1; 32]).unwrap(),
        DailyStamp {
            boot_count: 1,
            unlocked_ms: 1,
        },
    )
    .save(&dir)
    .unwrap();
    VaultCache::default()
        .save(&dir, &key(), &mut counter())
        .unwrap();
    save_sealed(&dir, HISTORY_FILE, &key(), b"[]", &mut counter()).unwrap();
    StayFile::new(
        "s",
        vec![1],
        &vault::StayKey::from_slice(&[2; 32]).unwrap(),
        1,
    )
    .save(&dir)
    .unwrap();
    UseFile::new(&key(), 1, &mut counter()).save(&dir).unwrap();
    fs::write(dir.join("other.json"), "{}").unwrap();
    let mut deleted = remove_all(&dir).unwrap();
    deleted.sort();
    let mut wanted: Vec<String> = [
        CACHE_FILE,
        DAILY_FILE,
        HISTORY_FILE,
        STAY_FILE,
        USE_FILE,
        VAULT_FILE,
    ]
    .iter()
    .map(|n| dir.join(n).to_string_lossy().into_owned())
    .collect();
    wanted.sort();
    assert_eq!(deleted, wanted);
    assert!(dir.join("other.json").exists());
    assert_eq!(remove_all(&dir).unwrap(), Vec::<String>::new());
    let _ = fs::remove_dir_all(&dir);
}

/// A store file like the offline store writes (WAL), holding [`SECRET`]. The connection stays open, so its `-wal`
/// and `-shm` exist when the wipe runs.
fn store_with_secret(path: &Path) -> Connection {
    let conn = Connection::open(path).unwrap();
    conn.pragma_update(None, "journal_mode", "WAL").unwrap();
    conn.execute_batch("CREATE TABLE messages (id TEXT PRIMARY KEY, text TEXT);")
        .unwrap();
    for i in 0..50 {
        conn.execute(
            "INSERT INTO messages VALUES (?1, ?2)",
            (format!("m{i}"), format!("{SECRET} {i}")),
        )
        .unwrap();
    }
    conn
}

fn write(path: &Path, text: &str) {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, text).unwrap();
}

fn every_file(dir: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    for entry in fs::read_dir(dir).unwrap().flatten() {
        let path = entry.path();
        if path.is_dir() {
            out.extend(every_file(&path));
        } else {
            out.push(path);
        }
    }
    out
}

#[test]
fn the_wipe_removes_every_listed_plaintext_file() {
    let root = temp_dir("wipe");
    let dirs = AppDirs {
        data: root.join("no_backup"),
        files: root.join("files"),
        cache: root.join("cache"),
    };
    fs::create_dir_all(&dirs.data).unwrap();
    let open = store_with_secret(&dirs.data.join("wa-sync-inst1.db"));
    assert!(dirs.data.join("wa-sync-inst1.db-wal").exists());
    assert!(dirs.data.join("wa-sync-inst1.db-shm").exists());
    // A second instance, closed, and the leftovers of a third whose database is gone.
    drop(store_with_secret(&dirs.data.join("wa-sync-old~2e.db")));
    write(&dirs.data.join("wa-sync-gone.db-wal"), SECRET);
    write(&dirs.data.join("wa-sync-gone.db-shm"), SECRET);
    let link = r#"{"gateway_url":"https://rosty.erdemdev.tr","instance_id":"inst1","key":"rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU"}"#;
    write(&dirs.data.join("whatsapp-link.json"), link);
    write(&dirs.data.join("whatsapp-link.json.tmp"), link);
    write(&dirs.data.join("wa-live-names.json"), SECRET);
    write(&dirs.data.join("wa-rekeys.json"), SECRET);
    write(&dirs.data.join("wa-identity.json"), SECRET);
    write(
        &dirs.data.join("drafts.json"),
        &format!(r#"{{"sms:7":"On my way","wa:100000000001@s.whatsapp.net":"{SECRET}"}}"#),
    );
    write(
        &dirs.files.join("wa-media/inst1/files/m1/photo.jpg"),
        SECRET,
    );
    write(&dirs.files.join("wa-media/inst1/thumbs/m1.jpg"), SECRET);
    write(&dirs.files.join("wa-stars.json"), SECRET);
    write(&dirs.cache.join("wa-status/m-1"), SECRET);
    write(&dirs.cache.join("wa-avatars/1.jpg"), SECRET);
    write(&dirs.cache.join("wa-media/tmp-1"), SECRET);
    // Not WhatsApp data: kept.
    write(&dirs.data.join("messages-prefs.json"), "{}");
    write(&dirs.data.join("wa-vault.json"), "{}");
    write(&dirs.files.join("recent-reactions.json"), "[]");
    write(&dirs.cache.join("share/card.png"), "png");

    let report = wipe_plaintext(&dirs);
    drop(open);

    let p = |dir: &Path, name: &str| dir.join(name).to_string_lossy().into_owned();
    let mut deleted = vec![
        p(&dirs.data, "wa-sync-gone.db-shm"),
        p(&dirs.data, "wa-sync-gone.db-wal"),
        p(&dirs.data, "wa-sync-inst1.db"),
        p(&dirs.data, "wa-sync-inst1.db-shm"),
        p(&dirs.data, "wa-sync-inst1.db-wal"),
        p(&dirs.data, "wa-sync-old~2e.db"),
        p(&dirs.data, "whatsapp-link.json"),
        p(&dirs.data, "whatsapp-link.json.tmp"),
        p(&dirs.data, "wa-live-names.json"),
        p(&dirs.data, "wa-rekeys.json"),
        p(&dirs.data, "wa-identity.json"),
        p(&dirs.files, "wa-stars.json"),
        p(&dirs.files, "wa-media"),
        p(&dirs.cache, "wa-status"),
        p(&dirs.cache, "wa-avatars"),
        p(&dirs.cache, "wa-media"),
    ];
    deleted.sort();
    assert_eq!(
        report,
        WipeReport {
            deleted,
            scrubbed: vec![
                p(&dirs.data, "wa-sync-inst1.db"),
                p(&dirs.data, "wa-sync-old~2e.db"),
            ],
            drafts_removed: 1,
            failed: vec![],
        }
    );

    // Nothing left holds the secret or the key; only the files that are not WhatsApp data remain.
    let mut left: Vec<String> = every_file(&root)
        .iter()
        .map(|path| {
            path.strip_prefix(&root)
                .unwrap()
                .to_string_lossy()
                .into_owned()
        })
        .collect();
    left.sort();
    assert_eq!(
        left,
        [
            "cache/share/card.png",
            "files/recent-reactions.json",
            "no_backup/drafts.json",
            "no_backup/messages-prefs.json",
            "no_backup/wa-vault.json",
        ]
    );
    for path in every_file(&root) {
        let text = String::from_utf8_lossy(&fs::read(&path).unwrap()).into_owned();
        assert!(!text.contains(SECRET) && !text.contains("rwk_"), "{path:?}");
    }
    assert_eq!(
        fs::read_to_string(dirs.data.join("drafts.json")).unwrap(),
        r#"{"sms:7":"On my way"}"#
    );
    assert_eq!(legacy_link(&dirs.data), None);

    // A second run finds nothing.
    assert_eq!(wipe_plaintext(&dirs), WipeReport::default());
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn scrubbing_overwrites_the_rows_before_the_file_goes() {
    let dir = temp_dir("scrub");
    let path = dir.join("wa-sync-x.db");
    drop(store_with_secret(&path));
    let before = fs::read(&path).unwrap();
    assert!(String::from_utf8_lossy(&before).contains(SECRET));
    super::wipe::scrub_for_test(&path).unwrap();
    let after = fs::read(&path).unwrap();
    assert!(!String::from_utf8_lossy(&after).contains(SECRET));
    assert!(after.len() <= before.len(), "VACUUM never grows the file");
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn the_legacy_link_is_read_for_the_migration() {
    let dir = temp_dir("legacy");
    write(
        &dir.join("whatsapp-link.json"),
        r#"{"gateway_url":"https://rosty.erdemdev.tr","instance_id":"inst1","key":"rwk_x"}"#,
    );
    let link = legacy_link(&dir).unwrap();
    assert_eq!(link.instance_id, "inst1");
    assert_eq!(link.key, "rwk_x");
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn files_of_an_earlier_build_read_without_the_round_7a_fields() {
    let dir = temp_dir("old-files");
    fs::write(
        dir.join(VAULT_FILE),
        r#"{"version":1,"hw_alias":"a","hw_sealed":"AQ==","body":"Ag=="}"#,
    )
    .unwrap();
    let vault = VaultFile::load(&dir).unwrap().unwrap();
    assert_eq!(vault.link_sealed, None);
    // Written back, the new field round-trips.
    let wrapped = VaultFile {
        link_sealed: Some(vec![3, 4]),
        ..vault
    };
    wrapped.save(&dir).unwrap();
    assert_eq!(VaultFile::load(&dir).unwrap().unwrap(), wrapped);
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn a_daily_file_of_round_7b_reads_with_its_old_clock_fields_ignored() {
    let dir = temp_dir("old-daily");
    fs::write(
        dir.join(DAILY_FILE),
        r#"{"version":1,"alias":"rosty.wa.d.b.1","sealed":"AQ==","daily_key":"AQ==","stamp":{"boot_count":3,"unlocked_ms":1000},"seen":"UldWQQ==","last_unlock_ms":5000}"#,
    )
    .unwrap();
    let daily = DailyFile::load(&dir).unwrap().unwrap();
    assert_eq!(daily.stamp.unlocked_ms, 1000);
    assert_eq!(daily.plain_clock().last_use_ms, 1000);
    // Written back, the old fields are gone.
    daily.save(&dir).unwrap();
    let text = fs::read_to_string(dir.join(DAILY_FILE)).unwrap();
    assert!(
        !text.contains("seen") && !text.contains("last_unlock_ms"),
        "{text}"
    );
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn the_stay_file_round_trips_owner_only_and_hides_its_key() {
    let dir = temp_dir("stay");
    assert!(StayFile::load(&dir).unwrap().is_none());
    let stay_key = vault::StayKey::from_slice(&[7; 32]).unwrap();
    let file = StayFile::new("rosty.wa.s.1", vec![1, 2], &stay_key, 1_000);
    file.save(&dir).unwrap();
    let read = StayFile::load(&dir).unwrap().unwrap();
    assert_eq!(read, file);
    assert_eq!(read.key(), Some(stay_key));
    assert!(!format!("{read:?}").contains("BwcH"), "Debug hides SK");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        let mode = fs::metadata(dir.join(STAY_FILE))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o777, 0o600);
    }
    assert_eq!(StayFile::remove(&dir).unwrap().len(), 1);
    assert!(StayFile::load(&dir).unwrap().is_none());
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn the_use_record_moves_the_clock_only_under_its_key() {
    let dir = temp_dir("use");
    let day = 24 * 3_600_000;
    let made = 1_000_000;
    assert_eq!(sealed_clock(&dir, &key(), made).last_use_ms, made);
    UseFile::new(&key(), made + 50 * day, &mut counter())
        .save(&dir)
        .unwrap();
    assert_eq!(
        sealed_clock(&dir, &key(), made).last_use_ms,
        made + 50 * day
    );
    // Under another DK the record does not count: the stamp's 90 days apply.
    let other = DataKey::from_slice(&[9; 32]).unwrap();
    assert_eq!(sealed_clock(&dir, &other, made).last_use_ms, made);
    // The plain copy alone, with a forged later time, cannot open anything: unlocks read the sealed one.
    let mut forged = UseFile::load(&dir).unwrap().unwrap();
    forged.used_ms = made + 80 * day;
    forged.save(&dir).unwrap();
    assert_eq!(
        sealed_clock(&dir, &key(), made).last_use_ms,
        made + 50 * day
    );
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn the_plain_clock_is_the_latest_wrap_or_use_and_expiry_drops_every_wrap() {
    let dir = temp_dir("plain-clock");
    let day = 24 * 3_600_000;
    assert_eq!(plain_clock(&dir), None);
    // A use record alone is no way in.
    UseFile::new(&key(), 9 * day, &mut counter())
        .save(&dir)
        .unwrap();
    assert_eq!(plain_clock(&dir), None);
    let stamp = DailyStamp {
        boot_count: 3,
        unlocked_ms: day,
    };
    DailyFile::new(
        "d",
        vec![1],
        &DailyKey::from_slice(&[1; 32]).unwrap(),
        stamp,
    )
    .save(&dir)
    .unwrap();
    assert_eq!(plain_clock(&dir).unwrap().last_use_ms, 9 * day);
    let stay_key = vault::StayKey::from_slice(&[7; 32]).unwrap();
    StayFile::new("s", vec![1], &stay_key, 20 * day)
        .save(&dir)
        .unwrap();
    assert_eq!(plain_clock(&dir).unwrap().last_use_ms, 20 * day);
    let mut deleted = expire(&dir).unwrap();
    deleted.sort();
    assert_eq!(deleted.len(), 3, "{deleted:?}");
    assert_eq!(plain_clock(&dir), None);
    assert!(UseFile::load(&dir).unwrap().is_none());
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn the_history_file_opens_only_under_its_key() {
    let dir = temp_dir("history");
    assert!(load_sealed(&dir, HISTORY_FILE, &key()).unwrap().is_none());
    save_sealed(&dir, HISTORY_FILE, &key(), b"chats", &mut counter()).unwrap();
    assert_eq!(
        load_sealed(&dir, HISTORY_FILE, &key())
            .unwrap()
            .unwrap()
            .as_slice(),
        b"chats"
    );
    let raw = fs::read(dir.join(HISTORY_FILE)).unwrap();
    assert!(!raw.windows(5).any(|w| w == b"chats"));
    let other = DataKey::from_slice(&[9; 32]).unwrap();
    assert_eq!(
        load_sealed(&dir, HISTORY_FILE, &other).unwrap_err().kind(),
        std::io::ErrorKind::InvalidData
    );
    let _ = fs::remove_dir_all(&dir);
}
