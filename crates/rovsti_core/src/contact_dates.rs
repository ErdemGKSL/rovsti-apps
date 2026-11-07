//! Dates of a contact: birthdays, anniversaries and the upcoming list.
//!
//! Dates are kept as text on [`DraftEvent`] (`YYYY-MM-DD`, or `--MM-DD` when
//! the year is unknown, which is also what Android stores). This module parses
//! the forms that show up in vCards and the system database, does the calendar
//! maths without any time zone library, and builds the texts the UI shows.
//! Everything is pure.

use crate::call_log::{civil_from_days, days_from_civil};
use crate::contact_edit::{ContactDraft, DraftEvent, EventKind};

const SECS_PER_DAY: i64 = 86_400;
const MONTHS: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

/// A month and a day, without a year.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct Md {
    pub month: u32,
    pub day: u32,
}

/// A parsed event date; `year` is `None` when it is not known.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct EventDate {
    pub year: Option<i32>,
    pub md: Md,
}

impl EventDate {
    /// The form Android and the editor store: `YYYY-MM-DD` or `--MM-DD`.
    pub fn to_android(self) -> String {
        match self.year {
            Some(y) => format!("{y:04}-{:02}-{:02}", self.md.month, self.md.day),
            None => format!("--{:02}-{:02}", self.md.month, self.md.day),
        }
    }

    /// "7 Mar 1990" or "7 Mar".
    pub fn text(self) -> String {
        let month = MONTHS[(self.md.month as usize).saturating_sub(1).min(11)];
        match self.year {
            Some(y) => format!("{} {month} {y}", self.md.day),
            None => format!("{} {month}", self.md.day),
        }
    }
}

fn is_leap(y: i64) -> bool {
    (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}

fn days_in_month(y: Option<i64>, m: u32) -> u32 {
    match m {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if y.is_none_or(is_leap) => 29,
        2 => 28,
        _ => 0,
    }
}

fn digits(s: &str, len: usize) -> Option<u32> {
    (s.len() == len && s.bytes().all(|b| b.is_ascii_digit()))
        .then(|| s.parse().ok())
        .flatten()
}

/// Parses `YYYY-MM-DD`, `YYYYMMDD`, `--MM-DD`, `--MMDD`, or any of the full
/// forms followed by a time (`1990-03-07T10:00:00Z`, `1990-03-07 10:00`).
/// Impossible days (30 Feb, 31 Apr, 29 Feb in a non-leap year) are refused.
pub fn parse(text: &str) -> Option<EventDate> {
    let text = text.trim();
    let date = text.split(['T', 't', ' ']).next().unwrap_or(text);
    let (year, month, day) = if let Some(rest) = date.strip_prefix("--") {
        let (m, d) = match rest.split_once('-') {
            Some((m, d)) => (digits(m, 2)?, digits(d, 2)?),
            None => (digits(rest.get(..2)?, 2)?, digits(rest.get(2..)?, 2)?),
        };
        (None, m, d)
    } else if let Some((y, rest)) = date.split_once('-') {
        let (m, d) = rest.split_once('-')?;
        (Some(digits(y, 4)?), digits(m, 2)?, digits(d, 2)?)
    } else {
        (
            Some(digits(date.get(..4)?, 4)?),
            digits(date.get(4..6)?, 2)?,
            digits(date.get(6..)?, 2)?,
        )
    };
    if year == Some(0) || day == 0 || day > days_in_month(year.map(i64::from), month) {
        return None;
    }
    Some(EventDate {
        year: year.map(|y| y as i32),
        md: Md { month, day },
    })
}

/// Days since 1970-01-01 of the first occurrence of `md` on or after `from`.
/// 29 February falls on 28 February in years that are not leap years.
pub fn next_occurrence(md: Md, from: i64) -> i64 {
    let (year, _, _) = civil_from_days(from);
    (year..=year + 1)
        .map(|y| occurrence_in(md, y))
        .find(|d| *d >= from)
        .unwrap_or(from)
}

fn occurrence_in(md: Md, year: i64) -> i64 {
    let day = if md.month == 2 && md.day == 29 && !is_leap(year) {
        28
    } else {
        md.day
    };
    days_from_civil(year, md.month, day)
}

/// Days from `today` (days since epoch) until the next `md`; 0 when it is today.
pub fn days_until(md: Md, today: i64) -> i64 {
    next_occurrence(md, today) - today
}

/// Completed years on day `on` (days since epoch), or `None` when the year is
/// unknown or `on` is before the date.
pub fn age_on(date: EventDate, on: i64) -> Option<i64> {
    let (y, m, d) = civil_from_days(on);
    let age = y - i64::from(date.year?);
    let reached = (m, d) >= (date.md.month, date.md.day)
        || (date.md.month == 2 && date.md.day == 29 && !is_leap(y) && (m, d) >= (2, 28));
    let age = if reached { age } else { age - 1 };
    (age >= 0).then_some(age)
}

/// One row of the upcoming list.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Upcoming {
    pub contact_id: i64,
    pub name: String,
    pub kind: EventKind,
    pub in_days: i64,
    pub date_text: String,
    /// The age (or years together) reached on that day, when the year is known.
    pub turning: Option<i64>,
}

