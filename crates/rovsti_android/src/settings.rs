//! The Kotlin settings mirror (AD-30). Kotlin side: `SettingsMirror`.
//! Rust owns the truth; Kotlin keeps a copy
//! for the parts that run without Rust (proximity, flip, vibration, keypad
//! tones, the notification's Message action).
//!
//! On desktop they return [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// Pushes the `rovsti_core::wire::SettingsSync` JSON on attach and after every
/// change. `false` when Kotlin could not store it.
pub fn sync(json: &str) -> Result<bool> {
    bridge::call_bool("settingsSync", &[Arg::Str(json)])
}

/// What only Kotlin can read: `{flip_supported, system_keypad_tones}` as JSON.
pub fn system_facts() -> Result<String> {
    bridge::call_string("systemFacts", &[])
}

/// Plays one dial-pad tone (`0` to `9`, `*` as 10, `#` as 11) on the DTMF
/// stream, or stops it when `digit` is negative (story 16.8). Kotlin decides
/// from the mirror whether tones are on at all; this is the app's one
/// sanctioned sound and never plays for UI clicks.
pub fn keypad_tone(digit: i32) -> Result<bool> {
    bridge::call_bool("keypadTone", &[Arg::Int(digit)])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(sync("{}"), Err(Error::Unsupported)));
        assert!(matches!(system_facts(), Err(Error::Unsupported)));
        assert!(matches!(keypad_tone(3), Err(Error::Unsupported)));
    }
}
