//! WhatsApp wake and call audio, backed by Kotlin's `WaPush` and
//! `WaCallAudio`.
//!
//! The WhatsApp account lives on the gateway; the phone only supplies what the
//! gateway cannot: an FCM token to be woken by, and the microphone and speaker
//! of a call. Audio frames (960 mono samples at 16 kHz, 60 ms) cross the
//! strings-only JNI seam as base64. On desktop everything returns
//! [`crate::Error::Unsupported`].

use rovsti_wa_proto::audio::{frame_from_base64, frame_to_base64};

use crate::{
    Result,
    bridge::{self, Arg},
};

/// The FCM registration token, or `""` when the host app packages no Firebase.
pub fn push_token() -> Result<String> {
    bridge::call_string("waPushToken", &[])
}

/// Sets the audio mode to in-communication and starts the microphone and the
/// speaker. `false` when `RECORD_AUDIO` is missing or the device refused.
pub fn call_audio_start() -> Result<bool> {
    bridge::call_bool0("waAudioStart")
}

/// Stops both directions and restores the audio mode.
pub fn call_audio_stop() -> Result<()> {
    bridge::call_bool0("waAudioStop").map(drop)
}

/// The next microphone frame, `None` when none is ready yet.
pub fn call_audio_read() -> Result<Option<Vec<i16>>> {
    let text = bridge::call_string("waMicFrame", &[])?;
    if text.is_empty() {
        return Ok(None);
    }
    Ok(Some(frame_from_base64(&text)?))
}

/// Queues one frame of the peer's voice for the speaker. `false` when the
/// speaker is not running or its queue is full (the frame is dropped).
pub fn call_audio_play(frame: &[i16]) -> Result<bool> {
    bridge::call_bool("waPlayFrame", &[Arg::Str(&frame_to_base64(frame)?)])
}

/// Routes the call audio to the loudspeaker (`true`) or the earpiece (`false`).
/// `false` when the device offers no such route.
pub fn call_audio_speaker(on: bool) -> Result<bool> {
    bridge::call_bool("waSpeaker", &[Arg::Bool(on)])
}

/// Shows the incoming-call notification (full-screen, Answer and Decline) for
/// the gateway call `call_id`, titled `title`. `false` when notifications are
/// blocked. Answer and Decline come back as `wa_call_action` events.
pub fn show_incoming(call_id: &str, title: &str) -> Result<bool> {
    bridge::call_bool("waShowIncoming", &[Arg::Str(call_id), Arg::Str(title)])
}

/// Removes the incoming-call notification of `call_id`. `false` when none was shown.
pub fn cancel_incoming(call_id: &str) -> Result<bool> {
    bridge::call_bool("waCancelIncoming", &[Arg::Str(call_id)])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;
    use rovsti_wa_proto::audio::FRAME_SAMPLES;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(push_token(), Err(Error::Unsupported)));
        assert!(matches!(call_audio_start(), Err(Error::Unsupported)));
        assert!(matches!(call_audio_speaker(true), Err(Error::Unsupported)));
        assert!(matches!(show_incoming("c", "Ann"), Err(Error::Unsupported)));
        assert!(matches!(cancel_incoming("c"), Err(Error::Unsupported)));
        assert!(matches!(call_audio_stop(), Err(Error::Unsupported)));
        assert!(matches!(call_audio_read(), Err(Error::Unsupported)));
        assert!(matches!(
            call_audio_play(&[0; FRAME_SAMPLES]),
            Err(Error::Unsupported)
        ));
    }

    #[test]
    fn a_wrong_sized_frame_is_refused_before_the_bridge() {
        assert!(matches!(call_audio_play(&[0; 3]), Err(Error::Frame(_))));
    }
}
