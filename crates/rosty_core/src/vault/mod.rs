//! The WhatsApp vault (`_bmad-output/kotlin-ui/plans/wa-vault.md` 3, 13): the only secret Rosty keeps on disk for
//! WhatsApp is the connect key, sealed under a data key DK that needs both the passphrase and the device's secure
//! hardware. Pure: no I/O, no clock, no OS randomness. The caller passes [`Entropy`].
//!
//! Key hierarchy: KEK_p = KDF(passphrase, salt) ([`Kdf`], Argon2id by default) wraps DK; Kotlin seals that *key
//! blob* again with the Android Keystore key K_hw, which this module never sees. DK seals the *body* (gateway URL,
//! instance id, connect key) and the optional *cache* (identity map and pictures, plan 13.5). For the biometric
//! unlock (13.2) a daily key DDK seals DK in a *daily blob* that Kotlin seals with K_daily. For the vault that stays
//! unlocked (round 7c) a stay key SK seals DK in a *stay blob* that Kotlin seals with K_stay (no prompt, usable only
//! while the phone is unlocked).
//!
//! Layouts (integers big-endian, AES-256-GCM, every header is the associated data of its ciphertext):
//!
//! | blob  | header                                                                            | ciphertext       |
//! |-------|-----------------------------------------------------------------------------------|------------------|
//! | key   | `RWVK` · version · KDF id · 3 × u32 KDF words · salt (16) · nonce (12)  (46 bytes) | DK + tag (48)    |
//! | body  | `RWVB` · version · nonce (12)                                         (17 bytes)  | body JSON + tag  |
//! | cache | `RWVC` · version · nonce (12)                                         (17 bytes)  | cache + tag      |
//! | daily | `RWVD` · version · boot count u64 · last unlock ms i64 · nonce (12)   (33 bytes)  | DK + tag (48)    |
//! | link  | `RWVL` · version · salt (16) · nonce (12)                             (33 bytes)  | DK + tag (48)    |
//! | stay  | `RWVS` · version · created ms i64 · nonce (12)                        (25 bytes)  | DK + tag (48)    |
//! | use   | `RWVU` · version · last use ms i64 · nonce (12)                       (25 bytes)  | tag (16)         |
//!
//! The daily blob's time is the passphrase unlock (its boot count is no longer checked since round 7c). The *use*
//! record ([`UseClock`]) holds the last use: 90 days without one and the vault needs its passphrase again. The *link*
//! wrap ([`wrap_for_link`]) lets a sibling app's hand-over of the connect key open DK; the *stay* wrap
//! ([`seal_stay`]) reopens it silently. Round 7a's `RWVA` *seen* record is no longer written or read.
//!
//! KDF ids: 1 = Argon2id (words: memory KiB, passes, lanes), 2 = PBKDF2-HMAC-SHA256 (words: iterations, 0, 0).
//! Distinct magics keep a blob of one kind from being accepted as another.

mod clock;
mod kdf;
mod link;
mod passphrase;
mod stay;
#[cfg(test)]
mod tests;

use std::fmt;

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use serde::{Deserialize, Serialize};
use thiserror::Error;
use zeroize::{Zeroize, Zeroizing};

pub use clock::{
    CLOCK_SKEW_MS, IdleRefusal, MAX_IDLE_MS, USE_BLOB_LEN, USE_WRITE_INTERVAL_MS, UseClock,
    open_use, seal_use,
};
pub use kdf::{ARGON2ID, Kdf, PBKDF2, calibrate};
pub use link::{LINK_BLOB_LEN, unwrap_for_link, wrap_for_link};
pub use passphrase::{BITS, ENTROPY_BYTES, Passphrase, PassphraseError};
pub use stay::{STAY_BLOB_LEN, open_stay, seal_stay};

/// The version byte of every blob this build writes and reads.
pub const VERSION: u8 = 1;
pub const KEY_LEN: usize = 32;
pub const SALT_LEN: usize = 16;
pub const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;

const MAGIC_KEY: &[u8; 4] = b"RWVK";
const MAGIC_BODY: &[u8; 4] = b"RWVB";
const MAGIC_CACHE: &[u8; 4] = b"RWVC";
const MAGIC_DAILY: &[u8; 4] = b"RWVD";

