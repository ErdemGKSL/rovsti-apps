//! HTTP errors: an [`ApiError`] body plus the status it travels with.

use axum::Json;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use rovsti_wa_proto::{ApiError, ErrorCode};

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

impl IntoResponse for HttpError {
    fn into_response(self) -> Response {
        (self.status(), Json(self.0)).into_response()
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
            DriverError::Failed(msg) => {
                log::error!("driver: {msg}");
                Self::new(ErrorCode::Internal, "WhatsApp request failed")
            }
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
    }
}
