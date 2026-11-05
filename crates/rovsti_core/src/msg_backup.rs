//! Encrypted message backup archive.
//!
//! Layout: `MAGIC | version | iterations (u32 BE) | salt (16) | nonce (12) |
//! ciphertext + tag`. The key is PBKDF2-HMAC-SHA256 over the passphrase, the
//! cipher is AES-256-GCM, and everything before the ciphertext is
//! authenticated as associated data. The caller supplies salt and nonce, so
//! this module stays pure and deterministic.
//!
//! The WhatsApp connect key is a bearer secret and has no place in a backup;
//! `BackupBody` deliberately has no field for it.

use std::collections::HashMap;

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use thiserror::Error;

use crate::phone_number::PhoneNumber;
use crate::schedule::ScheduledSms;
use crate::sms::{MmsRow, SmsRow};
use crate::spam::SpamRules;

pub const MAGIC: &[u8; 4] = b"RMB1";
pub const VERSION: u8 = 1;
pub const ITERATIONS: u32 = 600_000;
pub const SALT_LEN: usize = 16;
pub const NONCE_LEN: usize = 12;

const HEADER_LEN: usize = MAGIC.len() + 1 + 4 + SALT_LEN + NONCE_LEN;
const TAG_LEN: usize = 16;
/// A header asking for more is treated as corrupt instead of burning CPU.
const MAX_ITERATIONS: u32 = 10_000_000;

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct BackupBody {
    pub created_ms: i64,
    pub app_version: String,
    pub sms: Vec<SmsRow>,
    /// Text parts only; see [`text_only_mms`].
    pub mms: Vec<MmsRow>,
    #[serde(default)]
    pub spam: SpamRules,
    #[serde(default)]
    pub scheduled: Vec<ScheduledSms>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Error)]
pub enum BackupError {
    #[error("not a Rovsti message backup")]
    NotABackup,
    #[error("backup format version {0} is newer than this app supports")]
    NewerVersion(u8),
    #[error("wrong passphrase, or the backup was modified")]
    WrongPassphrase,
    #[error("the backup is damaged")]
    Corrupt,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Strength {
    Weak,
    Fair,
    Strong,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RestorePlan {
    pub insert: Vec<SmsRow>,
    pub skipped_duplicates: usize,
}

/// Keeps only the text parts of each MMS and drops rows left without any.
/// Binary attachments live in files outside the archive.
pub fn text_only_mms(rows: &[MmsRow]) -> Vec<MmsRow> {
    rows.iter()
        .filter_map(|row| {
            let parts: Vec<_> = row
                .parts
                .iter()
                .filter(|p| p.text.is_some())
                .map(|p| crate::sms::MmsPart {
                    content_type: p.content_type.clone(),
                    text: p.text.clone(),
                    path: None,
                })
                .collect();
            (!parts.is_empty()).then(|| MmsRow {
                parts,
                ..row.clone()
            })
        })
        .collect()
}

fn header(iterations: u32, salt: &[u8; SALT_LEN], nonce: &[u8; NONCE_LEN]) -> Vec<u8> {
    let mut h = Vec::with_capacity(HEADER_LEN);
    h.extend_from_slice(MAGIC);
    h.push(VERSION);
    h.extend_from_slice(&iterations.to_be_bytes());
    h.extend_from_slice(salt);
    h.extend_from_slice(nonce);
    h
}

fn cipher(passphrase: &str, salt: &[u8], iterations: u32) -> Aes256Gcm {
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), salt, iterations, &mut key);
    Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key))
}

/// Serializes and encrypts `body`. `salt` and `nonce` must come from a
/// cryptographic RNG and must be fresh for every call.
pub fn seal(
    body: &BackupBody,
    passphrase: &str,
    salt: [u8; SALT_LEN],
    nonce: [u8; NONCE_LEN],
    iterations: u32,
) -> Vec<u8> {
    let plain = serde_json::to_vec(body).expect("BackupBody serializes: all keys are strings");
    let mut out = header(iterations, &salt, &nonce);
    let sealed = cipher(passphrase, &salt, iterations)
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: &plain,
                aad: &out,
            },
        )
        .expect("AES-GCM encryption of an in-memory buffer cannot fail");
    out.extend_from_slice(&sealed);
    out
}

