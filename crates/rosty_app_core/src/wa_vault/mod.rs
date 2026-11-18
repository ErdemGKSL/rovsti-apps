//! The files of the WhatsApp vault (`plans/wa-vault.md` 3, 13) and the wipe of the plaintext WhatsApp data it
//! replaces. The crypto is `rosty_core::vault`; this module only stores what it and Kotlin produce. The Keystore
//! layers (K_hw, K_daily) are Kotlin's: here they are opaque bytes.
//!
//! In the app's data directory (`noBackupFilesDir`), each written atomically and owner-only ([`storage::save_json`]):
//!
//! * [`VAULT_FILE`]: the K_hw-sealed key blob, the Keystore alias Kotlin sealed it with, and the body blob (DK).
//! * [`DAILY_FILE`]: the biometric unlock (13.2): the K_daily-sealed daily blob, its alias, DDK and a plain copy of
//!   the stamp. DDK opens nothing without the K_daily layer, which needs a Class 3 biometric.
//! * [`STAY_FILE`]: the vault that stays unlocked (round 7c): the K_stay-sealed stay blob, its alias, SK and a plain
//!   copy of its creation time. SK opens nothing without the K_stay layer, which works only while the phone is
//!   unlocked.
//! * [`USE_FILE`]: the last use of the vault (round 7c), sealed under DK, and its plain copy: 90 days without a use
//!   and the passphrase is needed again.
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
use rosty_core::vault::{self, DailyStamp, DataKey, Entropy, UseClock};
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use crate::storage;

pub const VAULT_FILE: &str = "wa-vault.json";
pub const DAILY_FILE: &str = "wa-vault-daily.json";
pub const STAY_FILE: &str = "wa-vault-stay.json";
pub const USE_FILE: &str = "wa-vault-use.json";
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
    // Round 7a's `seen` record and its plain `last_unlock_ms` are no longer read: an older file keeps them and they
    // are ignored (the use clock of round 7c replaces them).
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

    /// The use clock of the plain copy of the stamp (the sealed stamp is authoritative).
    pub fn plain_clock(&self) -> UseClock {
        UseClock::new(self.stamp.unlocked_ms, None)
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

    /// Ends the biometric unlock (90 days without a use, biometric change, five failures, a new passphrase).
    pub fn remove(dir: &Path) -> io::Result<Vec<String>> {
        remove(dir, DAILY_FILE)
    }
}

/// `wa-vault-stay.json` (round 7c).
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StayFile {
    pub version: u32,
    /// The Android Keystore alias of K_stay.
    pub alias: String,
    /// The stay blob (`rosty_core::vault::seal_stay`) as Kotlin sealed it with K_stay.
    #[serde(with = "b64")]
    pub sealed: Vec<u8>,
    /// SK, the key of the stay blob.
    #[serde(
        serialize_with = "b64::serialize",
        deserialize_with = "b64::deserialize_secret"
    )]
    pub stay_key: Zeroizing<Vec<u8>>,
    /// A plain copy of the creation time sealed inside, for the state without the Keystore. The sealed one is
    /// authoritative.
    pub created_ms: i64,
}

impl std::fmt::Debug for StayFile {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("StayFile")
            .field("alias", &self.alias)
            .field("sealed", &self.sealed.len())
            .field("created_ms", &self.created_ms)
            .finish_non_exhaustive()
    }
}

impl Versioned for StayFile {
    fn version(&self) -> u32 {
        self.version
    }
}

impl StayFile {
    pub fn new(alias: &str, sealed: Vec<u8>, stay_key: &vault::StayKey, created_ms: i64) -> Self {
        Self {
            version: FILE_VERSION,
            alias: alias.to_owned(),
            sealed,
            stay_key: Zeroizing::new(stay_key.as_bytes().to_vec()),
            created_ms,
        }
    }

    /// The use clock of the plain copy of the creation time.
    pub fn plain_clock(&self) -> UseClock {
        UseClock::new(self.created_ms, None)
    }

    pub fn load(dir: &Path) -> io::Result<Option<Self>> {
        load(dir, STAY_FILE)
    }

