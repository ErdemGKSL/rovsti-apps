//! Voice-note playback on the platform `MediaPlayer`. Kotlin side: `VoicePlayer`,
//! reached through `RovstiBridge` (`voicePlay`, `voicePause`, `voiceSeek`,
//! `voiceSpeed`, `voiceStop`, `voiceState`).
//!
//! One note plays at a time. Positions cross the seam as decimal strings and
//! the speed as a decimal string too ([`Arg`] has no float or long). Poll
//! [`state`] for progress; its JSON is parsed by `voice_player::parse_state`.
//! On desktop every function returns [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// Starts (or resumes) the note `key` from the file at `path`, at `from_ms`
/// and `speed` (1.0 is normal). Switching `key` replaces the current note.
/// `false` when the file cannot be played or audio focus was refused.
pub fn play(key: &str, path: &str, from_ms: u64, speed: f32) -> Result<bool> {
    bridge::call_bool(
        "voicePlay",
        &[
            Arg::Str(key),
            Arg::Str(path),
            Arg::Str(&from_ms.to_string()),
            Arg::Str(&speed.to_string()),
        ],
    )
}

/// Pauses the current note and keeps its position.
pub fn pause() -> Result<bool> {
    bridge::call_bool("voicePause", &[])
}

/// Moves the current note to `ms`.
pub fn seek(ms: u64) -> Result<bool> {
    bridge::call_bool("voiceSeek", &[Arg::Str(&ms.to_string())])
}

/// Changes the playback speed of the current note.
pub fn set_speed(speed: f32) -> Result<bool> {
    bridge::call_bool("voiceSpeed", &[Arg::Str(&speed.to_string())])
}

/// Stops and releases the player.
pub fn stop() -> Result<bool> {
    bridge::call_bool("voiceStop", &[])
}

/// `{"key","playing","position_ms","duration_ms","ended","error"}` JSON.
pub fn state() -> Result<String> {
    bridge::call_string("voiceState", &[])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(
            play("wa:1", "/c/a.ogg", 0, 1.0),
            Err(Error::Unsupported)
        ));
        assert!(matches!(pause(), Err(Error::Unsupported)));
        assert!(matches!(seek(500), Err(Error::Unsupported)));
        assert!(matches!(set_speed(1.5), Err(Error::Unsupported)));
        assert!(matches!(stop(), Err(Error::Unsupported)));
        assert!(matches!(state(), Err(Error::Unsupported)));
    }
}
