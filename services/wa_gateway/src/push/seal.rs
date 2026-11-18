//! Sealed push previews (round 7b): what a push says, readable only by the
//! phone it goes to.
//!
//! The app registers the public half of a P-256 key that lives in its Android
//! Keystore (non-exportable). For every push the gateway makes a fresh P-256
//! key pair and seals the content to the app's key:
//!
//! - `z` = ECDH(ephemeral private, device public), the 32-byte x coordinate;
//! - `k` = HKDF-SHA256(salt = ephemeral point, ikm = `z`,
//!   info = `"rosty-wa-push/1" | device point`), 32 bytes;
//! - `ct` = AES-256-GCM(`k`, random 12-byte nonce, AAD = `"rosty-wa-push/1|" kid`),
//!   carried as `nonce | ciphertext | tag`.
//!
//! The FCM data map is then `{"v":"1","kid","epk","ct"}` (base64, standard,
//! padded) next to the routing keys the app needs even when it cannot open the
//! rest (`kind`, and a call's opaque `call_id` and `missed`). Google sees no
//! chat, number, name or text. Nothing here logs content or keys.

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use ring::aead::{AES_256_GCM, Aad, LessSafeKey, NONCE_LEN, Nonce, UnboundKey};
use ring::agreement::{ECDH_P256, EphemeralPrivateKey, UnparsedPublicKey, agree_ephemeral};
use ring::hkdf::{HKDF_SHA256, Salt};
use ring::rand::{SecureRandom, SystemRandom};

use rosty_wa_proto::PUSH_KEY_VERSION;

use crate::store::DevicePushKey;

/// The DER prefix of a P-256 `SubjectPublicKeyInfo` (id-ecPublicKey,
/// prime256v1, an uncompressed point in a 66-byte BIT STRING). What
/// `java.security.PublicKey.getEncoded()` gives for a Keystore EC key.
pub const P256_SPKI_PREFIX: [u8; 26] = [
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
    0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
];

/// The length of an uncompressed P-256 point.
pub const POINT_LEN: usize = 65;

/// Both the HKDF info prefix and the AAD prefix of scheme 1.
pub const LABEL_V1: &[u8] = b"rosty-wa-push/1";

/// The longest key id the gateway stores.
pub const MAX_KEY_ID_LEN: usize = 64;

/// The data map keys of a sealed push.
pub const VERSION_KEY: &str = "v";
pub const KEY_ID_KEY: &str = "kid";
pub const EPHEMERAL_KEY: &str = "epk";
pub const CIPHERTEXT_KEY: &str = "ct";

#[derive(Debug, PartialEq, Eq, thiserror::Error)]
pub enum SealError {
    #[error("the push key id is empty, too long or not [A-Za-z0-9_-]")]
    KeyId,
    #[error("unsupported push key version")]
    Version,
    #[error("the push key is not a P-256 public key")]
    PublicKey,
    #[error("sealing failed")]
    Crypto,
    #[error("the sealed push does not fit FCM's data limit")]
    TooLarge,
}

/// Checks a registered push key and returns what the store keeps.
pub fn parse_push_key(key: &rosty_wa_proto::PushKey) -> Result<DevicePushKey, SealError> {
    let id_ok = !key.key_id.is_empty()
        && key.key_id.len() <= MAX_KEY_ID_LEN
        && key
            .key_id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
    if !id_ok {
        return Err(SealError::KeyId);
    }
    if key.version != PUSH_KEY_VERSION {
        return Err(SealError::Version);
    }
    let point = parse_public_key(&key.public_key)?;
    Ok(DevicePushKey {
        key_id: key.key_id.clone(),
        version: key.version,
        point,
    })
}

/// The point inside a base64 P-256 `SubjectPublicKeyInfo`, checked to be on
/// the curve (a trial agreement with a throwaway key).
pub fn parse_public_key(b64: &str) -> Result<[u8; POINT_LEN], SealError> {
    let der = STANDARD
        .decode(b64.trim())
        .map_err(|_| SealError::PublicKey)?;
    let point = der
        .strip_prefix(P256_SPKI_PREFIX.as_slice())
        .ok_or(SealError::PublicKey)?;
    let point = <[u8; POINT_LEN]>::try_from(point).map_err(|_| SealError::PublicKey)?;
    let probe = EphemeralPrivateKey::generate(&ECDH_P256, &SystemRandom::new())
        .map_err(|_| SealError::Crypto)?;
    agree_ephemeral(probe, &UnparsedPublicKey::new(&ECDH_P256, &point), |_| ())
        .map_err(|_| SealError::PublicKey)?;
    Ok(point)
}

