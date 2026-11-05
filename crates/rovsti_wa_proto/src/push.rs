//! The data payload of a WhatsApp FCM wake-up (AD-WA6).
//!
//! The gateway sends `{"kind":"call","call_id",..}` and
//! `{"kind":"message","chat",..}`. The payload only wakes the app; the event
//! stream delivers the content.

use serde::Deserialize;

/// Why the gateway woke the app.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Wake {
    Call { call_id: String },
    Message { chat: String },
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Payload {
    Call { call_id: String },
    Message { chat: String },
}

/// Decodes a push payload; `None` for an unknown kind or malformed JSON.
pub fn parse_wake(json: &str) -> Option<Wake> {
    match serde_json::from_str::<Payload>(json).ok()? {
        Payload::Call { call_id } => Some(Wake::Call { call_id }),
        Payload::Message { chat } => Some(Wake::Message { chat }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn call_payload() {
        let json = r#"{"kind":"call","call_id":"abc","from":"1@s.whatsapp.net","name":"Ann"}"#;
        assert_eq!(
            parse_wake(json),
            Some(Wake::Call {
                call_id: "abc".into()
            })
        );
    }

    #[test]
    fn message_payload() {
        let json = r#"{"kind":"message","chat":"123@g.us","count":"2"}"#;
        assert_eq!(
            parse_wake(json),
            Some(Wake::Message {
                chat: "123@g.us".into()
            })
        );
    }

    #[test]
    fn unknown_kind_and_garbage() {
        assert_eq!(parse_wake(r#"{"kind":"status","chat":"c"}"#), None);
        assert_eq!(parse_wake(r#"{"kind":"call"}"#), None);
        assert_eq!(parse_wake("not json"), None);
        assert_eq!(parse_wake(""), None);
        assert_eq!(parse_wake("[]"), None);
    }
}