/// Events of `drafts` (with an id) that fall within `within_days` days from
/// today, soonest first, then by name. `today_ms` is a UTC timestamp and
/// `tz_offset_s` the local offset in seconds. Unreadable dates are skipped.
pub fn upcoming(
    drafts: &[ContactDraft],
    today_ms: i64,
    tz_offset_s: i32,
    within_days: i64,
) -> Vec<Upcoming> {
    let today = (today_ms.div_euclid(1000) + i64::from(tz_offset_s)).div_euclid(SECS_PER_DAY);
    let mut out = Vec::new();
    for d in drafts {
        let Some(contact_id) = d.id else { continue };
        for e in &d.events {
            let Some(date) = parse(&e.date) else { continue };
            let in_days = days_until(date.md, today);
            if in_days > within_days {
                continue;
            }
            out.push(Upcoming {
                contact_id,
                name: d.display_name(),
                kind: e.kind,
                in_days,
                date_text: date.text(),
                turning: age_on(date, today + in_days),
            });
        }
    }
    out.sort_by(|a, b| {
        a.in_days
            .cmp(&b.in_days)
            .then_with(|| crate::search::fold(&a.name).cmp(&crate::search::fold(&b.name)))
            .then_with(|| a.contact_id.cmp(&b.contact_id))
    });
    out
}

/// "Today", "Tomorrow" or "In 5 days".
pub fn when_text(in_days: i64) -> String {
    match in_days {
        i64::MIN..=0 => "Today".to_owned(),
        1 => "Tomorrow".to_owned(),
        n => format!("In {n} days"),
    }
}

/// The line for an event on a contact card: "Birthday · 7 Mar 1990 · In 5 days".
/// `None` when the date is unreadable.
pub fn card_line(event: &DraftEvent, today_ms: i64, tz_offset_s: i32) -> Option<String> {
    let date = parse(&event.date)?;
    let today = (today_ms.div_euclid(1000) + i64::from(tz_offset_s)).div_euclid(SECS_PER_DAY);
    Some(format!(
        "{} · {} · {}",
        event.title(),
        date.text(),
        when_text(days_until(date.md, today))
    ))
}

fn ordinal(n: i64) -> String {
    let suffix = match (n % 100, n % 10) {
        (11..=13, _) => "th",
        (_, 1) => "st",
        (_, 2) => "nd",
        (_, 3) => "rd",
        _ => "th",
    };
    format!("{n}{suffix}")
}

/// A greeting to send: "Happy 34th birthday, Ayşe!".
pub fn wish_text(name: &str, kind: EventKind, turning: Option<i64>) -> String {
    let first = name.split_whitespace().next().unwrap_or("");
    let to = if first.is_empty() {
        String::new()
    } else {
        format!(", {first}")
    };
    let n = turning.filter(|n| *n > 0).map(ordinal);
    match (kind, n) {
        (EventKind::Birthday, Some(n)) => format!("Happy {n} birthday{to}!"),
        (EventKind::Birthday, None) => format!("Happy birthday{to}!"),
        (EventKind::Anniversary, Some(n)) => format!("Happy {n} anniversary{to}!"),
        (EventKind::Anniversary, None) => format!("Happy anniversary{to}!"),
        _ => format!("Thinking of you{to}!"),
    }
}

