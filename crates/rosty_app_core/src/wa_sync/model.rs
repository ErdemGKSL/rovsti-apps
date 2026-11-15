//! What the store answers: chat rows, message rows and the results of its writes.

use rosty_wa_proto::{
    DeliveryStatus, EditRecord, Location, MediaKind, MediaMeta, MessageBody, Quoted, Reaction,
    SharedContact,
};
use serde::{Deserialize, Serialize};

use super::outbox::OutboxState;
use super::plan::FetchPage;

fn is_false(flag: &bool) -> bool {
    !*flag
}

/// What a message is. Stored as its wire name.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    Text,
    Image,
    Video,
    Audio,
    Document,
    Sticker,
    Location,
    Contact,
    /// Nothing the gateway could describe (also a deleted message whose content was never seen).
    #[default]
    Unsupported,
}

impl Kind {
    const ALL: [Kind; 9] = [
        Kind::Text,
        Kind::Image,
        Kind::Video,
        Kind::Audio,
        Kind::Document,
        Kind::Sticker,
        Kind::Location,
        Kind::Contact,
        Kind::Unsupported,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Kind::Text => "text",
            Kind::Image => "image",
            Kind::Video => "video",
            Kind::Audio => "audio",
            Kind::Document => "document",
            Kind::Sticker => "sticker",
            Kind::Location => "location",
            Kind::Contact => "contact",
            Kind::Unsupported => "unsupported",
        }
    }

    /// An unknown name (a row of a newer build) reads as [`Kind::Unsupported`].
    pub fn from_name(name: &str) -> Kind {
        Self::ALL
            .into_iter()
            .find(|kind| kind.as_str() == name)
            .unwrap_or_default()
    }

    pub fn of_media(kind: MediaKind) -> Kind {
        match kind {
            MediaKind::Image => Kind::Image,
            MediaKind::Video => Kind::Video,
            MediaKind::Audio => Kind::Audio,
            MediaKind::Document => Kind::Document,
            MediaKind::Sticker => Kind::Sticker,
        }
    }

    pub fn is_media(self) -> bool {
        matches!(
            self,
            Kind::Image | Kind::Video | Kind::Audio | Kind::Document | Kind::Sticker
        )
    }
}

/// The kind, text, MIME type and size of a message.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(super) struct Content {
    pub kind: Kind,
    /// The text, or the caption of media.
    pub text: Option<String>,
    pub mime: Option<String>,
    pub size: Option<u64>,
}

impl Content {
    pub fn of_body(body: &MessageBody) -> Content {
        match body {
            MessageBody::Text(text) => Content {
                kind: Kind::Text,
                text: Some(text.clone()),
                ..Content::default()
            },
            MessageBody::Media {
                kind,
                caption,
                mime,
                size,
            } => Content {
                kind: Kind::of_media(*kind),
                text: caption.clone(),
                mime: Some(mime.clone()),
                size: Some(*size),
            },
            MessageBody::Unsupported => Content::default(),
        }
    }

    pub fn is_known(&self) -> bool {
        self.kind != Kind::Unsupported
    }
}

/// How far a row is: the ticks of a stored message, or the outbox state of one not sent yet.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RowState {
    /// Someone else's message.
    Received,
    Sent,
    Delivered,
    Read,
    Played,
    /// In the outbox, waiting for the connection.
    Waiting,
    /// In the outbox, going out now.
    Sending,
    /// In the outbox, refused; the owner retries, edits or cancels it.
    Failed,
}

impl RowState {
    pub(super) fn of_stored(from_me: bool, status: Option<DeliveryStatus>) -> RowState {
        match (from_me, status) {
            (false, _) => RowState::Received,
            (true, None | Some(DeliveryStatus::Sent)) => RowState::Sent,
            (true, Some(DeliveryStatus::Delivered)) => RowState::Delivered,
            (true, Some(DeliveryStatus::Read)) => RowState::Read,
            (true, Some(DeliveryStatus::Played)) => RowState::Played,
        }
    }

    pub(super) fn of_outbox(state: OutboxState) -> RowState {
        match state {
            OutboxState::Waiting => RowState::Waiting,
            OutboxState::Sending => RowState::Sending,
            OutboxState::Failed => RowState::Failed,
        }
    }
}

