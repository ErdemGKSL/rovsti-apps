//! The WhatsApp offline store and the per-device sync engine (E6.4s).
//!
//! The inbox and the thread read WhatsApp **only** from this SQLite store; the network only fills it. Platform-free:
//! no clock, no randomness, no network. The caller (Kotlin, through the `wa_sync.*` ops) performs the gateway calls
//! and hands the answers in, with the time.
//!
//! * [`Store`] is one SQLite file per linked instance (`wa-sync-<instance>.db`), so re-linking another instance
//!   never mixes data; the file also remembers its instance and refuses another.
//! * Gateway events are applied in order ([`Store::apply_events`]); every write is an idempotent upsert by gateway
//!   message id. The install keeps its own last event `seq` and, per chat, the newest and oldest stored message.
//! * A gap (the gateway restarted or its ring was trimmed: a `resync` event) starts a resync: refresh the chat list
//!   ([`Store::store_chats`]), then per chat fetch pages newer than the newest stored message, paging with `before=`
//!   until they overlap what is stored ([`Store::store_page`]). Stored history is never dropped. The planning itself
//!   is pure ([`plan`]).
//! * Deleted messages are never erased: a delete marks `deleted_at` / `deleted_by` and keeps text, quote and media.
//!   An edit keeps the previous text as edit history.
//! * The outbox ([`Store::outbox_enqueue`] and friends) keeps what was written offline, in the same database, and
//!   reconciles each send with its echo so nothing doubles.
//! * One person is one chat, whichever id the gateway names them by: a phone-number JID or a LID. Every id is
//!   resolved through the identity map before it is matched ([`Store::identity_resolve`]), a pair learned later
//!   ([`Store::identity_learn`]) merges what was stored under both, and each merge is a logged step the rows
//!   follow ([`Store::apply_pending_merges`], [`ChatRekeyed`]). Pairs come from the `identity_mapped` and
//!   `identity_number_changed` events, from the other form an event, a message or a chat carries next to an id,
//!   from the gateway's list of pairs ([`Store::identity_catch_up`]) and from groups, profiles and the instance
//!   ([`Store::identity_observe`]). A reader that still holds an id of before a merge reads the merged rows
//!   ([`Store::stored_key`]).
//! * Full media is a row of `media` per message ([`media`]): a download queue under the owner's auto-download
//!   rules ([`media::policy`]), a cached file path, and a cap with LRU eviction that spares starred, saved and
//!   recently used files. Evicting forgets the path and keeps the message and its thumbnail.
//! * The store follows the gateway process and the linked account ([`Store::note_status`]): another gateway
//!   epoch is a gap, another account empties the store, and unlinking deletes it ([`Store::wipe`]).

mod apply;
mod authors;
mod backfill;
#[cfg(test)]
mod contact_match_tests;
mod identity;
#[cfg(test)]
mod identity_tests;
#[cfg(test)]
mod ingest_tests;
mod link_state;
#[cfg(test)]
mod link_state_tests;
pub mod media;
mod media_policy;
#[cfg(test)]
mod media_tests;
mod merge;
mod model;
mod outbox;
pub mod plan;
mod query;
mod schema;
mod store;
#[cfg(test)]
mod tests;

pub use identity::{IdentityApplied, IdentityCaughtUp, Observed, ResolvedId};
pub use link_state::{
    HistoryBatch, HistoryProgress, HistoryReport, HistoryState, StatusNote, StatusNoted, Wiped,
    delete_files,
};
pub use media::{
    Conditions, Decision, Evicted, Eviction, Fetch, Fetched, KindUsage, MediaClass, MediaDue,
    MediaEntry, MediaInfo, MediaQueued, MediaSettled, MediaState, MediaStats, Missing, Network,
    Reconciled, Usage,
};
pub use merge::ChatRekeyed;
pub use model::{
    AppliedEvents, ChatRow, Kind, MessageRow, PendingOp, RowState, StarredCursor, StarredPage,
    StoredChats, StoredPage, ThreadCursor, ThreadPage,
};
pub use outbox::{
    Action, BACKOFF_BASE_MS, BACKOFF_MAX_MS, Due, ErrorKind, OutboxItem, OutboxState, Outcome,
    SENDING_LEASE_MS, Settled, backoff_ms,
};
pub use plan::{ChatCursor, FetchPage, Gap, SyncPlan, SyncState};
pub use schema::SCHEMA_VERSION;
pub use store::{Opened, Store, file_name};

/// Why a store call failed.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("database: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("file: {0}")]
    Io(#[from] std::io::Error),
    #[error("stored JSON: {0}")]
    Json(#[from] serde_json::Error),
    /// The file belongs to another linked instance.
    #[error("the store belongs to instance {found:?}, not {wanted:?}")]
    InstanceMismatch { wanted: String, found: String },
    /// The file was written by a newer build.
    #[error("the store has schema version {found}, this build knows up to {supported}")]
    NewerSchema { found: u32, supported: u32 },
    #[error("{0}")]
    NotFound(String),
    #[error("{0}")]
    Invalid(String),
}

pub type Result<T> = std::result::Result<T, Error>;
