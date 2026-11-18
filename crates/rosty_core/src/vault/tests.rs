//! Fixed vectors and failure cases of the vault blobs. Small KDF parameters keep the suite fast; one ignored test
//! runs the approved 64 MiB Argon2id.

use std::time::Duration;

use super::*;

/// Argon2id at its minimum (8 KiB, one pass): fast, still the real code path.
const FAST: Kdf = Kdf::Argon2id {
    m_kib: 8,
    t: 1,
    p: 1,
};
const FAST_PBKDF2: Kdf = Kdf::Pbkdf2Sha256 { iterations: 1000 };

/// Deterministic "entropy": 0, 1, 2, ... continuing across calls.
fn counter(start: u8) -> impl FnMut(&mut [u8]) {
    let mut next = start;
    move |out: &mut [u8]| {
        for byte in out {
            *byte = next;
            next = next.wrapping_add(1);
        }
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn body() -> VaultBody {
    VaultBody::new(
        "https://rosty.erdemdev.tr",
        "inst1",
        "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU",
    )
}

fn pass() -> Passphrase {
    Passphrase::from_entropy(&[7; ENTROPY_BYTES])
}

fn key() -> DataKey {
    DataKey::from_slice(&[0x42; KEY_LEN]).expect("32 bytes")
}

#[test]
fn argon2id_matches_the_reference_vector() {
    // phc-winner-argon2 `src/test.c`: Argon2id v1.3, t = 2, m = 2^16 KiB, p = 1, "password", "somesalt".
    let kdf = Kdf::Argon2id {
        m_kib: 1 << 16,
        t: 2,
        p: 1,
    };
    let out = kdf.derive(b"password", b"somesalt").expect("derive");
    assert_eq!(
        hex(out.as_slice()),
        "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7"
    );
}

#[test]
fn pbkdf2_matches_the_rfc_7914_vector() {
    // RFC 7914 section 11: PBKDF2-HMAC-SHA256, P = "passwd", S = "salt", c = 1, first 32 bytes of the 64.
    let kdf = Kdf::Pbkdf2Sha256 { iterations: 1 };
    let out = kdf.derive(b"passwd", b"salt").expect("derive");
    assert_eq!(
        hex(out.as_slice()),
        "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
    );
}

#[test]
fn key_blob_round_trips_and_has_the_documented_layout() {
    let blob = wrap_key(&key(), &pass(), FAST, [1; SALT_LEN], [2; NONCE_LEN]).expect("wrap");
    assert_eq!(blob.len(), KEY_BLOB_LEN);
    assert_eq!(&blob[..4], b"RWVK");
    assert_eq!(blob[4], VERSION);
    assert_eq!(blob[5], 1, "Argon2id");
    assert_eq!(&blob[6..18], &[0, 0, 0, 8, 0, 0, 0, 1, 0, 0, 0, 1]);
    assert_eq!(&blob[18..34], &[1; SALT_LEN]);
    assert_eq!(&blob[34..46], &[2; NONCE_LEN]);
    assert_eq!(key_blob_kdf(&blob), Ok(FAST));
    assert_eq!(unwrap_key(&blob, &pass()), Ok(key()));
    // Same inputs, same bytes: the module adds no randomness of its own.
    assert_eq!(
        wrap_key(&key(), &pass(), FAST, [1; SALT_LEN], [2; NONCE_LEN]).expect("wrap"),
        blob
    );
}

#[test]
fn pbkdf2_key_blobs_open_too() {
    let blob = wrap_key(&key(), &pass(), FAST_PBKDF2, [1; SALT_LEN], [2; NONCE_LEN]).expect("wrap");
    assert_eq!(blob[5], 2);
    assert_eq!(&blob[6..18], &[0, 0, 0x03, 0xe8, 0, 0, 0, 0, 0, 0, 0, 0]);
    assert_eq!(unwrap_key(&blob, &pass()), Ok(key()));
}

#[test]
fn the_build_default_is_the_approved_argon2id() {
    if cfg!(feature = "vault-pbkdf2") {
        assert_eq!(Kdf::for_build(), PBKDF2);
    } else {
        assert_eq!(
            Kdf::for_build(),
            Kdf::Argon2id {
                m_kib: 65536,
                t: 3,
                p: 1
            }
        );
    }
}

#[test]
fn a_wrong_passphrase_is_refused() {
    let blob = wrap_key(&key(), &pass(), FAST, [1; SALT_LEN], [2; NONCE_LEN]).expect("wrap");
    let other = Passphrase::from_entropy(&[8; ENTROPY_BYTES]);
    assert_eq!(unwrap_key(&blob, &other), Err(VaultError::WrongPassphrase));
}

#[test]
fn a_tampered_header_or_ciphertext_is_refused() {
    let blob = wrap_key(&key(), &pass(), FAST, [1; SALT_LEN], [2; NONCE_LEN]).expect("wrap");
    // Salt, nonce, KDF words (still in bounds) and ciphertext: authentication fails.
    for at in [7, 9, 20, 40, 50, KEY_BLOB_LEN - 1] {
        let mut bad = blob.clone();
        bad[at] ^= 0x01;
        assert_eq!(
            unwrap_key(&bad, &pass()),
            Err(VaultError::WrongPassphrase),
            "byte {at}"
        );
    }
    // KDF words out of bounds: corrupt before any derivation.
    let mut huge = blob.clone();
    huge[6] = 0xff;
    assert_eq!(unwrap_key(&huge, &pass()), Err(VaultError::Corrupt));
    let mut kdf = blob.clone();
    kdf[5] = 9;
    assert_eq!(unwrap_key(&kdf, &pass()), Err(VaultError::UnknownKdf(9)));
    let mut magic = blob;
    magic[0] = b'X';
    assert_eq!(unwrap_key(&magic, &pass()), Err(VaultError::NotAVault));
}

#[test]
fn truncated_and_unknown_version_blobs_are_refused() {
    let blob = wrap_key(&key(), &pass(), FAST, [1; SALT_LEN], [2; NONCE_LEN]).expect("wrap");
    for len in [0, 3] {
        assert_eq!(
            unwrap_key(&blob[..len], &pass()),
            Err(VaultError::NotAVault),
            "{len}"
        );
    }
    for len in [4, 5, 20, 46, KEY_BLOB_LEN - 1] {
        assert_eq!(
            unwrap_key(&blob[..len], &pass()),
            Err(VaultError::Corrupt),
            "{len}"
        );
    }
    let mut longer = blob.clone();
    longer.push(0);
    assert_eq!(unwrap_key(&longer, &pass()), Err(VaultError::Corrupt));
    for version in [0, 2, 255] {
        let mut newer = blob.clone();
        newer[4] = version;
        assert_eq!(
            unwrap_key(&newer, &pass()),
            Err(VaultError::UnknownVersion(version))
        );
    }

    let sealed = seal_body(&key(), &body(), [3; NONCE_LEN]);
    for len in [5, 17, 32] {
        assert_eq!(
            open_body(&key(), &sealed[..len]),
            Err(VaultError::Corrupt),
            "{len}"
        );
    }
    let mut cut = sealed.clone();
    cut.pop();
    assert_eq!(open_body(&key(), &cut), Err(VaultError::Tampered));
    let mut newer = sealed;
    newer[4] = 2;
    assert_eq!(
        open_body(&key(), &newer),
        Err(VaultError::UnknownVersion(2))
    );
}

#[test]
fn body_blob_vector() {
    let sealed = seal_body(&key(), &body(), [3; NONCE_LEN]);
    assert_eq!(&sealed[..5], b"RWVB\x01");
    assert_eq!(&sealed[5..17], &[3; NONCE_LEN]);
    let plain_len = serde_json::to_vec(&body()).expect("json").len();
    assert_eq!(sealed.len(), 17 + plain_len + 16);
    let opened = open_body(&key(), &sealed).expect("open");
    assert_eq!(opened, body());
    assert_eq!(opened.connect_key(), body().connect_key());
    assert_eq!(seal_body(&key(), &body(), [3; NONCE_LEN]), sealed);

    let other = DataKey::from_slice(&[0x43; KEY_LEN]).expect("key");
    assert_eq!(open_body(&other, &sealed), Err(VaultError::Tampered));
    let mut bad = sealed;
    bad[6] ^= 1;
    assert_eq!(open_body(&key(), &bad), Err(VaultError::Tampered));
}

#[test]
fn aes_gcm_matches_a_known_answer() {
    // NIST GCM test case 13 (zero key, zero nonce, empty plaintext): the tag of nothing under a zero key.
    let zero = DataKey::from_slice(&[0; KEY_LEN]).expect("key");
    let tag = zero
        .cipher()
        .encrypt(Nonce::from_slice(&[0; NONCE_LEN]), &[][..])
        .expect("encrypt");
    assert_eq!(hex(&tag), "530f8afbc74536b9a963b4f1c4cb738b");
}

#[test]
fn blobs_of_one_kind_are_not_another() {
    let body_blob = seal_body(&key(), &body(), [3; NONCE_LEN]);
    let cache_blob = seal_cache(&key(), b"cache", [3; NONCE_LEN]);
    assert_eq!(
        open_cache(&key(), &body_blob).err(),
        Some(VaultError::NotAVault)
    );
    assert_eq!(open_body(&key(), &cache_blob), Err(VaultError::NotAVault));
    // Swapping the magic in is not enough: the header is authenticated.
    let mut relabeled = cache_blob.clone();
    relabeled[..4].copy_from_slice(b"RWVB");
    assert_eq!(open_body(&key(), &relabeled), Err(VaultError::Tampered));
    assert_eq!(
        open_cache(&key(), &cache_blob).expect("open").as_slice(),
        b"cache"
    );
}

#[test]
fn rotation_makes_the_old_blobs_useless() {
    let first = create(&body(), FAST, &mut counter(0)).expect("create");
    let second = create(&body(), FAST, &mut counter(100)).expect("change");
    assert_ne!(first.passphrase, second.passphrase);
    assert_ne!(first.key, second.key);
    // The old key blob does not open with the new passphrase, nor the new one with the old.
    assert_eq!(
        unwrap_key(&first.key_blob, &second.passphrase),
        Err(VaultError::WrongPassphrase)
    );
    assert_eq!(
        unwrap_key(&second.key_blob, &first.passphrase),
        Err(VaultError::WrongPassphrase)
    );
    // The old body blob does not open under the new DK.
    let new_key = unwrap_key(&second.key_blob, &second.passphrase).expect("new");
    assert_eq!(
        open_body(&new_key, &first.body_blob),
        Err(VaultError::Tampered)
    );
    assert_eq!(open_body(&new_key, &second.body_blob), Ok(body()));
}

#[test]
fn create_is_deterministic_for_fixed_entropy() {
    let a = create(&body(), FAST, &mut counter(0)).expect("create");
    let b = create(&body(), FAST, &mut counter(0)).expect("create");
    assert_eq!(a.key_blob, b.key_blob);
    assert_eq!(a.body_blob, b.body_blob);
    assert_eq!(a.passphrase.grouped(), b.passphrase.grouped());
    // Entropy order: passphrase (17), DK (32), salt (16), key nonce (12), body nonce (12).
    assert_eq!(a.key.as_bytes()[0], 17);
    assert_eq!(&a.key_blob[18..34], &(49..65).collect::<Vec<u8>>()[..]);
    assert_eq!(unwrap_key(&a.key_blob, &a.passphrase), Ok(a.key.clone()));
    assert_eq!(open_body(&a.key, &a.body_blob), Ok(body()));
}

#[test]
fn daily_blob_round_trips_and_binds_its_stamp() {
    let daily = DailyKey::from_slice(&[9; KEY_LEN]).expect("key");
    let stamp = DailyStamp {
        boot_count: 7,
        unlocked_ms: 1_800_000_000_000,
    };
    let blob = seal_daily(&daily, &key(), stamp, [4; NONCE_LEN]);
    assert_eq!(blob.len(), DAILY_BLOB_LEN);
    assert_eq!(&blob[..5], b"RWVD\x01");
    assert_eq!(&blob[5..13], &7u64.to_be_bytes());
    assert_eq!(open_daily(&daily, &blob), Ok((key(), stamp)));

    // Moving the boot count or the time forward breaks the tag.
    for at in [12, 20] {
        let mut moved = blob.clone();
        moved[at] ^= 1;
        assert_eq!(open_daily(&daily, &moved), Err(VaultError::Tampered));
    }
    let other = DailyKey::from_slice(&[8; KEY_LEN]).expect("key");
    assert_eq!(open_daily(&other, &blob), Err(VaultError::Tampered));
    assert_eq!(
        open_daily(&daily, &blob[..DAILY_BLOB_LEN - 1]),
        Err(VaultError::Corrupt)
    );
}

#[test]
fn kdf_bounds() {
    for bad in [
        Kdf::Argon2id {
            m_kib: 7,
            t: 1,
            p: 1,
        },
        Kdf::Argon2id {
            m_kib: 64,
            t: 0,
            p: 1,
        },
        Kdf::Argon2id {
            m_kib: 64,
            t: 1,
            p: 0,
        },
        Kdf::Argon2id {
            m_kib: 2 * 1024 * 1024,
            t: 1,
            p: 1,
        },
        Kdf::Pbkdf2Sha256 { iterations: 0 },
    ] {
        assert_eq!(
            wrap_key(&key(), &pass(), bad, [1; SALT_LEN], [2; NONCE_LEN]),
            Err(VaultError::Corrupt),
            "{bad:?}"
        );
    }
    assert_eq!(
        Kdf::from_header(2, [1000, 1, 0]),
        Err(VaultError::Corrupt),
        "PBKDF2 with stray words"
    );
}

#[test]
fn calibration_picks_the_strongest_setting_that_fits() {
    let budget = Duration::from_millis(1000);
    assert_eq!(calibrate(Duration::from_millis(400), budget), ARGON2ID);
    assert_eq!(
        calibrate(Duration::from_millis(1200), budget),
        Kdf::Argon2id {
            m_kib: 65536,
            t: 2,
            p: 1
        }
    );
    assert_eq!(
        calibrate(Duration::from_millis(2500), budget),
        Kdf::Argon2id {
            m_kib: 65536,
            t: 1,
            p: 1
        }
    );
    assert_eq!(
        calibrate(Duration::from_secs(60), budget),
        Kdf::Argon2id {
            m_kib: 19 * 1024,
            t: 2,
            p: 1
        }
    );
}

#[test]
fn debug_hides_keys_and_the_connect_key() {
    assert_eq!(format!("{:?}", key()), "DataKey(…)");
    let text = format!("{:?}", body());
    assert!(!text.contains("BQUF"), "{text}");
}

#[test]
#[ignore = "derives at the approved 64 MiB, 3 passes: slow in unoptimised builds"]
fn the_approved_parameters_derive() {
    let blob = wrap_key(&key(), &pass(), ARGON2ID, [1; SALT_LEN], [2; NONCE_LEN]).expect("wrap");
    assert_eq!(key_blob_kdf(&blob), Ok(ARGON2ID));
    assert_eq!(unwrap_key(&blob, &pass()), Ok(key()));
}

// ---- round 7c: the use clock and the stay wrap; round 7a: the link wrap

const MADE_AT: i64 = 1_000_000_000;
const HOUR: i64 = 60 * 60 * 1000;
const DAY: i64 = 24 * HOUR;

#[test]
fn the_vault_locks_after_90_days_without_a_use() {
    assert_eq!(MAX_IDLE_MS, 90 * DAY);
    let fresh = UseClock::new(MADE_AT, None);
    assert_eq!(fresh.check(MADE_AT), Ok(()));
    assert_eq!(fresh.check(MADE_AT + MAX_IDLE_MS), Ok(()));
    assert_eq!(
        fresh.check(MADE_AT + MAX_IDLE_MS + 1),
        Err(IdleRefusal::Idle)
    );
}

#[test]
fn any_use_moves_the_90_days_on_with_no_cap() {
    // A use every 80 days keeps the vault open for years: no 72 h, no 30 days, no other cap.
    let mut clock = UseClock::new(MADE_AT, None);
    let mut at = MADE_AT;
    for _ in 0..20 {
        at += 80 * DAY;
        assert_eq!(clock.check(at), Ok(()), "{at}");
        clock = clock.used_at(at);
    }
    assert!(at - MADE_AT > 4 * 365 * DAY);
    // A use never moves the clock back.
    assert_eq!(clock.used_at(MADE_AT).last_use_ms, at);
}

#[test]
fn a_clock_set_back_before_the_last_use_is_refused() {
    let used = UseClock::new(MADE_AT, Some(MADE_AT + 50 * DAY));
    assert_eq!(used.check(MADE_AT + 50 * DAY - CLOCK_SKEW_MS), Ok(()));
    assert_eq!(
        used.check(MADE_AT + 50 * DAY - CLOCK_SKEW_MS - 1),
        Err(IdleRefusal::ClockBack)
    );
}

#[test]
fn a_use_record_from_before_the_stamp_changes_nothing() {
    assert_eq!(
        UseClock::new(MADE_AT, Some(MADE_AT - HOUR)).last_use_ms,
        MADE_AT
    );
    assert_eq!(UseClock::new(MADE_AT, None).last_use_ms, MADE_AT);
    assert_eq!(
        UseClock::latest([
            UseClock::new(MADE_AT, None),
            UseClock::new(MADE_AT + DAY, None)
        ]),
        Some(UseClock::new(MADE_AT + DAY, None))
    );
    assert_eq!(UseClock::latest([]), None);
}

#[test]
fn use_writes_are_throttled_to_one_an_hour() {
    assert!(UseClock::record_due(None, MADE_AT));
    assert!(!UseClock::record_due(
        Some(MADE_AT),
        MADE_AT + 59 * 60 * 1000
    ));
    assert!(UseClock::record_due(
        Some(MADE_AT),
        MADE_AT + USE_WRITE_INTERVAL_MS
    ));
    assert_eq!(USE_WRITE_INTERVAL_MS, HOUR);
}

#[test]
fn the_use_record_round_trips_under_dk_only() {
    let blob = seal_use(&key(), MADE_AT + 5 * HOUR, [3; NONCE_LEN]);
    assert_eq!(blob.len(), USE_BLOB_LEN);
    assert_eq!(&blob[..5], b"RWVU\x01");
    assert_eq!(open_use(&key(), &blob), Ok(MADE_AT + 5 * HOUR));
    let other = DataKey::from_slice(&[1; KEY_LEN]).expect("key");
    assert_eq!(open_use(&other, &blob), Err(VaultError::Tampered));
    // Moving the time forward breaks the tag.
    let mut moved = blob.clone();
    moved[12] ^= 1;
    assert_eq!(open_use(&key(), &moved), Err(VaultError::Tampered));
    assert_eq!(
        open_use(&key(), &blob[..USE_BLOB_LEN - 1]),
        Err(VaultError::Corrupt)
    );
    // A daily blob is not a use record.
    let daily = DailyKey::from_slice(&[9; KEY_LEN]).expect("key");
    let stamp = DailyStamp {
        boot_count: 7,
        unlocked_ms: MADE_AT,
    };
    let daily_blob = seal_daily(&daily, &key(), stamp, [4; NONCE_LEN]);
    assert_eq!(open_use(&key(), &daily_blob), Err(VaultError::NotAVault));
}

#[test]
fn the_stay_wrap_round_trips_and_binds_its_time() {
    let stay = StayKey::from_slice(&[9; KEY_LEN]).expect("key");
    let blob = seal_stay(&stay, &key(), MADE_AT, [4; NONCE_LEN]);
    assert_eq!(blob.len(), STAY_BLOB_LEN);
    assert_eq!(&blob[..5], b"RWVS\x01");
    assert_eq!(&blob[5..13], &MADE_AT.to_be_bytes());
    assert_eq!(open_stay(&stay, &blob), Ok((key(), MADE_AT)));
    // Moving the creation time forward breaks the tag.
    let mut moved = blob.clone();
    moved[12] ^= 1;
    assert_eq!(open_stay(&stay, &moved), Err(VaultError::Tampered));
    let other = StayKey::from_slice(&[8; KEY_LEN]).expect("key");
    assert_eq!(open_stay(&other, &blob), Err(VaultError::Tampered));
    assert_eq!(
        open_stay(&stay, &blob[..STAY_BLOB_LEN - 1]),
        Err(VaultError::Corrupt)
    );
    // A daily blob is not a stay wrap, even under the same key bytes.
    let daily = DailyKey::from_slice(&[9; KEY_LEN]).expect("key");
    let stamp = DailyStamp {
        boot_count: 7,
        unlocked_ms: MADE_AT,
    };
    let daily_blob = seal_daily(&daily, &key(), stamp, [4; NONCE_LEN]);
    assert_eq!(open_stay(&stay, &daily_blob), Err(VaultError::NotAVault));
}

#[test]
fn the_link_wrap_opens_with_the_vaults_connect_key_only() {
    let connect = body().connect_key().to_owned();
    let blob = wrap_for_link(&key(), &connect, [5; SALT_LEN], [6; NONCE_LEN]);
    assert_eq!(blob.len(), LINK_BLOB_LEN);
    assert_eq!(&blob[..5], b"RWVL\x01");
    assert_eq!(unwrap_for_link(&blob, &connect), Ok(key()));
    // Surrounding white space is not part of the key.
    assert_eq!(unwrap_for_link(&blob, &format!(" {connect}\n")), Ok(key()));
    assert_eq!(
        unwrap_for_link(&blob, "rwk_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
        Err(VaultError::Tampered)
    );
    let mut salted = blob.clone();
    salted[7] ^= 1;
    assert_eq!(
        unwrap_for_link(&salted, &connect),
        Err(VaultError::Tampered)
    );
    assert_eq!(
        unwrap_for_link(&blob[..LINK_BLOB_LEN - 1], &connect),
        Err(VaultError::Corrupt)
    );
    // A key blob is not a link wrap.
    let key_blob = wrap_key(&key(), &pass(), FAST, [1; SALT_LEN], [2; NONCE_LEN]).expect("wrap");
    assert_eq!(
        unwrap_for_link(&key_blob, &connect),
        Err(VaultError::NotAVault)
    );
    // Another salt, another blob for the same DK.
    assert_ne!(
        wrap_for_link(&key(), &connect, [7; SALT_LEN], [6; NONCE_LEN]),
        blob
    );
}
