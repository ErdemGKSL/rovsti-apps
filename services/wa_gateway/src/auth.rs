//! Bearer authentication: `Authorization: Bearer rwk_...` resolves to an
//! instance through the stored key hash.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use axum::extract::{ConnectInfo, FromRequestParts};
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;
use rovsti_wa_proto::{ConnectKey, ErrorCode, KeyId};

use crate::AppState;
use crate::error::HttpError;
use crate::now_ms;
use crate::store::InstanceId;

/// A request that carried a live connect key.
#[derive(Clone, Debug)]
pub struct Authed {
    pub instance: InstanceId,
    pub key_id: KeyId,
}

/// The address to charge a failed attempt to. Behind a trusted proxy that is
/// the last `X-Forwarded-For` entry (the one the proxy itself appended);
/// otherwise the TCP peer.
pub fn client_ip(peer: Option<IpAddr>, forwarded_for: Option<&str>, trust_proxy: bool) -> IpAddr {
    if trust_proxy {
        let forwarded = forwarded_for
            .and_then(|v| v.rsplit(',').next())
            .and_then(|v| v.trim().parse::<IpAddr>().ok());
        if let Some(ip) = forwarded {
            return ip;
        }
    }
    peer.unwrap_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED))
}

/// Pulls the key out of an `Authorization` header value.
pub fn bearer_key(header: Option<&str>) -> Option<ConnectKey> {
    let value = header?.trim();
    let (scheme, token) = value.split_once(' ')?;
    if !scheme.eq_ignore_ascii_case("bearer") {
        return None;
    }
    ConnectKey::parse(token).ok()
}

fn rejected(state: &AppState, parts: &Parts) -> HttpError {
    let peer = parts
        .extensions
        .get::<ConnectInfo<SocketAddr>>()
        .map(|c| c.0.ip());
    let forwarded = parts
        .headers
        .get("x-forwarded-for")
        .and_then(|v| v.to_str().ok());
    let ip = client_ip(peer, forwarded, state.config().trust_proxy);
    if state.allow_failed_auth(ip) {
        HttpError::new(ErrorCode::Unauthorized, "missing or invalid connect key")
    } else {
        HttpError::new(ErrorCode::RateLimited, "too many failed attempts")
    }
}

impl FromRequestParts<AppState> for Authed {
    type Rejection = HttpError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let header = parts
            .headers
            .get(AUTHORIZATION)
            .and_then(|v| v.to_str().ok());
        let Some(key) = bearer_key(header) else {
            return Err(rejected(state, parts));
        };
        match state.store().instance_for_key(key.hash(), now_ms()).await? {
            Some((instance, key_id)) => Ok(Self { instance, key_id }),
            None => Err(rejected(state, parts)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bearer_parsing() {
        let key = ConnectKey::from_bytes(&[3; 32]);
        let ok = format!("Bearer {}", key.as_str());
        assert_eq!(bearer_key(Some(&ok)).unwrap(), key);
        assert!(bearer_key(Some(&format!("bearer {}", key.as_str()))).is_some());
        assert!(bearer_key(Some(&format!("Basic {}", key.as_str()))).is_none());
        assert!(bearer_key(Some("Bearer nope")).is_none());
        assert!(bearer_key(Some(key.as_str())).is_none());
        assert!(bearer_key(None).is_none());
    }

    #[test]
    fn client_ip_rules() {
        let peer: IpAddr = "10.0.0.9".parse().unwrap();
        let ip = |p, h, t| client_ip(p, h, t);
        // Without a trusted proxy the header is ignored (it is forgeable).
        assert_eq!(ip(Some(peer), Some("1.2.3.4"), false), peer);
        assert_eq!(
            ip(Some(peer), Some("1.2.3.4"), true),
            "1.2.3.4".parse::<IpAddr>().unwrap()
        );
        // The proxy's own entry is the last one; earlier ones are client-supplied.
        assert_eq!(
            ip(Some(peer), Some("6.6.6.6, 1.2.3.4"), true),
            "1.2.3.4".parse::<IpAddr>().unwrap()
        );
        assert_eq!(ip(Some(peer), Some("garbage"), true), peer);
        assert_eq!(ip(Some(peer), None, true), peer);
        assert_eq!(ip(None, None, false), IpAddr::V4(Ipv4Addr::UNSPECIFIED));
    }
}
