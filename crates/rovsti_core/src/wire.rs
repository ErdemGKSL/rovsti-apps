//! Versioned JSON shapes exchanged between Kotlin and Rust (AD-4, AD-5, AD-10).
//!
//! Rules every type here follows:
//!
//! - snake_case field names, `v: 1` version marker;
//! - unknown fields are ignored (forward compatible);
//! - every enum has a `#[serde(other)] Unknown` variant, so an unrecognised
//!   value never drops a whole snapshot;
//! - a missing `keyguard_locked` means locked (AD-3);
//! - `connect_time_ms == 0` means "not connected";
//! - `normalized` is digits with an optional leading `+`.
//!
//! This crate does not depend on `serde_json`; callers parse with
//! `serde_json::from_str` and the types below.
//!
//! # Golden fixtures
//!
//! `crates/rovsti_core/tests/fixtures/*.json` are the shared goldens. The Rust
//! side reads them in `tests/wire_golden.rs`. The Kotlin JVM unit test that
//! must read the same files (story 3.2) lives at
//! `apps/phone/platforms/android/app/src/test/java/dev/rovsti/bridge/WireFixturesTest.kt`
//! and finds them by walking up from the Gradle project dir to
//! `crates/rovsti_core/tests/fixtures` (see the fixtures `README.md`). Kotlin builds its payloads with `org.json.JSONObject`, so the
//! test compares parsed objects, not raw text.

use serde::{Deserialize, Serialize};

/// The only wire version this build understands.
pub const WIRE_VERSION: u32 = 1;

/// Per-call capability bits (`WireCall::caps`, AD-24). Kotlin mirrors them in
/// `WireJson.kt`; the golden tests keep both sides honest.
pub mod caps {
    /// The call can be put on hold now (`CAPABILITY_HOLD`).
    pub const HOLD: u32 = 1;
    /// The call may be held at all (`CAPABILITY_SUPPORT_HOLD`).
    pub const SUPPORT_HOLD: u32 = 2;
    /// Can merge into a conference (`CAPABILITY_MERGE_CONFERENCE`).
    pub const MERGE: u32 = 4;
    /// Can swap with another call (`CAPABILITY_SWAP_CONFERENCE`).
    pub const SWAP: u32 = 8;
    /// Conference participants can be managed (`CAPABILITY_MANAGE_CONFERENCE`).
    pub const MANAGE: u32 = 16;
    /// A child can leave the conference (`CAPABILITY_SEPARATE_FROM_CONFERENCE`).
    pub const SPLIT: u32 = 32;
    /// A child can be dropped (`CAPABILITY_DISCONNECT_FROM_CONFERENCE`).
    pub const DISCONNECT_PARTICIPANT: u32 = 64;
    /// The ringing call can be declined with a text (`CAPABILITY_RESPOND_VIA_TEXT`).
    pub const RESPOND_VIA_TEXT: u32 = 128;
    /// The microphone can be muted (`CAPABILITY_MUTE`).
    pub const MUTE: u32 = 256;
    /// A participant can be added (`CAPABILITY_ADD_PARTICIPANT`).
    pub const ADD_PARTICIPANT: u32 = 512;

    /// Every bit above, for overlap checks.
    pub const ALL: [u32; 10] = [
        HOLD,
        SUPPORT_HOLD,
        MERGE,
        SWAP,
        MANAGE,
        SPLIT,
        DISCONNECT_PARTICIPANT,
        RESPOND_VIA_TEXT,
        MUTE,
        ADD_PARTICIPANT,
    ];
}

/// Per-call property bits (`WireCall::props`, AD-24).
pub mod props {
    /// The call is a conference (`PROPERTY_CONFERENCE`).
    pub const CONFERENCE: u32 = 1;
    /// High-definition audio (`PROPERTY_HIGH_DEF_AUDIO`).
    pub const HD: u32 = 2;
    /// Carried over Wi-Fi (`PROPERTY_WIFI`).
    pub const WIFI: u32 = 4;
    /// An emergency call.
    pub const EMERGENCY: u32 = 8;

    /// Every bit above, for overlap checks.
    pub const ALL: [u32; 4] = [CONFERENCE, HD, WIFI, EMERGENCY];
}

/// Call-log quality feature bits (`CallLogEntry::features`, AD-31).
pub mod features {
    /// `FEATURES_HD_CALL`.
    pub const HD: u32 = 1;
    /// `FEATURES_WIFI`.
    pub const WIFI: u32 = 2;
    /// `FEATURES_VOLTE`.
    pub const VOLTE: u32 = 4;
    /// `FEATURES_VIDEO`.
    pub const VIDEO: u32 = 8;

    /// Every bit above, for overlap checks.
    pub const ALL: [u32; 4] = [HD, WIFI, VOLTE, VIDEO];
}

fn default_version() -> u32 {
    WIRE_VERSION
}

/// AD-3: a payload without `keyguard_locked` counts as locked.
fn default_true() -> bool {
    true
}

