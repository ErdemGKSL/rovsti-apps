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
use rovsti_wa_proto::audio::{decode_frame, encode_frame};
use rovsti_wa_proto::{
    CallDirection, CallInput, CallMachine, CallPhase, CallStarted, CallUpdate, ErrorCode,
    GatewayEvent, PlaceCall, SetMuted,
};
use whatsapp_rust::CallError as WaCallError;
use whatsapp_rust::voip::{CallEvent, CallHandle};
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

/// A ringing call that nobody answers ends as missed after this long.
pub const RING_TIMEOUT: Duration = Duration::from_secs(60);
/// Frames each audio channel holds (about half a second).
pub const AUDIO_QUEUE: usize = 8;
/// Ended calls stay queryable this long.
const KEEP_ENDED: Duration = Duration::from_secs(300);
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

    /// Ends the call.
    fn hangup(&self) -> BoxFuture<'_, ()>;

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
    ended_at: Option<Instant>,
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
            ended_at: None,
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
            GatewayEvent::Call(CallUpdate {
                call_id: call_id.to_owned(),
                peer: entry.peer.clone(),
                peer_name: entry.peer_name.clone(),
                direction: entry.machine.direction(),
                phase: entry.machine.phase().clone(),
                is_video: entry.is_video,
            }),
        );
    }

    /// Applies `input` to `entry`; publishes and, when the call ends, closes
    /// its audio channels so the socket and the engine stop.
    fn apply(&self, call_id: &str, entry: &mut CallEntry, input: CallInput) -> Option<CallPhase> {
        let phase = entry.machine.apply(input)?;
        if phase.is_ended() {
            entry.ended_at = Some(Instant::now());
            entry.mic_tx.close();
            entry.spk_tx.close();
        }
        self.publish(call_id, entry);
        Some(phase)
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
        if calls.len() >= MAX_CALLS || calls.contains_key(call_id) {
            return false;
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
        if let Some(handle) = handle {
            handle.hangup().await;
        }
    }

    /// Stores the live handle and starts following it. A call that ended
    /// while it was being set up is hung up at once.
    async fn attach_handle(self: &Arc<Self>, call_id: &str, handle: Arc<dyn LiveCall>) {
        let direction = {
            let mut calls = self.lock();
            match calls.get_mut(call_id) {
                Some(entry) if !entry.machine.phase().is_ended() => {
                    entry.handle = Some(Arc::clone(&handle));
                    Some(entry.machine.direction())
                }
                _ => None,
            }
        };
        match direction {
            Some(direction) => {
                tokio::spawn(Arc::clone(self).follow(call_id.to_owned(), handle, direction));
            }
            None => handle.hangup().await,
        }
    }

    /// Turns the engine's events into phases until the call is over.
    async fn follow(self: Arc<Self>, call_id: String, handle: Arc<dyn LiveCall>, d: CallDirection) {
        let ended = handle.wait_ended();
        tokio::pin!(ended);
        loop {
            tokio::select! {
                // Events first: a failure just before the end is the reason.
                biased;
                event = handle.next_event() => match event {
                    Some(LiveEvent::MediaUp) => {
                        if d == CallDirection::Out {
                            // Media only flows once the callee picked up.
                            self.transition(&call_id, CallInput::RemoteAccepted);
                        }
                        self.transition(&call_id, CallInput::MediaUp);
                    }
                    Some(LiveEvent::Failed(reason)) => {
                        self.transition(&call_id, CallInput::Failed(reason));
                        handle.hangup().await;
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
    pub fn on_incoming(self: &Arc<Self>, call: &IncomingCall) {
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
                let from = call.from.to_non_ad().to_string();
                let mut entry = CallEntry::new(CallDirection::In, from.clone());
                entry.wire_peer = call.from.to_string();
                entry.creator = call_creator.to_string();
                entry.peer_name = call.notify.clone().filter(|n| !n.is_empty());
                entry.is_video = *is_video;
                let name = entry.peer_name.clone().unwrap_or_default();
                entry.incoming = Some(call.clone());
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
            CallAction::Accept { call_id, .. } => {
                self.transition(call_id, CallInput::RemoteAccepted);
            }
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
            (EndAction::Hangup, Some(handle), _) => handle.hangup().await,
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
        entry.creator = call_id.clone();
        if !self.insert(&call_id, entry) {
            handle.hangup().await;
            return Err(CallError::Conflict("too many calls"));
        }
        self.spawn_ring_timeout(call_id.clone());
        self.attach_handle(&call_id, handle).await;
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
    loop {
        tokio::select! {
            frame = ports.spk_rx.recv() => match frame {
                Ok(samples) => match encode_frame(&samples) {
                    Ok(bytes) => {
                        if socket.send(Message::Binary(bytes.into())).await.is_err() {
                            break;
                        }
                    }
                    Err(e) => log::warn!("call audio: dropping speaker frame: {e}"),
                },
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
        .route(r::CALLS, post(place))
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

/// A [`CallHandle`] as a [`LiveCall`].
struct WaLiveCall(CallHandle);

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
        self.0.call_id().to_owned()
    }

    fn hangup(&self) -> BoxFuture<'_, ()> {
        Box::pin(self.0.hangup())
    }

    fn set_muted(&self, muted: bool) {
        self.0.set_muted(muted);
    }

    fn next_event(&self) -> BoxFuture<'_, Option<LiveEvent>> {
        let events = self.0.events();
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
        Box::pin(self.0.wait_ended())
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
            Ok(Arc::new(WaLiveCall(handle)) as Arc<dyn LiveCall>)
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
            Ok(Arc::new(WaLiveCall(handle)) as Arc<dyn LiveCall>)
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

    /// A call that follows whatever the test tells it.
    pub struct FakeLive {
        id: String,
        muted: AtomicBool,
        hung_up: AtomicBool,
        events_tx: Sender<LiveEvent>,
        events_rx: Receiver<LiveEvent>,
        ended: tokio::sync::watch::Sender<bool>,
    }

    impl FakeLive {
        pub fn new(id: &str) -> Arc<Self> {
            let (events_tx, events_rx) = async_channel::unbounded();
            Arc::new(Self {
                id: id.to_owned(),
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
        ops: Mutex<Vec<String>>,
        lives: Mutex<Vec<Arc<FakeLive>>>,
        fail_start: Mutex<Option<StartError>>,
        counter: AtomicU64,
    }

    impl FakeCalls {
        pub fn new() -> Self {
            Self::default()
        }

        /// `accept`, `place`, `reject` and `terminate` calls so far.
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
            self.ops.lock().unwrap_or_else(|e| e.into_inner()).push(op);
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
            let live = FakeLive::new(&id);
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
        rig.table.on_incoming(&offer(id, FROM, Some("Ayse")));
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
        live.emit(LiveEvent::MediaUp);
        wait_phase(&rig.table, &id, CallPhase::Active).await;

        rig.table.end(&rig.calls, &id).await.unwrap();
        assert!(live.hung_up());
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

    #[tokio::test]
    async fn a_failed_place_leaves_no_call() {
        let rig = rig();
        rig.calls
            .fail_next_start(StartError::Driver(DriverError::NotConnected));
        assert!(rig.table.place(&rig.calls, FROM).await.is_err());
        assert!(rig.table.lock().is_empty());
    }
}
