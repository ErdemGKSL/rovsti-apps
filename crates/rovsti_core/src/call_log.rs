//! Call-log grouping for Recents, the missed badge and row time labels.
//!
//! Pure functions over [`CallLogEntry`] (AD-14). Input is always newest first,
//! exactly as the provider returns it.

use crate::phone_number::PhoneNumber;
use crate::small_text::SmallText;
use crate::wire::{CallLogEntry, CallLogKind};

/// At most this many of the newest entries are considered.
pub const MAX_ENTRIES: usize = 500;

/// Row type shown in Recents.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum CallType {
    Incoming,
    Outgoing,
    Missed,
    /// Rejected by the user (`CallLog.Calls.REJECTED_TYPE`).
    Declined,
    Voicemail,
    Unknown,
}

impl CallType {
    /// `None` for blocked calls, which Recents drops.
    fn from_kind(kind: CallLogKind) -> Option<Self> {
        Some(match kind {
            CallLogKind::Incoming => Self::Incoming,
            CallLogKind::Outgoing => Self::Outgoing,
            CallLogKind::Missed => Self::Missed,
            CallLogKind::Rejected => Self::Declined,
            CallLogKind::Voicemail => Self::Voicemail,
            CallLogKind::Unknown => Self::Unknown,
            CallLogKind::Blocked => return None,
        })
    }
}

/// One line in Recents: a run of consecutive same-number, same-type calls.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RecentsRow {
    /// Number as the newest entry stored it; pass to the dialer unchanged.
    pub number_raw: String,
    pub normalized: String,
    pub call_type: CallType,
    /// How many entries collapsed into this row (at least 1).
    pub count: u32,
    /// Timestamp of the oldest entry in the run.
    pub first_ms: i64,
    /// Timestamp of the newest entry in the run (what the row displays).
    pub last_ms: i64,
    /// Cached provider name of the newest entry that had one.
    pub name: Option<String>,
    pub label: Option<String>,
    pub contact_id: Option<i64>,
    /// False for private/unknown numbers: nothing to call back.
    pub callable: bool,
}

/// How an entry's number is compared. Parsed once per entry.
enum Key {
    /// Empty/hidden number; groups only with other private entries.
    Private,
    Number(PhoneNumber),
    /// Not parsable (USSD, service names): equal only when identical.
    Raw(String),
}

impl Key {
    fn of(entry: &CallLogEntry) -> Self {
        let normalized = entry.normalized.trim();
        let raw = entry.number_raw.trim();
        if normalized.is_empty() && raw.is_empty() {
            return Self::Private;
        }
        let source = if normalized.is_empty() {
            raw
        } else {
            normalized
        };
        match PhoneNumber::parse(source).or_else(|_| PhoneNumber::parse(raw)) {
            Ok(n) => Self::Number(n),
            Err(_) => Self::Raw(source.to_owned()),
        }
    }

    fn same_line(&self, other: &Self) -> bool {
        match (self, other) {
            (Self::Private, Self::Private) => true,
            (Self::Number(a), Self::Number(b)) => a.matches(b),
            (Self::Raw(a), Self::Raw(b)) => a == b,
            _ => false,
        }
    }
}

/// Collapses consecutive entries with the same number (by
/// [`PhoneNumber::matches`]) and the same type. Blocked entries are dropped
/// and only the newest [`MAX_ENTRIES`] inputs are considered.
pub fn group(entries: &[CallLogEntry]) -> Vec<RecentsRow> {
    let mut rows: Vec<RecentsRow> = Vec::with_capacity(entries.len().min(MAX_ENTRIES));
    let mut last_key: Option<Key> = None;

    for entry in entries.iter().take(MAX_ENTRIES) {
        let Some(call_type) = CallType::from_kind(entry.kind) else {
            continue;
        };
        let key = Key::of(entry);
        if let (Some(row), Some(prev)) = (rows.last_mut(), last_key.as_ref())
            && row.call_type == call_type
            && prev.same_line(&key)
        {
            row.count += 1;
            row.first_ms = row.first_ms.min(entry.date_ms);
            row.last_ms = row.last_ms.max(entry.date_ms);
            if row.name.is_none() {
                row.name.clone_from(&entry.cached_name);
                row.label.clone_from(&entry.number_label);
            }
            if row.contact_id.is_none() {
                row.contact_id = entry.contact_id;
            }
            continue;
        }
        rows.push(RecentsRow {
            number_raw: entry.number_raw.clone(),
            normalized: entry.normalized.clone(),
            call_type,
            count: 1,
            first_ms: entry.date_ms,
            last_ms: entry.date_ms,
            name: entry.cached_name.clone(),
            label: entry.number_label.clone(),
            contact_id: entry.contact_id,
            callable: !matches!(key, Key::Private),
        });
        last_key = Some(key);
    }
    rows
}

