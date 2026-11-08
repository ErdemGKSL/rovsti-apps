//! Recents search, filters and CSV export. Pure logic, no platform types.

use std::collections::BTreeSet;

use crate::call_log::{CallType, EntryRef, RecentsRow, civil_from_days, format_duration};
use crate::call_notes::Notes;
use crate::phone_number::PhoneNumber;
use crate::recents::{ExtraCall, number_in, wanted_numbers};
use crate::search::fold;
use crate::wire::{CallLogEntry, CallLogKind};

/// Which calls Recents shows.
#[derive(
    Clone, Copy, Debug, Default, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize,
)]
#[serde(rename_all = "snake_case")]
pub enum RecentsFilter {
    #[default]
    All,
    Missed,
    Incoming,
    Outgoing,
    #[serde(rename = "whatsapp")]
    WhatsApp,
    /// Calls that have a note; needs the notes, see [`apply_with_notes`].
    WithNotes,
}

impl RecentsFilter {
    pub const ALL: [Self; 6] = [
        Self::All,
        Self::Missed,
        Self::Incoming,
        Self::Outgoing,
        Self::WhatsApp,
        Self::WithNotes,
    ];

    pub fn label(self) -> &'static str {
        match self {
            Self::All => "All",
            Self::Missed => "Missed",
            Self::Incoming => "Incoming",
            Self::Outgoing => "Outgoing",
            Self::WhatsApp => "WhatsApp",
            Self::WithNotes => "Notes",
        }
    }

    pub fn keeps(self, row: &RecentsRow) -> bool {
        match self {
            Self::All => true,
            Self::Missed => row.call_type == CallType::Missed,
            Self::Incoming => row.call_type == CallType::Incoming,
            Self::Outgoing => row.call_type == CallType::Outgoing,
            Self::WhatsApp => row.whatsapp,
            // Needs the notes: only `apply_with_notes` can keep these.
            Self::WithNotes => false,
        }
    }
}

/// Search text plus filter chip.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Query {
    pub text: String,
    pub filter: RecentsFilter,
}

fn digits_of(s: &str) -> String {
    s.chars().filter(char::is_ascii_digit).collect()
}

/// Case- and accent-insensitive name match, or a digit match for text with at
/// least two digits (formatting, `+` and a trunk zero are ignored).
pub fn matches(row: &RecentsRow, text: &str) -> bool {
    let text = text.trim();
    if text.is_empty() {
        return true;
    }
    let folded = fold(text);
    if let Some(name) = &row.name
        && fold(name).contains(&folded)
    {
        return true;
    }
    let q = digits_of(text);
    if q.len() < 2 {
        return false;
    }
    let national = q
        .strip_prefix("00")
        .or_else(|| q.strip_prefix('0'))
        .unwrap_or(&q);
    if national.is_empty() {
        return false;
    }
    [&row.normalized, &row.number_raw]
        .into_iter()
        .map(|n| digits_of(n))
        .any(|d| d.contains(&q) || d.contains(national))
}

/// Indices of the rows that pass the query, in input order.
pub fn apply(rows: &[RecentsRow], query: &Query) -> Vec<usize> {
    rows.iter()
        .enumerate()
        .filter(|(_, r)| query.filter.keeps(r) && matches(r, &query.text))
        .map(|(i, _)| i)
        .collect()
}

/// Like [`apply`], but a row also passes when a call note written for it
/// contains the search text (at least two chars after trimming).
pub fn apply_with_notes(rows: &[RecentsRow], query: &Query, notes: &Notes) -> Vec<usize> {
    let text = query.text.trim();
    let folded = if text.chars().count() >= 2 {
        fold(text)
    } else {
        String::new()
    };
    rows.iter()
        .enumerate()
        .filter(|(_, r)| {
            let text_ok = matches(r, &query.text)
                || notes.mentions(&r.number_raw, r.first_ms, r.last_ms, &folded);
            if query.filter == RecentsFilter::WithNotes {
                text_ok && notes.has_note(&r.number_raw, r.first_ms, r.last_ms)
            } else {
                query.filter.keeps(r) && text_ok
            }
        })
        .map(|(i, _)| i)
        .collect()
}

/// The key of a row: its first ref, which is the newest call (refs keep the
/// newest-first input order).
fn row_key(row: &RecentsRow) -> Option<EntryRef> {
    row.refs.first().cloned()
}

/// The rows chosen in Recents select mode, keyed by each row's newest ref so
/// a rebuild after the log changes keeps the choice.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Selection {
    keys: BTreeSet<EntryRef>,
}

impl Selection {
    /// Flips the row; true when it is now selected. A row without refs is
    /// never selected.
    pub fn toggle(&mut self, row: &RecentsRow) -> bool {
        let Some(key) = row_key(row) else {
            return false;
        };
        if self.keys.remove(&key) {
            false
        } else {
            self.keys.insert(key);
            true
        }
    }

    pub fn contains(&self, row: &RecentsRow) -> bool {
        row_key(row).is_some_and(|k| self.keys.contains(&k))
    }

    /// Selects every visible row (`visible` indexes `rows`).
    pub fn select_all(&mut self, rows: &[RecentsRow], visible: &[usize]) {
        self.keys.extend(
            visible
                .iter()
                .filter_map(|&i| rows.get(i))
                .filter_map(row_key),
        );
    }