pub fn open(bytes: &[u8], passphrase: &str) -> Result<BackupBody, BackupError> {
    if bytes.len() < MAGIC.len() || &bytes[..MAGIC.len()] != MAGIC {
        return Err(BackupError::NotABackup);
    }
    if bytes.len() < HEADER_LEN + TAG_LEN {
        return Err(BackupError::Corrupt);
    }
    let version = bytes[MAGIC.len()];
    if version > VERSION {
        return Err(BackupError::NewerVersion(version));
    }
    if version != VERSION {
        return Err(BackupError::Corrupt);
    }
    let mut at = MAGIC.len() + 1;
    let iterations = u32::from_be_bytes(
        bytes[at..at + 4]
            .try_into()
            .map_err(|_| BackupError::Corrupt)?,
    );
    at += 4;
    if iterations == 0 || iterations > MAX_ITERATIONS {
        return Err(BackupError::Corrupt);
    }
    let salt = &bytes[at..at + SALT_LEN];
    at += SALT_LEN;
    let nonce = &bytes[at..at + NONCE_LEN];

    // A tampered header changes the key or the AAD, which surfaces as an
    // authentication failure indistinguishable from a wrong passphrase.
    let plain = cipher(passphrase, salt, iterations)
        .decrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: &bytes[HEADER_LEN..],
                aad: &bytes[..HEADER_LEN],
            },
        )
        .map_err(|_| BackupError::WrongPassphrase)?;
    serde_json::from_slice(&plain).map_err(|_| BackupError::Corrupt)
}

/// A rough guide for the passphrase field; it never blocks a backup.
pub fn passphrase_strength(passphrase: &str) -> Strength {
    let len = passphrase.chars().count();
    let mut unique: Vec<char> = passphrase.chars().collect();
    unique.sort_unstable();
    unique.dedup();
    let classes = [
        passphrase.chars().any(char::is_lowercase),
        passphrase.chars().any(char::is_uppercase),
        passphrase.chars().any(|c| c.is_ascii_digit()),
        passphrase
            .chars()
            .any(|c| !c.is_alphanumeric() && !c.is_whitespace()),
    ]
    .into_iter()
    .filter(|&c| c)
    .count();

    if len < 10 || unique.len() < 5 {
        Strength::Weak
    } else if len >= 16 || (len >= 12 && classes >= 3) {
        Strength::Strong
    } else {
        Strength::Fair
    }
}

fn same_address(a: &str, b: &str) -> bool {
    match (PhoneNumber::parse(a), PhoneNumber::parse(b)) {
        (Ok(a), Ok(b)) => a.matches(&b),
        // Alphanumeric sender ids and short codes that do not parse.
        _ => a.trim().eq_ignore_ascii_case(b.trim()),
    }
}