/// Kotlin may send `null` for a field Rust models as a plain value: read it
/// as the field's default instead of failing the whole payload.
pub(crate) fn null_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de> + Default,
{
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

/// `null` keyguard state still means locked (AD-3).
fn null_true<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<bool, D::Error> {
    Ok(Option::<bool>::deserialize(deserializer)?.unwrap_or(true))
}

fn null_version<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<u32, D::Error> {
    Ok(Option::<u32>::deserialize(deserializer)?.unwrap_or(WIRE_VERSION))
}

fn is_zero(n: &i64) -> bool {
    *n == 0
}

fn is_false(b: &bool) -> bool {
    !*b
}

fn is_zero_u32(n: &u32) -> bool {
    *n == 0
}

/// Telecom call state as mapped by Kotlin.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WireCallState {
    Dialing,
    Ringing,
    Active,
    Holding,
    Disconnected,
    #[default]
    #[serde(other)]
    Unknown,
}

/// Where call audio currently goes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AudioRoute {
    Earpiece,
    Speaker,
    Bluetooth,
    WiredHeadset,
    #[default]
    #[serde(other)]
    Unknown,
}

/// Why a call ended, as classified by Kotlin from `DisconnectCause`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DisconnectCause {
    /// The user ended it.
    Local,
    /// The other side ended it.
    Remote,
    Missed,
    Rejected,
    Canceled,
    Busy,
    OutOfService,
    Unreachable,
    Error,
    #[default]
    #[serde(other)]
    Unknown,
}

/// The kind of one audio endpoint (`CallEndpoint.getEndpointType()`, AD-23).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EndpointType {
    Earpiece,
    Bluetooth,
    WiredHeadset,
    Speaker,
    /// Never sent by Kotlin (it omits `TYPE_STREAMING`); ignored if it arrives.
    Streaming,
    /// A type this build does not know: it renders as an `unknown` row.
    #[default]
    #[serde(other)]
    Unknown,
}

impl EndpointType {
    /// The v0.1 route of this endpoint type.
    pub fn route(self) -> AudioRoute {
        match self {
            Self::Earpiece => AudioRoute::Earpiece,
            Self::Bluetooth => AudioRoute::Bluetooth,
            Self::WiredHeadset => AudioRoute::WiredHeadset,
            Self::Speaker => AudioRoute::Speaker,
            Self::Streaming | Self::Unknown => AudioRoute::Unknown,
        }
    }
}

/// Extra knowledge Kotlin has about an endpoint (best effort, AD-23).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EndpointHint {
    HearingAid,
    #[default]
    #[serde(other)]
    Unknown,
}

/// One audio output of the phone. `id` is a session handle: never persisted,
/// never compared across a reconnect.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Endpoint {
    pub id: String,
    #[serde(rename = "type", default, deserialize_with = "null_default")]
    pub kind: EndpointType,
    /// Sent for Bluetooth, wired and unknown endpoints; Rust names the rest.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_false"
    )]
    pub selected: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hint: Option<EndpointHint>,
}

/// Audio section of a [`CallSnapshot`].
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AudioState {
    #[serde(default, deserialize_with = "null_default")]
    pub muted: bool,
    #[serde(default, deserialize_with = "null_default")]
    pub route: AudioRoute,
    #[serde(default, deserialize_with = "null_default")]
    pub routes: Vec<AudioRoute>,
    /// The id-addressed endpoint list (AD-23); empty from a v0.1 sender.
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "Vec::is_empty"
    )]
    pub endpoints: Vec<Endpoint>,
}

impl AudioState {
    /// The route the endpoints say is in use: the type of the selected one.
    pub fn route_from_endpoints(&self) -> Option<AudioRoute> {
        self.endpoints
            .iter()
            .find(|e| e.selected)
            .map(|e| e.kind.route())
    }

    /// The distinct endpoint types, in first-seen order.
    pub fn routes_from_endpoints(&self) -> Vec<AudioRoute> {
        let mut out: Vec<AudioRoute> = Vec::with_capacity(4);
        for e in &self.endpoints {
            let route = e.kind.route();
            if !out.contains(&route) {
                out.push(route);
            }
        }
        out
    }

    /// With endpoints present, `route` and `routes` must be derived from them
    /// (AD-23). Without endpoints (a v0.1 sender) there is nothing to check.
    pub fn is_consistent(&self) -> bool {
        if self.endpoints.is_empty() {
            return true;
        }
        let route_ok = match self.route_from_endpoints() {
            Some(route) => route == self.route,
            None => true,
        };
        route_ok && self.routes_from_endpoints() == self.routes
    }
}

/// An operation the registry is running (`pending`, AD-25).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PendingOp {
    Answer,
    Swap,
    Merge,
    #[default]
    #[serde(other)]
    Unknown,
}

/// How the ringer was silenced (`silenced_by`, AD-29).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SilencedBy {
    User,
    Volume,
    Flip,
    #[default]
    #[serde(other)]
    Unknown,
}

/// One call-capable account offered while a call waits in
/// `selecting_account` (AD-26).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AccountRef {
    pub key: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub label: String,
}

/// One call inside a [`CallSnapshot`].
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct WireCall {
    pub id: i32,
    #[serde(default, deserialize_with = "null_default")]
    pub state: WireCallState,
    #[serde(default, deserialize_with = "null_default")]
    pub number_raw: String,
    #[serde(default, deserialize_with = "null_default")]
    pub normalized: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub contact_id: Option<i64>,
    /// Milliseconds since the epoch; `0` means not connected.
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_zero"
    )]
    pub connect_time_ms: i64,
    /// Milliseconds since the epoch when the call was placed or rang; `0` when unknown.
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_zero"
    )]
    pub started_ms: i64,
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_false"
    )]
    pub can_hold: bool,
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_false"
    )]
    pub is_incoming: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub disconnect_cause: Option<DisconnectCause>,
    /// The conference this call belongs to (AD-24).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_id: Option<i32>,
    /// Conference participants; empty when the carrier hides them.
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "Vec::is_empty"
    )]
    pub children: Vec<i32>,
    /// [`caps`] bits.
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_zero_u32"
    )]
    pub caps: u32,
    /// [`props`] bits.
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_zero_u32"
    )]
    pub props: u32,
    /// Opaque `"{component}#{handle id}"` (AD-26).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub account_key: Option<String>,
    /// `DISPLAY_NAME_ALTERNATIVE` of the contact (AD-30).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub display_name_alt: Option<String>,
    /// When the call went to Holding (epoch ms).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hold_since_ms: Option<i64>,
}

