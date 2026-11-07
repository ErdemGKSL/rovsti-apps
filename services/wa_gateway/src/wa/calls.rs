//! Voice calls: the per-instance [`CallTable`], the HTTP routes that drive
//! it, and the audio WebSocket that carries the call's PCM.
//!
//! The table owns one [`CallEntry`] per call: the whatsapp-rust offer (for an
//! incoming call), a [`CallMachine`], the live handle once the call is
//! answered or placed, and two bounded audio channel pairs. whatsapp-rust
//! sees only the engine ends (`mic_rx`, `spk_tx`); the audio socket sees the
//! other two (`mic_tx`, `spk_rx`). A full channel drops frames, so a lagging
//! side never adds latency.
//!
//! Everything WhatsApp-specific sits behind [`CallBackend`] and [`LiveCall`],
//! so the table is tested with fakes (see [`fake`]).

use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use async_channel::{Receiver, Sender, TrySendError};
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Path, State};
use axum::response::Response;
use axum::routing::{get, post};
use axum::{Json, Router};
use futures::future::BoxFuture;
use rovsti_wa_proto::api::routes as r;
use rovsti_wa_proto::audio::{Rechunker, decode_frame, encode_frame};
use rovsti_wa_proto::{
    CallDirection, CallInput, CallMachine, CallPhase, CallStarted, CallSummary, CallUpdate,
    ErrorCode, GatewayEvent, PlaceCall, SetMuted,
};
use whatsapp_rust::CallError as WaCallError;
use whatsapp_rust::voip::{CallEvent, CallHandle};
use whatsapp_rust::wacore::stanza::call::REJECT_REASON_BUSY;
use whatsapp_rust::wacore::types::call::{CallAction, IncomingCall};

use crate::AppState;
use crate::auth::Authed;
use crate::driver::DriverError;
use crate::error::HttpError;
use crate::hub::Hub;
use crate::now_ms;
use crate::push::{PushKind, Pusher};
use crate::store::InstanceId;
use crate::wa::convert::parse_jid;
use crate::wa::lid::{self, LidMap};

/// A ringing call that nobody answers ends as missed after this long.
pub const RING_TIMEOUT: Duration = Duration::from_secs(60);
/// Frames each audio channel holds (about half a second).
pub const AUDIO_QUEUE: usize = 8;
/// Ended calls stay queryable this long.
const KEEP_ENDED: Duration = Duration::from_secs(24 * 60 * 60);
/// Calls (live and recently ended) one instance may hold.
const MAX_CALLS: usize = 64;
const MAX_CALL_ID_LEN: usize = 128;

type Frame = Vec<i16>;

/// What a live call reports besides its end.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LiveEvent {
    /// The relay is up; media flows.
    MediaUp,
    /// The call cannot continue.
    Failed(String),
}

/// A call whose media engine is running.
pub trait LiveCall: Send + Sync + 'static {
    fn call_id(&self) -> String;

    /// Stops the media engine. Local only: the peer is not told, so use it
    /// for a call the peer already ended.
    fn hangup(&self) -> BoxFuture<'_, ()>;

    /// Tells the peer the call is over (`<terminate>`, which also cancels a
    /// call that still rings) and stops the media engine, even if the
    /// signaling fails.
    fn terminate(&self) -> BoxFuture<'_, Result<(), DriverError>>;

    fn set_muted(&self, muted: bool);

    /// The next event, or `None` once the event stream is closed.
    fn next_event(&self) -> BoxFuture<'_, Option<LiveEvent>>;

    /// Resolves once the media engine has stopped.
    fn wait_ended(&self) -> BoxFuture<'_, ()>;
}

/// The engine's ends of a call's audio channels.
pub struct CallIo {
    pub mic_rx: Receiver<Frame>,
    pub spk_tx: Sender<Frame>,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum StartError {
    /// The offer does not advertise the codec this gateway speaks.
    #[error("unsupported codec")]
    UnsupportedCodec,
    #[error(transparent)]
    Driver(DriverError),
}

impl StartError {
    /// The reason shown to the apps when the call fails to start.
    fn reason(&self) -> String {
        match self {
            Self::UnsupportedCodec => "unsupported codec".to_owned(),
            Self::Driver(DriverError::NotConnected) => "WhatsApp is not connected".to_owned(),
            Self::Driver(_) => "call setup failed".to_owned(),
        }
    }
}

/// What the table asks of WhatsApp for one instance.
pub trait CallBackend: Send + Sync + 'static {
    /// Answers an incoming call.
    fn accept(
        &self,
        incoming: IncomingCall,
        io: CallIo,
    ) -> BoxFuture<'_, Result<Arc<dyn LiveCall>, StartError>>;

    /// Rings `peer` (a JID).
    fn place(
        &self,
        peer: String,
        io: CallIo,
    ) -> BoxFuture<'_, Result<Arc<dyn LiveCall>, StartError>>;

    fn reject(&self, incoming: IncomingCall) -> BoxFuture<'_, Result<(), DriverError>>;

    /// Cancels a call that never got a live handle.
    fn terminate(
        &self,
        call_id: String,
        peer: String,
        creator: String,
    ) -> BoxFuture<'_, Result<(), DriverError>>;
}

#[derive(Debug, thiserror::Error)]
pub enum CallError {
    #[error("no such call")]
    NotFound,
    #[error("{0}")]
    Conflict(&'static str),
    #[error(transparent)]
    Start(#[from] StartError),
    #[error(transparent)]
    Driver(#[from] DriverError),
}

impl From<CallError> for HttpError {
    fn from(e: CallError) -> Self {
        match e {
            CallError::NotFound => Self::not_found("no such call"),
            CallError::Conflict(msg) => Self::new(ErrorCode::Conflict, msg),
            CallError::Start(StartError::UnsupportedCodec) => {
                Self::bad_request("unsupported codec")
            }
            CallError::Start(StartError::Driver(e)) | CallError::Driver(e) => e.into(),
        }
    }
}

/// How ending a call has to be carried out.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EndAction {
    /// Decline the ringing call.
    Reject,
    /// Hang up through the live handle.
    Hangup,
    /// Cancel with signaling only: the call has no live handle yet.
    Terminate,
}

/// Decides how `end` applies to a call, or `None` if it is over already.
fn plan_end(
    direction: CallDirection,
    phase: &CallPhase,
    has_handle: bool,
) -> Option<(CallInput, EndAction)> {
    match (direction, phase) {
        (_, CallPhase::Ended(_)) => None,
        // An unanswered incoming call can only be declined.
        (CallDirection::In, CallPhase::Ringing) => Some((CallInput::Reject, EndAction::Reject)),
        _ if has_handle => Some((CallInput::Hangup, EndAction::Hangup)),
        _ => Some((CallInput::Hangup, EndAction::Terminate)),
    }
}

/// How one microphone frame from the socket fared.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MicOutcome {
    Sent,
    /// Not exactly one frame long.
    Dropped,
    /// The engine is behind; the frame was dropped.
    Lagging,
    /// The call is over.
    Closed,
}

/// Hands a binary socket message to the engine.
pub fn feed_mic(bytes: &[u8], mic: &Sender<Frame>) -> MicOutcome {
    let Ok(samples) = decode_frame(bytes) else {
        return MicOutcome::Dropped;
    };
    match mic.try_send(samples) {
        Ok(()) => MicOutcome::Sent,
        Err(TrySendError::Full(_)) => MicOutcome::Lagging,
        Err(TrySendError::Closed(_)) => MicOutcome::Closed,
    }
}

/// One call. See the module docs.
struct CallEntry {
    incoming: Option<IncomingCall>,
    machine: CallMachine,
    handle: Option<Arc<dyn LiveCall>>,
    /// The other side, for the apps.
    peer: String,
    /// The other side as WhatsApp addresses it (signaling).
    wire_peer: String,
    creator: String,
    peer_name: Option<String>,
    is_video: bool,
    mic_tx: Sender<Frame>,
    mic_rx: Receiver<Frame>,
    spk_tx: Sender<Frame>,
    spk_rx: Receiver<Frame>,
    audio_attached: bool,
    /// The relay is up. An outgoing call gets it from the offer's ack, while
    /// the callee still rings, so it only counts once the callee accepts.
    media_up: bool,
    /// Wall clock of the first sighting, for the call list.
    started_ms: i64,
    /// Wall clock of the end, for the call list.
    ended_ms: Option<i64>,
    ended_at: Option<Instant>,
    /// A ring push went out, so the phone also hears how the call ended.
    pushed: bool,
}

impl CallEntry {
    fn new(direction: CallDirection, peer: String) -> Self {
        let (mic_tx, mic_rx) = async_channel::bounded(AUDIO_QUEUE);
        let (spk_tx, spk_rx) = async_channel::bounded(AUDIO_QUEUE);
        Self {
            incoming: None,
            machine: CallMachine::new(direction),
            handle: None,
            wire_peer: peer.clone(),
            peer,
            creator: String::new(),
            peer_name: None,
            is_video: false,
            mic_tx,
            mic_rx,
            spk_tx,
            spk_rx,
            audio_attached: false,
            media_up: false,
            started_ms: now_ms(),
            ended_ms: None,
            ended_at: None,
            pushed: false,
        }
    }

