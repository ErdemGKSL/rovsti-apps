//! Connect keys: bearer secrets that let an app use a linked instance.

use std::fmt;

use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use sha2::{Digest, Sha256};

const PREFIX: &str = "rwk_";
/// `rwk_` plus 43 base64url characters (32 bytes, no padding).
pub const KEY_LEN: usize = 47;

#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum KeyError {
    #[error("connect key must start with \"rwk_\"")]
    MissingPrefix,
    #[error("connect key has {0} characters; expected {KEY_LEN}")]
    BadLength(usize),
    #[error("connect key is not valid base64url")]
    BadEncoding,
    #[error("hash must be 64 hex characters")]
    BadHash,
}

/// A connect key. The key is a secret: `Debug` never prints it.
#[derive(Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct ConnectKey(String);

impl ConnectKey {
    pub fn from_bytes(bytes: &[u8; 32]) -> Self {
        Self(format!("{PREFIX}{}", URL_SAFE_NO_PAD.encode(bytes)))
    }

    /// Parses pasted text. Surrounding whitespace and newlines are ignored.
    pub fn parse(input: &str) -> Result<Self, KeyError> {
        let s = input.trim();
        let body = s.strip_prefix(PREFIX).ok_or(KeyError::MissingPrefix)?;
        let len = s.chars().count();
        if len != KEY_LEN {
            return Err(KeyError::BadLength(len));
        }
        let decoded = URL_SAFE_NO_PAD
            .decode(body)
            .map_err(|_| KeyError::BadEncoding)?;
        if decoded.len() != 32 {
            return Err(KeyError::BadEncoding);
        }
        Ok(Self(s.to_owned()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// SHA-256 of the key text; the only form the server stores.
    pub fn hash(&self) -> KeyHash {
        KeyHash(Sha256::digest(self.0.as_bytes()).into())
    }
}

impl fmt::Debug for ConnectKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("ConnectKey(rwk_…)")
    }
}

impl TryFrom<String> for ConnectKey {
    type Error = KeyError;
    fn try_from(value: String) -> Result<Self, KeyError> {
        Self::parse(&value)
    }
}

impl From<ConnectKey> for String {
    fn from(key: ConnectKey) -> String {
        key.0
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct KeyHash([u8; 32]);

impl KeyHash {
    pub fn to_hex(&self) -> String {
        hex(&self.0)
    }

    pub fn from_hex(s: &str) -> Result<Self, KeyError> {
        let b = s.as_bytes();
        if b.len() != 64 {
            return Err(KeyError::BadHash);
        }
        let mut out = [0u8; 32];
        let (pairs, _) = b.as_chunks::<2>();
        for (slot, [hi, lo]) in out.iter_mut().zip(pairs) {
            *slot = (nibble(*hi)? << 4) | nibble(*lo)?;
        }
        Ok(Self(out))
    }

    /// Constant-time comparison, for checking a presented key against storage.
    pub fn ct_eq(&self, other: &KeyHash) -> bool {
        self.0
            .iter()
            .zip(other.0.iter())
            .fold(0u8, |acc, (a, b)| acc | (a ^ b))
            == 0
    }
}

impl fmt::Debug for KeyHash {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "KeyHash({})", self.to_hex())
    }
}

fn nibble(c: u8) -> Result<u8, KeyError> {
    match c {
        b'0'..=b'9' => Ok(c - b'0'),
        b'a'..=b'f' => Ok(c - b'a' + 10),
        b'A'..=b'F' => Ok(c - b'A' + 10),
        _ => Err(KeyError::BadHash),
    }
}

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(DIGITS[(b >> 4) as usize] as char);
        s.push(DIGITS[(b & 15) as usize] as char);
    }
    s
}

/// Public identifier of a key (safe to list and log), 16 hex characters.
#[derive(Clone, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(transparent)]
pub struct KeyId(String);

impl KeyId {
    pub fn from_bytes(bytes: &[u8; 8]) -> Self {
        Self(hex(bytes))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for KeyId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> ConnectKey {
        ConnectKey::from_bytes(&[7u8; 32])
    }

    #[test]
    fn round_trip() {
        let key = sample();
        assert_eq!(key.as_str().len(), KEY_LEN);
        assert!(key.as_str().starts_with("rwk_"));
        assert_eq!(ConnectKey::parse(key.as_str()).unwrap(), key);
        let json = serde_json::to_string(&key).unwrap();
        assert_eq!(serde_json::from_str::<ConnectKey>(&json).unwrap(), key);
    }

    #[test]
    fn paste_is_trimmed() {
        let key = sample();
        let pasted = format!("  \n{}\r\n\t", key.as_str());
        assert_eq!(ConnectKey::parse(&pasted).unwrap(), key);
    }

    #[test]
    fn rejects() {
        let k = sample();
        let body = &k.as_str()[4..];
        assert_eq!(ConnectKey::parse(""), Err(KeyError::MissingPrefix));
        assert_eq!(
            ConnectKey::parse(&format!("rwx_{body}")),
            Err(KeyError::MissingPrefix)
        );
        assert_eq!(
            ConnectKey::parse(&format!("rwk_{}", &body[1..])),
            Err(KeyError::BadLength(46))
        );
        assert_eq!(
            ConnectKey::parse(&format!("rwk_{body}A")),
            Err(KeyError::BadLength(48))
        );
        assert_eq!(
            ConnectKey::parse(&format!("rwk_{}+", &body[1..])),
            Err(KeyError::BadEncoding)
        );
        // Whitespace inside the key is not a paste artefact.
        assert!(ConnectKey::parse(&format!("rwk_ {}", &body[1..])).is_err());
        assert!(serde_json::from_str::<ConnectKey>("\"nope\"").is_err());
    }

    #[test]
    fn debug_is_redacted() {
        let key = sample();
        let shown = format!("{key:?}");
        assert!(!shown.contains(&key.as_str()[4..]));
        assert_eq!(shown, "ConnectKey(rwk_…)");
    }

    #[test]
    fn hash_vector_is_pinned() {
        // sha256("rwk_" + base64url([7; 32])).
        assert_eq!(
            sample().hash().to_hex(),
            "b29bc36198324ee92b180c190cbab2734be28d1d34afc2ad99e7dceed8b45b74"
        );
    }

    #[test]
    fn hash_hex_and_ct_eq() {
        let a = sample().hash();
        assert_eq!(KeyHash::from_hex(&a.to_hex()).unwrap(), a);
        assert_eq!(KeyHash::from_hex(&a.to_hex().to_uppercase()).unwrap(), a);
        assert!(a.ct_eq(&a));
        let b = ConnectKey::from_bytes(&[8u8; 32]).hash();
        assert!(!a.ct_eq(&b));
        assert_eq!(KeyHash::from_hex("abc"), Err(KeyError::BadHash));
        assert_eq!(KeyHash::from_hex(&"zz".repeat(32)), Err(KeyError::BadHash));
    }

    #[test]
    fn key_id() {
        let id = KeyId::from_bytes(&[0x01, 0xab, 0, 0xff, 0x10, 0x20, 0x30, 0x40]);
        assert_eq!(id.as_str(), "01ab00ff10203040");
        assert_eq!(serde_json::to_string(&id).unwrap(), "\"01ab00ff10203040\"");
    }
}
