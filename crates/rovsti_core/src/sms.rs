//! SMS and MMS payloads exchanged between Kotlin and Rust (AD-MSG2).
//!
//! Same rules as [`crate::wire`]: snake_case fields, unknown fields ignored,
//! every field defaulted and `null` read as the default. The Telephony provider
//! is the store; these types only describe one pull of it.

use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::PhoneNumber;
use crate::thread::{Message, MessageDirection, ThreadId};
use crate::wire::null_default;

/// `Telephony.Sms.TYPE`. Unknown or missing values read as [`SmsBox::Inbox`].
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum SmsBox {
    #[default]
    Inbox,
    Sent,
    Draft,
    Outbox,
    Failed,
    Queued,
}

impl SmsBox {
    /// The `Telephony.TextBasedSmsColumns.MESSAGE_TYPE_*` value.
    pub fn code(self) -> u8 {
        match self {
            Self::Inbox => 1,
            Self::Sent => 2,
            Self::Draft => 3,
            Self::Outbox => 4,
            Self::Failed => 5,
            Self::Queued => 6,
        }
    }

    pub fn from_code(code: i64) -> Self {
        match code {
            2 => Self::Sent,
            3 => Self::Draft,
            4 => Self::Outbox,
            5 => Self::Failed,
            6 => Self::Queued,
            _ => Self::Inbox,
        }
    }

    /// True for boxes that hold a message we sent (or tried to).
    pub fn is_outgoing(self) -> bool {
        !matches!(self, Self::Inbox)
    }
}

impl Serialize for SmsBox {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_u8(self.code())
    }
}

impl<'de> Deserialize<'de> for SmsBox {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        #[serde(untagged)]
        enum Raw {
            Int(i64),
            Text(String),
        }
        Ok(match Option::<Raw>::deserialize(deserializer)? {
            Some(Raw::Int(code)) => Self::from_code(code),
            Some(Raw::Text(text)) => match text.trim().to_ascii_lowercase().as_str() {
                "sent" => Self::Sent,
                "draft" => Self::Draft,
                "outbox" => Self::Outbox,
                "failed" => Self::Failed,
                "queued" => Self::Queued,
                other => other.parse::<i64>().map_or(Self::Inbox, Self::from_code),
            },
            None => Self::Inbox,
        })
    }
}

/// How far an outgoing message got. Unknown values read as `None`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DeliveryState {
    Pending,
    Sent,
    Delivered,
    Failed,
    #[default]
    #[serde(other)]
    None,
}

/// One row of `Telephony.Sms`.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsRow {
    #[serde(default, deserialize_with = "null_default")]
    pub id: i64,
    #[serde(default, deserialize_with = "null_default")]
    pub thread_id: i64,
    #[serde(default, deserialize_with = "null_default")]
    pub address: String,
    #[serde(default, deserialize_with = "null_default")]
    pub body: String,
    #[serde(default, deserialize_with = "null_default")]
    pub date_ms: i64,
    #[serde(default, rename = "box")]
    pub box_: SmsBox,
    #[serde(default, deserialize_with = "null_default")]
    pub read: bool,
    #[serde(default)]
    pub sub_id: Option<i32>,
    #[serde(default, deserialize_with = "null_default")]
    pub delivery: DeliveryState,
}

impl SmsRow {
    /// The unified-model view of this row.
    pub fn to_message(&self) -> Message {
        let incoming = !self.box_.is_outgoing();
        Message {
            thread: ThreadId::Sms(self.thread_id),
            direction: if incoming {
                MessageDirection::Incoming
            } else {
                MessageDirection::Outgoing
            },
            sender: if incoming {
                PhoneNumber::parse(&self.address).ok()
            } else {
                None
            },
            body: self.body.clone(),
            timestamp_ms: self.date_ms,
            read: self.read,
        }
    }
}

/// One MMS part. Binary parts are files Kotlin wrote; only the path travels.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MmsPart {
    #[serde(default, deserialize_with = "null_default")]
    pub content_type: String,
    #[serde(default)]
    pub text: Option<String>,
    #[serde(default)]
    pub path: Option<String>,
}