    fn update(&self, call_id: &str) -> CallUpdate {
        CallUpdate {
            call_id: call_id.to_owned(),
            peer: self.peer.clone(),
            peer_name: self.peer_name.clone(),
            direction: self.machine.direction(),
            phase: self.machine.phase().clone(),
            is_video: self.is_video,
        }
    }

    fn io(&self) -> CallIo {
        CallIo {
            mic_rx: self.mic_rx.clone(),
            spk_tx: self.spk_tx.clone(),
        }
    }
}

/// The calls of one instance.
pub struct CallTable {
    instance: InstanceId,
    hub: Arc<Hub>,
    pusher: Arc<dyn Pusher>,
    ring_timeout: Duration,
    calls: Mutex<HashMap<String, CallEntry>>,
}

impl std::fmt::Debug for CallTable {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "CallTable({})", self.instance)
    }
}

/// The audio socket's side of a call. Dropping it frees the call's single
/// audio slot.
pub struct AudioPorts {
    pub mic_tx: Sender<Frame>,
    pub spk_rx: Receiver<Frame>,
    _lease: AudioLease,
}

struct AudioLease {
    table: Arc<CallTable>,
    call_id: String,
}

impl Drop for AudioLease {
    fn drop(&mut self) {
        if let Some(entry) = self.table.lock().get_mut(&self.call_id) {
            entry.audio_attached = false;
        }
    }
}

impl CallTable {
    pub fn new(
        instance: InstanceId,
        hub: Arc<Hub>,
        pusher: Arc<dyn Pusher>,
        ring_timeout: Duration,
    ) -> Arc<Self> {
        Arc::new(Self {
            instance,
            hub,
            pusher,
            ring_timeout,
            calls: Mutex::new(HashMap::new()),
        })
    }

    fn lock(&self) -> MutexGuard<'_, HashMap<String, CallEntry>> {
        self.calls.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// The phase of a call, if it is known.
    pub fn phase(&self, call_id: &str) -> Option<CallPhase> {
        self.lock().get(call_id).map(|e| e.machine.phase().clone())
    }

    fn publish(&self, call_id: &str, entry: &CallEntry) {
        self.hub.publish(
            &self.instance,
            now_ms(),
            GatewayEvent::Call(entry.update(call_id)),
        );
    }

    /// Every known call, newest first.
    pub fn list(&self) -> Vec<CallSummary> {
        let mut calls: Vec<_> = self
            .lock()
            .iter()
            .map(|(id, e)| CallSummary {
                update: e.update(id),
                started_ms: e.started_ms,
                ended_ms: e.ended_ms,
            })
            .collect();
        calls.sort_by(|a, b| {
            b.started_ms
                .cmp(&a.started_ms)
                .then_with(|| a.update.call_id.cmp(&b.update.call_id))
        });
        calls
    }

    /// Applies `input` to `entry`; publishes and, when the call ends, closes
    /// its audio channels so the socket and the engine stop.
    fn apply(&self, call_id: &str, entry: &mut CallEntry, input: CallInput) -> Option<CallPhase> {
        let was_ringing = *entry.machine.phase() == CallPhase::Ringing;
        let phase = entry.machine.apply(input.clone())?;
        if phase.is_ended() {
            entry.ended_at = Some(Instant::now());
            entry.ended_ms = Some(now_ms());
            entry.mic_tx.close();
            entry.spk_tx.close();
            if was_ringing && entry.pushed && entry.machine.direction() == CallDirection::In {
                self.push_call_end(call_id, entry, &input);
            }
        }
        self.publish(call_id, entry);
        Some(phase)
    }

    /// Tells the phone a ring it was woken for is over. Missed when the
    /// caller gave up or nobody answered; not when the user or another device
    /// dealt with it.
    fn push_call_end(&self, call_id: &str, entry: &CallEntry, input: &CallInput) {
        let missed = matches!(input, CallInput::RemoteTerminate | CallInput::Timeout);
        self.pusher.notify(
            &self.instance,
            PushKind::CallEnd {
                call_id: call_id.to_owned(),
                missed,
                from: entry.peer.clone(),
                name: entry.peer_name.clone().unwrap_or_default(),
            },
        );
    }

    fn transition(&self, call_id: &str, input: CallInput) -> Option<CallPhase> {
        let mut calls = self.lock();
        let entry = calls.get_mut(call_id)?;
        self.apply(call_id, entry, input)
    }

    /// Adds a call and publishes its first phase. False when it is a
    /// duplicate or the table is full.
    fn insert(&self, call_id: &str, entry: CallEntry) -> bool {
        let mut calls = self.lock();
        calls.retain(|_, e| e.ended_at.is_none_or(|at| at.elapsed() < KEEP_ENDED));
        if calls.contains_key(call_id) {
            return false;
        }
        // Full: the oldest finished call makes room; live calls never do.
        while calls.len() >= MAX_CALLS {
            let oldest = calls
                .iter()
                .filter_map(|(id, e)| e.ended_at.map(|at| (at, id)))
                .min()
                .map(|(_, id)| id.clone());
            match oldest {
                Some(id) => calls.remove(&id),
                None => return false,
            };
        }
        let entry = calls.entry(call_id.to_owned()).or_insert(entry);
        self.publish(call_id, entry);
        true
    }

    fn spawn_ring_timeout(self: &Arc<Self>, call_id: String) {
        let table = Arc::clone(self);
        tokio::spawn(async move {
            tokio::time::sleep(table.ring_timeout).await;
            table.ring_expired(&call_id).await;
        });
    }

    async fn ring_expired(&self, call_id: &str) {
        let handle = {
            let mut calls = self.lock();
            let Some(entry) = calls.get_mut(call_id) else {
                return;
            };
            // Only a call still ringing times out; an answered one has its
            // own relay failure events.
            if *entry.machine.phase() != CallPhase::Ringing
                || self.apply(call_id, entry, CallInput::Timeout).is_none()
            {
                return;
            }
            entry.handle.clone()
        };
        // An outgoing call nobody picked up is cancelled on WhatsApp's side.
        if let Some(handle) = handle
            && let Err(e) = handle.terminate().await
        {
            log::warn!(
                "{}: cancelling unanswered call {call_id}: {e}",
                self.instance
            );
        }
    }

    /// Stores the live handle and starts following it. A call that ended
    /// while it was being set up is hung up at once.
    async fn attach_handle(self: &Arc<Self>, call_id: &str, handle: Arc<dyn LiveCall>) {
        let live = {
            let mut calls = self.lock();
            match calls.get_mut(call_id) {
                Some(entry) if !entry.machine.phase().is_ended() => {
                    entry.handle = Some(Arc::clone(&handle));
                    true
                }
                _ => false,
            }
        };
        if live {
            tokio::spawn(Arc::clone(self).follow(call_id.to_owned(), handle));
        } else {
            // Ended by the peer, or by `end`, which already told WhatsApp.
            handle.hangup().await;
        }
    }

    /// The relay is up: an answered call becomes active, a ringing outgoing
    /// one remembers it for the callee's accept.
    fn media_up(&self, call_id: &str) {
        let mut calls = self.lock();
        if let Some(entry) = calls.get_mut(call_id) {
            entry.media_up = true;
            self.apply(call_id, entry, CallInput::MediaUp);
        }
    }

    /// The callee accepted our call; it is active at once if the relay is up.
    fn remote_accepted(&self, call_id: &str) {
        let mut calls = self.lock();
        let Some(entry) = calls.get_mut(call_id) else {
            return;
        };
        if self
            .apply(call_id, entry, CallInput::RemoteAccepted)
            .is_some()
            && entry.media_up
        {
            self.apply(call_id, entry, CallInput::MediaUp);
        }
    }

