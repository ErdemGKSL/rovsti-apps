//! The passphrase clock of the daily unlock (plan 13.2, round 7a): the 72 h rule counts *idle* time.
//!
//! The daily blob ([`super::seal_daily`]) is sealed once, at the passphrase unlock: its stamp is the boot and the
//! time of the passphrase. A daily (Class 3 biometric) unlock cannot re-seal it without a second prompt, so the time
//! of the last unlock is kept in a small *seen* record sealed under DK, which the daily unlock opens anyway:
//!
//! | blob | header                                                                                  | ciphertext |
//! |------|-----------------------------------------------------------------------------------------|------------|
//! | seen | `RWVA` · version · boot count u64 · passphrase ms i64 · last unlock ms i64 · nonce (12)  | tag (16)   |
//!
//! The record names the passphrase epoch it belongs to (boot count and passphrase time, as in the daily stamp); a
//! record of another epoch, a damaged one or none at all leaves the clock where the stamp put it. Deleting or
//! rolling back the record can therefore only make the rule stricter, and a new one needs DK.
//!
//! The rules ([`DailyClock::check`]): the boot count must be the stamp's; the clock may not be behind the last
//! unlock (more than [`DAILY_CLOCK_SKEW_MS`]); at most [`DAILY_MAX_AGE_MS`] since the passphrase, whatever the use;
//! at most [`DAILY_MAX_IDLE_MS`] since the last unlock.

use super::{
    DAILY_CLOCK_SKEW_MS, DAILY_MAX_IDLE_MS, DailyRefusal, DailyStamp, DataKey, NONCE_LEN, TAG_LEN,
    VERSION, VaultError, be_8, check_frame, open, seal,
};

/// The hard cap: the passphrase is asked at least this often, however often the biometric unlocks.
pub const DAILY_MAX_AGE_MS: i64 = 30 * 24 * 60 * 60 * 1000;

const MAGIC_SEEN: &[u8; 4] = b"RWVA";
const SEEN_HEADER_LEN: usize = 4 + 1 + 8 + 8 + 8 + NONCE_LEN;
/// The length of a seen blob.
pub const SEEN_BLOB_LEN: usize = SEEN_HEADER_LEN + TAG_LEN;

/// The last unlock of a passphrase epoch: the epoch's stamp and the time of its latest unlock.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Seen {
    pub epoch: DailyStamp,
    pub last_unlock_ms: i64,
}

/// Seals `seen` under DK (no secret inside: the record is all authenticated header).
pub fn seal_seen(key: &DataKey, seen: Seen, nonce: [u8; NONCE_LEN]) -> Vec<u8> {
    let mut header = Vec::with_capacity(SEEN_BLOB_LEN);
    header.extend_from_slice(MAGIC_SEEN);
    header.push(VERSION);
    header.extend_from_slice(&seen.epoch.boot_count.to_be_bytes());
    header.extend_from_slice(&seen.epoch.unlocked_ms.to_be_bytes());
    header.extend_from_slice(&seen.last_unlock_ms.to_be_bytes());
    header.extend_from_slice(&nonce);
    seal(&key.cipher(), header, &nonce, &[])
}

/// Opens a seen blob under DK.
pub fn open_seen(key: &DataKey, blob: &[u8]) -> Result<Seen, VaultError> {
    check_frame(blob, MAGIC_SEEN, SEEN_HEADER_LEN)?;
    if blob.len() != SEEN_BLOB_LEN {
        return Err(VaultError::Corrupt);
    }
    open(&key.cipher(), blob, SEEN_HEADER_LEN, VaultError::Tampered)?;
    Ok(Seen {
        epoch: DailyStamp {
            boot_count: u64::from_be_bytes(be_8(blob, 5)),
            unlocked_ms: i64::from_be_bytes(be_8(blob, 13)),
        },
        last_unlock_ms: i64::from_be_bytes(be_8(blob, 21)),
    })
}

/// The clock of one passphrase epoch: the stamp of its daily blob and the last unlock known for it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DailyClock {
    pub stamp: DailyStamp,
    pub last_unlock_ms: i64,
}

impl DailyClock {
    /// The clock of `stamp`, moved on by `seen` when it belongs to the same epoch. A later unlock only counts
    /// forward: a record older than the stamp changes nothing.
    pub fn new(stamp: DailyStamp, seen: Option<Seen>) -> Self {
        let last = seen
            .filter(|seen| seen.epoch == stamp)
            .map_or(stamp.unlocked_ms, |seen| {
                seen.last_unlock_ms.max(stamp.unlocked_ms)
            });
        Self {
            stamp,
            last_unlock_ms: last,
        }
    }

    /// Whether the daily unlock may open now, on boot `boot_count`.
    pub fn check(self, boot_count: u64, now_ms: i64) -> Result<(), DailyRefusal> {
        if boot_count != self.stamp.boot_count {
            Err(DailyRefusal::Rebooted)
        } else if now_ms < self.last_unlock_ms.saturating_sub(DAILY_CLOCK_SKEW_MS) {
            Err(DailyRefusal::ClockBack)
        } else if now_ms.saturating_sub(self.stamp.unlocked_ms) > DAILY_MAX_AGE_MS {
            Err(DailyRefusal::Expired)
        } else if now_ms.saturating_sub(self.last_unlock_ms) > DAILY_MAX_IDLE_MS {
            Err(DailyRefusal::Idle)
        } else {
            Ok(())
        }
    }

    /// The record of an unlock at `now_ms` (never earlier than what the clock knows).
    pub fn seen_at(self, now_ms: i64) -> Seen {
        Seen {
            epoch: self.stamp,
            last_unlock_ms: now_ms.max(self.last_unlock_ms),
        }
    }
}
