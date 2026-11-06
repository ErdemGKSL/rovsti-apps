//! Downloading profile pictures from WhatsApp's picture CDN.
//!
//! WhatsApp answers a picture query with a signed HTTPS URL. The gateway
//! fetches it itself (the bytes are not end-to-end encrypted), but only from
//! WhatsApp's own hosts, so a forged answer cannot make it fetch anything else.

use std::time::Duration;

use crate::driver::DriverError;

/// Largest picture accepted (full pictures are about 100 KiB).
pub const MAX_PICTURE_BYTES: u64 = 4 * 1024 * 1024;
const TIMEOUT: Duration = Duration::from_secs(20);

/// Hosts profile pictures are served from.
const ALLOWED_SUFFIXES: [&str; 2] = [".whatsapp.net", ".fbcdn.net"];

/// True for an `https` URL on one of WhatsApp's picture hosts.
pub fn allowed_url(url: &str) -> bool {
    let Ok(uri) = url.parse::<ureq::http::Uri>() else {
        return false;
    };
    let host = uri.host().unwrap_or("").to_ascii_lowercase();
    uri.scheme_str() == Some("https")
        && uri.port().is_none()
        && ALLOWED_SUFFIXES.iter().any(|s| host.ends_with(s))
}

/// Fetches the picture at `url`. Blocking: run it on a blocking thread.
pub fn download(url: &str) -> Result<Vec<u8>, DriverError> {
    if !allowed_url(url) {
        return Err(DriverError::Failed(format!(
            "refusing to fetch a picture from {url:?}"
        )));
    }
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .timeout_global(Some(TIMEOUT))
        .max_redirects(0)
        .build()
        .into();
    let mut response = agent
        .get(url)
        .call()
        .map_err(|e| DriverError::Failed(format!("picture download failed: {e}")))?;
    let status = response.status().as_u16();
    if status != 200 {
        return Err(DriverError::Failed(format!(
            "picture download answered {status}"
        )));
    }
    response
        .body_mut()
        .with_config()
        .limit(MAX_PICTURE_BYTES)
        .read_to_vec()
        .map_err(|e| DriverError::Failed(format!("picture download failed: {e}")))
}

/// [`download`] on a blocking thread.
pub async fn fetch(url: String) -> Result<Vec<u8>, DriverError> {
    tokio::task::spawn_blocking(move || download(&url))
        .await
        .map_err(|e| DriverError::Failed(format!("picture task failed: {e}")))?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_whatsapp_picture_hosts_over_https() {
        assert!(allowed_url(
            "https://pps.whatsapp.net/v/t61.24694-24/1_n.jpg?ccb=11-4&oh=x"
        ));
        assert!(allowed_url("https://scontent.xx.fbcdn.net/a.jpg"));
        for bad in [
            "http://pps.whatsapp.net/a.jpg",
            "https://pps.whatsapp.net:8443/a.jpg",
            "https://evil.example/a.jpg",
            "https://whatsapp.net.evil.example/a.jpg",
            "https://127.0.0.1/a.jpg",
            "file:///etc/passwd",
            "not a url",
        ] {
            assert!(!allowed_url(bad), "{bad}");
        }
        assert!(download("http://127.0.0.1/x").is_err());
    }
}
