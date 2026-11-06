//! Request and response bodies of the gateway HTTP API.

use rovsti_core::{Message, MessageDirection, PhoneNumber, PhoneNumberError, ThreadId};

/// Route templates shared by the server (axum) and the client. `{name}`
/// segments are path parameters; clients fill them with [`fill`].
pub mod routes {
    pub const HEALTHZ: &str = "/healthz";
    pub const INSTANCES: &str = "/v1/instances";
    pub const INSTANCE: &str = "/v1/instance";
    pub const INSTANCE_LINK: &str = "/v1/instance/link";
    pub const KEYS: &str = "/v1/keys";
    pub const KEY: &str = "/v1/keys/{key_id}";
    pub const PUSH: &str = "/v1/push";
    pub const EVENTS: &str = "/v1/events";
    pub const CHATS: &str = "/v1/chats";
    pub const CHAT_MESSAGES: &str = "/v1/chats/{jid}/messages";
    pub const SEND_TEXT: &str = "/v1/messages/text";
    pub const SCHEDULED: &str = "/v1/scheduled";
    pub const SCHEDULED_ONE: &str = "/v1/scheduled/{id}";
    pub const SEND_MEDIA: &str = "/v1/messages/media";
    pub const REACT: &str = "/v1/messages/reaction";
    pub const MARK_READ: &str = "/v1/read";
    pub const MESSAGE_MEDIA: &str = "/v1/messages/{message_id}/media";
    pub const PRESENCE: &str = "/v1/presence";
    pub const PRESENCE_SUBSCRIBE: &str = "/v1/presence/subscribe";
    pub const CHAT_STATE: &str = "/v1/chatstate";
    pub const CONTACTS_LOOKUP: &str = "/v1/contacts/lookup";
    pub const GROUPS: &str = "/v1/groups";
    pub const GROUP: &str = "/v1/groups/{jid}";
    pub const CALLS: &str = "/v1/calls";
    pub const CALL_ACCEPT: &str = "/v1/calls/{call_id}/accept";
    pub const CALL_REJECT: &str = "/v1/calls/{call_id}/reject";
    pub const CALL_END: &str = "/v1/calls/{call_id}/end";
    pub const CALL_MUTE: &str = "/v1/calls/{call_id}/mute";
    pub const CALL_AUDIO: &str = "/v1/calls/{call_id}/audio";
    pub const MESSAGE_THUMBNAIL: &str = "/v1/messages/{message_id}/thumbnail";
    pub const EDIT: &str = "/v1/messages/edit";
    pub const REVOKE: &str = "/v1/messages/revoke";
    pub const DELETE_FOR_ME: &str = "/v1/messages/delete";
    pub const STAR: &str = "/v1/messages/star";
    pub const MARK_PLAYED: &str = "/v1/played";
    pub const CHAT_ACTION: &str = "/v1/chats/action";
    pub const PROFILES: &str = "/v1/contacts/profiles";
    pub const PICTURE: &str = "/v1/pictures/{jid}";
    pub const STATUSES: &str = "/v1/statuses";
    pub const STATUS_VIEWED: &str = "/v1/statuses/viewed";
    pub const STATUS_MEDIA: &str = "/v1/statuses/{id}/media";
    pub const STATUS_THUMBNAIL: &str = "/v1/statuses/{id}/thumbnail";

    /// Replaces `{name}` in `template` with `value`, percent-encoding every
    /// byte that is not unreserved (so a JID's `@` is safe in a path).
    pub fn fill(template: &str, name: &str, value: &str) -> String {
        let mut encoded = String::with_capacity(value.len());
        for b in value.bytes() {
            match b {
                b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                    encoded.push(b as char)
                }
                _ => encoded.push_str(&format!("%{b:02X}")),
            }
        }
        template.replace(&format!("{{{name}}}"), &encoded)
    }
}