    /// Turns the engine's events into phases until the call is over.
    async fn follow(self: Arc<Self>, call_id: String, handle: Arc<dyn LiveCall>) {
        let ended = handle.wait_ended();
        tokio::pin!(ended);
        loop {
            tokio::select! {
                // Events first: a failure just before the end is the reason.
                biased;
                event = handle.next_event() => match event {
                    // For an outgoing call the relay comes up while the callee
                    // still rings; its <accept> (`on_incoming`) answers the call.
                    Some(LiveEvent::MediaUp) => self.media_up(&call_id),
                    Some(LiveEvent::Failed(reason)) => {
                        self.transition(&call_id, CallInput::Failed(reason));
                        if let Err(e) = handle.terminate().await {
                            log::warn!("{}: ending failed call {call_id}: {e}", self.instance);
                        }
                        break;
                    }
                    None => {
                        (&mut ended).await;
                        break;
                    }
                },
                () = &mut ended => break,
            }
        }
        self.transition(&call_id, CallInput::RemoteTerminate);
    }

    /// An `IncomingCall` event: a new offer, or the peer's answer to ours.
    pub fn on_incoming(self: &Arc<Self>, call: &IncomingCall, map: &LidMap) {
        match &call.action {
            CallAction::Offer {
                call_id,
                call_creator,
                is_video,
                group_jid,
                ..
            } => {
                if group_jid.is_some() {
                    log::info!("{}: ignoring a group call", self.instance);
                    return;
                }
                let from = lid::resolve(&call.from, None, map).to_string();
                let mut entry = CallEntry::new(CallDirection::In, from.clone());
                entry.wire_peer = call.from.to_string();
                entry.creator = call_creator.to_string();
                entry.peer_name = call.notify.clone().filter(|n| !n.is_empty());
                entry.is_video = *is_video;
                let name = entry.peer_name.clone().unwrap_or_default();
                entry.incoming = Some(call.clone());
                // Set before the entry is visible, so an end racing the push
                // below is still reported.
                entry.pushed = true;
                if !self.insert(call_id, entry) {
                    return;
                }
                self.spawn_ring_timeout(call_id.clone());
                self.pusher.notify(
                    &self.instance,
                    PushKind::Call {
                        call_id: call_id.clone(),
                        from,
                        name,
                    },
                );
            }
            CallAction::Accept { call_id, .. } => self.remote_accepted(call_id),
            // One of the callee's devices cannot take calls; the others ring
            // on and whatsapp-rust keeps the call (handlers/call.rs).
            CallAction::Reject {
                reason: Some(reason),
                ..
            } if reason == REJECT_REASON_BUSY => {}
            CallAction::Reject { call_id, .. } | CallAction::Terminate { call_id, .. } => {
                self.transition(call_id, CallInput::RemoteTerminate);
            }
            _ => {}
        }
    }

    /// A `MissedCall` event.
    pub fn on_missed(&self, call_id: &str) {
        self.transition(call_id, CallInput::RemoteTerminate);
    }

    /// A `CallEndedElsewhere` event.
    pub fn on_ended_elsewhere(&self, call_id: &str) {
        self.transition(call_id, CallInput::AnsweredElsewhere);
    }

    /// Answers a ringing incoming call.
    pub async fn accept(
        self: &Arc<Self>,
        backend: &dyn CallBackend,
        call_id: &str,
    ) -> Result<(), CallError> {
        let (incoming, io) = {
            let mut calls = self.lock();
            let entry = calls.get_mut(call_id).ok_or(CallError::NotFound)?;
            let ringing_in = entry.machine.direction() == CallDirection::In
                && *entry.machine.phase() == CallPhase::Ringing;
            let incoming = entry.incoming.clone().filter(|_| ringing_in);
            let Some(incoming) = incoming else {
                return Err(CallError::Conflict("the call is not ringing"));
            };
            // Connecting now, so a second accept or a reject is refused while
            // the engine starts.
            self.apply(call_id, entry, CallInput::Accept);
            (incoming, entry.io())
        };
        match backend.accept(incoming, io).await {
            Ok(handle) => {
                self.attach_handle(call_id, handle).await;
                Ok(())
            }
            Err(e) => {
                self.transition(call_id, CallInput::Failed(e.reason()));
                Err(e.into())
            }
        }
    }

    /// Declines a ringing incoming call.
    pub async fn reject(&self, backend: &dyn CallBackend, call_id: &str) -> Result<(), CallError> {
        let incoming = {
            let mut calls = self.lock();
            let entry = calls.get_mut(call_id).ok_or(CallError::NotFound)?;
            if entry.machine.direction() != CallDirection::In
                || self.apply(call_id, entry, CallInput::Reject).is_none()
            {
                return Err(CallError::Conflict("the call is not ringing"));
            }
            entry.incoming.clone()
        };
        if let Some(incoming) = incoming {
            backend.reject(incoming).await?;
        }
        Ok(())
    }

    /// Ends a call whatever its state: declines it while it rings, hangs up
    /// a live one, and cancels one that has no handle yet.
    pub async fn end(&self, backend: &dyn CallBackend, call_id: &str) -> Result<(), CallError> {
        let (action, handle, incoming, signaling) = {
            let mut calls = self.lock();
            let entry = calls.get_mut(call_id).ok_or(CallError::NotFound)?;
            let (input, action) = plan_end(
                entry.machine.direction(),
                entry.machine.phase(),
                entry.handle.is_some(),
            )
            .ok_or(CallError::Conflict("the call is over"))?;
            // Phase first: the engine's own end must not be read as the peer
            // hanging up.
            self.apply(call_id, entry, input);
            (
                action,
                entry.handle.clone(),
                entry.incoming.clone(),
                (
                    call_id.to_owned(),
                    entry.wire_peer.clone(),
                    entry.creator.clone(),
                ),
            )
        };
        match (action, handle, incoming) {
            (EndAction::Reject, _, Some(incoming)) => backend.reject(incoming).await?,
            // `<terminate>`: a local hangup alone leaves the peer ringing.
            (EndAction::Hangup, Some(handle), _) => handle.terminate().await?,
            (EndAction::Terminate, ..) => {
                let (id, peer, creator) = signaling;
                backend.terminate(id, peer, creator).await?;
            }
            _ => {}
        }
        Ok(())
    }

    /// Mutes or unmutes our microphone.
    pub fn set_muted(&self, call_id: &str, muted: bool) -> Result<(), CallError> {
        let calls = self.lock();
        let entry = calls.get(call_id).ok_or(CallError::NotFound)?;
        if entry.machine.phase().is_ended() {
            return Err(CallError::Conflict("the call is over"));
        }
        let handle = entry
            .handle
            .as_ref()
            .ok_or(CallError::Conflict("the call is not connected yet"))?;
        handle.set_muted(muted);
        Ok(())
    }

    /// Rings `to`.
    pub async fn place(
        self: &Arc<Self>,
        backend: &dyn CallBackend,
        to: &str,
    ) -> Result<CallStarted, CallError> {
        let mut entry = CallEntry::new(CallDirection::Out, to.to_owned());
        let handle = backend.place(to.to_owned(), entry.io()).await?;
        let call_id = handle.call_id();
        // Visible with its handle, so `end` always has one to terminate.
        entry.handle = Some(Arc::clone(&handle));
        if !self.insert(&call_id, entry) {
            if let Err(e) = handle.terminate().await {
                log::warn!("{}: cancelling surplus call {call_id}: {e}", self.instance);
            }
            return Err(CallError::Conflict("too many calls"));
        }
        self.spawn_ring_timeout(call_id.clone());
        tokio::spawn(Arc::clone(self).follow(call_id.clone(), handle));
        Ok(CallStarted { call_id })
    }

    /// Claims the call's one audio slot. The slot is free again when the
    /// returned ports are dropped.
    pub fn attach_audio(self: &Arc<Self>, call_id: &str) -> Result<AudioPorts, CallError> {
        let mut calls = self.lock();
        let entry = calls.get_mut(call_id).ok_or(CallError::NotFound)?;
        if entry.machine.phase().is_ended() {
            return Err(CallError::Conflict("the call is over"));
        }
        if entry.audio_attached {
            return Err(CallError::Conflict("call audio is already attached"));
        }
        entry.audio_attached = true;
        // Frames queued while nobody listened are stale.
        while entry.spk_rx.try_recv().is_ok() {}
        while entry.mic_rx.try_recv().is_ok() {}
        Ok(AudioPorts {
            mic_tx: entry.mic_tx.clone(),
            spk_rx: entry.spk_rx.clone(),
            _lease: AudioLease {
                table: Arc::clone(self),
                call_id: call_id.to_owned(),
            },
        })
    }
}

