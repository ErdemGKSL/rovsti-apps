//! `vault.*`: the WhatsApp vault (`_bmad-output/kotlin-ui/plans/wa-vault.md`, Implementation notes).
//!
//! DK and the connect key live here, in a session handle, and nowhere else: Kotlin gets the handle and the
//! session's state, never a key. Releasing the handle (`vault.lock`, `RustNative.release`) zeroizes them and
//! releases every `wa.open` gateway opened from the session. One session per process: an unlock replaces the last.
//!
//! The Keystore layers are Kotlin's. What crosses the boundary is ciphertext only: the key blob (DK under KEK_p)
//! that Kotlin seals with K_hw, and the daily blob (DK under DDK) that it seals with K_daily. The one secret Kotlin
//! holds is the passphrase, which it shows once and the user types; Rust zeroizes its copies (the request and
//! answer buffers of every `vault.*` call, see `jni_exports`).
//!
//! Randomness comes from the OS (`getrandom`); `rosty_core::vault` stays pure.

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use rosty_app_core::wa_vault::wipe::{self, AppDirs, WipeReport};
use rosty_app_core::wa_vault::{self, CachedPicture, DailyFile, VaultCache, VaultFile};
use rosty_core::vault::{
    self, DailyKey, DailyRefusal, DailyStamp, DataKey, Kdf, Passphrase, PassphraseError, VaultBody,
    VaultError,
};
use rosty_wa_proto::{ConnectKey, GatewayUrl};
use serde::{Deserialize, Serialize};
use serde_json::json;
use zeroize::Zeroizing;

use super::registry::{Case, OpSpec, PLACEHOLDER_HANDLE};
use crate::envelope::{ErrorKind, FfiError};
use crate::op;
use crate::{handles, state};

pub(super) const OPS: &[OpSpec] = &[
    op!("vault.state", StateReq, StateAns, vault_state, state_cases),
    op!("vault.create", CreateReq, KeysAns, create, create_cases),
    op!("vault.commit", CommitReq, StateAns, commit, commit_cases),
    op!("vault.unlock", UnlockReq, UnlockedAns, unlock, unlock_cases),
    op!(
        "vault.unlock_daily",
        DailyUnlockReq,
        UnlockedAns,
        unlock_daily,
        unlock_daily_cases
    ),
    op!(
        "vault.daily_store",
        DailyStoreReq,
        StateAns,
        daily_store,
        daily_store_cases
    ),
    op!(
        "vault.daily_forget",
        DirReq,
        StateAns,
        daily_forget,
        daily_forget_cases
    ),
    op!("vault.lock", LockReq, StateAns, lock, lock_cases),
    op!("vault.change", HandleReq, KeysAns, change, change_cases),
    op!("vault.reset", DirReq, ResetAns, reset, reset_cases),
    op!(
        "vault.wipe_plaintext",
        WipeReq,
        WipeReport,
        wipe_plaintext,
        wipe_cases
    ),
    op!(
        "vault.pictures",
        PicturesReq,
        PicturesAns,
        pictures,
        pictures_cases
    ),
    op!(
        "vault.picture_put",
        PicturePutReq,
        PicturePutAns,
        picture_put,
        picture_put_cases
    ),
];

/// Key blobs of new vaults and passphrase changes use this KDF (`vault-pbkdf2` builds: PBKDF2).
const KDF: Kdf = Kdf::for_build();

// ---- the session

/// A new DK and the body sealed under it, waiting for Kotlin's K_hw seal of the key blob (`vault.commit`).
struct Pending {
    key: DataKey,
    body_blob: Vec<u8>,
}

/// A daily blob waiting for Kotlin's K_daily seal (`vault.daily_store`).
struct PendingDaily {
    key: DailyKey,
    stamp: DailyStamp,
}

/// What an unlocked (or newly created) vault holds in memory.
pub(crate) struct Session {
    dir: PathBuf,
    key: DataKey,
    body: VaultBody,
    /// False between `vault.create` and `vault.commit`: nothing is on disk yet, and `wa.open` does not use it.
    committed: bool,
    pending: Option<Pending>,
    daily: Option<PendingDaily>,
    /// `wa.open` handles that took the connect key from this session.
    gateways: Vec<i64>,
    /// The identity and picture cache (`wa-vault-cache.bin`, plan 13.5) once read or written in this session.
    cache: Option<VaultCache>,
}

impl Drop for Session {
    /// DK and the body zeroize themselves; the gateways holding a copy of the connect key are released too.
    fn drop(&mut self) {
        for handle in self.gateways.drain(..) {
            handles::global().release(handle);
        }
    }
}

/// The live session handle, if any.
static CURRENT: Mutex<Option<i64>> = Mutex::new(None);

fn current() -> Option<i64> {
    *CURRENT.lock().unwrap_or_else(|e| e.into_inner())
}

