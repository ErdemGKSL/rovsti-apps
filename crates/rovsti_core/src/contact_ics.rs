//! Birthdays and anniversaries as an iCalendar file (RFC 5545), so they can
//! be opened in any calendar app. Every event is an all-day, yearly one that
//! does not block time. Everything here is pure.

use crate::call_log::civil_from_days;
use crate::contact_dates;
use crate::contact_edit::{ContactDraft, EventKind};

/// Year used for DTSTART when the card has none: a leap year, so 29 Feb works.
const BASE_YEAR: i32 = 1972;
/// Longest content line in octets, line break excluded.
const LINE_OCTETS: usize = 75;

/// One yearly all-day event.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IcsEvent {
    pub uid: String,
    pub summary: String,
    /// `(month, day)`.
    pub md: (u8, u8),
    pub year: Option<i32>,
}

/// The events of `drafts`: birthdays, anniversaries and custom dates. Dates
/// that do not parse and nameless contacts are skipped, and so is an event
/// that repeats an earlier uid.
pub fn events_of(drafts: &[ContactDraft]) -> Vec<IcsEvent> {
    let mut out: Vec<IcsEvent> = Vec::new();
    for draft in drafts {
        let name = draft.display_name();
        if name.is_empty() {
            continue;
        }
        for event in &draft.events {
            let Some(date) = contact_dates::parse(&event.date) else {
                continue;
            };
            let (slug, what) = match event.kind {
                EventKind::Birthday => ("birthday", "birthday".to_owned()),
                EventKind::Anniversary => ("anniversary", "anniversary".to_owned()),
                _ => ("other", event.title()),
            };
            let md = (date.md.month as u8, date.md.day as u8);
            let uid = format!(
                "rovsti-{}-{slug}-{:02}{:02}@contacts.local",
                draft.id.unwrap_or(0),
                md.0,
                md.1
            );
            if out.iter().any(|e| e.uid == uid) {
                continue;
            }
            out.push(IcsEvent {
                uid,
                summary: format!("{name}'s {what}"),
                md,
                year: date.year,
            });
        }
    }
    out
}

/// The calendar text, CRLF separated. `stamp_ms` (UTC milliseconds) is the
/// creation time written as DTSTAMP.
pub fn write(events: &[IcsEvent], stamp_ms: i64) -> String {
    let secs = stamp_ms.div_euclid(1000);
    let (y, m, d) = civil_from_days(secs.div_euclid(86_400));
    let sod = secs.rem_euclid(86_400);
    let stamp = format!(
        "{y:04}{m:02}{d:02}T{:02}{:02}{:02}Z",
        sod / 3600,
        sod % 3600 / 60,
        sod % 60
    );
    let mut out = String::new();
    push(&mut out, "BEGIN:VCALENDAR");
    push(&mut out, "VERSION:2.0");
    push(&mut out, "PRODID:-//Rovsti//Contacts//EN");
    for e in events {
        let year = e.year.unwrap_or(BASE_YEAR);
        push(&mut out, "BEGIN:VEVENT");
        push(&mut out, &format!("UID:{}", escape(&e.uid)));
        push(&mut out, &format!("DTSTAMP:{stamp}"));
        push(
            &mut out,
            &format!("DTSTART;VALUE=DATE:{year:04}{:02}{:02}", e.md.0, e.md.1),
        );
        if e.md == (2, 29) {
            push(&mut out, "RRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1");
        } else {
            push(&mut out, "RRULE:FREQ=YEARLY");
        }
        push(&mut out, &format!("SUMMARY:{}", escape(&e.summary)));
        push(&mut out, "TRANSP:TRANSPARENT");
        push(&mut out, "END:VEVENT");
    }
    push(&mut out, "END:VCALENDAR");
    out
}

/// `rovsti-birthdays-<n>-YYYY-MM-DD.ics`.
pub fn file_name(n: usize, today: (i32, u8, u8)) -> String {
    format!(
        "rovsti-birthdays-{n}-{:04}-{:02}-{:02}.ics",
        today.0, today.1, today.2
    )
}

/// "Saved 3 dates to the calendar file".
pub fn saved_notice(n: usize) -> String {
    if n == 1 {
        "Saved 1 date to the calendar file".to_owned()
    } else {
        format!("Saved {n} dates to the calendar file")
    }
}

/// TEXT escaping: backslash, comma, semicolon and line breaks.
fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            ',' => out.push_str("\\,"),
            ';' => out.push_str("\\;"),
            '\n' => out.push_str("\\n"),
            '\r' => {}
            c => out.push(c),
        }
    }
    out
}

