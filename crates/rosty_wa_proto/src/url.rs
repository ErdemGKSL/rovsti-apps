//! The gateway address an app is configured with.

use std::net::{Ipv4Addr, Ipv6Addr};

#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum UrlError {
    #[error("gateway URL is empty")]
    Empty,
    #[error("gateway URL needs a scheme (https://...)")]
    MissingScheme,
    #[error("unsupported scheme {0:?}; use https")]
    UnsupportedScheme(String),
    #[error("gateway URL has no host")]
    EmptyHost,
    #[error("gateway URL has an invalid port")]
    InvalidPort,
    #[error("gateway URL must be just scheme, host and port")]
    UnexpectedPart,
    #[error("http is only allowed for local network hosts; use https for {0:?}")]
    InsecureHost(String),
}

/// A validated gateway origin such as `https://wa.example.com` (no path).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct GatewayUrl {
    secure: bool,
    /// `host` or `host:port`, lower-case, IPv6 in brackets.
    authority: String,
}

impl GatewayUrl {
    pub fn parse(input: &str) -> Result<Self, UrlError> {
        let s = input.trim();
        if s.is_empty() {
            return Err(UrlError::Empty);
        }
        let (scheme, rest) = s.split_once("://").ok_or(UrlError::MissingScheme)?;
        let secure = match scheme.to_ascii_lowercase().as_str() {
            "https" => true,
            "http" => false,
            other => return Err(UrlError::UnsupportedScheme(other.to_owned())),
        };
        let authority = rest.strip_suffix('/').unwrap_or(rest);
        if authority.contains(['/', '?', '#', '@', ' ']) {
            return Err(UrlError::UnexpectedPart);
        }
        let (host, port) = split_host_port(authority)?;
        if host.is_empty() || host == "[]" {
            return Err(UrlError::EmptyHost);
        }
        let host = host.to_ascii_lowercase();
        if !secure && !is_local_host(&host) {
            return Err(UrlError::InsecureHost(host));
        }
        let authority = match port {
            Some(p) => format!("{host}:{p}"),
            None => host,
        };
        Ok(Self { secure, authority })
    }

    /// `https://host[:port]` without a trailing slash.
    pub fn as_str(&self) -> String {
        format!(
            "{}://{}",
            if self.secure { "https" } else { "http" },
            self.authority
        )
    }

    /// HTTP URL for an absolute path such as `/v1/chats`.
    pub fn join(&self, path: &str) -> String {
        format!("{}{}", self.as_str(), slash(path))
    }

    /// WebSocket URL (`https` becomes `wss`, `http` becomes `ws`).
    pub fn ws(&self, path: &str) -> String {
        format!(
            "{}://{}{}",
            if self.secure { "wss" } else { "ws" },
            self.authority,
            slash(path)
        )
    }
}

impl std::fmt::Display for GatewayUrl {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.as_str())
    }
}

impl TryFrom<String> for GatewayUrl {
    type Error = UrlError;
    fn try_from(value: String) -> Result<Self, UrlError> {
        Self::parse(&value)
    }
}

impl From<GatewayUrl> for String {
    fn from(url: GatewayUrl) -> String {
        url.as_str()
    }
}

fn slash(path: &str) -> String {
    if path.starts_with('/') {
        path.to_owned()
    } else {
        format!("/{path}")
    }
}

fn split_host_port(authority: &str) -> Result<(&str, Option<u16>), UrlError> {
    let (host, port) = if authority.starts_with('[') {
        let end = authority.find(']').ok_or(UrlError::EmptyHost)?;
        let (host, rest) = authority.split_at(end + 1);
        match rest {
            "" => (host, None),
            _ => (
                host,
                Some(rest.strip_prefix(':').ok_or(UrlError::UnexpectedPart)?),
            ),
        }
    } else {
        match authority.rsplit_once(':') {
            Some((h, p)) => (h, Some(p)),
            None => (authority, None),
        }
    };
    let port = port
        .map(|p| match p.parse::<u16>() {
            Ok(n) if n != 0 => Ok(n),
            _ => Err(UrlError::InvalidPort),
        })
        .transpose()?;
    Ok((host, port))
}

