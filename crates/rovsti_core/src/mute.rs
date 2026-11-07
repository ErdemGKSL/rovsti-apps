//! Mute durations offered by the header bell, and the "Muted until ..." pill.

use crate::call_log::{MONTHS_SHORT, WEEKDAYS, civil_from_days, day_key, weekday_index};

const HOUR_MS: i64 = 3_600_000;

/// How long a chat is muted for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MuteFor {
    EightHours,
    Week,
    Always,
}

impl MuteFor {
    pub const ALL: [Self; 3] = [Self::EightHours, Self::Week, Self::Always];

    pub fn label(self) -> &'static str {
        match self {
            Self::EightHours => "8 hours",
            Self::Week => "1 week",
            Self::Always => "Always",
        }
    }

    /// When the mute ends; `None` means never.
    pub fn until_ms(self, now_ms: i64) -> Option<i64> {
        match self {
            Self::EightHours => Some(now_ms + 8 * HOUR_MS),
            Self::Week => Some(now_ms + 7 * 24 * HOUR_MS),
            Self::Always => None,
        }
    }
}

/// "Muted", "Muted until 18:05" (today) or "Muted until Sat 14 Nov 09:00".
pub fn muted_notice(until_ms: Option<i64>, now_ms: i64, tz: i32) -> String {
    let Some(until) = until_ms else {
        return "Muted".to_owned();
    };
    let local = until.div_euclid(1000) + i64::from(tz);
    let secs = local.rem_euclid(86_400);
    let clock = format!("{:02}:{:02}", secs / 3600, secs / 60 % 60);
    let day = day_key(until, tz);
    if day == day_key(now_ms, tz) {
        return format!("Muted until {clock}");
    }
    let (_, m, d) = civil_from_days(day);
    let weekday = &WEEKDAYS[weekday_index(day)][..3];
    let month = MONTHS_SHORT[(m as usize - 1) % 12];
    format!("Muted until {weekday} {d} {month} {clock}")
}

/// The header line of a chat muted until a time: `None` for no end (or one that
/// has passed), so an "always" mute shows nothing extra.
pub fn header_line(until_ms: Option<i64>, now_ms: i64, tz: i32) -> Option<String> {
    until_ms
        .filter(|&u| u > now_ms)
        .map(|u| muted_notice(Some(u), now_ms, tz))
}

/// Whether a mute is still in force.
pub fn is_active(until_ms: Option<i64>, now_ms: i64) -> bool {
    until_ms.is_none_or(|u| u > now_ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Mon 9 Nov 2026 10:05 UTC.
    const NOW: i64 = 1_794_218_700_000;

    #[test]
    fn choices() {
        assert_eq!(MuteFor::ALL.len(), 3);
        let labels: Vec<_> = MuteFor::ALL.iter().map(|m| m.label()).collect();
        assert_eq!(labels, ["8 hours", "1 week", "Always"]);
        assert_eq!(MuteFor::EightHours.until_ms(1000), Some(1000 + 28_800_000));
        assert_eq!(MuteFor::Week.until_ms(0), Some(604_800_000));
        assert_eq!(MuteFor::Always.until_ms(0), None);
    }

    #[test]
    fn notices() {
        assert_eq!(muted_notice(None, NOW, 0), "Muted");
        assert_eq!(
            muted_notice(MuteFor::EightHours.until_ms(NOW), NOW, 0),
            "Muted until 18:05"
        );
        assert_eq!(
            muted_notice(MuteFor::Week.until_ms(NOW - 3_600_000), NOW, 0),
            "Muted until Mon 16 Nov 09:05"
        );
        // +2h zone moves the clock and can move the day.
        assert_eq!(
            muted_notice(MuteFor::EightHours.until_ms(NOW), NOW, 2 * 3600),
            "Muted until 20:05"
        );
        assert_eq!(
            muted_notice(Some(NOW + 15 * HOUR_MS), NOW, 0),
            "Muted until Tue 10 Nov 01:05"
        );
    }

    #[test]
    fn header_line_needs_a_future_end() {
        assert_eq!(header_line(None, NOW, 0), None);
        assert_eq!(header_line(Some(NOW), NOW, 0), None);
        assert_eq!(
            header_line(Some(NOW + HOUR_MS), NOW, 0).as_deref(),
            Some("Muted until 11:05")
        );
    }

    #[test]
    fn activity() {
        assert!(is_active(None, NOW));
        assert!(is_active(Some(NOW + 1), NOW));
        assert!(!is_active(Some(NOW), NOW));
        assert!(!is_active(Some(NOW - 1), NOW));
    }
}
