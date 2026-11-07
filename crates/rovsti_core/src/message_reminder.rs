//! "Remind me" on a single message: when it fires, what it says and how it
//! is stored. The Android side keeps the same JSON and only arms the alarm.

use serde::{Deserialize, Serialize};

use crate::reminder::{Slot, clock, local_day};

const PREVIEW_CHARS: usize = 80;

/// A pending reminder for one message.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MessageReminder {
    pub id: i64,
    /// Chat tag: `sms:12` or `wa:<jid>`.
    pub tag: String,
    /// WhatsApp id, or `sms:12` / `mms:7`.
    pub message_id: String,
    pub message_at_ms: i64,
    pub at_ms: i64,
    /// Chat title.
    pub title: String,
    pub preview: String,
}

fn one_line(s: &str) -> String {
    let line = s.split_whitespace().collect::<Vec<_>>().join(" ");
    if line.chars().count() <= PREVIEW_CHARS {
        return line;
    }
    let mut out: String = line.chars().take(PREVIEW_CHARS).collect();
    out.push('…');
    out
}

/// A reminder, or `None` for a blank tag or message id. The id is `now_ms`.
#[allow(clippy::too_many_arguments)]
pub fn new_reminder(
    tag: &str,
    message_id: &str,
    message_at_ms: i64,
    title: &str,
    preview: &str,
    slot: Slot,
    now_ms: i64,
    tz_offset_s: i32,
) -> Option<MessageReminder> {
    let tag = tag.trim();
    let message_id = message_id.trim();
    if tag.is_empty() || message_id.is_empty() {
        return None;
    }
    let title = title.trim();
    Some(MessageReminder {
        id: now_ms,
        tag: tag.to_owned(),
        message_id: message_id.to_owned(),
        message_at_ms,
        at_ms: slot.due_ms(now_ms, tz_offset_s),
        title: if title.is_empty() { "Message" } else { title }.to_owned(),
        preview: one_line(preview),
    })
}

/// The struct plus the finished notification texts.
pub fn arm_json(r: &MessageReminder) -> String {
    let mut value = serde_json::to_value(r).unwrap_or_default();
    if let Some(map) = value.as_object_mut() {
        map.insert(
            "notif_title".into(),
            format!("Reminder: {}", r.title).into(),
        );
        let body = if r.preview.is_empty() {
            "Open the message"
        } else {
            r.preview.as_str()
        };
        map.insert("notif_body".into(), body.into());
    }
    value.to_string()
}

/// The stored list; bad JSON gives an empty list and bad entries are skipped.
pub fn parse_list(json: &str) -> Vec<MessageReminder> {
    let Ok(items) = serde_json::from_str::<Vec<serde_json::Value>>(json) else {
        return Vec::new();
    };
    items
        .into_iter()
        .filter_map(|v| serde_json::from_value(v).ok())
        .collect()
}

/// The id in a `message_reminder_fired` payload (`{"id": 5}`, or the id as a string).
pub fn fired_id(json: &str) -> Option<i64> {
    let value: serde_json::Value = serde_json::from_str(json).ok()?;
    let id = value.get("id")?;
    id.as_i64().or_else(|| id.as_str()?.trim().parse().ok())
}

/// The reminder armed for `message_id`, if any.
pub fn for_message<'a>(
    list: &'a [MessageReminder],
    message_id: &str,
) -> Option<&'a MessageReminder> {
    list.iter().find(|r| r.message_id == message_id)
}

/// Pill text after setting the reminder.
pub fn set_text(r: &MessageReminder, now_ms: i64, tz_offset_s: i32) -> String {
    let time = clock(r.at_ms, tz_offset_s);
    match local_day(r.at_ms, tz_offset_s) - local_day(now_ms, tz_offset_s) {
        ..=0 => format!("Reminder set for {time}"),
        1 => format!("Reminder set for tomorrow at {time}"),
        n => format!("Reminder set for in {n} days at {time}"),
    }
}

