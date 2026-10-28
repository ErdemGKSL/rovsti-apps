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

/// Declines a ringing call with `text` (story 18.1). The result says what
/// happened: [`Reject::Stale`], [`Reject::Sent`] (through Telecom),
/// [`Reject::Opened`] (the system SMS app, text typed in) or
/// [`Reject::RejectedOnly`]. The app never holds an SMS permission.
pub fn reject_with_message(call_id: i32, text: &str) -> Result<Reject> {
    let code = bridge::call_int(
        "callRejectWithMessage",
        &[Arg::Int(call_id), Arg::Str(text)],
    )?;
    Ok(Reject::from_code(code))
}

/// Declines and opens the system SMS app on the caller with an empty body.
pub fn reject_and_compose(call_id: i32) -> Result<bool> {
    bridge::call_bool("callRejectAndCompose", &[Arg::Int(call_id)])
}

/// Silences the ringer of the ringing call (`TelecomManager.silenceRinger`);
/// the call stays answerable. `false` when Android refused (story 18.4).
pub fn silence_ringer() -> Result<bool> {
    bridge::call_bool("callSilenceRinger", &[])
}

/// What `reject_with_message` did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reject {
    /// Nothing: the call was gone or the text was not valid.
    Stale,
    /// Telecom sent the text.
    Sent,
    /// The system SMS app opened with the text.
    Opened,
    /// The call was declined and no text could go out.
    RejectedOnly,
}

impl Reject {
    pub const fn from_code(code: i32) -> Self {
        match code {
            1 => Self::Sent,
            2 => Self::Opened,
            3 => Self::RejectedOnly,
            _ => Self::Stale,
        }
    }
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

/// Swaps the active and the held call (a conference swap, or resuming the held
/// call). `Ok(false)` when there is nothing to swap. The result arrives as the
/// next snapshot (`pending: swap` while it runs).
pub fn swap() -> Result<bool> {
    bridge::call_bool0("callSwap")
}

/// Merges the two calls into a conference. `Ok(false)` when nothing can be merged.
pub fn merge() -> Result<bool> {
    bridge::call_bool0("callMerge")
}

/// Splits a conference participant into a private call (needs the child's
/// split capability); the rest of the conference is held.
pub fn split(child_id: i32) -> Result<bool> {
    bridge::call_bool("callSplit", &[Arg::Int(child_id)])
}

/// Answers a ringing call and lets the network hold the current one (FR-48).
/// `Ok(false)` when the current call cannot be held or a call is already held.
pub fn answer_holding(call_id: i32) -> Result<bool> {
    bridge::call_bool("callAnswerHolding", &[Arg::Int(call_id)])
}

/// The relabelled "End held call and answer": the held call ends, then
/// `call_id` is answered and the network holds the active one.
pub fn answer_ending_held(call_id: i32) -> Result<bool> {
    bridge::call_bool("callAnswerEndingHeld", &[Arg::Int(call_id)])
}

/// Ends every non-ringing top-level call.
pub fn end_all() -> Result<bool> {
    bridge::call_bool0("callEndAll")
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
        assert!(matches!(
            reject_with_message(1, "hi"),
            Err(Error::Unsupported)
        ));
        assert!(matches!(reject_and_compose(1), Err(Error::Unsupported)));
        assert!(matches!(silence_ringer(), Err(Error::Unsupported)));
        assert!(matches!(disconnect(1), Err(Error::Unsupported)));
        assert!(matches!(hold(1, true), Err(Error::Unsupported)));
        assert!(matches!(mute(true), Err(Error::Unsupported)));
        assert!(matches!(route("speaker"), Err(Error::Unsupported)));
        assert!(matches!(route_endpoint("id"), Err(Error::Unsupported)));
        assert!(matches!(dtmf('5'), Err(Error::Unsupported)));
        assert!(matches!(dtmf_stop(), Err(Error::Unsupported)));
        assert!(matches!(answer_replacing(2), Err(Error::Unsupported)));
        assert!(matches!(swap(), Err(Error::Unsupported)));
        assert!(matches!(merge(), Err(Error::Unsupported)));
        assert!(matches!(split(2), Err(Error::Unsupported)));
        assert!(matches!(answer_holding(2), Err(Error::Unsupported)));
        assert!(matches!(answer_ending_held(2), Err(Error::Unsupported)));
        assert!(matches!(end_all(), Err(Error::Unsupported)));
        assert!(matches!(incoming_frame(2), Err(Error::Unsupported)));
        assert!(matches!(perf_log("x"), Err(Error::Unsupported)));
    }

    #[test]
    fn reject_codes_map_to_results() {
        assert_eq!(Reject::from_code(0), Reject::Stale);
        assert_eq!(Reject::from_code(1), Reject::Sent);
        assert_eq!(Reject::from_code(2), Reject::Opened);
        assert_eq!(Reject::from_code(3), Reject::RejectedOnly);
        assert_eq!(Reject::from_code(9), Reject::Stale);
    }
}
