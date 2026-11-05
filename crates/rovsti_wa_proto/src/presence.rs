//! Presence lines and the latest presence seen per peer.

use std::collections::VecDeque;

use crate::event::GatewayEvent;

/// Peers remembered by a [`PresenceBook`].
pub const BOOK_CAP: usize = 256;

/// `HH:MM` of a timestamp at `offset_s` from UTC.
pub fn clock(ms: i64, offset_s: i32) -> String {
    let secs = (ms.div_euclid(1000) + i64::from(offset_s)).rem_euclid(86_400);
    format!("{:02}:{:02}", secs / 3600, secs % 3600 / 60)
}

/// The header line for a peer's presence.
pub fn presence_label(
    available: bool,
    last_seen_ms: Option<i64>,
    now: i64,
    offset_s: i32,
) -> Option<String> {
    if available {
        return Some("online".to_owned());
    }
    let ts = last_seen_ms?;
    let day_start = ts - (ts.div_euclid(1000) + i64::from(offset_s)).rem_euclid(86_400) * 1000;
    let when = match day_label(day_start, now, offset_s).as_str() {
        "Today" => format!("today at {}", clock(ts, offset_s)),
        "Yesterday" => format!("yesterday at {}", clock(ts, offset_s)),
        date => format!("{date} at {}", clock(ts, offset_s)),
    };
    Some(format!("last seen {when}"))
}

/// Days since 1970-01-01 as (year, month 1..=12, day).
pub fn civil_from_days(days: i64) -> (i64, usize, i64) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    (year, usize::try_from(month).unwrap_or(1), day)
}

/// The separator above a day: "Today", "Yesterday" or "5 Nov 2026".
pub fn day_label(day_start_ms: i64, now_ms: i64, offset_s: i32) -> String {
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    let day_of = |ms: i64| (ms.div_euclid(1000) + i64::from(offset_s)).div_euclid(86_400);
    let day = day_of(day_start_ms);
    match day_of(now_ms) - day {
        0 => "Today".to_owned(),
        1 => "Yesterday".to_owned(),
        _ => {
            let (year, month, d) = civil_from_days(day);
            format!("{d} {} {year}", MONTHS[month - 1])
        }
    }
}

/// The latest presence per peer, bounded: when full, the peer that was
/// updated longest ago is dropped.
#[derive(Clone, Debug, Default)]
pub struct PresenceBook {
    /// Oldest first; `(jid, available, last_seen_ms)`.
    entries: VecDeque<(String, bool, Option<i64>)>,
}

impl PresenceBook {
    pub fn new() -> Self {
        Self::default()
    }

    /// Records a presence event; other events are ignored.
    pub fn apply(&mut self, event: &GatewayEvent, _now_ms: i64) {
        let GatewayEvent::Presence {
            jid,
            available,
            last_seen_ms,
        } = event
        else {
            return;
        };
        if let Some(at) = self.entries.iter().position(|(j, ..)| j == jid) {
            self.entries.remove(at);
        }
        while self.entries.len() >= BOOK_CAP {
            self.entries.pop_front();
        }
        self.entries
            .push_back((jid.clone(), *available, *last_seen_ms));
    }

    /// `(available, last_seen_ms)` of the latest event for `jid`.
    pub fn get(&self, jid: &str) -> Option<(bool, Option<i64>)> {
        self.entries
            .iter()
            .rev()
            .find(|(j, ..)| j == jid)
            .map(|(_, available, seen)| (*available, *seen))
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const T: i64 = 1_793_887_500_000;

    fn presence(jid: &str, available: bool, seen: Option<i64>) -> GatewayEvent {
        GatewayEvent::Presence {
            jid: jid.to_owned(),
            available,
            last_seen_ms: seen,
        }
    }

    #[test]
    fn presence_lines() {
        let day = T - T.rem_euclid(86_400_000);
        assert_eq!(presence_label(true, None, T, 0).as_deref(), Some("online"));
        assert_eq!(presence_label(false, None, T, 0), None);
        assert_eq!(
            presence_label(false, Some(T - 3_600_000), T, 0).as_deref(),
            Some("last seen today at 13:05")
        );
        assert_eq!(
            presence_label(false, Some(day - 1_000), T, 0).as_deref(),
            Some("last seen yesterday at 23:59")
        );
        assert_eq!(
            presence_label(false, Some(day - 3 * 86_400_000 + 60_000), T, 0).as_deref(),
            Some("last seen 2 Nov 2026 at 00:01")
        );
    }

    #[test]
    fn book_keeps_the_latest_event() {
        let mut book = PresenceBook::new();
        assert!(book.is_empty());
        book.apply(&presence("a", true, None), T);
        book.apply(&presence("a", false, Some(5)), T);
        assert_eq!(book.get("a"), Some((false, Some(5))));
        assert_eq!(book.len(), 1);
        assert_eq!(book.get("b"), None);
        book.apply(
            &GatewayEvent::Typing {
                chat: "a".into(),
                jid: "a".into(),
                state: crate::api::TypingState::Typing,
            },
            T,
        );
        assert_eq!(book.len(), 1, "other events are ignored");
    }

    #[test]
    fn book_is_bounded_and_drops_the_oldest() {
        let mut book = PresenceBook::new();
        for i in 0..BOOK_CAP + 10 {
            book.apply(&presence(&format!("j{i}"), true, None), T);
        }
        assert_eq!(book.len(), BOOK_CAP);
        assert_eq!(book.get("j0"), None);
        assert_eq!(book.get("j9"), None);
        assert!(book.get("j10").is_some());
        // Refreshing an old entry keeps it past newer ones.
        book.apply(&presence("j10", false, Some(1)), T);
        book.apply(&presence("new", true, None), T);
        assert_eq!(book.get("j10"), Some((false, Some(1))));
        assert_eq!(book.get("j11"), None);
    }
}
