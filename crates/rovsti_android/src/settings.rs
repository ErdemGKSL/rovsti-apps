//! The Kotlin settings mirror (AD-30). Facade only: the Kotlin bodies arrive
//! with the settings stories (17.x). Rust owns the truth; Kotlin keeps a copy
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

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(sync("{}"), Err(Error::Unsupported)));
        assert!(matches!(system_facts(), Err(Error::Unsupported)));
    }
}
