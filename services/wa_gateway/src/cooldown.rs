//! The per-instance pause after WhatsApp itself said "rate-overlimit".
//!
//! While it runs, the contact routes (lookups, profiles, pictures) send
//! nothing to WhatsApp: every early retry risks a longer penalty for the
//! account. Time is passed in by the caller.

/// The first pause, doubling with every strike.
pub const BASE_MS: i64 = 60_000;
/// No pause is longer than this.
pub const MAX_MS: i64 = 30 * 60 * 1000;
/// Strikes are forgotten once this long passed after a pause without a new
/// limit.
pub const FORGET_MS: i64 = 10 * 60 * 1000;

/// What one instance learned about WhatsApp's limit.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Cooldown {
    until_ms: i64,
    strikes: u32,
    /// The end of the current pause is still to be announced.
    running: bool,
}

/// What [`Cooldown::check`] found.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Check {
    /// Nothing is paused.
    Clear,
    /// A pause just ended (announce it once).
    Ended,
    /// Paused for `left_ms` more.
    Paused { left_ms: u64 },
}

impl Cooldown {
    /// Records a 429 from WhatsApp at `now_ms`; `retry_after_ms` is its hint.
    /// Returns how long the pause is: at least the hint, at least
    /// `BASE_MS << strikes`, at most [`MAX_MS`] (unless the hint is longer).
    pub fn note(&mut self, retry_after_ms: Option<u64>, now_ms: i64) -> u64 {
        if self.strikes > 0 && now_ms.saturating_sub(self.until_ms) >= FORGET_MS {
            self.strikes = 0;
        }
        let ours = BASE_MS
            .saturating_mul(1i64 << self.strikes.min(16))
            .min(MAX_MS);
        let hint = retry_after_ms.map_or(0, |ms| i64::try_from(ms).unwrap_or(i64::MAX));
        let wait = ours.max(hint);
        self.strikes = self.strikes.saturating_add(1);
        // A second 429 during a pause never shortens it.
        self.until_ms = self.until_ms.max(now_ms.saturating_add(wait));
        self.running = true;
        u64::try_from(self.until_ms.saturating_sub(now_ms)).unwrap_or(0)
    }

    /// Whether requests to WhatsApp must wait at `now_ms`.
    pub fn check(&mut self, now_ms: i64) -> Check {
        if now_ms < self.until_ms {
            return Check::Paused {
                left_ms: u64::try_from(self.until_ms - now_ms).unwrap_or(0),
            };
        }
        if std::mem::take(&mut self.running) {
            Check::Ended
        } else {
            Check::Clear
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_strike_pauses_a_minute_then_doubles() {
        let mut c = Cooldown::default();
        assert_eq!(c.check(0), Check::Clear);
        assert_eq!(c.note(None, 0), 60_000);
        assert_eq!(c.check(1_000), Check::Paused { left_ms: 59_000 });
        assert_eq!(c.check(60_000), Check::Ended);
        assert_eq!(c.check(60_001), Check::Clear);
        assert_eq!(c.note(None, 61_000), 120_000);
        assert_eq!(c.note(None, 61_000), 240_000);
    }

    #[test]
    fn the_hint_is_never_undercut_and_the_pause_caps() {
        let mut c = Cooldown::default();
        assert_eq!(c.note(Some(300_000), 0), 300_000);
        let mut c = Cooldown::default();
        let mut last = 0;
        for _ in 0..10 {
            last = c.note(None, 0);
        }
        assert_eq!(last, MAX_MS as u64);
        // A longer server hint still wins over the cap.
        assert_eq!(c.note(Some(3_600_000), 0), 3_600_000);
    }

    #[test]
    fn strikes_are_forgotten_after_a_quiet_spell() {
        let mut c = Cooldown::default();
        assert_eq!(c.note(None, 0), 60_000);
        assert_eq!(c.note(None, 0), 120_000);
        // Paused until 120 s; quiet for ten more minutes.
        let later = 120_000 + FORGET_MS;
        assert_eq!(c.check(later), Check::Ended);
        assert_eq!(c.note(None, later), 60_000);
    }

    #[test]
    fn a_new_limit_never_shortens_a_running_pause() {
        let mut c = Cooldown::default();
        c.note(Some(600_000), 0);
        // Base for the second strike is 120 s, but 600 s are already set.
        assert_eq!(c.note(None, 1_000), 599_000);
    }
}
