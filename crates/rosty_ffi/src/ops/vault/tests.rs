//! The whole vault life cycle through the ops, with a reversible stand-in for the Keystore seals. Runs the real
//! KDF parameters (a few derivations of about half a second each in the checks profile).

use super::*;
use crate::envelope::{Answer, decode};
use std::fs;

const KEY: &str = super::super::wa::fixture::KEY;
const LINK: &str = r#"{"gateway_url":"https://rosty.erdemdev.tr","instance_id":"inst1","key":"rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU"}"#;
const T0: i64 = 1_800_000_000_000;

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rosty-ffi-vault-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

fn text(dir: &Path) -> Option<String> {
    Some(dir.to_string_lossy().into_owned())
}

/// What Kotlin's `Cipher` does with K_hw or K_daily, as far as Rust can tell: some reversible transformation.
fn keystore(b64_in: &str) -> String {
    let bytes = STANDARD.decode(b64_in).unwrap();
    b64(&bytes.iter().map(|b| b ^ 0x5a).collect::<Vec<_>>())
}

/// Runs `wa.open` through the dispatcher and answers its handle or error.
fn wa_open(request: &str) -> Result<i64, FfiError> {
    match decode(&crate::ops::call("wa.open", request.as_bytes())).unwrap() {
        Answer::Ok(value) => Ok(value["handle"].as_i64().unwrap()),
        Answer::Err(error) => Err(error),
    }
}

fn code(error: &FfiError) -> &str {
    error
        .detail
        .as_ref()
        .and_then(|d| d["code"].as_str())
        .unwrap_or("")
}

fn unlock_with(
    dir: &Path,
    passphrase: &str,
    boot_count: u64,
    now_ms: i64,
) -> Result<UnlockedAns, FfiError> {
    let file = VaultFile::load(dir).unwrap().unwrap();
    unlock(UnlockReq {
        dir: text(dir),
        key_blob: keystore(&b64(&file.hw_sealed)),
        passphrase: Zeroizing::new(passphrase.to_owned()),
        boot_count,
        now_ms,
    })
}

