//! The chat tools of the Messages app (E2.11b): in-chat find, the per-chat gallery, export, global search, forward,
//! message info and reminders, starred and local marks, mute, quick replies, jumps, voice speeds, shared cards and
//! the detectors for numbers, dates and places in a body.
//!
//! Every op is stateless: the request carries the data (texts, lists, marks, a cursor) and the answer the new data.
//! Offsets into a body or a snippet are UTF-16 units, the unit of a Kotlin `String`, not Rust byte offsets.

mod detect;
mod gallery;
mod jump;
mod marks;
mod search;

use super::registry::OpSpec;

/// The op tables of the submodules, in the order the registry lists them.
pub(super) const TABLES: &[&[OpSpec]] = &[
    search::OPS,
    gallery::OPS,
    jump::OPS,
    marks::OPS,
    detect::OPS,
];

/// "now" of every fixture: 2026-09-21T17:33:20Z.
pub(super) const NOW: i64 = 1_790_000_000_000;
/// The zone of every fixture: UTC+3.
pub(super) const TZ: i32 = 10_800;

/// UTF-16 offset of the byte offset `byte` in `text` (clamped to the end).
pub(super) fn utf16_at(text: &str, byte: usize) -> usize {
    let byte = byte.min(text.len());
    text.get(..byte)
        .map_or_else(|| text.len(), |head| head.encode_utf16().count())
}

/// A `[start, end)` range in UTF-16 units.
#[derive(serde::Serialize)]
pub(super) struct Span {
    pub start: usize,
    pub end: usize,
}

impl Span {
    pub fn of(text: &str, range: &std::ops::Range<usize>) -> Self {
        Self {
            start: utf16_at(text, range.start),
            end: utf16_at(text, range.end),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::{Answer, ErrorKind, decode};
    use crate::ops::call;
    use serde_json::{Value, json};

    fn run(op: &str, request: Value) -> Result<Value, (ErrorKind, Value)> {
        match decode(&call(op, request.to_string().as_bytes())).expect("envelope") {
            Answer::Ok(value) => Ok(value),
            Answer::Err(e) => Err((e.kind, e.detail.unwrap_or(Value::Null))),
        }
    }

    #[test]
    fn offsets_count_utf16_units() {
        assert_eq!(utf16_at("😊 abc", 4), 2, "the emoji is two units");
        assert_eq!(utf16_at("abc", 99), 3);
        assert_eq!(utf16_at("ağ", 2), 3, "inside a char: clamped to the end");
    }

    #[test]
    fn a_date_after_an_emoji_is_located_in_utf16_units() {
        let found = run(
            "date_detect.detect",
            json!({"body": "😊 see you 2026-12-01 at 10:30", "now_ms": NOW,
                   "tz_offset_s": TZ, "chat": "Ayse"}),
        )
        .expect("detect");
        let first = &found["dates"][0];
        assert_eq!(first["text"], "2026-12-01 at 10:30");
        assert_eq!(
            first["start"], 11,
            "emoji 2 + space + 'see you ' counted in UTF-16"
        );
        assert_eq!(first["end"], 30);
    }

    #[test]
    fn a_search_highlight_is_in_utf16_units() {
        let hits = run(
            "global_search.search",
            json!({"query": "kahve", "partial": false, "items": [
                {"chat_key": "sms:1", "chat_title": "Ayse", "message_id": "sms:9",
                 "author": null, "text": "😊 Kahve icelim mi", "at_ms": NOW}]}),
        )
        .expect("search");
        assert_eq!(hits["hits"][0]["highlight"], json!({"start": 3, "end": 8}));
    }

    #[test]
    fn a_blank_reminder_is_invalid() {
        let (kind, _) = run(
            "message_reminder.make",
            json!({"kind": "message", "tag": " ", "message_id": "x", "message_at_ms": NOW,
                   "title": "", "preview": "", "slot": "in1_hour", "minutes": 0,
                   "now_ms": NOW, "tz_offset_s": TZ}),
        )
        .expect_err("blank tag");
        assert_eq!(kind, ErrorKind::Invalid);
    }
}