const KEY_HEADER_LEN: usize = 4 + 1 + 1 + 12 + SALT_LEN + NONCE_LEN;
const DATA_HEADER_LEN: usize = 4 + 1 + NONCE_LEN;
const DAILY_HEADER_LEN: usize = 4 + 1 + 8 + 8 + NONCE_LEN;
/// The length of a key blob and of a daily blob's ciphertext part.
pub const KEY_BLOB_LEN: usize = KEY_HEADER_LEN + KEY_LEN + TAG_LEN;
pub const DAILY_BLOB_LEN: usize = DAILY_HEADER_LEN + KEY_LEN + TAG_LEN;

/// Cryptographically random bytes, supplied by the caller (`getrandom` in the FFI, a counter in tests).
pub trait Entropy {
    fn fill(&mut self, out: &mut [u8]);
}

impl<F: FnMut(&mut [u8])> Entropy for F {
    fn fill(&mut self, out: &mut [u8]) {
        self(out);
    }
}

/// Why a blob does not open.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Error)]
pub enum VaultError {
    #[error("not a WhatsApp vault")]
    NotAVault,
    #[error("vault format version {0} is not supported")]
    UnknownVersion(u8),
    #[error("the vault names an unknown key derivation ({0})")]
    UnknownKdf(u8),
    #[error("the vault is damaged")]
    Corrupt,
    /// The key blob did not authenticate: a wrong passphrase, or a changed header (the two look the same).
    #[error("wrong passphrase, or the vault was modified")]
    WrongPassphrase,
    /// A body, cache or daily blob did not authenticate under its key.
    #[error("the vault was modified or belongs to another key")]
    Tampered,
}

macro_rules! secret_key {
    ($(#[$doc:meta])* $name:ident) => {
        $(#[$doc])*
        #[derive(Clone, PartialEq, Eq)]
        pub struct $name(Zeroizing<[u8; KEY_LEN]>);

        impl $name {
            /// A fresh random key.
            pub fn generate(entropy: &mut impl Entropy) -> Self {
                let mut key = Zeroizing::new([0u8; KEY_LEN]);
                entropy.fill(key.as_mut_slice());
                Self(key)
            }

            /// The key in `bytes`; `None` unless exactly 32 bytes.
            pub fn from_slice(bytes: &[u8]) -> Option<Self> {
                let mut key = Zeroizing::new([0u8; KEY_LEN]);
                if bytes.len() != KEY_LEN {
                    return None;
                }
                key.copy_from_slice(bytes);
                Some(Self(key))
            }

            /// The raw key, for the one place that must store it.
            pub fn as_bytes(&self) -> &[u8; KEY_LEN] {
                &self.0
            }

            fn cipher(&self) -> Aes256Gcm {
                Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(self.0.as_slice()))
            }
        }

        impl fmt::Debug for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str(concat!(stringify!($name), "(…)"))
            }
        }
    };
}

secret_key!(
    /// DK: seals the body and the cache. Never leaves Rust memory in plain form.
    DataKey
);
secret_key!(
    /// DDK: seals DK for the daily unlock. Stored next to the K_daily-sealed blob, which is useless without it.
    DailyKey
);
secret_key!(
    /// SK: seals DK for the vault that stays unlocked (round 7c). Stored next to the K_stay-sealed blob, which is
    /// useless without it.
    StayKey
);

/// What the vault holds (plan 3.5). The connect key is zeroized on drop and hidden from `Debug`.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VaultBody {
    pub gateway_url: String,
    pub instance_id: String,
    connect_key: String,
}

impl VaultBody {
    pub fn new(gateway_url: &str, instance_id: &str, connect_key: &str) -> Self {
        Self {
            gateway_url: gateway_url.to_owned(),
            instance_id: instance_id.to_owned(),
            connect_key: connect_key.to_owned(),
        }
    }

    /// The bearer secret. Callers keep it in Rust memory only.
    pub fn connect_key(&self) -> &str {
        &self.connect_key
    }
}

impl Drop for VaultBody {
    fn drop(&mut self) {
        self.connect_key.zeroize();
    }
}

impl fmt::Debug for VaultBody {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("VaultBody")
            .field("gateway_url", &self.gateway_url)
            .field("instance_id", &self.instance_id)
            .field("connect_key", &"rwk_…")
            .finish()
    }
}

/// When the daily blob was sealed: its boot count and the time of the unlock. Since round 7c only the time counts (as
/// the start of the use clock); the boot count stays in the format and is ignored.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DailyStamp {
    pub boot_count: u64,
    pub unlocked_ms: i64,
}