/// Makes `handle` the process's session; the previous one is released (locked).
fn set_current(handle: i64) {
    let previous = CURRENT
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .replace(handle);
    if let Some(previous) = previous.filter(|&p| p != handle) {
        handles::global().release(previous);
    }
}

fn session<R>(handle: i64, f: impl FnOnce(&mut Session) -> R) -> Result<R, FfiError> {
    handles::global()
        .with(handle, f)
        .ok_or_else(|| super::wa::not_found("vault", handle).with_detail(json!({"code": "locked"})))
}

/// The gateway URL and connect key of the unlocked session, for `wa.open`.
pub(crate) struct VaultLink {
    pub session: i64,
    pub url: String,
    pub key: Zeroizing<String>,
}

/// `Some` while a committed session is open.
pub(crate) fn unlocked() -> Option<VaultLink> {
    let handle = current()?;
    handles::global()
        .with(handle, |s: &mut Session| {
            s.committed.then(|| VaultLink {
                session: handle,
                url: s.body.gateway_url.clone(),
                key: Zeroizing::new(s.body.connect_key().to_owned()),
            })
        })
        .flatten()
}

/// Ties a gateway handle to the session it took its key from: locking releases it.
pub(crate) fn adopt_gateway(session_handle: i64, gateway: i64) {
    let adopted =
        handles::global().with(session_handle, |s: &mut Session| s.gateways.push(gateway));
    if adopted.is_none() {
        // Locked in between: the gateway must not outlive it.
        handles::global().release(gateway);
    }
}

fn os_entropy(out: &mut [u8]) {
    getrandom::fill(out).expect("the OS random source is available");
}

// ---- errors

fn coded(kind: ErrorKind, message: impl Into<String>, code: &str) -> FfiError {
    FfiError::new(kind, message).with_detail(json!({ "code": code }))
}

fn passphrase_error(error: PassphraseError) -> FfiError {
    let code = match error {
        PassphraseError::Length { .. } => "length",
        PassphraseError::Character => "character",
        PassphraseError::Typo => "typo",
    };
    coded(ErrorKind::Invalid, error.to_string(), code)
}

fn vault_error(error: VaultError) -> FfiError {
    match error {
        VaultError::WrongPassphrase => {
            coded(ErrorKind::Auth, error.to_string(), "wrong_passphrase")
        }
        other => coded(ErrorKind::Parse, other.to_string(), "damaged"),
    }
}

fn file_error(error: std::io::Error) -> FfiError {
    if error.kind() == std::io::ErrorKind::InvalidData {
        coded(ErrorKind::Parse, "the vault file is damaged", "damaged")
    } else {
        FfiError::from(error)
    }
}

fn b64(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}

fn from_b64(field: &str, text: &str) -> Result<Vec<u8>, FfiError> {
    STANDARD
        .decode(text.trim())
        .map_err(|_| FfiError::invalid(format!("{field} is not base64")))
}

fn dir_of(dir: &Option<String>) -> Result<PathBuf, FfiError> {
    super::contacts::data_dir(dir)
}

fn vault_file(dir: &Path) -> Result<VaultFile, FfiError> {
    VaultFile::load(dir).map_err(file_error)?.ok_or_else(|| {
        coded(
            ErrorKind::NotFound,
            "there is no WhatsApp vault",
            "no_vault",
        )
    })
}

// ---- vault.state

#[derive(Deserialize)]
struct StateReq {
    #[serde(default)]
    dir: Option<String>,
    /// With `now_ms`: a daily unlock that lapsed (reboot, 72 h) is deleted and named in `daily_dropped`.
    #[serde(default)]
    boot_count: Option<u64>,
    #[serde(default)]
    now_ms: Option<i64>,
}

#[derive(Serialize)]
struct VaultInfo {
    hw_alias: String,
    /// The K_hw-sealed key blob: Kotlin decrypts it and passes the result to `vault.unlock`.
    hw_sealed: String,
}

#[derive(Serialize)]
struct DailyInfo {
    alias: String,
    /// The K_daily-sealed daily blob: Kotlin decrypts it and passes the result to `vault.unlock_daily`.
    sealed: String,
    boot_count: u64,
    unlocked_ms: i64,
}

#[derive(Serialize)]
struct UnlockedInfo {
    handle: i64,
    gateway_url: String,
    instance_id: String,
}

#[derive(Serialize)]
struct StateAns {
    #[serde(skip_serializing_if = "Option::is_none")]
    vault: Option<VaultInfo>,
    #[serde(skip_serializing_if = "Option::is_none")]
    daily: Option<DailyInfo>,
    #[serde(skip_serializing_if = "Option::is_none")]
    daily_dropped: Option<DailyRefusal>,
    /// A plaintext `whatsapp-link.json` is still there: the migration (plan 8) has not run.
    legacy_link: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    unlocked: Option<UnlockedInfo>,
}