/// Shuttles audio between the socket and the call until either side ends.
async fn audio_socket(mut socket: WebSocket, ports: AudioPorts) {
    // The decoder hands over frames of any length (20 ms ones are common);
    // the apps take whole 60 ms frames.
    let mut speaker = Rechunker::new();
    'shuttle: loop {
        tokio::select! {
            frame = ports.spk_rx.recv() => match frame {
                Ok(samples) => {
                    for whole in speaker.push(&samples) {
                        match encode_frame(&whole) {
                            Ok(bytes) => {
                                if socket.send(Message::Binary(bytes.into())).await.is_err() {
                                    break 'shuttle;
                                }
                            }
                            Err(e) => log::warn!("call audio: dropping speaker frame: {e}"),
                        }
                    }
                }
                // The call ended and closed the channel.
                Err(_) => break,
            },
            message = socket.recv() => match message {
                Some(Ok(Message::Binary(bytes))) => {
                    if feed_mic(&bytes, &ports.mic_tx) == MicOutcome::Closed {
                        break;
                    }
                }
                Some(Ok(Message::Close(_)) | Err(_)) | None => break,
                Some(Ok(_)) => {}
            },
        }
    }
    let _ = socket.send(Message::Close(None)).await;
}

// ----------------------------------------------------------------- routes

pub fn routes() -> Router<AppState> {
    Router::new()
        .route(r::CALLS, post(place).get(list))
        .route(r::CALL_ACCEPT, post(accept))
        .route(r::CALL_REJECT, post(reject))
        .route(r::CALL_END, post(end))
        .route(r::CALL_MUTE, post(mute))
        .route(r::CALL_AUDIO, get(audio))
}

type ApiResult<T> = Result<T, HttpError>;

fn check_call_id(id: &str) -> ApiResult<()> {
    let ok = !id.is_empty()
        && id.len() <= MAX_CALL_ID_LEN
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
    if ok {
        Ok(())
    } else {
        Err(HttpError::bad_request("invalid call id"))
    }
}

async fn place(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<PlaceCall>,
) -> ApiResult<Json<CallStarted>> {
    let to = req.to.trim();
    parse_jid(to)?;
    let backend = state.driver().call_backend(&auth.instance)?;
    let started = state.calls(&auth.instance).place(&*backend, to).await?;
    Ok(Json(started))
}

async fn list(State(state): State<AppState>, auth: Authed) -> Json<Vec<CallSummary>> {
    Json(state.calls(&auth.instance).list())
}

async fn accept(
    State(state): State<AppState>,
    auth: Authed,
    Path(call_id): Path<String>,
) -> ApiResult<()> {
    check_call_id(&call_id)?;
    let backend = state.driver().call_backend(&auth.instance)?;
    state
        .calls(&auth.instance)
        .accept(&*backend, &call_id)
        .await?;
    Ok(())
}

async fn reject(
    State(state): State<AppState>,
    auth: Authed,
    Path(call_id): Path<String>,
) -> ApiResult<()> {
    check_call_id(&call_id)?;
    let backend = state.driver().call_backend(&auth.instance)?;
    state
        .calls(&auth.instance)
        .reject(&*backend, &call_id)
        .await?;
    Ok(())
}

async fn end(
    State(state): State<AppState>,
    auth: Authed,
    Path(call_id): Path<String>,
) -> ApiResult<()> {
    check_call_id(&call_id)?;
    let backend = state.driver().call_backend(&auth.instance)?;
    state.calls(&auth.instance).end(&*backend, &call_id).await?;
    Ok(())
}

async fn mute(
    State(state): State<AppState>,
    auth: Authed,
    Path(call_id): Path<String>,
    Json(req): Json<SetMuted>,
) -> ApiResult<()> {
    check_call_id(&call_id)?;
    state.calls(&auth.instance).set_muted(&call_id, req.muted)?;
    Ok(())
}

/// The audio socket. The slot is claimed before the upgrade, so a missing
/// call or a second socket is an HTTP error, not a silent stream.
async fn audio(
    State(state): State<AppState>,
    auth: Authed,
    Path(call_id): Path<String>,
    ws: WebSocketUpgrade,
) -> ApiResult<Response> {
    check_call_id(&call_id)?;
    let ports = state.calls(&auth.instance).attach_audio(&call_id)?;
    Ok(ws.on_upgrade(move |socket| audio_socket(socket, ports)))
}

// ------------------------------------------------------------ whatsapp-rust

/// A [`CallHandle`] as a [`LiveCall`]. `CallHandle::hangup` is local only, so
/// the client is kept for the `<terminate>` that tells the peer.
struct WaLiveCall {
    handle: CallHandle,
    client: Arc<whatsapp_rust::Client>,
}

/// The engine event the apps care about, if it is one.
pub fn live_event(event: &CallEvent) -> Option<LiveEvent> {
    match event {
        CallEvent::RelayAllocated => Some(LiveEvent::MediaUp),
        CallEvent::RelayAllocateFailed(code) => Some(LiveEvent::Failed(format!(
            "relay allocation failed ({code})"
        ))),
        CallEvent::RelayAllocateTimedOut => {
            Some(LiveEvent::Failed("relay allocation timed out".to_owned()))
        }
        CallEvent::RelayReconnectTimedOut => {
            Some(LiveEvent::Failed("relay reconnect timed out".to_owned()))
        }
        CallEvent::AudioFormatMismatch { .. } => {
            Some(LiveEvent::Failed("unsupported codec".to_owned()))
        }
        _ => None,
    }
}

impl LiveCall for WaLiveCall {
    fn call_id(&self) -> String {
        self.handle.call_id().to_owned()
    }

    fn hangup(&self) -> BoxFuture<'_, ()> {
        Box::pin(self.handle.hangup())
    }

    fn terminate(&self) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async move {
            // The handle knows the device that answered, which `<terminate>`
            // must address; before an answer it is the peer that rings.
            let sent = self
                .client
                .voip()
                .terminate(
                    self.handle.call_id(),
                    &self.handle.peer_jid(),
                    self.handle.call_creator(),
                )
                .await
                .map_err(failed);
            self.handle.hangup().await;
            sent
        })
    }

    fn set_muted(&self, muted: bool) {
        self.handle.set_muted(muted);
    }

    fn next_event(&self) -> BoxFuture<'_, Option<LiveEvent>> {
        let events = self.handle.events();
        Box::pin(async move {
            loop {
                let event = events.recv().await.ok()?;
                if let Some(event) = live_event(&event) {
                    return Some(event);
                }
            }
        })
    }

    fn wait_ended(&self) -> BoxFuture<'_, ()> {
        Box::pin(self.handle.wait_ended())
    }
}

fn start_error(e: WaCallError) -> StartError {
    match e {
        WaCallError::AudioFormatNotOffered(_) => StartError::UnsupportedCodec,
        other => StartError::Driver(DriverError::Failed(other.to_string())),
    }
}

/// The real call backend of one running instance.
pub struct WaCalls {
    client: Arc<whatsapp_rust::Client>,
}

impl WaCalls {
    pub fn new(client: Arc<whatsapp_rust::Client>) -> Self {
        Self { client }
    }

    fn live(&self, handle: CallHandle) -> Arc<dyn LiveCall> {
        Arc::new(WaLiveCall {
            handle,
            client: Arc::clone(&self.client),
        })
    }
}

fn failed(e: impl std::fmt::Display) -> DriverError {
    DriverError::Failed(e.to_string())
}

impl CallBackend for WaCalls {
    fn accept(
        &self,
        incoming: IncomingCall,
        io: CallIo,
    ) -> BoxFuture<'_, Result<Arc<dyn LiveCall>, StartError>> {
        Box::pin(async move {
            let handle = self
                .client
                .voip()
                .accept(&incoming)
                .audio(io.mic_rx, io.spk_tx)
                .start()
                .await
                .map_err(start_error)?;
            Ok(self.live(handle))
        })
    }

    fn place(
        &self,
        peer: String,
        io: CallIo,
    ) -> BoxFuture<'_, Result<Arc<dyn LiveCall>, StartError>> {
        Box::pin(async move {
            let peer = parse_jid(&peer).map_err(StartError::Driver)?;
            let handle = self
                .client
                .voip()
                .call(&peer)
                .audio(io.mic_rx, io.spk_tx)
                .start()
                .await
                .map_err(start_error)?;
            Ok(self.live(handle))
        })
    }

    fn reject(&self, incoming: IncomingCall) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async move { self.client.voip().reject(&incoming).await.map_err(failed) })
    }

    fn terminate(
        &self,
        call_id: String,
        peer: String,
        creator: String,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async move {
            let peer = parse_jid(&peer)?;
            let creator = parse_jid(&creator)?;
            self.client
                .voip()
                .terminate(&call_id, &peer, &creator)
                .await
                .map_err(failed)
        })
    }
}

