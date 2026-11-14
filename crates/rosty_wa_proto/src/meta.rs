//! What the gateway knows about messages, chats and people beyond the basic
//! [`WaMessage`] / [`ChatSummary`] shapes: replies, edits, deletes, delivery
//! ticks, media details, chat settings, push names and profile pictures.
//!
//! Everything is additive on the wire. `GET /v1/chats` and
//! `GET /v1/chats/{jid}/messages` send the old fields plus a `meta` object, so
//! a client that only knows [`WaMessage`] / [`ChatSummary`] keeps working, and
//! [`RichMessage`] / [`RichChat`] read the whole thing.

use crate::api::{ChatSummary, MediaKind, MessageBody, WaMessage};

/// Most JIDs one profile request may carry.
pub const MAX_PROFILES: usize = 50;

/// Mute end that means "until I unmute" (WhatsApp's own value).
pub const MUTED_FOREVER: i64 = -1;

fn is_false(b: &bool) -> bool {
    !*b
}

fn is_default<T: Default + PartialEq>(value: &T) -> bool {
    *value == T::default()
}

/// The delivery state of a message I sent, as the ticks show it. Ordered:
/// a later state never goes back to an earlier one.
#[derive(
    Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, serde::Serialize, serde::Deserialize,
)]
#[serde(rename_all = "snake_case")]
pub enum DeliveryStatus {
    /// The server has it (one tick).
    Sent,
    /// A device of the recipient has it (two ticks).
    Delivered,
    /// The recipient opened it (blue ticks).
    Read,
    /// A voice or video note was played.
    Played,
}

impl DeliveryStatus {
    /// The later of two states.
    pub fn merge(self, other: Option<Self>) -> Self {
        other.map_or(self, |o| self.max(o))
    }
}

/// The message a reply quotes.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Quoted {
    /// Id of the quoted message (look it up in the chat to jump to it).
    pub id: String,
    /// Author of the quoted message; `None` when it is unknown (the gateway
    /// fills our own JID for our messages when it knows it).
    #[serde(default)]
    pub sender: Option<String>,
    /// A one-line preview of the quoted message: its text or caption, or a
    /// label such as `[Photo]`.
    #[serde(default)]
    pub preview: Option<String>,
}

/// Details of a media message that the bubble needs before downloading.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct MediaMeta {
    /// File name of a document.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub file_name: Option<String>,
    /// Duration of audio and video, in seconds.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seconds: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
    /// Pages of a PDF document.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pages: Option<u32>,
    /// An audio message recorded as a voice note (push to talk).
    #[serde(default, skip_serializing_if = "is_false")]
    pub voice_note: bool,
    /// A video that plays as a GIF, or an animated sticker.
    #[serde(default, skip_serializing_if = "is_false")]
    pub animated: bool,
    /// View once: the media can be opened a single time.
    #[serde(default, skip_serializing_if = "is_false")]
    pub view_once: bool,
    /// `GET /v1/messages/{id}/thumbnail` has a small preview image.
    #[serde(default, skip_serializing_if = "is_false")]
    pub thumbnail: bool,
}

/// A shared location.
#[derive(Clone, Debug, Default, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Location {
    pub latitude: f64,
    pub longitude: f64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub address: Option<String>,
    /// Live location (the sender shares their position for a while).
    #[serde(default, skip_serializing_if = "is_false")]
    pub live: bool,
}

/// A shared contact card.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SharedContact {
    pub display_name: String,
    /// The vCard text as sent.
    pub vcard: String,
}

