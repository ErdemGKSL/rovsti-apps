//! The passphrase key derivation of the vault: Argon2id, or PBKDF2-HMAC-SHA256 in a `vault-pbkdf2` build.

use std::time::Duration;

use argon2::{Algorithm, Argon2, Block, Params, Version};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use zeroize::Zeroizing;

use super::{KEY_LEN, VaultError};

/// How the key-encryption key is derived from the passphrase. Stored in the key blob header, so a vault written with
/// other parameters (another device's calibration, an older build) still opens.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "id", rename_all = "snake_case")]
pub enum Kdf {
    /// Argon2id v1.3: memory in KiB, passes, lanes.
    Argon2id { m_kib: u32, t: u32, p: u32 },
    /// PBKDF2-HMAC-SHA256.
    Pbkdf2Sha256 { iterations: u32 },
}

/// The approved parameters (plan 13.7): 64 MiB, 3 passes, 1 lane.
pub const ARGON2ID: Kdf = Kdf::Argon2id {
    m_kib: 64 * 1024,
    t: 3,
    p: 1,
};

/// The compile-time fallback: 600 000 rounds, as `msg_backup`.
pub const PBKDF2: Kdf = Kdf::Pbkdf2Sha256 {
    iterations: 600_000,
};

const ID_ARGON2ID: u8 = 1;
const ID_PBKDF2: u8 = 2;

/// Header values beyond these are treated as corrupt instead of allocating or spinning without end.
const MAX_M_KIB: u32 = 1024 * 1024;
const MAX_T: u32 = 64;
const MAX_P: u32 = 16;
const MAX_ITERATIONS: u32 = 10_000_000;

/// The Argon2id settings [`calibrate`] may pick, strongest first. The last ones are the OWASP minimums for one and
/// two passes; nothing weaker is ever chosen.
const LADDER: [(u32, u32); 5] = [
    (64 * 1024, 3),
    (64 * 1024, 2),
    (64 * 1024, 1),
    (46 * 1024, 1),
    (19 * 1024, 2),
];

impl Kdf {
    /// What a new vault uses in this build: [`ARGON2ID`], or [`PBKDF2`] with the `vault-pbkdf2` feature.
    pub const fn for_build() -> Kdf {
        if cfg!(feature = "vault-pbkdf2") {
            PBKDF2
        } else {
            ARGON2ID
        }
    }

    pub(super) const fn id(self) -> u8 {
        match self {
            Kdf::Argon2id { .. } => ID_ARGON2ID,
            Kdf::Pbkdf2Sha256 { .. } => ID_PBKDF2,
        }
    }

    /// The three header words; unused ones are 0.
    pub(super) const fn words(self) -> [u32; 3] {
        match self {
            Kdf::Argon2id { m_kib, t, p } => [m_kib, t, p],
            Kdf::Pbkdf2Sha256 { iterations } => [iterations, 0, 0],
        }
    }

    /// Reads the KDF of a header and checks its bounds.
    pub(super) fn from_header(id: u8, words: [u32; 3]) -> Result<Kdf, VaultError> {
        let kdf = match (id, words) {
            (ID_ARGON2ID, [m_kib, t, p]) => Kdf::Argon2id { m_kib, t, p },
            (ID_PBKDF2, [iterations, 0, 0]) => Kdf::Pbkdf2Sha256 { iterations },
            (ID_PBKDF2, _) => return Err(VaultError::Corrupt),
            (other, _) => return Err(VaultError::UnknownKdf(other)),
        };
        kdf.check()?;
        Ok(kdf)
    }

    /// Bounds a header (or a caller) may ask for.
    pub(super) fn check(self) -> Result<(), VaultError> {
        let ok = match self {
            Kdf::Argon2id { m_kib, t, p } => {
                (1..=MAX_P).contains(&p)
                    && (1..=MAX_T).contains(&t)
                    && m_kib >= 8 * p
                    && m_kib <= MAX_M_KIB
            }
            Kdf::Pbkdf2Sha256 { iterations } => (1..=MAX_ITERATIONS).contains(&iterations),
        };
        if ok { Ok(()) } else { Err(VaultError::Corrupt) }
    }

    /// The 32-byte key for `secret` and `salt`. Argon2id's working memory is zeroized before it is freed.
    pub(super) fn derive(
        self,
        secret: &[u8],
        salt: &[u8],
    ) -> Result<Zeroizing<[u8; KEY_LEN]>, VaultError> {
        self.check()?;
        let mut key = Zeroizing::new([0u8; KEY_LEN]);
        match self {
            Kdf::Argon2id { m_kib, t, p } => {
                let params =
                    Params::new(m_kib, t, p, Some(KEY_LEN)).map_err(|_| VaultError::Corrupt)?;
                let mut blocks = Zeroizing::new(vec![Block::new(); params.block_count()]);
                Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
                    .hash_password_into_with_memory(
                        secret,
                        salt,
                        key.as_mut_slice(),
                        blocks.as_mut_slice(),
                    )
                    .map_err(|_| VaultError::Corrupt)?;
            }
            Kdf::Pbkdf2Sha256 { iterations } => {
                pbkdf2::pbkdf2_hmac::<Sha256>(secret, salt, iterations, key.as_mut_slice());
            }
        }
        Ok(key)
    }
}

/// Picks Argon2id settings for this device: `measured` is how long one derivation at [`ARGON2ID`] took, `budget`
/// the most an unlock may spend. The cost of Argon2id grows with memory times passes, so the strongest rung of the
/// ladder whose estimate fits is chosen; a device too slow for any keeps the weakest rung (the OWASP minimum).
pub fn calibrate(measured: Duration, budget: Duration) -> Kdf {
    let full = cost(LADDER[0]);
    let measured_ms = measured.as_secs_f64() * 1000.0;
    let budget_ms = budget.as_secs_f64() * 1000.0;
    let (m_kib, t) = LADDER
        .into_iter()
        .find(|&rung| measured_ms * cost(rung) / full <= budget_ms)
        .unwrap_or(LADDER[LADDER.len() - 1]);
    Kdf::Argon2id { m_kib, t, p: 1 }
}

fn cost((m_kib, t): (u32, u32)) -> f64 {
    f64::from(m_kib) * f64::from(t)
}
