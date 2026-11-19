//! Request and response bodies of the gateway HTTP API.

use rosty_core::{Message, MessageDirection, PhoneNumber, PhoneNumberError, ThreadId};

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
    /// The same send as [`SEND_MEDIA`]. A request that carries an option
    /// from the media round (preview, pixel size, waveform, GIF flag, view
    /// once) goes here: a gateway from before those options has no such
    /// route and answers 404, where on [`SEND_MEDIA`] it would drop them
    /// without a word (a view-once photo sent as an ordinary one) and read a
    /// preview in the body as part of the file.
    pub const SEND_MEDIA_OPTIONS: &str = "/v1/messages/media/options";
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
    /// Forward a stored message to another chat (E6-WA R4). Older gateways
    /// answer 404.
    pub const FORWARD: &str = "/v1/messages/forward";
    /// Who got, read or played one of my messages, with times, `?chat=`
    /// (E6-WA R4). Older gateways answer 404.
    pub const MESSAGE_RECEIPTS: &str = "/v1/messages/{message_id}/receipts";
    pub const CHAT_ACTION: &str = "/v1/chats/action";
    pub const PROFILES: &str = "/v1/contacts/profiles";
    pub const PICTURE: &str = "/v1/pictures/{jid}";
    pub const STATUSES: &str = "/v1/statuses";
    pub const STATUS_VIEWED: &str = "/v1/statuses/viewed";
    pub const STATUS_MEDIA: &str = "/v1/statuses/{id}/media";
    pub const STATUS_THUMBNAIL: &str = "/v1/statuses/{id}/thumbnail";
    /// The LID and phone-number pairs the gateway learned, paged by
    /// `?since=<cursor>` ([`crate::IdentityMappings`]). Older gateways do not
    /// have it and answer 404.
    pub const IDENTITY_MAPPINGS: &str = "/v1/identity/mappings";
    /// People sync v3: start or join the instance's job (`POST`).
    pub const PEOPLE_SYNC: &str = "/v1/people/sync";
    /// The job's status, long-polled with `?rev=&wait_ms=` (`GET`), or its cancel (`DELETE`).
    pub const PEOPLE_SYNC_JOB: &str = "/v1/people/sync/{job_id}";
    /// One part of a ready job's bundle, `?after=&max_bytes=` (`GET`).
    pub const PEOPLE_SYNC_BUNDLE: &str = "/v1/people/sync/{job_id}/bundle";

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
    /// This account's LID (`<user>@lid`), the other form of `own_jid`. Older
    /// gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub own_lid: Option<String>,
    /// The id of this gateway process: a new one after every restart. Event
    /// sequences start over with it, so a device that stored another epoch
    /// has a gap whatever its last `seq` says. Older gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub epoch: Option<String>,
    /// Raised each time the linked account (own number) changes; the stored
    /// rows of the account before were cleared. Older gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub account_epoch: Option<u32>,
    /// How far WhatsApp's history sync got. Older gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub history: Option<HistoryStatus>,
}

/// Where the history sync of an instance stands.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HistoryState {
    /// No history sync arrived since the account was linked.
    #[default]
    Idle,
    /// Chunks are arriving; more are expected.
    Syncing,
    /// The last expected chunk arrived, or none came for a long while.
    Done,
    /// A state from a newer gateway.
    #[serde(other)]
    Unknown,
}

/// `InstanceStatus.history`: the history sync and what the gateway holds.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct HistoryStatus {
    pub state: HistoryState,
    /// 0 to 100 over the whole sync, never going back while it runs; `None`
    /// when WhatsApp gave no progress or nothing is running.
    pub progress: Option<u32>,
    /// Chats the gateway holds for the account.
    pub chats: u32,
    /// Messages the gateway holds for the account.
    pub messages: u64,
    /// Time of the oldest held message (unix ms).
    pub oldest_ms: Option<i64>,
}

/// What kind of history chunk a `history_synced` event announces.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HistorySyncType {
    /// The first chunk after linking: the chat list and the newest messages.
    Initial,
    /// The recent messages, in several chunks.
    Recent,
    /// The older messages of a full history sync.
    Full,
    /// The answer to a request for older messages of one chat.
    OnDemand,
    /// Push names, status updates and other data, or a kind this build does
    /// not know.
    #[serde(other)]
    Other,
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

