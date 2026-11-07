//! Finds dates and times in message text ("tomorrow 15:00", "12.11.2026").

use std::ops::Range;

use crate::call_log::{MONTHS_SHORT, civil_from_days, days_from_civil};

const DAY_MS: i64 = 86_400_000;
const WEEKDAYS_SHORT: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FoundDate {
    /// Byte range of the matched text in the body.
    pub span: Range<usize>,
    /// UTC milliseconds of the start (local midnight when `all_day`).
    pub start_ms: i64,
    pub all_day: bool,
    /// `Thu 12 Nov, 15:00` or `Thu 12 Nov 2026` (year when not the current one).
    pub label: String,
}

/// End of the calendar event: one hour after the start, one day when all day.
pub fn calendar_end_ms(found: &FoundDate) -> i64 {
    found.start_ms + if found.all_day { DAY_MS } else { 3_600_000 }
}

/// Calendar event title: `{chat}: {first 40 chars of the message}`, one line.
pub fn event_title(chat: &str, text: &str) -> String {
    let flat: String = text.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut head: String = flat.chars().take(40).collect();
    if flat.chars().count() > 40 {
        head.push('…');
    }
    let chat = chat.trim();
    match (chat.is_empty(), head.is_empty()) {
        (true, _) => head,
        (false, true) => chat.to_owned(),
        (false, false) => format!("{chat}: {head}"),
    }
}

/// The matched date text of `found` in `body` (what "Copy date" copies).
pub fn matched_text<'a>(body: &'a str, found: &FoundDate) -> &'a str {
    body.get(found.span.clone()).unwrap_or_default()
}

/// Up to `max` dates in `body`, in order.
pub fn detect(body: &str, now_ms: i64, tz_offset_s: i32, max: usize) -> Vec<FoundDate> {
    let today = (now_ms.div_euclid(1000) + i64::from(tz_offset_s)).div_euclid(86_400);
    let mut out = Vec::new();

    let mut prev: Option<char> = None;
    let mut skip_to = 0;
    for (i, c) in body.char_indices() {
        let before = prev.replace(c);
        if out.len() >= max {
            break;
        }
        if i < skip_to {
            continue;
        }
        let found = if c.is_ascii_digit() && !before.is_some_and(is_glue) {
            numeric_date(body, i).map(|(day, end)| (day, end, None))
        } else if c.is_alphabetic() && !before.is_some_and(char::is_alphanumeric) {
            day_word(body, i, today)
        } else {
            None
        };
        let Some((day, end, default_secs)) = found else {
            continue;
        };
        let (secs, end) = match time_after(body, end) {
            Some((secs, e)) => (Some(secs), e),
            None => (default_secs, end),
        };
        let start_ms = (day * 86_400 + secs.unwrap_or(0) - i64::from(tz_offset_s)) * 1000;
        skip_to = end;
        if start_ms < now_ms - DAY_MS {
            continue;
        }
        out.push(FoundDate {
            span: i..end,
            start_ms,
            all_day: secs.is_none(),
            label: label(day, secs, today),
        });
    }
    out
}

/// Characters that make a following digit part of another token.
fn is_glue(c: char) -> bool {
    c.is_alphanumeric() || matches!(c, '.' | '/' | '-' | ':' | ',')
}

fn digits(b: &[u8], at: usize, min: usize, max: usize) -> Option<(u32, usize)> {
    let mut n = 0_u32;
    let mut len = 0;
    while len < max && b.get(at + len).is_some_and(u8::is_ascii_digit) {
        n = n * 10 + u32::from(b[at + len] - b'0');
        len += 1;
    }
    (len >= min).then_some((n, at + len))
}

fn valid(y: u32, m: u32, d: u32) -> Option<i64> {
    if !(2000..=2100).contains(&y) || !(1..=12).contains(&m) || d == 0 {
        return None;
    }
    let day = days_from_civil(i64::from(y), m, d);
    (civil_from_days(day) == (i64::from(y), m, d)).then_some(day)
}