    pub fn clear(&mut self) {
        self.keys.clear();
    }

    pub fn len(&self) -> usize {
        self.keys.len()
    }

    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }

    /// Drops the keys whose row is gone.
    pub fn retain_present(&mut self, rows: &[RecentsRow]) {
        let present: BTreeSet<EntryRef> = rows.iter().filter_map(row_key).collect();
        self.keys.retain(|k| present.contains(k));
    }

    /// Every ref of every selected row, for deleting.
    pub fn refs(&self, rows: &[RecentsRow]) -> Vec<EntryRef> {
        rows.iter()
            .filter(|r| self.contains(r))
            .flat_map(|r| r.refs.iter().cloned())
            .collect()
    }

    /// The `number_raw` of each selected callable row, in row order, one per
    /// person (same-number rows are compared by [`PhoneNumber::matches`], or
    /// as trimmed text when a number does not parse).
    pub fn numbers(&self, rows: &[RecentsRow]) -> Vec<String> {
        let mut seen: Vec<(Option<PhoneNumber>, String)> = Vec::new();
        let mut out = Vec::new();
        for r in rows.iter().filter(|r| r.callable && self.contains(r)) {
            let text = r.number_raw.trim();
            if text.is_empty() {
                continue;
            }
            let parsed = PhoneNumber::parse(text).ok();
            let dup = seen.iter().any(|(p, t)| match (p, &parsed) {
                (Some(a), Some(b)) => a.matches(b),
                _ => t == text,
            });
            if !dup {
                seen.push((parsed, text.to_owned()));
                out.push(r.number_raw.clone());
            }
        }
        out
    }

    /// Calls behind the selected rows.
    pub fn call_count(&self, rows: &[RecentsRow]) -> u32 {
        rows.iter()
            .filter(|r| self.contains(r))
            .map(|r| r.count)
            .sum()
    }
}

/// One line per selected number: `Anna Smith: +90 532 111 22 33`, or just the
/// grouped number. Empty when nothing callable is selected.
pub fn select_share_text(
    rows: &[RecentsRow],
    sel: &Selection,
    name_of: impl Fn(&str) -> Option<String>,
) -> String {
    sel.numbers(rows)
        .iter()
        .map(|raw| {
            let shown = PhoneNumber::parse(raw).map_or_else(
                |_| raw.trim().to_owned(),
                |p| crate::call::format_number(&p),
            );
            match name_of(raw).filter(|n| !n.trim().is_empty()) {
                Some(name) => format!("{}: {shown}", name.trim()),
                None => shown,
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Title and message of the block confirmation for `n` numbers.
pub fn select_block_text(n: usize) -> (String, String) {
    let title = if n == 1 {
        "Block this number?".to_owned()
    } else {
        format!("Block {n} numbers?")
    };
    (
        title,
        "Calls and messages from them will be blocked on this phone.".to_owned(),
    )
}

/// Pill text when the selection has no number to block or share.
pub const fn select_nothing_callable_text() -> &'static str {
    "No numbers to block or share"
}

/// Title of the select-mode header.
pub fn select_title(n: usize) -> String {
    if n == 0 {
        "Select calls".to_owned()
    } else {
        format!("{n} selected")
    }
}

/// Title and body of the confirm for deleting `rows` rows holding `calls` calls.
pub fn delete_selected_text(rows: usize, calls: u32) -> (String, String) {
    let title = if rows == 1 {
        "Delete this row?".to_owned()
    } else {
        format!("Delete {rows} rows?")
    };
    let body = if calls == 1 {
        "1 call will be removed from this phone's call log.".to_owned()
    } else {
        format!("{calls} calls will be removed from this phone's call log.")
    };
    (title, body)
}

/// The call-log entries (blocked ones excluded) whose number is one of
/// `numbers`, in input order. Same matching as `call_log::ids_for_numbers`.
pub fn entries_for_numbers(entries: &[CallLogEntry], numbers: &[&str]) -> Vec<CallLogEntry> {
    let wanted = wanted_numbers(numbers);
    if wanted.is_empty() {
        return Vec::new();
    }
    entries
        .iter()
        .filter(|e| e.kind != CallLogKind::Blocked)
        .filter(|e| {
            let raw = if e.number_raw.trim().is_empty() {
                e.normalized.as_str()
            } else {
                e.number_raw.as_str()
            };
            number_in(raw, &wanted)
        })
        .cloned()
        .collect()
}

/// The extra (WhatsApp) calls whose number is one of `numbers`.
pub fn extras_for_numbers(extras: &[ExtraCall], numbers: &[&str]) -> Vec<ExtraCall> {
    let wanted = wanted_numbers(numbers);
    extras
        .iter()
        .filter(|e| number_in(&e.number_raw, &wanted))
        .cloned()
        .collect()
}

/// The file name and CSV (with notes) of every call with `numbers`, or `None`
/// when there is nothing to export.
pub fn person_export(
    entries: &[CallLogEntry],
    extras: &[ExtraCall],
    numbers: &[&str],
    who: &str,
    now_ms: i64,
    tz_offset_s: i32,
    notes: &Notes,
) -> Option<(String, String)> {
    let mine = entries_for_numbers(entries, numbers);
    let wa = extras_for_numbers(extras, numbers);
    if mine.is_empty() && wa.is_empty() {
        return None;
    }
    Some((
        person_csv_file_name(who, now_ms, tz_offset_s),
        to_csv_with_notes(&mine, &wa, tz_offset_s, notes),
    ))
}

/// How old a call must be to be pruned from the log.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PruneAge {
    Days30,
    Days90,
    Year,
}

impl PruneAge {
    pub const ALL: [Self; 3] = [Self::Days30, Self::Days90, Self::Year];

    pub fn label(self) -> &'static str {
        match self {
            Self::Days30 => "Older than 30 days",
            Self::Days90 => "Older than 90 days",
            Self::Year => "Older than 1 year",
        }
    }

    fn days(self) -> i64 {
        match self {
            Self::Days30 => 30,
            Self::Days90 => 90,
            Self::Year => 365,
        }
    }

    /// Calls that started before this instant are older (`now_ms` minus the age).
    pub fn cutoff_ms(self, now_ms: i64) -> i64 {
        now_ms.saturating_sub(self.days() * 86_400_000)
    }

    /// The age in the confirmation text: `30 days`, `1 year`.
    fn span(self) -> &'static str {
        match self {
            Self::Days30 => "30 days",
            Self::Days90 => "90 days",
            Self::Year => "1 year",
        }
    }
}

