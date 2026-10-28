//! The haptics setting applied to every tick the app asks for (story 16.8).
//!
//! Views call [`perform`] instead of `Window::perform_haptic_feedback`; the
//! core filter ([`rovsti_core::settings::haptic_allowed`]) decides. The level
//! is one relaxed atomic, written when the settings change, so a tick costs
//! one load. `Off` silences the app's own ticks only: the system ringer and
//! the vibrate-on-connect options are separate.

use std::sync::atomic::{AtomicU8, Ordering};

use gpui::{HapticFeedback, Window};
use rovsti_core::{
    settings::{HapticKind, haptic_allowed},
    wire::HapticsLevel,
};

static LEVEL: AtomicU8 = AtomicU8::new(2);

const fn encode(level: HapticsLevel) -> u8 {
    match level {
        HapticsLevel::Off => 0,
        HapticsLevel::Light => 1,
        HapticsLevel::Normal => 2,
    }
}

const fn decode(value: u8) -> HapticsLevel {
    match value {
        0 => HapticsLevel::Off,
        1 => HapticsLevel::Light,
        _ => HapticsLevel::Normal,
    }
}

/// Sets the level every later tick is filtered by.
pub fn set_level(level: HapticsLevel) {
    LEVEL.store(encode(level), Ordering::Relaxed);
}

pub fn level() -> HapticsLevel {
    decode(LEVEL.load(Ordering::Relaxed))
}

/// Which core kind a platform haptic belongs to.
pub const fn kind_of(feedback: HapticFeedback) -> HapticKind {
    match feedback {
        HapticFeedback::Selection => HapticKind::Tick,
        HapticFeedback::Confirm => HapticKind::Confirm,
        HapticFeedback::Reject | HapticFeedback::LongPress => HapticKind::Warning,
        HapticFeedback::GestureStart | HapticFeedback::GestureEnd => HapticKind::Press,
    }
}

/// Whether `feedback` plays at `level`.
pub const fn allowed(level: HapticsLevel, feedback: HapticFeedback) -> bool {
    haptic_allowed(level, kind_of(feedback))
}

/// Plays `feedback` unless the Haptics setting filters it out.
pub fn perform(window: &Window, feedback: HapticFeedback) {
    if allowed(level(), feedback) {
        window.perform_haptic_feedback(feedback);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn off_plays_nothing() {
        for f in [
            HapticFeedback::Selection,
            HapticFeedback::Confirm,
            HapticFeedback::LongPress,
        ] {
            assert!(!allowed(HapticsLevel::Off, f));
        }
    }

    #[test]
    fn light_keeps_only_ticks() {
        assert!(allowed(HapticsLevel::Light, HapticFeedback::Selection));
        assert!(!allowed(HapticsLevel::Light, HapticFeedback::Confirm));
        assert!(!allowed(HapticsLevel::Light, HapticFeedback::LongPress));
        assert!(!allowed(HapticsLevel::Light, HapticFeedback::GestureStart));
    }

    #[test]
    fn normal_plays_everything() {
        assert!(allowed(HapticsLevel::Normal, HapticFeedback::Reject));
        assert!(allowed(HapticsLevel::Normal, HapticFeedback::GestureEnd));
    }

    #[test]
    fn the_level_round_trips() {
        set_level(HapticsLevel::Light);
        assert_eq!(level(), HapticsLevel::Light);
        set_level(HapticsLevel::Normal);
        assert_eq!(level(), HapticsLevel::Normal);
    }
}
