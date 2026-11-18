//! The stay-unlocked wrap (round 7c): DK sealed under a random stay key SK, which Kotlin seals again with an Android
//! Keystore key that needs no prompt but only works while the phone is unlocked (`setUnlockedDeviceRequired`). The
//! app reopens its vault with it silently, also after a reboot once the person has unlocked the phone.
//!
//! | blob | header                                           | ciphertext    |
//! |------|--------------------------------------------------|---------------|
//! | stay | `RWVS` · version · created ms i64 · nonce (12)    | DK + tag (48) |
//!
//! The creation time is authenticated: it is where the use clock ([`super::UseClock`]) starts when no later use is
//! known. Locking (by the person, or after 90 days with no use) deletes the wrap; the next passphrase, biometric or
//! hand-over unlock makes a new one.

use super::{
    DataKey, KEY_LEN, NONCE_LEN, StayKey, TAG_LEN, VERSION, VaultError, be_8, check_frame, open,
    seal,
};

const MAGIC_STAY: &[u8; 4] = b"RWVS";
const STAY_HEADER_LEN: usize = 4 + 1 + 8 + NONCE_LEN;
/// The length of a stay blob.
pub const STAY_BLOB_LEN: usize = STAY_HEADER_LEN + KEY_LEN + TAG_LEN;

/// Seals DK under SK with `created_ms` in the authenticated header. `nonce` must be fresh.
pub fn seal_stay(
    stay: &StayKey,
    key: &DataKey,
    created_ms: i64,
    nonce: [u8; NONCE_LEN],
) -> Vec<u8> {
    let mut header = Vec::with_capacity(STAY_BLOB_LEN);
    header.extend_from_slice(MAGIC_STAY);
    header.push(VERSION);
    header.extend_from_slice(&created_ms.to_be_bytes());
    header.extend_from_slice(&nonce);
    seal(&stay.cipher(), header, &nonce, key.as_bytes())
}

/// Opens a stay blob: DK and the time the wrap was made. The caller then applies the use clock.
pub fn open_stay(stay: &StayKey, blob: &[u8]) -> Result<(DataKey, i64), VaultError> {
    check_frame(blob, MAGIC_STAY, STAY_HEADER_LEN)?;
    if blob.len() != STAY_BLOB_LEN {
        return Err(VaultError::Corrupt);
    }
    let created_ms = i64::from_be_bytes(be_8(blob, 5));
    let plain = open(&stay.cipher(), blob, STAY_HEADER_LEN, VaultError::Tampered)?;
    let key = DataKey::from_slice(&plain).ok_or(VaultError::Corrupt)?;
    Ok((key, created_ms))
}
