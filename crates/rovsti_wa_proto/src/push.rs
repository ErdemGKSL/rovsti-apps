//! The data payload of a WhatsApp FCM wake-up (AD-WA6).
//!
//! The gateway sends `{"kind":"call","call_id",..}` and
//! `{"kind":"message","chat",..}`. The payload only wakes the app; the event
//! stream delivers the content.

use serde::Deserialize;

/// Why the gateway woke the app.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Wake {
    Call {
        call_id: String,
        from: Option<String>,
        name: Option<String>,
    },
    Message {
        chat: String,
    },
    /// The call stopped ringing; `missed` when nobody answered it.
    CallEnd {
        call_id: String,
        missed: bool,
    },
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Payload {
    Call {
        call_id: String,
        #[serde(default)]
        from: Option<String>,
        #[serde(default)]
        name: Option<String>,
    },
    Message {
        chat: String,
    },
    CallEnd {
        call_id: String,
        #[serde(default)]
        missed: String,
    },
}

/// Decodes a push payload; `None` for an unknown kind or malformed JSON.
pub fn parse_wake(json: &str) -> Option<Wake> {
    match serde_json::from_str::<Payload>(json).ok()? {
        Payload::Call {
            call_id,
            from,
            name,
        } => Some(Wake::Call {
            call_id,
            from,
            name,
        }),
        Payload::Message { chat } => Some(Wake::Message { chat }),
        Payload::CallEnd { call_id, missed } if !call_id.is_empty() => Some(Wake::CallEnd {
            call_id,
            missed: missed == "1",
        }),
        Payload::CallEnd { .. } => None,
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
                call_id: "abc".into(),
                from: Some("1@s.whatsapp.net".into()),
                name: Some("Ann".into()),
            })
        );
    }

    #[test]
    fn call_payload_without_details() {
        assert_eq!(
            parse_wake(r#"{"kind":"call","call_id":"abc"}"#),
            Some(Wake::Call {
                call_id: "abc".into(),
                from: None,
                name: None,
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
    fn call_end_payload() {
        let end = |missed: &str| {
            parse_wake(&format!(
                r#"{{"kind":"call_end","call_id":"abc","missed":"{missed}"}}"#
            ))
        };
        let want = |missed| {
            Some(Wake::CallEnd {
                call_id: "abc".into(),
                missed,
            })
        };
        assert_eq!(end("1"), want(true));
        assert_eq!(end("0"), want(false));
        assert_eq!(end("true"), want(false));
        assert_eq!(
            parse_wake(r#"{"kind":"call_end","call_id":"abc"}"#),
            want(false)
        );
    }

    #[test]
    fn call_end_without_id() {
        assert_eq!(parse_wake(r#"{"kind":"call_end","missed":"1"}"#), None);
        assert_eq!(
            parse_wake(r#"{"kind":"call_end","call_id":"","missed":"1"}"#),
            None
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
