//! Scheduled messages: validation, the persisted queue and the quick-pick
//! times. The clock and the time zone are always passed in.

use serde::{Deserialize, Serialize};

pub const MIN_LEAD_MS: i64 = 60_000;
pub const MAX_LEAD_MS: i64 = 365 * 24 * 3_600_000;
pub const MAX_BODY_BYTES: usize = 64 * 1024;
pub const MAX_QUEUE: usize = 200;

const HOUR_MS: i64 = 3_600_000;
const DAY_S: i64 = 86_400;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum ScheduleTarget {
    Sms { to: String, sub_id: Option<i32> },
    WhatsApp { chat: String },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScheduledSms {
    pub id: u64,
    pub at_ms: i64,
    pub target: ScheduleTarget,
    pub body: String,
    pub created_ms: i64,
}

/// What the platform reports after trying to send an item that was due.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FiredSms {
    pub id: u64,
    pub ok: bool,
    pub at_ms: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum ScheduleError {
    #[error("pick a time at least a minute from now")]
    TooSoon,
    #[error("pick a time within a year")]
    TooFar,
    #[error("the message is empty")]
    EmptyBody,
    #[error("the message is too long")]
    BodyTooLong,
    #[error("too many scheduled messages")]
    QueueFull,
    #[error("a scheduled message with this id exists")]
    DuplicateId,
}

pub fn validate(at_ms: i64, now_ms: i64, body: &str) -> Result<(), ScheduleError> {
    if at_ms < now_ms.saturating_add(MIN_LEAD_MS) {
        return Err(ScheduleError::TooSoon);
    }
    if at_ms > now_ms.saturating_add(MAX_LEAD_MS) {
        return Err(ScheduleError::TooFar);
    }
    if body.is_empty() {
        return Err(ScheduleError::EmptyBody);
    }
    if body.len() > MAX_BODY_BYTES {
        return Err(ScheduleError::BodyTooLong);
    }
    Ok(())
}

/// Items waiting to be sent, kept ordered by time (ties by id).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScheduleQueue {
    items: Vec<ScheduledSms>,
}

impl ScheduleQueue {
    pub fn items(&self) -> &[ScheduledSms] {
        &self.items
    }

    pub fn len(&self) -> usize {
        self.items.len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    /// Callers run [`validate`] first; this enforces the cap and unique ids.
    pub fn add(&mut self, item: ScheduledSms) -> Result<(), ScheduleError> {
        if self.items.len() >= MAX_QUEUE {
            return Err(ScheduleError::QueueFull);
        }
        if self.items.iter().any(|i| i.id == item.id) {
            return Err(ScheduleError::DuplicateId);
        }
        let at = self
            .items
            .partition_point(|i| (i.at_ms, i.id) <= (item.at_ms, item.id));
        self.items.insert(at, item);
        Ok(())
    }

    pub fn cancel(&mut self, id: u64) -> bool {
        let before = self.items.len();
        self.items.retain(|i| i.id != id);
        self.items.len() != before
    }

    /// Removes every item the platform reports as fired and returns the ones
    /// that failed, for the "could not send" notice. Unknown ids are ignored.
    pub fn reconcile(&mut self, fired: &[FiredSms]) -> Vec<ScheduledSms> {
        let mut failed = Vec::new();
        for f in fired {
            if let Some(pos) = self.items.iter().position(|i| i.id == f.id) {
                let item = self.items.remove(pos);
                if !f.ok {
                    failed.push(item);
                }
            }
        }
        failed
    }

    /// Items due at or before `now_ms + within_ms`, oldest first. The list
    /// is what gets (re)registered with the platform alarm service.
    pub fn due_soon(&self, now_ms: i64, within_ms: i64) -> Vec<&ScheduledSms> {
        let limit = now_ms.saturating_add(within_ms);
        self.items.iter().filter(|i| i.at_ms <= limit).collect()
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| "{\"items\":[]}".into())
    }

