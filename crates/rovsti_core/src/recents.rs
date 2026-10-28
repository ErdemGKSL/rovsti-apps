//! Row view-models for the Recents list (stories 6.1 to 6.3).
//!
//! The UI builds these once per call-log snapshot (and again when the time
//! labels need refreshing), never inside a frame: every string a row draws or
//! speaks is ready here, so scrolling only copies handles.

use crate::{
    avatar,
    call_log::{CallType, DateOrder, RecentsRow, TimeFormat, relative_time},
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
    fn of(call_type: CallType) -> Self {
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
    }
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
}