/// The public half of an app install's push key (round 7b). The gateway seals
/// what a push says to it, so FCM carries only ciphertext. The private half
/// never leaves the phone's Keystore.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PushKey {
    /// Chosen by the app; names the Keystore key a sealed push was made for.
    pub key_id: String,
    /// The sealing scheme; [`PUSH_KEY_VERSION`] is the only one today.
    pub version: u32,
    /// The X.509 `SubjectPublicKeyInfo` of a P-256 key, standard base64.
    pub public_key: String,
}

/// The one push sealing scheme: ephemeral-static ECDH on P-256, HKDF-SHA256,
/// AES-256-GCM (`docs/whatsapp-gateway.md`, "Sealed push").
pub const PUSH_KEY_VERSION: u32 = 1;

/// What `POST /v1/push` takes: the token and, from an app that has one, its
/// push key. Without a key the gateway forgets any key it held for the app
/// and sends that app content-free pushes. A separate struct, so
/// [`PushRegistration`] keeps its shape for older callers.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PushKeyedRegistration {
    pub app: AppId,
    pub token: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub push_key: Option<PushKey>,
}

impl From<PushRegistration> for PushKeyedRegistration {
    fn from(reg: PushRegistration) -> Self {
        Self {
            app: reg.app,
            token: reg.token,
            push_key: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SendText {
    pub chat: String,
    pub text: String,
    pub quoted_id: Option<String>,
    /// JIDs the text mentions with `@<user>` tokens (`contextInfo.mentioned_jid`).
    /// An older gateway ignores it and sends the plain text.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub mentions: Vec<String>,
    /// The preview card of the link in the text, made on the phone. An older
    /// gateway ignores it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub link_preview: Option<LinkPreviewOut>,
}

impl SendText {
    /// A plain text, or a reply when `quoted_id` is set.
    pub fn new(
        chat: impl Into<String>,
        text: impl Into<String>,
        quoted_id: Option<String>,
    ) -> Self {
        Self {
            chat: chat.into(),
            text: text.into(),
            quoted_id,
            mentions: Vec::new(),
            link_preview: None,
        }
    }
}

/// The preview card of a link a text sends ([`SendText::link_preview`]).
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct LinkPreviewOut {
    /// The link as it is written in the text (`matchedText`).
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    /// A small JPEG, base64, at most
    /// [`MAX_SEND_THUMBNAIL_BYTES`](crate::MAX_SEND_THUMBNAIL_BYTES) decoded.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub thumbnail_b64: Option<String>,
}

impl LinkPreviewOut {
    /// The thumbnail bytes, checked like a media preview (a JPEG within the
    /// size limit); `Ok(None)` without one.
    pub fn thumbnail(&self) -> Result<Option<Vec<u8>>, crate::MediaOptionError> {
        let Some(b64) = self.thumbnail_b64.as_deref() else {
            return Ok(None);
        };
        let bytes = crate::decode_b64(b64).ok_or(crate::MediaOptionError::ThumbnailNotBase64)?;
        crate::check_thumbnail(&bytes)?;
        Ok(Some(bytes))
    }
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
    /// The other form of `sender` (its LID when `sender` is a phone-number
    /// JID, its phone-number JID when `sender` is a LID), when the gateway
    /// knows it. Older gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sender_alt: Option<String>,
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
    /// The other form of `chat` in a one-to-one chat (the LID next to a
    /// phone-number JID, or the reverse), when the gateway knows it. Bare:
    /// no device, no agent. Older gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub chat_alt: Option<String>,
    /// The other form of `sender`, when the gateway knows it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sender_alt: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ChatSummary {
    pub jid: String,
    pub name: Option<String>,
    pub last: Option<WaMessage>,
    pub unread: u32,
    pub is_group: bool,
    /// The other form of `jid` in a one-to-one chat, when the gateway knows
    /// it. Older gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub alt: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Participant {
    pub jid: String,
    pub admin: bool,
    /// Their WhatsApp push name, when the gateway has seen it. Older
    /// gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Their LID (`<user>@lid`), when the gateway knows it. Older gateways
    /// omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lid: Option<String>,
    /// Their phone-number JID, when the gateway knows it. A member who hides
    /// their number has none.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pn: Option<String>,
}

/// [`GroupInfo::addressing_mode`] of a group whose members WhatsApp names by
/// phone number.
pub const ADDRESSING_PN: &str = "pn";
/// [`GroupInfo::addressing_mode`] of a group whose members WhatsApp names by
/// LID.
pub const ADDRESSING_LID: &str = "lid";

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
    /// How WhatsApp names the members on the wire: [`ADDRESSING_PN`] or
    /// [`ADDRESSING_LID`]. Older gateways omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub addressing_mode: Option<String>,
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
    /// With `rate_limited`: how long to wait before asking again, in ms.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub retry_after_ms: Option<u64>,
    /// With `rate_limited`: who set the limit (`source` on the wire; the
    /// Rust name keeps `thiserror` from taking it for an error source).
    #[serde(rename = "source", default, skip_serializing_if = "Option::is_none")]
    pub limit_source: Option<LimitSource>,
}

/// Who refused a request for being too fast.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LimitSource {
    /// WhatsApp's servers answered "rate-overlimit" (or the gateway is
    /// waiting out such an answer).
    Whatsapp,
    /// The gateway's own per-instance allowance.
    Gateway,
    /// A source a newer gateway reports that this build does not know.
    #[serde(other)]
    Other,
}

impl ApiError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            retry_after_ms: None,
            limit_source: None,
        }
    }

    /// A `rate_limited` error: wait `retry_after_ms`, the limit is `source`'s.
    pub fn rate_limited(
        message: impl Into<String>,
        retry_after_ms: Option<u64>,
        source: LimitSource,
    ) -> Self {
        Self {
            retry_after_ms,
            limit_source: Some(source),
            ..Self::new(ErrorCode::RateLimited, message)
        }
    }
}