#[derive(Clone, Debug, PartialEq, thiserror::Error)]
pub enum LinkError {
    #[error(transparent)]
    Number(#[from] PhoneNumberError),
    #[error("phone number needs a country code, e.g. +90 532 ...")]
    MissingCountryCode,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AppId {
    Phone,
    Messages,
    Contacts,
    Stops,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum LinkRequest {
    Qr,
    PhoneNumber { number: String },
}

impl LinkRequest {
    /// The digits for pair-code linking: `None` for QR linking, otherwise the
    /// WhatsApp user part of the number (which needs a country code).
    pub fn pair_phone(&self) -> Result<Option<String>, LinkError> {
        match self {
            Self::Qr => Ok(None),
            Self::PhoneNumber { number } => {
                let parsed = PhoneNumber::parse(number)?;
                parsed
                    .whatsapp_user()
                    .map(|u| Some(u.to_owned()))
                    .ok_or(LinkError::MissingCountryCode)
            }
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct CreateInstance {
    pub link: LinkRequest,
    pub app: AppId,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct CreatedInstance {
    pub instance_id: String,
    pub key: String,
    pub key_id: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum InstanceState {
    Linking,
    Connected,
    Disconnected,
    LoggedOut,
    Banned,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct InstanceStatus {
    pub instance_id: String,
    pub state: InstanceState,
    pub own_jid: Option<String>,
    pub push_name: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct MintKey {
    pub label: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct IssuedKey {
    pub key_id: String,
    pub key: String,
    pub label: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct KeyInfo {
    pub key_id: String,
    pub label: String,
    pub created_ms: i64,
    pub last_used_ms: Option<i64>,
    /// True for the key that made this request. Older gateways omit it.
    #[serde(default)]
    pub current: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PushRegistration {
    pub app: AppId,
    pub token: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SendText {
    pub chat: String,
    pub text: String,
    pub quoted_id: Option<String>,
}

/// Asks the gateway to send a text at `send_at_ms` (unix milliseconds).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ScheduleText {
    pub chat: String,
    pub text: String,
    pub send_at_ms: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum ScheduledState {
    Pending,
    Sent { message_id: String },
    Failed { reason: String },
}

/// A text the gateway will send (or has tried to send) later.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ScheduledText {
    pub id: String,
    pub chat: String,
    pub text: String,
    pub send_at_ms: i64,
    pub created_ms: i64,
    pub state: ScheduledState,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Sent {
    pub message_id: String,
    pub timestamp_ms: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SendReaction {
    pub chat: String,
    pub message_id: String,
    pub from_me: bool,
    pub sender: Option<String>,
    /// Empty removes the reaction.
    pub emoji: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct MarkRead {
    pub chat: String,
    pub sender: Option<String>,
    pub ids: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SetPresence {
    pub available: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TypingState {
    Typing,
    Recording,
    Paused,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SubscribePresence {
    pub jid: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ChatState {
    pub chat: String,
    pub state: TypingState,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaKind {
    Image,
    Video,
    Audio,
    Document,
    Sticker,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Reaction {
    pub sender: Option<String>,
    pub from_me: bool,
    pub emoji: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageBody {
    Text(String),
    Media {
        kind: MediaKind,
        caption: Option<String>,
        mime: String,
        size: u64,
    },
    Unsupported,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct WaMessage {
    pub id: String,
    pub chat: String,
    pub sender: Option<String>,
    pub from_me: bool,
    pub timestamp_ms: i64,
    pub body: MessageBody,
    pub reactions: Vec<Reaction>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ChatSummary {
    pub jid: String,
    pub name: Option<String>,
    pub last: Option<WaMessage>,
    pub unread: u32,
    pub is_group: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Participant {
    pub jid: String,
    pub admin: bool,
    /// Their WhatsApp push name, when the gateway has seen it. Older
    /// gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct GroupInfo {
    pub jid: String,
    pub subject: String,
    pub participants: Vec<Participant>,
    /// The group description. Older gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    /// Only admins may send (announcement group).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub announce: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PlaceCall {
    pub to: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct CallStarted {
    pub call_id: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SetMuted {
    pub muted: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    Unauthorized,
    NotFound,
    BadRequest,
    Conflict,
    LimitReached,
    NotConnected,
    RateLimited,
    Internal,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, thiserror::Error)]
#[error("{code:?}: {message}")]
pub struct ApiError {
    pub code: ErrorCode,
    pub message: String,
}

impl ApiError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

/// User part of a JID: `905321234567:12@s.whatsapp.net` gives `905321234567`.
pub fn jid_user(jid: &str) -> &str {
    let local = jid.split('@').next().unwrap_or(jid);
    local.split(':').next().unwrap_or(local)
}

fn media_label(kind: MediaKind) -> &'static str {
    match kind {
        MediaKind::Image => "[Photo]",
        MediaKind::Video => "[Video]",
        MediaKind::Audio => "[Voice message]",
        MediaKind::Document => "[Document]",
        MediaKind::Sticker => "[Sticker]",
    }
}

/// Maps a gateway message to the unified model the Messages app renders.
pub fn to_rovsti(m: &WaMessage) -> Message {
    let sender = if m.from_me {
        None
    } else {
        // Group chats carry the sender's JID; one-to-one chats are the chat JID.
        let jid = m.sender.as_deref().unwrap_or(&m.chat);
        let user = jid_user(jid);
        (!user.is_empty())
            .then(|| PhoneNumber::parse(&format!("+{user}")).ok())
            .flatten()
    };
    let body = match &m.body {
        MessageBody::Text(t) => t.clone(),
        MessageBody::Media { kind, caption, .. } => match caption.as_deref() {
            Some(c) if !c.is_empty() => format!("{} {c}", media_label(*kind)),
            _ => media_label(*kind).to_owned(),
        },
        MessageBody::Unsupported => "[Unsupported message]".to_owned(),
    };
    Message {
        thread: ThreadId::WhatsApp(m.chat.clone()),
        direction: if m.from_me {
            MessageDirection::Outgoing
        } else {
            MessageDirection::Incoming
        },
        sender,
        body,
        timestamp_ms: m.timestamp_ms,
        read: m.from_me,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn to_json<T: serde::Serialize>(v: &T) -> String {
        serde_json::to_string(v).unwrap()
    }

    fn pinned<T>(cases: &[(T, &str)])
    where
        T: serde::Serialize + serde::de::DeserializeOwned + PartialEq + std::fmt::Debug,
    {
        for (value, text) in cases {
            assert_eq!(&to_json(value), text);
            assert_eq!(&serde_json::from_str::<T>(text).unwrap(), value);
        }
    }

    #[test]
    fn simple_enums_are_pinned() {
        pinned(&[
            (AppId::Phone, "\"phone\""),
            (AppId::Messages, "\"messages\""),
            (AppId::Contacts, "\"contacts\""),
            (AppId::Stops, "\"stops\""),
        ]);
        pinned(&[
            (InstanceState::Linking, "\"linking\""),
            (InstanceState::Connected, "\"connected\""),
            (InstanceState::Disconnected, "\"disconnected\""),
            (InstanceState::LoggedOut, "\"logged_out\""),
            (InstanceState::Banned, "\"banned\""),
        ]);
        pinned(&[
            (TypingState::Typing, "\"typing\""),
            (TypingState::Recording, "\"recording\""),
            (TypingState::Paused, "\"paused\""),
        ]);
        pinned(&[
            (MediaKind::Image, "\"image\""),
            (MediaKind::Video, "\"video\""),
            (MediaKind::Audio, "\"audio\""),
            (MediaKind::Document, "\"document\""),
            (MediaKind::Sticker, "\"sticker\""),
        ]);
        pinned(&[
            (ErrorCode::Unauthorized, "\"unauthorized\""),
            (ErrorCode::NotFound, "\"not_found\""),
            (ErrorCode::BadRequest, "\"bad_request\""),
            (ErrorCode::Conflict, "\"conflict\""),
            (ErrorCode::LimitReached, "\"limit_reached\""),
            (ErrorCode::NotConnected, "\"not_connected\""),
            (ErrorCode::RateLimited, "\"rate_limited\""),
            (ErrorCode::Internal, "\"internal\""),
        ]);
    }

    #[test]
    fn tagged_types_are_pinned() {
        pinned(&[
            (LinkRequest::Qr, r#"{"type":"qr"}"#),
            (
                LinkRequest::PhoneNumber {
                    number: "+90 532".into(),
                },
                r#"{"type":"phone_number","number":"+90 532"}"#,
            ),
        ]);
        pinned(&[
            (MessageBody::Text("hi".into()), r#"{"text":"hi"}"#),
            (MessageBody::Unsupported, "\"unsupported\""),
            (
                MessageBody::Media {
                    kind: MediaKind::Image,
                    caption: None,
                    mime: "image/jpeg".into(),
                    size: 10,
                },
                r#"{"media":{"kind":"image","caption":null,"mime":"image/jpeg","size":10}}"#,
            ),
        ]);
        pinned(&[(
            CreateInstance {
                link: LinkRequest::Qr,
                app: AppId::Stops,
            },
            r#"{"link":{"type":"qr"},"app":"stops"}"#,
        )]);
        pinned(&[(
            ApiError::new(ErrorCode::NotConnected, "offline"),
            r#"{"code":"not_connected","message":"offline"}"#,
        )]);
        pinned(&[(SetMuted { muted: true }, r#"{"muted":true}"#)]);
        pinned(&[
            (ScheduledState::Pending, r#"{"status":"pending"}"#),
            (
                ScheduledState::Sent {
                    message_id: "M".into(),
                },
                r#"{"status":"sent","message_id":"M"}"#,
            ),
            (
                ScheduledState::Failed { reason: "x".into() },
                r#"{"status":"failed","reason":"x"}"#,
            ),
        ]);
        pinned(&[(
            ScheduleText {
                chat: "c".into(),
                text: "t".into(),
                send_at_ms: 5,
            },
            r#"{"chat":"c","text":"t","send_at_ms":5}"#,
        )]);
        pinned(&[(
            ChatState {
                chat: "c".into(),
                state: TypingState::Typing,
            },
            r#"{"chat":"c","state":"typing"}"#,
        )]);
    }

    #[test]
    fn pair_phone() {
        let qr = LinkRequest::Qr;
        assert_eq!(qr.pair_phone(), Ok(None));
        let intl = LinkRequest::PhoneNumber {
            number: "+90 (532) 123-45-67".into(),
        };
        assert_eq!(intl.pair_phone(), Ok(Some("905321234567".into())));
        let double_zero = LinkRequest::PhoneNumber {
            number: "0090 532 123 45 67".into(),
        };
        assert_eq!(double_zero.pair_phone(), Ok(Some("905321234567".into())));
        let local = LinkRequest::PhoneNumber {
            number: "0532 123 45 67".into(),
        };
        assert_eq!(local.pair_phone(), Err(LinkError::MissingCountryCode));
        let junk = LinkRequest::PhoneNumber {
            number: "abc".into(),
        };
        assert!(matches!(junk.pair_phone(), Err(LinkError::Number(_))));
        let empty = LinkRequest::PhoneNumber { number: " ".into() };
        assert_eq!(
            empty.pair_phone(),
            Err(LinkError::Number(PhoneNumberError::Empty))
        );
    }

    #[test]
    fn route_fill_encodes() {
        assert_eq!(
            routes::fill(routes::CHAT_MESSAGES, "jid", "9053@s.whatsapp.net"),
            "/v1/chats/9053%40s.whatsapp.net/messages"
        );
        assert_eq!(routes::fill(routes::KEY, "key_id", "ab12"), "/v1/keys/ab12");
        assert_eq!(
            routes::fill(routes::SCHEDULED_ONE, "id", "ab12"),
            "/v1/scheduled/ab12"
        );
    }

    fn wa(from_me: bool, sender: Option<&str>, body: MessageBody) -> WaMessage {
        WaMessage {
            id: "m".into(),
            chat: "905321234567@s.whatsapp.net".into(),
            sender: sender.map(str::to_owned),
            from_me,
            timestamp_ms: 1_700_000_000_000,
            body,
            reactions: vec![],
        }
    }

    #[test]
    fn to_rovsti_incoming_direct() {
        let m = to_rovsti(&wa(false, None, MessageBody::Text("selam".into())));
        assert_eq!(
            m.thread,
            ThreadId::WhatsApp("905321234567@s.whatsapp.net".into())
        );
        assert_eq!(m.direction, MessageDirection::Incoming);
        assert_eq!(m.sender.unwrap().as_str(), "+905321234567");
        assert_eq!(m.body, "selam");
        assert!(!m.read);
        assert_eq!(m.timestamp_ms, 1_700_000_000_000);
    }

    #[test]
    fn to_rovsti_group_sender_and_device_suffix() {
        let mut w = wa(
            false,
            Some("4915123456789:7@s.whatsapp.net"),
            MessageBody::Text("x".into()),
        );
        w.chat = "123-456@g.us".into();
        let m = to_rovsti(&w);
        assert_eq!(m.sender.unwrap().as_str(), "+4915123456789");
    }

    #[test]
    fn to_rovsti_outgoing_and_media() {
        let out = to_rovsti(&wa(true, None, MessageBody::Text("ok".into())));
        assert_eq!(out.direction, MessageDirection::Outgoing);
        assert!(out.sender.is_none());
        assert!(out.read);

        let media = |caption: Option<&str>| MessageBody::Media {
            kind: MediaKind::Image,
            caption: caption.map(str::to_owned),
            mime: "image/jpeg".into(),
            size: 1,
        };
        assert_eq!(to_rovsti(&wa(false, None, media(None))).body, "[Photo]");
        assert_eq!(
            to_rovsti(&wa(false, None, media(Some("hey")))).body,
            "[Photo] hey"
        );
        assert_eq!(
            to_rovsti(&wa(false, None, MessageBody::Unsupported)).body,
            "[Unsupported message]"
        );
    }

    #[test]
    fn to_rovsti_non_numeric_sender_is_none() {
        let mut w = wa(false, Some("lid-abc@lid"), MessageBody::Text("x".into()));
        w.chat = "g@g.us".into();
        assert!(to_rovsti(&w).sender.is_none());
    }
}
