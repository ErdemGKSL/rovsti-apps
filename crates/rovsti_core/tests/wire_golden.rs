//! Golden-fixture tests for `rovsti_core::wire`. The same files are read by
//! the Kotlin JVM test described in the `wire` module docs.

use rovsti_core::wire::{
    AudioRoute, BlockReason, BlocklistResponse, CallLogKind, CallLogResponse, CallSnapshot,
    ContactsResponse, DisconnectCause, DocumentOp, DocumentResult, EmergencyListResponse,
    EndpointHint, EndpointType, FavoritesResponse, HapticsLevel, NameFormat, PendingOp,
    PhoneAccountsResponse, RingtoneResult, SettingsSync, SilencedBy, UiRequest, UiRequestKind,
    VoicemailInfoResponse, WireCallState, WireContact, WireError, caps, features, props,
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
fn v02_snapshot_fixtures_round_trip() {
    for name in [
        "audio_endpoints.json",
        "two_calls.json",
        "conference.json",
        "selecting_account.json",
    ] {
        assert_round_trips::<CallSnapshot>(name);
    }
}

#[test]
fn audio_endpoints_content_and_derivation() {
    let s: CallSnapshot = parse("audio_endpoints.json");
    let e = &s.audio.endpoints;
    assert_eq!(e.len(), 5);
    assert_eq!(e[1].name.as_deref(), Some("Galaxy Buds"));
    assert!(e[1].selected && !e[0].selected);
    assert_eq!(e[2].hint, Some(EndpointHint::HearingAid));
    assert_eq!(e[0].kind, EndpointType::Earpiece);
    assert!(s.audio.is_consistent());
    assert_eq!(s.audio.route_from_endpoints(), Some(AudioRoute::Bluetooth));
    assert_eq!(
        s.calls[0].caps,
        caps::HOLD | caps::SUPPORT_HOLD | caps::MUTE | caps::ADD_PARTICIPANT
    );
}

#[test]
fn two_calls_and_conference_content() {
    let s: CallSnapshot = parse("two_calls.json");
    assert_eq!(s.background_call_id, Some(1));
    assert!(s.can_add_call && s.can_merge && s.can_swap);
    assert_eq!(s.pending, Some(PendingOp::Swap));
    assert_eq!(s.calls[0].hold_since_ms, Some(1_790_000_440_000));
    assert!(s.calls[1].has_prop(props::HD));
    let c: CallSnapshot = parse("conference.json");
    let parent = &c.calls[2];
    assert!(parent.has_prop(props::CONFERENCE));
    assert_eq!(parent.children, [1, 2]);
    assert_eq!(c.calls[0].parent_id, Some(3));
    assert!(c.calls[0].has_cap(caps::SPLIT) && c.calls[0].has_cap(caps::DISCONNECT_PARTICIPANT));
}

#[test]
fn selecting_account_content() {
    let s: CallSnapshot = parse("selecting_account.json");
    assert!(s.selecting_account);
    assert_eq!(s.accounts.len(), 2);
    assert_eq!(s.accounts[1].label, "SIM 2");
    assert!(s.ringer_silenced);
    assert_eq!(s.silenced_by, Some(SilencedBy::Volume));
    assert_eq!(s.pending_failed, Some(PendingOp::Merge));
    assert_eq!(s.ended_call_id, Some(9));
}

#[test]
fn an_unknown_endpoint_type_and_extra_fields_keep_the_snapshot() {
    let mut value: Value =
        serde_json::from_str(&fixture_text("audio_endpoints.json")).expect("fixture is JSON");
    value["audio"]["endpoints"][3]["type"] = Value::from("usb_dongle");
    value["future_field"] = serde_json::json!({"x": 1});
    value["calls"][0]["future_call_field"] = Value::from(true);
    let s: CallSnapshot = serde_json::from_value(value).expect("parse");
    assert_eq!(s.audio.endpoints[3].kind, EndpointType::Unknown);
    assert_eq!(s.audio.endpoints.len(), 5);
    assert_eq!(s.calls.len(), 1);
}

#[test]
fn a_newer_version_is_rejected_by_the_helper() {
    let mut value: Value =
        serde_json::from_str(&fixture_text("two_calls.json")).expect("fixture is JSON");
    value["v"] = Value::from(2);
    let s: CallSnapshot = serde_json::from_value(value).expect("parse");
    assert!(s.accept().is_err());
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

#[test]
fn v02_provider_fixtures_round_trip() {
    assert_round_trips::<CallLogResponse>("call_log_v2.json");
    assert_round_trips::<ContactsResponse>("contacts_v2.json");
    assert_round_trips::<PhoneAccountsResponse>("phone_accounts.json");
    assert_round_trips::<BlocklistResponse>("blocklist.json");
    assert_round_trips::<VoicemailInfoResponse>("voicemail_info.json");
    assert_round_trips::<EmergencyListResponse>("emergency_list.json");
}

#[test]
fn v02_events_and_settings_round_trip() {
    assert_round_trips::<SettingsSync>("settings_sync.json");
    assert_round_trips::<DocumentResult>("document_result_import.json");
    assert_round_trips::<DocumentResult>("document_result_error.json");
    assert_round_trips::<RingtoneResult>("ringtone_result.json");
    assert_round_trips::<UiRequest>("ui_request.json");
}

#[test]
fn v02_call_log_content() {
    let rows = parse::<CallLogResponse>("call_log_v2.json")
        .into_result()
        .expect("ok")
        .entries;
    assert_eq!(rows[0].kind, CallLogKind::Blocked);
    assert_eq!(rows[0].block_reason, Some(BlockReason::User));
    assert_eq!(rows[1].block_reason, Some(BlockReason::System));
    assert_eq!(rows[2].kind, CallLogKind::Voicemail);
    assert_eq!(rows[3].features, features::HD | features::VOLTE);
    assert!(rows[3].account_key.is_some());
}

#[test]
fn v01_rows_default_every_new_field() {
    let rows = parse::<CallLogResponse>("call_log_ok.json")
        .into_result()
        .expect("ok")
        .entries;
    for row in &rows {
        assert_eq!(row.account_key, None);
        assert_eq!(row.block_reason, None);
        assert_eq!(row.features, 0);
    }
    let contacts = parse::<ContactsResponse>("contacts_ok.json")
        .into_result()
        .expect("ok")
        .contacts;
    for c in &contacts {
        assert_eq!(c.lookup_key, None);
        assert_eq!(c.display_name_alt, None);
        assert_eq!(c.sort_key, None);
        assert_eq!(c.sort_key_alt, None);
    }
}

#[test]
fn v02_contact_fields() {
    let c: Vec<WireContact> = parse::<ContactsResponse>("contacts_v2.json")
        .into_result()
        .expect("ok")
        .contacts;
    assert_eq!(c[0].lookup_key.as_deref(), Some("0r17-2A3B"));
    assert_eq!(c[0].display_name_alt.as_deref(), Some("Yılmaz, Ayşe"));
}

#[test]
fn accounts_content() {
    let a = parse::<PhoneAccountsResponse>("phone_accounts.json")
        .into_result()
        .expect("ok");
    assert_eq!(a.accounts.len(), 2);
    assert_eq!(a.accounts[1].slot_index, Some(1));
    assert!(!a.accounts[1].enabled);
    assert!(a.accounts[0].sim);
    assert!(!a.ask);
    assert!(a.default_key.is_some());
}

#[test]
fn settings_sync_content_and_defaults() {
    let s: SettingsSync = parse("settings_sync.json");
    assert!(!s.proximity && s.flip_to_silence && s.vibrate_connect);
    assert_eq!(s.keypad_tones, Some(false));
    assert_eq!(s.haptics, HapticsLevel::Light);
    assert_eq!(s.name_format, NameFormat::LastFirst);
    // An empty object is the v0.1 behaviour.
    let d: SettingsSync = serde_json::from_str("{}").expect("parse");
    assert_eq!(d, SettingsSync::default());
    assert!(d.proximity);
    assert_eq!(d.keypad_tones, None);
}

#[test]
fn unknown_enum_values_do_not_fail() {
    let d: DocumentResult =
        serde_json::from_str(r#"{"op":"print_it","ok":false,"error":"nope","extra":1}"#)
            .expect("parse");
    assert_eq!(d.op, DocumentOp::Unknown);
    assert_eq!(d.error, Some(WireError::Unknown));
    let u: UiRequest = serde_json::from_str(r#"{"kind":"warp"}"#).expect("parse");
    assert_eq!(u.kind, UiRequestKind::Unknown);
    let reply: UiRequest =
        serde_json::from_str(r#"{"kind":"show_reply","call_id":4}"#).expect("parse");
    assert_eq!(reply.kind, UiRequestKind::ShowReply);
    assert_eq!(reply.call_id, Some(4));
    let s: SettingsSync =
        serde_json::from_str(r#"{"haptics":"turbo","name_format":"zzz","new":1}"#).expect("parse");
    assert_eq!(s.haptics, HapticsLevel::Normal);
    assert_eq!(s.name_format, NameFormat::FirstLast);
    let e = parse::<UiRequest>("ui_request.json");
    assert_eq!(e.call_id, Some(3));
}

#[test]
fn every_envelope_error_parses() {
    let all: Vec<CallLogResponse> = parse("envelope_errors.json");
    let errors: Vec<_> = all
        .into_iter()
        .map(|r| r.into_result().expect_err("error envelope"))
        .collect();
    assert_eq!(
        errors,
        [
            WireError::Permission,
            WireError::Unavailable,
            WireError::Refused,
            WireError::NotFound,
            WireError::Unknown,
            WireError::Unknown,
        ]
    );
}

#[test]
fn voicemail_and_emergency_content() {
    let v = parse::<VoicemailInfoResponse>("voicemail_info.json")
        .into_result()
        .expect("ok");
    assert!(v.available);
    assert_eq!(v.count, Some(2));
    let e = parse::<EmergencyListResponse>("emergency_list.json")
        .into_result()
        .expect("ok");
    assert_eq!(e.numbers, ["112", "155", "110"]);
    let r: RingtoneResult = parse("ringtone_result.json");
    assert_eq!(r.title.as_deref(), Some("Aurora"));
}