impl WireCall {
    pub fn has_cap(&self, bit: u32) -> bool {
        self.caps & bit != 0
    }

    pub fn has_prop(&self, bit: u32) -> bool {
        self.props & bit != 0
    }

    pub fn is_connected(&self) -> bool {
        self.connect_time_ms > 0
    }
}

/// The whole call state, pushed by Kotlin on every change (kind `calls`).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallSnapshot {
    #[serde(default = "default_version", deserialize_with = "null_version")]
    pub v: u32,
    /// Monotonic per process; the reducer ignores lower values.
    #[serde(default, deserialize_with = "null_default")]
    pub seq: u64,
    #[serde(default, deserialize_with = "null_default")]
    pub now_ms: i64,
    #[serde(default = "default_true", deserialize_with = "null_true")]
    pub keyguard_locked: bool,
    #[serde(default, deserialize_with = "null_default")]
    pub audio: AudioState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub foreground_call_id: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub waiting_call_id: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub background_call_id: Option<i32>,
    /// A call that just ended while another is live (AD-24).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ended_call_id: Option<i32>,
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_false"
    )]
    pub can_add_call: bool,
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_false"
    )]
    pub can_merge: bool,
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_false"
    )]
    pub can_swap: bool,
    /// The operation the registry is running right now.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pending: Option<PendingOp>,
    /// An operation that just failed (present for one rebuild).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pending_failed: Option<PendingOp>,
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_false"
    )]
    pub ringer_silenced: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub silenced_by: Option<SilencedBy>,
    /// A call waits for the user to choose an account (AD-26).
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_false"
    )]
    pub selecting_account: bool,
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "Vec::is_empty"
    )]
    pub accounts: Vec<AccountRef>,
    #[serde(default, deserialize_with = "null_default")]
    pub calls: Vec<WireCall>,
}

/// A snapshot from a newer wire version than this build knows.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
#[error("snapshot wire version {0} is newer than {WIRE_VERSION}")]
pub struct NewerVersion(pub u32);

impl CallSnapshot {
    /// Rejects a snapshot whose `v` is higher than [`WIRE_VERSION`], so the
    /// caller keeps showing the last good one (a changed field bumps `v`).
    pub fn accept(self) -> Result<Self, NewerVersion> {
        if self.v > WIRE_VERSION {
            Err(NewerVersion(self.v))
        } else {
            Ok(self)
        }
    }
}

impl Default for CallSnapshot {
    /// The empty snapshot: no calls, keyguard assumed locked.
    fn default() -> Self {
        Self {
            v: WIRE_VERSION,
            seq: 0,
            now_ms: 0,
            keyguard_locked: true,
            audio: AudioState::default(),
            foreground_call_id: None,
            waiting_call_id: None,
            background_call_id: None,
            ended_call_id: None,
            can_add_call: false,
            can_merge: false,
            can_swap: false,
            pending: None,
            pending_failed: None,
            ringer_silenced: false,
            silenced_by: None,
            selecting_account: false,
            accounts: Vec::new(),
            calls: Vec::new(),
        }
    }
}

/// Call log row type as stored by Android (`CallLog.Calls.TYPE`).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CallLogKind {
    Incoming,
    Outgoing,
    Missed,
    Rejected,
    Blocked,
    Voicemail,
    #[default]
    #[serde(other)]
    Unknown,
}

/// Why a call was blocked (`CallLog.Calls.BLOCK_REASON`).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BlockReason {
    /// Blocked by the owner's blocklist.
    User,
    /// Blocked by the system or the carrier.
    System,
    #[default]
    #[serde(other)]
    Unknown,
}

/// One row pulled from the system call log.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallLogEntry {
    /// Absent in restore JSON: the provider assigns a new id.
    #[serde(default)]
    pub id: i64,
    #[serde(default, deserialize_with = "null_default")]
    pub number_raw: String,
    #[serde(default, deserialize_with = "null_default")]
    pub normalized: String,
    #[serde(default, deserialize_with = "null_default")]
    pub kind: CallLogKind,
    pub date_ms: i64,
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_zero"
    )]
    pub duration_s: i64,
    /// `CallLog.Calls.CACHED_NAME`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cached_name: Option<String>,
    /// `CallLog.Calls.CACHED_NUMBER_LABEL`, e.g. "Mobile".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub number_label: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub contact_id: Option<i64>,
    /// Opaque account key of the SIM the call used (AD-26).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub account_key: Option<String>,
    /// Only for `kind == blocked` (AD-27).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub block_reason: Option<BlockReason>,
    /// [`features`] bits; read by the details view only.
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_zero_u32"
    )]
    pub features: u32,
}