/// Missed calls newer than `last_seen_ms` (strictly). Other types and older
/// misses do not count.
pub fn missed_badge(entries: &[CallLogEntry], last_seen_ms: i64) -> u32 {
    let n = entries
        .iter()
        .filter(|e| e.kind == CallLogKind::Missed && e.date_ms > last_seen_ms)
        .count();
    u32::try_from(n).unwrap_or(u32::MAX)
}

/// Badge text, or `None` when the badge is hidden (zero). Above 99 it reads
/// "99+".
pub fn badge_label(count: u32) -> Option<SmallText<3>> {
    if count == 0 {
        return None;
    }
    let mut out = SmallText::new();
    if count > 99 {
        out.push_str("99+");
    } else {
        out.push_padded(u64::from(count), 1);
    }
    Some(out)
}

/// Order of day, month and year in short dates.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DateOrder {
    DayMonthYear,
    MonthDayYear,
    YearMonthDay,
}

/// Locale choices injected by the caller, so tests never read the host locale.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TimeFormat {
    /// `3:14 pm` when true, `15:14` when false.
    pub hour12: bool,
    pub date_order: DateOrder,
}

/// "3:14 pm", "Yesterday", "Monday" or "12/10/26" (fits in 16 bytes).
pub type RelativeTime = SmallText<16>;

const WEEKDAYS: [&str; 7] = [
    "Sunday",
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
];

const SECS_PER_DAY: i64 = 86_400;

/// Time label for a Recents row.
///
/// Same day (or a timestamp in the future) shows the time, the day before
/// shows "Yesterday", two to six days back shows the weekday, anything
/// older (including previous years) a short date. `tz_offset_s` is the
/// local offset from UTC in seconds.
pub fn relative_time(
    now_ms: i64,
    then_ms: i64,
    tz_offset_s: i32,
    format: TimeFormat,
) -> RelativeTime {
    let local = |ms: i64| ms.div_euclid(1000) + i64::from(tz_offset_s);
    let (now_s, then_s) = (local(now_ms), local(then_ms));
    let age_days = now_s.div_euclid(SECS_PER_DAY) - then_s.div_euclid(SECS_PER_DAY);
    let then_day = then_s.div_euclid(SECS_PER_DAY);

    let mut out = RelativeTime::new();
    match age_days {
        ..=0 => push_clock(&mut out, then_s.rem_euclid(SECS_PER_DAY), format.hour12),
        1 => out.push_str("Yesterday"),
        2..=6 => out.push_str(WEEKDAYS[weekday_index(then_day)]),
        _ => push_short_date(&mut out, then_day, format.date_order),
    }
    out
}

/// 1970-01-01 was a Thursday; index 0 is Sunday.
fn weekday_index(days_since_epoch: i64) -> usize {
    (days_since_epoch + 4).rem_euclid(7) as usize
}

fn push_clock(out: &mut RelativeTime, secs_of_day: i64, hour12: bool) {
    let hour = (secs_of_day / 3600) as u64;
    let minute = ((secs_of_day / 60) % 60) as u64;
    if hour12 {
        let h = match hour % 12 {
            0 => 12,
            h => h,
        };
        out.push_padded(h, 1);
        out.push_str(":");
        out.push_padded(minute, 2);
        out.push_str(if hour < 12 { " am" } else { " pm" });
    } else {
        out.push_padded(hour, 2);
        out.push_str(":");
        out.push_padded(minute, 2);
    }
}

fn push_short_date(out: &mut RelativeTime, days_since_epoch: i64, order: DateOrder) {
    let (year, month, day) = civil_from_days(days_since_epoch);
    let yy = year.rem_euclid(100) as u64;
    let (month, day) = (u64::from(month), u64::from(day));
    let parts = match order {
        DateOrder::DayMonthYear => [day, month, yy],
        DateOrder::MonthDayYear => [month, day, yy],
        DateOrder::YearMonthDay => [yy, month, day],
    };
    for (i, part) in parts.iter().enumerate() {
        if i > 0 {
            out.push_str("/");
        }
        // The year part is always two digits; day/month are unpadded.
        let year_slot = match order {
            DateOrder::YearMonthDay => i == 0,
            _ => i == 2,
        };
        out.push_padded(*part, if year_slot { 2 } else { 1 });
    }
}

