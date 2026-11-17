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
        }
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

/// Removes the vault, the daily unlock and the cache (reset, forgotten passphrase). Answers what was deleted.
pub fn remove_all(dir: &Path) -> io::Result<Vec<String>> {
    let mut deleted = Vec::new();
    for name in [VAULT_FILE, DAILY_FILE, CACHE_FILE] {
        deleted.extend(remove(dir, name)?);
    }
    Ok(deleted)
}
