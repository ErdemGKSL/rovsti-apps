//! What the store answers: chat rows, message rows and the results of its writes.

use rosty_core::wa_actions::MentionSpan;
use rosty_core::wa_identity::Rejected;
use rosty_wa_proto::{
    DeliveryStatus, EditRecord, LinkPreview, Location, MediaKind, MediaMeta, MessageBody, Poll,
    Quoted, Reaction, SharedContact, SystemNote, Unavailable,
};
use serde::{Deserialize, Serialize};

use super::media::MediaState;
use super::merge::ChatRekeyed;
use super::outbox::OutboxState;
use super::plan::FetchPage;

fn is_false(flag: &bool) -> bool {
    !*flag
}

/// What a message is. Stored as its wire name.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
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
    /// A poll (E6-WA R5).
    Poll,
    /// Nothing the gateway could describe (also a deleted message whose content was never seen).
    #[default]
    Unsupported,
}

impl Kind {
    const ALL: [Kind; 10] = [
        Kind::Text,
        Kind::Image,
        Kind::Video,
        Kind::Audio,
        Kind::Document,
        Kind::Sticker,
        Kind::Location,
        Kind::Contact,
        Kind::Poll,
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
            Kind::Poll => "poll",
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
    /// The message reached this account's linked devices as a placeholder only (`kind` stays `unsupported`): a
    /// view-once message someone sent, or a bot's or hosted business's message. Show "open it on your phone".
    #[serde(skip_serializing_if = "Option::is_none")]
    pub unavailable: Option<Unavailable>,
    /// A thumbnail is stored (`Store::media`).
    #[serde(skip_serializing_if = "is_false")]
    pub has_thumbnail: bool,
    /// The cached full media file; `None` when not downloaded or evicted. For an outbox media send: the file to send.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub media_path: Option<String>,
    /// Where the full file is in its download (`Store::media_enqueue`); left out when nothing was ever asked
    /// for, and for an outbox row.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub media_state: Option<MediaState>,
    /// The outbox client id this message was sent with, or still waits under.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
    /// The id the sender arrived under, when it is not `sender` (a LID whose number is known): what an outbound
    /// message key names.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wire_sender: Option<String>,
    /// Why the last attempt of an outbox row failed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// Edits, deletes and reactions of this message that wait in the outbox.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub pending: Vec<PendingOp>,
    /// A view-once message that was opened on this device ([`super::Store::media_viewed`]): its file is gone
    /// and is never fetched again.
    #[serde(skip_serializing_if = "is_false")]
    pub viewed: bool,
    /// A received voice note whose played receipt was queued ([`super::Store::mark_played`]).
    #[serde(skip_serializing_if = "is_false")]
    pub played: bool,
    /// Until when (unix ms) my sent message can be edited (`rosty_core::wa_actions::editable_until`); `None`:
    /// never. Compare with the clock when the menu opens.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub editable_until_ms: Option<i64>,
    /// How often it was forwarded before it reached this chat.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub forwarding_score: Option<u32>,
    /// Show "Forwarded many times" instead of "Forwarded".
    #[serde(skip_serializing_if = "is_false")]
    pub forwarded_many: bool,
    /// Where the text names the people in `mentions` (UTF-16 ranges of each `@<token>`), resolved through every
    /// id the identity layer knows for them.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub mention_spans: Vec<MentionSpan>,
    /// The card of the link in the text; its picture is the message's thumbnail (`has_thumbnail`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub link_preview: Option<LinkPreview>,
    /// A line of the conversation (the disappearing timer changed), not a message.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub system: Option<SystemNote>,
    /// When the message disappears (unix ms). `None` for a message kept in the chat.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<i64>,
    /// A poll with its votes and my choice (E6-WA R5; `kind` is `poll`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub poll: Option<Poll>,
    /// Every card of a contacts message; `contact` is the first (E6-WA R5).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub contacts: Vec<SharedContact>,
    /// Kept in a disappearing chat ("keep in chat"): it does not disappear (E6-WA R5).
    #[serde(skip_serializing_if = "is_false")]
    pub kept: bool,
}

/// One row of the chat list.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ChatRow {
    pub jid: String,
    /// The id the gateway last used for this chat, when it is not `jid`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wire_jid: Option<String>,
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
    /// The chat's disappearing timer in seconds; `None` when off.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ephemeral_s: Option<u32>,
    /// The time of the newest row (0 for an empty chat); the list is sorted by it, newest first.
    pub last_ms: i64,
    /// The newest row: a stored message or a send waiting in the outbox.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last: Option<MessageRow>,
    /// Sends of this chat waiting in the outbox.
    pub waiting: u32,
}

/// What [`super::Store::expire`] removed.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct Expired {
    /// Messages deleted.
    pub removed: u32,
    /// Their media files: delete them.
    pub files: Vec<String>,
    /// The chats that lost messages.
    pub chats: Vec<String>,
    /// When the next stored message disappears; `None`: none will.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_ms: Option<i64>,
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

/// A page of the media of one chat, newest first ([`super::Store::chat_media`]).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct GalleryPage {
    pub items: Vec<MessageRow>,
    /// Older media exists: pass it as `before` for the page that follows this one.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub older: Option<ThreadCursor>,
    /// Newer media exists: pass it as `after` for the page that precedes this one.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub newer: Option<ThreadCursor>,
}

/// Where a page of starred messages ended: pass it back as `before` for the next (older) page.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct StarredCursor {
    pub timestamp_ms: i64,
    pub chat: String,
    pub id: String,
}

/// A page of the starred messages of every chat, newest first.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct StarredPage {
    pub items: Vec<MessageRow>,
    /// The cursor of the next page; `None` when nothing older is starred.
    pub next: Option<StarredCursor>,
}

/// What [`super::Store::apply_events`] did.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct AppliedEvents {
    pub last_seq: Option<u64>,
    /// Events applied (stale ones are skipped).
    pub applied: u32,
    /// Refresh the chat list ([`super::Store::store_chats`]): a `resync` came, a `hello`, `resync` or `state`
    /// named another gateway epoch than the stored one (a gap), history arrived, a merge moved rows or the
    /// account changed.
    pub resync: bool,
    /// Chats whose rows changed, in first-change order.
    pub chats: Vec<String>,
    /// Outbox sends an echo confirmed.
    pub reconciled: Vec<String>,
    /// Key changes the stored rows followed while these events were applied (a merge the gateway announced, or
    /// one left over from before), oldest first.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rekeyed: Vec<ChatRekeyed>,
    /// Pairs the events named that changed nothing (an id that does not read, a weaker source than the one the
    /// LID is held by, a pair that would join the account's own id to someone else), each once: for the log.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rejected: Vec<Rejected>,
    /// An `account_changed` event, or a `state` event that named another own number or a raised account epoch:
    /// another account was linked and the store was emptied
    /// ([`super::Store::note_status`]). What is kept per chat outside the store belongs to the old account too.
    #[serde(skip_serializing_if = "is_false")]
    pub account_changed: bool,
    /// Media files of the old account to delete.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub delete_files: Vec<String>,
}

/// What [`super::Store::store_chats`] did.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct StoredChats {
    pub chats: u32,
    /// The first page of every chat that missed messages.
    pub fetch: Vec<FetchPage>,
    /// Key changes the stored rows followed: the list named a pair the store did not know.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rekeyed: Vec<ChatRekeyed>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rejected: Vec<Rejected>,
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
    /// Key changes the stored rows followed: the page named a pair the store did not know.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rekeyed: Vec<ChatRekeyed>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rejected: Vec<Rejected>,
}
