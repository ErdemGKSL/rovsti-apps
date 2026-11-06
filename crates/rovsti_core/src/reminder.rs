//! "Call back later" reminders: when they fire, what they say and how they
//! are stored.
//!
//! Pure logic. The Android side keeps the same JSON (see [`parse_list`]) and
//! only arms the alarm and posts the notification.

use serde::{Deserialize, Serialize};

use crate::call::format_number;
use crate::phone_number::PhoneNumber;

const MIN_MS: i64 = 60_000;
const HOUR_MS: i64 = 60 * MIN_MS;
const DAY_S: i64 = 86_400;
const HOUR_S: i64 = 3_600;
/// Local hour of the "Tonight" reminder.
const TONIGHT_HOUR: i64 = 20;
/// From this local hour on, "Tonight" would be too close, so it becomes +1 h.
const TONIGHT_CUTOFF_HOUR: i64 = 19;
/// Local hour of the "Tomorrow morning" reminder.
const MORNING_HOUR: i64 = 9;

/// A pending call-back reminder.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Reminder {
    pub id: i64,
    pub number: String,
    pub name: Option<String>,
    pub at_ms: i64,
    pub created_ms: i64,
    /// Set from a missed call, so the notification can say so.
    #[serde(default)]
    pub missed: bool,
}

/// The choices offered when setting a reminder.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Slot {
    In15Min,
    In1Hour,
    Tonight,
    TomorrowMorning,
}

fn local_s(ms: i64, tz_offset_s: i32) -> i64 {
    ms.div_euclid(1000) + i64::from(tz_offset_s)
}

fn local_day(ms: i64, tz_offset_s: i32) -> i64 {
    local_s(ms, tz_offset_s).div_euclid(DAY_S)
}

/// UTC milliseconds of `hour`:00 local time on local `day`.
fn at_hour(day: i64, hour: i64, tz_offset_s: i32) -> i64 {
    (day * DAY_S + hour * HOUR_S - i64::from(tz_offset_s)) * 1000
}

fn clock(ms: i64, tz_offset_s: i32) -> String {
    let s = local_s(ms, tz_offset_s).rem_euclid(DAY_S);
    format!("{:02}:{:02}", s / HOUR_S, s % HOUR_S / 60)
}

impl Slot {
    pub const ALL: [Self; 4] = [
        Self::In15Min,
        Self::In1Hour,
        Self::Tonight,
        Self::TomorrowMorning,
    ];

    /// When the reminder fires, in UTC milliseconds.
    pub fn due_ms(self, now_ms: i64, tz_offset_s: i32) -> i64 {
        match self {
            Self::In15Min => now_ms + 15 * MIN_MS,
            Self::In1Hour => now_ms + HOUR_MS,
            Self::Tonight => {
                if self.is_soon(now_ms, tz_offset_s) {
                    now_ms + HOUR_MS
                } else {
                    at_hour(local_day(now_ms, tz_offset_s), TONIGHT_HOUR, tz_offset_s)
                }
            }
            Self::TomorrowMorning => at_hour(
                local_day(now_ms, tz_offset_s) + 1,
                MORNING_HOUR,
                tz_offset_s,
            ),
        }
    }

    /// "Tonight" at or after 19:00 local falls back to one hour from now.
    fn is_soon(self, now_ms: i64, tz_offset_s: i32) -> bool {
        self == Self::Tonight
            && local_s(now_ms, tz_offset_s).rem_euclid(DAY_S) >= TONIGHT_CUTOFF_HOUR * HOUR_S
    }

    pub fn label(self, now_ms: i64, tz_offset_s: i32) -> String {
        match self {
            Self::In15Min => "In 15 minutes".to_owned(),
            Self::In1Hour => "In 1 hour".to_owned(),
            Self::Tonight if self.is_soon(now_ms, tz_offset_s) => "Tonight, in 1 hour".to_owned(),
            Self::Tonight => format!("Tonight at {TONIGHT_HOUR:02}:00"),
            Self::TomorrowMorning => format!("Tomorrow at {MORNING_HOUR:02}:00"),
        }
    }
}

/// A reminder for `number`, or `None` for a private or empty number. The id
/// is `now_ms`.
pub fn new_reminder(
    number: &str,
    name: Option<&str>,
    slot: Slot,
    now_ms: i64,
    tz_offset_s: i32,
) -> Option<Reminder> {
    let number = number.trim();
    PhoneNumber::parse(number).ok()?;
    Some(Reminder {
        id: now_ms,
        number: number.to_owned(),
        name: name
            .map(str::trim)
            .filter(|n| !n.is_empty())
            .map(str::to_owned),
        at_ms: slot.due_ms(now_ms, tz_offset_s),
        created_ms: now_ms,
        missed: false,
    })
}

/// Pill text after setting the reminder.
pub fn set_text(r: &Reminder, now_ms: i64, tz_offset_s: i32) -> String {
    let time = clock(r.at_ms, tz_offset_s);
    match local_day(r.at_ms, tz_offset_s) - local_day(now_ms, tz_offset_s) {
        ..=0 => format!("Reminder at {time}"),
        1 => format!("Reminder tomorrow at {time}"),
        n => format!("Reminder in {n} days at {time}"),
    }
}