/// Log ids and WhatsApp call ids of the calls that started strictly before
/// `cutoff_ms`.
pub fn ids_older_than(
    entries: &[CallLogEntry],
    extras: &[ExtraCall],
    cutoff_ms: i64,
) -> (Vec<i64>, Vec<String>) {
    let log = entries
        .iter()
        .filter(|e| e.date_ms < cutoff_ms)
        .map(|e| e.id)
        .collect();
    let wa = extras
        .iter()
        .filter(|x| x.started_ms < cutoff_ms)
        .map(|x| x.call_id.clone())
        .collect();
    (log, wa)
}

/// Title and body of the confirmation before pruning `count` calls.
pub fn prune_text(age: PruneAge, count: usize) -> (String, String) {
    let body = if count == 1 {
        "1 call will be removed from this phone's call log.".to_owned()
    } else {
        format!("{count} calls will be removed from this phone's call log.")
    };
    (format!("Delete calls older than {}?", age.span()), body)
}

/// Shown when there is nothing to prune.
pub fn prune_none_text(age: PruneAge) -> String {
    format!("No calls older than {}", age.span())
}

/// What to show when [`apply`] finds nothing.
pub fn empty_text(query: &Query) -> String {
    let text = query.text.trim();
    if !text.is_empty() {
        return format!("No calls match \"{text}\"");
    }
    match query.filter {
        RecentsFilter::All => "No calls yet".to_owned(),
        RecentsFilter::Missed => "No missed calls".to_owned(),
        RecentsFilter::Incoming => "No incoming calls".to_owned(),
        RecentsFilter::Outgoing => "No outgoing calls".to_owned(),
        RecentsFilter::WhatsApp => "No WhatsApp calls".to_owned(),
        RecentsFilter::WithNotes => "No calls with notes".to_owned(),
    }
}

const HEADER: &str = "date,time,direction,name,number,duration_s,duration,channel";
const NOTE_HEADER: &str = ",note";

/// Escapes one text cell: injection guard first, then RFC 4180 quoting.
pub(crate) fn cell(raw: &str) -> String {
    let mut chars = raw.chars();
    let risky = match chars.next() {
        Some('=' | '@' | '\t' | '\r') => true,
        Some('+' | '-') => !chars.next().is_some_and(|c| c.is_ascii_digit()),
        _ => false,
    };
    let mut s = String::with_capacity(raw.len() + 1);
    if risky {
        s.push('\'');
    }
    s.push_str(raw);
    if s.contains([',', '"', '\r', '\n']) {
        format!("\"{}\"", s.replace('"', "\"\""))
    } else {
        s
    }
}

fn direction(kind: CallLogKind) -> &'static str {
    match kind {
        CallLogKind::Incoming => "incoming",
        CallLogKind::Outgoing => "outgoing",
        CallLogKind::Missed => "missed",
        CallLogKind::Rejected => "declined",
        CallLogKind::Blocked => "blocked",
        CallLogKind::Voicemail => "voicemail",
        CallLogKind::Unknown => "unknown",
    }
}

pub(crate) fn local_date(ms: i64, tz_offset_s: i32) -> (String, String) {
    let secs = ms.div_euclid(1000) + i64::from(tz_offset_s);
    let (y, m, d) = civil_from_days(secs.div_euclid(86_400));
    let sod = secs.rem_euclid(86_400);
    (
        format!("{y:04}-{m:02}-{d:02}"),
        format!("{:02}:{:02}", sod / 3600, sod / 60 % 60),
    )
}

/// CSV of the call log and the WhatsApp calls, newest first, local time.
pub fn to_csv(entries: &[CallLogEntry], extras: &[ExtraCall], tz_offset_s: i32) -> String {
    csv(entries, extras, tz_offset_s, None)
}

/// Like `to_csv`, with a trailing `note` column filled from `notes`.
pub fn to_csv_with_notes(
    entries: &[CallLogEntry],
    extras: &[ExtraCall],
    tz_offset_s: i32,
    notes: &Notes,
) -> String {
    csv(entries, extras, tz_offset_s, Some(notes))
}

