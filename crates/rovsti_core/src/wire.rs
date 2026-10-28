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

fn default_version() -> u32 {
    WIRE_VERSION
}

/// AD-3: a payload without `keyguard_locked` counts as locked.
fn default_true() -> bool {
    true
}

fn is_zero(n: &i64) -> bool {
    *n == 0
}

fn is_false(b: &bool) -> bool {
    !*b
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

/// Audio section of a [`CallSnapshot`].
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AudioState {
    #[serde(default)]
    pub muted: bool,
    #[serde(default)]
    pub route: AudioRoute,
    #[serde(default)]
    pub routes: Vec<AudioRoute>,
}

/// One call inside a [`CallSnapshot`].
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct WireCall {
    pub id: i32,
    #[serde(default)]
    pub state: WireCallState,
    #[serde(default)]
    pub number_raw: String,
    #[serde(default)]
    pub normalized: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub contact_id: Option<i64>,
    /// Milliseconds since the epoch; `0` means not connected.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub connect_time_ms: i64,
    #[serde(default, skip_serializing_if = "is_false")]
    pub can_hold: bool,
    #[serde(default, skip_serializing_if = "is_false")]
    pub is_incoming: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub disconnect_cause: Option<DisconnectCause>,
}

impl WireCall {
    pub fn is_connected(&self) -> bool {
        self.connect_time_ms > 0
    }
}

/// The whole call state, pushed by Kotlin on every change (kind `calls`).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallSnapshot {
    #[serde(default = "default_version")]
    pub v: u32,
    /// Monotonic per process; the reducer ignores lower values.
    #[serde(default)]
    pub seq: u64,
    #[serde(default)]
    pub now_ms: i64,
    #[serde(default = "default_true")]
    pub keyguard_locked: bool,
    #[serde(default)]
    pub audio: AudioState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub foreground_call_id: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub waiting_call_id: Option<i32>,
    #[serde(default)]
    pub calls: Vec<WireCall>,
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

/// One row pulled from the system call log.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallLogEntry {
    pub id: i64,
    #[serde(default)]
    pub number_raw: String,
    #[serde(default)]
    pub normalized: String,
    #[serde(default)]
    pub kind: CallLogKind,
    pub date_ms: i64,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub duration_s: i64,
    /// `CallLog.Calls.CACHED_NAME`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cached_name: Option<String>,
    /// `CallLog.Calls.CACHED_NUMBER_LABEL`, e.g. "Mobile".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub number_label: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub contact_id: Option<i64>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallLogPayload {
    #[serde(default)]
    pub entries: Vec<CallLogEntry>,
}

/// One number of a contact.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct WireNumber {
    pub raw: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    /// The contact's default (super-primary) number.
    #[serde(default, skip_serializing_if = "is_false")]
    pub default: bool,
}

/// A contact as pulled from `ContactsContract`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct WireContact {
    pub id: i64,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub numbers: Vec<WireNumber>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub starred: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub photo_uri: Option<String>,
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
    #[serde(default)]
    pub contacts: Vec<WireContact>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FavoritesPayload {
    #[serde(default)]
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
    #[default]
    #[serde(other)]
    #[error("provider error")]
    Unknown,
}

/// `{v, ok, error?, data?}` — every provider pull returns this and never throws.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Envelope<T> {
    #[serde(default = "default_version")]
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

pub type CallLogResponse = Envelope<CallLogPayload>;
pub type ContactsResponse = Envelope<ContactsPayload>;
pub type FavoritesResponse = Envelope<FavoritesPayload>;

#[cfg(test)]
mod tests {
    use super::*;

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
}
