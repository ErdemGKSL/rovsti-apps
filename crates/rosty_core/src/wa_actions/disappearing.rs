//! Disappearing messages: the timer choices and when a message expires.

/// The timers the chat setting offers, in seconds: off, 24 hours, 7 days, 90 days (WhatsApp's own list).
pub const DISAPPEARING_CHOICES: [u32; 4] = [0, 86_400, 604_800, 7_776_000];

/// Whether `seconds` is one of [`DISAPPEARING_CHOICES`].
#[must_use]
pub fn is_disappearing_choice(seconds: u32) -> bool {
    DISAPPEARING_CHOICES.contains(&seconds)
}

/// A chat timer as the UI names it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Timer {
    Off,
    Day,
    Week,
    Days90,
    /// A timer another client set that is not one of the choices.
    Other(u32),
}

/// The [`Timer`] of `seconds` (`None` and 0 are off).
#[must_use]
pub fn timer_of(seconds: Option<u32>) -> Timer {
    match seconds.unwrap_or(0) {
        0 => Timer::Off,
        86_400 => Timer::Day,
        604_800 => Timer::Week,
        7_776_000 => Timer::Days90,
        other => Timer::Other(other),
    }
}

/// When a message sent at `sent_ms` with a timer of `expires_s` disappears (unix ms); `None` when it does not.
#[must_use]
pub fn expires_at(sent_ms: i64, expires_s: Option<u32>) -> Option<i64> {
    expires_s
        .filter(|s| *s > 0)
        .map(|s| sent_ms.saturating_add(i64::from(s) * 1000))
}

/// Whether a message that expires at `at` is gone at `now_ms`.
#[must_use]
pub fn is_expired(at: Option<i64>, now_ms: i64) -> bool {
    at.is_some_and(|at| at <= now_ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn choices_and_labels() {
        assert!(is_disappearing_choice(0));
        assert!(is_disappearing_choice(604_800));
        assert!(!is_disappearing_choice(3600));
        assert_eq!(timer_of(None), Timer::Off);
        assert_eq!(timer_of(Some(0)), Timer::Off);
        assert_eq!(timer_of(Some(86_400)), Timer::Day);
        assert_eq!(timer_of(Some(7_776_000)), Timer::Days90);
        assert_eq!(timer_of(Some(3600)), Timer::Other(3600));
    }

    #[test]
    fn expiry_from_send_time() {
        assert_eq!(expires_at(1_000, None), None);
        assert_eq!(expires_at(1_000, Some(0)), None);
        assert_eq!(expires_at(1_000, Some(86_400)), Some(86_401_000));
        assert!(!is_expired(None, i64::MAX));
        assert!(!is_expired(Some(10), 9));
        assert!(is_expired(Some(10), 10));
    }
}