/// "Call back Anna", or the grouped number when there is no name.
pub fn notification_title(r: &Reminder) -> String {
    let who = r
        .name
        .as_deref()
        .map(str::trim)
        .filter(|n| !n.is_empty())
        .map_or_else(
            || {
                PhoneNumber::parse(&r.number)
                    .map_or_else(|_| r.number.clone(), |n| format_number(&n))
            },
            str::to_owned,
        );
    format!("Call back {who}")
}

pub fn notification_body(r: &Reminder) -> String {
    if r.missed {
        "You missed a call".to_owned()
    } else {
        "You asked to be reminded".to_owned()
    }
}

/// The JSON the Android alarm side stores: the reminder plus the finished
/// notification `title` and `body`, so Kotlin does not format anything.
pub fn arm_json(r: &Reminder) -> String {
    let mut value = serde_json::to_value(r).unwrap_or_default();
    if let Some(map) = value.as_object_mut() {
        map.insert("title".into(), notification_title(r).into());
        map.insert("body".into(), notification_body(r).into());
    }
    value.to_string()
}

/// The stored list; bad JSON gives an empty list.
pub fn parse_list(json: &str) -> Vec<Reminder> {
    serde_json::from_str(json).unwrap_or_default()
}

/// The reminder for `number` (parsed match, else equal trimmed text).
pub fn for_number<'a>(list: &'a [Reminder], number: &str) -> Option<&'a Reminder> {
    let number = number.trim();
    let wanted = PhoneNumber::parse(number).ok()?;
    list.iter().find(|r| {
        PhoneNumber::parse(&r.number).is_ok_and(|n| n.matches(&wanted)) || r.number.trim() == number
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 2024-03-05 00:00 UTC.
    const DAY0_MS: i64 = 1_709_596_800_000;
    const TR: i32 = 3 * 3600;
    const NEG: i32 = -5 * 3600;

    fn rem(at_ms: i64) -> Reminder {
        Reminder {
            id: 1,
            number: "+442079460958".into(),
            name: Some("Anna Smith".into()),
            at_ms,
            created_ms: 0,
            missed: true,
        }
    }

    #[test]
    fn arm_json_carries_reminder_and_texts() {
        let r = rem(5_000);
        let v: serde_json::Value = serde_json::from_str(&arm_json(&r)).expect("json");
        assert_eq!(v["id"], 1);
        assert_eq!(v["number"], "+442079460958");
        assert_eq!(v["at_ms"], 5_000);
        assert_eq!(v["title"], notification_title(&r));
        assert_eq!(v["body"], "You missed a call");
        // The stored form still parses as a Reminder.
        assert_eq!(parse_list(&format!("[{}]", arm_json(&r))), vec![r]);
    }

    #[test]
    fn relative_slots() {
        assert_eq!(Slot::In15Min.due_ms(1000, 0), 1000 + 900_000);
        assert_eq!(Slot::In1Hour.due_ms(1000, TR), 1000 + 3_600_000);
    }

    #[test]
    fn tonight_before_cutoff() {
        // 10:00 local (UTC+3) = 07:00 UTC; 20:00 local = 17:00 UTC.
        let now = DAY0_MS + 7 * HOUR_MS;
        assert_eq!(Slot::Tonight.due_ms(now, TR), DAY0_MS + 17 * HOUR_MS);
        assert_eq!(Slot::Tonight.label(now, TR), "Tonight at 20:00");
    }

    #[test]
    fn tonight_after_1930_is_one_hour() {
        // 19:30 local = 16:30 UTC.
        let now = DAY0_MS + 16 * HOUR_MS + 30 * MIN_MS;
        assert_eq!(Slot::Tonight.due_ms(now, TR), now + HOUR_MS);
        assert_eq!(Slot::Tonight.label(now, TR), "Tonight, in 1 hour");
        // 18:59 local is still 20:00.
        let early = DAY0_MS + 15 * HOUR_MS + 59 * MIN_MS;
        assert_eq!(Slot::Tonight.due_ms(early, TR), DAY0_MS + 17 * HOUR_MS);
    }

    #[test]
    fn tonight_just_after_midnight_is_same_local_day() {
        // 00:30 local on the 6th = 21:30 UTC on the 5th; 20:00 local the 6th.
        let now = DAY0_MS + 21 * HOUR_MS + 30 * MIN_MS;
        assert_eq!(
            Slot::Tonight.due_ms(now, TR),
            DAY0_MS + 24 * HOUR_MS + 17 * HOUR_MS
        );
    }

    #[test]
    fn tomorrow_morning_midnight_and_negative_tz() {
        // 23:30 local on the 5th = 20:30 UTC; 09:00 local on the 6th = 06:00 UTC.
        let now = DAY0_MS + 20 * HOUR_MS + 30 * MIN_MS;
        assert_eq!(
            Slot::TomorrowMorning.due_ms(now, TR),
            DAY0_MS + 30 * HOUR_MS
        );
        // 00:30 local on the 6th: tomorrow is the 7th.
        let now = DAY0_MS + 21 * HOUR_MS + 30 * MIN_MS;
        assert_eq!(
            Slot::TomorrowMorning.due_ms(now, TR),
            DAY0_MS + 54 * HOUR_MS
        );
        // UTC-5: 22:00 UTC on the 5th is 17:00 local the 5th; 09:00 local the
        // 6th is 14:00 UTC the 6th.
        let now = DAY0_MS + 22 * HOUR_MS;
        assert_eq!(
            Slot::TomorrowMorning.due_ms(now, NEG),
            DAY0_MS + 38 * HOUR_MS
        );
        // UTC-5: 03:00 UTC on the 5th is 22:00 local on the 4th.
        let now = DAY0_MS + 3 * HOUR_MS;
        assert_eq!(
            Slot::TomorrowMorning.due_ms(now, NEG),
            DAY0_MS + 14 * HOUR_MS
        );
        assert_eq!(Slot::Tonight.due_ms(now, NEG), now + HOUR_MS);
    }

    #[test]
    fn labels() {
        assert_eq!(Slot::In15Min.label(0, 0), "In 15 minutes");
        assert_eq!(Slot::In1Hour.label(0, 0), "In 1 hour");
        assert_eq!(Slot::TomorrowMorning.label(0, 0), "Tomorrow at 09:00");
        assert_eq!(Slot::ALL.len(), 4);
    }

    #[test]
    fn new_reminder_fields() {
        let now = DAY0_MS + 7 * HOUR_MS;
        let r = new_reminder(" 020 7946 0958 ", Some("  "), Slot::In1Hour, now, TR).expect("ok");
        assert_eq!(r.id, now);
        assert_eq!(r.created_ms, now);
        assert_eq!(r.at_ms, now + HOUR_MS);
        assert_eq!(r.number, "020 7946 0958");
        assert_eq!(r.name, None);
        assert!(!r.missed);
        let r = new_reminder("+90 532 1", Some(" Ali "), Slot::In15Min, now, TR).expect("ok");
        assert_eq!(r.name.as_deref(), Some("Ali"));
    }

    #[test]
    fn new_reminder_refuses_private() {
        for n in ["", "  ", "Private", "unknown"] {
            assert_eq!(new_reminder(n, None, Slot::In15Min, 0, 0), None, "{n}");
        }
    }

    #[test]
    fn set_text_days() {
        let now = DAY0_MS + 7 * HOUR_MS;
        let tonight = rem(Slot::Tonight.due_ms(now, TR));
        assert_eq!(set_text(&tonight, now, TR), "Reminder at 20:00");
        let morning = rem(Slot::TomorrowMorning.due_ms(now, TR));
        assert_eq!(set_text(&morning, now, TR), "Reminder tomorrow at 09:00");
        let later = rem(now + 3 * 24 * HOUR_MS);
        assert_eq!(set_text(&later, now, TR), "Reminder in 3 days at 10:00");
        let neg = rem(DAY0_MS + 14 * HOUR_MS);
        assert_eq!(
            set_text(&neg, DAY0_MS + 3 * HOUR_MS, NEG),
            "Reminder tomorrow at 09:00"
        );
    }

    #[test]
    fn notification_text() {
        let mut r = rem(0);
        assert_eq!(notification_title(&r), "Call back Anna Smith");
        assert_eq!(notification_body(&r), "You missed a call");
        r.name = None;
        r.missed = false;
        r.number = "+905321234567".into();
        assert_eq!(notification_title(&r), "Call back +90 532 123 45 67");
        assert_eq!(notification_body(&r), "You asked to be reminded");
        r.name = Some("  ".into());
        assert!(notification_title(&r).starts_with("Call back +90"));
        r.number = "weird".into();
        r.name = None;
        assert_eq!(notification_title(&r), "Call back weird");
    }

    #[test]
    fn json_round_trip_and_bad_json() {
        let list = vec![rem(5), Reminder { id: 2, ..rem(9) }];
        let json = serde_json::to_string(&list).expect("ok");
        assert_eq!(parse_list(&json), list);
        assert!(parse_list("not json").is_empty());
        assert!(parse_list("").is_empty());
        assert!(parse_list("{}").is_empty());
        // `missed` is optional in stored data.
        let old = r#"[{"id":1,"number":"5","name":null,"at_ms":2,"created_ms":1}]"#;
        let parsed = parse_list(old);
        assert_eq!(parsed.len(), 1);
        assert!(!parsed[0].missed);
    }

    #[test]
    fn for_number_matches_national_and_e164() {
        let list = vec![rem(1)];
        assert!(for_number(&list, "+442079460958").is_some());
        assert!(for_number(&list, "020 7946 0958").is_some());
        assert!(for_number(&list, "+442079460959").is_none());
        assert!(for_number(&list, "").is_none());
        assert!(for_number(&[], "+442079460958").is_none());
    }
}