    /// Corrupt input gives an empty queue; oversized input is cut to the cap.
    pub fn from_json(json: &str) -> Self {
        let mut q: Self = serde_json::from_str(json).unwrap_or_default();
        q.items.sort_by_key(|i| (i.at_ms, i.id));
        q.items.dedup_by_key(|i| i.id);
        q.items.truncate(MAX_QUEUE);
        q
    }
}

/// "In 1 hour", the next 18:00 and the next day's 09:00, in the viewer's
/// zone. Every time is at least [`MIN_LEAD_MS`] away.
pub fn quick_times(now_ms: i64, tz_offset_s: i32) -> [(&'static str, i64); 3] {
    let tz = i64::from(tz_offset_s);
    let local_s = now_ms.div_euclid(1000) + tz;
    let local_midnight_ms = (local_s.div_euclid(DAY_S) * DAY_S - tz) * 1000;
    let at = |day: i64, hour: i64| local_midnight_ms + day * DAY_S * 1000 + hour * HOUR_MS;

    let earliest = now_ms + MIN_LEAD_MS;
    let (evening_label, evening) = if at(0, 18) >= earliest {
        ("This evening", at(0, 18))
    } else {
        ("Tomorrow evening", at(1, 18))
    };
    let morning = if at(1, 9) >= earliest {
        at(1, 9)
    } else {
        at(2, 9)
    };
    [
        ("In 1 hour", now_ms + HOUR_MS),
        (evening_label, evening),
        ("Tomorrow morning", morning),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_800_000_000_000;

    fn item(id: u64, at_ms: i64) -> ScheduledSms {
        ScheduledSms {
            id,
            at_ms,
            target: ScheduleTarget::Sms {
                to: "+905321234567".into(),
                sub_id: Some(1),
            },
            body: "hi".into(),
            created_ms: NOW,
        }
    }

    #[test]
    fn validate_bounds() {
        assert_eq!(validate(NOW + 60_000, NOW, "x"), Ok(()));
        assert_eq!(
            validate(NOW + 59_999, NOW, "x"),
            Err(ScheduleError::TooSoon)
        );
        assert_eq!(validate(NOW - 1, NOW, "x"), Err(ScheduleError::TooSoon));
        assert_eq!(validate(NOW + MAX_LEAD_MS, NOW, "x"), Ok(()));
        assert_eq!(
            validate(NOW + MAX_LEAD_MS + 1, NOW, "x"),
            Err(ScheduleError::TooFar)
        );
        assert_eq!(
            validate(NOW + 60_000, NOW, ""),
            Err(ScheduleError::EmptyBody)
        );
        assert_eq!(
            validate(NOW + 60_000, NOW, &"a".repeat(MAX_BODY_BYTES)),
            Ok(())
        );
        assert_eq!(
            validate(NOW + 60_000, NOW, &"a".repeat(MAX_BODY_BYTES + 1)),
            Err(ScheduleError::BodyTooLong)
        );
        // Bytes, not characters.
        assert_eq!(
            validate(NOW + 60_000, NOW, &"ş".repeat(MAX_BODY_BYTES / 2 + 1)),
            Err(ScheduleError::BodyTooLong)
        );
    }

    #[test]
    fn queue_cap_order_and_cancel() {
        let mut q = ScheduleQueue::default();
        for id in 0..MAX_QUEUE as u64 {
            q.add(item(id, NOW + 1000 * (MAX_QUEUE as i64 - id as i64)))
                .expect("room");
        }
        assert_eq!(q.add(item(999, NOW)), Err(ScheduleError::QueueFull));
        assert!(q.items().windows(2).all(|w| w[0].at_ms <= w[1].at_ms));
        assert!(q.cancel(0));
        assert!(!q.cancel(0));
        assert_eq!(q.add(item(5, NOW)), Err(ScheduleError::DuplicateId));
        assert_eq!(q.add(item(1000, NOW)), Ok(()));
        assert_eq!(q.items()[0].id, 1000);
    }

    #[test]
    fn reconcile_returns_failures_and_drops_fired() {
        let mut q = ScheduleQueue::default();
        for (id, at) in [(1, 10), (2, 20), (3, 30)] {
            q.add(item(id, at)).expect("add");
        }
        let failed = q.reconcile(&[
            FiredSms {
                id: 1,
                ok: true,
                at_ms: 10,
            },
            FiredSms {
                id: 2,
                ok: false,
                at_ms: 20,
            },
            FiredSms {
                id: 77,
                ok: false,
                at_ms: 1,
            },
        ]);
        assert_eq!(failed, vec![item(2, 20)]);
        assert_eq!(q.items(), &[item(3, 30)]);
        assert!(q.reconcile(&[]).is_empty());
    }

    #[test]
    fn due_soon_window() {
        let mut q = ScheduleQueue::default();
        q.add(item(1, NOW + 10)).expect("add");
        q.add(item(2, NOW + 1000)).expect("add");
        let ids: Vec<u64> = q.due_soon(NOW, 500).iter().map(|i| i.id).collect();
        assert_eq!(ids, [1]);
        assert_eq!(q.due_soon(NOW, 1000).len(), 2);
        assert!(q.due_soon(NOW - 100, 0).is_empty());
    }

    #[test]
    fn json_round_trip_and_corruption() {
        let mut q = ScheduleQueue::default();
        q.add(item(1, 10)).expect("add");
        q.add(ScheduledSms {
            target: ScheduleTarget::WhatsApp {
                chat: "90532@s.whatsapp.net".into(),
            },
            ..item(2, 5)
        })
        .expect("add");
        assert_eq!(ScheduleQueue::from_json(&q.to_json()), q);
        for bad in ["", "{", "null", "[1]", "{\"items\":5}", "garbage"] {
            assert!(ScheduleQueue::from_json(bad).is_empty(), "{bad}");
        }
        let many: Vec<ScheduledSms> = (0..300).map(|i| item(i, i as i64)).collect();
        let json = serde_json::json!({ "items": many }).to_string();
        assert_eq!(ScheduleQueue::from_json(&json).len(), MAX_QUEUE);
    }

    // 2026-11-05 00:00:00 UTC.
    const DAY0_MS: i64 = 1_793_836_800_000;
    const TR: i32 = 3 * 3600;

    #[test]
    fn quick_times_midday() {
        // 10:00 local (UTC+3) = 07:00 UTC.
        let now = DAY0_MS + 7 * HOUR_MS;
        let t = quick_times(now, TR);
        assert_eq!(t[0], ("In 1 hour", now + HOUR_MS));
        assert_eq!(t[1], ("This evening", DAY0_MS + 15 * HOUR_MS));
        assert_eq!(
            t[2],
            ("Tomorrow morning", DAY0_MS + 24 * HOUR_MS + 6 * HOUR_MS)
        );
    }

    #[test]
    fn quick_times_across_midnight() {
        // 23:30 local on the 5th = 20:30 UTC.
        let now = DAY0_MS + 20 * HOUR_MS + 30 * 60_000;
        let t = quick_times(now, TR);
        assert_eq!(t[0].1, now + HOUR_MS);
        // 18:00 local on the 6th = 15:00 UTC.
        assert_eq!(
            t[1],
            ("Tomorrow evening", DAY0_MS + 24 * HOUR_MS + 15 * HOUR_MS)
        );
        // 09:00 local on the 6th = 06:00 UTC.
        assert_eq!(t[2].1, DAY0_MS + 24 * HOUR_MS + 6 * HOUR_MS);

        // 00:30 local on the 6th: "tomorrow" is the 7th.
        let now = DAY0_MS + 21 * HOUR_MS + 30 * 60_000;
        let t = quick_times(now, TR);
        assert_eq!(t[2].1, DAY0_MS + 48 * HOUR_MS + 6 * HOUR_MS);
        assert_eq!(t[1].0, "This evening");
    }

    #[test]
    fn quick_times_are_always_valid_and_ordered() {
        for tz in [-12 * 3600, -3600 * 5 - 1800, 0, 3 * 3600, 14 * 3600] {
            for step in 0..96 {
                let now = DAY0_MS + step * 15 * 60_000;
                let t = quick_times(now, tz);
                for (_, at) in t {
                    assert_eq!(validate(at, now, "x"), Ok(()), "tz {tz} step {step}");
                }
            }
        }
    }
}
