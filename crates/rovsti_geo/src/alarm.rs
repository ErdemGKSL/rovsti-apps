//! Pure alarm policy: how loud, how long and which sound to fall back to.
//!
//! The Android side applies these decisions; it ships identical defaults so it
//! still rings when Rust never answered.

use std::time::Duration;

use serde::{Deserialize, Serialize};

/// The alarm stream is raised to at least this share of its maximum.
pub const MIN_ALARM_VOLUME_PCT: u8 = 80;
/// Time to ramp from half loudness to the target (full loudness within 5 s).
pub const RAMP: Duration = Duration::from_secs(4);
/// Ringing stops by itself after this long, then rings again.
pub const RING_TIMEOUT: Duration = Duration::from_secs(600);
/// Re-rings after a ring timeout before giving up.
pub const MAX_TIMEOUT_RERINGS: u8 = 6;
/// Vibration pattern (off, on, off, on, pause), repeated from index 0.
pub const VIBRATION_MS: [u64; 5] = [0, 700, 500, 700, 1500];

/// Where the alarm sound comes from.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SoundSource {
    UserAlarm,
    DefaultAlarm,
    DefaultRingtone,
    DefaultNotification,
    /// Generated tone on the alarm stream; cannot fail to exist.
    Tone,
}

/// Fallback order when a source is missing or fails.
pub const SOUND_ORDER: [SoundSource; 5] = [
    SoundSource::UserAlarm,
    SoundSource::DefaultAlarm,
    SoundSource::DefaultRingtone,
    SoundSource::DefaultNotification,
    SoundSource::Tone,
];

impl SoundSource {
    /// Stable name, the same as the serde form.
    pub fn key(self) -> &'static str {
        match self {
            Self::UserAlarm => "user_alarm",
            Self::DefaultAlarm => "default_alarm",
            Self::DefaultRingtone => "default_ringtone",
            Self::DefaultNotification => "default_notification",
            Self::Tone => "tone",
        }
    }

    /// The next source to try, `None` after the last resort.
    pub fn next(self) -> Option<Self> {
        let i = SOUND_ORDER.iter().position(|s| *s == self)?;
        SOUND_ORDER.get(i + 1).copied()
    }
}

/// Alarm stream volume to use: never below the user's level, at least
/// [`MIN_ALARM_VOLUME_PCT`] of `max`, within `min..=max`.
pub fn volume_target(current: i32, min: i32, max: i32) -> i32 {
    if max <= 0 {
        return max;
    }
    let floor = (max * i32::from(MIN_ALARM_VOLUME_PCT) + 99) / 100;
    current.max(floor).clamp(min.min(max), max)
}

/// Volume `elapsed` after the ring started: linear from half of `target`
/// (at least `min` and 1) to `target` over [`RAMP`].
pub fn ramp_volume(target: i32, min: i32, elapsed: Duration) -> i32 {
    let start = ((target + 1) / 2).max(min).max(1).min(target.max(1));
    if elapsed >= RAMP || target <= start {
        return target;
    }
    let frac = elapsed.as_secs_f64() / RAMP.as_secs_f64();
    let v = f64::from(start) + f64::from(target - start) * frac;
    (v.round() as i32).clamp(start, target)
}

/// Everything Kotlin needs to ring, sent inside the trip start spec.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct AlarmPlan {
    pub volume_pct: u8,
    pub ramp_ms: u64,
    pub ring_timeout_ms: u64,
    pub sound_order: Vec<SoundSource>,
    pub vibrate_pattern_ms: Vec<u64>,
}

impl Default for AlarmPlan {
    fn default() -> Self {
        Self {
            volume_pct: MIN_ALARM_VOLUME_PCT,
            ramp_ms: RAMP.as_millis() as u64,
            ring_timeout_ms: RING_TIMEOUT.as_millis() as u64,
            sound_order: SOUND_ORDER.to_vec(),
            vibrate_pattern_ms: VIBRATION_MS.to_vec(),
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn volume_never_lowers_and_reaches_80_percent() {
        for max in 1..=30 {
            for current in 0..=max {
                let v = volume_target(current, 0, max);
                assert!(v >= current && v <= max);
                assert!(v * 100 >= max * 80, "max {max} current {current} -> {v}");
            }
        }
        assert_eq!(volume_target(7, 1, 7), 7);
        assert_eq!(volume_target(0, 0, 10), 8);
    }

    #[test]
    fn volume_edge_cases() {
        assert_eq!(volume_target(0, 0, 0), 0);
        assert_eq!(volume_target(3, 0, -1), -1);
        // min above the computed floor still wins, but never exceeds max.
        assert_eq!(volume_target(0, 9, 10), 9);
        assert_eq!(volume_target(0, 20, 10), 10);
    }

    #[test]
    fn ramp_is_monotonic_starts_at_half_and_ends_at_target() {
        assert!(RAMP <= Duration::from_secs(5));
        for target in 1..=30 {
            let first = ramp_volume(target, 0, Duration::ZERO);
            assert!(first * 2 >= target && first <= target);
            let mut prev = first;
            for ms in (0..=6_000).step_by(100) {
                let v = ramp_volume(target, 0, Duration::from_millis(ms));
                assert!(v >= prev && v <= target);
                prev = v;
            }
            assert_eq!(ramp_volume(target, 0, RAMP), target);
            assert_eq!(ramp_volume(target, 0, Duration::from_secs(60)), target);
        }
        // Honours the stream minimum.
        assert_eq!(ramp_volume(10, 6, Duration::ZERO), 6);
    }

    #[test]
    fn sound_order_ends_in_tone_without_duplicates() {
        assert_eq!(SOUND_ORDER.last(), Some(&SoundSource::Tone));
        for (i, a) in SOUND_ORDER.iter().enumerate() {
            assert!(!SOUND_ORDER[i + 1..].contains(a));
        }
        let mut s = SOUND_ORDER[0];
        let mut walked = vec![s];
        while let Some(n) = s.next() {
            walked.push(n);
            s = n;
        }
        assert_eq!(walked, SOUND_ORDER);
        assert_eq!(SoundSource::Tone.next(), None);
    }

    #[test]
    fn keys_match_serde() {
        for s in SOUND_ORDER {
            assert_eq!(serde_json::to_value(s).unwrap(), s.key());
        }
    }

    #[test]
    fn plan_round_trips_with_stable_keys() {
        let plan = AlarmPlan::default();
        assert!(plan.volume_pct >= 80);
        let v = serde_json::to_value(&plan).unwrap();
        let obj = v.as_object().unwrap();
        for k in [
            "volume_pct",
            "ramp_ms",
            "ring_timeout_ms",
            "sound_order",
            "vibrate_pattern_ms",
        ] {
            assert!(obj.contains_key(k), "{k}");
        }
        assert_eq!(obj.len(), 5);
        assert_eq!(v["sound_order"][0], "user_alarm");
        assert_eq!(
            v["vibrate_pattern_ms"],
            serde_json::json!([0, 700, 500, 700, 1500])
        );
        assert_eq!(v["ring_timeout_ms"], 600_000);
        let back: AlarmPlan = serde_json::from_value(v).unwrap();
        assert_eq!(back, plan);
    }
}
