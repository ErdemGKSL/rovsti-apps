//! TLS for HTTP and WebSocket: rustls with the pure-Rust graviola provider, so
//! the crate builds for Android without a C toolchain.

use std::sync::Arc;

use rustls::crypto::CryptoProvider;
use rustls::{ClientConfig, RootCertStore};

use crate::error::ClientError;

pub fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls_graviola::default_provider())
}

/// A client config that trusts the bundled WebPKI roots.
pub fn client_config() -> Result<Arc<ClientConfig>, ClientError> {
    let roots = RootCertStore {
        roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
    };
    let config = ClientConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()
        .map_err(|e| ClientError::Tls(e.to_string()))?
        .with_root_certificates(roots)
        .with_no_client_auth();
    Ok(Arc::new(config))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_config_builds() {
        let config = client_config().unwrap();
        assert!(!config.crypto_provider().cipher_suites.is_empty());
    }
}
