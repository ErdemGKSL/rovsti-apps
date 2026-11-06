//! Row view-models for the Recents list (stories 6.1 to 6.3).
//!
//! The UI builds these once per call-log snapshot (and again when the time
//! labels need refreshing), never inside a frame: every string a row draws or
//! speaks is ready here, so scrolling only copies handles.

use crate::{
    avatar,
    call_log::{
        CallType, DateOrder, EntryRef, MAX_ENTRIES, RecentsRow, TimeFormat, clock_text,
        format_duration, relative_time,
    },
    phone_number::PhoneNumber,
    wire::{CallLogEntry, CallLogKind},
};

/// What the leading glyph in the subtitle shows.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Direction {
    Incoming,
    Outgoing,
    Missed,
    Declined,
    Voicemail,
    Unknown,
}

impl Direction {
    pub fn of(call_type: CallType) -> Self {
        match call_type {
            CallType::Incoming => Self::Incoming,
            CallType::Outgoing => Self::Outgoing,
            CallType::Missed => Self::Missed,
            CallType::Declined => Self::Declined,
            CallType::Voicemail => Self::Voicemail,
            CallType::Unknown => Self::Unknown,
        }
    }

    /// Spoken type of the call ("missed call").
    fn phrase(self) -> &'static str {
        match self {
            Self::Incoming => "incoming call",
            Self::Outgoing => "outgoing call",
            Self::Missed => "missed call",
            Self::Declined => "declined call",
            Self::Voicemail => "voicemail",
            Self::Unknown => "call",
        }
    }
}

/// Title shown when the log has no number at all.
pub const UNKNOWN_NUMBER: &str = "Unknown number";

/// One ready-to-draw Recents line.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RowView {
    /// Name, or the number as stored.
    pub title: String,
    /// "(2)" when several calls collapsed into the row.
    pub count: Option<String>,
    pub subtitle: String,
    pub time: String,
    /// "{Name}, missed call, 2 calls, 3:14 pm, Mobile. Double tap to call."
    pub aria: String,
    pub direction: Direction,
    pub missed: bool,
    /// False for private numbers: the row is inert.
    pub callable: bool,
    /// The number to dial, exactly as the log stored it.
    pub number: String,
    pub hue: Option<usize>,
    pub monogram: Option<char>,
    pub named: bool,
    /// The SIM the newest call used (AD-26); the view resolves it to a chip.
    pub account_key: Option<String>,
    /// A WhatsApp call: the row draws a WhatsApp badge.
    pub whatsapp: bool,
}

/// Time-zone offset and clock style of the phone, injected so tests and the
/// desktop preview never read the host locale.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LocaleTime {
    pub tz_offset_s: i32,
    pub format: TimeFormat,
}

impl Default for LocaleTime {
    fn default() -> Self {
        Self {
            tz_offset_s: 0,
            format: TimeFormat {
                hour12: false,
                date_order: DateOrder::DayMonthYear,
            },
        }
    }
}

impl LocaleTime {
    /// Parses what `RovstiBridge.localTimeInfo` returns:
    /// `"<offset seconds>,<12|24>,<dmy|mdy|ymd>"`.
    pub fn parse(text: &str) -> Option<Self> {
        let mut parts = text.trim().split(',');
        let tz_offset_s = parts.next()?.trim().parse::<i32>().ok()?;
        let hour12 = match parts.next()?.trim() {
            "12" => true,
            "24" => false,
            _ => return None,
        };
        let date_order = match parts.next()?.trim() {
            "dmy" => DateOrder::DayMonthYear,
            "mdy" => DateOrder::MonthDayYear,
            "ymd" => DateOrder::YearMonthDay,
            _ => return None,
        };
        Some(Self {
            tz_offset_s,
            format: TimeFormat { hour12, date_order },
        })
    }
}

/// Builds the view-models for `rows`, with times relative to `now_ms`.
pub fn build(rows: &[RecentsRow], now_ms: i64, locale: LocaleTime) -> Vec<RowView> {
    rows.iter().map(|row| view(row, now_ms, locale)).collect()
}