/// An edit, delete or reaction of a stored message that is still in the outbox.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct PendingOp {
    pub client_id: String,
    /// `edit`, `revoke` or `react`.
    pub kind: String,
    pub state: OutboxState,
    /// The new text of an edit, the emoji of a reaction.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
}

/// One row of a thread: a stored message, or a send still in the outbox.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct MessageRow {
    /// The gateway message id; the client id for a row still in the outbox.
    pub id: String,
    pub chat: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sender: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sender_name: Option<String>,
    pub from_me: bool,
    pub timestamp_ms: i64,
    pub kind: Kind,
    /// The text, or the caption of media. Kept when the message was deleted.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mime: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub size: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub quoted: Option<Quoted>,
    pub reactions: Vec<Reaction>,
    pub state: RowState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub edited_ms: Option<i64>,
    /// Earlier texts, oldest first.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub edit_history: Vec<EditRecord>,
    /// Show "This message was deleted"; the content above is what it said.
    #[serde(skip_serializing_if = "is_false")]
    pub deleted: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub deleted_at: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub deleted_by: Option<String>,
    #[serde(skip_serializing_if = "is_false")]
    pub forwarded: bool,
    #[serde(skip_serializing_if = "is_false")]
    pub starred: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub media: Option<MediaMeta>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub location: Option<Location>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub contact: Option<SharedContact>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub mentions: Vec<String>,
    /// A thumbnail is stored (`Store::media`).
    #[serde(skip_serializing_if = "is_false")]
    pub has_thumbnail: bool,
    /// The cached full media file; `None` when not downloaded or evicted. For an outbox media send: the file to send.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub media_path: Option<String>,
    /// The outbox client id this message was sent with, or still waits under.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
    /// Why the last attempt of an outbox row failed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// Edits, deletes and reactions of this message that wait in the outbox.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub pending: Vec<PendingOp>,
}

/// One row of the chat list.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ChatRow {
    pub jid: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub push_name: Option<String>,
    pub is_group: bool,
    pub unread: u32,
    #[serde(skip_serializing_if = "is_false")]
    pub pinned: bool,
    #[serde(skip_serializing_if = "is_false")]
    pub archived: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub muted_until_ms: Option<i64>,
    #[serde(skip_serializing_if = "is_false")]
    pub marked_unread: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub picture_id: Option<String>,
    /// The time of the newest row (0 for an empty chat); the list is sorted by it, newest first.
    pub last_ms: i64,
    /// The newest row: a stored message or a send waiting in the outbox.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last: Option<MessageRow>,
    /// Sends of this chat waiting in the outbox.
    pub waiting: u32,
}

/// Where a thread page ended: pass it back as `before` for the next (older) page.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ThreadCursor {
    pub timestamp_ms: i64,
    pub id: String,
}

/// A page of a thread, newest first.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ThreadPage {
    pub items: Vec<MessageRow>,
    /// The cursor of the next stored page; `None` when the store has nothing older.
    pub next: Option<ThreadCursor>,
    /// The gateway has nothing older than what is stored.
    pub reached_start: bool,
    /// A resync of this chat is still filling a hole.
    pub syncing: bool,
}

/// What [`super::Store::apply_events`] did.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct AppliedEvents {
    pub last_seq: Option<u64>,
    /// Events applied (stale ones are skipped).
    pub applied: u32,
    /// A `resync` came: refresh the chat list ([`super::Store::store_chats`]).
    pub resync: bool,
    /// Chats whose rows changed, in first-change order.
    pub chats: Vec<String>,
    /// Outbox sends an echo confirmed.
    pub reconciled: Vec<String>,
}

/// What [`super::Store::store_chats`] did.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct StoredChats {
    pub chats: u32,
    /// The first page of every chat that missed messages.
    pub fetch: Vec<FetchPage>,
}

/// What [`super::Store::store_page`] did.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct StoredPage {
    pub stored: u32,
    /// Messages the store did not have.
    pub new: u32,
    /// The next page of the gap this page belongs to; `None` when it closed (or the page was older history).
    pub next: Option<FetchPage>,
    pub reached_start: bool,
    /// Outbox sends this page confirmed.
    pub reconciled: Vec<String>,
}
