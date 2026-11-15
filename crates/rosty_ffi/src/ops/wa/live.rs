//! The two long-lived pieces of the gateway: the event stream and the call audio socket.
//!
//! `wa.events_start` opens `rosty_wa_client::subscribe` (its own thread and runtime, ARCH 6.4) and a second
//! thread that turns every `ClientEvent` into one `wa.event` through the event sink. Order is the order of the
//! socket. Releasing the stream handle stops the socket; the forwarding thread then sees the channel close and
//! ends. Kotlin's `WaEvents` turns a sink overflow into a synthetic `resync`.
//!
//! `wa.call_audio_open` opens the audio socket of a call and asks the gateway for 20 ms speaker frames. `audioPush`
//! takes whole microphone frames of 1 920 bytes (960 `i16`, 60 ms, AD-WA5); anything else is dropped with a debug
//! log. `audioPull` hands out the peer's voice one frame at a time: 640 bytes (20 ms) from a gateway that knows
//! 20 ms frames, 1 920 bytes from an older one (1 280 is valid too). It waits up to [`PULL_WAIT`] for a frame, so the
//! player thread gets it the moment it arrives instead of polling. Both queues drop their oldest audio, never the
//! newest, and do not let a delay stand (`rosty_wa_client::queue`).

use super::fixture::{case, named};
use super::{Call, Empty, HandleAns, ctx, map_error, not_found};
use crate::envelope::FfiError;
use crate::ops::registry::{Case, OpSpec};
use crate::{events, handles};
use futures::StreamExt;
use rosty_wa_client::{CallAudio, ClientEvent, EventStream, RecvError, call_audio_with, subscribe};
use rosty_wa_proto::EventEnvelope;
use rosty_wa_proto::audio::{FRAME_BYTES, SpeakerFrames, decode_frame, encode_speaker_frame};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::thread;
use std::time::Duration;

/// How long `audioPull` waits for a frame before it answers 0. Short, so the player thread still notices that it
/// was stopped; a frame that arrives meanwhile is handed over at once.
pub const PULL_WAIT: Duration = Duration::from_millis(20);

pub(super) const OPS: &[OpSpec] = &[
    wa_op!(
        "wa.events_start",
        Call<StartReq>,
        HandleAns,
        events_start,
        || vec![
            case(json!({"instance": "inst1", "after": null})),
            named("resume", json!({"instance": "inst1", "after": 41})),
        ]
    ),
    wa_op!(
        "wa.events_reconnect_now",
        Call<Nothing>,
        Empty,
        events_reconnect_now,
        || vec![
            // The stream handle is the second one the setup opens; the placeholder names the latest.
            Case::new(json!({"handle": crate::ops::registry::PLACEHOLDER_HANDLE}))
                .after("wa.open", json!({"url": "$WA", "key": super::fixture::KEY}))
                .after(
                    "wa.events_start",
                    json!({"handle": crate::ops::registry::PLACEHOLDER_HANDLE, "instance": "inst1"})
                ),
        ]
    ),
    wa_op!(
        "wa.call_audio_open",
        Call<CallRef>,
        HandleAns,
        call_audio_open,
        || vec![
            case(json!({"call_id": "call1"})),
            named("unauthorized", json!({"call_id": "call1"})),
            named("no_call", json!({"call_id": "call9"})),
        ]
    ),
];

type Nothing = super::Nothing;

#[derive(Deserialize)]
pub(super) struct CallRef {
    call_id: String,
}

#[derive(Deserialize)]
pub(super) struct StartReq {
    /// Echoed in every `wa.event`, so a screen with two linked instances can tell them apart.
    instance: String,
    /// The last `seq` already processed; the gateway resumes after it.
    #[serde(default)]
    after: Option<u64>,
}

struct Events {
    stream: EventStream,
}

struct Audio {
    audio: CallAudio,
}

/// One stream event as Kotlin receives it, under the key `event` of `wa.event`.
// The gateway event is the common case; boxing it would cost an allocation per event.
#[allow(clippy::large_enum_variant)]
#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum EventDto {
    Connected,
    Event(EventEnvelope),
    Disconnected { reason: String },
    Unauthorized,
}