fn state_of(dir: &Path, check: Option<(u64, i64)>) -> Result<StateAns, FfiError> {
    let vault = VaultFile::load(dir).map_err(file_error)?;
    let mut daily = DailyFile::load(dir).ok().flatten();
    let mut daily_dropped = None;
    if vault.is_none() && daily.is_some() {
        // A daily blob of a vault that is gone opens nothing.
        DailyFile::remove(dir)?;
        daily = None;
    }
    if let (Some(file), Some((boot_count, now_ms))) = (&daily, check)
        && let Err(refusal) = file.stamp.check(boot_count, now_ms)
    {
        DailyFile::remove(dir)?;
        daily = None;
        daily_dropped = Some(refusal);
    }
    let unlocked = current().and_then(|handle| {
        handles::global()
            .with(handle, |s: &mut Session| {
                (s.committed && s.dir == dir).then(|| UnlockedInfo {
                    handle,
                    gateway_url: s.body.gateway_url.clone(),
                    instance_id: s.body.instance_id.clone(),
                })
            })
            .flatten()
    });
    Ok(StateAns {
        vault: vault.map(|v| VaultInfo {
            hw_alias: v.hw_alias,
            hw_sealed: b64(&v.hw_sealed),
        }),
        daily: daily.map(|d| DailyInfo {
            alias: d.alias.clone(),
            sealed: b64(&d.sealed),
            boot_count: d.stamp.boot_count,
            unlocked_ms: d.stamp.unlocked_ms,
        }),
        daily_dropped,
        legacy_link: wipe::legacy_link(dir).is_some(),
        unlocked,
    })
}

fn vault_state(req: StateReq) -> Result<StateAns, FfiError> {
    let check = req.boot_count.zip(req.now_ms);
    state_of(&dir_of(&req.dir)?, check)
}

/// The seeded vault of the fixtures (`rosty_app_core/tests/fixtures/dirs/wa_vault`): its passphrase, the key blob
/// Kotlin would get back from K_hw (the seed "seals" with the identity), the daily blob and its stamp.
pub(crate) mod seed {
    pub const PASSPHRASE: &str = "0000-0000-0000-0000-0000-0000-0000";
    pub const BOOT_COUNT: u64 = 7;
    pub const UNLOCKED_MS: i64 = 1_800_000_000_000;

    /// A well-formed passphrase that is not the seed's.
    pub fn other_passphrase() -> String {
        rosty_core::vault::Passphrase::from_entropy(&[1; rosty_core::vault::ENTROPY_BYTES])
            .grouped()
            .to_string()
    }
}

fn state_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("wa_vault"),
        Case::named("empty", json!({"dir": "$DIR/nothing-here"})).seeded("wa_vault"),
        Case::named("legacy", json!({"dir": "$DIR"})).seeded("wa"),
        Case::named(
            "rebooted",
            json!({"dir": "$DIR", "boot_count": seed::BOOT_COUNT + 1, "now_ms": seed::UNLOCKED_MS}),
        )
        .seeded("wa_vault"),
        Case::named(
            "daily_ok",
            json!({"dir": "$DIR", "boot_count": seed::BOOT_COUNT, "now_ms": seed::UNLOCKED_MS + 60_000}),
        )
        .seeded("wa_vault"),
    ]
}

// ---- vault.create

#[derive(Deserialize)]
struct LinkIn {
    gateway_url: String,
    instance_id: String,
    key: Zeroizing<String>,
}

#[derive(Deserialize)]
struct CreateReq {
    #[serde(default)]
    dir: Option<String>,
    /// The link to protect. Left out: the plaintext `whatsapp-link.json` of `dir` (the migration).
    #[serde(default)]
    link: Option<LinkIn>,
}

#[derive(Serialize)]
struct KeysAns {
    handle: i64,
    /// The new passphrase, grouped; shown once.
    passphrase: String,
    /// The key blob for Kotlin to seal with K_hw and hand to `vault.commit`.
    seal: String,
}

fn checked_body(url: &str, instance_id: &str, key: &str) -> Result<VaultBody, FfiError> {
    let url =
        GatewayUrl::parse(url).map_err(|e| coded(ErrorKind::Invalid, e.to_string(), "bad_url"))?;
    ConnectKey::parse(key.trim())
        .map_err(|e| coded(ErrorKind::Invalid, e.to_string(), "bad_key"))?;
    if instance_id.trim().is_empty() {
        return Err(coded(
            ErrorKind::Invalid,
            "the instance id is empty",
            "bad_instance",
        ));
    }
    Ok(VaultBody::new(
        &url.as_str(),
        instance_id.trim(),
        key.trim(),
    ))
}

fn keys_answer(handle: i64, created: &vault::Created) -> KeysAns {
    KeysAns {
        handle,
        passphrase: created.passphrase.grouped().to_string(),
        seal: b64(&created.key_blob),
    }
}

