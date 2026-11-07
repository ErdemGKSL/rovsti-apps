//! Pure voice note player state: speed cycling, progress, seeking and time labels.

use serde::{Deserialize, Serialize};

/// Playback speed, cycled by the speed pill.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum Speed {
    #[default]
    X1,
    X1_5,
    X2,
}

impl Speed {
    /// The next speed in the 1x, 1.5x, 2x cycle.
    pub fn next(self) -> Self {
        match self {
            Self::X1 => Self::X1_5,
            Self::X1_5 => Self::X2,
            Self::X2 => Self::X1,
        }
    }

    /// Playback rate multiplier.
    pub fn factor(self) -> f32 {
        match self {
            Self::X1 => 1.0,
            Self::X1_5 => 1.5,
            Self::X2 => 2.0,
        }
    }

    /// Pill label.
    pub fn label(self) -> &'static str {
        match self {
            Self::X1 => "1×",
            Self::X1_5 => "1.5×",
            Self::X2 => "2×",
        }
    }
}

/// Player state as reported by the platform player (JSON from Kotlin).
#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
#[serde(default)]
pub struct PlayerState {
    pub key: String,
    pub playing: bool,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub ended: bool,
    pub error: Option<String>,
}

/// Parses player JSON; anything malformed yields the idle default.
pub fn parse_state(json: &str) -> PlayerState {
    serde_json::from_str(json).unwrap_or_default()
}

/// Fraction played, 0..=1; 0 when the duration is unknown.
pub fn progress(position_ms: u64, duration_ms: u64) -> f32 {
    if duration_ms == 0 {
        return 0.0;
    }
    (position_ms as f64 / duration_ms as f64).clamp(0.0, 1.0) as f32
}

/// Millisecond position for a scrub fraction, clamped to the duration.
pub fn seek_target(fraction: f32, duration_ms: u64) -> u64 {
    let f = if fraction.is_finite() {
        f64::from(fraction).clamp(0.0, 1.0)
    } else {
        0.0
    };
    ((f * duration_ms as f64).round() as u64).min(duration_ms)
}

fn clock(ms: u64) -> String {
    let s = ms / 1000;
    let (h, m, s) = (s / 3600, s % 3600 / 60, s % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

/// "0:12 / 0:42" while playing or paused mid-way, "0:42" when idle.
pub fn time_label(position_ms: u64, duration_ms: u64, playing: bool) -> String {
    if playing || (position_ms > 0 && position_ms < duration_ms) {
        format!(
            "{} / {}",
            clock(position_ms.min(duration_ms)),
            clock(duration_ms)
        )
    } else {
        clock(duration_ms)
    }
}

/// What a tap on a note's play button does.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TogglePlan {
    /// The note is playing: pause it.
    Pause,
    /// The note is paused part-way: continue from this position.
    Resume { from_ms: u64 },
    /// Start from the beginning (another note, finished, or idle).
    Start,
}

/// Decides the play button's action for note `key` given the player `state`.
pub fn toggle_plan(state: &PlayerState, key: &str) -> TogglePlan {
    if state.key != key || state.error.is_some() || state.ended {
        return TogglePlan::Start;
    }
    if state.playing {
        TogglePlan::Pause
    } else if state.position_ms > 0 && state.position_ms < state.duration_ms {
        TogglePlan::Resume {
            from_ms: state.position_ms,
        }
    } else {
        TogglePlan::Start
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn speed_cycles() {
        assert_eq!(Speed::default(), Speed::X1);
        assert_eq!(Speed::X1.next(), Speed::X1_5);
        assert_eq!(Speed::X1_5.next(), Speed::X2);
        assert_eq!(Speed::X2.next(), Speed::X1);
        assert_eq!(Speed::X1_5.factor(), 1.5);
        assert_eq!(Speed::X2.label(), "2×");
        let json = serde_json::to_string(&Speed::X1_5).expect("serialize");
        assert_eq!(
            serde_json::from_str::<Speed>(&json).expect("deserialize"),
            Speed::X1_5
        );
    }

    #[test]
    fn parse_state_tolerates_garbage() {
        assert_eq!(parse_state("nope"), PlayerState::default());
        assert_eq!(parse_state(""), PlayerState::default());
        assert_eq!(
            parse_state("{\"position_ms\":\"x\"}"),
            PlayerState::default()
        );
        let s = parse_state("{\"key\":\"a\",\"playing\":true,\"position_ms\":5,\"extra\":1}");
        assert_eq!(s.key, "a");
        assert!(s.playing);
        assert_eq!(s.position_ms, 5);
        assert_eq!(s.duration_ms, 0);
        assert_eq!(
            parse_state("{\"error\":\"boom\"}").error.as_deref(),
            Some("boom")
        );
    }

    #[test]
    fn progress_clamps() {
        assert_eq!(progress(10, 0), 0.0);
        assert_eq!(progress(50, 100), 0.5);
        assert_eq!(progress(500, 100), 1.0);
    }

    #[test]
    fn seek_target_clamps() {
        assert_eq!(seek_target(0.5, 1000), 500);
        assert_eq!(seek_target(-1.0, 1000), 0);
        assert_eq!(seek_target(2.0, 1000), 1000);
        assert_eq!(seek_target(f32::NAN, 1000), 0);
        assert_eq!(seek_target(0.5, 0), 0);
    }

    #[test]
    fn time_labels() {
        assert_eq!(time_label(0, 42_000, false), "0:42");
        assert_eq!(time_label(12_000, 42_000, true), "0:12 / 0:42");
        assert_eq!(time_label(12_000, 42_000, false), "0:12 / 0:42");
        assert_eq!(time_label(42_000, 42_000, false), "0:42");
        assert_eq!(time_label(0, 3_723_000, false), "1:02:03");
        assert_eq!(time_label(99_000, 42_000, true), "0:42 / 0:42");
    }

    #[test]
    fn toggle_plan_follows_the_state() {
        let playing = PlayerState {
            key: "a".into(),
            playing: true,
            position_ms: 10,
            duration_ms: 100,
            ..PlayerState::default()
        };
        assert_eq!(toggle_plan(&playing, "a"), TogglePlan::Pause);
        assert_eq!(toggle_plan(&playing, "b"), TogglePlan::Start);
        let paused = PlayerState {
            playing: false,
            ..playing.clone()
        };
        assert_eq!(
            toggle_plan(&paused, "a"),
            TogglePlan::Resume { from_ms: 10 }
        );
        let ended = PlayerState {
            ended: true,
            ..paused.clone()
        };
        assert_eq!(toggle_plan(&ended, "a"), TogglePlan::Start);
        let failed = PlayerState {
            error: Some("x".into()),
            ..paused
        };
        assert_eq!(toggle_plan(&failed, "a"), TogglePlan::Start);
        assert_eq!(toggle_plan(&PlayerState::default(), "a"), TogglePlan::Start);
    }
}