impl From<ClientEvent> for EventDto {
    fn from(event: ClientEvent) -> Self {
        match event {
            ClientEvent::Connected => Self::Connected,
            ClientEvent::Event(envelope) => Self::Event(envelope),
            ClientEvent::Disconnected(reason) => Self::Disconnected { reason },
            ClientEvent::Unauthorized => Self::Unauthorized,
        }
    }
}

fn payload(instance: &str, event: ClientEvent) -> Value {
    json!({"instance": instance, "event": EventDto::from(event)})
}

fn events_start(req: Call<StartReq>) -> Result<HandleAns, FfiError> {
    let g = ctx(req.handle)?;
    let key = g
        .key
        .clone()
        .ok_or_else(|| map_error(rosty_wa_client::ClientError::Unauthorized))?;
    let (stream, mut receiver) =
        subscribe(g.url.clone(), key, req.body.after).map_err(map_error)?;
    let instance = req.body.instance;
    thread::Builder::new()
        .name("wa-forward".into())
        .spawn(move || {
            while let Some(event) = futures::executor::block_on(receiver.next()) {
                if !events::emit("wa.event", &payload(&instance, event)) {
                    log::debug!("wa.event dropped: no sink");
                }
            }
        })
        .map_err(FfiError::from)?;
    Ok(HandleAns {
        handle: handles::global().insert(Events { stream }),
    })
}

fn events_reconnect_now(req: Call<Nothing>) -> Result<Empty, FfiError> {
    handles::global()
        .with(req.handle, |events: &mut Events| {
            events.stream.reconnect_now();
        })
        .map(|()| Empty {})
        .ok_or_else(|| not_found("wa event stream", req.handle))
}

fn call_audio_open(req: Call<CallRef>) -> Result<HandleAns, FfiError> {
    let g = ctx(req.handle)?;
    let key = g
        .key
        .clone()
        .ok_or_else(|| map_error(rosty_wa_client::ClientError::Unauthorized))?;
    // Kotlin's player writes whatever length comes, so 20 ms frames are asked for.
    let audio = call_audio_with(g.url.clone(), key, &req.body.call_id, SpeakerFrames::Step)
        .map_err(map_error)?;
    Ok(HandleAns {
        handle: handles::global().insert(Audio { audio }),
    })
}

/// Sends one microphone frame. A wrong size or a closed call drops it. When the socket is behind, the oldest queued
/// frames go instead of this one.
pub fn audio_push(handle: i64, pcm: &[u8]) {
    let Ok(samples) = decode_frame(pcm) else {
        log::debug!(
            "audioPush: dropped a frame of {} bytes (want {FRAME_BYTES})",
            pcm.len()
        );
        return;
    };
    let sent = handles::global().with(handle, |audio: &mut Audio| {
        audio.audio.mic.try_send(samples)
    });
    match sent {
        Some(Ok(())) => {}
        Some(Err(error)) => log::debug!("audioPush: dropped a frame: {error}"),
        None => log::debug!("audioPush: call handle {handle} is not open"),
    }
}

/// Copies the next frame of the peer's voice into `out`. Answers the bytes written (640, 1 280 or 1 920: 20, 40
/// or 60 ms), `0` when no frame came within [`PULL_WAIT`] (or `out` is smaller than `FRAME_BYTES`, which is
/// logged) and `-1` when the call has ended or the handle is gone.
pub fn audio_pull(handle: i64, out: &mut [u8]) -> i32 {
    audio_pull_within(handle, out, PULL_WAIT)
}

