//! HTTP errors: an [`ApiError`] body plus the status it travels with.

use axum::Json;
use axum::http::StatusCode;
use axum::http::header::{HeaderValue, RETRY_AFTER};
use axum::response::{IntoResponse, Response};
use rovsti_wa_proto::{ApiError, ErrorCode, LimitSource};

use crate::driver::DriverError;
use crate::store::StoreError;

#[derive(Debug)]
pub struct HttpError(pub ApiError);

impl HttpError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self(ApiError::new(code, message))
    }

    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::BadRequest, message)
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::NotFound, message)
    }

    pub fn status(&self) -> StatusCode {
        status_of(self.0.code)
    }
}

pub fn status_of(code: ErrorCode) -> StatusCode {
    match code {
        ErrorCode::Unauthorized => StatusCode::UNAUTHORIZED,
        ErrorCode::NotFound => StatusCode::NOT_FOUND,
        ErrorCode::BadRequest => StatusCode::BAD_REQUEST,
        ErrorCode::Conflict | ErrorCode::LimitReached | ErrorCode::NotConnected => {
            StatusCode::CONFLICT
        }
        ErrorCode::RateLimited => StatusCode::TOO_MANY_REQUESTS,
        ErrorCode::Internal => StatusCode::INTERNAL_SERVER_ERROR,
    }
}

/// `Retry-After` in whole seconds, rounded up.
fn retry_after_secs(ms: u64) -> u64 {
    ms.div_ceil(1000)
}

impl IntoResponse for HttpError {
    fn into_response(self) -> Response {
        let retry = self.0.retry_after_ms.map(retry_after_secs);
        let mut response = (self.status(), Json(self.0)).into_response();
        if let Some(secs) = retry {
            response
                .headers_mut()
                .insert(RETRY_AFTER, HeaderValue::from(secs));
        }
        response
    }
}

impl From<StoreError> for HttpError {
    fn from(e: StoreError) -> Self {
        match e {
            StoreError::LimitReached => Self::new(ErrorCode::LimitReached, "limit reached"),
            StoreError::NotFound => Self::not_found("not found"),
            other => {
                // Details stay in the log; the client only learns it failed.
                log::error!("store: {other}");
                Self::new(ErrorCode::Internal, "internal error")
            }
        }
    }
}

impl From<DriverError> for HttpError {
    fn from(e: DriverError) -> Self {
        match e {
            DriverError::NotConnected => {
                Self::new(ErrorCode::NotConnected, "WhatsApp is not connected")
            }
            DriverError::Unsupported => Self::bad_request("not supported"),
            DriverError::BadRequest(msg) => Self::bad_request(msg),
            DriverError::Failed(msg) => {
                log::error!("driver: {msg}");
                Self::new(ErrorCode::Internal, "WhatsApp request failed")
            }
            DriverError::RateLimited { retry_after_s } => Self(ApiError::rate_limited(
                "WhatsApp rate limit reached",
                retry_after_s.map(|s| u64::from(s) * 1000),
                LimitSource::Whatsapp,
            )),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn statuses() {
        assert_eq!(status_of(ErrorCode::Unauthorized), StatusCode::UNAUTHORIZED);
        assert_eq!(status_of(ErrorCode::LimitReached), StatusCode::CONFLICT);
        assert_eq!(
            status_of(ErrorCode::RateLimited),
            StatusCode::TOO_MANY_REQUESTS
        );
        assert_eq!(
            HttpError::from(StoreError::LimitReached).0.code,
            ErrorCode::LimitReached
        );
        assert_eq!(
            HttpError::from(DriverError::NotConnected).0.code,
            ErrorCode::NotConnected
        );
        let limited = HttpError::from(DriverError::RateLimited {
            retry_after_s: Some(7),
        });
        assert_eq!(limited.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(limited.0.retry_after_ms, Some(7_000));
        assert_eq!(limited.0.limit_source, Some(LimitSource::Whatsapp));
    }

    #[test]
    fn retry_after_header_is_whole_seconds_rounded_up() {
        assert_eq!(retry_after_secs(0), 0);
        assert_eq!(retry_after_secs(1), 1);
        assert_eq!(retry_after_secs(1_000), 1);
        assert_eq!(retry_after_secs(1_001), 2);
        let response = HttpError(ApiError::rate_limited(
            "x",
            Some(2_500),
            LimitSource::Gateway,
        ))
        .into_response();
        assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(response.headers()[RETRY_AFTER], "3");
        let plain = HttpError::new(ErrorCode::RateLimited, "x").into_response();
        assert!(plain.headers().get(RETRY_AFTER).is_none());
    }
}