fn csv(
    entries: &[CallLogEntry],
    extras: &[ExtraCall],
    tz_offset_s: i32,
    notes: Option<&Notes>,
) -> String {
    struct Line<'a> {
        ms: i64,
        direction: &'static str,
        name: &'a str,
        number: &'a str,
        duration_s: u64,
        channel: &'static str,
    }
    let mut lines: Vec<Line> = entries
        .iter()
        .map(|e| Line {
            ms: e.date_ms,
            direction: direction(e.kind),
            name: e.cached_name.as_deref().unwrap_or(""),
            number: &e.number_raw,
            duration_s: e.duration_s.max(0) as u64,
            channel: "phone",
        })
        .chain(extras.iter().map(|x| Line {
            ms: x.started_ms,
            direction: if x.missed { "missed" } else { "unknown" },
            name: x.label.as_deref().unwrap_or(""),
            number: &x.number_raw,
            duration_s: x.duration_s,
            channel: "whatsapp",
        }))
        .collect();
    // Stable: the call log keeps its order on ties.
    lines.sort_by_key(|l| std::cmp::Reverse(l.ms));

    let mut out = String::from(HEADER);
    if notes.is_some() {
        out.push_str(NOTE_HEADER);
    }
    out.push_str("\r\n");
    for l in lines {
        let (date, time) = local_date(l.ms, tz_offset_s);
        out.push_str(&format!(
            "{date},{time},{},{},{},{},{},{}",
            l.direction,
            cell(l.name),
            cell(l.number),
            l.duration_s,
            format_duration(l.duration_s),
            l.channel
        ));
        if let Some(notes) = notes {
            let note = notes
                .find(l.number, l.ms)
                .map_or("", |i| notes.notes[i].text.as_str());
            out.push(',');
            out.push_str(&cell(note));
        }
        out.push_str("\r\n");
    }
    out
}

/// `rovsti-calls-YYYY-MM-DD.csv` for the local date of `now_ms`.
pub fn csv_file_name(now_ms: i64, tz_offset_s: i32) -> String {
    format!("rovsti-calls-{}.csv", local_date(now_ms, tz_offset_s).0)
}