/// Everything about a message that is not in [`WaMessage`].
#[derive(Clone, Debug, Default, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct MessageMeta {
    /// The reply context, when this message quotes another.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub quoted: Option<Quoted>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub forwarded: bool,
    /// When the message was last edited (unix ms); the body is the new text.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub edited_ms: Option<i64>,
    /// Deleted: show "This message was deleted". The body reads as
    /// `unsupported` and the content fields are empty, as they always did; a
    /// gateway that keeps deleted messages also sets [`Self::deleted`] and
    /// puts what the message said in [`Self::original`].
    #[serde(default, skip_serializing_if = "is_false")]
    pub revoked: bool,
    /// Ticks of a message I sent.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<DeliveryStatus>,
    /// The sender's WhatsApp push name when the message arrived (useful in
    /// groups, where the sender is often not in the address book).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sender_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub media: Option<MediaMeta>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub location: Option<Location>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub contact: Option<SharedContact>,
    /// JIDs mentioned with `@`.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub mentions: Vec<String>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub starred: bool,
    /// The message was deleted (for everyone, or for me on this account) and
    /// the gateway kept it, marked. `revoked` is set too, so older clients
    /// show it as deleted.
    #[serde(default, skip_serializing_if = "is_false")]
    pub deleted: bool,
    /// When it was deleted (unix ms); `None` when unknown (deleted before the
    /// gateway kept deleted messages).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub deleted_at: Option<i64>,
    /// Who deleted it: [`DELETED_BY_ME`] or the JID of the sender (or group
    /// admin) who deleted it for everyone.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub deleted_by: Option<String>,
    /// What a deleted message said ("show content" on long-press); `None`
    /// when nothing of it was kept.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub original: Option<Box<KeptContent>>,
    /// Earlier texts of an edited message, oldest first. The body holds the
    /// current text.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub edit_history: Vec<EditRecord>,
}

/// [`MessageMeta::deleted_by`] of a message this account deleted.
pub const DELETED_BY_ME: &str = "me";

/// One earlier version of an edited message.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct EditRecord {
    /// The text (or caption) before the edit.
    pub text: String,
    /// When this text was replaced (unix ms).
    pub edited_at: i64,
}

/// The content of a deleted message, kept for "show content".
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct KeptContent {
    pub body: MessageBody,
    /// Its reply context, media details, location, contact card, mentions,
    /// edit time and edit history.
    #[serde(default, skip_serializing_if = "MessageMeta::is_empty")]
    pub meta: MessageMeta,
}

impl MessageMeta {
    pub fn is_empty(&self) -> bool {
        *self == Self::default()
    }
}

/// A message with its [`MessageMeta`]. On the wire: the [`WaMessage`] fields
/// plus `meta` (left out when empty).
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct RichMessage {
    #[serde(flatten)]
    pub message: WaMessage,
    #[serde(default, skip_serializing_if = "MessageMeta::is_empty")]
    pub meta: MessageMeta,
}

impl From<WaMessage> for RichMessage {
    fn from(message: WaMessage) -> Self {
        Self {
            message,
            meta: MessageMeta::default(),
        }
    }
}

/// Chat settings and identity that are not in [`ChatSummary`].
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ChatMeta {
    /// Current profile picture id of the person or group (`None`: none
    /// known). It changes whenever the picture does, so it is a cache key for
    /// `GET /v1/pictures/{jid}`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub picture_id: Option<String>,
    /// The other person's WhatsApp push name (one-to-one chats).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub push_name: Option<String>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub archived: bool,
    #[serde(default, skip_serializing_if = "is_false")]
    pub pinned: bool,
    /// Muted until this time (unix ms), or [`MUTED_FOREVER`]. `None`: not muted.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub muted_until_ms: Option<i64>,
    /// Marked as unread by hand on another device.
    #[serde(default, skip_serializing_if = "is_false")]
    pub marked_unread: bool,
}

impl ChatMeta {
    /// True while notifications of the chat are silenced at `now_ms`.
    pub fn is_muted(&self, now_ms: i64) -> bool {
        match self.muted_until_ms {
            Some(MUTED_FOREVER) => true,
            Some(until) => until > now_ms,
            None => false,
        }
    }
}

/// A chat with its [`ChatMeta`] and the meta of its newest message.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct RichChat {
    #[serde(flatten)]
    pub chat: ChatSummary,
    #[serde(default, skip_serializing_if = "is_default")]
    pub meta: ChatMeta,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_meta: Option<MessageMeta>,
}

/// What WhatsApp tells about a person or a group.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct WaProfile {
    pub jid: String,
    /// The name they set in WhatsApp.
    #[serde(default)]
    pub push_name: Option<String>,
    /// The name this account saved for them (from WhatsApp's contact sync).
    #[serde(default)]
    pub contact_name: Option<String>,
    /// Their "About" text, when their privacy settings show it.
    #[serde(default)]
    pub about: Option<String>,
    /// Current profile picture id; `None` when there is none or it is hidden.
    #[serde(default)]
    pub picture_id: Option<String>,
    #[serde(default)]
    pub business: bool,
    #[serde(default)]
    pub verified_name: Option<String>,
    /// When the gateway last asked WhatsApp (unix ms); 0 when it never did
    /// and only knows what arrived with messages.
    #[serde(default)]
    pub fetched_ms: i64,
}