/// A `geo:` link that opens `address` in any maps app, or `None` for a blank
/// address. Lines are joined with commas and the query is percent-encoded.
pub fn maps_url(address: &str) -> Option<String> {
    let line = address
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .collect::<Vec<_>>()
        .join(", ");
    if line.is_empty() {
        return None;
    }
    let mut out = String::from("geo:0,0?q=");
    for b in line.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ymd(y: i32, m: u32, d: u32) -> EventDate {
        EventDate {
            year: Some(y),
            md: Md { month: m, day: d },
        }
    }

    fn day(y: i64, m: u32, d: u32) -> i64 {
        days_from_civil(y, m, d)
    }

    fn ms(y: i64, m: u32, d: u32, h: i64) -> i64 {
        (day(y, m, d) * SECS_PER_DAY + h * 3600) * 1000
    }

    #[test]
    fn every_date_form_is_accepted() {
        assert_eq!(parse("1990-03-07"), Some(ymd(1990, 3, 7)));
        assert_eq!(parse("19900307"), Some(ymd(1990, 3, 7)));
        assert_eq!(parse(" 1990-03-07T10:00:00Z "), Some(ymd(1990, 3, 7)));
        assert_eq!(parse("1990-03-07 10:00"), Some(ymd(1990, 3, 7)));
        let no_year = EventDate {
            year: None,
            md: Md { month: 3, day: 7 },
        };
        assert_eq!(parse("--03-07"), Some(no_year));
        assert_eq!(parse("--0307"), Some(no_year));
    }

    #[test]
    fn bad_dates_are_rejected() {
        for bad in [
            "",
            "x",
            "1990-02-30",
            "1990-04-31",
            "1990-13-01",
            "1990-00-10",
            "1990-01-00",
            "2023-02-29",
            "--02-30",
            "--13-01",
            "1990-3-7",
            "199003",
            "0000-01-01",
            "--0",
            "ü",
        ] {
            assert_eq!(parse(bad), None, "{bad}");
        }
        assert!(parse("2024-02-29").is_some());
        assert!(parse("--02-29").is_some());
        assert_eq!(parse("1900-02-29"), None);
        assert!(parse("2000-02-29").is_some());
    }

    #[test]
    fn android_form_round_trips() {
        assert_eq!(
            parse("19900307").map(EventDate::to_android).as_deref(),
            Some("1990-03-07")
        );
        assert_eq!(
            parse("--0307").map(EventDate::to_android).as_deref(),
            Some("--03-07")
        );
        assert_eq!(ymd(1990, 3, 7).text(), "7 Mar 1990");
        assert_eq!(parse("--12-25").expect("date").text(), "25 Dec");
    }

    #[test]
    fn leap_day_moves_to_28_feb_in_common_years() {
        let md = Md { month: 2, day: 29 };
        assert_eq!(next_occurrence(md, day(2023, 1, 1)), day(2023, 2, 28));
        assert_eq!(next_occurrence(md, day(2024, 1, 1)), day(2024, 2, 29));
        assert_eq!(next_occurrence(md, day(2024, 3, 1)), day(2025, 2, 28));
        assert_eq!(next_occurrence(md, day(2027, 3, 1)), day(2028, 2, 29));
    }

    #[test]
    fn days_until_handles_today_and_year_end() {
        let md = Md { month: 1, day: 2 };
        assert_eq!(days_until(md, day(2025, 12, 30)), 3);
        assert_eq!(days_until(md, day(2026, 1, 2)), 0);
        assert_eq!(days_until(md, day(2026, 1, 3)), 364);
        assert_eq!(days_until(Md { month: 12, day: 31 }, day(2024, 12, 31)), 0);
        assert_eq!(days_until(Md { month: 1, day: 1 }, day(2024, 12, 31)), 1);
    }

    #[test]
    fn age_with_and_without_a_year() {
        let d = ymd(1990, 3, 7);
        assert_eq!(age_on(d, day(2024, 3, 6)), Some(33));
        assert_eq!(age_on(d, day(2024, 3, 7)), Some(34));
        assert_eq!(age_on(d, day(1990, 3, 7)), Some(0));
        assert_eq!(age_on(d, day(1989, 1, 1)), None);
        assert_eq!(
            age_on(parse("--03-07").expect("date"), day(2024, 3, 7)),
            None
        );
        let leap = ymd(2000, 2, 29);
        assert_eq!(age_on(leap, day(2023, 2, 27)), Some(22));
        assert_eq!(age_on(leap, day(2023, 2, 28)), Some(23));
    }

    fn person(id: i64, given: &str, events: &[(&str, EventKind)]) -> ContactDraft {
        ContactDraft {
            id: Some(id),
            given: given.into(),
            events: events
                .iter()
                .map(|(d, k)| DraftEvent::new(*d, *k))
                .collect(),
            ..ContactDraft::default()
        }
    }

    #[test]
    fn upcoming_is_sorted_and_windowed() {
        let drafts = [
            person(1, "Zed", &[("1990-03-10", EventKind::Birthday)]),
            person(2, "Ayşe", &[("--03-10", EventKind::Birthday)]),
            person(
                3,
                "Bob",
                &[
                    ("2015-03-08", EventKind::Anniversary),
                    ("bad", EventKind::Birthday),
                ],
            ),
            person(4, "Far", &[("--09-01", EventKind::Birthday)]),
            ContactDraft {
                given: "NoId".into(),
                events: vec![DraftEvent::new("--03-08", EventKind::Birthday)],
                ..ContactDraft::default()
            },
        ];
        let list = upcoming(&drafts, ms(2024, 3, 8, 12), 0, 30);
        let ids: Vec<i64> = list.iter().map(|u| u.contact_id).collect();
        assert_eq!(ids, [3, 2, 1]);
        assert_eq!(list[0].in_days, 0);
        assert_eq!(list[0].turning, Some(9));
        assert_eq!(list[1].turning, None);
        assert_eq!(list[2].turning, Some(34));
        assert_eq!(list[2].date_text, "10 Mar 1990");
        assert_eq!(upcoming(&drafts, ms(2024, 3, 8, 12), 0, 1).len(), 1);
    }

    #[test]
    fn upcoming_follows_the_local_time_zone() {
        let drafts = [person(1, "A", &[("--03-09", EventKind::Birthday)])];
        // 23:00 UTC on 8 March is already 9 March at UTC+3.
        let now = ms(2024, 3, 8, 23);
        assert_eq!(upcoming(&drafts, now, 0, 5)[0].in_days, 1);
        assert_eq!(upcoming(&drafts, now, 3 * 3600, 5)[0].in_days, 0);
        assert_eq!(upcoming(&drafts, now, -5 * 3600, 5)[0].in_days, 1);
    }

    #[test]
    fn upcoming_ties_sort_by_name() {
        let drafts = [
            person(1, "Zed", &[("--03-10", EventKind::Birthday)]),
            person(2, "Ada", &[("--03-10", EventKind::Birthday)]),
        ];
        let list = upcoming(&drafts, ms(2024, 3, 1, 0), 0, 30);
        assert_eq!(list[0].name, "Ada");
    }

    #[test]
    fn when_and_card_lines() {
        assert_eq!(when_text(0), "Today");
        assert_eq!(when_text(1), "Tomorrow");
        assert_eq!(when_text(5), "In 5 days");
        let e = DraftEvent::new("1990-03-10", EventKind::Birthday);
        assert_eq!(
            card_line(&e, ms(2024, 3, 8, 0), 0).as_deref(),
            Some("Birthday · 10 Mar 1990 · In 2 days")
        );
        assert_eq!(
            card_line(&DraftEvent::new("nope", EventKind::Birthday), 0, 0),
            None
        );
    }

    #[test]
    fn wishes_read_well() {
        assert_eq!(
            wish_text("Ayşe Yılmaz", EventKind::Birthday, Some(34)),
            "Happy 34th birthday, Ayşe!"
        );
        assert_eq!(
            wish_text("Ayşe", EventKind::Birthday, None),
            "Happy birthday, Ayşe!"
        );
        assert_eq!(
            wish_text("Bob", EventKind::Anniversary, Some(1)),
            "Happy 1st anniversary, Bob!"
        );
        assert_eq!(
            wish_text("Bob", EventKind::Anniversary, Some(12)),
            "Happy 12th anniversary, Bob!"
        );
        assert_eq!(
            wish_text("Bob", EventKind::Anniversary, Some(22)),
            "Happy 22nd anniversary, Bob!"
        );
        assert_eq!(
            wish_text("", EventKind::Birthday, Some(0)),
            "Happy birthday!"
        );
        assert_eq!(
            wish_text("Bob", EventKind::Custom, None),
            "Thinking of you, Bob!"
        );
    }

    #[test]
    fn maps_url_encodes_one_line() {
        assert_eq!(maps_url("  \n "), None);
        assert_eq!(
            maps_url("1 Main St\nIstanbul").as_deref(),
            Some("geo:0,0?q=1%20Main%20St%2C%20Istanbul")
        );
        assert_eq!(
            maps_url("Café & Bar\r\n\r\nŞişli").as_deref(),
            Some("geo:0,0?q=Caf%C3%A9%20%26%20Bar%2C%20%C5%9Ei%C5%9Fli")
        );
        assert_eq!(
            maps_url("a=b?c#d").as_deref(),
            Some("geo:0,0?q=a%3Db%3Fc%23d")
        );
    }
}