/// A new or re-keyed vault: what Kotlin shows (the passphrase), seals with K_hw (the key blob) and what is stored
/// (the body blob, sealed under the new DK).
#[derive(Debug)]
pub struct Created {
    pub passphrase: Passphrase,
    pub key: DataKey,
    pub key_blob: Vec<u8>,
    pub body_blob: Vec<u8>,
}

/// A vault for `body` with a new passphrase, salt and DK. Also the passphrase change (rotation): a new DK means an
/// older body blob no longer opens, and an older key blob opens only with the old passphrase.
pub fn create(
    body: &VaultBody,
    kdf: Kdf,
    entropy: &mut impl Entropy,
) -> Result<Created, VaultError> {
    let passphrase = Passphrase::generate(entropy);
    let key = DataKey::generate(entropy);
    let mut salt = [0u8; SALT_LEN];
    entropy.fill(&mut salt);
    let key_blob = wrap_key(&key, &passphrase, kdf, salt, nonce(entropy))?;
    let body_blob = seal_body(&key, body, nonce(entropy));
    Ok(Created {
        passphrase,
        key,
        key_blob,
        body_blob,
    })
}

/// A fresh nonce from `entropy`.
pub fn nonce(entropy: &mut impl Entropy) -> [u8; NONCE_LEN] {
    let mut nonce = [0u8; NONCE_LEN];
    entropy.fill(&mut nonce);
    nonce
}

fn seal(cipher: &Aes256Gcm, mut header: Vec<u8>, nonce: &[u8; NONCE_LEN], plain: &[u8]) -> Vec<u8> {
    let sealed = cipher
        .encrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: plain,
                aad: &header,
            },
        )
        .expect("AES-GCM encryption of an in-memory buffer cannot fail");
    header.extend_from_slice(&sealed);
    header
}

fn open(
    cipher: &Aes256Gcm,
    bytes: &[u8],
    header_len: usize,
    failure: VaultError,
) -> Result<Zeroizing<Vec<u8>>, VaultError> {
    let nonce = &bytes[header_len - NONCE_LEN..header_len];
    cipher
        .decrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: &bytes[header_len..],
                aad: &bytes[..header_len],
            },
        )
        .map(Zeroizing::new)
        .map_err(|_| failure)
}

/// Checks magic, length and version; the length check comes after the magic so a foreign file is `NotAVault`.
fn check_frame(bytes: &[u8], magic: &[u8; 4], min_len: usize) -> Result<(), VaultError> {
    if bytes.len() < magic.len() || &bytes[..magic.len()] != magic {
        return Err(VaultError::NotAVault);
    }
    if bytes.len() < magic.len() + 1 {
        return Err(VaultError::Corrupt);
    }
    match bytes[magic.len()] {
        VERSION if bytes.len() >= min_len => Ok(()),
        VERSION => Err(VaultError::Corrupt),
        other => Err(VaultError::UnknownVersion(other)),
    }
}

fn be_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

fn be_8(bytes: &[u8], at: usize) -> [u8; 8] {
    let mut out = [0u8; 8];
    out.copy_from_slice(&bytes[at..at + 8]);
    out
}

/// Wraps DK under KEK_p = `kdf`(passphrase, `salt`). `salt` and `nonce` must be fresh random bytes.
pub fn wrap_key(
    key: &DataKey,
    passphrase: &Passphrase,
    kdf: Kdf,
    salt: [u8; SALT_LEN],
    nonce: [u8; NONCE_LEN],
) -> Result<Vec<u8>, VaultError> {
    let kek = DataKey(kdf.derive(passphrase.kdf_input().as_slice(), &salt)?);
    let mut header = Vec::with_capacity(KEY_BLOB_LEN);
    header.extend_from_slice(MAGIC_KEY);
    header.push(VERSION);
    header.push(kdf.id());
    for word in kdf.words() {
        header.extend_from_slice(&word.to_be_bytes());
    }
    header.extend_from_slice(&salt);
    header.extend_from_slice(&nonce);
    Ok(seal(&kek.cipher(), header, &nonce, key.as_bytes()))
}

/// The KDF a key blob names, without deriving anything (to tell the user how long an unlock takes).
pub fn key_blob_kdf(blob: &[u8]) -> Result<Kdf, VaultError> {
    check_frame(blob, MAGIC_KEY, KEY_HEADER_LEN)?;
    let words = [be_u32(blob, 6), be_u32(blob, 10), be_u32(blob, 14)];
    Kdf::from_header(blob[5], words)
}