impl WaProfile {
    /// The name to show when the address book has none: the saved WhatsApp
    /// contact name, then the business name, then the push name.
    pub fn display_name(&self) -> Option<&str> {
        [&self.contact_name, &self.verified_name, &self.push_name]
            .into_iter()
            .filter_map(|n| n.as_deref())
            .map(str::trim)
            .find(|n| !n.is_empty())
    }
}

/// Body of `POST /v1/contacts/profiles`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ProfilesRequest {
    /// User (`number@s.whatsapp.net`) or group (`...@g.us`) JIDs, 1 to
    /// [`MAX_PROFILES`].
    pub jids: Vec<String>,
    /// Ask WhatsApp even when the stored profile is fresh.
    #[serde(default)]
    pub refresh: bool,
}

/// Body of `POST /v1/messages/edit`. Only texts (and captions) I sent can be
/// edited, within WhatsApp's time limit (15 minutes).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct EditMessage {
    pub chat: String,
    pub message_id: String,
    pub text: String,
}

/// Body of `POST /v1/messages/revoke`: delete a message for everyone.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct RevokeMessage {
    pub chat: String,
    pub message_id: String,
    /// Author of someone else's message, for a group admin deleting it. `None`
    /// deletes my own message.
    #[serde(default)]
    pub sender: Option<String>,
}

/// Body of `POST /v1/messages/delete`: delete a message on my devices only.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct DeleteForMe {
    pub chat: String,
    pub message_id: String,
}

/// Body of `POST /v1/messages/star`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct StarMessage {
    pub chat: String,
    pub message_id: String,
    pub starred: bool,
}

/// What `POST /v1/chats/action` does to a chat. Synced to the phone and the
/// other linked devices.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ChatActionKind {
    Archive {
        archived: bool,
    },
    Pin {
        pinned: bool,
    },
    /// Mute until `until_ms` (unix ms), or forever when it is `None`.
    Mute {
        #[serde(default)]
        until_ms: Option<i64>,
    },
    Unmute,
    /// Mark the whole chat read, or unread (the unread dot).
    MarkRead {
        read: bool,
    },
}

/// Body of `POST /v1/chats/action`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ChatAction {
    pub chat: String,
    pub action: ChatActionKind,
}

/// Query of `POST /v1/messages/media` beyond chat, kind, MIME and caption.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct MediaOptions {
    /// Reply to this message.
    #[serde(default)]
    pub quoted_id: Option<String>,
    /// File name shown for a document.
    #[serde(default)]
    pub file_name: Option<String>,
    /// Duration of audio or video, in seconds.
    #[serde(default)]
    pub seconds: Option<u32>,
    /// Send audio as a voice note even when the MIME type does not say Opus.
    #[serde(default)]
    pub voice_note: bool,
}

/// A label for media in previews and quotes.
pub fn media_preview(kind: MediaKind) -> &'static str {
    match kind {
        MediaKind::Image => "[Photo]",
        MediaKind::Video => "[Video]",
        MediaKind::Audio => "[Voice message]",
        MediaKind::Document => "[Document]",
        MediaKind::Sticker => "[Sticker]",
    }
}

/// A one-line preview of a body: the text, else the caption, else the media
/// label. At most `max_chars` characters (an ellipsis marks a cut).
pub fn body_preview(body: &MessageBody, max_chars: usize) -> Option<String> {
    let text = match body {
        MessageBody::Text(t) => t.clone(),
        MessageBody::Media { kind, caption, .. } => caption
            .as_deref()
            .filter(|c| !c.trim().is_empty())
            .map_or_else(|| media_preview(*kind).to_owned(), str::to_owned),
        MessageBody::Unsupported => return None,
    };
    let line = text.lines().next().unwrap_or("").trim();
    if line.is_empty() {
        return None;
    }
    if line.chars().count() <= max_chars {
        Some(line.to_owned())
    } else {
        let mut cut: String = line.chars().take(max_chars.saturating_sub(1)).collect();
        cut.push('\u{2026}');
        Some(cut)
    }
}