/// `d.m.yyyy`, `d/m/yyyy` or `yyyy-mm-dd` at `at`: (day number, end offset).
fn numeric_date(body: &str, at: usize) -> Option<(i64, usize)> {
    let b = body.as_bytes();
    let (first, p) = digits(b, at, 1, 4)?;
    let (day, end) = if p - at == 4 {
        if b.get(p) != Some(&b'-') {
            return None;
        }
        let (m, p) = digits(b, p + 1, 2, 2)?;
        if b.get(p) != Some(&b'-') {
            return None;
        }
        let (d, p) = digits(b, p + 1, 2, 2)?;
        (valid(first, m, d)?, p)
    } else {
        let sep = *b.get(p)?;
        if sep != b'.' && sep != b'/' {
            return None;
        }
        let (m, p2) = digits(b, p + 1, 1, 2)?;
        if b.get(p2) != Some(&sep) {
            return None;
        }
        let (y, p3) = digits(b, p2 + 1, 4, 4)?;
        (valid(y, m, first)?, p3)
    };
    // Not the start of a longer number or another date.
    if b.get(end).is_some_and(|c| c.is_ascii_digit()) {
        return None;
    }
    Some((day, end))
}

/// `today`, `tomorrow`, `tonight`, `bugün`, `yarın` at `at`.
fn day_word(body: &str, at: usize, today: i64) -> Option<(i64, usize, Option<i64>)> {
    let len = body[at..]
        .find(|c: char| !c.is_alphabetic())
        .unwrap_or(body.len() - at);
    let word = body[at..at + len].to_lowercase();
    let (offset, secs) = match word.as_str() {
        "today" | "bugün" | "bugun" => (0, None),
        "tomorrow" | "yarın" | "yarin" => (1, None),
        "tonight" => (0, Some(20 * 3600)),
        _ => return None,
    };
    Some((today + offset, at + len, secs))
}

/// Optional `HH:MM`, `at HH:MM` or `saat HH:MM` after `from`: (seconds, end).
fn time_after(body: &str, from: usize) -> Option<(i64, usize)> {
    let b = body.as_bytes();
    let mut p = from;
    let skip_ws = |mut p: usize| {
        while b.get(p).is_some_and(|c| matches!(c, b' ' | b'\t' | b',')) {
            p += 1;
        }
        p
    };
    p = skip_ws(p);
    if p == from && b.get(p).is_some_and(u8::is_ascii_digit) {
        return None;
    }
    for prefix in ["at", "saat"] {
        let rest = &body[p..];
        if rest.len() > prefix.len()
            && rest.is_char_boundary(prefix.len())
            && rest[..prefix.len()].eq_ignore_ascii_case(prefix)
            && b[p + prefix.len()] == b' '
        {
            p = skip_ws(p + prefix.len());
            break;
        }
    }
    let (h, p2) = digits(b, p, 1, 2)?;
    if b.get(p2) != Some(&b':') {
        return None;
    }
    let (m, end) = digits(b, p2 + 1, 2, 2)?;
    if h > 23 || m > 59 || b.get(end).is_some_and(|c| c.is_ascii_digit()) {
        return None;
    }
    Some((i64::from(h) * 3600 + i64::from(m) * 60, end))
}

