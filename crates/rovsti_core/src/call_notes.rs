//! Per-call notes and the call details text.
//!
//! A note is keyed by number plus call time rather than by log id: ids change
//! after a backup restore, and a note typed during a call exists before the
//! call is logged.

use serde::{Deserialize, Serialize};

use crate::call_log::{CallType, DateOrder, civil_from_days, clock_text, format_duration};
use crate::phone_number::PhoneNumber;
use crate::recents::{HistoryLine, LocaleTime, history_text};

/// One note on one call.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallNote {
    /// The number as dialed or logged.
    pub number: String,
    /// Call start, ms since the epoch.
    pub at_ms: i64,
    pub text: String,
    pub edited_ms: i64,
}

/// What `Notes::set` did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SetOutcome {
    Added,
    Updated,
    /// The cleaned text was empty, so the note was deleted.
    Removed,
    Unchanged,
}

/// All notes, as stored in `FILE`.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Notes {
    #[serde(default)]
    pub schema: u32,
    #[serde(default)]
    pub notes: Vec<CallNote>,
}

pub const FILE: &str = "call-notes.json";
/// Longest note, in chars.
pub const MAX_LEN: usize = 1000;
pub const MAX_NOTES: usize = 2000;
/// A note belongs to a call that started within this many ms of its time.
pub const MATCH_WINDOW_MS: i64 = 120_000;

/// Trims, collapses 3+ newlines to 2, drops control chars except `\n` and
/// clips to `MAX_LEN` chars. Empty input gives `None`.
pub fn clean(text: &str) -> Option<String> {
    let mut out = String::with_capacity(text.len().min(MAX_LEN * 2));
    let mut newlines = 0;
    for c in text.trim().chars() {
        if c == '\r' {
            continue;
        }
        if c == '\n' {
            newlines += 1;
            if newlines > 2 {
                continue;
            }
        } else if c.is_control() {
            continue;
        } else {
            newlines = 0;
        }
        out.push(c);
    }
    let out = out.trim();
    if out.is_empty() {
        return None;
    }
    Some(
        out.chars()
            .take(MAX_LEN)
            .collect::<String>()
            .trim_end()
            .to_owned(),
    )
}

/// First line of `text`, at most 60 chars, with `…` when cut.
pub fn preview(text: &str) -> String {
    const MAX: usize = 60;
    let text = text.trim();
    let first = text.lines().next().unwrap_or("").trim_end();
    let cut = text.lines().nth(1).is_some_and(|l| !l.trim().is_empty());
    if first.chars().count() > MAX {
        let mut s: String = first.chars().take(MAX - 1).collect();
        s.push('\u{2026}');
        s
    } else if cut {
        format!("{first}\u{2026}")
    } else {
        first.to_owned()
    }
}

fn parse_number(raw: &str) -> Option<PhoneNumber> {
    let raw = raw.trim();
    if raw.is_empty() {
        return None;
    }
    PhoneNumber::parse(raw).ok()
}

