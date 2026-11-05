//! The OAuth2 JWT-bearer assertion Google asks for in exchange for an FCM
//! access token. Pure: the clock is a parameter and nothing touches the network.

use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use ring::rand::SystemRandom;
use ring::signature::{RSA_PKCS1_SHA256, RsaKeyPair};
use rustls_pki_types::PrivatePkcs8KeyDer;
use rustls_pki_types::pem::PemObject as _;

/// The only scope the gateway asks for.
pub const SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";
/// Where Google sends service-account JSON that omits `token_uri`.
pub const DEFAULT_TOKEN_URI: &str = "https://oauth2.googleapis.com/token";
/// Lifetime of one assertion; Google accepts at most an hour.
pub const LIFETIME_S: u64 = 3600;

#[derive(Debug, thiserror::Error)]
pub enum JwtError {
    #[error("the service account has no usable private key: {0}")]
    Key(String),
    #[error("signing failed")]
    Sign,
}

/// The parts of a Google service-account JSON file the gateway needs.
#[derive(Clone, serde::Deserialize)]
pub struct ServiceAccount {
    pub client_email: String,
    /// PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`).
    pub private_key: String,
    #[serde(default = "default_token_uri")]
    pub token_uri: String,
}

fn default_token_uri() -> String {
    DEFAULT_TOKEN_URI.to_owned()
}

impl std::fmt::Debug for ServiceAccount {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ServiceAccount")
            .field("client_email", &self.client_email)
            .field("private_key", &"<redacted>")
            .field("token_uri", &self.token_uri)
            .finish()
    }
}

impl ServiceAccount {
    pub fn from_json(json: &[u8]) -> Result<Self, serde_json::Error> {
        serde_json::from_slice(json)
    }

    fn key_pair(&self) -> Result<RsaKeyPair, JwtError> {
        let der = PrivatePkcs8KeyDer::from_pem_slice(self.private_key.as_bytes())
            .map_err(|e| JwtError::Key(e.to_string()))?;
        RsaKeyPair::from_pkcs8(der.secret_pkcs8_der()).map_err(|e| JwtError::Key(e.to_string()))
    }
}

fn b64(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

/// The JSON claim set, exactly as signed.
pub fn claims(sa: &ServiceAccount, now_s: u64) -> serde_json::Value {
    serde_json::json!({
        "iss": sa.client_email,
        "scope": SCOPE,
        "aud": sa.token_uri,
        "iat": now_s,
        "exp": now_s + LIFETIME_S,
    })
}

/// A signed RS256 JWT for `sa`, valid from `now_s` (Unix seconds) for an hour.
pub fn assertion(sa: &ServiceAccount, now_s: u64) -> Result<String, JwtError> {
    let key = sa.key_pair()?;
    let header = serde_json::json!({"alg": "RS256", "typ": "JWT"});
    let signing_input = format!(
        "{}.{}",
        b64(header.to_string().as_bytes()),
        b64(claims(sa, now_s).to_string().as_bytes())
    );
    let mut signature = vec![0u8; key.public().modulus_len()];
    key.sign(
        &RSA_PKCS1_SHA256,
        &SystemRandom::new(),
        signing_input.as_bytes(),
        &mut signature,
    )
    .map_err(|_| JwtError::Sign)?;
    Ok(format!("{signing_input}.{}", b64(&signature)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::signature::{RSA_PKCS1_2048_8192_SHA256, UnparsedPublicKey};

    /// Test-only key; see the `_comment` field of the file.
    const TEST_SA: &str = include_str!("../../tests/data/test-sa.json");
    const NOW: u64 = 1_700_000_000;

    fn account() -> ServiceAccount {
        ServiceAccount::from_json(TEST_SA.as_bytes()).unwrap()
    }

    fn part(jwt: &str, n: usize) -> Vec<u8> {
        URL_SAFE_NO_PAD
            .decode(jwt.split('.').nth(n).unwrap())
            .unwrap()
    }

    #[test]
    fn signature_verifies_with_the_public_key() {
        let sa = account();
        let jwt = assertion(&sa, NOW).unwrap();
        let (signing_input, sig) = jwt.rsplit_once('.').unwrap();
        let signature = URL_SAFE_NO_PAD.decode(sig).unwrap();
        let public = sa.key_pair().unwrap();
        UnparsedPublicKey::new(&RSA_PKCS1_2048_8192_SHA256, public.public().as_ref())
            .verify(signing_input.as_bytes(), &signature)
            .unwrap();
        // A tampered payload must not verify.
        let tampered = format!("{}x", signing_input);
        assert!(
            UnparsedPublicKey::new(&RSA_PKCS1_2048_8192_SHA256, public.public().as_ref())
                .verify(tampered.as_bytes(), &signature)
                .is_err()
        );
    }

    #[test]
    fn header_and_claims_are_pinned() {
        let jwt = assertion(&account(), NOW).unwrap();
        assert_eq!(jwt.split('.').count(), 3);
        let header: serde_json::Value = serde_json::from_slice(&part(&jwt, 0)).unwrap();
        assert_eq!(header, serde_json::json!({"alg": "RS256", "typ": "JWT"}));
        let claims: serde_json::Value = serde_json::from_slice(&part(&jwt, 1)).unwrap();
        assert_eq!(
            claims,
            serde_json::json!({
                "iss": "fcm-test@test-project.iam.gserviceaccount.com",
                "scope": "https://www.googleapis.com/auth/firebase.messaging",
                "aud": "https://oauth2.googleapis.com/token",
                "iat": 1_700_000_000u64,
                "exp": 1_700_003_600u64,
            })
        );
    }

    #[test]
    fn token_uri_defaults() {
        let sa = ServiceAccount::from_json(br#"{"client_email":"a@b","private_key":"x"}"#).unwrap();
        assert_eq!(sa.token_uri, DEFAULT_TOKEN_URI);
    }

    #[test]
    fn bad_keys_are_errors_and_never_printed() {
        let mut sa = account();
        sa.private_key = "not a pem".to_owned();
        assert!(matches!(assertion(&sa, NOW), Err(JwtError::Key(_))));
        assert!(!format!("{:?}", account()).contains("BEGIN"));
    }
}