// ------------------------------------------------------------------- fakes

/// Scripted call backend for tests.
#[cfg(any(test, feature = "fake"))]
pub mod fake {
    use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

    use whatsapp_rust::chrono::Utc;
    use whatsapp_rust::{Jid, wacore::types::call::IncomingCall};

    use super::*;

    /// What the fake backend and its calls sent to "WhatsApp".
    type Ops = Arc<Mutex<Vec<String>>>;

    fn record(ops: &Ops, op: String) {
        ops.lock().unwrap_or_else(|e| e.into_inner()).push(op);
    }

    /// A call that follows whatever the test tells it.
    pub struct FakeLive {
        id: String,
        ops: Ops,
        muted: AtomicBool,
        hung_up: AtomicBool,
        events_tx: Sender<LiveEvent>,
        events_rx: Receiver<LiveEvent>,
        ended: tokio::sync::watch::Sender<bool>,
    }

    impl FakeLive {
        pub fn new(id: &str) -> Arc<Self> {
            Self::recording(id, Ops::default())
        }

        /// A call whose `terminate` is written to `ops`.
        fn recording(id: &str, ops: Ops) -> Arc<Self> {
            let (events_tx, events_rx) = async_channel::unbounded();
            Arc::new(Self {
                id: id.to_owned(),
                ops,
                muted: AtomicBool::new(false),
                hung_up: AtomicBool::new(false),
                events_tx,
                events_rx,
                ended: tokio::sync::watch::channel(false).0,
            })
        }

        /// Reports an engine event.
        pub fn emit(&self, event: LiveEvent) {
            let _ = self.events_tx.try_send(event);
        }

        /// The media engine stops (the peer hung up, say).
        pub fn finish(&self) {
            self.ended.send_replace(true);
        }

        pub fn is_muted(&self) -> bool {
            self.muted.load(Ordering::Relaxed)
        }

        /// The engine was stopped, by `hangup` or `terminate`.
        pub fn hung_up(&self) -> bool {
            self.hung_up.load(Ordering::Relaxed)
        }
    }

    impl LiveCall for FakeLive {
        fn call_id(&self) -> String {
            self.id.clone()
        }

        fn hangup(&self) -> BoxFuture<'_, ()> {
            Box::pin(async move {
                self.hung_up.store(true, Ordering::Relaxed);
                self.finish();
            })
        }

        fn terminate(&self) -> BoxFuture<'_, Result<(), DriverError>> {
            Box::pin(async move {
                record(&self.ops, format!("terminate {}", self.id));
                self.hangup().await;
                Ok(())
            })
        }

        fn set_muted(&self, muted: bool) {
            self.muted.store(muted, Ordering::Relaxed);
        }

        fn next_event(&self) -> BoxFuture<'_, Option<LiveEvent>> {
            Box::pin(async move { self.events_rx.recv().await.ok() })
        }

        fn wait_ended(&self) -> BoxFuture<'_, ()> {
            let mut ended = self.ended.subscribe();
            Box::pin(async move {
                let _ = ended.wait_for(|v| *v).await;
            })
        }
    }

    /// Echoes microphone frames to the speaker, like a peer repeating what
    /// it hears.
    fn echo(io: CallIo) {
        tokio::spawn(async move {
            while let Ok(frame) = io.mic_rx.recv().await {
                if io.spk_tx.try_send(frame).is_err() && io.spk_tx.is_closed() {
                    break;
                }
            }
        });
    }

    #[derive(Default)]
    pub struct FakeCalls {
        ops: Ops,
        lives: Mutex<Vec<Arc<FakeLive>>>,
        fail_start: Mutex<Option<StartError>>,
        counter: AtomicU64,
    }

    impl FakeCalls {
        pub fn new() -> Self {
            Self::default()
        }

        /// `accept`, `place`, `reject` and `terminate` calls so far, a live
        /// call's own `terminate` included (a local `hangup` is not listed).
        pub fn ops(&self) -> Vec<String> {
            self.ops.lock().unwrap_or_else(|e| e.into_inner()).clone()
        }

        /// Every live call started so far, oldest first.
        pub fn lives(&self) -> Vec<Arc<FakeLive>> {
            self.lives.lock().unwrap_or_else(|e| e.into_inner()).clone()
        }

        /// Makes the next `accept` or `place` fail with `error`.
        pub fn fail_next_start(&self, error: StartError) {
            *self.fail_start.lock().unwrap_or_else(|e| e.into_inner()) = Some(error);
        }

        fn record(&self, op: String) {
            record(&self.ops, op);
        }

        fn start(
            &self,
            op: String,
            id: String,
            io: CallIo,
        ) -> Result<Arc<dyn LiveCall>, StartError> {
            self.record(op);
            if let Some(error) = self
                .fail_start
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .take()
            {
                return Err(error);
            }
            let live = FakeLive::recording(&id, Arc::clone(&self.ops));
            self.lives
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(Arc::clone(&live));
            echo(io);
            Ok(live)
        }
    }

    impl CallBackend for FakeCalls {
        fn accept(
            &self,
            incoming: IncomingCall,
            io: CallIo,
        ) -> BoxFuture<'_, Result<Arc<dyn LiveCall>, StartError>> {
            let id = incoming.action.call_id().to_owned();
            let result = self.start(format!("accept {id}"), id, io);
            Box::pin(async move { result })
        }

        fn place(
            &self,
            peer: String,
            io: CallIo,
        ) -> BoxFuture<'_, Result<Arc<dyn LiveCall>, StartError>> {
            let n = self.counter.fetch_add(1, Ordering::Relaxed) + 1;
            let result = self.start(format!("place {peer}"), format!("FAKECALL{n:04}"), io);
            Box::pin(async move { result })
        }

        fn reject(&self, incoming: IncomingCall) -> BoxFuture<'_, Result<(), DriverError>> {
            self.record(format!("reject {}", incoming.action.call_id()));
            Box::pin(async { Ok(()) })
        }

        fn terminate(
            &self,
            call_id: String,
            _peer: String,
            _creator: String,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            self.record(format!("terminate {call_id}"));
            Box::pin(async { Ok(()) })
        }
    }

    /// An offer for call `call_id` from `from` (a JID).
    pub fn offer(call_id: &str, from: &str, name: Option<&str>) -> IncomingCall {
        let from: Jid = from
            .parse()
            .unwrap_or_else(|e| panic!("bad jid {from}: {e}"));
        IncomingCall::builder()
            .from(from.clone())
            .stanza_id(format!("stanza-{call_id}"))
            .maybe_notify(name.map(str::to_owned))
            .timestamp(Utc::now())
            .offline(false)
            .action(CallAction::Offer {
                call_id: call_id.to_owned(),
                call_creator: from,
                caller_pn: None,
                caller_country_code: None,
                device_class: None,
                joinable: false,
                is_video: false,
                audio: Vec::new(),
                group_jid: None,
            })
            .build()
    }
}

#[cfg(test)]
mod tests {
    use super::fake::{FakeCalls, offer};
    use super::*;
    use rovsti_wa_proto::audio::FRAME_SAMPLES;
    use rovsti_wa_proto::{EndReason, ErrorCode, GatewayEvent};

    const FROM: &str = "905321234567@s.whatsapp.net";

    #[derive(Default)]
    struct Recorder(Mutex<Vec<PushKind>>);

    impl Pusher for Recorder {
        fn notify(&self, _instance: &InstanceId, kind: PushKind) {
            self.0.lock().unwrap().push(kind);
        }
    }

    struct Rig {
        table: Arc<CallTable>,
        calls: FakeCalls,
        hub: Arc<Hub>,
        pushes: Arc<Recorder>,
    }