impl Notes {
    /// Parses the stored JSON. Never fails: bad input gives no notes.
    pub fn parse(json: &str) -> Self {
        serde_json::from_str(json).unwrap_or_default()
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| "{}".to_owned())
    }

    /// The note for a call of `number` at `date_ms`: the nearest in time
    /// within `MATCH_WINDOW_MS`. A private or unparsable number never matches.
    pub fn find(&self, number: &str, date_ms: i64) -> Option<usize> {
        let wanted = parse_number(number)?;
        self.notes
            .iter()
            .enumerate()
            .filter_map(|(i, n)| {
                let gap = n.at_ms.abs_diff(date_ms);
                if gap > MATCH_WINDOW_MS as u64 {
                    return None;
                }
                parse_number(&n.number)
                    .filter(|p| p.matches(&wanted))
                    .map(|_| (gap, i))
            })
            .min()
            .map(|(_, i)| i)
    }

    /// Adds, replaces or (for empty text) deletes the note of a call.
    pub fn set(&mut self, number: &str, at_ms: i64, text: &str, now_ms: i64) -> SetOutcome {
        let cleaned = clean(text);
        match (self.find(number, at_ms), cleaned) {
            (Some(i), None) => {
                self.notes.remove(i);
                SetOutcome::Removed
            }
            (Some(i), Some(text)) => {
                if self.notes[i].text == text {
                    return SetOutcome::Unchanged;
                }
                self.notes[i].text = text;
                self.notes[i].edited_ms = now_ms;
                SetOutcome::Updated
            }
            (None, None) => SetOutcome::Unchanged,
            (None, Some(text)) => {
                if parse_number(number).is_none() {
                    // Could never be found again.
                    return SetOutcome::Unchanged;
                }
                self.notes.push(CallNote {
                    number: number.trim().to_owned(),
                    at_ms,
                    text,
                    edited_ms: now_ms,
                });
                self.trim_to_cap();
                SetOutcome::Added
            }
        }
    }

    fn trim_to_cap(&mut self) {
        while self.notes.len() > MAX_NOTES {
            let Some(oldest) = self
                .notes
                .iter()
                .enumerate()
                .min_by_key(|(_, n)| n.at_ms)
                .map(|(i, _)| i)
            else {
                break;
            };
            self.notes.remove(oldest);
        }
    }

    /// Deletes the note of a call and returns it, for Undo.
    pub fn remove(&mut self, number: &str, date_ms: i64) -> Option<CallNote> {
        let i = self.find(number, date_ms)?;
        Some(self.notes.remove(i))
    }

    /// Puts a removed note back (replacing any note typed meanwhile).
    pub fn restore(&mut self, note: CallNote) {
        if let Some(i) = self.find(&note.number, note.at_ms) {
            self.notes.remove(i);
        }
        self.notes.push(note);
        self.trim_to_cap();
    }

    /// For each `(number, date_ms)` call, whether it has a note.
    pub fn for_calls(&self, calls: &[(String, i64)]) -> Vec<bool> {
        if self.notes.is_empty() {
            return vec![false; calls.len()];
        }
        let parsed: Vec<Option<PhoneNumber>> =
            self.notes.iter().map(|n| parse_number(&n.number)).collect();
        calls
            .iter()
            .map(|(number, date_ms)| {
                let Some(wanted) = parse_number(number) else {
                    return false;
                };
                self.notes.iter().zip(&parsed).any(|(n, p)| {
                    n.at_ms.abs_diff(*date_ms) <= MATCH_WINDOW_MS as u64
                        && p.as_ref().is_some_and(|p| p.matches(&wanted))
                })
            })
            .collect()
    }
}

/// The texts of the call details sheet.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CallDetails {
    /// "Incoming call", "WhatsApp Missed call".
    pub title: String,
    /// "Thu 5 Nov 2026, 14:32".
    pub when: String,
    /// `None` for calls that were not answered.
    pub duration: Option<String>,
    /// "Mobile", "WhatsApp" or the SIM label.
    pub channel: String,
    pub number: String,
}

const WEEKDAYS: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

fn when_text(ms: i64, locale: LocaleTime) -> String {
    let local_s = ms.div_euclid(1000) + i64::from(locale.tz_offset_s);
    let days = local_s.div_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    let weekday = WEEKDAYS[(days + 4).rem_euclid(7) as usize];
    let month = MONTHS[(m.clamp(1, 12) - 1) as usize];
    let clock = clock_text(ms, locale.tz_offset_s, locale.format.hour12);
    let clock = clock.as_str();
    match locale.format.date_order {
        DateOrder::DayMonthYear => format!("{weekday} {d} {month} {y}, {clock}"),
        DateOrder::MonthDayYear => format!("{weekday} {month} {d}, {y}, {clock}"),
        DateOrder::YearMonthDay => format!("{weekday} {y}-{m:02}-{d:02}, {clock}"),
    }
}