/// What [`seal`] adds to the FCM data map.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Sealed {
    pub key_id: String,
    /// The ephemeral public point, base64.
    pub epk: String,
    /// `nonce | ciphertext | tag`, base64.
    pub ct: String,
}

impl Sealed {
    pub fn entries(&self) -> [(String, String); 4] {
        [
            (VERSION_KEY.to_owned(), PUSH_KEY_VERSION.to_string()),
            (KEY_ID_KEY.to_owned(), self.key_id.clone()),
            (EPHEMERAL_KEY.to_owned(), self.epk.clone()),
            (CIPHERTEXT_KEY.to_owned(), self.ct.clone()),
        ]
    }
}

/// The AAD of scheme 1 for `key_id`.
pub fn aad(key_id: &str) -> Vec<u8> {
    let mut aad = LABEL_V1.to_vec();
    aad.push(b'|');
    aad.extend_from_slice(key_id.as_bytes());
    aad
}

/// Seals `plaintext` to `key` with a fresh ephemeral key and nonce.
pub fn seal(key: &DevicePushKey, plaintext: &[u8]) -> Result<Sealed, SealError> {
    let rng = SystemRandom::new();
    let ephemeral =
        EphemeralPrivateKey::generate(&ECDH_P256, &rng).map_err(|_| SealError::Crypto)?;
    let epk = ephemeral
        .compute_public_key()
        .map_err(|_| SealError::Crypto)?;
    let epk = epk.as_ref().to_vec();
    let aead = agree_ephemeral(
        ephemeral,
        &UnparsedPublicKey::new(&ECDH_P256, &key.point),
        |z| derive_key(z, &epk, &key.point),
    )
    .map_err(|_| SealError::PublicKey)??;
    let mut nonce = [0u8; NONCE_LEN];
    rng.fill(&mut nonce).map_err(|_| SealError::Crypto)?;
    let mut sealed = plaintext.to_vec();
    aead.seal_in_place_append_tag(
        Nonce::assume_unique_for_key(nonce),
        Aad::from(aad(&key.key_id)),
        &mut sealed,
    )
    .map_err(|_| SealError::Crypto)?;
    let mut ct = nonce.to_vec();
    ct.extend_from_slice(&sealed);
    Ok(Sealed {
        key_id: key.key_id.clone(),
        epk: STANDARD.encode(&epk),
        ct: STANDARD.encode(&ct),
    })
}

/// HKDF-SHA256 of the shared secret into the AES-256-GCM key (see the module).
pub fn derive_key(z: &[u8], epk: &[u8], device_point: &[u8]) -> Result<LessSafeKey, SealError> {
    let info: [&[u8]; 2] = [LABEL_V1, device_point];
    let prk = Salt::new(HKDF_SHA256, epk).extract(z);
    let okm = prk
        .expand(&info, &AES_256_GCM)
        .map_err(|_| SealError::Crypto)?;
    Ok(LessSafeKey::new(UnboundKey::from(okm)))
}

#[cfg(test)]
pub(crate) mod test_device {
    //! A device key for round-trip tests: a ring ephemeral key stands in for
    //! the phone's Keystore key (it can do one agreement, which is all a test
    //! needs). Generated per test; never a real key.

    use super::*;

    pub struct TestDevice {
        private: EphemeralPrivateKey,
        pub key: DevicePushKey,
    }

    impl TestDevice {
        pub fn new(key_id: &str) -> Self {
            let private = EphemeralPrivateKey::generate(&ECDH_P256, &SystemRandom::new()).unwrap();
            let point = <[u8; POINT_LEN]>::try_from(private.compute_public_key().unwrap().as_ref())
                .unwrap();
            Self {
                private,
                key: DevicePushKey {
                    key_id: key_id.to_owned(),
                    version: PUSH_KEY_VERSION,
                    point,
                },
            }
        }

        /// The SPKI of the public half, base64, as the app registers it.
        pub fn spki_b64(&self) -> String {
            let mut der = P256_SPKI_PREFIX.to_vec();
            der.extend_from_slice(&self.key.point);
            STANDARD.encode(der)
        }

