//! The one error type of the client.

use rovsti_wa_proto::{ApiError, ErrorCode, KeyError, UrlError};
use tokio_tungstenite::tungstenite;

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum ClientError {
    #[error(transparent)]
    Url(#[from] UrlError),
    #[error(transparent)]
    Key(#[from] KeyError),
    /// The gateway rejected the connect key (revoked, wrong or missing).
    #[error("the gateway rejected the connect key")]
    Unauthorized,
    #[error(transparent)]
    Api(ApiError),
    #[error("http: {0}")]
    Http(String),
    #[error("websocket: {0}")]
    Ws(String),
    #[error("tls: {0}")]
    Tls(String),
    #[error("could not decode the gateway response: {0}")]
    Decode(String),
    #[error("connection closed")]
    Closed,
}

impl From<ureq::Error> for ClientError {
    fn from(e: ureq::Error) -> Self {
        Self::Http(e.to_string())
    }
}

/// Maps a non-2xx response to an error: 401 is [`ClientError::Unauthorized`],
/// a body that parses as an [`ApiError`] is [`ClientError::Api`], anything else
/// is a plain HTTP error carrying the status.
pub(crate) fn from_status(status: u16, body: &[u8]) -> ClientError {
    if status == 401 {
        return ClientError::Unauthorized;
    }
    match serde_json::from_slice::<ApiError>(body) {
        Ok(api) if api.code == ErrorCode::Unauthorized => ClientError::Unauthorized,
        Ok(api) => ClientError::Api(api),
        Err(_) => ClientError::Http(format!("unexpected response (status {status})")),
    }
}

/// Maps a WebSocket handshake or transport error.
pub(crate) fn from_ws(e: tungstenite::Error) -> ClientError {
    match e {
        tungstenite::Error::Http(resp) => from_status(
            resp.status().as_u16(),
            resp.body().as_deref().unwrap_or(&[]),
        ),
        tungstenite::Error::ConnectionClosed | tungstenite::Error::AlreadyClosed => {
            ClientError::Closed
        }
        other => ClientError::Ws(other.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unauthorized_wins_over_body() {
        assert_eq!(from_status(401, b""), ClientError::Unauthorized);
        assert_eq!(from_status(401, b"not json"), ClientError::Unauthorized);
        let body = br#"{"code":"not_found","message":"x"}"#;
        assert_eq!(from_status(401, body), ClientError::Unauthorized);
    }

    #[test]
    fn api_error_bodies_are_mapped() {
        let body = br#"{"code":"limit_reached","message":"too many instances"}"#;
        assert_eq!(
            from_status(409, body),
            ClientError::Api(ApiError::new(ErrorCode::LimitReached, "too many instances"))
        );
        // An `unauthorized` code is Unauthorized even on a 403.
        let body = br#"{"code":"unauthorized","message":"nope"}"#;
        assert_eq!(from_status(403, body), ClientError::Unauthorized);
    }

    #[test]
    fn rate_limit_bodies_carry_the_hint_when_sent() {
        use rovsti_wa_proto::LimitSource;
        let body = br#"{"code":"rate_limited","message":"slow","retry_after_ms":60000,"source":"whatsapp"}"#;
        assert_eq!(
            from_status(429, body),
            ClientError::Api(ApiError::rate_limited(
                "slow",
                Some(60_000),
                LimitSource::Whatsapp
            ))
        );
        // An older gateway sends neither field.
        let body = br#"{"code":"rate_limited","message":"too many number lookups"}"#;
        match from_status(429, body) {
            ClientError::Api(api) => {
                assert_eq!(api.code, ErrorCode::RateLimited);
                assert_eq!((api.retry_after_ms, api.limit_source), (None, None));
            }
            other => panic!("expected an API error, got {other:?}"),
        }
    }

    #[test]
    fn unknown_bodies_keep_the_status() {
        assert_eq!(
            from_status(502, b"<html>bad gateway</html>"),
            ClientError::Http("unexpected response (status 502)".into())
        );
    }

    #[test]
    fn ws_handshake_status_is_mapped() {
        let resp = tungstenite::http::Response::builder()
            .status(401)
            .body(None)
            .unwrap();
        assert_eq!(
            from_ws(tungstenite::Error::Http(Box::new(resp))),
            ClientError::Unauthorized
        );
        let resp = tungstenite::http::Response::builder()
            .status(404)
            .body(Some(
                br#"{"code":"not_found","message":"no call"}"#.to_vec(),
            ))
            .unwrap();
        assert_eq!(
            from_ws(tungstenite::Error::Http(Box::new(resp))),
            ClientError::Api(ApiError::new(ErrorCode::NotFound, "no call"))
        );
        assert_eq!(
            from_ws(tungstenite::Error::ConnectionClosed),
            ClientError::Closed
        );
    }
}
