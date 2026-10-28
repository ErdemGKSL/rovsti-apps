//! Call state and commands, backed by Kotlin's `CallRegistry` (AD-4, AD-6).
//!
//! Kotlin owns the state. [`snapshot`] returns the last JSON snapshot it
//! built (`rovsti_core::wire::CallSnapshot`); changes arrive as `calls`
//! events. Commands are non-blocking and id-addressed: an unknown or stale
//! `call_id` is a no-op that returns `Ok(false)`. On desktop everything
//! returns [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// The last snapshot Kotlin built, as JSON. Cheap: no Telecom call is made.
pub fn snapshot() -> Result<String> {
    bridge::call_string("callsSnapshot", &[])
}

/// Answers a ringing call (audio only).
pub fn answer(call_id: i32) -> Result<bool> {
    bridge::call_bool("callAnswer", &[Arg::Int(call_id)])
}

/// Rejects a ringing call.
pub fn reject(call_id: i32) -> Result<bool> {
    bridge::call_bool("callReject", &[Arg::Int(call_id)])
}

/// Ends a call in any state.
pub fn disconnect(call_id: i32) -> Result<bool> {
    bridge::call_bool("callDisconnect", &[Arg::Int(call_id)])
}

/// Holds (`true`) or resumes (`false`) a call.
pub fn hold(call_id: i32, on: bool) -> Result<bool> {
    bridge::call_bool("callHold", &[Arg::Int(call_id), Arg::Bool(on)])
}

/// Mutes or unmutes the microphone.
pub fn mute(on: bool) -> Result<bool> {
    bridge::call_bool("callMute", &[Arg::Bool(on)])
}

/// Routes audio: `earpiece`, `speaker`, `bluetooth` or `wired_headset`.
pub fn route(name: &str) -> Result<bool> {
    bridge::call_bool("callRoute", &[Arg::Str(name)])
}

/// Routes audio to the endpoint with this identifier (AD-23). `Ok(false)` for
/// an id Kotlin does not know. Ids are session handles: never persist them.
pub fn route_endpoint(id: &str) -> Result<bool> {
    bridge::call_bool("callRouteEndpoint", &[Arg::Str(id)])
}

/// Starts one DTMF tone on the foreground call; it plays until [`dtmf_stop`]
/// (Kotlin stops it by itself after a few seconds as a safety net).
pub fn dtmf(digit: char) -> Result<bool> {
    // Digits and `*#` fit in an i32 code point.
    bridge::call_bool("callDtmf", &[Arg::Int(digit as i32)])
}

/// Stops the DTMF tone started by [`dtmf`].
pub fn dtmf_stop() -> Result<bool> {
    bridge::call_bool0("callDtmfStop")
}

/// Ends the active call and answers `call_id` (FR-20), as one registry
/// operation.
pub fn answer_replacing(call_id: i32) -> Result<bool> {
    bridge::call_bool("callAnswerReplacing", &[Arg::Int(call_id)])
}

/// Tells Kotlin the Incoming Call screen for `call_id` has drawn its first
/// frame: Kotlin logs the ring latency (`RovstiPerf`) and lifts the native
/// curtain if one is up (story 5.6).
pub fn incoming_frame(call_id: i32) -> Result<bool> {
    bridge::call_bool("callIncomingFrame", &[Arg::Int(call_id)])
}

/// Writes one line to logcat under the tag `RovstiPerf` (slow-frame reports,
/// story 11.10). Never pass names or numbers.
pub fn perf_log(line: &str) -> Result<bool> {
    bridge::call_bool_str("perfLog", line)
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(snapshot(), Err(Error::Unsupported)));
        assert!(matches!(answer(1), Err(Error::Unsupported)));
        assert!(matches!(reject(1), Err(Error::Unsupported)));
        assert!(matches!(disconnect(1), Err(Error::Unsupported)));
        assert!(matches!(hold(1, true), Err(Error::Unsupported)));
        assert!(matches!(mute(true), Err(Error::Unsupported)));
        assert!(matches!(route("speaker"), Err(Error::Unsupported)));
        assert!(matches!(route_endpoint("id"), Err(Error::Unsupported)));
        assert!(matches!(dtmf('5'), Err(Error::Unsupported)));
        assert!(matches!(dtmf_stop(), Err(Error::Unsupported)));
        assert!(matches!(answer_replacing(2), Err(Error::Unsupported)));
        assert!(matches!(incoming_frame(2), Err(Error::Unsupported)));
        assert!(matches!(perf_log("x"), Err(Error::Unsupported)));
    }
}