/// Small badge on the message, e.g. "Reminder 18:05".
pub fn badge_text(r: &MessageReminder, tz_offset_s: i32) -> String {
    format!("Reminder {}", clock(r.at_ms, tz_offset_s))
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_700_000_000_000; // 2023-11-14 22:13:20 UTC

    fn make(slot: Slot, tz: i32) -> MessageReminder {
        new_reminder("wa:abc", "ID1", 5, " Anna ", "hi", slot, NOW, tz).expect("ok")
    }

    #[test]
    fn new_and_none_cases() {
        let r = make(Slot::In1Hour, 0);
        assert_eq!(r.id, NOW);
        assert_eq!(r.at_ms, NOW + 3_600_000);
        assert_eq!(r.title, "Anna");
        assert!(new_reminder(" ", "m", 0, "t", "p", Slot::In15Min, NOW, 0).is_none());
        assert!(new_reminder("sms:1", "  ", 0, "t", "p", Slot::In15Min, NOW, 0).is_none());
        let r = new_reminder("sms:1", "sms:9", 0, " ", "p", Slot::In15Min, NOW, 0).expect("ok");
        assert_eq!(r.title, "Message");
    }

    #[test]
    fn preview_trimmed() {
        let long = "word ".repeat(40);
        let r = new_reminder(
            "sms:1",
            "sms:9",
            0,
            "t",
            &format!("a\n\n b {long}"),
            Slot::In15Min,
            NOW,
            0,
        )
        .expect("ok");
        assert!(r.preview.starts_with("a b word"));
        assert_eq!(r.preview.chars().count(), 81);
        assert!(r.preview.ends_with('…'));
    }

    #[test]
    fn arm_json_keys() {
        let r = make(Slot::In15Min, 0);
        let v: serde_json::Value = serde_json::from_str(&arm_json(&r)).expect("ok");
        assert_eq!(v["notif_title"], "Reminder: Anna");
        assert_eq!(v["notif_body"], "hi");
        assert_eq!(v["message_id"], "ID1");
        assert_eq!(v["tag"], "wa:abc");
        let mut empty = r;
        empty.preview.clear();
        let v: serde_json::Value = serde_json::from_str(&arm_json(&empty)).expect("ok");
        assert_eq!(v["notif_body"], "Open the message");
        assert!(serde_json::from_str::<MessageReminder>(&arm_json(&empty)).is_ok());
    }

    #[test]
    fn parse_list_skips_garbage() {
        let good = serde_json::to_string(&make(Slot::In15Min, 0)).expect("ok");
        let json = format!("[{good}, {{\"id\": 1}}, 5, null, {good}]");
        assert_eq!(parse_list(&json).len(), 2);
        assert!(parse_list("nope").is_empty());
        assert!(parse_list("{}").is_empty());
        assert!(parse_list("").is_empty());
    }

    #[test]
    fn fired_id_reads_number_or_string() {
        assert_eq!(fired_id(r#"{"id": 7}"#), Some(7));
        assert_eq!(fired_id(r#"{"id": "8"}"#), Some(8));
        assert_eq!(fired_id(r#"{"id": "x"}"#), None);
        assert_eq!(fired_id("{}"), None);
        assert_eq!(fired_id("nope"), None);
    }

    #[test]
    fn finds_by_message() {
        let list = vec![make(Slot::In15Min, 0)];
        assert!(for_message(&list, "ID1").is_some());
        assert!(for_message(&list, "other").is_none());
    }

    #[test]
    fn set_text_same_day_vs_tomorrow() {
        // UTC: now 22:13, +1 h = 23:13 same day.
        let r = make(Slot::In1Hour, 0);
        assert_eq!(set_text(&r, NOW, 0), "Reminder set for 23:13");
        assert_eq!(badge_text(&r, 0), "Reminder 23:13");
        // UTC+3: now is 01:13 on the 15th; +1 h = 02:13 same local day.
        let r = make(Slot::In1Hour, 3 * 3600);
        assert_eq!(set_text(&r, NOW, 3 * 3600), "Reminder set for 02:13");
        // UTC: 22:13 + 1 h is same day, but tomorrow morning is next day.
        let r = make(Slot::TomorrowMorning, 0);
        assert_eq!(set_text(&r, NOW, 0), "Reminder set for tomorrow at 09:00");
        // Crossing midnight: UTC+1, now 23:13 local, +1 h = 00:13 next day.
        let r = make(Slot::In1Hour, 3600);
        assert_eq!(
            set_text(&r, NOW, 3600),
            "Reminder set for tomorrow at 00:13"
        );
    }
}
