//! The two long-lived pieces of the gateway: the event stream and the call audio socket.
//!
//! `wa.events_start` opens `rovsti_wa_client::subscribe` (its own thread and runtime, ARCH 6.4) and a second
//! thread that turns every `ClientEvent` into one `wa.event` through the event sink. Order is the order of the
//! socket. Releasing the stream handle stops the socket; the forwarding thread then sees the channel close and
//! ends. Kotlin's `WaEvents` turns a sink overflow into a synthetic `resync`.
//!
//! `wa.call_audio_open` opens the audio socket of a call. The `audioPush` / `audioPull` exports talk to it with
//! whole frames of 1 920 bytes (960 `i16`, 60 ms, AD-WA5); anything else is dropped with a debug log.

use super::fixture::{case, named};
use super::{Call, Empty, HandleAns, ctx, map_error, not_found};
use crate::envelope::FfiError;
use crate::ops::registry::{Case, OpSpec};
use crate::{events, handles};
use futures::StreamExt;
use futures::channel::mpsc::TryRecvError;
use rovsti_wa_client::{CallAudio, ClientEvent, EventStream, call_audio, subscribe};
use rovsti_wa_proto::EventEnvelope;
use rovsti_wa_proto::audio::{FRAME_BYTES, decode_frame, encode_frame};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::thread;

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
        .ok_or_else(|| map_error(rovsti_wa_client::ClientError::Unauthorized))?;
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
        .ok_or_else(|| map_error(rovsti_wa_client::ClientError::Unauthorized))?;
    let audio = call_audio(g.url.clone(), key, &req.body.call_id).map_err(map_error)?;
    Ok(HandleAns {
        handle: handles::global().insert(Audio { audio }),
    })
}

/// Sends one microphone frame. A wrong size, a full queue (the socket is behind) or a closed call drops it.
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

/// Copies the next frame of the peer's voice into `out`. Answers the bytes written: `FRAME_BYTES` (1 920), `0`
/// when no frame is ready yet (or `out` is too small, which is logged) and `-1` when the call has ended or the
/// handle is gone.
pub fn audio_pull(handle: i64, out: &mut [u8]) -> i32 {
    if out.len() < FRAME_BYTES {
        log::debug!(
            "audioPull: buffer of {} bytes is smaller than a frame",
            out.len()
        );
        return 0;
    }
    let pulled = handles::global().with(handle, |audio: &mut Audio| audio.audio.speaker.try_recv());
    match pulled {
        Some(Ok(samples)) => match encode_frame(&samples) {
            Ok(bytes) => {
                out[..FRAME_BYTES].copy_from_slice(&bytes);
                FRAME_BYTES as i32
            }
            Err(error) => {
                log::debug!("audioPull: dropped a frame: {error}");
                0
            }
        },
        // Nothing queued yet.
        Some(Err(TryRecvError::Empty)) => 0,
        // The socket closed, or the handle is gone.
        Some(Err(TryRecvError::Closed)) | None => -1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_wa_proto::{GatewayEvent, InstanceState, InstanceStatus};

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
                }),
            }),
        );
        assert_eq!(event["event"]["kind"], "event");
        assert_eq!(event["event"]["seq"], 7);
        assert_eq!(event["event"]["event"]["type"], "state");
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
}