/// Days since 1970-01-01 to (year, month, day), proleptic Gregorian
/// (Howard Hinnant's `civil_from_days`).
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn e(id: i64, number: &str, kind: CallLogKind, date_ms: i64) -> CallLogEntry {
        CallLogEntry {
            id,
            number_raw: number.to_owned(),
            normalized: number.replace(' ', ""),
            kind,
            date_ms,
            duration_s: 0,
            cached_name: None,
            number_label: None,
            contact_id: None,
            ..Default::default()
        }
    }

    // ---- grouping ------------------------------------------------------

    #[test]
    fn only_consecutive_same_type_collapse() {
        let rows = group(&[
            e(4, "05551112233", CallLogKind::Missed, 4000),
            e(3, "05551112233", CallLogKind::Missed, 3000),
            e(2, "02125550011", CallLogKind::Incoming, 2000),
            e(1, "05551112233", CallLogKind::Missed, 1000),
        ]);
        assert_eq!(rows.len(), 3);
        assert_eq!((rows[0].count, rows[0].call_type), (2, CallType::Missed));
        assert_eq!(rows[0].number_raw, "05551112233");
        assert_eq!((rows[0].first_ms, rows[0].last_ms), (3000, 4000));
        assert_eq!((rows[1].count, rows[1].call_type), (1, CallType::Incoming));
        assert_eq!((rows[2].count, rows[2].call_type), (1, CallType::Missed));
    }

    #[test]
    fn same_number_different_type_does_not_collapse() {
        let rows = group(&[
            e(2, "05551112233", CallLogKind::Missed, 2000),
            e(1, "05551112233", CallLogKind::Incoming, 1000),
        ]);
        assert_eq!(rows.len(), 2);
    }

    #[test]
    fn formatting_variants_collapse_as_one_number() {
        let rows = group(&[
            e(2, "+905551112233", CallLogKind::Missed, 2000),
            e(1, "05551112233", CallLogKind::Missed, 1000),
        ]);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].count, 2);
        // The newest entry's spelling is kept for call back.
        assert_eq!(rows[0].number_raw, "+905551112233");
    }

    #[test]
    fn different_numbers_do_not_collapse() {
        let rows = group(&[
            e(2, "05551112233", CallLogKind::Missed, 2000),
            e(1, "05551112234", CallLogKind::Missed, 1000),
        ]);
        assert_eq!(rows.len(), 2);
    }

    #[test]
    fn private_numbers_group_only_with_each_other_and_are_not_callable() {
        let rows = group(&[
            e(4, "", CallLogKind::Missed, 4000),
            e(3, "", CallLogKind::Missed, 3000),
            e(2, "05551112233", CallLogKind::Missed, 2000),
            e(1, "", CallLogKind::Missed, 1000),
        ]);
        assert_eq!(rows.len(), 3);
        assert_eq!((rows[0].count, rows[0].callable), (2, false));
        assert!(rows[1].callable);
        assert!(!rows[2].callable);
    }

    #[test]
    fn unparsable_numbers_group_when_identical() {
        let rows = group(&[
            e(3, "*123#", CallLogKind::Outgoing, 3000),
            e(2, "*123#", CallLogKind::Outgoing, 2000),
            e(1, "*124#", CallLogKind::Outgoing, 1000),
        ]);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].count, 2);
        assert!(rows[0].callable);
    }

    #[test]
    fn blocked_entries_are_dropped_and_do_not_split_runs() {
        let rows = group(&[
            e(3, "05551112233", CallLogKind::Missed, 3000),
            e(2, "05559998877", CallLogKind::Blocked, 2000),
            e(1, "05551112233", CallLogKind::Missed, 1000),
        ]);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].count, 2);
    }

    #[test]
    fn rejected_becomes_declined() {
        let rows = group(&[e(1, "05551112233", CallLogKind::Rejected, 1000)]);
        assert_eq!(rows[0].call_type, CallType::Declined);
    }

    #[test]
    fn voicemail_and_unknown_types_are_kept() {
        let rows = group(&[
            e(2, "0555", CallLogKind::Voicemail, 2000),
            e(1, "0556", CallLogKind::Unknown, 1000),
        ]);
        assert_eq!(rows[0].call_type, CallType::Voicemail);
        assert_eq!(rows[1].call_type, CallType::Unknown);
    }

    #[test]
    fn input_is_capped_at_the_500_newest() {
        let entries: Vec<_> = (0..700)
            .map(|i| {
                // Distinct numbers so nothing collapses.
                e(
                    i,
                    &format!("0{:010}", 5_000_000_000u64 + i as u64),
                    CallLogKind::Incoming,
                    10_000 - i,
                )
            })
            .collect();
        let rows = group(&entries);
        assert_eq!(rows.len(), MAX_ENTRIES);
        assert_eq!(rows[0].last_ms, 10_000);
        assert_eq!(rows[MAX_ENTRIES - 1].last_ms, 10_000 - 499);
    }

    #[test]
    fn cap_counts_blocked_entries_in_the_input() {
        let mut entries: Vec<_> = (0..MAX_ENTRIES as i64)
            .map(|i| e(i, "05551112233", CallLogKind::Blocked, 10_000 - i))
            .collect();
        entries.push(e(999, "05551112233", CallLogKind::Missed, 1));
        assert!(group(&entries).is_empty());
    }

    #[test]
    fn name_and_label_come_from_the_newest_entry_that_has_them() {
        let mut newest = e(2, "05551112233", CallLogKind::Missed, 2000);
        let mut older = e(1, "05551112233", CallLogKind::Missed, 1000);
        older.cached_name = Some("Mehmet".into());
        older.number_label = Some("Mobile".into());
        older.contact_id = Some(9);
        let rows = group(&[newest.clone(), older.clone()]);
        assert_eq!(rows[0].name.as_deref(), Some("Mehmet"));
        assert_eq!(rows[0].label.as_deref(), Some("Mobile"));
        assert_eq!(rows[0].contact_id, Some(9));

        newest.cached_name = Some("Mehmet B".into());
        let rows = group(&[newest, older]);
        assert_eq!(rows[0].name.as_deref(), Some("Mehmet B"));
    }

    #[test]
    fn empty_input_is_empty() {
        assert!(group(&[]).is_empty());
    }

    // ---- badge ---------------------------------------------------------

    #[test]
    fn badge_counts_only_newer_missed() {
        let entries = [
            e(5, "1", CallLogKind::Missed, 500),
            e(4, "1", CallLogKind::Missed, 400),
            e(3, "1", CallLogKind::Incoming, 300),
            e(2, "1", CallLogKind::Missed, 200),
            e(1, "1", CallLogKind::Missed, 100),
        ];
        assert_eq!(missed_badge(&entries, 250), 2);
        assert_eq!(missed_badge(&entries, 500), 0, "equal is already seen");
        assert_eq!(missed_badge(&entries, 0), 4);
        assert_eq!(missed_badge(&[], 0), 0);
    }

    #[test]
    fn badge_ignores_rejected_and_blocked() {
        let entries = [
            e(2, "1", CallLogKind::Rejected, 500),
            e(1, "1", CallLogKind::Blocked, 400),
        ];
        assert_eq!(missed_badge(&entries, 0), 0);
    }

    #[test]
    fn badge_with_over_a_hundred_misses() {
        let entries: Vec<_> = (0..150)
            .map(|i| e(i, "1", CallLogKind::Missed, 1000 + i))
            .collect();
        let n = missed_badge(&entries, 0);
        assert_eq!(n, 150);
        assert_eq!(badge_label(n).as_ref().map(SmallText::as_str), Some("99+"));
    }

    #[test]
    fn badge_label_table() {
        assert!(badge_label(0).is_none());
        let text = |n| badge_label(n).map(|t| t.to_string());
        assert_eq!(text(1).as_deref(), Some("1"));
        assert_eq!(text(9).as_deref(), Some("9"));
        assert_eq!(text(10).as_deref(), Some("10"));
        assert_eq!(text(99).as_deref(), Some("99"));
        assert_eq!(text(100).as_deref(), Some("99+"));
        assert_eq!(text(u32::MAX).as_deref(), Some("99+"));
    }

    // ---- relative time ---------------------------------------------------

    /// 2026-10-27 (Tuesday) 15:14:00 UTC.
    const NOW: i64 = 1_793_114_040_000;
    const DAY: i64 = 86_400_000;
    const DMY12: TimeFormat = TimeFormat {
        hour12: true,
        date_order: DateOrder::DayMonthYear,
    };

    #[test]
    fn sanity_of_the_test_epoch() {
        assert_eq!(
            civil_from_days(NOW.div_euclid(DAY)),
            (2026, 10, 27),
            "NOW constant"
        );
        assert_eq!(weekday_index(NOW.div_euclid(DAY)), 2, "Tuesday");
    }

    #[test]
    fn same_day_shows_the_time() {
        let then = NOW - 3 * 3_600_000 + 60_000; // 12:15 UTC
        assert_eq!(relative_time(NOW, then, 0, DMY12), "12:15 pm");
        assert_eq!(relative_time(NOW, NOW, 0, DMY12), "3:14 pm");
    }

    #[test]
    fn midnight_and_noon_in_12_hour_clock() {
        let midnight = NOW - (15 * 3600 + 14 * 60) * 1000;
        assert_eq!(relative_time(NOW, midnight, 0, DMY12), "12:00 am");
        assert_eq!(
            relative_time(NOW, midnight + 12 * 3_600_000, 0, DMY12),
            "12:00 pm"
        );
        assert_eq!(
            relative_time(NOW, midnight + 5 * 3_600_000 + 7 * 60_000, 0, DMY12),
            "5:07 am"
        );
    }

    #[test]
    fn twenty_four_hour_clock() {
        let f = TimeFormat {
            hour12: false,
            ..DMY12
        };
        assert_eq!(relative_time(NOW, NOW, 0, f), "15:14");
        let midnight = NOW - (15 * 3600 + 14 * 60) * 1000;
        assert_eq!(relative_time(NOW, midnight + 5 * 60_000, 0, f), "00:05");
    }

    #[test]
    fn yesterday() {
        assert_eq!(relative_time(NOW, NOW - DAY, 0, DMY12), "Yesterday");
        // 23:59 yesterday vs 00:01 today is still "Yesterday".
        let midnight = NOW - (15 * 3600 + 14 * 60) * 1000;
        assert_eq!(relative_time(NOW, midnight - 60_000, 0, DMY12), "Yesterday");
        assert_eq!(relative_time(NOW, midnight + 60_000, 0, DMY12), "12:01 am");
    }

    #[test]
    fn two_to_six_days_show_the_weekday() {
        assert_eq!(relative_time(NOW, NOW - 2 * DAY, 0, DMY12), "Sunday");
        assert_eq!(relative_time(NOW, NOW - 3 * DAY, 0, DMY12), "Saturday");
        assert_eq!(relative_time(NOW, NOW - 6 * DAY, 0, DMY12), "Wednesday");
    }

    #[test]
    fn seven_days_and_older_show_a_short_date() {
        assert_eq!(relative_time(NOW, NOW - 7 * DAY, 0, DMY12), "20/10/26");
        let mdy = TimeFormat {
            date_order: DateOrder::MonthDayYear,
            ..DMY12
        };
        assert_eq!(relative_time(NOW, NOW - 7 * DAY, 0, mdy), "10/20/26");
        let ymd = TimeFormat {
            date_order: DateOrder::YearMonthDay,
            ..DMY12
        };
        assert_eq!(relative_time(NOW, NOW - 7 * DAY, 0, ymd), "26/10/20");
    }

    #[test]
    fn previous_year_short_date() {
        // 2025-10-12 12:00 UTC.
        let then = 1_760_270_400_000;
        assert_eq!(relative_time(NOW, then, 0, DMY12), "12/10/25");
        // 2000-01-05: two-digit year keeps its leading zero.
        assert_eq!(relative_time(NOW, 947_073_600_000, 0, DMY12), "5/1/00");
    }

    #[test]
    fn timezone_offset_moves_the_day_boundary() {
        // 23:30 UTC yesterday is 02:30 today at UTC+3.
        let midnight_utc = NOW - (15 * 3600 + 14 * 60) * 1000;
        let then = midnight_utc - 30 * 60_000;
        assert_eq!(relative_time(NOW, then, 0, DMY12), "Yesterday");
        assert_eq!(relative_time(NOW, then, 3 * 3600, DMY12), "2:30 am");
    }

    #[test]
    fn future_timestamps_show_the_time() {
        assert_eq!(relative_time(NOW, NOW + 2 * DAY, 0, DMY12), "3:14 pm");
    }

    #[test]
    fn leap_day_date() {
        // 2024-02-29 12:00 UTC.
        assert_eq!(relative_time(NOW, 1_709_208_000_000, 0, DMY12), "29/2/24");
    }

    #[test]
    fn negative_epoch_does_not_panic() {
        let t = relative_time(NOW, -86_400_000 * 400, 0, DMY12);
        assert!(!t.is_empty());
    }
}