#[test]
fn the_vault_life_cycle() {
    let _guard = test_lock();
    let dir = temp_dir("cycle");
    fs::write(dir.join("whatsapp-link.json"), LINK).unwrap();

    // Migration: the plaintext link becomes a vault. Nothing is written before Kotlin's seal comes back.
    let created = create(CreateReq {
        dir: text(&dir),
        link: None,
    })
    .unwrap();
    assert_eq!(created.passphrase.len(), 34);
    assert!(!VaultFile::exists(&dir));
    assert!(unlocked().is_none(), "an uncommitted vault serves nobody");
    let state = commit(CommitReq {
        handle: created.handle,
        hw_alias: "rosty.wa.k1".into(),
        hw_sealed: keystore(&created.seal),
    })
    .unwrap();
    assert_eq!(state.vault.as_ref().unwrap().hw_alias, "rosty.wa.k1");
    assert_eq!(state.unlocked.as_ref().unwrap().handle, created.handle);
    assert_eq!(state.unlocked.as_ref().unwrap().instance_id, "inst1");
    assert!(state.legacy_link);
    let on_disk = fs::read_to_string(dir.join(wa_vault::VAULT_FILE)).unwrap();
    assert!(
        !on_disk.contains("rwk_")
            && !on_disk.contains("inst1")
            && !on_disk.contains("rosty.erdemdev")
    );
    assert_eq!(
        commit(CommitReq {
            handle: created.handle,
            hw_alias: "x".into(),
            hw_sealed: "AQ==".into(),
        })
        .map(|_| ())
        .unwrap_err()
        .kind,
        ErrorKind::Invalid
    );

    // The wipe now goes ahead: the link file and the rest of the plaintext are gone.
    let report = wipe_plaintext(WipeReq {
        dir: text(&dir),
        files_dir: dir.join("files").to_string_lossy().into_owned(),
        cache_dir: text(&dir.join("cache")),
    })
    .unwrap();
    assert_eq!(
        report.deleted,
        vec![
            dir.join("whatsapp-link.json")
                .to_string_lossy()
                .into_owned()
        ]
    );
    assert!(
        !vault_state(StateReq {
            dir: text(&dir),
            boot_count: None,
            now_ms: None
        })
        .unwrap()
        .legacy_link
    );

    // The gateway takes key and URL from the vault; a key or another gateway in the request is refused.
    let gateway = wa_open("{}").unwrap();
    let ctx = super::super::wa::ctx(gateway).unwrap();
    assert_eq!(ctx.key.as_ref(), Some(&ConnectKey::parse(KEY).unwrap()));
    assert_eq!(ctx.url.as_str(), "https://rosty.erdemdev.tr");
    let refused = wa_open(&format!(r#"{{"key":"{KEY}"}}"#)).unwrap_err();
    assert_eq!(code(&refused), "vault_unlocked");
    let other = wa_open(r#"{"url":"https://wa.example.com"}"#).unwrap_err();
    assert_eq!(code(&other), "other_gateway");

    // Lock: the session and every gateway opened from it are gone.
    let state = lock(LockReq {
        handle: None,
        dir: text(&dir),
    })
    .unwrap();
    assert!(state.unlocked.is_none());
    assert!(super::super::wa::ctx(gateway).is_err());
    assert!(unlocked().is_none());
    assert_eq!(
        wa_open("{}").unwrap_err().kind,
        ErrorKind::Invalid,
        "url required while locked"
    );

    // Passphrase unlock: a typo and a wrong passphrase are told apart.
    let typo = created.passphrase.replace('-', "");
    let typo = format!(
        "{}{}",
        &typo[..27],
        if typo.ends_with('0') { '1' } else { '0' }
    );
    assert_eq!(
        code(&unlock_with(&dir, &typo, 3, T0).map(|_| ()).unwrap_err()),
        "typo"
    );
    let wrong = seed::other_passphrase();
    let wrong = unlock_with(&dir, &wrong, 3, T0).map(|_| ()).unwrap_err();
    assert_eq!(
        (wrong.kind, code(&wrong)),
        (ErrorKind::Auth, "wrong_passphrase")
    );
    let opened = unlock_with(&dir, &created.passphrase.to_lowercase(), 3, T0).unwrap();
    assert_eq!(unlocked().unwrap().session, opened.handle);

    // Daily unlock: stored after the passphrase unlock, honoured within 72 h on the same boot.
    let state = daily_store(DailyStoreReq {
        handle: opened.handle,
        alias: "rosty.wa.d1".into(),
        sealed: keystore(&opened.daily_seal),
    })
    .unwrap();
    let daily = state.daily.unwrap();
    assert_eq!((daily.boot_count, daily.unlocked_ms), (3, T0));
    lock(LockReq {
        handle: Some(opened.handle),
        dir: text(&dir),
    })
    .unwrap();
    let again = unlock_daily(DailyUnlockReq {
        dir: text(&dir),
        daily_blob: keystore(&daily.sealed),
        boot_count: 3,
        now_ms: T0 + 3_600_000,
        biometric: true,
    })
    .unwrap();
    assert!(unlocked().is_some());
    let rebooted = unlock_daily(DailyUnlockReq {
        dir: text(&dir),
        daily_blob: keystore(&daily.sealed),
        boot_count: 4,
        now_ms: T0 + 3_600_000,
        biometric: true,
    })
    .map(|_| ())
    .unwrap_err();
    assert_eq!(
        (rebooted.kind, code(&rebooted)),
        (ErrorKind::Auth, "daily_expired")
    );
    assert!(
        DailyFile::load(&dir).unwrap().is_none(),
        "a lapsed daily unlock is deleted"
    );

    // Change: a new passphrase and DK; the old key blob no longer opens with it, the old passphrase is useless.
    let old_file = VaultFile::load(&dir).unwrap().unwrap();
    let changed = change(HandleReq {
        handle: again.handle,
    })
    .unwrap();
    assert_ne!(changed.passphrase, created.passphrase);
    commit(CommitReq {
        handle: again.handle,
        hw_alias: "rosty.wa.k2".into(),
        hw_sealed: keystore(&changed.seal),
    })
    .unwrap();
    let wrong = unlock_with(&dir, &created.passphrase, 3, T0)
        .map(|_| ())
        .unwrap_err();
    assert_eq!(code(&wrong), "wrong_passphrase");
    // An old copy of the vault file with the new passphrase: refused.
    let new_file = VaultFile::load(&dir).unwrap().unwrap();
    old_file.save(&dir).unwrap();
    let stale = unlock_with(&dir, &changed.passphrase, 3, T0)
        .map(|_| ())
        .unwrap_err();
    assert_eq!(code(&stale), "wrong_passphrase");
    new_file.save(&dir).unwrap();
    let fresh = unlock_with(&dir, &changed.passphrase, 3, T0).unwrap();

    // Reset: files gone, session locked.
    let reset = reset(DirReq { dir: text(&dir) }).unwrap();
    assert_eq!(
        reset.deleted,
        vec![
            dir.join(wa_vault::VAULT_FILE)
                .to_string_lossy()
                .into_owned()
        ]
    );
    assert!(unlocked().is_none());
    assert!(
        handles::global()
            .with(fresh.handle, |_: &mut Session| ())
            .is_none()
    );
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn the_wipe_waits_for_the_vault() {
    let dir = temp_dir("wipe-first");
    fs::write(dir.join("whatsapp-link.json"), LINK).unwrap();
    let refused = wipe_plaintext(WipeReq {
        dir: text(&dir),
        files_dir: dir.join("files").to_string_lossy().into_owned(),
        cache_dir: text(&dir.join("cache")),
    })
    .unwrap_err();
    assert_eq!(code(&refused), "no_vault");
    assert!(dir.join("whatsapp-link.json").exists());
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn create_checks_the_link() {
    let dir = temp_dir("create");
    let make = |key: &str, url: &str| {
        create(CreateReq {
            dir: text(&dir),
            link: Some(LinkIn {
                gateway_url: url.into(),
                instance_id: "inst1".into(),
                key: Zeroizing::new(key.into()),
            }),
        })
        .map(|_| ())
        .unwrap_err()
    };
    assert_eq!(
        code(&make("rwk_short", "https://rosty.erdemdev.tr")),
        "bad_key"
    );
    assert_eq!(code(&make(KEY, "http://rosty.erdemdev.tr")), "bad_url");
    let missing = create(CreateReq {
        dir: text(&dir),
        link: None,
    })
    .map(|_| ())
    .unwrap_err();
    assert_eq!(
        (missing.kind, code(&missing)),
        (ErrorKind::NotFound, "no_link")
    );
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn the_seeded_fixture_vault_is_what_the_seed_constants_say() {
    // `rosty_app_core/tests/fixtures/dirs/wa_vault` was written by `write_seed` below; the fixtures rely on it.
    let seed_dir = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../rosty_app_core/tests/fixtures/dirs/wa_vault");
    if std::env::var("UPDATE_FIXTURES").is_ok_and(|v| v == "1") {
        write_seed(&seed_dir);
    }
    let expected = temp_dir("seed");
    write_seed(&expected);
    for name in [wa_vault::VAULT_FILE, wa_vault::DAILY_FILE] {
        assert_eq!(
            fs::read_to_string(seed_dir.join(name)).ok(),
            fs::read_to_string(expected.join(name)).ok(),
            "{name}: UPDATE_FIXTURES=1 rewrites the seed"
        );
    }
    let _ = fs::remove_dir_all(&expected);
}

/// The seeded vault: passphrase all zeros, small Argon2id parameters, the K_hw and K_daily "seals" are the identity.
fn write_seed(dir: &Path) {
    let mut next = 0u8;
    let mut entropy = move |out: &mut [u8]| {
        for byte in out {
            *byte = next;
            next = next.wrapping_add(1);
        }
    };
    let body = VaultBody::new("https://rosty.erdemdev.tr", "inst1", KEY);
    let pass = Passphrase::parse(seed::PASSPHRASE).unwrap();
    let key = DataKey::generate(&mut entropy);
    let kdf = Kdf::Argon2id {
        m_kib: 8,
        t: 1,
        p: 1,
    };
    let key_blob = vault::wrap_key(
        &key,
        &pass,
        kdf,
        [1; vault::SALT_LEN],
        vault::nonce(&mut entropy),
    )
    .unwrap();
    let body_blob = vault::seal_body(&key, &body, vault::nonce(&mut entropy));
    let mut file = VaultFile::new("rosty.wa.k1", key_blob, body_blob);
    let daily_key = DailyKey::generate(&mut entropy);
    let stamp = DailyStamp {
        boot_count: seed::BOOT_COUNT,
        unlocked_ms: seed::UNLOCKED_MS,
    };
    let daily_blob = vault::seal_daily(&daily_key, &key, stamp, vault::nonce(&mut entropy));
    DailyFile::new("rosty.wa.d1", daily_blob, &daily_key, stamp)
        .save(dir)
        .unwrap();
    // The link wrap of round 7a: a sibling's hand-over of the seed's link opens it.
    file.link_sealed = Some(vault::wrap_for_link(
        &key,
        KEY,
        [2; vault::SALT_LEN],
        vault::nonce(&mut entropy),
    ));
    file.save(dir).unwrap();
}

#[test]
fn pictures_and_identity_live_only_sealed_in_the_cache() {
    let _guard = test_lock();
    let dir = temp_dir("cache");
    write_seed(&dir);
    // The seed's K_hw "seal" is the identity.
    let unlock_seed = || {
        let file = VaultFile::load(&dir).unwrap().unwrap();
        unlock(UnlockReq {
            dir: text(&dir),
            key_blob: b64(&file.hw_sealed),
            passphrase: Zeroizing::new(seed::PASSPHRASE.to_owned()),
            boot_count: seed::BOOT_COUNT,
            now_ms: seed::UNLOCKED_MS,
        })
        .unwrap()
    };
    let lock_now = || {
        lock(LockReq {
            handle: None,
            dir: text(&dir),
        })
        .unwrap()
    };
    lock_now();
    // Locked: nothing is read or written.
    let refused = pictures(PicturesReq { jids: None })
        .map(|_| ())
        .unwrap_err();
    assert_eq!(code(&refused), "locked");

    let session = unlock_seed();
    let jid = "100000000001@s.whatsapp.net";
    let put = picture_put(PicturePutReq {
        jid: jid.into(),
        picture_id: Some("p1".into()),
        jpeg: Some(b64(b"JPEG-BYTES-1000000000")),
    })
    .unwrap();
    assert_eq!((put.handle, put.pictures), (session.handle, 1));
    with_cache(true, |cache| {
        cache.identity = json!({"persons": [], "aliases": []});
        cache.identity_cursor = Some("cursor-77".into());
    })
    .unwrap();
    let on_disk = fs::read(dir.join(wa_vault::CACHE_FILE)).unwrap();
    let plain = String::from_utf8_lossy(&on_disk);
    assert!(
        !plain.contains("1000000000") && !plain.contains("cursor-77") && !plain.contains("JPEG")
    );

    // Another unlock reads the same cache back.
    lock_now();
    assert!(unlocked_instance().is_none());
    unlock_seed();
    assert_eq!(unlocked_instance().as_deref(), Some("inst1"));
    let read = pictures(PicturesReq { jids: None }).unwrap();
    assert_eq!(read.pictures.len(), 1);
    assert_eq!(read.pictures[0].picture_id, "p1");
    assert_eq!(
        with_cache(false, |cache| cache.identity_cursor.clone()).unwrap(),
        Some("cursor-77".into())
    );
    // Forgetting a picture; a damaged cache is deleted and starts over empty.
    picture_put(PicturePutReq {
        jid: jid.into(),
        picture_id: None,
        jpeg: None,
    })
    .unwrap();
    assert!(
        pictures(PicturesReq { jids: None })
            .unwrap()
            .pictures
            .is_empty()
    );
    fs::write(dir.join(wa_vault::CACHE_FILE), b"RWVC broken").unwrap();
    lock_now();
    unlock_seed();
    assert!(
        pictures(PicturesReq { jids: None })
            .unwrap()
            .pictures
            .is_empty()
    );
    assert!(!dir.join(wa_vault::CACHE_FILE).exists());
    lock_now();
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn only_the_open_vault_hands_its_link_over_and_a_trip_keeps_it_in_ram() {
    let _guard = test_lock();
    let dir = temp_dir("export");
    write_seed(&dir);
    let file = VaultFile::load(&dir).unwrap().unwrap();
    let unlocked = unlock(UnlockReq {
        dir: text(&dir),
        key_blob: b64(&file.hw_sealed),
        passphrase: Zeroizing::new(seed::PASSPHRASE.to_owned()),
        boot_count: seed::BOOT_COUNT,
        now_ms: seed::UNLOCKED_MS,
    })
    .unwrap();
    let link = export_link(HandleReq {
        handle: unlocked.handle,
    })
    .unwrap();
    assert_eq!(link.instance_id, "inst1");
    assert!(link.gateway_url.starts_with("https://rosty.erdemdev.tr"));
    assert_eq!(link.key.as_str(), KEY);
    // Another handle is not the open vault.
    let other = export_link(HandleReq { handle: 42 })
        .map(|_| ())
        .unwrap_err();
    assert_eq!(code(&other), "locked");

    // A trip keeps a copy that outlives the lock and goes with the trip's end; nothing is written for it.
    let names = || {
        let mut names: Vec<_> = fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        names.sort();
        names
    };
    let before = names();
    assert!(
        trip_hold(HandleReq {
            handle: unlocked.handle
        })
        .unwrap()
        .held
    );
    lock(LockReq {
        handle: None,
        dir: text(&dir),
    })
    .unwrap();
    assert!(rosty_app_core::wa_link::send::trip_link_held());
    assert_eq!(before, names());
    assert!(!trip_release(NoReq {}).unwrap().held);
    assert!(!rosty_app_core::wa_link::send::trip_link_held());

    // Locked: nothing is handed over and no trip can take the link.
    let locked = export_link(HandleReq {
        handle: unlocked.handle,
    })
    .map(|_| ())
    .unwrap_err();
    assert_eq!(code(&locked), "locked");
    assert!(
        trip_hold(HandleReq {
            handle: unlocked.handle
        })
        .is_err()
    );
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn phones_identity_table_moves_into_the_vault_cache() {
    let _guard = test_lock();
    let dir = temp_dir("idbook");
    write_seed(&dir);
    let seeded = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../rosty_app_core/tests/fixtures/dirs/wa_idbook/wa-identity.json");
    let plain = fs::read_to_string(&seeded)
        .unwrap()
        .replace("\"inst-1\"", "\"inst1\"");
    fs::write(dir.join("wa-identity.json"), &plain).unwrap();
    let call = |op: &str, body: serde_json::Value| -> Result<serde_json::Value, FfiError> {
        match decode(&crate::ops::call(op, body.to_string().as_bytes())).unwrap() {
            Answer::Ok(value) => Ok(value),
            Answer::Err(error) => Err(error),
        }
    };
    let where_ = text(&dir);
    let snapshot = || {
        call(
            "wa_idbook.snapshot",
            json!({"dir": where_, "instance_id": "inst1", "vault": true}),
        )
    };
    lock(LockReq {
        handle: None,
        dir: text(&dir),
    })
    .unwrap();
    // Locked: the table is not read, not even from the plaintext file.
    assert_eq!(code(&snapshot().unwrap_err()), "locked");
    assert!(dir.join("wa-identity.json").exists());

    let file = VaultFile::load(&dir).unwrap().unwrap();
    unlock(UnlockReq {
        dir: text(&dir),
        key_blob: b64(&file.hw_sealed),
        passphrase: Zeroizing::new(seed::PASSPHRASE.to_owned()),
        boot_count: seed::BOOT_COUNT,
        now_ms: seed::UNLOCKED_MS,
    })
    .unwrap();
    let first = snapshot().unwrap();
    assert!(!first["pairs"].as_array().unwrap().is_empty(), "{first}");
    // Moved: the file is gone and the cache holds the table, sealed.
    assert!(!dir.join("wa-identity.json").exists());
    let sealed = fs::read(dir.join(wa_vault::CACHE_FILE)).unwrap();
    assert!(!String::from_utf8_lossy(&sealed).contains("777000000000001"));
    call(
        "wa_idbook.learn",
        json!({"dir": where_, "instance_id": "inst1", "vault": true, "now_ms": seed::UNLOCKED_MS,
            "pairs": [{"id": "777000000000042@lid", "alt": "100000000042@s.whatsapp.net"}]}),
    )
    .unwrap();
    assert!(!dir.join("wa-identity.json").exists());
    let again = snapshot().unwrap();
    assert!(
        again["pairs"]
            .as_array()
            .unwrap()
            .iter()
            .any(|p| p["lid"] == "777000000000042@lid"),
        "{again}"
    );
    call("wa_idbook.wipe", json!({"dir": where_, "vault": true})).unwrap();
    assert!(snapshot().unwrap()["pairs"].as_array().unwrap().is_empty());
    lock(LockReq {
        handle: None,
        dir: text(&dir),
    })
    .unwrap();
    let _ = fs::remove_dir_all(&dir);
}

fn link_in(key: &str, instance: &str) -> LinkIn {
    LinkIn {
        gateway_url: "https://rosty.erdemdev.tr".into(),
        instance_id: instance.into(),
        key: Zeroizing::new(key.to_owned()),
    }
}

fn hand_over(dir: &Path, link: LinkIn, now_ms: i64) -> Result<HandleAns, FfiError> {
    unlock_link(LinkUnlockReq {
        dir: text(dir),
        link,
        boot_count: 3,
        now_ms,
    })
}

/// A committed vault for [`LINK`] in `dir`, locked, with its daily unlock stored at [`T0`] on boot 3.
fn vault_with_daily(dir: &Path) -> (String, DailyInfo) {
    let created = create(CreateReq {
        dir: text(dir),
        link: Some(link_in(KEY, "inst1")),
    })
    .unwrap();
    commit(CommitReq {
        handle: created.handle,
        hw_alias: "rosty.wa.k1".into(),
        hw_sealed: keystore(&created.seal),
    })
    .unwrap();
    let opened = unlock_with(dir, &created.passphrase, 3, T0).unwrap();
    let daily = daily_store(DailyStoreReq {
        handle: opened.handle,
        alias: "rosty.wa.d1".into(),
        sealed: keystore(&opened.daily_seal),
    })
    .unwrap()
    .daily
    .unwrap();
    lock(LockReq {
        handle: None,
        dir: text(dir),
    })
    .unwrap();
    (created.passphrase, daily)
}

#[test]
fn a_sibling_hand_over_opens_this_apps_own_vault_without_a_prompt() {
    let _guard = test_lock();
    let dir = temp_dir("hand-over");
    let _ = vault_with_daily(&dir);
    let on_disk = fs::read_to_string(dir.join(wa_vault::VAULT_FILE)).unwrap();
    assert!(on_disk.contains("link_sealed") && !on_disk.contains("rwk_"));

    // Another key, or the same key for another instance or gateway: refused, nothing opens.
    let other = "rwk_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    for link in [link_in(other, "inst1"), link_in(KEY, "inst2")] {
        let refused = hand_over(&dir, link, T0 + 1_000).map(|_| ()).unwrap_err();
        assert_eq!(
            (refused.kind, code(&refused)),
            (ErrorKind::Auth, "other_link")
        );
    }
    let mut elsewhere = link_in(KEY, "inst1");
    elsewhere.gateway_url = "https://wa.example.com".into();
    assert_eq!(
        code(&hand_over(&dir, elsewhere, T0).map(|_| ()).unwrap_err()),
        "other_link"
    );
    assert!(unlocked().is_none());

    // The vault's own link opens the vault's own session: `wa.open` takes the key from it.
    let opened = hand_over(&dir, link_in(KEY, "inst1"), T0 + 70 * HOUR_MS).unwrap();
    assert_eq!(unlocked().unwrap().session, opened.handle);
    assert!(
        session(opened.handle, |s: &mut Session| s.daily.is_none()).unwrap(),
        "a hand-over prepares no daily blob: sealing one would need a prompt"
    );
    // It also counts as an unlock for the idle clock of the daily unlock.
    let daily = DailyFile::load(&dir).unwrap().unwrap();
    assert_eq!(daily.last_unlock_ms, Some(T0 + 70 * HOUR_MS));
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn a_vault_of_an_earlier_build_gets_its_link_wrap_at_the_next_unlock() {
    let _guard = test_lock();
    let dir = temp_dir("wrap-later");
    let (passphrase, _) = vault_with_daily(&dir);
    let mut file = VaultFile::load(&dir).unwrap().unwrap();
    file.link_sealed = None;
    file.save(&dir).unwrap();
    let missing = hand_over(&dir, link_in(KEY, "inst1"), T0)
        .map(|_| ())
        .unwrap_err();
    assert_eq!(code(&missing), "no_link_wrap");
    unlock_with(&dir, &passphrase, 3, T0).unwrap();
    assert!(
        VaultFile::load(&dir)
            .unwrap()
            .unwrap()
            .link_sealed
            .is_some()
    );
    lock(LockReq {
        handle: None,
        dir: text(&dir),
    })
    .unwrap();
    hand_over(&dir, link_in(KEY, "inst1"), T0).unwrap();
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn a_biometric_daily_unlock_resets_the_idle_clock_up_to_30_days() {
    let _guard = test_lock();
    let dir = temp_dir("idle");
    let (_, daily) = vault_with_daily(&dir);
    let daily_at = |now_ms: i64, biometric: bool| {
        unlock_daily(DailyUnlockReq {
            dir: text(&dir),
            daily_blob: keystore(&daily.sealed),
            boot_count: 3,
            now_ms,
            biometric,
        })
        .map(|_| ())
    };
    // A biometric unlock every 70 h keeps the daily unlock alive past 72 h since the passphrase.
    let mut at = T0;
    while at + 70 * HOUR_MS <= T0 + vault::DAILY_MAX_AGE_MS {
        at += 70 * HOUR_MS;
        daily_at(at, true).unwrap();
    }
    assert!(at > T0 + vault::DAILY_MAX_IDLE_MS);
    // The state's plain copy agrees.
    let state = vault_state(StateReq {
        dir: text(&dir),
        boot_count: Some(3),
        now_ms: Some(at + HOUR_MS),
    })
    .unwrap();
    assert_eq!(state.daily.unwrap().last_unlock_ms, Some(at));
    // Then the 30 days since the passphrase end it, however recent the last unlock.
    let expired = daily_at(T0 + vault::DAILY_MAX_AGE_MS + 1, true).unwrap_err();
    assert_eq!(code(&expired), "daily_expired");
    assert_eq!(
        expired.detail.as_ref().unwrap()["reason"].as_str(),
        Some("expired")
    );
    assert!(DailyFile::load(&dir).unwrap().is_none());
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn a_screen_lock_daily_unlock_does_not_reset_the_idle_clock() {
    let _guard = test_lock();
    let dir = temp_dir("idle-pin");
    let (_, daily) = vault_with_daily(&dir);
    let daily_at = |now_ms: i64| {
        unlock_daily(DailyUnlockReq {
            dir: text(&dir),
            daily_blob: keystore(&daily.sealed),
            boot_count: 3,
            now_ms,
            biometric: false,
        })
        .map(|_| ())
    };
    daily_at(T0 + 70 * HOUR_MS).unwrap();
    let idle = daily_at(T0 + vault::DAILY_MAX_IDLE_MS + 1).unwrap_err();
    assert_eq!(
        idle.detail.as_ref().unwrap()["reason"].as_str(),
        Some("idle")
    );
    let _ = fs::remove_dir_all(&dir);
}