fn create(req: CreateReq) -> Result<KeysAns, FfiError> {
    let dir = dir_of(&req.dir)?;
    if VaultFile::exists(&dir) {
        return Err(coded(
            ErrorKind::Invalid,
            "a WhatsApp vault exists; reset it first",
            "vault_exists",
        ));
    }
    let body = match &req.link {
        Some(link) => checked_body(&link.gateway_url, &link.instance_id, &link.key)?,
        None => {
            let legacy = wipe::legacy_link(&dir).ok_or_else(|| {
                coded(
                    ErrorKind::NotFound,
                    "there is no WhatsApp link to protect",
                    "no_link",
                )
            })?;
            let body = checked_body(&legacy.gateway_url, &legacy.instance_id, &legacy.key);
            let mut key = legacy.key;
            zeroize::Zeroize::zeroize(&mut key);
            body?
        }
    };
    let created = vault::create(&body, KDF, &mut os_entropy).map_err(vault_error)?;
    let pending = Pending {
        key: created.key.clone(),
        body_blob: created.body_blob.clone(),
    };
    let handle = handles::global().insert(Session {
        dir,
        key: created.key.clone(),
        body,
        committed: false,
        pending: Some(pending),
        daily: None,
        gateways: Vec::new(),
        cache: None,
    });
    Ok(keys_answer(handle, &created))
}

fn create_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("wa"),
        Case::named(
            "link",
            json!({"dir": "$DIR/new", "link": {
                "gateway_url": "https://rosty.erdemdev.tr",
                "instance_id": "inst1",
                "key": super::wa::fixture::KEY,
            }}),
        )
        .seeded("wa"),
        Case::named("exists", json!({"dir": "$DIR"})).seeded("wa_vault"),
        Case::named("no_link", json!({"dir": "$DIR/nothing-here"})).seeded("wa"),
        Case::named(
            "bad_key",
            json!({"dir": "$DIR/new", "link": {
                "gateway_url": "https://rosty.erdemdev.tr",
                "instance_id": "inst1",
                "key": "rwk_short",
            }}),
        )
        .seeded("wa"),
    ]
    .into_iter()
    .map(|case| case.volatile(&["passphrase", "seal"]))
    .collect()
}

// ---- vault.commit

#[derive(Deserialize)]
struct CommitReq {
    handle: i64,
    /// The Keystore alias Kotlin sealed with (a new one per passphrase).
    hw_alias: String,
    /// `seal` of `vault.create` / `vault.change`, sealed with K_hw, base64.
    hw_sealed: String,
}

fn commit(req: CommitReq) -> Result<StateAns, FfiError> {
    let hw_sealed = from_b64("hw_sealed", &req.hw_sealed)?;
    if req.hw_alias.trim().is_empty() || hw_sealed.is_empty() {
        return Err(FfiError::invalid("hw_alias and hw_sealed are required"));
    }
    let dir = session(req.handle, |s: &mut Session| -> Result<PathBuf, FfiError> {
        let pending = s.pending.take().ok_or_else(|| {
            coded(
                ErrorKind::Invalid,
                "there is no new passphrase to commit",
                "nothing_to_commit",
            )
        })?;
        let file = VaultFile::new(req.hw_alias.trim(), hw_sealed, pending.body_blob.clone());
        if let Err(error) = file.save(&s.dir) {
            s.pending = Some(pending);
            return Err(error.into());
        }
        s.key = pending.key;
        s.committed = true;
        // A daily blob seals the old DK, and a cache sealed under it no longer opens.
        let _ = DailyFile::remove(&s.dir);
        let _ = std::fs::remove_file(s.dir.join(wa_vault::CACHE_FILE));
        s.daily = None;
        Ok(s.dir.clone())
    })??;
    set_current(req.handle);
    state_of(&dir, None)
}

fn commit_cases() -> Vec<Case> {
    let create = || ("vault.create", json!({"dir": "$DIR"}));
    vec![
        Case::new(json!({"handle": PLACEHOLDER_HANDLE, "hw_alias": "rosty.wa.k1", "hw_sealed": "AQIDBA=="}))
            .seeded("wa")
            .after(create().0, create().1),
        Case::named("gone", json!({"handle": 42, "hw_alias": "rosty.wa.k1", "hw_sealed": "AQIDBA=="})),
        Case::named(
            "twice",
            json!({"handle": PLACEHOLDER_HANDLE, "hw_alias": "rosty.wa.k1", "hw_sealed": "AQIDBA=="}),
        )
        .seeded("wa_vault")
        .after("vault.unlock", unlock_request(json!({}))),
    ]
}

// ---- vault.unlock

#[derive(Deserialize)]
struct UnlockReq {
    #[serde(default)]
    dir: Option<String>,
    /// The key blob, as K_hw decrypted `hw_sealed`, base64.
    key_blob: String,
    passphrase: Zeroizing<String>,
    /// `Settings.Global.BOOT_COUNT` and the wall clock, sealed into the daily blob.
    boot_count: u64,
    now_ms: i64,
}