/// Loopback, the Android emulator host, RFC1918 and `.local` names.
fn is_local_host(host: &str) -> bool {
    if host == "localhost" || host.ends_with(".local") {
        return true;
    }
    if let Ok(v4) = host.parse::<Ipv4Addr>() {
        return v4.is_loopback() || v4.is_private() || v4 == Ipv4Addr::new(10, 0, 2, 2);
    }
    if let Some(v6) = host.strip_prefix('[').and_then(|h| h.strip_suffix(']')) {
        return v6.parse::<Ipv6Addr>().is_ok_and(|a| a.is_loopback());
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepted() {
        for (input, canonical) in [
            ("https://wa.example.com", "https://wa.example.com"),
            ("HTTPS://WA.Example.com/", "https://wa.example.com"),
            (
                "  https://wa.example.com:8443  ",
                "https://wa.example.com:8443",
            ),
            ("http://127.0.0.1:8787", "http://127.0.0.1:8787"),
            ("http://localhost:8787/", "http://localhost:8787"),
            ("http://10.0.2.2:8787", "http://10.0.2.2:8787"),
            ("http://192.168.1.5", "http://192.168.1.5"),
            ("http://172.16.0.9:80", "http://172.16.0.9:80"),
            ("http://foo.local", "http://foo.local"),
            ("http://[::1]:8787", "http://[::1]:8787"),
            ("https://8.8.8.8", "https://8.8.8.8"),
        ] {
            let url = GatewayUrl::parse(input).unwrap_or_else(|e| panic!("{input}: {e}"));
            assert_eq!(url.as_str(), canonical, "{input}");
        }
    }

    #[test]
    fn rejected() {
        let cases: [(&str, UrlError); 12] = [
            ("", UrlError::Empty),
            ("example.com", UrlError::MissingScheme),
            (
                "ftp://example.com",
                UrlError::UnsupportedScheme("ftp".into()),
            ),
            ("https://", UrlError::EmptyHost),
            ("https://:8443", UrlError::EmptyHost),
            ("https://example.com:0", UrlError::InvalidPort),
            ("https://example.com:99999", UrlError::InvalidPort),
            ("https://example.com/v1", UrlError::UnexpectedPart),
            ("https://example.com?x=1", UrlError::UnexpectedPart),
            ("https://user@example.com", UrlError::UnexpectedPart),
            (
                "http://example.com",
                UrlError::InsecureHost("example.com".into()),
            ),
            (
                "http://172.32.0.1",
                UrlError::InsecureHost("172.32.0.1".into()),
            ),
        ];
        for (input, err) in cases {
            assert_eq!(GatewayUrl::parse(input), Err(err), "{input}");
        }
        assert!(GatewayUrl::parse("http://8.8.8.8").is_err());
        assert!(GatewayUrl::parse("http://[2001:db8::1]").is_err());
    }

    #[test]
    fn join_and_ws() {
        let https = GatewayUrl::parse("https://wa.example.com").unwrap();
        assert_eq!(https.join("/v1/chats"), "https://wa.example.com/v1/chats");
        assert_eq!(https.join("v1/chats"), "https://wa.example.com/v1/chats");
        assert_eq!(https.ws("/v1/events"), "wss://wa.example.com/v1/events");
        let http = GatewayUrl::parse("http://10.0.2.2:8787").unwrap();
        assert_eq!(http.ws("/v1/events"), "ws://10.0.2.2:8787/v1/events");
        assert_eq!(http.join("/healthz"), "http://10.0.2.2:8787/healthz");
    }

    #[test]
    fn serde_uses_the_canonical_string() {
        let url = GatewayUrl::parse("https://wa.example.com/").unwrap();
        let json = serde_json::to_string(&url).unwrap();
        assert_eq!(json, "\"https://wa.example.com\"");
        assert_eq!(serde_json::from_str::<GatewayUrl>(&json).unwrap(), url);
        assert!(serde_json::from_str::<GatewayUrl>("\"http://example.com\"").is_err());
    }
}
