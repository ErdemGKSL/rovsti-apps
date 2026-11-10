#![cfg_attr(test, allow(clippy::unwrap_used))]

//! Wire and domain types for the WhatsApp gateway.
//!
//! Everything here is pure: no I/O, no clock and no randomness. Callers pass
//! in random bytes and timestamps, so the gateway, the client and the apps
//! share one tested definition of the protocol.

pub mod api;
pub mod audio;
pub mod call;
pub mod event;
pub mod history;
pub mod key;
pub mod limit;
pub mod log;
pub mod lookup;
pub mod media;
pub mod meta;
pub mod presence;
pub mod push;
pub mod status;
pub mod timeline;
pub mod url;

pub use api::*;
pub use call::{
    Arbitration, CallDirection, CallInput, CallMachine, CallPhase, EndReason, WaCallView,
    WaControl, WaScreen, WaScreenPhase, arbitrate,
};
pub use event::{CallSummary, CallUpdate, EventEnvelope, GatewayEvent};
pub use history::{SnapshotPlan, WaCallHistory, WaCallRecord, plan_snapshot, tracked_end};
pub use key::{ConnectKey, KeyError, KeyHash, KeyId};
pub use log::{EventLog, Since};
pub use lookup::{LookupCache, LookupUsers, MAX_LOOKUP, WaTarget, WaUser, chat_users, wa_target};
pub use media::{MAX_SEND_BYTES, MediaTooLarge, check_size, classify};
pub use meta::{
    ChatAction, ChatActionKind, ChatMeta, DELETED_BY_ME, DeleteForMe, DeliveryStatus, EditMessage,
    EditRecord, KeptContent, Location, MAX_PROFILES, MUTED_FOREVER, MediaMeta, MediaOptions,
    MessageMeta, ProfilesRequest, Quoted, RevokeMessage, RichChat, RichMessage, SharedContact,
    StarMessage, WaProfile,
};
pub use presence::{PresenceBook, presence_label};
pub use push::{Wake, parse_wake};
pub use status::{
    MarkStatusViewed, STATUS_BROADCAST, STATUS_TTL_MS, StatusAuthor, StatusItem, StatusKind,
    StatusList, StatusMedia, StatusPlayer, group_statuses, is_status_chat,
};
pub use timeline::{
    Item, ItemState, Timeline, TypingThrottle, day_breaks, reaction_chips, read_requests, user_jid,
};
pub use url::{GatewayUrl, UrlError};