#[derive(Serialize)]
struct UnlockedAns {
    handle: i64,
    /// A fresh daily blob for Kotlin to seal with K_daily and hand to `vault.daily_store` (optional: without it,
    /// the next cold start needs the passphrase).
    daily_seal: String,
}

/// Opens the body of the vault in `dir` with DK and makes the session; a daily blob is prepared.
fn open_session(dir: PathBuf, key: DataKey, stamp: DailyStamp) -> Result<UnlockedAns, FfiError> {
    let file = vault_file(&dir)?;
    let body = vault::open_body(&key, &file.body).map_err(vault_error)?;
    let daily_key = DailyKey::generate(&mut os_entropy);
    let daily_blob = vault::seal_daily(&daily_key, &key, stamp, vault::nonce(&mut os_entropy));
    let handle = handles::global().insert(Session {
        dir,
        key,
        body,
        committed: true,
        pending: None,
        daily: Some(PendingDaily {
            key: daily_key,
            stamp,
        }),
        gateways: Vec::new(),
        cache: None,
    });
    set_current(handle);
    Ok(UnlockedAns {
        handle,
        daily_seal: b64(&daily_blob),
    })
}

fn unlock(req: UnlockReq) -> Result<UnlockedAns, FfiError> {
    let dir = dir_of(&req.dir)?;
    let passphrase = Passphrase::parse(&req.passphrase).map_err(passphrase_error)?;
    let key_blob = Zeroizing::new(from_b64("key_blob", &req.key_blob)?);
    // The file first: no KDF work without a vault.
    vault_file(&dir)?;
    let key = vault::unwrap_key(&key_blob, &passphrase).map_err(vault_error)?;
    open_session(
        dir,
        key,
        DailyStamp {
            boot_count: req.boot_count,
            unlocked_ms: req.now_ms,
        },
    )
}

/// An unlock request for the seeded vault; `extra` overrides fields.
fn unlock_request(extra: serde_json::Value) -> serde_json::Value {
    let mut req = json!({
        "dir": "$DIR",
        "key_blob": seed_file("wa-vault.json")["hw_sealed"],
        "passphrase": seed::PASSPHRASE,
        "boot_count": seed::BOOT_COUNT,
        "now_ms": seed::UNLOCKED_MS,
    });
    if let (Some(req), Some(extra)) = (req.as_object_mut(), extra.as_object()) {
        req.extend(extra.clone());
    }
    req
}

/// The unlock of the seeded vault, as other domains' fixtures open it (`wa.open` with an unlocked vault).
pub(super) fn seed_unlock_request() -> serde_json::Value {
    unlock_request(json!({}))
}

/// Unit tests that open a session, or that open a gateway with a key, take this lock: one session per process.
#[cfg(test)]
pub(crate) fn test_lock() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: Mutex<()> = Mutex::new(());
    LOCK.lock().unwrap_or_else(|e| e.into_inner())
}

/// A JSON file of the seeded vault directory.
fn seed_file(name: &str) -> serde_json::Value {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../rosty_app_core/tests/fixtures/dirs/wa_vault")
        .join(name);
    std::fs::read(&path)
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default()
}

fn unlock_cases() -> Vec<Case> {
    vec![
        Case::new(unlock_request(json!({}))).seeded("wa_vault"),
        Case::named(
            "wrong_passphrase",
            unlock_request(json!({"passphrase": seed::other_passphrase()})),
        )
        .seeded("wa_vault"),
        Case::named(
            "typo",
            unlock_request(json!({"passphrase": "0000-0000-0000-0000-0000-0000-0001"})),
        )
        .seeded("wa_vault"),
        Case::named(
            "no_vault",
            unlock_request(json!({"dir": "$DIR/nothing-here"})),
        )
        .seeded("wa_vault"),
        Case::named("damaged", unlock_request(json!({"key_blob": "UldWSwE="}))).seeded("wa_vault"),
    ]
    .into_iter()
    .map(|case| case.volatile(&["daily_seal"]))
    .collect()
}

// ---- vault.unlock_daily

#[derive(Deserialize)]
struct DailyUnlockReq {
    #[serde(default)]
    dir: Option<String>,
    /// The daily blob, as K_daily decrypted `daily.sealed`, base64.
    daily_blob: String,
    boot_count: u64,
    now_ms: i64,
}

fn unlock_daily(req: DailyUnlockReq) -> Result<UnlockedAns, FfiError> {
    let dir = dir_of(&req.dir)?;
    let blob = from_b64("daily_blob", &req.daily_blob)?;
    let file = DailyFile::load(&dir)
        .map_err(file_error)?
        .ok_or_else(|| coded(ErrorKind::NotFound, "there is no daily unlock", "no_daily"))?;
    let daily_key = file
        .key()
        .ok_or_else(|| coded(ErrorKind::Parse, "the daily unlock is damaged", "damaged"))?;
    let (key, stamp) = vault::open_daily(&daily_key, &blob).map_err(|_| {
        let _ = DailyFile::remove(&dir);
        coded(ErrorKind::Parse, "the daily unlock is damaged", "damaged")
    })?;
    if let Err(refusal) = stamp.check(req.boot_count, req.now_ms) {
        DailyFile::remove(&dir)?;
        return Err(FfiError::new(ErrorKind::Auth, refusal.to_string())
            .with_detail(json!({"code": "daily_expired", "reason": refusal})));
    }
    open_session(
        dir,
        key,
        DailyStamp {
            boot_count: req.boot_count,
            unlocked_ms: req.now_ms,
        },
    )
}

