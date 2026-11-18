//! The files of the WhatsApp vault (`plans/wa-vault.md` 3, 13) and the wipe of the plaintext WhatsApp data it
//! replaces. The crypto is `rosty_core::vault`; this module only stores what it and Kotlin produce. The Keystore
//! layers (K_hw, K_daily) are Kotlin's: here they are opaque bytes.
//!
//! In the app's data directory (`noBackupFilesDir`), each written atomically and owner-only ([`storage::save_json`]):
//!
//! * [`VAULT_FILE`]: the K_hw-sealed key blob, the Keystore alias Kotlin sealed it with, and the body blob (DK).
//! * [`DAILY_FILE`]: the daily unlock (13.2): the K_daily-sealed daily blob, its alias, DDK and a plain copy of the
//!   stamp. DDK opens nothing without the K_daily layer, which needs a Class 3 biometric.
//! * [`CACHE_FILE`]: the identity and picture cache (13.5), sealed under DK.
//! * [`HISTORY_FILE`]: the recent chats and messages shown at once after an unlock (round 7a), sealed under DK.
//!   Its own file, so a lock writes it without rewriting the pictures.

#[cfg(test)]
mod tests;
pub mod wipe;

use std::io;
use std::path::Path;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use rosty_core::vault::{self, DailyStamp, DataKey, Entropy};
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use crate::storage;

pub const VAULT_FILE: &str = "wa-vault.json";
pub const DAILY_FILE: &str = "wa-vault-daily.json";
pub const CACHE_FILE: &str = "wa-vault-cache.bin";
pub const HISTORY_FILE: &str = "wa-vault-history.bin";
/// The `version` of the JSON files this build writes.
pub const FILE_VERSION: u32 = 1;

/// Base64 (standard, padded) for the byte fields of the JSON files.
mod b64 {
    use super::*;
    use serde::{Deserializer, Serializer, de::Error as _};

    pub fn serialize<S: Serializer>(bytes: &[u8], serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&STANDARD.encode(bytes))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Vec<u8>, D::Error> {
        let text = String::deserialize(deserializer)?;
        STANDARD.decode(text).map_err(D::Error::custom)
    }

    pub fn serialize_opt<S: Serializer>(
        bytes: &Option<Vec<u8>>,
        serializer: S,
    ) -> Result<S::Ok, S::Error> {
        match bytes {
            Some(bytes) => serializer.serialize_some(&STANDARD.encode(bytes)),
            None => serializer.serialize_none(),
        }
    }

    pub fn deserialize_opt<'de, D: Deserializer<'de>>(
        deserializer: D,
    ) -> Result<Option<Vec<u8>>, D::Error> {
        Option::<String>::deserialize(deserializer)?
            .map(|text| STANDARD.decode(text).map_err(D::Error::custom))
            .transpose()
    }

    pub fn deserialize_secret<'de, D: Deserializer<'de>>(
        deserializer: D,
    ) -> Result<Zeroizing<Vec<u8>>, D::Error> {
        let text = Zeroizing::new(String::deserialize(deserializer)?);
        STANDARD
            .decode(text.as_bytes())
            .map(Zeroizing::new)
            .map_err(D::Error::custom)
    }
}

fn invalid(what: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, what.to_owned())
}

/// Reads `dir/name`: `None` when missing, `InvalidData` when it is not this build's file.
fn load<T: serde::de::DeserializeOwned + Versioned>(
    dir: &Path,
    name: &str,
) -> io::Result<Option<T>> {
    let bytes = match std::fs::read(dir.join(name)) {
        Ok(bytes) => Zeroizing::new(bytes),
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    let value: T = serde_json::from_slice(&bytes).map_err(|_| invalid(name))?;
    if value.version() != FILE_VERSION {
        return Err(invalid(name));
    }
    Ok(Some(value))
}

trait Versioned {
    fn version(&self) -> u32;
}

/// Deletes `dir/name` and its stale temp file; answers the paths that existed.
fn remove(dir: &Path, name: &str) -> io::Result<Vec<String>> {
    let mut deleted = Vec::new();
    for path in [dir.join(name), dir.join(format!("{name}.tmp"))] {
        match std::fs::remove_file(&path) {
            Ok(()) => deleted.push(path.to_string_lossy().into_owned()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
    }
    Ok(deleted)
}

/// `wa-vault.json`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct VaultFile {
    pub version: u32,
    /// The Android Keystore alias of K_hw; rotation makes a new one.
    pub hw_alias: String,
    /// The key blob (`rosty_core::vault::wrap_key`) as Kotlin sealed it with K_hw.
    #[serde(with = "b64")]
    pub hw_sealed: Vec<u8>,
    /// The body blob, sealed under DK.
    #[serde(with = "b64")]
    pub body: Vec<u8>,
    /// DK wrapped under the connect key (`rosty_core::vault::wrap_for_link`, round 7a): a sibling Rosty app that
    /// hands this link over opens the vault without a prompt here. Written at the commit and, for a vault of an
    /// earlier build, at its next unlock.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        serialize_with = "b64::serialize_opt",
        deserialize_with = "b64::deserialize_opt"
    )]
    pub link_sealed: Option<Vec<u8>>,
}