/// User part of a JID: `905321234567:12@s.whatsapp.net` gives `905321234567`.
pub fn jid_user(jid: &str) -> &str {
    let local = jid.split('@').next().unwrap_or(jid);
    local.split(':').next().unwrap_or(local)
}

/// What to call a WhatsApp user whose only identity is a LID.
pub const WHATSAPP_USER_LABEL: &str = "WhatsApp user";

fn jid_server(jid: &str) -> Option<(&str, &str)> {
    jid.split_once('@')
}

/// A phone-number JID: digits-only user on `s.whatsapp.net`; a device suffix
/// (`:12`) is allowed.
pub fn is_pn_jid(jid: &str) -> bool {
    jid_server(jid).is_some_and(|(_, server)| server == "s.whatsapp.net") && {
        let user = jid_user(jid);
        !user.is_empty() && user.bytes().all(|b| b.is_ascii_digit())
    }
}

/// A WhatsApp LID identity (`...@lid`), which is not a phone number.
pub fn is_lid_jid(jid: &str) -> bool {
    jid_server(jid).is_some_and(|(_, server)| server == "lid")
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
pub fn to_rosty(m: &WaMessage) -> Message {
    let sender = if m.from_me {
        None
    } else {
        // Group chats carry the sender's JID; one-to-one chats are the chat JID.
        let jid = m.sender.as_deref().unwrap_or(&m.chat);
        is_pn_jid(jid)
            .then(|| PhoneNumber::parse(&format!("+{}", jid_user(jid))).ok())
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
    fn api_error_stays_compatible_both_ways() {
        // An old gateway's body still parses.
        let old: ApiError =
            serde_json::from_str(r#"{"code":"rate_limited","message":"too many"}"#).unwrap();
        assert_eq!(old, ApiError::new(ErrorCode::RateLimited, "too many"));
        // An old client (no new fields, no deny_unknown_fields) still parses a new body.
        #[derive(serde::Deserialize)]
        struct OldApiError {
            code: ErrorCode,
            message: String,
        }
        let new = to_json(&ApiError::rate_limited("x", Some(5), LimitSource::Whatsapp));
        let parsed: OldApiError = serde_json::from_str(&new).unwrap();
        assert_eq!(
            (parsed.code, parsed.message.as_str()),
            (ErrorCode::RateLimited, "x")
        );
        // A source this build does not know is not an error.
        let future: ApiError = serde_json::from_str(
            r#"{"code":"rate_limited","message":"x","source":"cloud","extra":1}"#,
        )
        .unwrap();
        assert_eq!(future.limit_source, Some(LimitSource::Other));
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
        pinned(&[
            (
                ApiError::new(ErrorCode::NotConnected, "offline"),
                r#"{"code":"not_connected","message":"offline"}"#,
            ),
            (
                ApiError::rate_limited("slow down", Some(61_000), LimitSource::Whatsapp),
                r#"{"code":"rate_limited","message":"slow down","retry_after_ms":61000,"source":"whatsapp"}"#,
            ),
            (
                ApiError::rate_limited("slow down", None, LimitSource::Gateway),
                r#"{"code":"rate_limited","message":"slow down","source":"gateway"}"#,
            ),
        ]);
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
            chat_alt: None,
            sender_alt: None,
        }
    }

    #[test]
    fn to_rosty_incoming_direct() {
        let m = to_rosty(&wa(false, None, MessageBody::Text("selam".into())));
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
    fn to_rosty_group_sender_and_device_suffix() {
        let mut w = wa(
            false,
            Some("4915123456789:7@s.whatsapp.net"),
            MessageBody::Text("x".into()),
        );
        w.chat = "123-456@g.us".into();
        let m = to_rosty(&w);
        assert_eq!(m.sender.unwrap().as_str(), "+4915123456789");
    }

    #[test]
    fn to_rosty_outgoing_and_media() {
        let out = to_rosty(&wa(true, None, MessageBody::Text("ok".into())));
        assert_eq!(out.direction, MessageDirection::Outgoing);
        assert!(out.sender.is_none());
        assert!(out.read);

        let media = |caption: Option<&str>| MessageBody::Media {
            kind: MediaKind::Image,
            caption: caption.map(str::to_owned),
            mime: "image/jpeg".into(),
            size: 1,
        };
        assert_eq!(to_rosty(&wa(false, None, media(None))).body, "[Photo]");
        assert_eq!(
            to_rosty(&wa(false, None, media(Some("hey")))).body,
            "[Photo] hey"
        );
        assert_eq!(
            to_rosty(&wa(false, None, MessageBody::Unsupported)).body,
            "[Unsupported message]"
        );
    }

    #[test]
    fn pn_and_lid_jids() {
        for (jid, pn, lid) in [
            ("905321234567@s.whatsapp.net", true, false),
            ("905321234567:12@s.whatsapp.net", true, false),
            ("96413459456093@lid", false, true),
            ("96413459456093:3@lid", false, true),
            ("120363@g.us", false, false),
            ("abc@s.whatsapp.net", false, false),
            ("@s.whatsapp.net", false, false),
            ("905321234567", false, false),
            ("status@broadcast", false, false),
        ] {
            assert_eq!(is_pn_jid(jid), pn, "{jid}");
            assert_eq!(is_lid_jid(jid), lid, "{jid}");
        }
    }

    #[test]
    fn to_rosty_lid_sender_is_none() {
        let mut w = wa(false, None, MessageBody::Text("x".into()));
        w.chat = "96413459456093@lid".into();
        assert!(to_rosty(&w).sender.is_none());
        w.sender = Some("96413459456093@lid".into());
        assert!(to_rosty(&w).sender.is_none());
    }

    #[test]
    fn to_rosty_non_numeric_sender_is_none() {
        let mut w = wa(false, Some("lid-abc@lid"), MessageBody::Text("x".into()));
        w.chat = "g@g.us".into();
        assert!(to_rosty(&w).sender.is_none());
    }

    /// What a gateway from before the identity fields sends still parses,
    /// and goes back out byte for byte.
    #[test]
    fn payloads_without_identity_fields_are_pinned() {
        pinned(&[(
            InstanceStatus {
                instance_id: "i".into(),
                state: InstanceState::Connected,
                own_jid: Some("905550000009@s.whatsapp.net".into()),
                push_name: Some("Ada".into()),
                own_lid: None,
                epoch: None,
                account_epoch: None,
                history: None,
            },
            r#"{"instance_id":"i","state":"connected","own_jid":"905550000009@s.whatsapp.net","push_name":"Ada"}"#,
        )]);
        pinned(&[(
            ChatSummary {
                jid: "905550000001@s.whatsapp.net".into(),
                name: None,
                last: None,
                unread: 2,
                is_group: false,
                alt: None,
            },
            r#"{"jid":"905550000001@s.whatsapp.net","name":null,"last":null,"unread":2,"is_group":false}"#,
        )]);
        pinned(&[(
            GroupInfo {
                jid: "1-2@g.us".into(),
                subject: "Aile".into(),
                participants: vec![Participant {
                    jid: "905550000001@s.whatsapp.net".into(),
                    admin: true,
                    name: None,
                    lid: None,
                    pn: None,
                }],
                description: None,
                announce: false,
                addressing_mode: None,
            },
            r#"{"jid":"1-2@g.us","subject":"Aile","participants":[{"jid":"905550000001@s.whatsapp.net","admin":true}]}"#,
        )]);
        pinned(&[(
            Reaction {
                sender: None,
                from_me: true,
                emoji: "x".into(),
                sender_alt: None,
            },
            r#"{"sender":null,"from_me":true,"emoji":"x"}"#,
        )]);
    }

    /// The status of a gateway that knows its epoch, the account epoch and
    /// the history sync; one from before parses with all three unset.
    #[test]
    fn status_with_epoch_account_and_history_is_pinned() {
        pinned(&[
            (
                InstanceStatus {
                    instance_id: "i".into(),
                    state: InstanceState::Connected,
                    own_jid: Some("100000000001@s.whatsapp.net".into()),
                    push_name: Some("Ada".into()),
                    own_lid: Some("9@lid".into()),
                    epoch: Some("9f2c41d07ab35e68".into()),
                    account_epoch: Some(0),
                    history: Some(HistoryStatus {
                        state: HistoryState::Idle,
                        progress: None,
                        chats: 0,
                        messages: 0,
                        oldest_ms: None,
                    }),
                },
                r#"{"instance_id":"i","state":"connected","own_jid":"100000000001@s.whatsapp.net","push_name":"Ada","own_lid":"9@lid","epoch":"9f2c41d07ab35e68","account_epoch":0,"history":{"state":"idle","progress":null,"chats":0,"messages":0,"oldest_ms":null}}"#,
            ),
            (
                InstanceStatus {
                    instance_id: "i".into(),
                    state: InstanceState::Connected,
                    own_jid: None,
                    push_name: None,
                    own_lid: None,
                    epoch: None,
                    account_epoch: Some(3),
                    history: Some(HistoryStatus {
                        state: HistoryState::Done,
                        progress: Some(100),
                        chats: 412,
                        messages: 5_000_000_000,
                        oldest_ms: Some(1_400_000_000_000),
                    }),
                },
                r#"{"instance_id":"i","state":"connected","own_jid":null,"push_name":null,"account_epoch":3,"history":{"state":"done","progress":100,"chats":412,"messages":5000000000,"oldest_ms":1400000000000}}"#,
            ),
        ]);
        pinned(&[
            (HistoryState::Idle, r#""idle""#),
            (HistoryState::Syncing, r#""syncing""#),
            (HistoryState::Done, r#""done""#),
        ]);
        pinned(&[
            (HistorySyncType::Initial, r#""initial""#),
            (HistorySyncType::Recent, r#""recent""#),
            (HistorySyncType::Full, r#""full""#),
            (HistorySyncType::OnDemand, r#""on_demand""#),
            (HistorySyncType::Other, r#""other""#),
        ]);
        let old: InstanceStatus = serde_json::from_str(
            r#"{"instance_id":"i","state":"connected","own_jid":null,"push_name":null}"#,
        )
        .unwrap();
        assert_eq!(
            (old.epoch, old.account_epoch, old.history),
            (None, None, None)
        );
        let newer: HistoryStatus = serde_json::from_str(
            r#"{"state":"paused","progress":null,"chats":1,"messages":2,"oldest_ms":null,"later":1}"#,
        )
        .unwrap();
        assert_eq!(newer.state, HistoryState::Unknown);
        assert_eq!(
            serde_json::from_str::<HistorySyncType>(r#""later_kind""#).unwrap(),
            HistorySyncType::Other
        );
    }

    #[test]
    fn payloads_with_identity_fields_are_pinned() {
        pinned(&[(
            InstanceStatus {
                instance_id: "i".into(),
                state: InstanceState::Connected,
                own_jid: Some("905550000009@s.whatsapp.net".into()),
                push_name: None,
                own_lid: Some("9@lid".into()),
                epoch: None,
                account_epoch: None,
                history: None,
            },
            r#"{"instance_id":"i","state":"connected","own_jid":"905550000009@s.whatsapp.net","push_name":null,"own_lid":"9@lid"}"#,
        )]);
        pinned(&[(
            ChatSummary {
                jid: "905550000001@s.whatsapp.net".into(),
                name: None,
                last: None,
                unread: 0,
                is_group: false,
                alt: Some("1@lid".into()),
            },
            r#"{"jid":"905550000001@s.whatsapp.net","name":null,"last":null,"unread":0,"is_group":false,"alt":"1@lid"}"#,
        )]);
        pinned(&[(
            GroupInfo {
                jid: "1-2@g.us".into(),
                subject: "Aile".into(),
                participants: vec![
                    Participant {
                        jid: "905550000001@s.whatsapp.net".into(),
                        admin: false,
                        name: Some("Ada".into()),
                        lid: Some("1@lid".into()),
                        pn: Some("905550000001@s.whatsapp.net".into()),
                    },
                    // A member who hides their number: a LID and nothing else.
                    Participant {
                        jid: "2@lid".into(),
                        admin: false,
                        name: None,
                        lid: Some("2@lid".into()),
                        pn: None,
                    },
                ],
                description: None,
                announce: false,
                addressing_mode: Some(ADDRESSING_LID.into()),
            },
            r#"{"jid":"1-2@g.us","subject":"Aile","participants":[{"jid":"905550000001@s.whatsapp.net","admin":false,"name":"Ada","lid":"1@lid","pn":"905550000001@s.whatsapp.net"},{"jid":"2@lid","admin":false,"lid":"2@lid"}],"addressing_mode":"lid"}"#,
        )]);
        pinned(&[(
            WaMessage {
                id: "m".into(),
                chat: "1-2@g.us".into(),
                sender: Some("905550000001@s.whatsapp.net".into()),
                from_me: false,
                timestamp_ms: 3,
                body: MessageBody::Unsupported,
                reactions: vec![],
                chat_alt: None,
                sender_alt: Some("1@lid".into()),
            },
            r#"{"id":"m","chat":"1-2@g.us","sender":"905550000001@s.whatsapp.net","from_me":false,"timestamp_ms":3,"body":"unsupported","reactions":[],"sender_alt":"1@lid"}"#,
        )]);
        assert_eq!((ADDRESSING_PN, ADDRESSING_LID), ("pn", "lid"));
    }

    /// A client from before the identity fields reads the new payloads: it
    /// knows none of the added keys and skips them.
    #[test]
    fn old_clients_skip_the_identity_fields() {
        #[derive(Debug, PartialEq, serde::Deserialize)]
        struct OldStatus {
            instance_id: String,
            state: InstanceState,
            own_jid: Option<String>,
            push_name: Option<String>,
        }
        let old: OldStatus = serde_json::from_str(
            r#"{"instance_id":"i","state":"connected","own_jid":"905550000009@s.whatsapp.net","push_name":null,"own_lid":"9@lid"}"#,
        )
        .unwrap();
        assert_eq!(old.own_jid.as_deref(), Some("905550000009@s.whatsapp.net"));

        #[derive(Debug, PartialEq, serde::Deserialize)]
        struct OldParticipant {
            jid: String,
            admin: bool,
        }
        let old: OldParticipant = serde_json::from_str(
            r#"{"jid":"2@lid","admin":false,"lid":"2@lid","pn":"905550000002@s.whatsapp.net"}"#,
        )
        .unwrap();
        assert_eq!(
            old,
            OldParticipant {
                jid: "2@lid".into(),
                admin: false,
            }
        );
    }

    #[test]
    fn send_text_keeps_its_old_shape_without_the_r4_fields() {
        let plain = SendText::new("100000000011@s.whatsapp.net", "Hi", None);
        assert_eq!(
            serde_json::to_value(&plain).expect("json"),
            serde_json::json!({"chat": "100000000011@s.whatsapp.net", "text": "Hi", "quoted_id": null})
        );
        let old: SendText =
            serde_json::from_str(r#"{"chat":"c","text":"t","quoted_id":null}"#).expect("old");
        assert!(old.mentions.is_empty() && old.link_preview.is_none());
    }

    #[test]
    fn link_preview_thumbnail_is_checked() {
        let mut preview = LinkPreviewOut {
            url: "https://example.com".into(),
            ..LinkPreviewOut::default()
        };
        assert_eq!(preview.thumbnail(), Ok(None));
        preview.thumbnail_b64 = Some(crate::encode_b64(&[0xFF, 0xD8, 0xFF, 0xE0, 1]));
        assert_eq!(preview.thumbnail().expect("jpeg").map(|b| b.len()), Some(5));
        preview.thumbnail_b64 = Some(crate::encode_b64(b"not a jpeg"));
        assert!(preview.thumbnail().is_err());
        preview.thumbnail_b64 = Some("%%%".into());
        assert!(preview.thumbnail().is_err());
    }
}