/// JSON for restoring deleted call-log rows (undo):
/// `{"entries":[{number_raw,kind,date_ms,duration_s,account_key?}]}`.
pub fn call_log_restore_json(entries: &[CallLogEntry]) -> String {
    #[derive(Serialize)]
    struct Row<'a> {
        number_raw: &'a str,
        kind: CallLogKind,
        date_ms: i64,
        duration_s: i64,
        #[serde(skip_serializing_if = "Option::is_none")]
        account_key: Option<&'a str>,
    }
    #[derive(Serialize)]
    struct Out<'a> {
        entries: Vec<Row<'a>>,
    }
    let out = Out {
        entries: entries
            .iter()
            .map(|e| Row {
                number_raw: &e.number_raw,
                kind: e.kind,
                date_ms: e.date_ms,
                duration_s: e.duration_s,
                account_key: e.account_key.as_deref(),
            })
            .collect(),
    };
    serde_json::to_string(&out).unwrap_or_else(|_| r#"{"entries":[]}"#.to_owned())
}

/// Data of a call-log write (delete or restore): how many rows changed.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct WriteCount {
    #[serde(default, deserialize_with = "null_default")]
    pub count: u32,
}

/// Why a call-log write failed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum WriteError {
    /// The write permission is missing.
    Permission,
    /// The system refused (not the default dialer).
    Refused,
    Other(String),
}

/// Reads the envelope a call-log write returns.
pub fn parse_write_count(envelope: &str) -> Result<u32, WriteError> {
    let parsed: Envelope<WriteCount> =
        serde_json::from_str(envelope).map_err(|e| WriteError::Other(e.to_string()))?;
    parsed.into_result().map(|d| d.count).map_err(|e| match e {
        WireError::Permission => WriteError::Permission,
        WireError::Refused => WriteError::Refused,
        other => WriteError::Other(other.to_string()),
    })
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallLogPayload {
    #[serde(default, deserialize_with = "null_default")]
    pub entries: Vec<CallLogEntry>,
}

/// One number of a contact.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct WireNumber {
    pub raw: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    /// The contact's default (super-primary) number.
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_false"
    )]
    pub default: bool,
}

/// A contact as pulled from `ContactsContract`.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct WireContact {
    pub id: i64,
    #[serde(default, deserialize_with = "null_default")]
    pub display_name: String,
    #[serde(default, deserialize_with = "null_default")]
    pub numbers: Vec<WireNumber>,
    #[serde(
        default,
        deserialize_with = "null_default",
        skip_serializing_if = "is_false"
    )]
    pub starred: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub photo_uri: Option<String>,
    /// `LOOKUP_KEY`: survives raw-contact merges, unlike `id` (AD-32).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lookup_key: Option<String>,
    /// `DISPLAY_NAME_ALTERNATIVE`, e.g. "Yilmaz, Ayse".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub display_name_alt: Option<String>,
    /// `SORT_KEY_PRIMARY`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sort_key: Option<String>,
    /// `SORT_KEY_ALTERNATIVE`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sort_key_alt: Option<String>,
    /// The first email, shown when the contact has no number.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
    /// `CONTACT_LAST_UPDATED_TIMESTAMP` in epoch milliseconds.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub updated_ms: Option<i64>,
}

impl From<&WireContact> for crate::Contact {
    /// Numbers that [`crate::PhoneNumber`] cannot parse (USSD codes, service
    /// names) are dropped; use the wire form when raw strings matter.
    fn from(w: &WireContact) -> Self {
        Self {
            id: crate::ContactId(w.id),
            display_name: w.display_name.clone(),
            numbers: w
                .numbers
                .iter()
                .filter_map(|n| crate::PhoneNumber::parse(&n.raw).ok())
                .collect(),
            starred: w.starred,
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ContactsPayload {
    #[serde(default, deserialize_with = "null_default")]
    pub contacts: Vec<WireContact>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FavoritesPayload {
    #[serde(default, deserialize_with = "null_default")]
    pub contacts: Vec<WireContact>,
}

/// Failure reported by a provider pull.
#[derive(
    Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize, thiserror::Error,
)]
#[serde(rename_all = "snake_case")]
pub enum WireError {
    /// The runtime permission is missing (`SecurityException` in Kotlin).
    #[error("permission denied")]
    Permission,
    /// The provider or service is not there (no SIM, no voicemail app).
    #[error("unavailable")]
    Unavailable,
    /// The system refused the request (e.g. not the default dialer).
    #[error("refused")]
    Refused,
    #[error("not found")]
    NotFound,
    #[default]
    #[serde(other)]
    #[error("provider error")]
    Unknown,
}

/// `{v, ok, error?, data?}` — every provider pull returns this and never throws.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Envelope<T> {
    #[serde(default = "default_version", deserialize_with = "null_version")]
    pub v: u32,
    pub ok: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<WireError>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data: Option<T>,
}

impl<T> Envelope<T> {
    /// Converts to a `Result`. `ok:true` without `data` is reported as
    /// [`WireError::Unknown`] because a successful pull must carry data.
    pub fn into_result(self) -> Result<T, WireError> {
        match (self.ok, self.data) {
            (true, Some(data)) => Ok(data),
            (true, None) => Err(WireError::Unknown),
            (false, _) => Err(self.error.unwrap_or_default()),
        }
    }
}

/// Result of opening a system settings page (story 14.4).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct OpenSystemData {
    /// App info opened instead because the page has no activity on this phone.
    #[serde(default, deserialize_with = "null_default")]
    pub fallback: bool,
}

