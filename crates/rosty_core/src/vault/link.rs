//! The link wrap (round 7a, the one prompt for the four apps): DK sealed under a key derived from the vault's own
//! connect key, so a sibling Rosty app that is unlocked can open this app's vault by handing over the link alone
//! (`WaLinkProvider`, memory only), without a prompt here.
//!
//! | blob | header                                     | ciphertext    |
//! |------|--------------------------------------------|---------------|
//! | link | `RWVL` · version · salt (16) · nonce (12)  | DK + tag (48) |
//!
//! KL = PBKDF2-HMAC-SHA256(connect key, salt ‖ `rosty-wa-link`, 1 round). One round is enough: the connect key is
//! 32 random bytes (`rosty_wa_proto::ConnectKey`), not a password, so there is nothing to slow down. The wrap adds
//! no new way in: whoever holds the connect key already reads and sends everything through the gateway, and the
//! connect key is on disk only inside the body, under DK.

use pbkdf2::pbkdf2_hmac;
use sha2::Sha256;
use zeroize::Zeroizing;

use super::{
    DataKey, KEY_LEN, NONCE_LEN, SALT_LEN, TAG_LEN, VERSION, VaultError, check_frame, open, seal,
};

const MAGIC_LINK: &[u8; 4] = b"RWVL";
const LINK_HEADER_LEN: usize = 4 + 1 + SALT_LEN + NONCE_LEN;
/// The length of a link wrap.
pub const LINK_BLOB_LEN: usize = LINK_HEADER_LEN + KEY_LEN + TAG_LEN;
const LABEL: &[u8] = b"rosty-wa-link";

fn link_key(connect_key: &str, salt: &[u8]) -> DataKey {
    let mut info = Vec::with_capacity(salt.len() + LABEL.len());
    info.extend_from_slice(salt);
    info.extend_from_slice(LABEL);
    let mut out = Zeroizing::new([0u8; KEY_LEN]);
    pbkdf2_hmac::<Sha256>(connect_key.trim().as_bytes(), &info, 1, out.as_mut_slice());
    DataKey(out)
}

/// Wraps DK under the key of `connect_key`. `salt` and `nonce` must be fresh random bytes.
pub fn wrap_for_link(
    key: &DataKey,
    connect_key: &str,
    salt: [u8; SALT_LEN],
    nonce: [u8; NONCE_LEN],
) -> Vec<u8> {
    let mut header = Vec::with_capacity(LINK_BLOB_LEN);
    header.extend_from_slice(MAGIC_LINK);
    header.push(VERSION);
    header.extend_from_slice(&salt);
    header.extend_from_slice(&nonce);
    seal(
        &link_key(connect_key, &salt).cipher(),
        header,
        &nonce,
        key.as_bytes(),
    )
}

/// DK, when `connect_key` is the vault's. Another key (or a changed blob) is [`VaultError::Tampered`].
pub fn unwrap_for_link(blob: &[u8], connect_key: &str) -> Result<DataKey, VaultError> {
    check_frame(blob, MAGIC_LINK, LINK_HEADER_LEN)?;
    if blob.len() != LINK_BLOB_LEN {
        return Err(VaultError::Corrupt);
    }
    let salt = &blob[5..5 + SALT_LEN];
    let plain = open(
        &link_key(connect_key, salt).cipher(),
        blob,
        LINK_HEADER_LEN,
        VaultError::Tampered,
    )?;
    DataKey::from_slice(&plain).ok_or(VaultError::Corrupt)
}
