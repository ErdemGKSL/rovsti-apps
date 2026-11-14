//! "Jump to date" in a conversation: the day picker value, and the decision of
//! what to do once a day is chosen (reveal a message, load older pages, ...).
//!
//! Pure: days are local day numbers as [`day_key`] gives them.

use crate::call_log::{
    MONTHS_SHORT, WEEKDAYS, civil_from_days, day_key, days_from_civil, weekday_index,
};

/// Most pages of history loaded while looking for a day.
pub const MAX_DATE_PAGES: usize = 20;

const MS_PER_DAY: i64 = 86_400_000;

/// A local calendar day chosen in the picker.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DayPick {
    day: i64,
}

impl DayPick {
    /// The current local day.
    pub fn today(now_ms: i64, tz: i32) -> Self {
        Self {
            day: day_key(now_ms, tz),
        }
    }

    /// Moves by `d` days, never past today.
    pub fn step_day(self, d: i32, now_ms: i64, tz: i32) -> Self {
        let today = day_key(now_ms, tz);
        Self {
            day: (self.day + i64::from(d)).min(today),
        }
    }

    /// Moves by `d` months keeping the day of month (clamped to the length of
    /// the target month), never past today.
    pub fn step_month(self, d: i32, now_ms: i64, tz: i32) -> Self {
        let today = day_key(now_ms, tz);
        let (y, m, dom) = civil_from_days(self.day);
        let index = y * 12 + i64::from(m) - 1 + i64::from(d);
        let (ny, nm) = (index.div_euclid(12), index.rem_euclid(12) as u32 + 1);
        let dom = dom.min(month_len(ny, nm));
        Self {
            day: days_from_civil(ny, nm, dom).min(today),
        }
    }

    /// Local midnight of the day, in epoch milliseconds.
    pub fn start_ms(self, tz: i32) -> i64 {
        self.day * MS_PER_DAY - i64::from(tz) * 1000
    }

    /// "Today", "Yesterday", "Mon 9 Nov", or "9 Nov 2025" in another year.
    pub fn label(self, now_ms: i64, tz: i32) -> String {
        let today = day_key(now_ms, tz);
        match today - self.day {
            ..=0 => "Today".to_owned(),
            1 => "Yesterday".to_owned(),
            _ => {
                let (y, m, d) = civil_from_days(self.day);
                let month = MONTHS_SHORT[(m as usize - 1) % 12];
                if y == civil_from_days(today).0 {
                    let weekday = &WEEKDAYS[weekday_index(self.day)][..3];
                    format!("{weekday} {d} {month}")
                } else {
                    format!("{d} {month} {y}")
                }
            }
        }
    }

    pub fn is_today(self, now_ms: i64, tz: i32) -> bool {
        self.day >= day_key(now_ms, tz)
    }
}

fn month_len(y: i64, m: u32) -> u32 {
    let (ny, nm) = if m == 12 { (y + 1, 1) } else { (y, m + 1) };
    (days_from_civil(ny, nm, 1) - days_from_civil(y, m, 1)) as u32
}

/// Index of the first message at or after `start_ms` (`at_ms` oldest first).
pub fn first_on_or_after(at_ms: &[i64], start_ms: i64) -> Option<usize> {
    let i = at_ms.partition_point(|&t| t < start_ms);
    (i < at_ms.len()).then_some(i)
}

/// What to do for a chosen day.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DateStep {
    /// Scroll to and glow the message at this index.
    Reveal(usize),
    /// Load one more older page, then ask again.
    LoadOlder,
    /// The page cap was hit before reaching the day.
    FurtherBack,
    /// Nothing on or after that day: go to the newest message.
    Newest,
    /// The conversation has no messages.
    Empty,
}

/// Decides the next step; `at_ms` is the loaded timeline, oldest first.
pub fn date_step(at_ms: &[i64], has_older: bool, pages_loaded: usize, start_ms: i64) -> DateStep {
    if at_ms.is_empty() && !has_older {
        return DateStep::Empty;
    }
    let reached = at_ms.first().is_some_and(|&t| t <= start_ms);
    if reached || !has_older {
        return first_on_or_after(at_ms, start_ms).map_or(DateStep::Newest, DateStep::Reveal);
    }
    if pages_loaded >= MAX_DATE_PAGES {
        DateStep::FurtherBack
    } else {
        DateStep::LoadOlder
    }
}