/// One call-capable SIM or account (AD-26).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct PhoneAccountInfo {
    /// Opaque `"{component}#{handle id}"`; never parsed (AD-26).
    pub key: String,
    #[serde(default, deserialize_with = "null_default")]
    pub label: String,
    /// Physical slot, when the account is a SIM.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub slot_index: Option<u8>,
    #[serde(default, deserialize_with = "null_default")]
    pub color_argb: i32,
    /// A SIM-backed account (as opposed to a VoIP one).
    #[serde(default, deserialize_with = "null_default")]
    pub sim: bool,
    #[serde(default = "default_true", deserialize_with = "null_true")]
    pub enabled: bool,
}

/// `phone_accounts` provider payload.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct PhoneAccounts {
    #[serde(default, deserialize_with = "null_default")]
    pub accounts: Vec<PhoneAccountInfo>,
    /// The system default outgoing account.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub default_key: Option<String>,
    /// The system is set to "ask every time".
    #[serde(default, deserialize_with = "null_default")]
    pub ask: bool,
}

/// One row of the system blocklist.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct BlockedNumber {
    pub id: i64,
    #[serde(default, deserialize_with = "null_default")]
    pub number_raw: String,
    #[serde(default, deserialize_with = "null_default")]
    pub normalized: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct BlocklistPayload {
    #[serde(default, deserialize_with = "null_default")]
    pub numbers: Vec<BlockedNumber>,
}

/// Name order setting (AD-32).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NameFormat {
    /// "Yilmaz, Ayse".
    LastFirst,
    /// "Ayse Yilmaz"; also what an unknown value reads as.
    #[default]
    #[serde(other)]
    FirstLast,
}

/// What Rust pushes to Kotlin when a setting Kotlin acts on changes
/// (`syncSettings`, AD-30). Sent as a string; Kotlin keeps only this copy.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SettingsSync {
    #[serde(default = "default_true", deserialize_with = "null_true")]
    pub proximity: bool,
    #[serde(default, deserialize_with = "null_default")]
    pub flip_to_silence: bool,
    #[serde(default, deserialize_with = "null_default")]
    pub vibrate_connect: bool,
    #[serde(default, deserialize_with = "null_default")]
    pub vibrate_end: bool,
    #[serde(default, deserialize_with = "null_default")]
    pub has_replies: bool,
    /// `None` follows the system dial-pad tone setting.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub keypad_tones: Option<bool>,
    #[serde(default, deserialize_with = "null_default")]
    pub haptics: HapticsLevel,
    #[serde(default, deserialize_with = "null_default")]
    pub name_format: NameFormat,
}

impl Default for SettingsSync {
    /// The v0.1 behaviour.
    fn default() -> Self {
        Self {
            proximity: true,
            flip_to_silence: false,
            vibrate_connect: false,
            vibrate_end: false,
            has_replies: false,
            keypad_tones: None,
            haptics: HapticsLevel::Normal,
            name_format: NameFormat::FirstLast,
        }
    }
}

/// Haptics strength setting.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HapticsLevel {
    Off,
    Light,
    #[default]
    #[serde(other)]
    Normal,
}

/// `voicemail_info` provider payload (AD-33).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct VoicemailInfo {
    /// A voicemail number is configured for the default account.
    #[serde(default, deserialize_with = "null_default")]
    pub available: bool,
    /// Unheard messages when the carrier reports it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub count: Option<u32>,
    /// Account the numbers below belong to.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub account_key: Option<String>,
}

/// `emergency_numbers` provider payload: numbers the telephony stack treats
/// as emergency for the current country. `112` and `911` are always added by
/// [`crate::dial::is_emergency`].
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct EmergencyList {
    #[serde(default, deserialize_with = "null_default")]
    pub numbers: Vec<String>,
}

/// Which document operation a `document_result` answers.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DocumentOp {
    ExportBackup,
    ImportBackup,
    ExportCalls,
    /// Rovsti Contacts: the `.vcf` was saved.
    ExportContacts,
    /// Rovsti Contacts: a `.vcf` was picked and read.
    ImportContacts,
    /// Rovsti Messages: an encrypted archive was saved from a file.
    ExportMessages,
    /// Rovsti Messages: an archive was picked and copied to `path`.
    ImportMessages,
    /// Rovsti Contacts: a contact photo was picked and copied to `path`.
    ContactPhoto,
    /// Rovsti Contacts: a picture to scan for a QR code was picked and copied
    /// to `path`.
    ScanImage,
    /// Rovsti Messages: a file to attach was picked and copied to `path`
    /// (its type is in `mime`).
    Attachment,
    /// Rovsti Stops: a GTFS `.zip` was picked and copied to `path`.
    ImportGtfs,
    #[default]
    #[serde(other)]
    Unknown,
}

/// One-shot event: a file picker finished (`document_result`).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct DocumentResult {
    #[serde(default, deserialize_with = "null_default")]
    pub op: DocumentOp,
    #[serde(default, deserialize_with = "null_default")]
    pub ok: bool,
    /// Display name of the chosen document.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// The text read from an import.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    /// An import by file: where Kotlin copied the document (app cache).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    /// An attachment pick: the MIME type the provider reports, when it does.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mime: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<WireError>,
}

