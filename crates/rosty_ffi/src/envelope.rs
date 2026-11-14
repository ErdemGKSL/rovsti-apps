//! The answer of every `call`: `{"ok": value}` or `{"err": {"kind", "message", "detail"?}}`.

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Why a call failed. Kotlin maps each kind to a `RustError` subclass.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorKind {
    Invalid,
    NotFound,
    Io,
    Parse,
    Network,
    Auth,
    Unsupported,
    Panic,
}

impl ErrorKind {
    /// Every kind, in declaration order.
    pub const ALL: [ErrorKind; 8] = [
        ErrorKind::Invalid,
        ErrorKind::NotFound,
        ErrorKind::Io,
        ErrorKind::Parse,
        ErrorKind::Network,
        ErrorKind::Auth,
        ErrorKind::Unsupported,
        ErrorKind::Panic,
    ];

    /// The wire name (`"not_found"`).
    pub fn name(self) -> &'static str {
        match self {
            ErrorKind::Invalid => "invalid",
            ErrorKind::NotFound => "not_found",
            ErrorKind::Io => "io",
            ErrorKind::Parse => "parse",
            ErrorKind::Network => "network",
            ErrorKind::Auth => "auth",
            ErrorKind::Unsupported => "unsupported",
            ErrorKind::Panic => "panic",
        }
    }

    /// Parses a wire name.
    pub fn from_name(name: &str) -> Option<ErrorKind> {
        Self::ALL.into_iter().find(|kind| kind.name() == name)
    }
}

/// A failed call: a kind to branch on, a sentence for the user, optional structured data.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FfiError {
    pub kind: ErrorKind,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<Value>,
}

impl FfiError {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
            detail: None,
        }
    }

    pub fn invalid(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Invalid, message)
    }

    pub fn unsupported(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Unsupported, message)
    }

    pub fn with_detail(mut self, detail: Value) -> Self {
        self.detail = Some(detail);
        self
    }
}

impl From<serde_json::Error> for FfiError {
    fn from(error: serde_json::Error) -> Self {
        Self::new(ErrorKind::Parse, error.to_string())
    }
}

impl From<std::io::Error> for FfiError {
    fn from(error: std::io::Error) -> Self {
        let kind = if error.kind() == std::io::ErrorKind::NotFound {
            ErrorKind::NotFound
        } else {
            ErrorKind::Io
        };
        Self::new(kind, error.to_string())
    }
}

/// The two shapes of an answer.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Answer {
    Ok(Value),
    Err(FfiError),
}

impl From<Result<Value, FfiError>> for Answer {
    fn from(result: Result<Value, FfiError>) -> Self {
        match result {
            Ok(value) => Answer::Ok(value),
            Err(error) => Answer::Err(error),
        }
    }
}

/// Encodes an answer as UTF-8 JSON bytes.
pub fn encode(answer: &Answer) -> Vec<u8> {
    serde_json::to_vec(answer).unwrap_or_else(|_| {
        br#"{"err":{"kind":"panic","message":"answer could not be encoded"}}"#.to_vec()
    })
}

/// Decodes bytes produced by [`encode`].
pub fn decode(bytes: &[u8]) -> Result<Answer, serde_json::Error> {
    serde_json::from_slice(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn ok_round_trips() {
        let answer = Answer::Ok(json!({"a": [1, 2], "b": null}));
        assert_eq!(decode(&encode(&answer)).unwrap(), answer);
        assert_eq!(
            String::from_utf8(encode(&Answer::Ok(json!(1)))).unwrap(),
            r#"{"ok":1}"#
        );
    }

    #[test]
    fn every_error_kind_round_trips_with_its_wire_name() {
        for kind in ErrorKind::ALL {
            let error = FfiError::new(kind, "sentence").with_detail(json!({"k": kind.name()}));
            let bytes = encode(&Answer::Err(error.clone()));
            let value: Value = serde_json::from_slice(&bytes).unwrap();
            assert_eq!(value["err"]["kind"], kind.name());
            assert_eq!(value["err"]["message"], "sentence");
            assert_eq!(decode(&bytes).unwrap(), Answer::Err(error));
            assert_eq!(ErrorKind::from_name(kind.name()), Some(kind));
        }
        assert_eq!(ErrorKind::from_name("nope"), None);
    }

    #[test]
    fn detail_is_omitted_when_absent() {
        let bytes = encode(&Answer::Err(FfiError::invalid("x")));
        let value: Value = serde_json::from_slice(&bytes).unwrap();
        assert!(value["err"].get("detail").is_none());
    }

    #[test]
    fn std_errors_map_to_kinds() {
        let missing = std::io::Error::from(std::io::ErrorKind::NotFound);
        assert_eq!(FfiError::from(missing).kind, ErrorKind::NotFound);
        let denied = std::io::Error::from(std::io::ErrorKind::PermissionDenied);
        assert_eq!(FfiError::from(denied).kind, ErrorKind::Io);
        let bad = serde_json::from_str::<Value>("{").unwrap_err();
        assert_eq!(FfiError::from(bad).kind, ErrorKind::Parse);
    }
}