fn daily_request(extra: serde_json::Value) -> serde_json::Value {
    let mut req = json!({
        "dir": "$DIR",
        "daily_blob": seed_file("wa-vault-daily.json")["sealed"],
        "boot_count": seed::BOOT_COUNT,
        "now_ms": seed::UNLOCKED_MS + 3_600_000,
    });
    if let (Some(req), Some(extra)) = (req.as_object_mut(), extra.as_object()) {
        req.extend(extra.clone());
    }
    req
}

fn unlock_daily_cases() -> Vec<Case> {
    vec![
        Case::new(daily_request(json!({}))).seeded("wa_vault"),
        Case::named(
            "rebooted",
            daily_request(json!({"boot_count": seed::BOOT_COUNT + 1})),
        )
        .seeded("wa_vault"),
        Case::named(
            "idle",
            daily_request(json!({"now_ms": seed::UNLOCKED_MS + vault::DAILY_MAX_IDLE_MS + 1})),
        )
        .seeded("wa_vault"),
        Case::named("damaged", daily_request(json!({"daily_blob": "AAAA"}))).seeded("wa_vault"),
        Case::named(
            "no_daily",
            daily_request(json!({"dir": "$DIR/nothing-here"})),
        )
        .seeded("wa_vault"),
    ]
    .into_iter()
    .map(|case| case.volatile(&["daily_seal"]))
    .collect()
}

// ---- vault.daily_store

#[derive(Deserialize)]
struct DailyStoreReq {
    handle: i64,
    /// The Keystore alias of K_daily.
    alias: String,
    /// `daily_seal` of the unlock, sealed with K_daily, base64.
    sealed: String,
}

fn daily_store(req: DailyStoreReq) -> Result<StateAns, FfiError> {
    let sealed = from_b64("sealed", &req.sealed)?;
    if req.alias.trim().is_empty() || sealed.is_empty() {
        return Err(FfiError::invalid("alias and sealed are required"));
    }
    let dir = session(req.handle, |s: &mut Session| -> Result<PathBuf, FfiError> {
        let daily = s.daily.take().ok_or_else(|| {
            coded(
                ErrorKind::Invalid,
                "there is no daily unlock to store",
                "nothing_to_store",
            )
        })?;
        DailyFile::new(req.alias.trim(), sealed, &daily.key, daily.stamp).save(&s.dir)?;
        Ok(s.dir.clone())
    })??;
    state_of(&dir, None)
}

fn daily_store_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": PLACEHOLDER_HANDLE, "alias": "rosty.wa.d1", "sealed": "BQYH"}))
            .seeded("wa_vault")
            .after("vault.unlock", unlock_request(json!({}))),
        Case::named(
            "gone",
            json!({"handle": 42, "alias": "rosty.wa.d1", "sealed": "BQYH"}),
        ),
    ]
}

// ---- vault.daily_forget

#[derive(Deserialize)]
struct DirReq {
    #[serde(default)]
    dir: Option<String>,
}

fn daily_forget(req: DirReq) -> Result<StateAns, FfiError> {
    let dir = dir_of(&req.dir)?;
    DailyFile::remove(&dir)?;
    state_of(&dir, None)
}

fn daily_forget_cases() -> Vec<Case> {
    vec![Case::new(json!({"dir": "$DIR"})).seeded("wa_vault")]
}

// ---- vault.lock

#[derive(Deserialize)]
struct LockReq {
    /// The session to end; left out: the current one.
    #[serde(default)]
    handle: Option<i64>,
    #[serde(default)]
    dir: Option<String>,
}

fn lock(req: LockReq) -> Result<StateAns, FfiError> {
    let dir = dir_of(&req.dir)?;
    let handle = {
        let mut guard = CURRENT.lock().unwrap_or_else(|e| e.into_inner());
        match req.handle {
            Some(handle) => {
                if *guard == Some(handle) {
                    *guard = None;
                }
                Some(handle)
            }
            None => guard.take(),
        }
    };
    if let Some(handle) = handle {
        handles::global().release(handle);
    }
    state_of(&dir, None)
}

fn lock_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"}))
            .seeded("wa_vault")
            .after("vault.unlock", unlock_request(json!({}))),
        Case::named(
            "handle",
            json!({"dir": "$DIR", "handle": PLACEHOLDER_HANDLE}),
        )
        .seeded("wa_vault")
        .after("vault.unlock", unlock_request(json!({}))),
        Case::named("nothing_open", json!({"dir": "$DIR"})).seeded("wa_vault"),
    ]
}