/// Appends `line` folded at 75 octets (on character boundaries) and CRLF.
fn push(out: &mut String, line: &str) {
    let mut used = 0;
    let mut limit = LINE_OCTETS;
    for c in line.chars() {
        let n = c.len_utf8();
        if used + n > limit {
            out.push_str("\r\n ");
            used = 1;
            limit = LINE_OCTETS;
        }
        out.push(c);
        used += n;
    }
    out.push_str("\r\n");
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::contact_edit::DraftEvent;

    fn person(id: i64, given: &str, events: &[(&str, EventKind)]) -> ContactDraft {
        ContactDraft {
            id: Some(id),
            given: given.into(),
            family: "Yılmaz".into(),
            events: events
                .iter()
                .map(|(d, k)| DraftEvent::new(*d, *k))
                .collect(),
            ..ContactDraft::default()
        }
    }

    // 2024-01-02 03:04:05 UTC.
    const STAMP: i64 = 1_704_164_645_000;

    #[test]
    fn birthday_with_year_golden() {
        let events = events_of(&[person(7, "Ayşe", &[("1990-03-07", EventKind::Birthday)])]);
        assert_eq!(events[0].uid, "rovsti-7-birthday-0307@contacts.local");
        assert_eq!(
            write(&events, STAMP),
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Rovsti//Contacts//EN\r\n\
BEGIN:VEVENT\r\nUID:rovsti-7-birthday-0307@contacts.local\r\nDTSTAMP:20240102T030405Z\r\n\
DTSTART;VALUE=DATE:19900307\r\nRRULE:FREQ=YEARLY\r\n\
SUMMARY:Ayşe Yılmaz's birthday\r\nTRANSP:TRANSPARENT\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        );
    }

    #[test]
    fn unknown_year_uses_a_leap_base_year() {
        let events = events_of(&[person(1, "Can", &[("--12-25", EventKind::Anniversary)])]);
        let text = write(&events, STAMP);
        assert!(text.contains("DTSTART;VALUE=DATE:19721225\r\n"));
        assert!(text.contains("SUMMARY:Can Yılmaz's anniversary\r\n"));
        assert!(text.contains("RRULE:FREQ=YEARLY\r\n"));
    }

    #[test]
    fn leap_day_repeats_on_the_last_day_of_february() {
        let events = events_of(&[person(1, "Can", &[("--02-29", EventKind::Birthday)])]);
        let text = write(&events, STAMP);
        assert!(text.contains("DTSTART;VALUE=DATE:19720229\r\n"));
        assert!(text.contains("RRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1\r\n"));
    }

    #[test]
    fn escapes_text() {
        let mut d = person(1, "A,B;C\\D", &[("--01-02", EventKind::Birthday)]);
        d.family = String::new();
        let text = write(&events_of(&[d]), STAMP);
        assert!(text.contains("SUMMARY:A\\,B\\;C\\\\D's birthday\r\n"));
    }

    #[test]
    fn custom_events_use_their_title_and_bad_dates_are_skipped() {
        let mut d = person(
            3,
            "Ece",
            &[
                ("--05-06", EventKind::Custom),
                ("garbage", EventKind::Birthday),
            ],
        );
        d.events[0].label = Some("Graduation".into());
        let events = events_of(&[d, ContactDraft::default()]);
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].summary, "Ece Yılmaz's Graduation");
        assert_eq!(events[0].uid, "rovsti-3-other-0506@contacts.local");
    }

    #[test]
    fn long_lines_fold_on_character_boundaries() {
        let mut d = person(1, &"Çağrı ".repeat(20), &[("--01-02", EventKind::Birthday)]);
        d.family = String::new();
        let text = write(&events_of(&[d]), STAMP);
        for line in text.split("\r\n") {
            assert!(line.len() <= LINE_OCTETS, "{line}");
        }
        assert!(text.contains("\r\n "));
        let unfolded = text.replace("\r\n ", "");
        assert!(unfolded.contains(&format!(
            "SUMMARY:{}'s birthday\r\n",
            "Çağrı ".repeat(20).trim()
        )));
    }

    #[test]
    fn empty_calendar_and_names() {
        assert_eq!(
            write(&[], STAMP),
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Rovsti//Contacts//EN\r\nEND:VCALENDAR\r\n"
        );
        assert_eq!(
            file_name(3, (2026, 3, 7)),
            "rovsti-birthdays-3-2026-03-07.ics"
        );
        assert_eq!(saved_notice(1), "Saved 1 date to the calendar file");
        assert_eq!(saved_notice(4), "Saved 4 dates to the calendar file");
    }
}
