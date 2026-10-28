//! Golden-fixture tests for `rovsti_core::wire`. The same files are read by
//! the Kotlin JVM test described in the `wire` module docs.

use rovsti_core::wire::{
    AudioRoute, CallLogKind, CallLogResponse, CallSnapshot, ContactsResponse, DisconnectCause,
    FavoritesResponse, WireCallState, WireError,
};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::Value;
use std::path::PathBuf;

fn fixture_text(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name);
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
}

fn parse<T: DeserializeOwned>(name: &str) -> T {
    serde_json::from_str(&fixture_text(name)).unwrap_or_else(|e| panic!("parse {name}: {e}"))
}

/// Deserialise, re-serialise and compare as JSON values (key order and
/// whitespace are irrelevant; Kotlin's `JSONObject` does not preserve them).
fn assert_round_trips<T: DeserializeOwned + Serialize>(name: &str) {
    let original: Value = serde_json::from_str(&fixture_text(name)).expect("fixture is JSON");
    let typed: T = parse(name);
    let again = serde_json::to_value(&typed).expect("serialise");
    assert_eq!(again, original, "{name} did not round-trip");
}

#[test]
fn snapshot_fixtures_round_trip() {
    assert_round_trips::<CallSnapshot>("call_snapshot_active.json");
    assert_round_trips::<CallSnapshot>("call_snapshot_waiting.json");
    assert_round_trips::<CallSnapshot>("call_snapshot_empty.json");
}

#[test]
fn provider_fixtures_round_trip() {
    assert_round_trips::<CallLogResponse>("call_log_ok.json");
    assert_round_trips::<ContactsResponse>("contacts_ok.json");
    assert_round_trips::<FavoritesResponse>("favorites_ok.json");
    assert_round_trips::<CallLogResponse>("call_log_permission.json");
}

#[test]
fn active_snapshot_content() {
    let s: CallSnapshot = parse("call_snapshot_active.json");
    assert_eq!(s.v, 1);
    assert_eq!(s.seq, 42);
    assert!(!s.keyguard_locked);
    assert!(s.audio.muted);
    assert_eq!(s.audio.route, AudioRoute::Speaker);
    assert_eq!(s.foreground_call_id, Some(1));
    let call = &s.calls[0];
    assert_eq!(call.state, WireCallState::Active);
    assert_eq!(call.display_name.as_deref(), Some("Ayşe Yılmaz"));
    assert_eq!(call.contact_id, Some(17));
    assert!(call.is_connected());
    assert!(call.can_hold);
}

#[test]
fn waiting_snapshot_content() {
    let s: CallSnapshot = parse("call_snapshot_waiting.json");
    assert_eq!(s.calls.len(), 2);
    assert_eq!(s.waiting_call_id, Some(2));
    let waiting = &s.calls[1];
    assert_eq!(waiting.state, WireCallState::Ringing);
    assert!(waiting.is_incoming);
    assert!(!waiting.is_connected());
}

#[test]
fn unknown_state_does_not_drop_the_snapshot() {
    let s: CallSnapshot = parse("call_snapshot_unknown_state.json");
    assert_eq!(s.calls.len(), 2);
    assert_eq!(s.calls[0].state, WireCallState::Unknown);
    assert_eq!(s.calls[1].state, WireCallState::Active);
    assert_eq!(s.audio.route, AudioRoute::Unknown);
    assert_eq!(s.calls[0].disconnect_cause, Some(DisconnectCause::Unknown));
}

#[test]
fn empty_snapshot_has_no_calls_and_is_locked() {
    let s: CallSnapshot = parse("call_snapshot_empty.json");
    assert!(s.calls.is_empty());
    assert!(s.keyguard_locked);
}

#[test]
fn missing_keyguard_locked_fixture_is_locked() {
    let s: CallSnapshot = parse("call_snapshot_no_keyguard.json");
    assert!(s.keyguard_locked);
    assert_eq!(s.calls[0].connect_time_ms, 0);
    assert!(!s.calls[0].is_connected());
}

#[test]
fn call_log_content() {
    let r: CallLogResponse = parse("call_log_ok.json");
    let payload = r.into_result().expect("ok");
    assert_eq!(payload.entries.len(), 4);
    assert_eq!(payload.entries[0].kind, CallLogKind::Missed);
    assert_eq!(payload.entries[2].kind, CallLogKind::Rejected);
    assert_eq!(payload.entries[3].kind, CallLogKind::Unknown);
    assert_eq!(payload.entries[3].number_raw, "");
}

#[test]
fn permission_error_is_a_value() {
    let r: CallLogResponse = parse("call_log_permission.json");
    assert!(!r.ok);
    assert_eq!(r.into_result(), Err(WireError::Permission));
}

#[test]
fn contacts_and_favorites_content() {
    let c = parse::<ContactsResponse>("contacts_ok.json")
        .into_result()
        .expect("ok");
    assert_eq!(c.contacts.len(), 2);
    assert_eq!(c.contacts[0].numbers[0].raw, "+90 555 111 22 33");
    let f = parse::<FavoritesResponse>("favorites_ok.json")
        .into_result()
        .expect("ok");
    assert!(f.contacts.iter().all(|c| c.starred));
}