// ---- vault.change

#[derive(Deserialize)]
struct HandleReq {
    handle: i64,
}

fn change(req: HandleReq) -> Result<KeysAns, FfiError> {
    let body = session(req.handle, |s: &mut Session| {
        s.committed.then(|| s.body.clone())
    })?
    .ok_or_else(|| {
        coded(
            ErrorKind::Invalid,
            "commit the vault first",
            "not_committed",
        )
    })?;
    let created = vault::create(&body, KDF, &mut os_entropy).map_err(vault_error)?;
    session(req.handle, |s: &mut Session| {
        s.pending = Some(Pending {
            key: created.key.clone(),
            body_blob: created.body_blob.clone(),
        });
    })?;
    Ok(keys_answer(req.handle, &created))
}

fn change_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": PLACEHOLDER_HANDLE}))
            .seeded("wa_vault")
            .after("vault.unlock", unlock_request(json!({}))),
        Case::named("locked", json!({"handle": 42})),
    ]
    .into_iter()
    .map(|case| case.volatile(&["passphrase", "seal"]))
    .collect()
}

// ---- vault.reset

#[derive(Serialize)]
struct ResetAns {
    /// The vault files deleted. Kotlin deletes the Keystore keys.
    deleted: Vec<String>,
}

fn reset(req: DirReq) -> Result<ResetAns, FfiError> {
    let dir = dir_of(&req.dir)?;
    let released = {
        let mut guard = CURRENT.lock().unwrap_or_else(|e| e.into_inner());
        let mine = guard.is_some_and(|handle| {
            handles::global()
                .with(handle, |s: &mut Session| s.dir == dir)
                .unwrap_or(false)
        });
        if mine { guard.take() } else { None }
    };
    if let Some(handle) = released {
        handles::global().release(handle);
    }
    let mut deleted = wa_vault::remove_all(&dir)?;
    deleted.sort();
    Ok(ResetAns { deleted })
}

fn reset_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("wa_vault"),
        Case::named("nothing", json!({"dir": "$DIR/nothing-here"})).seeded("wa_vault"),
    ]
}

// ---- vault.wipe_plaintext

#[derive(Deserialize)]
struct WipeReq {
    /// The data directory (`noBackupFilesDir`); left out: the core's.
    #[serde(default)]
    dir: Option<String>,
    /// `filesDir`.
    files_dir: String,
    /// `cacheDir`; left out: the core's.
    #[serde(default)]
    cache_dir: Option<String>,
}

fn wipe_plaintext(req: WipeReq) -> Result<WipeReport, FfiError> {
    let data = dir_of(&req.dir)?;
    if wipe::legacy_link(&data).is_some() && !VaultFile::exists(&data) {
        return Err(coded(
            ErrorKind::Invalid,
            "the link is not in a vault yet; create the vault first",
            "no_vault",
        ));
    }
    let cache = match req.cache_dir {
        Some(dir) => PathBuf::from(dir),
        None => state::dirs()
            .map(|dirs| dirs.cache_dir.clone())
            .ok_or_else(|| FfiError::invalid("init was not called"))?,
    };
    if req.files_dir.trim().is_empty() {
        return Err(FfiError::invalid("files_dir is required"));
    }
    Ok(wipe::wipe_plaintext(&AppDirs {
        data,
        files: PathBuf::from(req.files_dir),
        cache,
    }))
}

fn wipe_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "files_dir": "$DIR/files", "cache_dir": "$DIR/cache"}))
            .seeded("wa_plain"),
        Case::named(
            "no_vault",
            json!({"dir": "$DIR", "files_dir": "$DIR/files", "cache_dir": "$DIR/cache"}),
        )
        .seeded("wa"),
    ]
}

// ---- the encrypted cache (plan 13.5): identity and profile pictures, sealed under DK

/// What the cache keeps of profile pictures at most; the oldest go first.
const MAX_PICTURE_BYTES: usize = 4 * 1024 * 1024;

fn locked() -> FfiError {
    coded(
        ErrorKind::NotFound,
        "the WhatsApp vault is locked",
        "locked",
    )
}

/// The instance id of the unlocked (committed) vault.
pub(crate) fn unlocked_instance() -> Option<String> {
    let handle = current()?;
    handles::global()
        .with(handle, |s: &mut Session| {
            s.committed.then(|| s.body.instance_id.clone())
        })
        .flatten()
}