/// What a call-log refresh changed at the top of the list (story 10.3).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct RefreshDelta {
    /// Leading rows that are new or have a newer call than before: these fade in.
    pub fresh: usize,
    /// Rows inserted above the old first row: everything the user was looking
    /// at moved down by this many rows.
    pub shift: usize,
}

/// Compares the rows before and after a refresh. An empty `old` (the first
/// load) animates nothing. A call that merges into the old top run (same
/// number and type) is `fresh` but does not `shift`.
pub fn refresh_delta(old: &[RecentsRow], new: &[RecentsRow]) -> RefreshDelta {
    let Some(old_top) = old.first() else {
        return RefreshDelta::default();
    };
    let fresh = new
        .iter()
        .take_while(|row| row.last_ms > old_top.last_ms)
        .count();
    // The old top run keeps its oldest timestamp even after it grows.
    let shift = new
        .iter()
        .position(|row| row.first_ms == old_top.first_ms && row.call_type == old_top.call_type)
        .unwrap_or(fresh);
    RefreshDelta { fresh, shift }
}

fn view(row: &RecentsRow, now_ms: i64, locale: LocaleTime) -> RowView {
    let direction = Direction::of(row.call_type);
    let number = row.number_raw.trim();
    let name = row.name.as_deref().map(str::trim).filter(|n| !n.is_empty());
    let label = row
        .label
        .as_deref()
        .map(str::trim)
        .filter(|l| !l.is_empty());
    let named = name.is_some();
    let title = name.unwrap_or(if number.is_empty() {
        UNKNOWN_NUMBER
    } else {
        number
    });
    let count = (row.count > 1).then(|| format!("({})", row.count));
    let time = relative_time(now_ms, row.last_ms, locale.tz_offset_s, locale.format);

    // Subtitle: the word for the unusual types (and for any unnamed row, where
    // the title is the number), then the label, or the number under a name.
    let word = match direction {
        Direction::Missed => Some("Missed"),
        Direction::Declined => Some("Declined"),
        Direction::Voicemail => Some("Voicemail"),
        Direction::Incoming if !named => Some("Incoming"),
        Direction::Outgoing if !named => Some("Outgoing"),
        _ => None,
    };
    let detail = if named {
        label.or((!number.is_empty()).then_some(number))
    } else {
        label
    };
    let subtitle = [word, detail]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(" \u{b7} ");

    let mut aria = String::from(title);
    aria.push_str(", ");
    aria.push_str(direction.phrase());
    if row.count > 1 {
        aria.push_str(&format!(", {} calls", row.count));
    }
    if row.whatsapp {
        aria.push_str(", WhatsApp");
    }
    aria.push_str(", ");
    aria.push_str(time.as_str());
    if let Some(label) = label {
        aria.push_str(", ");
        aria.push_str(label);
    }
    aria.push_str(if row.callable {
        ". Double tap to call."
    } else {
        ". Unknown number, cannot call back."
    });

    RowView {
        title: title.to_owned(),
        count,
        subtitle,
        time: time.as_str().to_owned(),
        aria,
        direction,
        missed: direction == Direction::Missed,
        callable: row.callable,
        number: row.number_raw.clone(),
        hue: avatar::hue_index(row.contact_id),
        monogram: name.and_then(avatar::monogram),
        named,
        account_key: row.account_key.clone(),
        whatsapp: row.whatsapp,
    }
}

/// A call that did not come from the phone's call log (WhatsApp, kept by the
/// app itself), in the terms Recents needs. Generic on purpose: `rovsti_core`
/// knows nothing about where such calls come from.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExtraCall {
    /// The number as it should be dialed (`+905551234567`).
    pub number_raw: String,
    pub started_ms: i64,
    pub duration_s: u64,
    pub missed: bool,
    /// The peer's name when known; `None` shows the number as the title.
    pub label: Option<String>,
    /// The id of the stored call (for forgetting it).
    pub call_id: String,
}

impl ExtraCall {
    fn into_row(self) -> RecentsRow {
        let number: String = self
            .number_raw
            .chars()
            .filter(|c| c.is_ascii_digit() || *c == '+')
            .collect();
        let call_id = self.call_id.clone();
        RecentsRow {
            callable: !number.is_empty(),
            normalized: number,
            number_raw: self.number_raw,
            call_type: if self.missed {
                CallType::Missed
            } else {
                CallType::Unknown
            },
            count: 1,
            first_ms: self.started_ms,
            last_ms: self.started_ms,
            name: self.label,
            label: None,
            contact_id: None,
            account_key: None,
            whatsapp: true,
            refs: vec![EntryRef::WhatsApp(call_id)],
        }
    }
}

