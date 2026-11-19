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
pub mod identity;
pub mod key;
pub mod limit;
pub mod log;
pub mod lookup;
pub mod media;
pub mod meta;
pub mod people;
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
pub use identity::{IdentityMapping, IdentityMappings, IdentitySource};
pub use key::{ConnectKey, KeyError, KeyHash, KeyId};
pub use log::{EventLog, Since};
pub use lookup::{LookupCache, LookupUsers, MAX_LOOKUP, WaTarget, WaUser, chat_users, wa_target};
pub use media::{
    MAX_SEND_BYTES, MAX_SEND_THUMBNAIL_BYTES, MediaOptionError, MediaTooLarge, WAVEFORM_LEN,
    WAVEFORM_MAX, check_size, check_thumbnail, check_view_once, check_waveform, classify,
    decode_b64, encode_b64,
};
pub use meta::{
    ChatAction, ChatActionKind, ChatMeta, DELETED_BY_ME, DISAPPEARING_CHOICES, DeleteForMe,
    DeliveryStatus, EditMessage, EditRecord, FREQUENTLY_FORWARDED_SCORE, ForwardMessage,
    KeptContent, LinkPreview, Location, MAX_PROFILES, MUTED_FOREVER, MediaMeta, MediaOptions,
    MessageMeta, MessageReceipts, ProfilesRequest, Quoted, ReceiptMember, RevokeMessage, RichChat,
    RichMessage, SharedContact, StarMessage, SystemNote, Unavailable, WaProfile,
};
pub use people::{
    BundleEnd, BundleError, BundleHeader, BundleWriter, PeopleEntry, PeopleJobError,
    PeopleJobPhase, PeopleJobResult, PeopleJobState, PeopleJobStatus, PeopleRecord, PeopleSyncMode,
    PeopleSyncStart, PeopleWait, PeopleWaitSource, people_hash,
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