    fn rig_with(timeout: Duration) -> Rig {
        let hub = Arc::new(Hub::new());
        let pushes = Arc::new(Recorder::default());
        let table = CallTable::new(
            InstanceId::new("i"),
            Arc::clone(&hub),
            pushes.clone(),
            timeout,
        );
        Rig {
            table,
            calls: FakeCalls::new(),
            hub,
            pushes,
        }
    }

    fn rig() -> Rig {
        rig_with(RING_TIMEOUT)
    }

    fn phases(rig: &Rig) -> Vec<CallPhase> {
        let sub = rig.hub.subscribe(&InstanceId::new("i"), Some(0));
        match sub.replay {
            crate::hub::Replay::Events(events) => events
                .into_iter()
                .filter_map(|e| match e.event {
                    GatewayEvent::Call(update) => Some(update.phase),
                    _ => None,
                })
                .collect(),
            crate::hub::Replay::Gap { .. } => panic!("gap"),
        }
    }

    async fn wait_phase(table: &CallTable, id: &str, want: CallPhase) {
        for _ in 0..200 {
            if table.phase(id).as_ref() == Some(&want) {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("call {id} is {:?}, wanted {want:?}", table.phase(id));
    }

    fn ring(rig: &Rig, id: &str) {
        rig.table
            .on_incoming(&offer(id, FROM, Some("Ayse")), &LidMap::default());
    }

    #[tokio::test]
    async fn an_offer_rings_publishes_and_pushes() {
        let rig = rig();
        ring(&rig, "C1");
        assert_eq!(rig.table.phase("C1"), Some(CallPhase::Ringing));
        assert_eq!(phases(&rig), [CallPhase::Ringing]);
        assert_eq!(
            *rig.pushes.0.lock().unwrap(),
            [PushKind::Call {
                call_id: "C1".into(),
                from: FROM.into(),
                name: "Ayse".into(),
            }]
        );
        // The same offer again is not a second ring.
        ring(&rig, "C1");
        assert_eq!(rig.pushes.0.lock().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn accept_then_media_makes_the_call_active_and_end_hangs_up() {
        let rig = rig();
        ring(&rig, "C1");
        rig.table.accept(&rig.calls, "C1").await.unwrap();
        assert_eq!(rig.table.phase("C1"), Some(CallPhase::Connecting));
        let live = rig.calls.lives().pop().unwrap();

        live.emit(LiveEvent::MediaUp);
        wait_phase(&rig.table, "C1", CallPhase::Active).await;

        rig.table.set_muted("C1", true).unwrap();
        assert!(live.is_muted());

        rig.table.end(&rig.calls, "C1").await.unwrap();
        assert!(live.hung_up());
        wait_phase(&rig.table, "C1", CallPhase::Ended(EndReason::Hangup)).await;
        // The engine stopping afterwards does not rewrite the reason.
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(
            rig.table.phase("C1"),
            Some(CallPhase::Ended(EndReason::Hangup))
        );
        assert_eq!(
            phases(&rig),
            [
                CallPhase::Ringing,
                CallPhase::Connecting,
                CallPhase::Active,
                CallPhase::Ended(EndReason::Hangup)
            ]
        );
        assert!(matches!(
            rig.table.end(&rig.calls, "C1").await,
            Err(CallError::Conflict(_))
        ));
    }

    #[tokio::test]
    async fn accepting_twice_or_after_the_end_conflicts() {
        let rig = rig();
        ring(&rig, "C1");
        rig.table.accept(&rig.calls, "C1").await.unwrap();
        assert!(matches!(
            rig.table.accept(&rig.calls, "C1").await,
            Err(CallError::Conflict(_))
        ));
        assert!(matches!(
            rig.table.accept(&rig.calls, "nope").await,
            Err(CallError::NotFound)
        ));
        assert_eq!(rig.calls.ops(), ["accept C1"]);
    }

    #[tokio::test]
    async fn reject_declines_a_ringing_call() {
        let rig = rig();
        ring(&rig, "C1");
        rig.table.reject(&rig.calls, "C1").await.unwrap();
        assert_eq!(
            rig.table.phase("C1"),
            Some(CallPhase::Ended(EndReason::Rejected))
        );
        assert_eq!(rig.calls.ops(), ["reject C1"]);
        assert!(matches!(
            rig.table.accept(&rig.calls, "C1").await,
            Err(CallError::Conflict(_))
        ));
    }

    #[tokio::test]
    async fn ending_a_ringing_incoming_call_declines_it() {
        let rig = rig();
        ring(&rig, "C1");
        rig.table.end(&rig.calls, "C1").await.unwrap();
        assert_eq!(rig.calls.ops(), ["reject C1"]);
        assert_eq!(
            rig.table.phase("C1"),
            Some(CallPhase::Ended(EndReason::Rejected))
        );
    }

    #[tokio::test]
    async fn a_ringing_call_times_out_as_missed() {
        let rig = rig_with(Duration::from_millis(40));
        ring(&rig, "C1");
        wait_phase(&rig.table, "C1", CallPhase::Ended(EndReason::Missed)).await;
        assert!(matches!(
            rig.table.accept(&rig.calls, "C1").await,
            Err(CallError::Conflict(_))
        ));
    }

    #[tokio::test]
    async fn the_timeout_leaves_an_answered_call_alone() {
        let rig = rig_with(Duration::from_millis(40));
        ring(&rig, "C1");
        rig.table.accept(&rig.calls, "C1").await.unwrap();
        tokio::time::sleep(Duration::from_millis(120)).await;
        assert_eq!(rig.table.phase("C1"), Some(CallPhase::Connecting));
    }

    #[tokio::test]
    async fn missed_and_answered_elsewhere_end_the_call() {
        let rig = rig();
        ring(&rig, "C1");
        ring(&rig, "C2");
        rig.table.on_missed("C1");
        rig.table.on_ended_elsewhere("C2");
        assert_eq!(
            rig.table.phase("C1"),
            Some(CallPhase::Ended(EndReason::Missed))
        );
        assert_eq!(
            rig.table.phase("C2"),
            Some(CallPhase::Ended(EndReason::ElsewhereAnswered))
        );
    }

    #[tokio::test]
    async fn relay_failure_and_unsupported_codec_fail_the_call() {
        let rig = rig();
        ring(&rig, "C1");
        rig.table.accept(&rig.calls, "C1").await.unwrap();
        rig.calls
            .lives()
            .pop()
            .unwrap()
            .emit(LiveEvent::Failed("relay allocation timed out".into()));
        wait_phase(
            &rig.table,
            "C1",
            CallPhase::Ended(EndReason::Failed("relay allocation timed out".into())),
        )
        .await;

        ring(&rig, "C2");
        rig.calls.fail_next_start(StartError::UnsupportedCodec);
        let err = rig.table.accept(&rig.calls, "C2").await.unwrap_err();
        assert_eq!(HttpError::from(err).0.code, ErrorCode::BadRequest);
        assert_eq!(
            rig.table.phase("C2"),
            Some(CallPhase::Ended(EndReason::Failed(
                "unsupported codec".into()
            )))
        );
    }

    #[tokio::test]
    async fn the_peer_hanging_up_ends_a_live_call() {
        let rig = rig();
        ring(&rig, "C1");
        rig.table.accept(&rig.calls, "C1").await.unwrap();
        let live = rig.calls.lives().pop().unwrap();
        live.emit(LiveEvent::MediaUp);
        wait_phase(&rig.table, "C1", CallPhase::Active).await;
        live.finish();
        wait_phase(&rig.table, "C1", CallPhase::Ended(EndReason::Remote)).await;
    }

    #[tokio::test]
    async fn an_outgoing_call_rings_connects_and_can_be_cancelled() {
        let rig = rig();
        let started = rig.table.place(&rig.calls, FROM).await.unwrap();
        let id = started.call_id;
        assert_eq!(rig.table.phase(&id), Some(CallPhase::Ringing));
        assert_eq!(rig.calls.ops(), [format!("place {FROM}")]);

        let live = rig.calls.lives().pop().unwrap();
        rig.table
            .on_incoming(&callee_says(&id, "accept"), &LidMap::default());
        live.emit(LiveEvent::MediaUp);
        wait_phase(&rig.table, &id, CallPhase::Active).await;

        rig.table.end(&rig.calls, &id).await.unwrap();
        assert!(live.hung_up());
        assert_eq!(rig.calls.ops().last(), Some(&format!("terminate {id}")));
        assert_eq!(
            rig.table.phase(&id),
            Some(CallPhase::Ended(EndReason::Hangup))
        );

        let second = rig.table.place(&rig.calls, FROM).await.unwrap();
        rig.table.end(&rig.calls, &second.call_id).await.unwrap();
        assert_eq!(
            rig.table.phase(&second.call_id),
            Some(CallPhase::Ended(EndReason::Hangup))
        );
    }

    #[tokio::test]
    async fn an_unanswered_outgoing_call_is_hung_up_on_timeout() {
        let rig = rig_with(Duration::from_millis(40));
        let id = rig.table.place(&rig.calls, FROM).await.unwrap().call_id;
        wait_phase(&rig.table, &id, CallPhase::Ended(EndReason::Missed)).await;
        assert!(rig.calls.lives().pop().unwrap().hung_up());
    }

    #[tokio::test]
    async fn muting_needs_a_live_call() {
        let rig = rig();
        ring(&rig, "C1");
        assert!(matches!(
            rig.table.set_muted("C1", true),
            Err(CallError::Conflict(_))
        ));
        assert!(matches!(
            rig.table.set_muted("nope", true),
            Err(CallError::NotFound)
        ));
    }

    #[tokio::test]
    async fn a_second_audio_socket_conflicts_until_the_first_leaves() {
        let rig = rig();
        ring(&rig, "C1");
        let first = rig.table.attach_audio("C1").unwrap();
        let second = rig.table.attach_audio("C1").err().unwrap();
        assert_eq!(
            HttpError::from(second).status(),
            axum::http::StatusCode::CONFLICT
        );
        assert!(matches!(
            rig.table.attach_audio("nope"),
            Err(CallError::NotFound)
        ));
        drop(first);
        let again = rig.table.attach_audio("C1").unwrap();

        // The end of the call closes both directions.
        rig.table.on_missed("C1");
        assert!(again.spk_rx.recv().await.is_err());
        assert_eq!(
            feed_mic(&vec![0u8; FRAME_SAMPLES * 2], &again.mic_tx),
            MicOutcome::Closed
        );
        assert!(matches!(
            rig.table.attach_audio("C1"),
            Err(CallError::Conflict(_))
        ));
    }

    #[tokio::test]
    async fn audio_attached_late_starts_without_stale_frames() {
        let rig = rig();
        ring(&rig, "C1");
        rig.table.accept(&rig.calls, "C1").await.unwrap();
        // Nobody listens yet: the engine's frames pile up.
        let ports = {
            let calls = rig.table.lock();
            let entry = calls.get("C1").unwrap();
            entry.spk_tx.try_send(vec![1; FRAME_SAMPLES]).unwrap();
            entry.mic_tx.try_send(vec![2; FRAME_SAMPLES]).unwrap();
            drop(calls);
            rig.table.attach_audio("C1").unwrap()
        };
        assert!(ports.spk_rx.try_recv().is_err());
    }

    #[test]
    fn feed_mic_drops_wrong_sizes_and_reports_lag() {
        let (tx, rx) = async_channel::bounded(2);
        let frame = encode_frame(&vec![7i16; FRAME_SAMPLES]).unwrap();
        assert_eq!(feed_mic(&frame, &tx), MicOutcome::Sent);
        assert_eq!(rx.try_recv().unwrap(), vec![7i16; FRAME_SAMPLES]);
        for bad in [0usize, 1, 1919, 1921, 3840] {
            assert_eq!(feed_mic(&vec![0; bad], &tx), MicOutcome::Dropped, "{bad}");
        }
        assert!(rx.is_empty());
        assert_eq!(feed_mic(&frame, &tx), MicOutcome::Sent);
        assert_eq!(feed_mic(&frame, &tx), MicOutcome::Sent);
        assert_eq!(feed_mic(&frame, &tx), MicOutcome::Lagging);
        tx.close();
        assert_eq!(feed_mic(&frame, &tx), MicOutcome::Closed);
    }

    #[test]
    fn ending_picks_the_right_action() {
        use CallDirection::{In, Out};
        let ended = CallPhase::Ended(EndReason::Hangup);
        assert_eq!(plan_end(In, &ended, true), None);
        assert_eq!(
            plan_end(In, &CallPhase::Ringing, false),
            Some((CallInput::Reject, EndAction::Reject))
        );
        assert_eq!(
            plan_end(Out, &CallPhase::Ringing, true),
            Some((CallInput::Hangup, EndAction::Hangup))
        );
        assert_eq!(
            plan_end(In, &CallPhase::Active, true),
            Some((CallInput::Hangup, EndAction::Hangup))
        );
        // Accepted but the engine has no handle yet: only signaling can stop it.
        assert_eq!(
            plan_end(In, &CallPhase::Connecting, false),
            Some((CallInput::Hangup, EndAction::Terminate))
        );
        assert_eq!(
            plan_end(Out, &CallPhase::Ringing, false),
            Some((CallInput::Hangup, EndAction::Terminate))
        );
    }

    #[test]
    fn engine_events_map_to_call_events() {
        assert_eq!(
            live_event(&CallEvent::RelayAllocated),
            Some(LiveEvent::MediaUp)
        );
        assert_eq!(
            live_event(&CallEvent::RelayAllocateFailed(486)),
            Some(LiveEvent::Failed("relay allocation failed (486)".into()))
        );
        assert!(matches!(
            live_event(&CallEvent::RelayAllocateTimedOut),
            Some(LiveEvent::Failed(_))
        ));
        assert!(matches!(
            live_event(&CallEvent::RelayReconnectTimedOut),
            Some(LiveEvent::Failed(_))
        ));
        assert_eq!(live_event(&CallEvent::WaitingRoomHeartbeatFailed), None);
    }

    #[test]
    fn call_ids_are_checked() {
        assert!(check_call_id("ABC-123_x").is_ok());
        assert!(check_call_id("").is_err());
        assert!(check_call_id("a/b").is_err());
        assert!(check_call_id(&"a".repeat(MAX_CALL_ID_LEN + 1)).is_err());
    }

    fn ends(rig: &Rig) -> Vec<PushKind> {
        rig.pushes
            .0
            .lock()
            .unwrap()
            .iter()
            .filter(|k| matches!(k, PushKind::CallEnd { .. }))
            .cloned()
            .collect()
    }

    fn call_end(id: &str, missed: bool) -> PushKind {
        PushKind::CallEnd {
            call_id: id.into(),
            missed,
            from: FROM.into(),
            name: "Ayse".into(),
        }
    }

    #[tokio::test]
    async fn the_list_has_live_and_ended_calls_newest_first() {
        let rig = rig();
        ring(&rig, "C1");
        let listed = rig.table.list();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].update.call_id, "C1");
        assert_eq!(listed[0].update.phase, CallPhase::Ringing);
        assert_eq!(listed[0].update.peer_name.as_deref(), Some("Ayse"));
        assert!(listed[0].started_ms > 0);
        assert_eq!(listed[0].ended_ms, None);

        rig.table.on_missed("C1");
        let listed = rig.table.list();
        assert_eq!(listed[0].update.phase, CallPhase::Ended(EndReason::Missed));
        assert!(listed[0].ended_ms.unwrap() >= listed[0].started_ms);

        // Newest first.
        tokio::time::sleep(Duration::from_millis(5)).await;
        ring(&rig, "C2");
        let ids: Vec<_> = rig
            .table
            .list()
            .into_iter()
            .map(|c| c.update.call_id)
            .collect();
        assert_eq!(ids, ["C2", "C1"]);
    }

    #[tokio::test]
    async fn a_full_table_evicts_the_oldest_ended_call() {
        let rig = rig();
        for n in 0..MAX_CALLS {
            ring(&rig, &format!("C{n}"));
        }
        // Everything is live: refused.
        ring(&rig, "EXTRA");
        assert_eq!(rig.table.phase("EXTRA"), None);

        // End two; the one that ended first goes.
        rig.table.on_missed("C5");
        tokio::time::sleep(Duration::from_millis(5)).await;
        rig.table.on_missed("C3");
        ring(&rig, "EXTRA");
        assert_eq!(rig.table.phase("EXTRA"), Some(CallPhase::Ringing));
        assert_eq!(rig.table.phase("C5"), None);
        assert!(rig.table.phase("C3").is_some());
        assert_eq!(rig.table.list().len(), MAX_CALLS);
    }

    #[tokio::test]
    async fn a_table_full_of_ended_calls_still_takes_new_ones() {
        let rig = rig();
        for n in 0..MAX_CALLS {
            ring(&rig, &format!("C{n}"));
            rig.table.on_missed(&format!("C{n}"));
        }
        ring(&rig, "NEW");
        assert_eq!(rig.table.phase("NEW"), Some(CallPhase::Ringing));
        assert_eq!(rig.table.phase("C0"), None);
        assert_eq!(rig.table.list().len(), MAX_CALLS);
    }

    #[tokio::test]
    async fn a_full_table_of_live_calls_refuses_an_outgoing_call() {
        let rig = rig();
        for n in 0..MAX_CALLS {
            ring(&rig, &format!("C{n}"));
        }
        assert!(matches!(
            rig.table.place(&rig.calls, FROM).await,
            Err(CallError::Conflict(_))
        ));
    }

    #[tokio::test]
    async fn the_caller_cancelling_pushes_a_missed_call_end() {
        let rig = rig();
        ring(&rig, "C1");
        assert!(ends(&rig).is_empty());
        rig.table.on_missed("C1");
        assert_eq!(ends(&rig), [call_end("C1", true)]);
        // A repeat does not push again.
        rig.table.on_missed("C1");
        assert_eq!(ends(&rig).len(), 1);
    }

    #[tokio::test]
    async fn a_ring_timeout_pushes_a_missed_call_end() {
        let rig = rig_with(Duration::from_millis(40));
        ring(&rig, "C1");
        wait_phase(&rig.table, "C1", CallPhase::Ended(EndReason::Missed)).await;
        assert_eq!(ends(&rig), [call_end("C1", true)]);
    }

    #[tokio::test]
    async fn rejecting_or_answering_elsewhere_pushes_not_missed() {
        let rig = rig();
        ring(&rig, "C1");
        rig.table.reject(&rig.calls, "C1").await.unwrap();
        ring(&rig, "C2");
        rig.table.on_ended_elsewhere("C2");
        ring(&rig, "C3");
        rig.table.end(&rig.calls, "C3").await.unwrap();
        assert_eq!(
            ends(&rig),
            [
                call_end("C1", false),
                call_end("C2", false),
                call_end("C3", false)
            ]
        );
    }

    #[tokio::test]
    async fn answered_and_outgoing_calls_push_no_call_end() {
        let rig = rig();
        ring(&rig, "C1");
        rig.table.accept(&rig.calls, "C1").await.unwrap();
        let live = rig.calls.lives().pop().unwrap();
        live.emit(LiveEvent::MediaUp);
        wait_phase(&rig.table, "C1", CallPhase::Active).await;
        rig.table.end(&rig.calls, "C1").await.unwrap();

        let out = rig.table.place(&rig.calls, FROM).await.unwrap().call_id;
        rig.table.on_missed(&out);
        let out2 = rig.table.place(&rig.calls, FROM).await.unwrap().call_id;
        rig.table.end(&rig.calls, &out2).await.unwrap();
        assert!(ends(&rig).is_empty());
    }

    /// What the callee's device `from` sends about our outgoing call `call_id`.
    fn callee_says(call_id: &str, action: &str) -> IncomingCall {
        use whatsapp_rust::Jid;
        use whatsapp_rust::chrono::Utc;
        let from: Jid = FROM.parse().unwrap();
        let creator = from.clone();
        let call_id = call_id.to_owned();
        let action = match action {
            "accept" => CallAction::Accept {
                call_id,
                call_creator: creator,
                audio: Vec::new(),
            },
            "busy" => CallAction::Reject {
                call_id,
                call_creator: creator,
                reason: Some("busy".into()),
            },
            "reject" => CallAction::Reject {
                call_id,
                call_creator: creator,
                reason: None,
            },
            other => panic!("no such action {other}"),
        };
        IncomingCall::builder()
            .from(from)
            .stanza_id("stanza-callee".to_owned())
            .timestamp(Utc::now())
            .offline(false)
            .action(action)
            .build()
    }

    #[tokio::test]
    async fn cancelling_a_ringing_outgoing_call_terminates_it_on_whatsapp() {
        let rig = rig();
        let id = rig.table.place(&rig.calls, FROM).await.unwrap().call_id;
        assert_eq!(rig.table.phase(&id), Some(CallPhase::Ringing));
        rig.table.end(&rig.calls, &id).await.unwrap();
        assert_eq!(
            rig.table.phase(&id),
            Some(CallPhase::Ended(EndReason::Hangup))
        );
        // The callee must hear about it: a local teardown alone keeps it ringing.
        assert_eq!(
            rig.calls.ops(),
            [format!("place {FROM}"), format!("terminate {id}")]
        );
        assert!(rig.calls.lives().pop().unwrap().hung_up());
        // Ending again is a conflict, not a second terminate.
        assert!(matches!(
            rig.table.end(&rig.calls, &id).await,
            Err(CallError::Conflict(_))
        ));
        assert_eq!(rig.calls.ops().len(), 2);
    }

    #[tokio::test]
    async fn ending_an_answered_call_terminates_it_on_whatsapp() {
        let rig = rig();
        ring(&rig, "C1");
        rig.table.accept(&rig.calls, "C1").await.unwrap();
        let live = rig.calls.lives().pop().unwrap();
        live.emit(LiveEvent::MediaUp);
        wait_phase(&rig.table, "C1", CallPhase::Active).await;
        rig.table.end(&rig.calls, "C1").await.unwrap();
        assert_eq!(rig.calls.ops(), ["accept C1", "terminate C1"]);
    }

    #[tokio::test]
    async fn an_unanswered_outgoing_call_is_terminated_on_whatsapp_at_the_timeout() {
        let rig = rig_with(Duration::from_millis(40));
        let id = rig.table.place(&rig.calls, FROM).await.unwrap().call_id;
        wait_phase(&rig.table, &id, CallPhase::Ended(EndReason::Missed)).await;
        for _ in 0..200 {
            if rig.calls.ops().len() == 2 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        assert_eq!(
            rig.calls.ops(),
            [format!("place {FROM}"), format!("terminate {id}")]
        );
    }

    #[tokio::test]
    async fn the_relay_coming_up_does_not_answer_an_outgoing_call() {
        let rig = rig();
        let id = rig.table.place(&rig.calls, FROM).await.unwrap().call_id;
        // whatsapp-rust attaches the relay from the offer's ack, so the
        // relay is allocated while the callee still rings.
        rig.calls.lives().pop().unwrap().emit(LiveEvent::MediaUp);
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(rig.table.phase(&id), Some(CallPhase::Ringing));
        // The callee's <accept> answers it; media is already up.
        rig.table
            .on_incoming(&callee_says(&id, "accept"), &LidMap::default());
        assert_eq!(rig.table.phase(&id), Some(CallPhase::Active));
    }

    #[tokio::test]
    async fn an_accept_before_the_relay_waits_for_media() {
        let rig = rig();
        let id = rig.table.place(&rig.calls, FROM).await.unwrap().call_id;
        rig.table
            .on_incoming(&callee_says(&id, "accept"), &LidMap::default());
        assert_eq!(rig.table.phase(&id), Some(CallPhase::Connecting));
        rig.calls.lives().pop().unwrap().emit(LiveEvent::MediaUp);
        wait_phase(&rig.table, &id, CallPhase::Active).await;
    }

    #[tokio::test]
    async fn a_busy_device_does_not_end_an_outgoing_call() {
        let rig = rig();
        let id = rig.table.place(&rig.calls, FROM).await.unwrap().call_id;
        // One of the callee's devices cannot take calls; the others ring on.
        rig.table
            .on_incoming(&callee_says(&id, "busy"), &LidMap::default());
        assert_eq!(rig.table.phase(&id), Some(CallPhase::Ringing));
        // So the caller can still cancel it, and WhatsApp hears about it.
        rig.table.end(&rig.calls, &id).await.unwrap();
        assert_eq!(rig.calls.ops().last(), Some(&format!("terminate {id}")));

        let declined = rig.table.place(&rig.calls, FROM).await.unwrap().call_id;
        rig.table
            .on_incoming(&callee_says(&declined, "reject"), &LidMap::default());
        assert_eq!(
            rig.table.phase(&declined),
            Some(CallPhase::Ended(EndReason::Rejected))
        );
    }

    #[tokio::test]
    async fn a_failed_place_leaves_no_call() {
        let rig = rig();
        rig.calls
            .fail_next_start(StartError::Driver(DriverError::NotConnected));
        assert!(rig.table.place(&rig.calls, FROM).await.is_err());
        assert!(rig.table.lock().is_empty());
    }
}