fn same_line(a: &RecentsRow, b: &RecentsRow) -> bool {
    match (
        PhoneNumber::parse(&a.normalized),
        PhoneNumber::parse(&b.normalized),
    ) {
        (Ok(x), Ok(y)) => x.matches(&y),
        _ => a.normalized == b.normalized,
    }
}

/// Merges `extras` (WhatsApp calls) into the grouped call-log `rows`, newest
/// first. A row sits where its newest call sits (ties keep the call-log row
/// first). Consecutive WhatsApp calls of one number and type collapse into one
/// row like call-log runs do; a WhatsApp row never merges with a cellular one.
pub fn merge_extras(rows: &[RecentsRow], extras: &[ExtraCall]) -> Vec<RecentsRow> {
    if extras.is_empty() {
        return rows.to_vec();
    }
    let mut extra_rows: Vec<RecentsRow> = extras.iter().cloned().map(ExtraCall::into_row).collect();
    extra_rows.sort_by_key(|row| std::cmp::Reverse(row.last_ms));

    let mut merged: Vec<RecentsRow> = Vec::with_capacity(rows.len() + extra_rows.len());
    let mut log = rows.iter().peekable();
    let mut extra = extra_rows.into_iter().peekable();
    loop {
        let take_log = match (log.peek(), extra.peek()) {
            (Some(l), Some(e)) => l.last_ms >= e.last_ms,
            (Some(_), None) => true,
            (None, Some(_)) => false,
            (None, None) => break,
        };
        let next = if take_log {
            log.next().cloned()
        } else {
            extra.next()
        };
        let Some(next) = next else { break };
        match merged.last_mut() {
            Some(prev)
                if prev.whatsapp
                    && next.whatsapp
                    && prev.call_type == next.call_type
                    && same_line(prev, &next) =>
            {
                prev.count += next.count;
                prev.refs.extend(next.refs);
                prev.first_ms = prev.first_ms.min(next.first_ms);
                prev.last_ms = prev.last_ms.max(next.last_ms);
                if prev.name.is_none() {
                    prev.name = next.name;
                }
            }
            _ => merged.push(next),
        }
    }
    merged
}

/// Missed extra calls newer than `last_seen_ms` (strictly); adds to
/// [`crate::call_log::missed_badge`].
pub fn missed_extras(extras: &[ExtraCall], last_seen_ms: i64) -> u32 {
    let n = extras
        .iter()
        .filter(|e| e.missed && e.started_ms > last_seen_ms)
        .count();
    u32::try_from(n).unwrap_or(u32::MAX)
}

/// One call in a contact's or number's history.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HistoryLine {
    pub call_type: CallType,
    pub whatsapp: bool,
    pub date_ms: i64,
    pub duration_s: u64,
}

/// Whether `raw` is one of `numbers` (parsed match, or exact trimmed text for
/// numbers that do not parse). A blank number never matches.
pub(crate) fn number_in(raw: &str, numbers: &[(&str, Option<PhoneNumber>)]) -> bool {
    let raw = raw.trim();
    if raw.is_empty() {
        return false;
    }
    let parsed = PhoneNumber::parse(raw).ok();
    numbers
        .iter()
        .any(|(text, number)| match (&parsed, number) {
            (Some(a), Some(b)) => a.matches(b),
            _ => *text == raw,
        })
}

/// Non-blank trimmed `numbers`, each with its parsed form.
pub(crate) fn wanted_numbers<'a>(numbers: &[&'a str]) -> Vec<(&'a str, Option<PhoneNumber>)> {
    numbers
        .iter()
        .map(|n| n.trim())
        .filter(|n| !n.is_empty())
        .map(|n| (n, PhoneNumber::parse(n).ok()))
        .collect()
}

