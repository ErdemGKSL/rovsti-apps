//! `recents.*`, `call_log.*`, `call_notes.*`, `call_stats.*`, `speed_dial.*`, `blocklist.*`, `dial.*`, `sim.*`,
//! `setup.*`, `audio.*`, `scene.*` and `phone.*`: the Phone app's domain logic.
//!
//! Every op is stateless except the `phone.wa_tracker_*` family (a handle over the WhatsApp call tracker): the
//! caller sends the data an op works on and gets the new data back, so the Kotlin side keeps its state in
//! ordinary immutable models. The per-keystroke dial field (`dial::push`, long presses, font steps) is Kotlin
//! (OPS.md); everything here is per event, not per frame.

mod backup;
mod calls;
mod dial;
mod log;
mod people;
mod store;
mod wa_screen;

use super::registry::OpSpec;
use serde_json::{Value, json};

/// The op tables of the submodules, in the order the registry lists them.
pub(super) const TABLES: &[&[OpSpec]] = &[
    log::OPS,
    people::OPS,
    dial::OPS,
    calls::OPS,
    store::OPS,
    wa_screen::OPS,
    backup::OPS,
];

/// "now" of every fixture: 2026-09-21T17:33:20Z.
pub(super) const NOW: i64 = 1_790_000_000_000;

/// Istanbul in summer, 24 h clock, day-month-year.
pub(super) fn locale() -> Value {
    json!({"tz_offset_s": 10800, "format": {"hour12": false, "date_order": "day_month_year"}})
}

/// A small call log: two people, a blocked call and an unknown number.
pub(super) fn entries() -> Value {
    json!([
        {"id": 5, "number_raw": "0532 123 45 67", "normalized": "+905321234567", "kind": "missed",
         "date_ms": NOW - 60_000, "cached_name": "Ayşe Yılmaz", "contact_id": 1},
        {"id": 4, "number_raw": "0532 123 45 67", "normalized": "+905321234567", "kind": "missed",
         "date_ms": NOW - 120_000, "cached_name": "Ayşe Yılmaz", "contact_id": 1},
        {"id": 3, "number_raw": "+90 212 555 01 02", "normalized": "+902125550102", "kind": "outgoing",
         "date_ms": NOW - 3_600_000, "duration_s": 125, "contact_id": 2},
        {"id": 2, "number_raw": "+49 151 2345678", "normalized": "+491512345678", "kind": "blocked",
         "date_ms": NOW - 90_000_000, "block_reason": "user"},
        {"id": 1, "number_raw": "0532 123 45 67", "normalized": "+905321234567", "kind": "incoming",
         "date_ms": NOW - 200_000_000, "duration_s": 3700, "cached_name": "Ayşe Yılmaz", "contact_id": 1}
    ])
}

/// One WhatsApp call kept by the app.
pub(super) fn extras() -> Value {
    json!([
        {"number_raw": "+905321234567", "started_ms": NOW - 30_000, "duration_s": 0, "missed": true,
         "outgoing": false, "label": "Ayşe Yılmaz", "call_id": "wa-1"}
    ])
}