/// One-shot event: the ringtone picker finished (`ringtone_result`).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct RingtoneResult {
    #[serde(default, deserialize_with = "null_default")]
    pub ok: bool,
    /// Display title of the chosen tone; `None` for the silent choice.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
}

/// What a system-driven UI request asks Rust to show (`ui_request`).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UiRequestKind {
    /// The user asked to open the dial pad (e.g. a `tel:` intent).
    ShowDialer,
    /// The in-call screen for `call_id`.
    ShowCall,
    /// Open Recents (missed-call notification tap).
    ShowRecents,
    /// The Incoming screen for `call_id` with the reply sheet open (the
    /// notification's Message action, story 18.7).
    ShowReply,
    #[default]
    #[serde(other)]
    Unknown,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct UiRequest {
    #[serde(default, deserialize_with = "null_default")]
    pub kind: UiRequestKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub call_id: Option<i32>,
}

pub type PhoneAccountsResponse = Envelope<PhoneAccounts>;
pub type BlocklistResponse = Envelope<BlocklistPayload>;
pub type VoicemailInfoResponse = Envelope<VoicemailInfo>;
pub type EmergencyListResponse = Envelope<EmergencyList>;

pub type OpenSystemResponse = Envelope<OpenSystemData>;
pub type CallLogResponse = Envelope<CallLogPayload>;
pub type ContactsResponse = Envelope<ContactsPayload>;
pub type FavoritesResponse = Envelope<FavoritesPayload>;

/// What a handoff from another app asks for (the intent extras of AD-10).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HandoffAction {
    WaCall,
    WaChat,
    #[default]
    #[serde(other)]
    Unknown,
}

/// What the user chose on a WhatsApp incoming-call notification.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WaCallAction {
    Answer,
    Decline,
}

/// A WhatsApp action handed from one Rovsti app to another.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Handoff {
    pub action: HandoffAction,
    pub jid: String,
    #[serde(default)]
    pub name: Option<String>,
}