    pub fn save(&self, dir: &Path) -> io::Result<()> {
        storage::save_json(dir, STAY_FILE, self)
    }

    /// SK; `None` for a damaged file.
    pub fn key(&self) -> Option<vault::StayKey> {
        vault::StayKey::from_slice(&self.stay_key)
    }

    /// Ends the silent unlock (a lock by the person, 90 days without a use, a dead Keystore key).
    pub fn remove(dir: &Path) -> io::Result<Vec<String>> {
        remove(dir, STAY_FILE)
    }
}

/// `wa-vault-use.json` (round 7c): the last use, sealed under DK, and a plain copy.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct UseFile {
    pub version: u32,
    /// `rosty_core::vault::seal_use`, under DK.
    #[serde(with = "b64")]
    pub sealed: Vec<u8>,
    /// A plain copy of the time sealed inside, for the state without DK. The sealed one is authoritative.
    pub used_ms: i64,
}

impl Versioned for UseFile {
    fn version(&self) -> u32 {
        self.version
    }
}

impl UseFile {
    /// A use at `used_ms`, sealed under DK.
    pub fn new(key: &DataKey, used_ms: i64, entropy: &mut impl Entropy) -> Self {
        Self {
            version: FILE_VERSION,
            sealed: vault::seal_use(key, used_ms, vault::nonce(entropy)),
            used_ms,
        }
    }

    /// The sealed time, when the record opens under DK; `None` otherwise (the stricter rule).
    pub fn sealed_ms(&self, key: &DataKey) -> Option<i64> {
        vault::open_use(key, &self.sealed).ok()
    }

    pub fn load(dir: &Path) -> io::Result<Option<Self>> {
        load(dir, USE_FILE)
    }

    pub fn save(&self, dir: &Path) -> io::Result<()> {
        storage::save_json(dir, USE_FILE, self)
    }

    pub fn remove(dir: &Path) -> io::Result<Vec<String>> {
        remove(dir, USE_FILE)
    }
}

/// The last use of the vault in `dir` as the plain copies tell it (the stay wrap, the biometric unlock and the use
/// record); `None` when no wrap opens the vault without the passphrase. For the state only: an unlock checks the
/// sealed copies.
pub fn plain_clock(dir: &Path) -> Option<UseClock> {
    let stay = StayFile::load(dir).ok().flatten();
    let daily = DailyFile::load(dir).ok().flatten();
    let used = UseFile::load(dir).ok().flatten().map(|file| file.used_ms);
    UseClock::latest(
        stay.map(|file| file.plain_clock())
            .into_iter()
            .chain(daily.map(|file| file.plain_clock()))
            .map(|clock| UseClock::new(clock.last_use_ms, used)),
    )
}

/// The sealed use clock of a wrap stamped at `since_ms`: the use record of `dir`, when it opens under DK, moves it on.
pub fn sealed_clock(dir: &Path, key: &DataKey, since_ms: i64) -> UseClock {
    let used = UseFile::load(dir)
        .ok()
        .flatten()
        .and_then(|file| file.sealed_ms(key));
    UseClock::new(since_ms, used)
}

/// Every wrap that opens the vault without the passphrase goes, with the use record (90 days without a use, or a
/// clock set back): the passphrase is needed again. Answers what was deleted.
pub fn expire(dir: &Path) -> io::Result<Vec<String>> {
    let mut deleted = Vec::new();
    for name in [STAY_FILE, DAILY_FILE, USE_FILE] {
        deleted.extend(remove(dir, name)?);
    }
    Ok(deleted)
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

/// Removes the vault, the biometric and silent unlocks, the use record and the caches (reset, forgotten passphrase,
/// unlink). Answers what was deleted.
pub fn remove_all(dir: &Path) -> io::Result<Vec<String>> {
    let mut deleted = Vec::new();
    for name in [
        VAULT_FILE,
        DAILY_FILE,
        STAY_FILE,
        USE_FILE,
        CACHE_FILE,
        HISTORY_FILE,
    ] {
        deleted.extend(remove(dir, name)?);
    }
    Ok(deleted)
}