/// One MMS of a thread.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MmsRow {
    #[serde(default, deserialize_with = "null_default")]
    pub id: i64,
    #[serde(default, deserialize_with = "null_default")]
    pub thread_id: i64,
    #[serde(default, deserialize_with = "null_default")]
    pub addresses: Vec<String>,
    #[serde(default)]
    pub from: Option<String>,
    #[serde(default, deserialize_with = "null_default")]
    pub date_ms: i64,
    #[serde(default, deserialize_with = "null_default")]
    pub outgoing: bool,
    #[serde(default, deserialize_with = "null_default")]
    pub read: bool,
    #[serde(default, deserialize_with = "null_default")]
    pub parts: Vec<MmsPart>,
    #[serde(default)]
    pub sub_id: Option<i32>,
}

impl MmsRow {
    /// The text parts joined by a newline.
    pub fn text(&self) -> String {
        self.parts
            .iter()
            .filter_map(|p| p.text.as_deref())
            .filter(|t| !t.is_empty())
            .collect::<Vec<_>>()
            .join("\n")
    }
}

/// One conversation of `Telephony.Threads`.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsThreadRow {
    #[serde(default, deserialize_with = "null_default")]
    pub thread_id: i64,
    #[serde(default, deserialize_with = "null_default")]
    pub addresses: Vec<String>,
    #[serde(default, deserialize_with = "null_default")]
    pub snippet: String,
    #[serde(default, deserialize_with = "null_default")]
    pub date_ms: i64,
    #[serde(default, deserialize_with = "null_default")]
    pub unread: u32,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsThreadsPayload {
    #[serde(default, deserialize_with = "null_default")]
    pub threads: Vec<SmsThreadRow>,
}

/// One page of a thread, newest first or any order; the timeline sorts.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsMessagesPayload {
    #[serde(default, deserialize_with = "null_default")]
    pub sms: Vec<SmsRow>,
    #[serde(default, deserialize_with = "null_default")]
    pub mms: Vec<MmsRow>,
    #[serde(default, deserialize_with = "null_default")]
    pub has_older: bool,
}

/// An active SIM the user can send from.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsSim {
    #[serde(default, deserialize_with = "null_default")]
    pub sub_id: i32,
    #[serde(default)]
    pub slot_index: Option<u8>,
    #[serde(default, deserialize_with = "null_default")]
    pub label: String,
    #[serde(default, deserialize_with = "null_default")]
    pub color_argb: i32,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsSimsPayload {
    #[serde(default, deserialize_with = "null_default")]
    pub sims: Vec<SmsSim>,
    #[serde(default)]
    pub default_sub_id: Option<i32>,
}

/// The `sms_status` event: a send moved along.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsStatus {
    #[serde(default, deserialize_with = "null_default")]
    pub local_id: u64,
    #[serde(default)]
    pub uri: Option<String>,
    #[serde(default, deserialize_with = "null_default")]
    pub state: DeliveryState,
    #[serde(default)]
    pub error_code: Option<i32>,
}

/// A send request, Rust to Kotlin.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsSend {
    #[serde(default, deserialize_with = "null_default")]
    pub local_id: u64,
    #[serde(default, deserialize_with = "null_default")]
    pub to: Vec<String>,
    #[serde(default, deserialize_with = "null_default")]
    pub body: String,
    #[serde(default)]
    pub sub_id: Option<i32>,
}

/// What a body costs: number of SMS parts and room left in the last one.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SegmentInfo {
    pub parts: u32,
    pub left_in_part: u32,
    /// The body needs UCS-2 (70/67 per part instead of 160/153).
    pub unicode: bool,
}

/// GSM 03.38 extension table: each costs two septets.
const GSM_EXTENSION: &str = "^{}\\[~]|\u{20ac}\u{0c}";

/// GSM 03.38 default alphabet (without the escape slot).
const GSM_BASIC: &str = "@\u{a3}$\u{a5}\u{e8}\u{e9}\u{f9}\u{ec}\u{f2}\u{c7}\n\u{d8}\u{f8}\r\u{c5}\u{e5}\u{394}_\u{3a6}\u{393}\u{39b}\u{3a9}\u{3a0}\u{3a8}\u{3a3}\u{398}\u{39e}\u{c6}\u{e6}\u{df}\u{c9} !\"#\u{a4}%&'()*+,-./0123456789:;<=>?\u{a1}ABCDEFGHIJKLMNOPQRSTUVWXYZ\u{c4}\u{d6}\u{d1}\u{dc}\u{a7}\u{bf}abcdefghijklmnopqrstuvwxyz\u{e4}\u{f6}\u{f1}\u{fc}\u{e0}";