impl Versioned for VaultFile {
    fn version(&self) -> u32 {
        self.version
    }
}

impl VaultFile {
    pub fn new(hw_alias: &str, hw_sealed: Vec<u8>, body: Vec<u8>) -> Self {
        Self {
            version: FILE_VERSION,
            hw_alias: hw_alias.to_owned(),
            hw_sealed,
            body,
            link_sealed: None,
        }
    }

    pub fn load(dir: &Path) -> io::Result<Option<Self>> {
        load(dir, VAULT_FILE)
    }

    pub fn save(&self, dir: &Path) -> io::Result<()> {
        storage::save_json(dir, VAULT_FILE, self)
    }

    pub fn exists(dir: &Path) -> bool {
        dir.join(VAULT_FILE).is_file()
    }
}

/// `wa-vault-daily.json`.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DailyFile {
    pub version: u32,
    /// The Android Keystore alias of K_daily.
    pub alias: String,
    /// The daily blob (`rosty_core::vault::seal_daily`) as Kotlin sealed it with K_daily.
    #[serde(with = "b64")]
    pub sealed: Vec<u8>,
    /// DDK, the key of the daily blob.
    #[serde(
        serialize_with = "b64::serialize",
        deserialize_with = "b64::deserialize_secret"
    )]
    pub daily_key: Zeroizing<Vec<u8>>,
    /// A plain copy of the stamp sealed inside, so the state can be judged without the biometric. The sealed one is
    /// authoritative.
    pub stamp: DailyStamp,
    /// The last unlock of this passphrase epoch (`rosty_core::vault::seal_seen`, sealed under DK, round 7a): the
    /// daily unlock moves the idle clock on with it.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        serialize_with = "b64::serialize_opt",
        deserialize_with = "b64::deserialize_opt"
    )]
    pub seen: Option<Vec<u8>>,
    /// A plain copy of the last unlock time sealed in [`Self::seen`], for the state without the biometric. The
    /// sealed one is authoritative.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_unlock_ms: Option<i64>,
}

impl std::fmt::Debug for DailyFile {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DailyFile")
            .field("alias", &self.alias)
            .field("sealed", &self.sealed.len())
            .field("stamp", &self.stamp)
            .finish_non_exhaustive()
    }
}

impl Versioned for DailyFile {
    fn version(&self) -> u32 {
        self.version
    }
}

impl DailyFile {
    pub fn new(
        alias: &str,
        sealed: Vec<u8>,
        daily_key: &vault::DailyKey,
        stamp: DailyStamp,
    ) -> Self {
        Self {
            version: FILE_VERSION,
            alias: alias.to_owned(),
            sealed,
            daily_key: Zeroizing::new(daily_key.as_bytes().to_vec()),
            stamp,
            seen: None,
            last_unlock_ms: None,
        }
    }

    /// The clock of the plain copies: what [`Self::stamp`] and [`Self::last_unlock_ms`] say, for the state and the
    /// boot path. The daily unlock checks the sealed ones instead.
    pub fn plain_clock(&self) -> vault::DailyClock {
        let seen = self.last_unlock_ms.map(|last| vault::Seen {
            epoch: self.stamp,
            last_unlock_ms: last,
        });
        vault::DailyClock::new(self.stamp, seen)
    }

    /// The sealed clock: [`Self::seen`] opened under DK when it belongs to `stamp` (the stamp sealed inside the
    /// daily blob); otherwise the stamp alone, which is the stricter rule.
    pub fn sealed_clock(&self, key: &DataKey, stamp: DailyStamp) -> vault::DailyClock {
        let seen = self
            .seen
            .as_deref()
            .and_then(|blob| vault::open_seen(key, blob).ok());
        vault::DailyClock::new(stamp, seen)
    }