        /// Opens what [`seal`] made for this device (as the app does).
        pub fn open(self, sealed: &Sealed) -> Result<Vec<u8>, SealError> {
            let epk = STANDARD.decode(&sealed.epk).unwrap();
            let ct = STANDARD.decode(&sealed.ct).unwrap();
            let point = self.key.point;
            let aead = agree_ephemeral(
                self.private,
                &UnparsedPublicKey::new(&ECDH_P256, &epk),
                |z| derive_key(z, &epk, &point),
            )
            .map_err(|_| SealError::Crypto)??;
            let (nonce, body) = ct.split_at(NONCE_LEN);
            let mut body = body.to_vec();
            let plain = aead
                .open_in_place(
                    Nonce::try_assume_unique_for_key(nonce).unwrap(),
                    Aad::from(aad(&sealed.key_id)),
                    &mut body,
                )
                .map_err(|_| SealError::Crypto)?;
            Ok(plain.to_vec())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::test_device::TestDevice;
    use super::*;
    use rosty_wa_proto::PushKey;

    #[test]
    fn a_sealed_push_opens_with_the_device_key_only() {
        let device = TestDevice::new("k1");
        let sealed = seal(&device.key, b"hello").unwrap();
        assert_eq!(sealed.key_id, "k1");
        assert!(!sealed.ct.contains("hello"));
        assert_eq!(device.open(&sealed).unwrap(), b"hello");

        let other = TestDevice::new("k1");
        assert_eq!(other.open(&sealed), Err(SealError::Crypto));
    }

    #[test]
    fn each_seal_uses_a_fresh_ephemeral_key_and_nonce() {
        let device = TestDevice::new("k1");
        let a = seal(&device.key, b"same").unwrap();
        let b = seal(&device.key, b"same").unwrap();
        assert_ne!(a.epk, b.epk);
        assert_ne!(a.ct, b.ct);
    }

    #[test]
    fn the_key_id_is_bound_to_the_ciphertext() {
        let device = TestDevice::new("k1");
        let mut sealed = seal(&device.key, b"x").unwrap();
        sealed.key_id = "k2".into();
        assert_eq!(device.open(&sealed), Err(SealError::Crypto));
    }

    #[test]
    fn a_registered_key_is_checked() {
        let device = TestDevice::new("k1");
        let good = PushKey {
            key_id: "k1".into(),
            version: 1,
            public_key: device.spki_b64(),
        };
        assert_eq!(parse_push_key(&good).unwrap(), device.key);
        let bad = |change: fn(&mut PushKey)| {
            let mut key = good.clone();
            change(&mut key);
            parse_push_key(&key)
        };
        assert_eq!(bad(|k| k.key_id.clear()), Err(SealError::KeyId));
        assert_eq!(bad(|k| k.key_id = "a b".into()), Err(SealError::KeyId));
        assert_eq!(bad(|k| k.key_id = "x".repeat(65)), Err(SealError::KeyId));
        assert_eq!(bad(|k| k.version = 2), Err(SealError::Version));
        assert_eq!(
            bad(|k| k.public_key = "not base64!".into()),
            Err(SealError::PublicKey)
        );
        assert_eq!(
            bad(|k| k.public_key = STANDARD.encode([4u8; 65])),
            Err(SealError::PublicKey)
        );
        // The right shape, but a point that is not on the curve.
        let mut der = P256_SPKI_PREFIX.to_vec();
        der.push(4);
        der.extend_from_slice(&[1u8; 64]);
        let off_curve = STANDARD.encode(der);
        assert_eq!(parse_public_key(&off_curve), Err(SealError::PublicKey));
    }

    #[test]
    fn the_data_entries_name_version_key_and_parts() {
        let device = TestDevice::new("k1");
        let sealed = seal(&device.key, b"x").unwrap();
        let keys: Vec<String> = sealed.entries().into_iter().map(|(k, _)| k).collect();
        assert_eq!(keys, ["v", "kid", "epk", "ct"]);
        assert_eq!(sealed.entries()[0].1, "1");
        assert_eq!(STANDARD.decode(&sealed.epk).unwrap().len(), POINT_LEN);
        // nonce 12 + 1 byte + tag 16
        assert_eq!(STANDARD.decode(&sealed.ct).unwrap().len(), 29);
    }
}
