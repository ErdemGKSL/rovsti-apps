//! The use clock of the vault (round 7c): the vault stays unlocked and locks itself only after
//! [`MAX_IDLE_MS`] (90 days) with no use. It replaces round 7a's passphrase clock (72 h idle, 30-day cap, reboot).
//!
//! The time of the last use is kept in a *use* record sealed under DK, which every unlock opens anyway:
//!
//! | blob | header                                                 | ciphertext |
//! |------|--------------------------------------------------------|------------|
//! | use  | `RWVU` · version · last use ms i64 · nonce (12)         | tag (16)   |
//!
//! The rule ([`UseClock::check`]): the last use is the later of the record and the authenticated stamp of the wrap
//! being opened (the stay-unlocked or the daily blob); the clock may not be behind it by more than
//! [`CLOCK_SKEW_MS`] (a clock set back), and at most [`MAX_IDLE_MS`] may have passed since. A missing, damaged or
//! rolled-back record only moves the last use back to the stamp, so tampering with it can only make the rule
//! stricter, and a newer record needs DK.
//!
//! Writes are throttled ([`UseClock::record_due`]): a use is written at most once per [`USE_WRITE_INTERVAL_MS`].

use super::{DataKey, NONCE_LEN, TAG_LEN, VERSION, VaultError, be_8, check_frame, open, seal};
use serde::{Deserialize, Serialize};
use thiserror::Error;

const DAY_MS: i64 = 24 * 60 * 60 * 1000;
/// The vault locks itself after this long with no use (owner decision, round 7c).
pub const MAX_IDLE_MS: i64 = 90 * DAY_MS;
/// A clock this far behind the last use is a clock set back, not drift.
pub const CLOCK_SKEW_MS: i64 = 5 * 60 * 1000;
/// A use is written at most this often (not on every frame or activity start).
pub const USE_WRITE_INTERVAL_MS: i64 = 60 * 60 * 1000;

const MAGIC_USE: &[u8; 4] = b"RWVU";
const USE_HEADER_LEN: usize = 4 + 1 + 8 + NONCE_LEN;
/// The length of a use record.
pub const USE_BLOB_LEN: usize = USE_HEADER_LEN + TAG_LEN;

/// Why the vault no longer opens without the passphrase.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Error)]
#[serde(rename_all = "snake_case")]
pub enum IdleRefusal {
    #[error("90 days passed without a use")]
    Idle,
    #[error("the clock is behind the last use")]
    ClockBack,
}

/// Seals the time of a use under DK (no secret inside: the record is all authenticated header).
pub fn seal_use(key: &DataKey, used_ms: i64, nonce: [u8; NONCE_LEN]) -> Vec<u8> {
    let mut header = Vec::with_capacity(USE_BLOB_LEN);
    header.extend_from_slice(MAGIC_USE);
    header.push(VERSION);
    header.extend_from_slice(&used_ms.to_be_bytes());
    header.extend_from_slice(&nonce);
    seal(&key.cipher(), header, &nonce, &[])
}

/// Opens a use record under DK: the time of the use.
pub fn open_use(key: &DataKey, blob: &[u8]) -> Result<i64, VaultError> {
    check_frame(blob, MAGIC_USE, USE_HEADER_LEN)?;
    if blob.len() != USE_BLOB_LEN {
        return Err(VaultError::Corrupt);
    }
    open(&key.cipher(), blob, USE_HEADER_LEN, VaultError::Tampered)?;
    Ok(i64::from_be_bytes(be_8(blob, 5)))
}

/// The last known use of the vault.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UseClock {
    pub last_use_ms: i64,
}

impl UseClock {
    /// The clock of a wrap stamped at `since_ms`, moved on by a use record when it is later. A record from before
    /// the stamp changes nothing.
    pub fn new(since_ms: i64, used_ms: Option<i64>) -> Self {
        Self {
            last_use_ms: used_ms.map_or(since_ms, |used| used.max(since_ms)),
        }
    }

    /// The latest of several clocks (the plain copies of every wrap); `None` when there is none.
    pub fn latest(clocks: impl IntoIterator<Item = Self>) -> Option<Self> {
        clocks.into_iter().max_by_key(|clock| clock.last_use_ms)
    }

    /// Whether the vault may open now without the passphrase.
    pub fn check(self, now_ms: i64) -> Result<(), IdleRefusal> {
        if now_ms < self.last_use_ms.saturating_sub(CLOCK_SKEW_MS) {
            Err(IdleRefusal::ClockBack)
        } else if now_ms.saturating_sub(self.last_use_ms) > MAX_IDLE_MS {
            Err(IdleRefusal::Idle)
        } else {
            Ok(())
        }
    }

    /// A use at `now_ms` (never earlier than what the clock knows).
    pub fn used_at(self, now_ms: i64) -> Self {
        Self {
            last_use_ms: now_ms.max(self.last_use_ms),
        }
    }

    /// Whether a use at `now_ms` must be written, given the last time one was (`None`: never in this session).
    pub fn record_due(recorded_ms: Option<i64>, now_ms: i64) -> bool {
        recorded_ms.is_none_or(|at| now_ms.saturating_sub(at) >= USE_WRITE_INTERVAL_MS)
    }
}