/// [`audio_pull`] with its wait given; `Duration::ZERO` does not wait.
pub fn audio_pull_within(handle: i64, out: &mut [u8], wait: Duration) -> i32 {
    if out.len() < FRAME_BYTES {
        log::debug!(
            "audioPull: buffer of {} bytes is smaller than a frame",
            out.len()
        );
        return 0;
    }
    // The wait happens outside the handle table, so the microphone thread's `audioPush` is never held up.
    let speaker = handles::global().with(handle, |audio: &mut Audio| audio.audio.speaker.clone());
    let Some(speaker) = speaker else {
        return -1;
    };
    match speaker.recv_timeout(wait) {
        Ok(samples) => match encode_speaker_frame(&samples) {
            Ok(bytes) => {
                out[..bytes.len()].copy_from_slice(&bytes);
                bytes.len() as i32
            }
            Err(error) => {
                log::debug!("audioPull: dropped a frame: {error}");
                0
            }
        },
        // Nothing came in time.
        Err(RecvError::Empty) => 0,
        // The socket closed.
        Err(RecvError::Closed) => -1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rosty_wa_proto::{GatewayEvent, InstanceState, InstanceStatus};

    #[test]
    fn events_have_the_documented_shape() {
        let connected = payload("inst1", ClientEvent::Connected);
        assert_eq!(
            connected,
            json!({"instance": "inst1", "event": {"kind": "connected"}})
        );
        let lost = payload("inst1", ClientEvent::Disconnected("no data".into()));
        assert_eq!(
            lost["event"],
            json!({"kind": "disconnected", "reason": "no data"})
        );
        assert_eq!(
            payload("i", ClientEvent::Unauthorized)["event"],
            json!({"kind": "unauthorized"})
        );
        let event = payload(
            "inst1",
            ClientEvent::Event(EventEnvelope {
                seq: 7,
                at_ms: 5,
                event: GatewayEvent::State(InstanceStatus {
                    instance_id: "inst1".into(),
                    state: InstanceState::Connected,
                    own_jid: None,
                    push_name: None,
                    own_lid: None,
                    epoch: None,
                    account_epoch: None,
                    history: None,
                }),
            }),
        );
        assert_eq!(event["event"]["kind"], "event");
        assert_eq!(event["event"]["seq"], 7);
        assert_eq!(event["event"]["event"]["type"], "state");
        // A status of an old gateway carries none of the newer fields.
        assert_eq!(event["event"]["event"].get("epoch"), None);
    }

    /// The frames of the epoch, account and history round reach Kotlin as the gateway wrote them.
    #[test]
    fn the_gateway_s_newer_frames_pass_through_unchanged() {
        let frames = [
            json!({"type": "hello", "epoch": "9f2c41d07ab35e68"}),
            json!({"type": "resync", "epoch": "9f2c41d07ab35e68"}),
            json!({"type": "resync"}),
            json!({"type": "account_changed", "old_pn": "100000000001@s.whatsapp.net",
                "new_pn": "100000000002@s.whatsapp.net"}),
            json!({"type": "account_changed", "new_pn": "100000000002@s.whatsapp.net"}),
            json!({"type": "history_synced", "chats": 12, "progress": 40, "sync_type": "full",
                "messages": 3500, "oldest_ms": 1_500_000_000_000_i64}),
            json!({"type": "state", "instance_id": "i", "state": "connected",
                "own_jid": "100000000001@s.whatsapp.net", "push_name": null, "epoch": "9f2c41d07ab35e68",
                "account_epoch": 2, "history": {"state": "syncing", "progress": 55, "chats": 120,
                "messages": 48_000, "oldest_ms": 1_500_000_000_000_i64}}),
        ];
        for (seq, frame) in frames.into_iter().enumerate() {
            let envelope: EventEnvelope =
                serde_json::from_value(json!({"seq": seq, "at_ms": 5, "event": frame.clone()}))
                    .expect("envelope");
            let sent = payload("inst1", ClientEvent::Event(envelope));
            assert_eq!(sent["event"]["kind"], "event");
            assert_eq!(sent["event"]["seq"], seq);
            assert_eq!(sent["event"]["event"], frame);
        }
    }

    #[test]
    fn frames_of_the_wrong_size_are_dropped_and_unknown_handles_end() {
        audio_push(1, &[0; 10]);
        audio_push(1, &[0; FRAME_BYTES]);
        let mut small = [0u8; 100];
        assert_eq!(audio_pull(1, &mut small), 0);
        let mut out = [0u8; FRAME_BYTES];
        assert_eq!(audio_pull(1, &mut out), -1);
    }

    /// A short wait: the player thread must notice soon that it was stopped.
    #[test]
    fn the_pull_wait_is_one_20_ms_frame() {
        assert_eq!(PULL_WAIT, Duration::from_millis(20));
    }
}