/// The pill text for a step, when it needs one.
pub fn date_notice(step: DateStep, label: &str) -> Option<String> {
    match step {
        DateStep::FurtherBack => Some(format!(
            "Messages from {label} are further back. Scroll up to load them."
        )),
        DateStep::Newest => Some(format!("No messages from {label} or later")),
        DateStep::Empty => Some("No messages yet".to_owned()),
        DateStep::Reveal(_) | DateStep::LoadOlder => None,
    }
}

/// True when the revealed message is on a later day than the chosen one.
/// The quick chips of the sheet: Yesterday, 1 week ago, 1 month ago.
pub fn quick_picks(now_ms: i64, tz: i32) -> [(&'static str, DayPick); 3] {
    let today = DayPick::today(now_ms, tz);
    [
        ("Yesterday", today.step_day(-1, now_ms, tz)),
        ("1 week ago", today.step_day(-7, now_ms, tz)),
        ("1 month ago", today.step_month(-1, now_ms, tz)),
    ]
}

pub fn starts_later(at_ms: i64, start_ms: i64, tz: i32) -> bool {
    day_key(at_ms, tz) > day_key(start_ms, tz)
}

/// Pill for a reveal that landed on a later day.
pub fn later_notice(label: &str) -> String {
    format!("No messages on {label}: showing the next day with messages")
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY: i64 = MS_PER_DAY;

    fn at(y: i64, m: u32, d: u32, h: i64) -> i64 {
        days_from_civil(y, m, d) * DAY + h * 3_600_000
    }

    // Monday 9 Nov 2026, 12:00 UTC.
    fn now() -> i64 {
        at(2026, 11, 9, 12)
    }

    fn pick(y: i64, m: u32, d: u32) -> DayPick {
        DayPick::today(at(y, m, d, 12), 0)
    }

    #[test]
    fn quick_picks_step_back() {
        let n = now();
        let [y, w, m] = quick_picks(n, 0);
        assert_eq!((y.0, w.0, m.0), ("Yesterday", "1 week ago", "1 month ago"));
        assert_eq!(y.1, pick(2026, 11, 8));
        assert_eq!(w.1, pick(2026, 11, 2));
        assert_eq!(m.1, pick(2026, 10, 9));
    }

    #[test]
    fn labels() {
        let n = now();
        assert_eq!(DayPick::today(n, 0).label(n, 0), "Today");
        assert_eq!(pick(2026, 11, 8).label(n, 0), "Yesterday");
        assert_eq!(pick(2026, 11, 2).label(n, 0), "Mon 2 Nov");
        assert_eq!(pick(2026, 1, 1).label(n, 0), "Thu 1 Jan");
        assert_eq!(pick(2025, 11, 9).label(n, 0), "9 Nov 2025");
    }

    #[test]
    fn timezone_moves_today() {
        // 23:00 UTC is already the next day at +2h.
        let n = at(2026, 11, 9, 23);
        assert_eq!(DayPick::today(n, 0).label(n, 0), "Today");
        let p = pick(2026, 11, 9);
        assert_eq!(p.label(n, 2 * 3600), "Yesterday");
        assert!(!p.is_today(n, 2 * 3600));
        assert!(p.is_today(n, 0));
        assert_eq!(p.start_ms(0), at(2026, 11, 9, 0));
        assert_eq!(p.start_ms(2 * 3600), at(2026, 11, 9, 0) - 2 * 3_600_000);
        assert_eq!(p.start_ms(-3600), at(2026, 11, 9, 1));
    }

    #[test]
    fn step_day_never_after_today() {
        let n = now();
        let t = DayPick::today(n, 0);
        assert_eq!(t.step_day(1, n, 0), t);
        assert_eq!(t.step_day(-1, n, 0), pick(2026, 11, 8));
        assert_eq!(pick(2026, 11, 1).step_day(-1, n, 0), pick(2026, 10, 31));
        assert_eq!(pick(2026, 1, 1).step_day(-1, n, 0), pick(2025, 12, 31));
        assert_eq!(pick(2026, 11, 8).step_day(5, n, 0), t);
        assert_eq!(pick(2024, 2, 28).step_day(1, n, 0), pick(2024, 2, 29));
    }

    #[test]
    fn step_month_clamps_and_caps() {
        let n = now();
        assert_eq!(pick(2026, 3, 31).step_month(-1, n, 0), pick(2026, 2, 28));
        assert_eq!(pick(2024, 3, 31).step_month(-1, n, 0), pick(2024, 2, 29));
        assert_eq!(pick(2026, 1, 15).step_month(-1, n, 0), pick(2025, 12, 15));
        assert_eq!(pick(2025, 12, 15).step_month(1, n, 0), pick(2026, 1, 15));
        assert_eq!(pick(2026, 1, 31).step_month(1, n, 0), pick(2026, 2, 28));
        assert_eq!(
            pick(2026, 10, 31).step_month(1, n, 0),
            pick(2026, 11, 30).step_day(0, n, 0)
        );
        assert_eq!(pick(2026, 11, 3).step_month(1, n, 0), DayPick::today(n, 0));
        assert_eq!(pick(2026, 11, 9).step_month(-12, n, 0), pick(2025, 11, 9));
        assert_eq!(pick(2026, 11, 9).step_month(-24, n, 0), pick(2024, 11, 9));
    }

    #[test]
    fn first_on_or_after_finds_index() {
        let v = [10, 20, 20, 30];
        assert_eq!(first_on_or_after(&v, 5), Some(0));
        assert_eq!(first_on_or_after(&v, 20), Some(1));
        assert_eq!(first_on_or_after(&v, 21), Some(3));
        assert_eq!(first_on_or_after(&v, 31), None);
        assert_eq!(first_on_or_after(&[], 0), None);
    }

    #[test]
    fn every_step_branch() {
        let v = [100, 200, 300];
        assert_eq!(date_step(&v, true, 1, 150), DateStep::Reveal(1));
        assert_eq!(date_step(&v, true, 1, 100), DateStep::Reveal(0));
        assert_eq!(date_step(&v, false, 1, 50), DateStep::Reveal(0));
        assert_eq!(date_step(&v, false, 1, 400), DateStep::Newest);
        assert_eq!(date_step(&v, true, 1, 50), DateStep::LoadOlder);
        assert_eq!(
            date_step(&v, true, MAX_DATE_PAGES - 1, 50),
            DateStep::LoadOlder
        );
        assert_eq!(
            date_step(&v, true, MAX_DATE_PAGES, 50),
            DateStep::FurtherBack
        );
        assert_eq!(date_step(&[], false, 0, 50), DateStep::Empty);
        assert_eq!(date_step(&[], true, 0, 50), DateStep::LoadOlder);
        assert_eq!(
            date_step(&[], true, MAX_DATE_PAGES, 50),
            DateStep::FurtherBack
        );
    }

    #[test]
    fn notices() {
        assert_eq!(
            date_notice(DateStep::FurtherBack, "Mon 2 Nov").as_deref(),
            Some("Messages from Mon 2 Nov are further back. Scroll up to load them.")
        );
        assert_eq!(
            date_notice(DateStep::Newest, "Yesterday").as_deref(),
            Some("No messages from Yesterday or later")
        );
        assert_eq!(
            date_notice(DateStep::Empty, "x").as_deref(),
            Some("No messages yet")
        );
        assert_eq!(date_notice(DateStep::Reveal(0), "x"), None);
        assert_eq!(date_notice(DateStep::LoadOlder, "x"), None);
        assert_eq!(
            later_notice("Mon 2 Nov"),
            "No messages on Mon 2 Nov: showing the next day with messages"
        );
    }

    #[test]
    fn starts_later_compares_local_days() {
        let start = at(2026, 11, 9, 0);
        assert!(!starts_later(start + 5, start, 0));
        assert!(starts_later(start + DAY, start, 0));
        // 23:00 UTC on the 9th is the 10th at +2h.
        assert!(starts_later(at(2026, 11, 9, 23), start, 2 * 3600));
    }
}