fn gsm_cost(c: char) -> Option<u32> {
    if GSM_BASIC.contains(c) {
        Some(1)
    } else if GSM_EXTENSION.contains(c) {
        Some(2)
    } else {
        None
    }
}

/// Counts the SMS parts `body` needs. Parts are 160 septets (153 when
/// concatenated) in GSM-7, 70 (67) UTF-16 units in UCS-2. A character is never
/// split across parts.
pub fn segments(body: &str) -> SegmentInfo {
    let gsm: Option<Vec<u32>> = body.chars().map(gsm_cost).collect();
    let (costs, single, multi, unicode) = match gsm {
        Some(costs) => (costs, 160, 153, false),
        None => (
            body.chars().map(|c| c.len_utf16() as u32).collect(),
            70,
            67,
            true,
        ),
    };
    let total: u32 = costs.iter().sum();
    if total <= single {
        return SegmentInfo {
            parts: 1,
            left_in_part: single - total,
            unicode,
        };
    }
    let (mut parts, mut used) = (1, 0);
    for cost in costs {
        if used + cost > multi {
            parts += 1;
            used = 0;
        }
        used += cost;
    }
    SegmentInfo {
        parts,
        left_in_part: multi - used,
        unicode,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn box_codes_round_trip() {
        for code in 1..=6 {
            assert_eq!(i64::from(SmsBox::from_code(code).code()), code);
        }
        assert_eq!(SmsBox::from_code(99), SmsBox::Inbox);
        assert_eq!(SmsBox::from_code(0), SmsBox::Inbox);
    }

    #[test]
    fn to_message_maps_direction_and_sender() {
        let row = SmsRow {
            thread_id: 7,
            address: "+90 532 123 45 67".into(),
            body: "hi".into(),
            date_ms: 5,
            ..Default::default()
        };
        let m = row.to_message();
        assert_eq!(m.thread, ThreadId::Sms(7));
        assert_eq!(m.direction, MessageDirection::Incoming);
        assert_eq!(m.sender.expect("sender").as_str(), "+905321234567");
        let sent = SmsRow {
            box_: SmsBox::Sent,
            ..row
        };
        let m = sent.to_message();
        assert_eq!(m.direction, MessageDirection::Outgoing);
        assert!(m.sender.is_none());
    }

    #[test]
    fn ascii_boundary() {
        let s = segments(&"a".repeat(160));
        assert_eq!((s.parts, s.left_in_part, s.unicode), (1, 0, false));
        let s = segments(&"a".repeat(161));
        assert_eq!(
            (s.parts, s.left_in_part, s.unicode),
            (2, 153 * 2 - 161, false)
        );
        assert_eq!(segments("").parts, 1);
    }

    #[test]
    fn turkish_needs_ucs2() {
        let s = segments(&format!("{}\u{15f}", "a".repeat(69)));
        assert_eq!((s.parts, s.left_in_part, s.unicode), (1, 0, true));
        let s = segments(&format!("{}\u{15f}", "a".repeat(70)));
        assert_eq!((s.parts, s.unicode), (2, true));
    }

    #[test]
    fn euro_counts_two() {
        assert_eq!(segments("\u{20ac}").left_in_part, 158);
        let s = segments(&format!("{}\u{20ac}", "a".repeat(159)));
        assert_eq!(s.parts, 2);
        let s = segments(&format!("{}\u{20ac}", "a".repeat(158)));
        assert_eq!((s.parts, s.left_in_part), (1, 0));
    }

    #[test]
    fn extension_char_is_not_split_across_parts() {
        // 152 septets + a 2-septet euro does not fit in 153.
        let s = segments(&format!("{}\u{20ac}{}", "a".repeat(152), "a".repeat(10)));
        assert_eq!(s.parts, 2);
        assert_eq!(s.left_in_part, 153 - 12);
    }

    #[test]
    fn emoji_counts_two_units() {
        let s = segments("\u{1f600}");
        assert_eq!((s.parts, s.left_in_part, s.unicode), (1, 68, true));
    }
}