/// `rovsti-calls-<who>-YYYY-MM-DD.csv`; the slug is lowercase ASCII
/// `[a-z0-9-]`, at most 32 chars. Falls back to [`csv_file_name`] when empty.
pub fn person_csv_file_name(who: &str, now_ms: i64, tz_offset_s: i32) -> String {
    let mut slug = String::new();
    for c in fold(who).chars() {
        if c.is_ascii_alphanumeric() {
            slug.push(c.to_ascii_lowercase());
        } else if !slug.ends_with('-') {
            slug.push('-');
        }
    }
    let slug: String = slug.trim_matches('-').chars().take(32).collect();
    let slug = slug.trim_end_matches('-');
    if slug.is_empty() {
        return csv_file_name(now_ms, tz_offset_s);
    }
    format!(
        "rovsti-calls-{slug}-{}.csv",
        local_date(now_ms, tz_offset_s).0
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::call_log::group;

    fn entry(id: i64, number: &str, kind: CallLogKind, ms: i64) -> CallLogEntry {
        CallLogEntry {
            id,
            number_raw: number.into(),
            normalized: number.into(),
            kind,
            date_ms: ms,
            ..Default::default()
        }
    }

    fn row(number: &str, name: Option<&str>, t: CallType, wa: bool) -> RecentsRow {
        RecentsRow {
            number_raw: number.into(),
            normalized: number.into(),
            call_type: t,
            count: 1,
            first_ms: 0,
            last_ms: 0,
            name: name.map(Into::into),
            label: None,
            contact_id: None,
            callable: true,
            account_key: None,
            whatsapp: wa,
            refs: vec![],
        }
    }

    fn sample() -> Vec<RecentsRow> {
        vec![
            row(
                "+905551234567",
                Some("Şükrü Çelik"),
                CallType::Missed,
                false,
            ),
            row("+4915112345678", Some("Anna"), CallType::Incoming, false),
            row("05321112233", None, CallType::Outgoing, false),
            row("+905559998877", Some("Zoe"), CallType::Unknown, true),
            row("+905550001122", None, CallType::Declined, false),
        ]
    }

    fn q(text: &str, filter: RecentsFilter) -> Query {
        Query {
            text: text.into(),
            filter,
        }
    }

    #[test]
    fn every_filter() {
        let rows = sample();
        let run = |f| apply(&rows, &q("", f));
        assert_eq!(run(RecentsFilter::All), vec![0, 1, 2, 3, 4]);
        assert_eq!(run(RecentsFilter::Missed), vec![0]);
        assert_eq!(run(RecentsFilter::Incoming), vec![1]);
        assert_eq!(run(RecentsFilter::Outgoing), vec![2]);
        assert_eq!(run(RecentsFilter::WhatsApp), vec![3]);
        assert_eq!(RecentsFilter::default(), RecentsFilter::All);
        assert_eq!(RecentsFilter::ALL.len(), 6);
        assert_eq!(RecentsFilter::WhatsApp.label(), "WhatsApp");
        assert_eq!(RecentsFilter::WithNotes.label(), "Notes");
        // Plain apply has no notes, so nothing passes.
        assert!(run(RecentsFilter::WithNotes).is_empty());
    }

    #[test]
    fn name_search_ignores_case_and_accents() {
        let rows = sample();
        assert_eq!(apply(&rows, &q("sukru", RecentsFilter::All)), vec![0]);
        assert_eq!(apply(&rows, &q("ÇELİK", RecentsFilter::All)), vec![0]);
        assert_eq!(apply(&rows, &q("ann", RecentsFilter::All)), vec![1]);
        assert!(apply(&rows, &q("nobody", RecentsFilter::All)).is_empty());
    }

    #[test]
    fn digit_search_matches_national_and_international() {
        let rows = sample();
        assert_eq!(
            apply(&rows, &q("0555 123 45 67", RecentsFilter::All)),
            vec![0]
        );
        assert_eq!(apply(&rows, &q("+90 555 123", RecentsFilter::All)), vec![0]);
        assert_eq!(apply(&rows, &q("(532) 111", RecentsFilter::All)), vec![2]);
        assert_eq!(
            apply(&rows, &q("0090555", RecentsFilter::All)),
            vec![0, 3, 4]
        );
        assert!(apply(&rows, &q("00", RecentsFilter::All)).is_empty());
    }

    #[test]
    fn single_digit_does_not_match_numbers() {
        let rows = sample();
        assert!(apply(&rows, &q("5", RecentsFilter::All)).is_empty());
    }

    #[test]
    fn filter_and_text_combine() {
        let rows = sample();
        assert!(apply(&rows, &q("anna", RecentsFilter::Missed)).is_empty());
        assert_eq!(apply(&rows, &q("555", RecentsFilter::WhatsApp)), vec![3]);
    }

    #[test]
    fn empty_texts() {
        assert_eq!(empty_text(&Query::default()), "No calls yet");
        assert_eq!(empty_text(&q("", RecentsFilter::Missed)), "No missed calls");
        assert_eq!(
            empty_text(&q(" bob ", RecentsFilter::All)),
            "No calls match \"bob\""
        );
    }

    #[test]
    fn csv_empty_log_is_header_only() {
        assert_eq!(to_csv(&[], &[], 0), format!("{HEADER}\r\n"));
    }

    #[test]
    fn csv_rows_quoting_and_guard() {
        let mut a = entry(1, "+905551234567", CallLogKind::Outgoing, 1_700_000_000_000);
        a.cached_name = Some("Doe, \"J\"".into());
        a.duration_s = 3725;
        let mut b = entry(2, "=SUM(1)", CallLogKind::Blocked, 1_700_000_100_000);
        b.cached_name = Some("-evil".into());
        let csv = to_csv(&[a, b], &[], 3 * 3600);
        let lines: Vec<&str> = csv.split("\r\n").collect();
        assert_eq!(lines[0], HEADER);
        assert_eq!(
            lines[1],
            "2023-11-15,01:15,blocked,'-evil,'=SUM(1),0,0:00,phone"
        );
        assert_eq!(
            lines[2],
            "2023-11-15,01:13,outgoing,\"Doe, \"\"J\"\"\",+905551234567,3725,1:02:05,phone"
        );
        assert_eq!(lines[3], "");
    }

    #[test]
    fn guard_cases() {
        assert_eq!(cell("+90555"), "+90555");
        assert_eq!(cell("-5"), "-5");
        assert_eq!(cell("+"), "'+");
        assert_eq!(cell("@x"), "'@x");
        assert_eq!(cell("\tx"), "'\tx");
        assert_eq!(cell("plain"), "plain");
    }

    #[test]
    fn csv_merges_extras_newest_first() {
        let a = entry(1, "111", CallLogKind::Incoming, 2_000);
        let b = entry(2, "222", CallLogKind::Missed, 4_000);
        let x = ExtraCall {
            number_raw: "+905551112233".into(),
            started_ms: 3_000,
            duration_s: 61,
            missed: false,
            outgoing: false,
            label: Some("Zoe".into()),
            call_id: "w1".into(),
        };
        let csv = to_csv(&[b, a], &[x], 0);
        let lines: Vec<&str> = csv.lines().collect();
        assert_eq!(lines.len(), 4);
        assert!(lines[1].contains(",missed,") && lines[1].ends_with(",phone"));
        assert_eq!(
            lines[2],
            "1970-01-01,00:00,unknown,Zoe,+905551112233,61,1:01,whatsapp"
        );
        assert!(lines[3].contains(",incoming,"));
    }

    #[test]
    fn file_name_uses_local_date() {
        // 2023-11-14T22:13:20Z
        assert_eq!(
            csv_file_name(1_700_000_000_000, 0),
            "rovsti-calls-2023-11-14.csv"
        );
        assert_eq!(
            csv_file_name(1_700_000_000_000, 3 * 3600),
            "rovsti-calls-2023-11-15.csv"
        );
    }

    #[test]
    fn grouped_rows_work_with_filters() {
        let rows = group(&[entry(1, "+905551234567", CallLogKind::Missed, 5)]);
        assert_eq!(apply(&rows, &q("", RecentsFilter::Missed)), vec![0]);
    }

    #[test]
    fn csv_with_notes_adds_guarded_column() {
        let a = entry(1, "+905551234567", CallLogKind::Outgoing, 1_700_000_000_000);
        let b = entry(2, "+905559999999", CallLogKind::Incoming, 1_700_000_100_000);
        let c = entry(3, "+905550000000", CallLogKind::Incoming, 1_700_000_200_000);
        let mut notes = Notes::default();
        notes.set("05551234567", 1_700_000_010_000, "=cmd, \"x\"", 0);
        notes.set("+905559999999", 1_700_000_100_000, "plain", 0);
        let csv = to_csv_with_notes(&[a.clone(), b, c], &[], 0, &notes);
        let lines: Vec<&str> = csv.split("\r\n").collect();
        assert_eq!(lines[0], format!("{HEADER},note"));
        assert!(lines[1].ends_with(",phone,"));
        assert!(lines[2].ends_with(",phone,plain"));
        assert!(lines[3].ends_with(",phone,\"'=cmd, \"\"x\"\"\""));
        assert_eq!(to_csv(&[a], &[], 0).lines().next(), Some(HEADER));
    }

    fn note(number: &str, at_ms: i64, text: &str) -> crate::call_notes::CallNote {
        crate::call_notes::CallNote {
            number: number.into(),
            at_ms,
            text: text.into(),
            edited_ms: 0,
        }
    }

    fn notes_of(list: Vec<crate::call_notes::CallNote>) -> Notes {
        Notes {
            schema: 1,
            notes: list,
        }
    }

    #[test]
    fn notes_mentions_accents_case_and_number_forms() {
        let n = notes_of(vec![note("+905551234567", 1_000_000, "Ödeme ÇOK önemli")]);
        assert!(n.mentions("0555 123 45 67", 1_000_000, 1_000_000, &fold("odeme")));
        assert!(n.mentions("+905551234567", 1_000_000, 1_000_000, &fold("çok ÖNEMLİ")));
        assert!(!n.mentions("+905559999999", 1_000_000, 1_000_000, &fold("odeme")));
        assert!(!n.mentions("+905551234567", 1_000_000, 1_000_000, ""));
        assert!(!n.mentions("", 1_000_000, 1_000_000, "odeme"));
        assert!(!n.mentions("private", 1_000_000, 1_000_000, "odeme"));
    }

    #[test]
    fn notes_mentions_window_edges() {
        use crate::call_notes::MATCH_WINDOW_MS as W;
        let n = notes_of(vec![note("+905551234567", 10_000_000, "alpha")]);
        let hit = |first, last| n.mentions("+905551234567", first, last, "alpha");
        assert!(hit(10_000_000 + W, 10_000_000 + W));
        assert!(!hit(10_000_000 + W + 1, 10_000_000 + W + 1));
        assert!(hit(10_000_000 - W, 10_000_000 - W));
        assert!(!hit(10_000_000 - W - 1, 10_000_000 - W - 1));
        // A collapsed row spans the note.
        assert!(hit(0, 20_000_000));
    }

    #[test]
    fn apply_with_notes_uses_notes_and_one_char_is_ignored() {
        let mut rows = sample();
        rows[2].first_ms = 5_000;
        rows[2].last_ms = 5_000;
        let n = notes_of(vec![note("05321112233", 5_000, "Invoice Zürich")]);
        assert_eq!(
            apply_with_notes(&rows, &q("zurich", RecentsFilter::All), &n),
            [2]
        );
        assert!(apply_with_notes(&rows, &q("zurich", RecentsFilter::Missed), &n).is_empty());
        // One char: notes are not searched ("z" still matches Zoe by name).
        assert_eq!(
            apply_with_notes(&rows, &q("i", RecentsFilter::All), &n),
            apply(&rows, &q("i", RecentsFilter::All))
        );
    }

    #[test]
    fn with_notes_filter_needs_a_note_with_or_without_text() {
        let mut rows = sample();
        rows[2].first_ms = 5_000;
        rows[2].last_ms = 5_000;
        let n = notes_of(vec![note("05321112233", 5_000, "Invoice")]);
        let notes_only = q("", RecentsFilter::WithNotes);
        assert_eq!(apply_with_notes(&rows, &notes_only, &n), [2]);
        // Text matches by name/number or by the note.
        assert_eq!(
            apply_with_notes(&rows, &q("invoice", RecentsFilter::WithNotes), &n),
            [2]
        );
        assert_eq!(
            apply_with_notes(&rows, &q("532", RecentsFilter::WithNotes), &n),
            [2]
        );
        assert!(apply_with_notes(&rows, &q("nothing", RecentsFilter::WithNotes), &n).is_empty());
        assert!(apply_with_notes(&rows, &notes_only, &Notes::default()).is_empty());
        assert!(apply(&rows, &notes_only).is_empty());
        assert_eq!(empty_text(&notes_only), "No calls with notes");
    }

    #[test]
    fn prune_age_cutoffs() {
        let now = 1_000_000_000_000;
        assert_eq!(PruneAge::ALL.len(), 3);
        assert_eq!(PruneAge::Days30.cutoff_ms(now), now - 30 * 86_400_000);
        assert_eq!(PruneAge::Days90.cutoff_ms(now), now - 90 * 86_400_000);
        assert_eq!(PruneAge::Year.cutoff_ms(now), now - 365 * 86_400_000);
        assert_eq!(PruneAge::Days30.label(), "Older than 30 days");
        assert_eq!(PruneAge::Days90.label(), "Older than 90 days");
        assert_eq!(PruneAge::Year.label(), "Older than 1 year");
    }

    #[test]
    fn ids_older_than_keeps_the_boundary() {
        let e = |id, date_ms| CallLogEntry {
            id,
            date_ms,
            ..Default::default()
        };
        let x = |id: &str, started_ms| ExtraCall {
            number_raw: "+1".into(),
            started_ms,
            duration_s: 0,
            missed: false,
            outgoing: false,
            label: None,
            call_id: id.into(),
        };
        let (log, wa) = ids_older_than(
            &[e(1, 99), e(2, 100), e(3, 101)],
            &[x("a", 99), x("b", 100), x("c", 101)],
            100,
        );
        assert_eq!(log, [1]);
        assert_eq!(wa, ["a"]);
        let (log, wa) = ids_older_than(&[], &[], 100);
        assert!(log.is_empty() && wa.is_empty());
    }

    #[test]
    fn prune_texts_singular_and_plural() {
        assert_eq!(
            prune_text(PruneAge::Days30, 42),
            (
                "Delete calls older than 30 days?".to_owned(),
                "42 calls will be removed from this phone's call log.".to_owned()
            )
        );
        assert_eq!(
            prune_text(PruneAge::Year, 1),
            (
                "Delete calls older than 1 year?".to_owned(),
                "1 call will be removed from this phone's call log.".to_owned()
            )
        );
        assert_eq!(
            prune_none_text(PruneAge::Days90),
            "No calls older than 90 days"
        );
    }

    #[test]
    fn apply_with_empty_notes_equals_apply() {
        let rows = sample();
        for text in ["", "anna", "sukru", "+49", "05", "z", "xyz"] {
            for f in RecentsFilter::ALL {
                let query = q(text, f);
                assert_eq!(
                    apply_with_notes(&rows, &query, &Notes::default()),
                    apply(&rows, &query)
                );
            }
        }
    }

    fn sel_row(refs: Vec<EntryRef>, count: u32) -> RecentsRow {
        let mut r = row("+1", None, CallType::Incoming, false);
        r.count = count;
        r.refs = refs;
        r
    }

    #[test]
    fn selection_survives_a_rebuild_where_rows_shift() {
        let a = sel_row(vec![EntryRef::Log(5), EntryRef::Log(4)], 2);
        let b = sel_row(vec![EntryRef::Log(3)], 1);
        let c = sel_row(vec![EntryRef::WhatsApp("w".into())], 1);
        let mut sel = Selection::default();
        assert!(sel.toggle(&b));
        assert!(sel.contains(&b));
        assert!(!sel.contains(&a));
        // A new call appears on top; the rows shift by one.
        let rebuilt = [sel_row(vec![EntryRef::Log(9)], 1), a.clone(), b.clone(), c];
        assert!(sel.contains(&rebuilt[2]));
        assert!(!sel.contains(&rebuilt[1]));
        assert!(!sel.toggle(&b));
        assert!(sel.is_empty());
    }

    #[test]
    fn selection_retain_refs_count_and_select_all() {
        let a = sel_row(vec![EntryRef::Log(5), EntryRef::Log(4)], 2);
        let b = sel_row(vec![EntryRef::Log(3)], 1);
        let c = sel_row(vec![EntryRef::WhatsApp("w".into())], 3);
        let rows = vec![a.clone(), b.clone(), c.clone()];
        let mut sel = Selection::default();
        sel.select_all(&rows, &[0, 2, 7]);
        assert_eq!(sel.len(), 2);
        assert_eq!(
            sel.refs(&rows),
            [
                EntryRef::Log(5),
                EntryRef::Log(4),
                EntryRef::WhatsApp("w".into())
            ]
        );
        assert_eq!(sel.call_count(&rows), 5);
        // Row `a` is deleted.
        let after = vec![b, c];
        sel.retain_present(&after);
        assert_eq!(sel.len(), 1);
        assert_eq!(sel.call_count(&after), 3);
        sel.clear();
        assert!(sel.is_empty());
        // A row without refs cannot be selected.
        assert!(!sel.toggle(&sel_row(vec![], 1)));
    }

    #[test]
    fn person_export_filters_and_names_the_file() {
        let src = vec![
            entry(1, "0555 111 22 33", CallLogKind::Missed, 1_700_000_000_000),
            entry(2, "0444", CallLogKind::Incoming, 1_700_000_100_000),
        ];
        let (name, csv) = person_export(
            &src,
            &[],
            &["05551112233"],
            "Anna",
            1_700_000_000_000,
            0,
            &Notes::default(),
        )
        .expect("one call matches");
        assert!(name.to_lowercase().contains("anna") && name.ends_with(".csv"));
        assert!(csv.contains("0555 111 22 33"));
        assert!(!csv.contains("0444"));
    }

    #[test]
    fn person_export_is_none_without_calls() {
        let src = vec![entry(1, "0444", CallLogKind::Missed, 0)];
        assert!(person_export(&src, &[], &["0999"], "Bob", 0, 0, &Notes::default()).is_none());
        assert!(person_export(&src, &[], &[" "], "Bob", 0, 0, &Notes::default()).is_none());
    }

    #[test]
    fn select_and_delete_texts() {
        assert_eq!(select_title(0), "Select calls");
        assert_eq!(select_title(1), "1 selected");
        assert_eq!(select_title(3), "3 selected");
        assert_eq!(
            delete_selected_text(3, 7),
            (
                "Delete 3 rows?".to_owned(),
                "7 calls will be removed from this phone's call log.".to_owned()
            )
        );
        assert_eq!(
            delete_selected_text(1, 1),
            (
                "Delete this row?".to_owned(),
                "1 call will be removed from this phone's call log.".to_owned()
            )
        );
    }

    #[test]
    fn per_person_filters_keep_national_and_international_forms() {
        let src = [
            entry(4, "+905551112233", CallLogKind::Incoming, 40),
            entry(3, "05551112233", CallLogKind::Blocked, 30),
            entry(2, "0555 999 88 77", CallLogKind::Incoming, 20),
            entry(1, "05551112233", CallLogKind::Outgoing, 10),
        ];
        let got = entries_for_numbers(&src, &["0555 111 22 33"]);
        assert_eq!(got.iter().map(|e| e.id).collect::<Vec<_>>(), [4, 1]);
        assert!(entries_for_numbers(&src, &[" "]).is_empty());
        let extras = [
            ExtraCall {
                number_raw: "+905551112233".into(),
                started_ms: 1,
                duration_s: 1,
                missed: false,
                outgoing: true,
                label: None,
                call_id: "a".into(),
            },
            ExtraCall {
                number_raw: "+905550000000".into(),
                started_ms: 2,
                duration_s: 1,
                missed: false,
                outgoing: true,
                label: None,
                call_id: "b".into(),
            },
        ];
        let got = extras_for_numbers(&extras, &["05551112233"]);
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].call_id, "a");
        assert!(extras_for_numbers(&extras, &[""]).is_empty());
    }

    #[test]
    fn person_file_name_slug_cases() {
        // 2026-11-06 12:00 UTC.
        let now = 1_793_966_400_000;
        assert_eq!(
            person_csv_file_name("Ünal Öz", now, 0),
            "rovsti-calls-unal-oz-2026-11-06.csv"
        );
        assert_eq!(
            person_csv_file_name("  --Anna  /  Smith!! ", now, 0),
            "rovsti-calls-anna-smith-2026-11-06.csv"
        );
        assert_eq!(person_csv_file_name("😀", now, 0), csv_file_name(now, 0));
        assert_eq!(person_csv_file_name("", now, 0), csv_file_name(now, 0));
        let long = person_csv_file_name(&"ab ".repeat(30), now, 0);
        let slug = long
            .strip_prefix("rovsti-calls-")
            .and_then(|s| s.strip_suffix("-2026-11-06.csv"))
            .expect("slug");
        assert!(slug.len() <= 32 && !slug.ends_with('-'));
    }

    fn sel_of(rows: &[RecentsRow]) -> Selection {
        let mut sel = Selection::default();
        for r in rows {
            sel.toggle(r);
        }
        sel
    }

    fn numbered(number: &str, id: i64) -> RecentsRow {
        let mut r = row(number, None, CallType::Incoming, false);
        r.refs = vec![EntryRef::Log(id)];
        r
    }

    #[test]
    fn numbers_skip_private_and_repeats_in_row_order() {
        let mut private = numbered("", 1);
        private.callable = false;
        let rows = vec![
            numbered("05321112233", 2),
            private,
            numbered("+4915112345678", 3),
            numbered("+905321112233", 4),
            numbered("05321112233", 5),
            numbered("*123#", 6),
            numbered(" *123# ", 7),
            numbered("+905559998877", 8),
        ];
        let mut sel = sel_of(&rows);
        assert_eq!(
            sel.numbers(&rows),
            ["05321112233", "+4915112345678", "*123#", "+905559998877"]
        );
        sel.toggle(&rows[2]);
        assert_eq!(sel.numbers(&rows).len(), 3);
    }

    #[test]
    fn share_text_named_unnamed_and_empty() {
        let rows = vec![numbered("+905321112233", 1), numbered("+4915112345678", 2)];
        let sel = sel_of(&rows[..1]);
        let name = |n: &str| (n == "+905321112233").then(|| "Anna Smith".to_owned());
        assert_eq!(
            select_share_text(&rows, &sel, name),
            "Anna Smith: +90 532 111 22 33"
        );
        let both = sel_of(&rows);
        let text = select_share_text(&rows, &both, |_| None);
        assert!(text.starts_with("+90 532 111 22 33\n"));
        assert_eq!(text.lines().count(), 2);
        let mut private = numbered("", 3);
        private.callable = false;
        let only = [private];
        assert_eq!(select_share_text(&only, &sel_of(&only), |_| None), "");
    }

    #[test]
    fn block_text_singular_and_plural() {
        assert_eq!(select_block_text(1).0, "Block this number?");
        let (t, m) = select_block_text(3);
        assert_eq!(t, "Block 3 numbers?");
        assert_eq!(
            m,
            "Calls and messages from them will be blocked on this phone."
        );
        assert_eq!(
            select_nothing_callable_text(),
            "No numbers to block or share"
        );
    }
}
