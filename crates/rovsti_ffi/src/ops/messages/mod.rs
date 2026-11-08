//! `inbox.*`, `sms.*`, `sms_timeline.*`, `mms_pdu.*`, `otp.*`, `spam.*`, `schedule.*`, `notify.*`, `msg_backup.*` and
//! `messages.*`: the core half of the Messages app's domain logic (the chat tools are E2.11b).
//!
//! Every op is stateless except that `inbox.merge`, `inbox.person_for_*` read a contact index through its handle
//! (`search.index_new`). The SMS timeline is replayed from the events the caller sends, and the schedule queue is a
//! JSON string the caller keeps (OPS.md says why). Preferences and drafts are files in the data directory: a request
//! may name `dir` (tests, fixtures), else `core.init`'s data dir is used; saves are atomic.

mod backup;
mod inbox;
mod schedule;
mod sms;
mod spam;
mod store;

use super::registry::OpSpec;
use crate::envelope::FfiError;
use crate::state;
use serde::Serialize;
use serde_json::{Value, json};
use std::path::PathBuf;

/// The op tables of the submodules, in the order the registry lists them.
pub(super) const TABLES: &[&[OpSpec]] = &[
    inbox::OPS,
    sms::OPS,
    spam::OPS,
    schedule::OPS,
    backup::OPS,
    store::OPS,
];

/// "now" of every fixture: 2026-09-21T17:33:20Z.
pub(super) const NOW: i64 = 1_790_000_000_000;

#[derive(Serialize)]
pub(super) struct Empty {}

pub(super) fn data_dir(dir: &Option<String>) -> Result<PathBuf, FfiError> {
    match dir {
        Some(dir) => Ok(dir.into()),
        None => state::dirs()
            .map(|d| d.data_dir.clone())
            .ok_or_else(|| FfiError::invalid("init was not called")),
    }
}

/// The contacts every Messages fixture shares (`search.index_new`): Ayse owns 0532 123 45 67.
pub(super) fn contacts_setup() -> (&'static str, Value) {
    (
        "search.index_new",
        json!({"contacts": super::search::sample()}),
    )
}

/// A fixture literal in the form Rust writes it (every field present, `null` for none), so a Kotlin model that sits in
/// both a request and an answer re-encodes the file exactly.
pub(super) fn canon<T: serde::Serialize + serde::de::DeserializeOwned>(partial: Value) -> Value {
    let value: T = serde_json::from_value(partial).expect("fixture literal");
    serde_json::to_value(value).expect("fixture json")
}

/// One incoming message of a thread, as `rovsti_core::Message` travels.
pub(super) fn message(thread: Value, sender: &str, body: &str, at: i64, read: bool) -> Value {
    json!({"thread": thread, "direction": "Incoming",
           "sender": if sender.is_empty() { Value::Null } else { json!({"canonical": sender}) },
           "body": body, "timestamp_ms": at, "read": read})
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::{Answer, ErrorKind, decode};
    use crate::ops::call;
    use base64::Engine;

    fn run(op: &str, request: Value) -> Result<Value, (ErrorKind, Value)> {
        match decode(&call(op, request.to_string().as_bytes())).expect("envelope") {
            Answer::Ok(value) => Ok(value),
            Answer::Err(e) => Err((e.kind, e.detail.unwrap_or(Value::Null))),
        }
    }

    #[test]
    fn a_backup_opens_with_its_passphrase_and_only_with_it() {
        let sealed = run(
            "msg_backup.export",
            json!({"body": {"created_ms": NOW, "app_version": "1", "sms": [], "mms": []},
                   "passphrase": "pw", "iterations": 1000,
                   "salt": base64::engine::general_purpose::STANDARD.encode([1u8; 16]),
                   "nonce": base64::engine::general_purpose::STANDARD.encode([2u8; 12])}),
        )
        .expect("export");
        let bytes = sealed["bytes"].clone();
        let opened = run(
            "msg_backup.parse",
            json!({"bytes": bytes, "passphrase": "pw"}),
        )
        .expect("parse");
        assert_eq!(opened["body"]["created_ms"], NOW);
        let (kind, detail) = run(
            "msg_backup.parse",
            json!({"bytes": bytes, "passphrase": "no"}),
        )
        .expect_err("wrong");
        assert_eq!(kind, ErrorKind::Auth);
        assert_eq!(detail["reason"], "wrong_passphrase");
    }

    #[test]
    fn the_schedule_queue_survives_a_round_trip_through_its_string() {
        let item = |id: u64, at: i64| {
            canon::<rovsti_core::schedule::ScheduledSms>(
                json!({"id": id, "at_ms": at, "body": "hi", "created_ms": NOW,
                "target": {"WhatsApp": {"chat": "1@s.whatsapp.net"}}}),
            )
        };
        let first = run(
            "schedule.add",
            json!({"item": item(2, NOW + 120_000), "now_ms": NOW}),
        )
        .expect("add");
        let second = run(
            "schedule.add",
            json!({"queue_json": first["queue_json"], "item": item(1, NOW + 90_000), "now_ms": NOW}),
        )
        .expect("add");
        let ids: Vec<_> = second["items"]
            .as_array()
            .expect("items")
            .iter()
            .map(|i| i["id"].clone())
            .collect();
        assert_eq!(ids, [json!(1), json!(2)], "kept ordered by time");
        let loaded =
            run("schedule.load", json!({"queue_json": second["queue_json"]})).expect("load");
        assert_eq!(loaded["items"], second["items"]);
    }

    #[test]
    fn a_foreign_handle_is_not_found_for_the_inbox() {
        let (kind, _) = run(
            "inbox.person_for_number",
            json!({"contacts": 5, "raw": "0532"}),
        )
        .expect_err("no index");
        assert_eq!(kind, ErrorKind::NotFound);
    }

    #[test]
    fn otp_offsets_count_utf16_units() {
        let hit = run("otp.find", json!({"body": "😊 code 123456"})).expect("otp");
        assert_eq!(hit["otp"]["start"], 8, "the emoji is two UTF-16 units");
    }
}