/// Which backed-up SMS rows are missing from the device. A row is a
/// duplicate when address (via [`PhoneNumber::matches`]), body and date all
/// agree with an existing row, or with a row already planned for insertion.
/// MMS rows are not planned here: they need the device's MMS rows to compare
/// against.
pub fn restore_plan(backup: &BackupBody, existing: &[SmsRow]) -> RestorePlan {
    let mut seen: HashMap<(i64, &str), Vec<&str>> = HashMap::new();
    for row in existing {
        seen.entry((row.date_ms, row.body.as_str()))
            .or_default()
            .push(row.address.as_str());
    }

    let mut plan = RestorePlan::default();
    for row in &backup.sms {
        let bucket = seen.entry((row.date_ms, row.body.as_str())).or_default();
        if bucket.iter().any(|a| same_address(a, &row.address)) {
            plan.skipped_duplicates += 1;
        } else {
            bucket.push(row.address.as_str());
            plan.insert.push(row.clone());
        }
    }
    plan
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schedule::ScheduleTarget;
    use crate::sms::MmsPart;
    use crate::spam::SpamRule;

    const FAST: u32 = 1000;
    const SALT: [u8; SALT_LEN] = [7; SALT_LEN];
    const NONCE: [u8; NONCE_LEN] = [9; NONCE_LEN];

    fn sms(address: &str, body: &str, date_ms: i64) -> SmsRow {
        SmsRow {
            address: address.into(),
            body: body.into(),
            date_ms,
            ..SmsRow::default()
        }
    }

    fn body() -> BackupBody {
        BackupBody {
            created_ms: 1_700_000_000_000,
            app_version: "0.1.0".into(),
            sms: vec![sms("+905321234567", "selam", 10), sms("1234", "kod 55", 20)],
            mms: vec![MmsRow {
                id: 3,
                addresses: vec!["+905321234567".into()],
                parts: vec![MmsPart {
                    content_type: "text/plain".into(),
                    text: Some("hello".into()),
                    path: None,
                }],
                ..MmsRow::default()
            }],
            spam: SpamRules {
                rules: vec![SpamRule::Keyword {
                    text: "bahis".into(),
                }],
                allow: vec!["PTT".into()],
                ..SpamRules::default()
            },
            scheduled: vec![ScheduledSms {
                id: 1,
                at_ms: 1_700_000_100_000,
                target: ScheduleTarget::Sms {
                    to: "+905321234567".into(),
                    sub_id: None,
                },
                body: "later".into(),
                created_ms: 1_700_000_000_000,
            }],
        }
    }

    #[test]
    fn round_trips() {
        let sealed = seal(&body(), "correct horse", SALT, NONCE, FAST);
        assert_eq!(&sealed[..4], MAGIC);
        assert_eq!(sealed[4], VERSION);
        assert_eq!(open(&sealed, "correct horse"), Ok(body()));
    }

    #[test]
    fn old_backups_without_spam_or_schedule_still_open() {
        let body: BackupBody =
            serde_json::from_str(r#"{"created_ms":1,"app_version":"0","sms":[],"mms":[]}"#)
                .expect("de");
        assert!(body.spam.enabled && body.spam.rules.is_empty());
        assert!(body.scheduled.is_empty());
    }

    #[test]
    fn wrong_passphrase() {
        let sealed = seal(&body(), "correct horse", SALT, NONCE, FAST);
        assert_eq!(open(&sealed, "wrong"), Err(BackupError::WrongPassphrase));
    }

    #[test]
    fn tampered_header_fails_authentication() {
        let sealed = seal(&body(), "pw", SALT, NONCE, FAST);
        // Salt, nonce and iteration bytes.
        for at in [6, 10, 12, 25, 36] {
            let mut bad = sealed.clone();
            bad[at] ^= 1;
            assert_eq!(
                open(&bad, "pw"),
                Err(BackupError::WrongPassphrase),
                "byte {at}"
            );
        }
    }

    #[test]
    fn tampered_ciphertext_fails_authentication() {
        let mut sealed = seal(&body(), "pw", SALT, NONCE, FAST);
        let last = sealed.len() - 1;
        sealed[last] ^= 1;
        assert_eq!(open(&sealed, "pw"), Err(BackupError::WrongPassphrase));
    }

    #[test]
    fn newer_version() {
        let mut sealed = seal(&body(), "pw", SALT, NONCE, FAST);
        sealed[4] = VERSION + 1;
        assert_eq!(
            open(&sealed, "pw"),
            Err(BackupError::NewerVersion(VERSION + 1))
        );
    }

    #[test]
    fn not_a_backup_and_corrupt() {
        assert_eq!(open(b"", "pw"), Err(BackupError::NotABackup));
        assert_eq!(open(b"PK\x03\x04 zip", "pw"), Err(BackupError::NotABackup));
        assert_eq!(open(b"RMB1\x01", "pw"), Err(BackupError::Corrupt));
        let mut sealed = seal(&body(), "pw", SALT, NONCE, FAST);
        sealed[5..9].copy_from_slice(&u32::MAX.to_be_bytes());
        assert_eq!(open(&sealed, "pw"), Err(BackupError::Corrupt));
    }

    #[test]
    fn different_salt_gives_different_bytes() {
        let a = seal(&body(), "pw", SALT, NONCE, FAST);
        let b = seal(&body(), "pw", [8; SALT_LEN], NONCE, FAST);
        assert_ne!(a[HEADER_LEN..], b[HEADER_LEN..]);
    }

    #[test]
    fn body_has_no_link_key_field() {
        let json = serde_json::to_string(&body()).expect("json");
        assert!(!json.contains("connect_key") && !json.contains("link_key"));
    }

    #[test]
    fn strength_levels() {
        assert_eq!(passphrase_strength(""), Strength::Weak);
        assert_eq!(passphrase_strength("short1!"), Strength::Weak);
        assert_eq!(passphrase_strength("aaaaaaaaaaaaaaaaaaaa"), Strength::Weak);
        assert_eq!(passphrase_strength("tenletters"), Strength::Fair);
        assert_eq!(passphrase_strength("Tr0ub4dor&3x"), Strength::Strong);
        assert_eq!(
            passphrase_strength("correct horse battery"),
            Strength::Strong
        );
        assert!(Strength::Weak < Strength::Strong);
    }

    #[test]
    fn restore_plan_dedupes() {
        let backup = BackupBody {
            sms: vec![
                sms("+905321234567", "selam", 10),
                sms("+905321234567", "selam", 11),
                sms("+905329999999", "selam", 10),
                sms("1234", "kod 55", 20),
                sms("1234", "kod 55", 20),
            ],
            ..BackupBody::default()
        };
        let existing = vec![sms("05321234567", "selam", 10), sms("1234", "other", 20)];
        let plan = restore_plan(&backup, &existing);
        assert_eq!(plan.skipped_duplicates, 2);
        assert_eq!(
            plan.insert,
            vec![
                sms("+905321234567", "selam", 11),
                sms("+905329999999", "selam", 10),
                sms("1234", "kod 55", 20),
            ]
        );
    }

    #[test]
    fn text_only_mms_drops_binary_parts() {
        let row = MmsRow {
            parts: vec![
                MmsPart {
                    content_type: "image/jpeg".into(),
                    text: None,
                    path: Some("/x.jpg".into()),
                },
                MmsPart {
                    content_type: "text/plain".into(),
                    text: Some("hi".into()),
                    path: None,
                },
            ],
            ..MmsRow::default()
        };
        let image_only = MmsRow {
            parts: vec![row.parts[0].clone()],
            ..MmsRow::default()
        };
        let out = text_only_mms(&[row, image_only]);
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].parts.len(), 1);
        assert_eq!(out[0].text(), "hi");
    }
}
