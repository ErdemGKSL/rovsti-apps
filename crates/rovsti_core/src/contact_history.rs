//! Calls with one contact on the card. The matching is
//! [`recents::history`](crate::recents::history) and the line text is
//! [`recents::history_text`](crate::recents::history_text); this module only
//! holds the limits, the header and the permission text.

use crate::{contacts_view::recent_when, recents::HistoryLine};

/// Calls shown on the card.
pub const CARD_LIMIT: usize = 5;
/// Newest call-log rows read to find them.
pub const PULL_LIMIT: usize = 500;

/// "3 calls", "1 call"; `None` when there are none.
pub fn summary(lines: &[HistoryLine]) -> Option<String> {
    match lines.len() {
        0 => None,
        1 => Some("1 call".to_owned()),
        n => Some(format!("{n} calls")),
    }
}

/// [`summary`] plus when the newest call was: "3 calls, last call 2 days ago".
pub fn summary_at(lines: &[HistoryLine], now_ms: i64, tz_offset_s: i32) -> Option<String> {
    let count = summary(lines)?;
    let newest = lines.iter().map(|l| l.date_ms).max()?;
    let when = recent_when(newest, now_ms, tz_offset_s);
    let when = if when == "Today" || when == "Yesterday" {
        when.to_lowercase()
    } else {
        when
    };
    Some(format!("{count}, last call {when}"))
}

/// Title and body of the call-log permission prompt.
pub fn permission_text() -> (&'static str, &'static str) {
    (
        "Show calls with this contact?",
        "Rovsti Contacts reads your call log on this phone only.",
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        call_log::{CallType, EntryRef},
        contact_dates::local_day,
    };

    const DAY_MS: i64 = 86_400_000;
    const NOW: i64 = 1_700_000_000_000;

    fn line(id: i64, date_ms: i64) -> HistoryLine {
        HistoryLine {
            entry: EntryRef::Log(id),
            blocked: false,
            call_type: CallType::Incoming,
            whatsapp: false,
            date_ms,
            duration_s: 10,
            number: "555".to_owned(),
        }
    }

    #[test]
    fn summary_counts_calls() {
        assert_eq!(summary(&[]), None);
        assert_eq!(summary(&[line(1, NOW)]).as_deref(), Some("1 call"));
        let many = [line(1, NOW), line(2, NOW), line(3, NOW)];
        assert_eq!(summary(&many).as_deref(), Some("3 calls"));
    }

    #[test]
    fn summary_at_names_the_newest_call() {
        assert_eq!(summary_at(&[], NOW, 0), None);
        let lines = [line(1, NOW - 2 * DAY_MS), line(2, NOW - 4 * DAY_MS)];
        assert_eq!(local_day(NOW, 0) - local_day(NOW - 2 * DAY_MS, 0), 2);
        assert_eq!(
            summary_at(&lines, NOW, 0).as_deref(),
            Some("2 calls, last call 2 days ago")
        );
        assert_eq!(
            summary_at(&[line(1, NOW)], NOW, 0).as_deref(),
            Some("1 call, last call today")
        );
        assert_eq!(
            summary_at(&[line(1, NOW - DAY_MS)], NOW, 0).as_deref(),
            Some("1 call, last call yesterday")
        );
    }

    #[test]
    fn permission_text_is_fixed() {
        let (title, body) = permission_text();
        assert_eq!(title, "Show calls with this contact?");
        assert!(body.contains("this phone only"));
    }
}
