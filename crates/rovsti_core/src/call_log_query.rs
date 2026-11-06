//! Recents search, filters and CSV export. Pure logic, no platform types.

use crate::call_log::{CallType, RecentsRow, civil_from_days, format_duration};
use crate::recents::ExtraCall;
use crate::search::fold;
use crate::wire::{CallLogEntry, CallLogKind};

/// Which calls Recents shows.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum RecentsFilter {
    #[default]
    All,
    Missed,
    Incoming,
    Outgoing,
    WhatsApp,
}

impl RecentsFilter {
    pub const ALL: [Self; 5] = [
        Self::All,
        Self::Missed,
        Self::Incoming,
        Self::Outgoing,
        Self::WhatsApp,
    ];

    pub fn label(self) -> &'static str {
        match self {
            Self::All => "All",
            Self::Missed => "Missed",
            Self::Incoming => "Incoming",
            Self::Outgoing => "Outgoing",
            Self::WhatsApp => "WhatsApp",
        }
    }

    pub fn keeps(self, row: &RecentsRow) -> bool {
        match self {
            Self::All => true,
            Self::Missed => row.call_type == CallType::Missed,
            Self::Incoming => row.call_type == CallType::Incoming,
            Self::Outgoing => row.call_type == CallType::Outgoing,
            Self::WhatsApp => row.whatsapp,
        }
    }
}

/// Search text plus filter chip.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
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
    }
}

const HEADER: &str = "date,time,direction,name,number,duration_s,duration,channel";

/// Escapes one text cell: injection guard first, then RFC 4180 quoting.
fn cell(raw: &str) -> String {
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

fn local_date(ms: i64, tz_offset_s: i32) -> (String, String) {
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
    out.push_str("\r\n");
    for l in lines {
        let (date, time) = local_date(l.ms, tz_offset_s);
        out.push_str(&format!(
            "{date},{time},{},{},{},{},{},{}\r\n",
            l.direction,
            cell(l.name),
            cell(l.number),
            l.duration_s,
            format_duration(l.duration_s),
            l.channel
        ));
    }
    out
}

/// `rovsti-calls-YYYY-MM-DD.csv` for the local date of `now_ms`.
pub fn csv_file_name(now_ms: i64, tz_offset_s: i32) -> String {
    format!("rovsti-calls-{}.csv", local_date(now_ms, tz_offset_s).0)
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
        assert_eq!(RecentsFilter::ALL.len(), 5);
        assert_eq!(RecentsFilter::WhatsApp.label(), "WhatsApp");
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
}
