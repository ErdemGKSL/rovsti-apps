//! The wall clock behind rendered times, with a test override.
//!
//! Call sites that feed text on screen ("Yesterday", "15:32") read
//! [`now_ms`] instead of `SystemTime::now()`, so a screenshot rig can pin the
//! instant and render the same pixels twice.

use std::{
    sync::atomic::{AtomicI64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};

/// 0 means "no override": read the system clock.
static FIXED_NOW_MS: AtomicI64 = AtomicI64::new(0);

/// Milliseconds since the Unix epoch: the fixed instant if one is set,
/// otherwise the system clock.
pub fn now_ms() -> i64 {
    match FIXED_NOW_MS.load(Ordering::Relaxed) {
        0 => system_now_ms(),
        fixed => fixed,
    }
}

/// Pins [`now_ms`] to `ms` (`Some`), or returns it to the system clock (`None`).
/// A value of 0 also means the system clock.
pub fn set_fixed_now_ms(ms: Option<i64>) {
    FIXED_NOW_MS.store(ms.unwrap_or(0), Ordering::Relaxed);
}

fn system_now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    // The override is process-global, so the tests take turns.
    static LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn system_clock_is_recent_without_override() {
        let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
        set_fixed_now_ms(None);
        // After 2024-01-01.
        assert!(now_ms() > 1_704_067_200_000);
    }

    #[test]
    fn fixed_value_is_returned_until_cleared() {
        let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
        // 2026-11-07T15:40:00+03:00
        let fixed = 1_794_055_200_000;
        set_fixed_now_ms(Some(fixed));
        assert_eq!(now_ms(), fixed);
        assert_eq!(now_ms(), fixed);
        set_fixed_now_ms(None);
        assert_ne!(now_ms(), fixed);
    }

    #[test]
    fn zero_means_system_clock() {
        let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
        set_fixed_now_ms(Some(0));
        assert!(now_ms() > 1_704_067_200_000);
        set_fixed_now_ms(None);
    }
}