/// An HTTP entity tag for a picture id (quoted, as the header wants it).
pub fn picture_etag(picture_id: &str) -> String {
    format!("\"{}\"", picture_id.replace('"', ""))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(t: &str) -> WaMessage {
        WaMessage {
            id: "m".into(),
            chat: "c@s.whatsapp.net".into(),
            sender: None,
            from_me: true,
            timestamp_ms: 1,
            body: MessageBody::Text(t.into()),
            reactions: vec![],
        }
    }

    #[test]
    fn statuses_only_move_forward() {
        use DeliveryStatus::*;
        assert!(Sent < Delivered && Delivered < Read && Read < Played);
        assert_eq!(Delivered.merge(Some(Read)), Read);
        assert_eq!(Read.merge(Some(Delivered)), Read);
        assert_eq!(Sent.merge(None), Sent);
        assert_eq!(serde_json::to_string(&Played).unwrap(), "\"played\"");
    }

    #[test]
    fn empty_meta_is_left_out_and_old_readers_skip_it() {
        let plain = RichMessage::from(text("hi"));
        let json = serde_json::to_string(&plain).unwrap();
        assert!(!json.contains("meta"), "{json}");
        assert_eq!(serde_json::from_str::<RichMessage>(&json).unwrap(), plain);

        let rich = RichMessage {
            message: text("hi"),
            meta: MessageMeta {
                quoted: Some(Quoted {
                    id: "Q".into(),
                    sender: None,
                    preview: Some("earlier".into()),
                }),
                edited_ms: Some(5),
                status: Some(DeliveryStatus::Read),
                ..Default::default()
            },
        };
        let json = serde_json::to_string(&rich).unwrap();
        assert!(
            json.contains(r#""meta":{"quoted":{"id":"Q","sender":null,"preview":"earlier"},"edited_ms":5,"status":"read"}"#),
            "{json}"
        );
        assert_eq!(serde_json::from_str::<RichMessage>(&json).unwrap(), rich);
        // A reader that only knows WaMessage still reads the same JSON.
        assert_eq!(
            serde_json::from_str::<WaMessage>(&json).unwrap(),
            text("hi")
        );
    }

    #[test]
    fn kept_deletes_and_edit_history_are_additive() {
        let rich = RichMessage {
            message: WaMessage {
                body: MessageBody::Unsupported,
                ..text("x")
            },
            meta: MessageMeta {
                revoked: true,
                deleted: true,
                deleted_at: Some(9),
                deleted_by: Some(DELETED_BY_ME.into()),
                original: Some(Box::new(KeptContent {
                    body: MessageBody::Text("hi".into()),
                    meta: MessageMeta::default(),
                })),
                ..Default::default()
            },
        };
        let json = serde_json::to_string(&rich).unwrap();
        assert!(
            json.contains(r#""meta":{"revoked":true,"deleted":true,"deleted_at":9,"deleted_by":"me","original":{"body":{"text":"hi"}}}"#),
            "{json}"
        );
        assert_eq!(serde_json::from_str::<RichMessage>(&json).unwrap(), rich);
        // An old reader sees what an older gateway sent: a revoked message
        // whose body is gone.
        assert_eq!(
            serde_json::from_str::<WaMessage>(&json).unwrap().body,
            MessageBody::Unsupported
        );

        let edited = MessageMeta {
            edited_ms: Some(5),
            edit_history: vec![EditRecord {
                text: "frist".into(),
                edited_at: 5,
            }],
            ..Default::default()
        };
        let json = serde_json::to_string(&edited).unwrap();
        assert_eq!(
            json,
            r#"{"edited_ms":5,"edit_history":[{"text":"frist","edited_at":5}]}"#
        );
        assert_eq!(serde_json::from_str::<MessageMeta>(&json).unwrap(), edited);
        // An older gateway's meta reads with nothing deleted and no history.
        let old: MessageMeta = serde_json::from_str(r#"{"revoked":true}"#).unwrap();
        assert!(old.revoked && !old.deleted && old.edit_history.is_empty());
    }

    #[test]
    fn rich_chat_is_a_chat_summary_plus_meta() {
        let rich = RichChat {
            chat: ChatSummary {
                jid: "g@g.us".into(),
                name: Some("Aile".into()),
                last: Some(text("x")),
                unread: 2,
                is_group: true,
            },
            meta: ChatMeta {
                picture_id: Some("123".into()),
                pinned: true,
                muted_until_ms: Some(MUTED_FOREVER),
                ..Default::default()
            },
            last_meta: Some(MessageMeta {
                sender_name: Some("Ada".into()),
                ..Default::default()
            }),
        };
        let json = serde_json::to_string(&rich).unwrap();
        assert!(json.contains(r#""unread":2"#), "{json}");
        assert!(
            json.contains(r#""meta":{"picture_id":"123","pinned":true,"muted_until_ms":-1}"#),
            "{json}"
        );
        assert_eq!(serde_json::from_str::<RichChat>(&json).unwrap(), rich);
        let old: ChatSummary = serde_json::from_str(&json).unwrap();
        assert_eq!(old, rich.chat);
        // An old gateway's chat reads as a rich chat with empty meta.
        let bare = serde_json::to_string(&rich.chat).unwrap();
        let read: RichChat = serde_json::from_str(&bare).unwrap();
        assert_eq!(read.meta, ChatMeta::default());
        assert_eq!(read.last_meta, None);
    }

    #[test]
    fn mute_states() {
        let mut meta = ChatMeta::default();
        assert!(!meta.is_muted(10));
        meta.muted_until_ms = Some(MUTED_FOREVER);
        assert!(meta.is_muted(10));
        meta.muted_until_ms = Some(20);
        assert!(meta.is_muted(10));
        assert!(!meta.is_muted(20));
    }

    #[test]
    fn chat_actions_are_pinned() {
        let cases = [
            (
                ChatActionKind::Archive { archived: true },
                r#"{"type":"archive","archived":true}"#,
            ),
            (
                ChatActionKind::Pin { pinned: false },
                r#"{"type":"pin","pinned":false}"#,
            ),
            (
                ChatActionKind::Mute { until_ms: None },
                r#"{"type":"mute","until_ms":null}"#,
            ),
            (ChatActionKind::Unmute, r#"{"type":"unmute"}"#),
            (
                ChatActionKind::MarkRead { read: false },
                r#"{"type":"mark_read","read":false}"#,
            ),
        ];
        for (value, json) in cases {
            assert_eq!(serde_json::to_string(&value).unwrap(), json);
            assert_eq!(serde_json::from_str::<ChatActionKind>(json).unwrap(), value);
        }
        let mute: ChatActionKind = serde_json::from_str(r#"{"type":"mute"}"#).unwrap();
        assert_eq!(mute, ChatActionKind::Mute { until_ms: None });
    }

    #[test]
    fn profile_display_name_order() {
        let mut p = WaProfile {
            jid: "1@s.whatsapp.net".into(),
            push_name: Some("ada".into()),
            ..Default::default()
        };
        assert_eq!(p.display_name(), Some("ada"));
        p.verified_name = Some("Ada Shop".into());
        assert_eq!(p.display_name(), Some("Ada Shop"));
        p.contact_name = Some("  ".into());
        assert_eq!(p.display_name(), Some("Ada Shop"));
        p.contact_name = Some("Ada Lovelace".into());
        assert_eq!(p.display_name(), Some("Ada Lovelace"));
        let old: WaProfile = serde_json::from_str(r#"{"jid":"j"}"#).unwrap();
        assert_eq!(old.display_name(), None);
    }

    #[test]
    fn previews() {
        assert_eq!(
            body_preview(&MessageBody::Text("hello\nworld".into()), 20).as_deref(),
            Some("hello")
        );
        assert_eq!(
            body_preview(&MessageBody::Text("abcdef".into()), 4).as_deref(),
            Some("abc\u{2026}")
        );
        let photo = |caption: Option<&str>| MessageBody::Media {
            kind: MediaKind::Image,
            caption: caption.map(str::to_owned),
            mime: "image/jpeg".into(),
            size: 1,
        };
        assert_eq!(body_preview(&photo(None), 20).as_deref(), Some("[Photo]"));
        assert_eq!(
            body_preview(&photo(Some("sea")), 20).as_deref(),
            Some("sea")
        );
        assert_eq!(body_preview(&MessageBody::Unsupported, 20), None);
        assert_eq!(body_preview(&MessageBody::Text(" ".into()), 20), None);
    }

    #[test]
    fn etags_are_quoted() {
        assert_eq!(picture_etag("12345"), "\"12345\"");
        assert_eq!(picture_etag("a\"b"), "\"ab\"");
    }
}