/// Parses a handoff payload; `None` for bad JSON or a JID that is empty or
/// not a `@s.whatsapp.net` user.
pub fn parse_handoff(json: &str) -> Option<Handoff> {
    let handoff: Handoff = serde_json::from_str(json).ok()?;
    let user = handoff.jid.strip_suffix("@s.whatsapp.net")?;
    (!user.is_empty()).then_some(handoff)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handoff_round_trips() {
        let h = Handoff {
            action: HandoffAction::WaCall,
            jid: "905321234567@s.whatsapp.net".into(),
            name: Some("Ayse".into()),
        };
        let json = serde_json::to_string(&h).expect("parses");
        assert!(json.contains(r#""action":"wa_call""#));
        assert_eq!(parse_handoff(&json), Some(h));
        let chat =
            parse_handoff(r#"{"action":"wa_chat","jid":"1@s.whatsapp.net"}"#).expect("parses");
        assert_eq!((chat.action, chat.name), (HandoffAction::WaChat, None));
        let odd =
            parse_handoff(r#"{"action":"teleport","jid":"1@s.whatsapp.net"}"#).expect("parses");
        assert_eq!(odd.action, HandoffAction::Unknown);
    }

    #[test]
    fn handoff_rejects_bad_jids() {
        for jid in ["", "@s.whatsapp.net", "123@g.us", "123", "123@lid"] {
            let json = format!(r#"{{"action":"wa_chat","jid":"{jid}"}}"#);
            assert_eq!(parse_handoff(&json), None, "{jid}");
        }
        assert_eq!(parse_handoff("nope"), None);
    }

    #[test]
    fn null_plain_fields_read_as_their_defaults() {
        let s: CallSnapshot = serde_json::from_str(
            r#"{"v":null,"seq":null,"now_ms":null,"keyguard_locked":null,"audio":null,
                "calls":[{"id":1,"state":null,"number_raw":null,"normalized":null,
                          "connect_time_ms":null,"can_hold":null,"is_incoming":null}]}"#,
        )
        .expect("nulls are tolerated");
        assert_eq!(s.v, WIRE_VERSION);
        assert!(s.keyguard_locked, "null keyguard still means locked");
        assert_eq!(s.calls[0].number_raw, "");
        assert_eq!(s.calls[0].state, WireCallState::Unknown);
        let c: ContactsPayload =
            serde_json::from_str(r#"{"contacts":[{"id":2,"display_name":null,"numbers":null}]}"#)
                .expect("parse");
        assert_eq!(c.contacts[0].display_name, "");
        assert!(c.contacts[0].numbers.is_empty());
    }

    #[test]
    fn missing_keyguard_locked_means_locked() {
        let s: CallSnapshot = serde_json::from_str(r#"{"v":1,"seq":3}"#).expect("parse");
        assert!(s.keyguard_locked);
        assert!(s.calls.is_empty());
    }

    #[test]
    fn explicit_unlocked_is_respected() {
        let s: CallSnapshot = serde_json::from_str(r#"{"keyguard_locked":false}"#).expect("parse");
        assert!(!s.keyguard_locked);
    }

    #[test]
    fn unknown_enum_values_fall_back() {
        let s: CallSnapshot = serde_json::from_str(
            r#"{"audio":{"route":"hdmi","routes":["speaker","hdmi"]},
                "calls":[{"id":1,"state":"conferencing","disconnect_cause":"meteor"}]}"#,
        )
        .expect("parse");
        assert_eq!(s.audio.route, AudioRoute::Unknown);
        assert_eq!(s.audio.routes, [AudioRoute::Speaker, AudioRoute::Unknown]);
        assert_eq!(s.calls[0].state, WireCallState::Unknown);
        assert_eq!(s.calls[0].disconnect_cause, Some(DisconnectCause::Unknown));
    }

    #[test]
    fn unknown_fields_are_ignored() {
        let s: CallSnapshot =
            serde_json::from_str(r#"{"seq":1,"future":{"x":1},"calls":[{"id":2,"extra":true}]}"#)
                .expect("parse");
        assert_eq!(s.calls[0].id, 2);
    }

    #[test]
    fn connect_time_zero_is_not_connected() {
        let c: WireCall = serde_json::from_str(r#"{"id":1}"#).expect("parse");
        assert!(!c.is_connected());
        let c: WireCall = serde_json::from_str(r#"{"id":1,"connect_time_ms":5}"#).expect("parse");
        assert!(c.is_connected());
    }

    #[test]
    fn permission_error_maps() {
        let e: CallLogResponse =
            serde_json::from_str(r#"{"v":1,"ok":false,"error":"permission"}"#).expect("parse");
        assert_eq!(e.into_result(), Err(WireError::Permission));
    }

    #[test]
    fn unknown_error_maps_to_unknown() {
        let e: ContactsResponse =
            serde_json::from_str(r#"{"ok":false,"error":"kaboom"}"#).expect("parse");
        assert_eq!(e.into_result(), Err(WireError::Unknown));
        let e: ContactsResponse = serde_json::from_str(r#"{"ok":false}"#).expect("parse");
        assert_eq!(e.into_result(), Err(WireError::Unknown));
    }

    #[test]
    fn ok_without_data_is_an_error() {
        let e: FavoritesResponse = serde_json::from_str(r#"{"ok":true}"#).expect("parse");
        assert_eq!(e.into_result(), Err(WireError::Unknown));
    }

    #[test]
    fn wire_contact_converts_and_drops_unparsable_numbers() {
        let w: WireContact = serde_json::from_str(
            r#"{"id":7,"display_name":"Ada","starred":true,
                "numbers":[{"raw":"+90 555 111 22 33"},{"raw":"*123#"}]}"#,
        )
        .expect("parse");
        let c = crate::Contact::from(&w);
        assert_eq!(c.id, crate::ContactId(7));
        assert_eq!(c.numbers.len(), 1);
        assert!(c.starred);
    }

    #[test]
    fn old_wire_contact_json_has_no_updated_ms() {
        let w: WireContact = serde_json::from_str(r#"{"id":1}"#).expect("parse");
        assert_eq!(w.updated_ms, None);
        assert!(
            !serde_json::to_string(&w)
                .expect("json")
                .contains("updated_ms")
        );
        let w: WireContact = serde_json::from_str(r#"{"id":1,"updated_ms":5}"#).expect("parse");
        assert_eq!(w.updated_ms, Some(5));
    }

    #[test]
    fn bit_constants_are_pinned() {
        assert_eq!(
            caps::ALL,
            [1, 2, 4, 8, 16, 32, 64, 128, 256, 512],
            "caps are wire values"
        );
        assert_eq!(
            (
                caps::HOLD,
                caps::SUPPORT_HOLD,
                caps::MERGE,
                caps::SWAP,
                caps::MANAGE
            ),
            (1, 2, 4, 8, 16)
        );
        assert_eq!(
            (
                caps::SPLIT,
                caps::DISCONNECT_PARTICIPANT,
                caps::RESPOND_VIA_TEXT,
                caps::MUTE,
                caps::ADD_PARTICIPANT
            ),
            (32, 64, 128, 256, 512)
        );
        assert_eq!(
            (props::CONFERENCE, props::HD, props::WIFI, props::EMERGENCY),
            (1, 2, 4, 8)
        );
        assert_eq!(
            (
                features::HD,
                features::WIFI,
                features::VOLTE,
                features::VIDEO
            ),
            (1, 2, 4, 8)
        );
    }

    #[test]
    fn no_two_bits_of_one_mask_overlap() {
        fn disjoint(bits: &[u32]) {
            let mut seen = 0_u32;
            for &bit in bits {
                assert_eq!(bit.count_ones(), 1, "{bit} is not a single bit");
                assert_eq!(seen & bit, 0, "{bit} overlaps");
                seen |= bit;
            }
        }
        disjoint(&caps::ALL);
        disjoint(&props::ALL);
        disjoint(&features::ALL);
    }

    #[test]
    fn a_v01_snapshot_has_no_v02_fields() {
        let s: CallSnapshot = serde_json::from_str(
            r#"{"v":1,"seq":4,"audio":{"muted":false,"route":"speaker","routes":["earpiece","speaker"]},
                "foreground_call_id":1,"calls":[{"id":1,"state":"active","can_hold":true}]}"#,
        )
        .expect("parse");
        assert!(s.audio.endpoints.is_empty());
        assert!(s.audio.is_consistent(), "nothing to derive from");
        assert_eq!(s.background_call_id, None);
        assert!(!s.can_add_call && !s.can_merge && !s.can_swap);
        assert_eq!(s.pending, None);
        let call = &s.calls[0];
        assert_eq!((call.caps, call.props), (0, 0));
        assert!(call.children.is_empty() && call.parent_id.is_none());
    }

    #[test]
    fn endpoints_parse_and_unknown_types_do_not_drop_the_snapshot() {
        let s: CallSnapshot = serde_json::from_str(
            r#"{"audio":{"route":"bluetooth","routes":["earpiece","bluetooth","unknown"],
                "endpoints":[
                  {"id":"a","type":"earpiece"},
                  {"id":"b","type":"bluetooth","name":"Buds","selected":true,"hint":"hearing_aid"},
                  {"id":"c","type":"usb_c","name":"Dock","future":1}]}}"#,
        )
        .expect("parse");
        let e = &s.audio.endpoints;
        assert_eq!(e[2].kind, EndpointType::Unknown);
        assert_eq!(e[1].hint, Some(EndpointHint::HearingAid));
        assert_eq!(s.audio.route_from_endpoints(), Some(AudioRoute::Bluetooth));
        assert!(s.audio.is_consistent());
        let mut off = s.audio.clone();
        off.route = AudioRoute::Speaker;
        assert!(!off.is_consistent());
    }

    #[test]
    fn a_newer_wire_version_is_rejected() {
        let s: CallSnapshot = serde_json::from_str(r#"{"v":2,"seq":9}"#).expect("parse");
        assert_eq!(s.accept(), Err(NewerVersion(2)));
        let ok: CallSnapshot = serde_json::from_str(r#"{"v":1}"#).expect("parse");
        assert!(ok.accept().is_ok());
    }

    #[test]
    fn attachment_result_carries_its_mime() {
        let r: DocumentResult = serde_json::from_str(
            r#"{"op":"attachment","ok":true,"name":"a.jpg","path":"/c/x.bin","mime":"image/jpeg"}"#,
        )
        .expect("parse");
        assert_eq!(r.op, DocumentOp::Attachment);
        assert_eq!(r.mime.as_deref(), Some("image/jpeg"));
        assert_eq!(r.path.as_deref(), Some("/c/x.bin"));
    }

    #[test]
    fn scan_image_op_round_trips() {
        let r: DocumentResult = serde_json::from_str(
            r#"{"op":"scan_image","ok":true,"path":"/c/s.bin","mime":"image/png"}"#,
        )
        .expect("parse");
        assert_eq!(r.op, DocumentOp::ScanImage);
        assert_eq!(
            serde_json::to_string(&DocumentOp::ScanImage).expect("serialize"),
            r#""scan_image""#
        );
    }

    #[test]
    fn import_gtfs_op_round_trips() {
        let g: DocumentResult =
            serde_json::from_str(r#"{"op":"import_gtfs","ok":true,"path":"/c/g.bin"}"#)
                .expect("parse");
        assert_eq!(g.op, DocumentOp::ImportGtfs);
        assert_eq!(
            serde_json::to_string(&DocumentOp::ImportGtfs).expect("serialize"),
            r#""import_gtfs""#
        );
    }

    #[test]
    fn old_document_result_has_no_mime() {
        let r: DocumentResult =
            serde_json::from_str(r#"{"op":"import_messages","ok":true,"path":"/c/m.rmb"}"#)
                .expect("parse");
        assert_eq!(r.op, DocumentOp::ImportMessages);
        assert_eq!(r.mime, None);
        let json = serde_json::to_string(&r).expect("serialize");
        assert!(!json.contains("mime"));
    }

    #[test]
    fn unknown_document_op_stays_unknown() {
        let r: DocumentResult =
            serde_json::from_str(r#"{"op":"teleport","ok":false}"#).expect("parse");
        assert_eq!(r.op, DocumentOp::Unknown);
    }

    #[test]
    fn restore_json_round_trips_through_the_entry_parser() {
        let entries = [
            CallLogEntry {
                id: 9,
                number_raw: "0555 111 22 33".into(),
                kind: CallLogKind::Missed,
                date_ms: 1_700_000_000_000,
                duration_s: 0,
                account_key: Some("acc".into()),
                ..Default::default()
            },
            CallLogEntry {
                id: 8,
                number_raw: "112".into(),
                kind: CallLogKind::Outgoing,
                date_ms: 5,
                duration_s: 42,
                ..Default::default()
            },
        ];
        let json = call_log_restore_json(&entries);
        let back: CallLogPayload = serde_json::from_str(&json).expect("parse");
        assert_eq!(back.entries.len(), 2);
        for (a, b) in entries.iter().zip(&back.entries) {
            assert_eq!(a.number_raw, b.number_raw);
            assert_eq!(a.kind, b.kind);
            assert_eq!(a.date_ms, b.date_ms);
            assert_eq!(a.duration_s, b.duration_s);
            assert_eq!(a.account_key, b.account_key);
        }
    }

    #[test]
    fn write_count_parses_ok_and_errors() {
        assert_eq!(
            parse_write_count(r#"{"v":1,"ok":true,"data":{"count":3}}"#),
            Ok(3)
        );
        assert_eq!(
            parse_write_count(r#"{"v":1,"ok":false,"error":"permission"}"#),
            Err(WriteError::Permission)
        );
        assert_eq!(
            parse_write_count(r#"{"v":1,"ok":false,"error":"refused"}"#),
            Err(WriteError::Refused)
        );
        assert!(matches!(
            parse_write_count("garbage"),
            Err(WriteError::Other(_))
        ));
        assert!(matches!(
            parse_write_count(r#"{"v":1,"ok":true}"#),
            Err(WriteError::Other(_))
        ));
    }
}