/// Runs `f` on the cache of the unlocked session (read from `wa-vault-cache.bin` the first time; a cache that
/// does not open under DK is deleted and starts empty) and, with `save`, seals the result under DK and writes it.
/// `locked` while no committed session is open: nothing about WhatsApp is read or written then.
pub(crate) fn with_cache<R>(
    save: bool,
    f: impl FnOnce(&mut VaultCache) -> R,
) -> Result<R, FfiError> {
    let handle = current().ok_or_else(locked)?;
    handles::global()
        .with(handle, |s: &mut Session| -> Result<R, FfiError> {
            if !s.committed {
                return Err(locked());
            }
            let cache = match s.cache.take() {
                Some(cache) => cache,
                None => match VaultCache::load(&s.dir, &s.key) {
                    Ok(cache) => cache.unwrap_or_default(),
                    Err(error) if error.kind() == std::io::ErrorKind::InvalidData => {
                        let _ = std::fs::remove_file(s.dir.join(wa_vault::CACHE_FILE));
                        VaultCache::default()
                    }
                    Err(error) => return Err(error.into()),
                },
            };
            let cache = s.cache.insert(cache);
            let out = f(cache);
            if save {
                cache.save(&s.dir, &s.key, &mut os_entropy)?;
            }
            Ok(out)
        })
        .ok_or_else(locked)?
}

#[derive(Deserialize)]
struct PicturesReq {
    /// Only these people; left out: every cached picture.
    #[serde(default)]
    jids: Option<Vec<String>>,
}

#[derive(Serialize)]
struct PictureOut {
    jid: String,
    picture_id: String,
    /// The JPEG, base64.
    jpeg: String,
}

#[derive(Serialize)]
struct PicturesAns {
    pictures: Vec<PictureOut>,
}

fn pictures(req: PicturesReq) -> Result<PicturesAns, FfiError> {
    with_cache(false, |cache| PicturesAns {
        pictures: cache
            .pictures
            .iter()
            .filter(|p| req.jids.as_ref().is_none_or(|jids| jids.contains(&p.jid)))
            .map(|p| PictureOut {
                jid: p.jid.clone(),
                picture_id: p.picture_id.clone(),
                jpeg: b64(&p.jpeg),
            })
            .collect(),
    })
}

/// The seeded vault, unlocked, with one cached picture.
fn with_picture(case: Case) -> Case {
    case.seeded("wa_vault")
        .after("vault.unlock", unlock_request(json!({})))
        .after("vault.picture_put", picture_req())
}

fn picture_req() -> serde_json::Value {
    json!({"jid": "100000000001@s.whatsapp.net", "picture_id": "p1", "jpeg": "/9j/4AAQ"})
}

fn pictures_cases() -> Vec<Case> {
    vec![
        with_picture(Case::new(json!({}))),
        with_picture(Case::named(
            "some",
            json!({"jids": ["100000000002@s.whatsapp.net"]}),
        )),
        Case::named("locked", json!({})),
    ]
}

#[derive(Deserialize)]
struct PicturePutReq {
    jid: String,
    /// With `jpeg`: the picture to keep (it replaces the person's last one). Both left out: the person has no
    /// picture any more.
    #[serde(default)]
    picture_id: Option<String>,
    #[serde(default)]
    jpeg: Option<String>,
}

#[derive(Serialize)]
struct PicturePutAns {
    /// The vault session whose cache was written.
    handle: i64,
    /// Pictures in the cache now.
    pictures: u32,
}

fn picture_put(req: PicturePutReq) -> Result<PicturePutAns, FfiError> {
    let jid = req.jid.trim().to_owned();
    if jid.is_empty() {
        return Err(FfiError::invalid("jid is required"));
    }
    let picture = match (req.picture_id, req.jpeg) {
        (Some(id), Some(jpeg)) if !id.trim().is_empty() => Some(CachedPicture {
            jid: jid.clone(),
            picture_id: id.trim().to_owned(),
            jpeg: from_b64("jpeg", &jpeg)?,
        }),
        (None, None) => None,
        _ => {
            return Err(FfiError::invalid(
                "picture_id and jpeg go together, or neither",
            ));
        }
    };
    let handle = current().ok_or_else(locked)?;
    with_cache(true, |cache| {
        cache.pictures.retain(|p| p.jid != jid);
        cache.pictures.extend(picture);
        let mut total: usize = cache.pictures.iter().map(|p| p.jpeg.len()).sum();
        while total > MAX_PICTURE_BYTES && cache.pictures.len() > 1 {
            total -= cache.pictures.remove(0).jpeg.len();
        }
        PicturePutAns {
            handle,
            pictures: cache.pictures.len() as u32,
        }
    })
}

fn picture_put_cases() -> Vec<Case> {
    vec![
        Case::new(picture_req())
            .seeded("wa_vault")
            .after("vault.unlock", unlock_request(json!({}))),
        with_picture(Case::named(
            "removed",
            json!({"jid": "100000000001@s.whatsapp.net"}),
        )),
        Case::named("locked", picture_req()),
        Case::named(
            "half",
            json!({"jid": "100000000001@s.whatsapp.net", "picture_id": "p1"}),
        ),
    ]
}

#[cfg(test)]
mod tests;