/// Splits row refs into call-log ids and WhatsApp call ids.
pub fn split_refs(refs: &[EntryRef]) -> (Vec<i64>, Vec<String>) {
    let mut ids = Vec::new();
    let mut calls = Vec::new();
    for r in refs {
        match r {
            EntryRef::Log(id) => ids.push(*id),
            EntryRef::WhatsApp(id) => calls.push(id.clone()),
        }
    }
    (ids, calls)
}

/// Call ids of the `extras` whose number is one of `numbers`.
pub fn extra_ids_for_numbers(extras: &[ExtraCall], numbers: &[&str]) -> Vec<String> {
    let wanted = wanted_numbers(numbers);
    extras
        .iter()
        .filter(|e| number_in(&e.number_raw, &wanted))
        .map(|e| e.call_id.clone())
        .collect()
}

/// Calls (cellular and WhatsApp) with any of `numbers`, newest first, at most
/// `limit`. Blocked entries are dropped; only the newest
/// [`MAX_ENTRIES`] log entries are read.
pub fn history(
    entries: &[CallLogEntry],
    extras: &[ExtraCall],
    numbers: &[&str],
    limit: usize,
) -> Vec<HistoryLine> {
    let wanted = wanted_numbers(numbers);
    if wanted.is_empty() || limit == 0 {
        return Vec::new();
    }
    let mut lines: Vec<HistoryLine> = Vec::new();
    for entry in entries.iter().take(MAX_ENTRIES) {
        if entry.kind == CallLogKind::Blocked {
            continue;
        }
        let Some(call_type) = CallType::from_kind(entry.kind) else {
            continue;
        };
        let raw = if entry.number_raw.trim().is_empty() {
            entry.normalized.as_str()
        } else {
            entry.number_raw.as_str()
        };
        if number_in(raw, &wanted) {
            lines.push(HistoryLine {
                call_type,
                whatsapp: false,
                date_ms: entry.date_ms,
                duration_s: u64::try_from(entry.duration_s).unwrap_or(0),
            });
        }
    }
    for extra in extras {
        if number_in(&extra.number_raw, &wanted) {
            lines.push(HistoryLine {
                call_type: if extra.missed {
                    CallType::Missed
                } else {
                    CallType::Unknown
                },
                whatsapp: true,
                date_ms: extra.started_ms,
                duration_s: extra.duration_s,
            });
        }
    }
    lines.sort_by_key(|l| std::cmp::Reverse(l.date_ms));
    lines.truncate(limit);
    lines
}