fn label(day: i64, secs: Option<i64>, today: i64) -> String {
    let (y, m, d) = civil_from_days(day);
    let weekday = WEEKDAYS_SHORT[(day + 4).rem_euclid(7) as usize];
    let mut s = format!("{weekday} {d} {}", MONTHS_SHORT[(m as usize - 1) % 12]);
    if y != civil_from_days(today).0 {
        s.push_str(&format!(" {y}"));
    }
    if let Some(secs) = secs {
        s.push_str(&format!(", {:02}:{:02}", secs / 3600, secs % 3600 / 60));
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 2026-11-07 10:00 UTC (a Saturday).
    const NOW: i64 = 1_794_045_600_000;

    fn ms(y: i64, m: u32, d: u32, h: i64, min: i64) -> i64 {
        ((days_from_civil(y, m, d) * 24 + h) * 60 + min) * 60_000
    }

    #[test]
    fn dotted_and_iso_dates() {
        let f = detect("see you 12.11.2026 ok", NOW, 0, 5);
        assert_eq!(f.len(), 1);
        assert_eq!(&"see you 12.11.2026 ok"[f[0].span.clone()], "12.11.2026");
        assert_eq!(f[0].start_ms, ms(2026, 11, 12, 0, 0));
        assert!(f[0].all_day);
        assert_eq!(
            detect("1/12/2026", NOW, 0, 5)[0].start_ms,
            ms(2026, 12, 1, 0, 0)
        );
        let iso = detect("on 2026-11-20 at 9:30!", NOW, 0, 5);
        assert_eq!(iso[0].start_ms, ms(2026, 11, 20, 9, 30));
        assert!(!iso[0].all_day);
        assert_eq!(
            detect("12.11.2026 saat 15:00", NOW, 0, 5)[0].start_ms,
            ms(2026, 11, 12, 15, 0)
        );
    }

    #[test]
    fn day_word_with_time() {
        let f = detect("Tomorrow 15:00 works", NOW, 0, 5);
        assert_eq!(f[0].start_ms, ms(2026, 11, 8, 15, 0));
        assert_eq!(f[0].span, 0..14);
        let t = detect("tonight?", NOW, 0, 5);
        assert_eq!(t[0].start_ms, ms(2026, 11, 7, 20, 0));
        assert!(!t[0].all_day);
        let d = detect("today", NOW, 0, 5);
        assert!(d[0].all_day);
        assert_eq!(d[0].start_ms, ms(2026, 11, 7, 0, 0));
        // The local day depends on the offset.
        let z = detect("tomorrow 08:00", NOW, 14 * 3600, 5);
        assert_eq!(z[0].start_ms, ms(2026, 11, 9, 8, 0) - 14 * 3_600_000);
    }

    #[test]
    fn turkish_day_words() {
        let f = detect("YARIN saat 18:30 gel, bugün olmaz", NOW, 0, 5);
        assert_eq!(f.len(), 2);
        assert_eq!(f[0].start_ms, ms(2026, 11, 8, 18, 30));
        assert_eq!(f[1].start_ms, ms(2026, 11, 7, 0, 0));
    }

    #[test]
    fn bare_time_needs_day_word() {
        assert!(detect("meet at 15:00", NOW, 0, 5).is_empty());
        assert!(detect("15:00", NOW, 0, 5).is_empty());
        assert!(detect("untomorrow xtoday", NOW, 0, 5).is_empty());
    }

    #[test]
    fn invalid_dates_rejected() {
        for s in [
            "31.02.2026",
            "32.1.2026",
            "1.13.2026",
            "0.1.2026",
            "2026-13-01",
            "1.1.1999",
            "1.1.2101",
        ] {
            assert!(detect(s, NOW, 0, 5).is_empty(), "{s}");
        }
    }

    #[test]
    fn past_dates_dropped() {
        assert!(detect("12.10.2026", NOW, 0, 5).is_empty());
        assert_eq!(detect("06.11.2026 12:00", NOW, 0, 5).len(), 1);
        assert!(detect("05.11.2026", NOW, 0, 5).is_empty());
    }

    #[test]
    fn amounts_and_codes_are_not_dates() {
        for s in [
            "12.5",
            "pay 12.50 now",
            "code 123456",
            "ref 1234.5678",
            "v1.2.3",
            "12.11.20266",
            "112.11.2026",
        ] {
            assert!(detect(s, NOW, 0, 5).is_empty(), "{s}");
        }
    }

    #[test]
    fn label_formats() {
        let f = detect("12.11.2026 15:00", NOW, 0, 5);
        assert_eq!(f[0].label, "Thu 12 Nov, 15:00");
        assert_eq!(detect("12.11.2026", NOW, 0, 5)[0].label, "Thu 12 Nov");
        assert_eq!(detect("2027-01-05", NOW, 0, 5)[0].label, "Tue 5 Jan 2027");
        assert_eq!(
            detect("2027-01-05 07:05", NOW, 0, 5)[0].label,
            "Tue 5 Jan 2027, 07:05"
        );
        let end = calendar_end_ms(&f[0]);
        assert_eq!(end - f[0].start_ms, 3_600_000);
        assert_eq!(
            calendar_end_ms(&detect("today", NOW, 0, 1)[0]) - ms(2026, 11, 7, 0, 0),
            DAY_MS
        );
    }

    #[test]
    fn respects_max() {
        let body = "12.11.2026 13.11.2026 14.11.2026";
        assert_eq!(detect(body, NOW, 0, 2).len(), 2);
        assert!(detect(body, NOW, 0, 0).is_empty());
    }
}

#[cfg(test)]
mod title_tests {
    use super::*;

    #[test]
    fn title_joins_chat_and_head() {
        assert_eq!(
            event_title("Anna", "Dinner\n tomorrow"),
            "Anna: Dinner tomorrow"
        );
        assert_eq!(event_title("", "Hi"), "Hi");
        assert_eq!(event_title("Anna", "  "), "Anna");
    }

    #[test]
    fn title_truncates_at_40_chars() {
        let t = event_title("A", &"x".repeat(60));
        assert_eq!(t, format!("A: {}…", "x".repeat(40)));
    }

    #[test]
    fn matched_text_is_the_span() {
        let body = "see you 12.11.2099 ok";
        let d = detect(body, 0, 0, 1);
        assert_eq!(matched_text(body, &d[0]), "12.11.2099");
    }
}