/// Builds the details of one history line. `sim` is the SIM label, if known.
pub fn details(
    line: &HistoryLine,
    number: &str,
    sim: Option<&str>,
    locale: LocaleTime,
) -> CallDetails {
    let answered = line.duration_s > 0
        && !line.blocked
        && !matches!(line.call_type, CallType::Missed | CallType::Declined);
    let channel = if line.whatsapp {
        "WhatsApp".to_owned()
    } else {
        match sim.map(str::trim).filter(|s| !s.is_empty()) {
            Some(s) => s.to_owned(),
            None => "Mobile".to_owned(),
        }
    };
    CallDetails {
        title: history_text(line, line.date_ms, locale).0,
        when: when_text(line.date_ms, locale),
        duration: answered.then(|| format_duration(line.duration_s).as_str().to_owned()),
        channel,
        number: number.trim().to_owned(),
    }
}

/// Multi-line text for "Copy details".
pub fn details_text(d: &CallDetails, note: Option<&str>) -> String {
    let mut out = String::new();
    if !d.number.is_empty() {
        out.push_str(&format!("Number: {}\n", d.number));
    }
    out.push_str(&format!("When: {}\n", d.when));
    out.push_str(&format!("Type: {}\n", d.title));
    if let Some(dur) = &d.duration {
        out.push_str(&format!("Duration: {dur}\n"));
    }
    out.push_str(&format!("Via: {}", d.channel));
    if let Some(note) = note.and_then(clean) {
        out.push_str(&format!("\nNote: {note}"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::call_log::EntryRef;
    use crate::call_log::TimeFormat;

    const T: i64 = 1_790_000_000_000;

    fn locale(hour12: bool, date_order: DateOrder) -> LocaleTime {
        LocaleTime {
            tz_offset_s: 0,
            format: TimeFormat { hour12, date_order },
        }
    }

    fn line(call_type: CallType, whatsapp: bool, dur: u64) -> HistoryLine {
        HistoryLine {
            entry: EntryRef::Log(1),
            blocked: false,
            call_type,
            whatsapp,
            date_ms: 1_793_889_120_000, // Thu 5 Nov 2026 14:32:00 UTC
            duration_s: dur,
        }
    }

    #[test]
    fn clean_trims_and_drops_controls() {
        assert_eq!(clean("  hi\u{7} there \r\n"), Some("hi there".into()));
        assert_eq!(clean("a\n\n\n\n\nb"), Some("a\n\nb".into()));
        assert_eq!(clean("a\nb"), Some("a\nb".into()));
        assert_eq!(clean(" \n\t "), None);
        assert_eq!(clean(""), None);
    }

    #[test]
    fn clean_clips_on_char_boundary() {
        let s = "\u{e7}".repeat(MAX_LEN + 50);
        assert_eq!(clean(&s).expect("some").chars().count(), MAX_LEN);
    }

    #[test]
    fn preview_cuts() {
        assert_eq!(preview("short"), "short");
        assert_eq!(preview("one\ntwo"), "one\u{2026}");
        let p = preview(&"x".repeat(100));
        assert_eq!(p.chars().count(), 60);
        assert!(p.ends_with('\u{2026}'));
    }

    #[test]
    fn find_matches_national_and_international_in_window() {
        let mut n = Notes::default();
        n.set("+905321234567", T, "x", T);
        assert_eq!(n.find("0532 123 45 67", T + 119_000), Some(0));
        assert_eq!(n.find("0532 123 45 67", T + 121_000), None);
        assert_eq!(n.find("0555 000 00 00", T), None);
    }

    #[test]
    fn nearest_candidate_wins() {
        let mut n = Notes::default();
        n.set("+905321234567", T, "far", T);
        n.set("+905321234567", T + 200_000, "near", T);
        assert_eq!(n.notes.len(), 2);
        let i = n.find("+905321234567", T + 110_000).expect("present");
        assert_eq!(n.notes[i].text, "near");
    }

    #[test]
    fn private_number_never_matches() {
        let mut n = Notes::default();
        n.notes.push(CallNote {
            number: String::new(),
            at_ms: T,
            text: "p".into(),
            edited_ms: T,
        });
        assert_eq!(n.find("", T), None);
        assert_eq!(n.find("  ", T), None);
        assert_eq!(n.set("", T, "text", T), SetOutcome::Unchanged);
        assert_eq!(n.notes.len(), 1);
        assert_eq!(n.for_calls(&[(String::new(), T)]), vec![false]);
    }

    #[test]
    fn set_outcomes() {
        let mut n = Notes::default();
        assert_eq!(n.set("+905321234567", T, "a", 1), SetOutcome::Added);
        assert_eq!(n.set("+905321234567", T, "a", 2), SetOutcome::Unchanged);
        assert_eq!(n.set("+905321234567", T, "b", 3), SetOutcome::Updated);
        assert_eq!(n.notes[0].edited_ms, 3);
        assert_eq!(n.set("+905321234567", T, "  ", 4), SetOutcome::Removed);
        assert!(n.notes.is_empty());
        assert_eq!(n.set("+905321234567", T, "", 5), SetOutcome::Unchanged);
    }

    #[test]
    fn cap_drops_oldest() {
        let mut n = Notes::default();
        for i in 0..=MAX_NOTES as i64 {
            n.set("+905321234567", T + i * 1_000_000, "n", 0);
        }
        assert_eq!(n.notes.len(), MAX_NOTES);
        assert!(n.notes.iter().all(|c| c.at_ms != T));
    }

    #[test]
    fn remove_then_restore() {
        let mut n = Notes::default();
        n.set("+905321234567", T, "keep", 9);
        let gone = n.remove("05321234567", T).expect("present");
        assert!(n.notes.is_empty());
        assert!(n.remove("05321234567", T).is_none());
        n.restore(gone.clone());
        assert_eq!(n.notes, vec![gone]);
    }

    #[test]
    fn flags_for_calls() {
        let mut n = Notes::default();
        n.set("+905321234567", T, "x", 0);
        let calls = vec![
            ("05321234567".to_owned(), T + 5),
            ("05321234567".to_owned(), T + 500_000),
            ("0555 000 00 00".to_owned(), T),
        ];
        assert_eq!(n.for_calls(&calls), vec![true, false, false]);
    }

    #[test]
    fn json_round_trip_and_garbage() {
        let mut n = Notes::default();
        n.set("+905321234567", T, "caf\u{e9} \"x\"", 1);
        assert_eq!(Notes::parse(&n.to_json()), n);
        assert_eq!(Notes::parse("not json"), Notes::default());
        assert_eq!(Notes::parse(""), Notes::default());
        assert_eq!(Notes::parse("{}"), Notes::default());
    }

    #[test]
    fn details_dmy_24h() {
        let d = details(
            &line(CallType::Incoming, false, 65),
            " +905321234567 ",
            Some("SIM 1"),
            locale(false, DateOrder::DayMonthYear),
        );
        assert_eq!(d.title, "Incoming call");
        assert_eq!(d.when, "Thu 5 Nov 2026, 14:32");
        assert_eq!(d.duration.as_deref(), Some("1:05"));
        assert_eq!(d.channel, "SIM 1");
        assert_eq!(d.number, "+905321234567");
        let t = details_text(&d, Some(" call back \n"));
        assert_eq!(
            t,
            "Number: +905321234567\nWhen: Thu 5 Nov 2026, 14:32\nType: Incoming call\n\
             Duration: 1:05\nVia: SIM 1\nNote: call back"
        );
    }

    #[test]
    fn details_mdy_12h_and_ymd() {
        let l = line(CallType::Outgoing, false, 5);
        let d = details(&l, "123", None, locale(true, DateOrder::MonthDayYear));
        assert_eq!(d.when, "Thu Nov 5, 2026, 2:32 pm");
        assert_eq!(d.channel, "Mobile");
        let d = details(&l, "123", None, locale(false, DateOrder::YearMonthDay));
        assert_eq!(d.when, "Thu 2026-11-05, 14:32");
    }

    #[test]
    fn missed_has_no_duration_and_whatsapp_channel() {
        let d = details(
            &line(CallType::Missed, false, 30),
            "123",
            None,
            LocaleTime::default(),
        );
        assert_eq!(d.duration, None);
        assert!(!details_text(&d, None).contains("Duration"));
        assert!(!details_text(&d, None).contains("Note"));
        let w = details(
            &line(CallType::Incoming, true, 30),
            "123",
            Some("SIM 1"),
            LocaleTime::default(),
        );
        assert_eq!(w.channel, "WhatsApp");
        assert_eq!(w.title, "WhatsApp Incoming call");
    }
}