    /// Records an unlock at `now_ms` of the epoch of `clock` (sealed under DK, with its plain copy).
    pub fn record_unlock(
        &mut self,
        key: &DataKey,
        clock: vault::DailyClock,
        now_ms: i64,
        entropy: &mut impl Entropy,
    ) {
        let seen = clock.seen_at(now_ms);
        self.seen = Some(vault::seal_seen(key, seen, vault::nonce(entropy)));
        self.last_unlock_ms = Some(seen.last_unlock_ms);
    }

    pub fn load(dir: &Path) -> io::Result<Option<Self>> {
        load(dir, DAILY_FILE)
    }

    pub fn save(&self, dir: &Path) -> io::Result<()> {
        storage::save_json(dir, DAILY_FILE, self)
    }

    /// DDK; `None` for a damaged file.
    pub fn key(&self) -> Option<vault::DailyKey> {
        vault::DailyKey::from_slice(&self.daily_key)
    }

    /// Ends the daily unlock (reboot, 72 h idle, biometric change, five failures, lock by the user).
    pub fn remove(dir: &Path) -> io::Result<Vec<String>> {
        remove(dir, DAILY_FILE)
    }
}

/// What the encrypted cache keeps between unlocks (plan 13.5): who every id is, the cursor of the identity catch-up
/// and the profile pictures. Never message content.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct VaultCache {
    /// The identity map as its owner serializes it.
    #[serde(default)]
    pub identity: serde_json::Value,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub identity_cursor: Option<String>,
    #[serde(default)]
    pub pictures: Vec<CachedPicture>,
    /// Phone's own LID-to-number table (`phone::wa_idbook`), as [`crate::phone::wa_idbook::Book::to_value`] gives
    /// it: Phone keeps no `wa-identity.json` once it has a vault.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id_book: Option<serde_json::Value>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CachedPicture {
    pub jid: String,
    pub picture_id: String,
    #[serde(with = "b64")]
    pub jpeg: Vec<u8>,
}

impl VaultCache {
    /// Seals the cache under DK into [`CACHE_FILE`].
    pub fn save(&self, dir: &Path, key: &DataKey, entropy: &mut impl Entropy) -> io::Result<()> {
        let plain = Zeroizing::new(serde_json::to_vec(self).map_err(io::Error::other)?);
        let blob = vault::seal_cache(key, &plain, vault::nonce(entropy));
        storage::save_bytes(dir, CACHE_FILE, &blob)
    }

    /// The cache, `None` when there is none. A cache that does not open under DK (another key, damage) is
    /// `InvalidData`; the caller deletes it and starts over.
    pub fn load(dir: &Path, key: &DataKey) -> io::Result<Option<Self>> {
        let blob = match std::fs::read(dir.join(CACHE_FILE)) {
            Ok(blob) => blob,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error),
        };
        let plain = vault::open_cache(key, &blob).map_err(|_| invalid(CACHE_FILE))?;
        serde_json::from_slice(&plain)
            .map(Some)
            .map_err(|_| invalid(CACHE_FILE))
    }
}

/// Seals `plain` under DK into `dir/name` (the [`HISTORY_FILE`]).
pub fn save_sealed(
    dir: &Path,
    name: &str,
    key: &DataKey,
    plain: &[u8],
    entropy: &mut impl Entropy,
) -> io::Result<()> {
    let blob = vault::seal_cache(key, plain, vault::nonce(entropy));
    storage::save_bytes(dir, name, &blob)
}

/// The plain bytes of `dir/name` sealed under DK; `None` when missing, `InvalidData` when it does not open.
pub fn load_sealed(
    dir: &Path,
    name: &str,
    key: &DataKey,
) -> io::Result<Option<Zeroizing<Vec<u8>>>> {
    let blob = match std::fs::read(dir.join(name)) {
        Ok(blob) => blob,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    vault::open_cache(key, &blob)
        .map(Some)
        .map_err(|_| invalid(name))
}

/// Deletes `dir/name` (and its temp file); answers the paths that existed.
pub fn remove_file(dir: &Path, name: &str) -> io::Result<Vec<String>> {
    remove(dir, name)
}

/// Removes the vault, the daily unlock and the caches (reset, forgotten passphrase, unlink). Answers what was
/// deleted.
pub fn remove_all(dir: &Path) -> io::Result<Vec<String>> {
    let mut deleted = Vec::new();
    for name in [VAULT_FILE, DAILY_FILE, CACHE_FILE, HISTORY_FILE] {
        deleted.extend(remove(dir, name)?);
    }
    Ok(deleted)
}
