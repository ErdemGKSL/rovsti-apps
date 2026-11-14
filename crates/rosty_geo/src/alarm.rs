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

/// Volumes (percent of the alarm stream) the Settings row cycles through.
pub const VOLUME_CHOICES: [u8; 3] = [80, 90, 100];

/// The next volume in [`VOLUME_CHOICES`] (wrapping); values below 80 snap to 80.
pub fn next_volume(pct: u8) -> u8 {
    VOLUME_CHOICES
        .into_iter()
        .find(|v| *v > pct)
        .unwrap_or(VOLUME_CHOICES[0])
}

/// Vibration rhythm the user can pick.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VibrationPattern {
    #[default]
    Standard,
    Pulse,
    Long,
    Heartbeat,
}

impl VibrationPattern {
    pub const ALL: [Self; 4] = [Self::Standard, Self::Pulse, Self::Long, Self::Heartbeat];

    pub fn label(self) -> &'static str {
        match self {
            Self::Standard => "Standard",
            Self::Pulse => "Pulse",
            Self::Long => "Long",
            Self::Heartbeat => "Heartbeat",
        }
    }

    /// The next pattern (wrapping).
    pub fn next(self) -> Self {
        let i = Self::ALL.iter().position(|p| *p == self).unwrap_or(0);
        Self::ALL[(i + 1) % Self::ALL.len()]
    }

    /// Android pattern (off, on, off, on, ...), repeated from index 0.
    pub fn pattern_ms(self) -> &'static [u64] {
        match self {
            Self::Standard => &VIBRATION_MS,
            Self::Pulse => &[0, 250, 250, 250, 250, 250, 1000],
            Self::Long => &[0, 1500, 700],
            Self::Heartbeat => &[0, 120, 120, 300, 900],
        }
    }

    /// One cycle without the trailing pause, for a short preview buzz.
    pub fn preview_ms(self) -> Vec<u64> {
        let p = self.pattern_ms();
        // Even indexes are "off": an odd length ends on a trailing pause.
        let end = if p.len() % 2 == 1 {
            p.len() - 1
        } else {
            p.len()
        };
        p[..end].to_vec()
    }
}

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
    /// Sound the user picked (content URI); tried first when set.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_sound_uri: Option<String>,
}

impl Default for AlarmPlan {
    fn default() -> Self {
        Self {
            volume_pct: MIN_ALARM_VOLUME_PCT,
            ramp_ms: RAMP.as_millis() as u64,
            ring_timeout_ms: RING_TIMEOUT.as_millis() as u64,
            sound_order: SOUND_ORDER.to_vec(),
            vibrate_pattern_ms: VIBRATION_MS.to_vec(),
            user_sound_uri: None,
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn patterns_are_well_formed() {
        assert_eq!(VibrationPattern::Standard.pattern_ms(), &VIBRATION_MS);
        for p in VibrationPattern::ALL {
            let m = p.pattern_ms();
            assert!(m.len() >= 2 && m[0] == 0, "{p:?}");
            assert!(m.iter().skip(1).step_by(2).any(|v| *v > 0), "{p:?}");
            assert!(m.iter().sum::<u64>() >= 1000, "{p:?}");
            let pre = p.preview_ms();
            assert!(!pre.is_empty() && pre.len() % 2 == 0, "{p:?}");
            assert!(pre.iter().sum::<u64>() < 3000, "{p:?}");
            assert!(!p.label().is_empty());
        }
    }

    #[test]
    fn pattern_next_cycles_all() {
        let mut p = VibrationPattern::default();
        let mut seen = vec![p];
        for _ in 0..VibrationPattern::ALL.len() - 1 {
            p = p.next();
            assert!(!seen.contains(&p));
            seen.push(p);
        }
        assert_eq!(p.next(), VibrationPattern::Standard);
        assert_eq!(seen, VibrationPattern::ALL);
    }

    #[test]
    fn next_volume_cycles_and_snaps() {
        assert_eq!(next_volume(0), 80);
        assert_eq!(next_volume(79), 80);
        assert_eq!(next_volume(80), 90);
        assert_eq!(next_volume(90), 100);
        assert_eq!(next_volume(100), 80);
        assert_eq!(next_volume(255), 80);
    }

    #[test]
    fn default_plan_json_has_no_user_sound() {
        let json = serde_json::to_string(&AlarmPlan::default()).unwrap();
        assert!(!json.contains("user_sound_uri"));
        let back: AlarmPlan = serde_json::from_str(&json).unwrap();
        assert_eq!(back, AlarmPlan::default());
    }

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