/// Title and detail of one history line.
pub fn history_text(line: &HistoryLine, now_ms: i64, locale: LocaleTime) -> (String, String) {
    let base = match line.call_type {
        CallType::Missed => "Missed call",
        CallType::Incoming => "Incoming call",
        CallType::Outgoing => "Outgoing call",
        CallType::Declined => "Declined",
        CallType::Voicemail => "Voicemail",
        CallType::Unknown => "Call",
    };
    let title = if line.whatsapp {
        format!("WhatsApp {base}")
    } else {
        base.to_owned()
    };

    let day = |ms: i64| (ms.div_euclid(1000) + i64::from(locale.tz_offset_s)).div_euclid(86_400);
    let mut detail = relative_time(now_ms, line.date_ms, locale.tz_offset_s, locale.format)
        .as_str()
        .to_owned();
    if day(now_ms) > day(line.date_ms) {
        detail.push(' ');
        detail
            .push_str(clock_text(line.date_ms, locale.tz_offset_s, locale.format.hour12).as_str());
    }
    if line.duration_s > 0 && !matches!(line.call_type, CallType::Missed | CallType::Declined) {
        detail.push_str(" \u{b7} ");
        detail.push_str(format_duration(line.duration_s).as_str());
    }
    (title, detail)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        call_log::group,
        wire::{CallLogEntry, CallLogKind},
    };

    const NOW: i64 = 1_790_000_000_000;
    const MIN: i64 = 60_000;

    fn entry(
        id: i64,
        number: &str,
        kind: CallLogKind,
        ago: i64,
        name: Option<&str>,
    ) -> CallLogEntry {
        CallLogEntry {
            id,
            number_raw: number.to_owned(),
            normalized: number.replace(' ', ""),
            kind,
            date_ms: NOW - ago,
            duration_s: 0,
            cached_name: name.map(str::to_owned),
            number_label: None,
            contact_id: name.map(|_| id),
            ..Default::default()
        }
    }

    fn call(id: i64, number: &str, kind: CallLogKind, ago: i64, dur: i64) -> CallLogEntry {
        CallLogEntry {
            duration_s: dur,
            ..entry(id, number, kind, ago, None)
        }
    }

    fn wa_call(number: &str, ago: i64, missed: bool) -> ExtraCall {
        ExtraCall {
            number_raw: number.to_owned(),
            started_ms: NOW - ago,
            duration_s: 30,
            missed,
            label: None,
            call_id: format!("wa-{ago}"),
        }
    }

    #[test]
    fn history_matches_national_and_international_forms() {
        let entries = [
            call(2, "+905551112233", CallLogKind::Incoming, MIN, 10),
            call(1, "05551112233", CallLogKind::Outgoing, 2 * MIN, 20),
            call(3, "05559998877", CallLogKind::Outgoing, 3 * MIN, 20),
        ];
        let h = history(&entries, &[], &["0555 111 22 33"], 10);
        assert_eq!(h.len(), 2);
        assert_eq!(h[0].call_type, CallType::Incoming);
        assert_eq!(h[1].duration_s, 20);
        assert!(!h[0].whatsapp);
    }

    #[test]
    fn history_merges_extras_in_date_order_and_limits() {
        let entries = [
            call(2, "05551112233", CallLogKind::Outgoing, MIN, 0),
            call(1, "05551112233", CallLogKind::Outgoing, 5 * MIN, 0),
        ];
        let extras = [
            wa_call("+905551112233", 3 * MIN, true),
            wa_call("+905551112233", 4 * MIN, false),
        ];
        let h = history(&entries, &extras, &["05551112233"], 10);
        let ago: Vec<i64> = h.iter().map(|l| NOW - l.date_ms).collect();
        assert_eq!(ago, [MIN, 3 * MIN, 4 * MIN, 5 * MIN]);
        assert!(h[1].whatsapp && h[1].call_type == CallType::Missed);
        assert!(h[2].whatsapp && h[2].call_type == CallType::Unknown);
        assert_eq!(history(&entries, &extras, &["05551112233"], 2).len(), 2);
        assert!(history(&entries, &extras, &["05551112233"], 0).is_empty());
    }

    #[test]
    fn history_drops_blocked_and_blank_numbers() {
        let entries = [
            call(1, "05551112233", CallLogKind::Blocked, MIN, 0),
            call(2, "", CallLogKind::Incoming, 2 * MIN, 0),
        ];
        assert!(history(&entries, &[], &["05551112233"], 10).is_empty());
        assert!(history(&entries, &[], &["", "  "], 10).is_empty());
        let e = [call(3, "  ", CallLogKind::Incoming, MIN, 0)];
        assert!(history(&e, &[], &[""], 10).is_empty());
    }

    #[test]
    fn history_matches_unparsable_numbers_by_exact_text() {
        let entries = [
            call(1, " *123# ", CallLogKind::Outgoing, MIN, 0),
            call(2, "*124#", CallLogKind::Outgoing, 2 * MIN, 0),
        ];
        assert_eq!(history(&entries, &[], &["*123#"], 10).len(), 1);
    }

    fn line(call_type: CallType, whatsapp: bool, ago: i64, duration_s: u64) -> HistoryLine {
        HistoryLine {
            call_type,
            whatsapp,
            date_ms: NOW - ago,
            duration_s,
        }
    }

    #[test]
    fn history_text_today_shows_clock_only() {
        let l = line(CallType::Incoming, false, MIN, 65);
        let (title, detail) = history_text(&l, NOW, LocaleTime::default());
        assert_eq!(title, "Incoming call");
        let clock = clock_text(l.date_ms, 0, false);
        assert_eq!(detail, format!("{clock} \u{b7} 1:05"));
    }

    #[test]
    fn history_text_yesterday_adds_the_clock() {
        let l = line(CallType::Outgoing, false, 24 * 60 * MIN, 0);
        let (title, detail) = history_text(&l, NOW, LocaleTime::default());
        assert_eq!(title, "Outgoing call");
        let clock = clock_text(l.date_ms, 0, false);
        assert_eq!(detail, format!("Yesterday {clock}"));
    }

    #[test]
    fn history_text_missed_has_no_duration_and_whatsapp_prefix() {
        let l = line(CallType::Missed, true, MIN, 30);
        let (title, detail) = history_text(&l, NOW, LocaleTime::default());
        assert_eq!(title, "WhatsApp Missed call");
        assert!(!detail.contains('\u{b7}'));
        let (t, d) = history_text(
            &line(CallType::Declined, false, MIN, 9),
            NOW,
            LocaleTime::default(),
        );
        assert_eq!(t, "Declined");
        assert!(!d.contains('\u{b7}'));
        let (t, _) = history_text(
            &line(CallType::Unknown, true, MIN, 0),
            NOW,
            LocaleTime::default(),
        );
        assert_eq!(t, "WhatsApp Call");
    }

    fn views(entries: &[CallLogEntry]) -> Vec<RowView> {
        build(&group(entries), NOW, LocaleTime::default())
    }

    #[test]
    fn grouped_missed_row_reads_red_with_a_count() {
        let v = views(&[
            entry(2, "0555 111 22 33", CallLogKind::Missed, MIN, Some("Ayse")),
            entry(
                1,
                "0555 111 22 33",
                CallLogKind::Missed,
                2 * MIN,
                Some("Ayse"),
            ),
        ]);
        assert_eq!(v.len(), 1);
        assert_eq!(v[0].title, "Ayse");
        assert_eq!(v[0].count.as_deref(), Some("(2)"));
        assert!(v[0].missed);
        assert_eq!(v[0].subtitle, "Missed \u{b7} 0555 111 22 33");
        assert!(v[0].aria.starts_with("Ayse, missed call, 2 calls, "));
        assert!(v[0].aria.ends_with("Double tap to call."));
        assert!(v[0].monogram == Some('A') && v[0].hue.is_some());
    }

    #[test]
    fn unnamed_rows_use_the_number_as_title() {
        let v = views(&[entry(1, "0850 123 45 67", CallLogKind::Incoming, MIN, None)]);
        assert_eq!(v[0].title, "0850 123 45 67");
        assert_eq!(v[0].subtitle, "Incoming");
        assert_eq!(v[0].count, None);
        assert_eq!(v[0].monogram, None);
        assert_eq!(v[0].hue, None);
        assert!(!v[0].named);
    }

    #[test]
    fn named_incoming_shows_the_number_under_the_name() {
        let v = views(&[entry(
            1,
            "0532 123 45 67",
            CallLogKind::Incoming,
            MIN,
            Some("Burak"),
        )]);
        assert_eq!(v[0].subtitle, "0532 123 45 67");
        assert!(!v[0].missed);
    }

    #[test]
    fn private_numbers_are_inert_and_announce_it() {
        let v = views(&[entry(1, "", CallLogKind::Missed, MIN, None)]);
        assert_eq!(v[0].title, UNKNOWN_NUMBER);
        assert!(!v[0].callable);
        assert!(v[0].aria.ends_with("Unknown number, cannot call back."));
    }

    #[test]
    fn declined_rows_say_so() {
        let v = views(&[entry(
            1,
            "0532 123 45 67",
            CallLogKind::Rejected,
            MIN,
            Some("Burak"),
        )]);
        assert_eq!(v[0].direction, Direction::Declined);
        assert!(v[0].subtitle.starts_with("Declined"));
        assert!(v[0].aria.contains("declined call"));
    }

    #[test]
    fn labels_come_through() {
        let mut e = entry(
            1,
            "0532 123 45 67",
            CallLogKind::Outgoing,
            MIN,
            Some("Burak"),
        );
        e.number_label = Some("Mobile".to_owned());
        let v = views(&[e]);
        assert_eq!(v[0].subtitle, "Mobile");
        assert!(v[0].aria.contains(", Mobile. Double"));
    }

    #[test]
    fn locale_time_parses_the_bridge_string() {
        let l = LocaleTime::parse("10800,24,dmy").expect("valid");
        assert_eq!(l.tz_offset_s, 10_800);
        assert!(!l.format.hour12);
        assert_eq!(l.format.date_order, DateOrder::DayMonthYear);
        let l = LocaleTime::parse("-18000, 12, mdy").expect("valid");
        assert!(l.format.hour12);
        assert_eq!(l.format.date_order, DateOrder::MonthDayYear);
        assert!(LocaleTime::parse("").is_none());
        assert!(LocaleTime::parse("1,13,dmy").is_none());
        assert!(LocaleTime::parse("1,12").is_none());
    }

    #[test]
    fn refresh_delta_first_load_animates_nothing() {
        let rows = group(&[entry(1, "0555 111 22 33", CallLogKind::Incoming, MIN, None)]);
        assert_eq!(refresh_delta(&[], &rows), RefreshDelta::default());
    }

    #[test]
    fn refresh_delta_new_row_on_top_shifts_the_rest() {
        let old = group(&[
            entry(1, "0555 111 22 33", CallLogKind::Incoming, 20 * MIN, None),
            entry(0, "0532 123 45 67", CallLogKind::Outgoing, 40 * MIN, None),
        ]);
        let new = group(&[
            entry(2, "0544 987 65 43", CallLogKind::Missed, MIN, None),
            entry(1, "0555 111 22 33", CallLogKind::Incoming, 20 * MIN, None),
            entry(0, "0532 123 45 67", CallLogKind::Outgoing, 40 * MIN, None),
        ]);
        assert_eq!(
            refresh_delta(&old, &new),
            RefreshDelta { fresh: 1, shift: 1 }
        );
    }

    #[test]
    fn refresh_delta_merged_call_is_fresh_without_shifting() {
        let old = group(&[entry(
            1,
            "0555 111 22 33",
            CallLogKind::Missed,
            20 * MIN,
            None,
        )]);
        let new = group(&[
            entry(2, "0555 111 22 33", CallLogKind::Missed, MIN, None),
            entry(1, "0555 111 22 33", CallLogKind::Missed, 20 * MIN, None),
        ]);
        assert_eq!(new.len(), 1);
        assert_eq!(
            refresh_delta(&old, &new),
            RefreshDelta { fresh: 1, shift: 0 }
        );
    }

    #[test]
    fn refresh_delta_unchanged_log_is_quiet() {
        let rows = group(&[entry(1, "0555 111 22 33", CallLogKind::Incoming, MIN, None)]);
        assert_eq!(refresh_delta(&rows, &rows), RefreshDelta::default());
    }

    #[test]
    fn a_row_carries_the_account_of_its_newest_entry() {
        let mut newest = entry(2, "0555 111 22 33", CallLogKind::Outgoing, MIN, None);
        newest.account_key = Some("tel/pstn#2".into());
        let mut older = entry(1, "0555 111 22 33", CallLogKind::Outgoing, 2 * MIN, None);
        older.account_key = Some("tel/pstn#1".into());
        let rows = group(&[newest, older]);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].account_key.as_deref(), Some("tel/pstn#2"));
        let view = view(&rows[0], NOW, LocaleTime::default());
        assert_eq!(view.account_key.as_deref(), Some("tel/pstn#2"));
    }

    #[test]
    fn a_row_without_an_account_has_none() {
        let rows = group(&[entry(1, "0555 111 22 33", CallLogKind::Missed, MIN, None)]);
        assert_eq!(rows[0].account_key, None);
    }
    fn extra(number: &str, ago: i64, missed: bool, label: Option<&str>) -> ExtraCall {
        ExtraCall {
            number_raw: number.to_owned(),
            started_ms: NOW - ago,
            duration_s: 10,
            missed,
            label: label.map(str::to_owned),
            call_id: format!("wa-{ago}-{number}"),
        }
    }

    #[test]
    fn extras_interleave_by_time_with_the_call_log() {
        let rows = group(&[
            entry(2, "0555 111 22 33", CallLogKind::Incoming, MIN, None),
            entry(1, "0850 123 45 67", CallLogKind::Outgoing, 5 * MIN, None),
        ]);
        let merged = merge_extras(
            &rows,
            &[
                extra("+905321112233", 3 * MIN, false, Some("Ayse")),
                extra("+905321112233", 9 * MIN, false, Some("Ayse")),
            ],
        );
        let kinds: Vec<_> = merged.iter().map(|r| (r.whatsapp, r.count)).collect();
        // newest cellular, WhatsApp (3 min), cellular (5 min), WhatsApp (9 min)
        assert_eq!(kinds, [(false, 1), (true, 1), (false, 1), (true, 1)]);
        assert_eq!(merged[1].name.as_deref(), Some("Ayse"));
    }

    #[test]
    fn extras_group_with_each_other_but_not_across_other_calls_or_cellular() {
        let rows = group(&[entry(
            1,
            "+905321112233",
            CallLogKind::Incoming,
            4 * MIN,
            None,
        )]);
        let merged = merge_extras(
            &rows,
            &[
                extra("+90 532 111 22 33", MIN, false, None),
                extra("0532 111 22 33", 2 * MIN, false, Some("Ayse")),
                extra("+905321112233", 6 * MIN, false, None),
            ],
        );
        // Two collapsed, then the same-number cellular call, then the older one.
        assert_eq!(merged.len(), 3);
        assert_eq!((merged[0].whatsapp, merged[0].count), (true, 2));
        assert_eq!(merged[0].first_ms, NOW - 2 * MIN);
        assert_eq!(merged[0].last_ms, NOW - MIN);
        assert_eq!(merged[0].name.as_deref(), Some("Ayse"));
        assert!(!merged[1].whatsapp);
        assert_eq!((merged[2].whatsapp, merged[2].count), (true, 1));
    }

    #[test]
    fn missed_and_answered_whatsapp_calls_do_not_group() {
        let merged = merge_extras(
            &[],
            &[
                extra("+905321112233", MIN, true, None),
                extra("+905321112233", 2 * MIN, false, None),
            ],
        );
        assert_eq!(merged.len(), 2);
        assert_eq!(merged[0].call_type, CallType::Missed);
        let v = build(&merged, NOW, LocaleTime::default());
        assert!(v[0].missed && v[0].whatsapp);
        assert!(v[0].aria.contains(", WhatsApp, "));
        assert!(!v[1].missed);
    }

    #[test]
    fn no_extras_leave_the_rows_alone() {
        let rows = group(&[entry(1, "0850 123 45 67", CallLogKind::Missed, MIN, None)]);
        assert_eq!(merge_extras(&rows, &[]), rows);
    }

    #[test]
    fn missed_extras_count_only_new_misses() {
        let calls = [
            extra("+1", MIN, true, None),
            extra("+1", 2 * MIN, true, None),
            extra("+1", 3 * MIN, false, None),
            extra("+1", 10 * MIN, true, None),
        ];
        assert_eq!(missed_extras(&calls, NOW - 5 * MIN), 2);
        assert_eq!(missed_extras(&calls, NOW), 0);
    }

    #[test]
    fn merge_extras_collects_whatsapp_refs() {
        let rows = group(&[entry(
            1,
            "0850 123 45 67",
            CallLogKind::Incoming,
            9 * MIN,
            None,
        )]);
        let merged = merge_extras(
            &rows,
            &[
                extra("+905551112233", MIN, true, None),
                extra("+905551112233", 2 * MIN, true, None),
            ],
        );
        assert_eq!(merged[0].count, 2);
        assert_eq!(
            merged[0].refs,
            [
                EntryRef::WhatsApp(format!("wa-{MIN}-+905551112233")),
                EntryRef::WhatsApp(format!("wa-{}-+905551112233", 2 * MIN)),
            ]
        );
        assert_eq!(merged[1].refs, [EntryRef::Log(1)]);
    }

    #[test]
    fn split_refs_separates_kinds() {
        let (ids, calls) = split_refs(&[
            EntryRef::Log(2),
            EntryRef::WhatsApp("x".into()),
            EntryRef::Log(1),
        ]);
        assert_eq!(ids, [2, 1]);
        assert_eq!(calls, ["x"]);
    }

    #[test]
    fn extra_ids_for_numbers_matches_forms() {
        let extras = [
            extra("+905551112233", MIN, false, None),
            extra("+905550000000", 2 * MIN, false, None),
        ];
        assert_eq!(
            extra_ids_for_numbers(&extras, &["0555 111 22 33"]),
            [format!("wa-{MIN}-+905551112233")]
        );
        assert!(extra_ids_for_numbers(&extras, &[""]).is_empty());
    }
}