/// Unwraps DK with the passphrase. KEK_p is dropped (zeroized) before this returns.
pub fn unwrap_key(blob: &[u8], passphrase: &Passphrase) -> Result<DataKey, VaultError> {
    let kdf = key_blob_kdf(blob)?;
    if blob.len() != KEY_BLOB_LEN {
        return Err(VaultError::Corrupt);
    }
    let salt = &blob[18..18 + SALT_LEN];
    let kek = DataKey(kdf.derive(passphrase.kdf_input().as_slice(), salt)?);
    let plain = open(
        &kek.cipher(),
        blob,
        KEY_HEADER_LEN,
        VaultError::WrongPassphrase,
    )?;
    DataKey::from_slice(&plain).ok_or(VaultError::Corrupt)
}

fn data_header(magic: &[u8; 4], nonce: &[u8; NONCE_LEN]) -> Vec<u8> {
    let mut header = Vec::with_capacity(DATA_HEADER_LEN);
    header.extend_from_slice(magic);
    header.push(VERSION);
    header.extend_from_slice(nonce);
    header
}

/// Seals the body under DK. `nonce` must be fresh for every call.
pub fn seal_body(key: &DataKey, body: &VaultBody, nonce: [u8; NONCE_LEN]) -> Vec<u8> {
    let plain =
        Zeroizing::new(serde_json::to_vec(body).expect("VaultBody serializes: three strings"));
    seal(
        &key.cipher(),
        data_header(MAGIC_BODY, &nonce),
        &nonce,
        &plain,
    )
}

pub fn open_body(key: &DataKey, blob: &[u8]) -> Result<VaultBody, VaultError> {
    check_frame(blob, MAGIC_BODY, DATA_HEADER_LEN + TAG_LEN)?;
    let plain = open(&key.cipher(), blob, DATA_HEADER_LEN, VaultError::Tampered)?;
    serde_json::from_slice(&plain).map_err(|_| VaultError::Corrupt)
}

/// Seals cache bytes (the encrypted identity and picture cache, plan 13.5) under DK.
pub fn seal_cache(key: &DataKey, plain: &[u8], nonce: [u8; NONCE_LEN]) -> Vec<u8> {
    seal(
        &key.cipher(),
        data_header(MAGIC_CACHE, &nonce),
        &nonce,
        plain,
    )
}

pub fn open_cache(key: &DataKey, blob: &[u8]) -> Result<Zeroizing<Vec<u8>>, VaultError> {
    check_frame(blob, MAGIC_CACHE, DATA_HEADER_LEN + TAG_LEN)?;
    open(&key.cipher(), blob, DATA_HEADER_LEN, VaultError::Tampered)
}

/// Seals DK under DDK with `stamp` in the authenticated header.
pub fn seal_daily(
    daily: &DailyKey,
    key: &DataKey,
    stamp: DailyStamp,
    nonce: [u8; NONCE_LEN],
) -> Vec<u8> {
    let mut header = Vec::with_capacity(DAILY_BLOB_LEN);
    header.extend_from_slice(MAGIC_DAILY);
    header.push(VERSION);
    header.extend_from_slice(&stamp.boot_count.to_be_bytes());
    header.extend_from_slice(&stamp.unlocked_ms.to_be_bytes());
    header.extend_from_slice(&nonce);
    seal(&daily.cipher(), header, &nonce, key.as_bytes())
}

/// Opens a daily blob: DK and the stamp it was sealed with. The caller then applies the use clock ([`UseClock`]).
pub fn open_daily(daily: &DailyKey, blob: &[u8]) -> Result<(DataKey, DailyStamp), VaultError> {
    check_frame(blob, MAGIC_DAILY, DAILY_HEADER_LEN)?;
    if blob.len() != DAILY_BLOB_LEN {
        return Err(VaultError::Corrupt);
    }
    let stamp = DailyStamp {
        boot_count: u64::from_be_bytes(be_8(blob, 5)),
        unlocked_ms: i64::from_be_bytes(be_8(blob, 13)),
    };
    let plain = open(
        &daily.cipher(),
        blob,
        DAILY_HEADER_LEN,
        VaultError::Tampered,
    )?;
    let key = DataKey::from_slice(&plain).ok_or(VaultError::Corrupt)?;
    Ok((key, stamp))
}
