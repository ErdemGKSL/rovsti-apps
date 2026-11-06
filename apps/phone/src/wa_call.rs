//! WhatsApp calls in Phone (AD-WA5).
//!
//! The call itself runs on the gateway. Phone shows what the gateway says
//! (`GatewayEvent::Call`) as a glass card: Answer and Decline while an incoming
//! call rings, Mute and End once it is up. On Answer the phone opens the call
//! audio socket and starts Kotlin's microphone and speaker; one pump task moves
//! frames between them. On desktop the Android calls return `Unsupported`: the
//! card works, there is just no sound.
//!
//! [`Tracker`], [`status_line`], [`mic_step`] and [`play_available`] are pure
//! and unit tested; [`WaCallUi`] is the thin GPUI entity around them.

use std::{
    collections::VecDeque,
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use futures::{FutureExt, StreamExt, channel::mpsc::TrySendError, select_biased};
use gpui::{
    AnyElement, Context, Entity, InteractiveElement, IntoElement, ParentElement, Render, Role,
    SharedString, StatefulInteractiveElement, Styled, Task, Window, div, px,
};
use rovsti_android::{Error as AndroidError, events::WaCallAction};
use rovsti_core::{
    recents::ExtraCall,
    wire::{Handoff, HandoffAction},
};
use rovsti_ui::{
    components::flat_glass,
    current_theme,
    theme::{call_colors, colors},
    tokens::{Theme, typography},
};
use rovsti_wa_client::{CallAudio, ClientError};
use rovsti_wa_proto::{
    CallDirection, CallPhase, CallSummary, CallUpdate, ConnectKey, EndReason, GatewayEvent,
    GatewayUrl, WaCallHistory, WaCallView, WaScreen, plan_snapshot, tracked_end,
};
use rovsti_wa_ui::{StoredLink, WaAvatars, WhatsAppSettings};

/// An ended call stays on screen this long, so "Call ended" can be read.
const ENDED_HOLD: Duration = Duration::from_millis(2000);
/// How often the pump asks Kotlin for microphone frames (a frame is 60 ms).
const MIC_POLL: Duration = Duration::from_millis(20);
/// Frames moved per poll at most, so a stalled consumer cannot spin the pump.
const MIC_BURST: usize = 8;
/// The local WhatsApp call history, next to `prefs.json`.
const HISTORY_FILE: &str = "wa-calls.json";
/// A notification action waits this long for its call to reach the app.
const ACTION_TTL_MS: i64 = 60_000;
/// How many handled call ids the guard remembers.
const ONCE_MEMORY: usize = 16;

// ---- pure ------------------------------------------------------------------------

/// What an update did to the tracked call.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Change {
    /// Another call, or an update for a call that is already over.
    Ignored,
    /// The call was shown or changed.
    Shown,
    /// The call ended with this update.
    Ended,
}

#[derive(Clone, Debug, PartialEq)]
struct Tracked {
    update: CallUpdate,
    started_ms: i64,
    active_since_ms: Option<i64>,
    muted: bool,
}

/// The one WhatsApp call Phone shows at a time, folded from gateway updates.
#[derive(Debug, Default)]
pub struct Tracker {
    current: Option<Tracked>,
}

impl Tracker {
    pub fn on_update(&mut self, update: CallUpdate, now_ms: i64) -> Change {
        match &mut self.current {
            None if update.phase.is_ended() => Change::Ignored,
            None => {
                self.current = Some(Tracked {
                    started_ms: now_ms,
                    active_since_ms: (update.phase == CallPhase::Active).then_some(now_ms),
                    update,
                    muted: false,
                });
                Change::Shown
            }
            Some(tracked) if tracked.update.call_id != update.call_id => Change::Ignored,
            Some(tracked) => {
                if update.phase == CallPhase::Active && tracked.active_since_ms.is_none() {
                    tracked.active_since_ms = Some(now_ms);
                }
                let ended = update.phase.is_ended();
                tracked.update = update;
                if ended { Change::Ended } else { Change::Shown }
            }
        }
    }

    /// The gateway accepted an outgoing call: show it as ringing at once, before
    /// its first update arrives. Ignored while any call is on screen.
    pub fn on_placed(&mut self, call_id: &str, peer: &str, now_ms: i64) -> Change {
        if self.current.is_some() {
            return Change::Ignored;
        }
        self.on_update(
            CallUpdate {
                call_id: call_id.to_owned(),
                peer: peer.to_owned(),
                peer_name: None,
                direction: CallDirection::Out,
                phase: CallPhase::Ringing,
                is_video: false,
            },
            now_ms,
        )
    }

    pub fn view(&self, now_ms: i64) -> Option<WaCallView> {
        self.current.as_ref().map(|t| {
            WaCallView::from_update(&t.update, now_ms)
                .with_muted(t.muted)
                .with_active_since(t.active_since_ms, now_ms)
        })
    }

    /// When the tracked call first showed (its start, for the call history).
    pub fn started_ms(&self) -> Option<i64> {
        self.current.as_ref().map(|t| t.started_ms)
    }

    pub fn set_muted(&mut self, muted: bool) {
        if let Some(tracked) = &mut self.current {
            tracked.muted = muted;
        }
    }

    pub fn clear(&mut self) {
        self.current = None;
    }

    pub fn is_empty(&self) -> bool {
        self.current.is_none()
    }
}

/// Whether a WhatsApp call may be started now; the error is shown to the user.
pub fn can_place(connected: bool, busy: bool, number: &str) -> Result<(), &'static str> {
    if number.trim().is_empty() {
        Err("No number to call")
    } else if !connected {
        Err("WhatsApp is not connected")
    } else if busy {
        Err("Another call is in progress")
    } else {
        Ok(())
    }
}

/// Makes a notification action (Answer, Decline) take effect once per call,
/// however often Android redelivers its intent (`onCreate` and `onNewIntent`
/// both see it, and so does a recreated activity).
///
/// An Answer may arrive before the gateway has told the app about the call (a
/// cold start): it waits, up to [`ACTION_TTL_MS`], until that call rings. A
/// Decline needs no call on screen, the gateway can reject by id.
#[derive(Debug, Default)]
pub struct AnswerOnce {
    done: VecDeque<String>,
    pending: Option<PendingAction>,
}

#[derive(Debug)]
struct PendingAction {
    call_id: String,
    action: WaCallAction,
    at_ms: i64,
}

impl AnswerOnce {
    /// Registers an action. `false` when this call already had one (the first
    /// action wins, a late Decline cannot undo an Answer).
    pub fn offer(&mut self, call_id: &str, action: WaCallAction, now_ms: i64) -> bool {
        if self.done.iter().any(|id| id == call_id)
            || self.pending.as_ref().is_some_and(|p| p.call_id == call_id)
        {
            return false;
        }
        self.pending = Some(PendingAction {
            call_id: call_id.to_owned(),
            action,
            at_ms: now_ms,
        });
        true
    }

    /// The action that may run now, consumed: `shown` is the call on screen
    /// (`(call id, ringing incoming)`).
    pub fn due(
        &mut self,
        shown: Option<(&str, bool)>,
        now_ms: i64,
    ) -> Option<(String, WaCallAction)> {
        let pending = self.pending.as_ref()?;
        if now_ms.saturating_sub(pending.at_ms) > ACTION_TTL_MS {
            self.pending = None;
            return None;
        }
        let ready = match pending.action {
            WaCallAction::Decline => true,
            WaCallAction::Answer => shown == Some((pending.call_id.as_str(), true)),
        };
        if !ready {
            return None;
        }
        let PendingAction {
            call_id, action, ..
        } = self.pending.take()?;
        self.done.push_back(call_id.clone());
        if self.done.len() > ONCE_MEMORY {
            self.done.pop_front();
        }
        Some((call_id, action))
    }

    /// An action for this call already ran (a snapshot must not show it again).
    pub fn handled(&self, call_id: &str) -> bool {
        self.done.iter().any(|id| id == call_id)
    }

    /// The call is over: a waiting Answer for it is pointless now.
    pub fn call_ended(&mut self, call_id: &str) {
        if self
            .pending
            .as_ref()
            .is_some_and(|p| p.call_id == call_id && p.action == WaCallAction::Answer)
        {
            self.pending = None;
        }
    }
}

/// `+905551234567` for a phone JID (`905551234567:12@s.whatsapp.net`); `None`
/// for anything that is not a phone number (`@lid`, groups).
pub fn dial_number(peer: &str) -> Option<String> {
    let (user, server) = peer.split_once('@')?;
    let user = user.split(':').next()?;
    (server == "s.whatsapp.net" && !user.is_empty() && user.bytes().all(|b| b.is_ascii_digit()))
        .then(|| format!("+{user}"))
}

/// The number to call for a handoff from a sibling app: only `WaCall` handoffs for a phone JID
/// qualify (`+<digits>`); chats, unknown actions and `@lid` peers give `None`.
pub fn handoff_number(handoff: &Handoff) -> Option<String> {
    (handoff.action == HandoffAction::WaCall)
        .then(|| dial_number(&handoff.jid))
        .flatten()
}

/// The history as Recents rows. Calls with a peer that has no phone number
/// cannot be called back from Recents and are left out.
pub fn extra_calls(history: &WaCallHistory) -> Vec<ExtraCall> {
    history
        .iter()
        .filter_map(|record| {
            let number = dial_number(&record.peer)?;
            let label = (record.peer_label != number).then(|| record.peer_label.clone());
            Some(ExtraCall {
                number_raw: number,
                started_ms: record.started_ms,
                duration_s: record.duration_s,
                missed: record.missed,
                label,
            })
        })
        .collect()
}

/// `m:ss`, or `h:mm:ss` from an hour on.
pub fn format_elapsed(seconds: u64) -> String {
    let (h, m, s) = (seconds / 3600, seconds / 60 % 60, seconds % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

/// The line under the caller's name.
pub fn status_line(view: &WaCallView) -> String {
    match (&view.phase, view.direction) {
        (CallPhase::Ringing, CallDirection::In) => "WhatsApp call".into(),
        (CallPhase::Ringing, CallDirection::Out) => "Calling…".into(),
        (CallPhase::Connecting, _) => "Connecting…".into(),
        (CallPhase::Active, _) => format_elapsed(view.elapsed_s),
        (CallPhase::Ended(reason), _) => match reason {
            EndReason::Hangup | EndReason::Remote => "Call ended".into(),
            EndReason::Rejected => "Declined".into(),
            EndReason::Missed => "Missed call".into(),
            EndReason::ElsewhereAnswered => "Answered on another device".into(),
            EndReason::Failed(_) => "Call failed".into(),
        },
    }
}

/// What one microphone poll means for the pump.
#[derive(Debug, PartialEq, Eq)]
pub enum MicStep {
    /// A frame to send to the gateway.
    Frame(Vec<i16>),
    /// Nothing ready yet.
    Idle,
    /// No microphone on this platform; stop polling it.
    Unavailable,
}

pub fn mic_step(read: rovsti_android::Result<Option<Vec<i16>>>) -> MicStep {
    match read {
        Ok(Some(frame)) => MicStep::Frame(frame),
        Ok(None) => MicStep::Idle,
        Err(AndroidError::Unsupported | AndroidError::NotInitialized) => MicStep::Unavailable,
        Err(error) => {
            log::debug!("dropping a microphone frame: {error}");
            MicStep::Idle
        }
    }
}

/// Whether the speaker can still be fed after a play attempt. `Ok(false)` is a
/// full queue (the frame is dropped), not a failure.
pub fn play_available(played: &rovsti_android::Result<bool>) -> bool {
    !matches!(
        played,
        Err(AndroidError::Unsupported | AndroidError::NotInitialized)
    )
}

/// Whether a speaker request took effect. Without Android (desktop) there is
/// nothing to switch, so the screen still shows the chosen state.
pub fn speaker_applied(result: &rovsti_android::Result<bool>) -> bool {
    matches!(
        result,
        Ok(true) | Err(AndroidError::Unsupported | AndroidError::NotInitialized)
    )
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|d| i64::try_from(d.as_millis()).ok())
        .unwrap_or_default()
}

// ---- audio pump -----------------------------------------------------------------

/// Moves frames between Kotlin's audio and the gateway socket until the call
/// ends (the socket closes) or the task is dropped.
async fn pump(mut audio: CallAudio, muted: Arc<AtomicBool>, timer: impl Fn(Duration) -> Task<()>) {
    let mut mic_on = true;
    let mut speaker_on = true;
    loop {
        let mut tick = timer(MIC_POLL).fuse();
        let mut frame = audio.speaker.next().fuse();
        select_biased! {
            frame = frame => {
                let Some(frame) = frame else { break };
                if speaker_on {
                    let played = rovsti_android::wa::call_audio_play(&frame);
                    speaker_on = play_available(&played);
                }
            }
            () = tick => {
                if !mic_on {
                    continue;
                }
                for _ in 0..MIC_BURST {
                    match mic_step(rovsti_android::wa::call_audio_read()) {
                        MicStep::Frame(frame) => {
                            if muted.load(Ordering::Relaxed) {
                                continue;
                            }
                            // A full queue drops the frame: late audio is worse than none.
                            if let Err(TrySendError { .. }) = audio.mic.try_send(frame)
                                && audio.mic.is_closed()
                            {
                                return;
                            }
                        }
                        MicStep::Idle => break,
                        MicStep::Unavailable => {
                            mic_on = false;
                            break;
                        }
                    }
                }
            }
        }
    }
}

struct Session {
    muted: Arc<AtomicBool>,
    _pump: Task<()>,
}

fn parse_link(link: &StoredLink) -> Result<(GatewayUrl, ConnectKey), ClientError> {
    Ok((
        GatewayUrl::parse(&link.gateway_url)?,
        ConnectKey::parse(&link.key)?,
    ))
}

/// Opens the call audio socket and starts Kotlin's microphone and speaker.
fn open_call_audio(link: &StoredLink, call_id: &str) -> Result<CallAudio, ClientError> {
    let (url, key) = parse_link(link)?;
    let audio = rovsti_wa_client::call_audio(url, key, call_id)?;
    match rovsti_android::wa::call_audio_start() {
        Ok(true) => {}
        Ok(false) => log::warn!("call audio did not start: is RECORD_AUDIO granted?"),
        Err(error) => log::debug!("no call audio on this platform: {error}"),
    }
    Ok(audio)
}

// ---- the entity ------------------------------------------------------------------

pub struct WaCallUi {
    settings: Entity<WhatsAppSettings>,
    tracker: Tracker,
    session: Option<Session>,
    /// Answer was pressed and the audio is being set up.
    answering: bool,
    /// A call is being placed (the gateway has not answered yet).
    placing: bool,
    /// The loudspeaker is on for the current call.
    speaker: bool,
    /// The glass In-Call screen draws the call; this entity draws nothing itself.
    incall_mounted: bool,
    once: AnswerOnce,
    history: WaCallHistory,
    /// Bumped when the history changed; Recents rebuilds on a new value.
    history_rev: u64,
    history_dir: Option<PathBuf>,
    /// Latest write wins: an older queued write skips itself.
    write_gen: Arc<AtomicU64>,
    /// The last failure, said the way the user should read it.
    error: Option<&'static str>,
    /// A snapshot fetch is running; another request only sets `resync`.
    syncing: bool,
    resync: bool,
    /// The link was connected at the last settings change.
    was_connected: bool,
    _tick: Option<Task<()>>,
    _clear: Option<Task<()>>,
}

impl WaCallUi {
    /// `avatars` gets every gateway event this entity takes from `settings`
    /// (profiles and picture changes).
    pub fn new(
        settings: Entity<WhatsAppSettings>,
        avatars: Entity<WaAvatars>,
        cx: &mut Context<Self>,
    ) -> Self {
        cx.observe(&settings, move |this, settings, cx| {
            settings.update(cx, |settings, cx| settings.register_push(cx));
            let events = settings.update(cx, |settings, _| settings.take_events());
            if !events.is_empty() {
                avatars.update(cx, |avatars, cx| {
                    for event in &events {
                        avatars.on_event(event, cx);
                    }
                });
            }
            let linked = settings.read(cx).link().is_some();
            let connected = settings.read(cx).is_connected();
            let resync = events.iter().any(|e| matches!(e, GatewayEvent::Resync));
            this.on_events(events, linked, cx);
            if connected && (!this.was_connected || resync) {
                this.sync_calls(cx);
            }
            this.was_connected = connected;
        })
        .detach();
        // The history is a small file: read it off the UI thread, then show it.
        let dir = rovsti_ui::storage::data_dir("phone", cx);
        cx.spawn(async move |this, cx| {
            let Ok(dir) = dir.await else { return };
            let read = {
                let dir = dir.clone();
                cx.background_executor()
                    .spawn(async move { std::fs::read_to_string(dir.join(HISTORY_FILE)) })
                    .await
            };
            this.update(cx, |this, cx| {
                this.history_dir = Some(dir);
                if let Ok(text) = read {
                    // Calls ended while the file was being read stay on top.
                    let mut loaded = WaCallHistory::from_json(&text);
                    loaded.extend_newest(&this.history);
                    this.history = loaded;
                    this.history_rev += 1;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
        Self {
            settings,
            tracker: Tracker::default(),
            session: None,
            answering: false,
            placing: false,
            speaker: false,
            incall_mounted: false,
            once: AnswerOnce::default(),
            history: WaCallHistory::new(),
            history_rev: 0,
            history_dir: None,
            write_gen: Arc::new(AtomicU64::new(0)),
            error: None,
            syncing: false,
            resync: false,
            was_connected: false,
            _tick: None,
            _clear: None,
        }
    }

    /// The In-Call screen is mounted and draws WhatsApp calls; the card is only
    /// the fallback for a build without it.
    pub fn set_incall_mounted(&mut self, mounted: bool) {
        self.incall_mounted = mounted;
    }

    /// What the glass screens draw for the current call, if any.
    pub fn screen(&self) -> Option<WaScreen> {
        self.tracker
            .view(now_ms())
            .map(|view| WaScreen::from_view(&view, self.speaker))
    }

    /// A WhatsApp call is on screen (cheap: no view is built).
    pub fn has_call(&self) -> bool {
        !self.tracker.is_empty()
    }

    /// The raw call view, for arbitration against the cellular call.
    pub(crate) fn view(&self) -> Option<WaCallView> {
        self.tracker.view(now_ms())
    }

    /// Switches the loudspeaker; the screen follows at once and reverts if Android refuses.
    pub fn set_speaker(&mut self, on: bool, cx: &mut Context<Self>) {
        if self.tracker.is_empty() || self.speaker == on {
            return;
        }
        self.speaker = on;
        let task = cx
            .background_executor()
            .spawn(async move { rovsti_android::wa::call_audio_speaker(on) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            if speaker_applied(&result) {
                return;
            }
            this.update(cx, |this, cx| {
                this.speaker = !on;
                let why = result.err().map_or_else(
                    || "the speaker was not switched".to_owned(),
                    |e| e.to_string(),
                );
                this.fail("Could not change the speaker", why, cx);
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn on_events(&mut self, events: Vec<GatewayEvent>, linked: bool, cx: &mut Context<Self>) {
        if !linked && !self.tracker.is_empty() {
            self.finish(cx);
            self.tracker.clear();
            cx.notify();
            return;
        }
        for event in events {
            if let GatewayEvent::Call(update) = event {
                self.on_call(update, cx);
            }
        }
    }

    fn on_call(&mut self, update: CallUpdate, cx: &mut Context<Self>) {
        match self.tracker.on_update(update, now_ms()) {
            Change::Ignored => return,
            Change::Shown => {
                self.start_ticking(cx);
                // A notification Answer may have been waiting for this call.
                self.run_due_actions(cx);
            }
            Change::Ended => {
                if let Some(view) = self.tracker.view(now_ms()) {
                    self.once.call_ended(&view.call_id);
                    self.cancel_notice(&view.call_id, cx);
                    self.record_history(&view, cx);
                }
                self.finish(cx);
                self._tick = None;
                self._clear = Some(cx.spawn(async move |this, cx| {
                    cx.background_executor().timer(ENDED_HOLD).await;
                    this.update(cx, |this, cx| {
                        this.tracker.clear();
                        this.error = None;
                        cx.notify();
                    })
                    .ok();
                }));
            }
        }
        cx.notify();
    }

    /// Takes the incoming-call notification down (it may not exist).
    fn cancel_notice(&self, call_id: &str, cx: &mut Context<Self>) {
        let call_id = call_id.to_owned();
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = rovsti_android::wa::cancel_incoming(&call_id) {
                    log::debug!("no notification to cancel: {error}");
                }
            })
            .detach();
    }

    /// Writes the ended call to the history, once, and saves it.
    fn record_history(&mut self, view: &WaCallView, cx: &mut Context<Self>) {
        let CallPhase::Ended(reason) = &view.phase else {
            return;
        };
        let started = self.tracker.started_ms().unwrap_or_else(now_ms);
        if !self.history.record_end(view, started, reason) {
            return;
        }
        self.history_rev += 1;
        self.save_history(cx);
    }

    /// Saves the history off the UI thread; the latest save wins.
    fn save_history(&mut self, cx: &mut Context<Self>) {
        let Some(dir) = self.history_dir.clone() else {
            return;
        };
        let records: Vec<_> = self.history.iter().cloned().collect();
        let generation = self.write_gen.fetch_add(1, Ordering::SeqCst) + 1;
        let latest = self.write_gen.clone();
        cx.background_executor()
            .spawn(async move {
                if latest.load(Ordering::SeqCst) != generation {
                    return; // a newer snapshot is queued behind this one
                }
                if let Err(error) = rovsti_ui::storage::save_json(&dir, HISTORY_FILE, &records) {
                    log::warn!("saving the WhatsApp call history failed: {}", error.kind());
                }
            })
            .detach();
    }

    /// Fetches the gateway's call snapshot and folds it in: a live call becomes
    /// the tracked one (so a cold-start Answer can run), ended calls this device
    /// missed go to the history. One fetch runs at a time; a request meanwhile
    /// runs one more fetch afterwards.
    pub fn sync_calls(&mut self, cx: &mut Context<Self>) {
        if self.syncing {
            self.resync = true;
            return;
        }
        let Some(gateway) = self.settings.read(cx).gateway() else {
            return;
        };
        self.syncing = true;
        let task = cx
            .background_executor()
            .spawn(async move { gateway.calls() });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.syncing = false;
                match result {
                    Ok(summaries) => this.apply_snapshot(&summaries, cx),
                    Err(error) => log::warn!("fetching the WhatsApp calls failed: {error}"),
                }
                if std::mem::take(&mut this.resync) {
                    this.sync_calls(cx);
                }
            })
            .ok();
        })
        .detach();
    }

    fn apply_snapshot(&mut self, summaries: &[CallSummary], cx: &mut Context<Self>) {
        // The call on screen may have ended while its event was missed.
        let tracked = self
            .tracker
            .view(now_ms())
            .filter(|v| !v.phase.is_ended())
            .map(|v| v.call_id);
        if let Some(end) = tracked_end(tracked.as_deref(), summaries) {
            self.on_call(end, cx);
        }
        let plan = plan_snapshot(!self.tracker.is_empty(), summaries, &self.history);
        // A call that was just declined may still ring in the snapshot.
        if let Some(update) = plan.show.filter(|u| !self.once.handled(&u.call_id)) {
            self.on_call(update, cx);
        }
        let mut recorded = false;
        for summary in &plan.ended {
            let label = WaCallView::from_update(&summary.update, now_ms()).peer_label;
            recorded |= self.history.record_summary(summary, &label);
            self.cancel_notice(&summary.update.call_id, cx);
            self.once.call_ended(&summary.update.call_id);
        }
        if recorded {
            self.history_rev += 1;
            self.save_history(cx);
            cx.notify();
        }
    }

    /// The WhatsApp calls as Recents rows.
    pub fn recents_extras(&self) -> Vec<ExtraCall> {
        extra_calls(&self.history)
    }

    /// Changes whenever [`Self::recents_extras`] would.
    pub fn history_rev(&self) -> u64 {
        self.history_rev
    }

    /// The notification's Answer for `call_id` (idempotent, see [`AnswerOnce`]).
    pub fn answer_id(&mut self, call_id: &str, cx: &mut Context<Self>) {
        self.sync_calls(cx);
        if self.once.offer(call_id, WaCallAction::Answer, now_ms()) {
            self.run_due_actions(cx);
        }
    }

    /// The notification's Decline for `call_id` (idempotent, see [`AnswerOnce`]).
    pub fn decline_id(&mut self, call_id: &str, cx: &mut Context<Self>) {
        self.sync_calls(cx);
        if self.once.offer(call_id, WaCallAction::Decline, now_ms()) {
            self.run_due_actions(cx);
        }
    }

    fn run_due_actions(&mut self, cx: &mut Context<Self>) {
        let view = self.tracker.view(now_ms());
        let shown = view
            .as_ref()
            .map(|v| (v.call_id.as_str(), v.is_ringing_in()));
        let Some((call_id, action)) = self.once.due(shown, now_ms()) else {
            return;
        };
        match action {
            WaCallAction::Answer => {
                self.answer(cx);
            }
            // A stale Decline must not end a call that is already answered.
            WaCallAction::Decline if shown.is_some_and(|(id, _)| id == call_id) => {
                if shown.is_some_and(|(_, ringing)| ringing) {
                    self.hang_up(true, cx);
                }
            }
            WaCallAction::Decline => {
                // The app has not seen this call (cold start): reject it by id.
                let Some(gateway) = self.settings.read(cx).gateway() else {
                    return;
                };
                self.cancel_notice(&call_id, cx);
                let task = cx
                    .background_executor()
                    .spawn(async move { gateway.reject_call(&call_id) });
                cx.spawn(async move |this, cx| {
                    if let Err(error) = task.await {
                        this.update(cx, |this, cx| {
                            this.fail("The gateway did not decline the call", error, cx);
                        })
                        .ok();
                    }
                })
                .detach();
            }
        }
    }

    /// Redraws once a second while the call is on screen, for the timer.
    fn start_ticking(&mut self, cx: &mut Context<Self>) {
        self._clear = None;
        if self._tick.is_some() {
            return;
        }
        self._tick = Some(cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(Duration::from_secs(1)).await;
                if this.update(cx, |_, cx| cx.notify()).is_err() {
                    break;
                }
            }
        }));
    }

    /// Stops the audio: the pump, then Kotlin's microphone and speaker.
    fn finish(&mut self, cx: &mut Context<Self>) {
        self.answering = false;
        self.placing = false;
        self.speaker = false;
        if self.session.take().is_some() {
            cx.background_executor()
                .spawn(async {
                    if let Err(error) = rovsti_android::wa::call_audio_stop() {
                        log::debug!("stopping call audio: {error}");
                    }
                })
                .detach();
        }
    }

    /// Logs the cause; the user sees `what` only (never the technical error).
    fn fail(&mut self, what: &'static str, error: impl std::fmt::Display, cx: &mut Context<Self>) {
        log::warn!("{what}: {error}");
        self.error = Some(what);
        cx.notify();
    }

    /// Answers the ringing call. `false` when it cannot even start (no ringing call,
    /// WhatsApp not connected); a later failure shows up in [`Self::take_error`].
    pub(crate) fn answer(&mut self, cx: &mut Context<Self>) -> bool {
        let Some(view) = self.tracker.view(now_ms()) else {
            return false;
        };
        if !view.is_ringing_in() {
            return false;
        }
        if self.answering {
            return true;
        }
        let settings = self.settings.read(cx);
        let (Some(gateway), Some(link)) = (settings.gateway(), settings.link().cloned()) else {
            self.fail("WhatsApp is not connected", "no gateway link", cx);
            return false;
        };
        self.answering = true;
        self.error = None;
        let call_id = view.call_id;
        self.cancel_notice(&call_id, cx);
        let task = cx.background_executor().spawn(async move {
            gateway
                .accept_call(&call_id)
                .map_err(|e| ("Could not answer the call", e))?;
            open_call_audio(&link, &call_id).map_err(|e| ("Could not open the call audio", e))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.answering = false;
                match result {
                    Ok(audio) => this.attach_audio(audio, cx),
                    Err((what, error)) => this.fail(what, error, cx),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
        true
    }

    /// Starts the pump for an opened call audio socket.
    fn attach_audio(&mut self, audio: CallAudio, cx: &mut Context<Self>) {
        // The call may have ended while the audio was opening.
        if self.tracker.is_empty() {
            return;
        }
        let muted = Arc::new(AtomicBool::new(
            self.tracker.view(now_ms()).is_some_and(|v| v.muted),
        ));
        let executor = cx.background_executor().clone();
        let timers = executor.clone();
        let pump = executor.spawn(pump(audio, muted.clone(), move |d| timers.timer(d)));
        self.session = Some(Session { muted, _pump: pump });
        if self.speaker {
            // Starting the audio may have reset the route: set it again.
            cx.background_executor()
                .spawn(async {
                    if let Err(error) = rovsti_android::wa::call_audio_speaker(true) {
                        log::debug!("speaker: {error}");
                    }
                })
                .detach();
        }
    }

    /// Starts an outgoing WhatsApp call to `number`. `Err` is the reason it
    /// cannot start at all; a failure after that shows up in [`Self::take_error`].
    pub fn place(&mut self, number: &str, cx: &mut Context<Self>) -> Result<(), &'static str> {
        let busy = !self.tracker.is_empty() || self.placing || self.answering;
        let connected = self.settings.read(cx).is_connected();
        can_place(connected, busy, number)?;
        let settings = self.settings.read(cx);
        let (Some(gateway), Some(link)) = (settings.gateway(), settings.link().cloned()) else {
            return Err("WhatsApp is not connected");
        };
        self.placing = true;
        self.error = None;
        let number = number.to_owned();
        let place = cx.background_executor().spawn(async move {
            let jid = rovsti_wa_proto::user_jid(&number).map_err(|e| e.to_string())?;
            let started = gateway.place_call(&jid).map_err(|e| e.to_string())?;
            Ok::<_, String>((jid, started.call_id, gateway))
        });
        cx.spawn(async move |this, cx| {
            let placed = place.await;
            let Ok(Some((call_id, gateway, link))) = this.update(cx, |this, cx| {
                this.placing = false;
                match placed {
                    Ok((jid, call_id, gateway)) => {
                        match this.tracker.on_placed(&call_id, &jid, now_ms()) {
                            Change::Ignored => {
                                // The gateway's own update may already show this call.
                                let shown = this.tracker.view(now_ms());
                                if shown.is_some_and(|v| v.call_id == call_id) {
                                    cx.notify();
                                    return Some((call_id, gateway, link));
                                }
                                // Something else took the screen: do not leave a ghost call.
                                cx.background_executor()
                                    .spawn(async move {
                                        if let Err(error) = gateway.end_call(&call_id) {
                                            log::debug!("ending a surplus call: {error}");
                                        }
                                    })
                                    .detach();
                                None
                            }
                            _ => {
                                this.start_ticking(cx);
                                cx.notify();
                                Some((call_id, gateway, link))
                            }
                        }
                    }
                    Err(error) => {
                        this.fail("Could not place the WhatsApp call", error, cx);
                        None
                    }
                }
            }) else {
                return;
            };
            drop(gateway);
            let audio = cx
                .background_executor()
                .spawn(async move { open_call_audio(&link, &call_id) })
                .await;
            this.update(cx, |this, cx| {
                match audio {
                    Ok(audio) => this.attach_audio(audio, cx),
                    Err(error) => this.fail("Could not open the call audio", error, cx),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
        Ok(())
    }

    /// The last failure, once: the app shows it as a notice.
    pub fn take_error(&mut self) -> Option<&'static str> {
        self.error.take()
    }

    /// Decline, or End: the gateway answers with a phase update. `false` when it
    /// cannot even be asked (no call, WhatsApp not connected).
    pub(crate) fn hang_up(&mut self, decline: bool, cx: &mut Context<Self>) -> bool {
        let Some(view) = self.tracker.view(now_ms()) else {
            return false;
        };
        let Some(gateway) = self.settings.read(cx).gateway() else {
            self.fail("WhatsApp is not connected", "no gateway link", cx);
            return false;
        };
        self.finish(cx);
        let call_id = view.call_id;
        self.cancel_notice(&call_id, cx);
        let task = cx.background_executor().spawn(async move {
            if decline {
                gateway.reject_call(&call_id)
            } else {
                gateway.end_call(&call_id)
            }
        });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                this.update(cx, |this, cx| {
                    let what = if decline {
                        "Could not decline the WhatsApp call"
                    } else {
                        "Could not end the WhatsApp call"
                    };
                    this.fail(what, error, cx);
                })
                .ok();
            }
        })
        .detach();
        true
    }

    pub(crate) fn toggle_mute(&mut self, cx: &mut Context<Self>) {
        let Some(view) = self.tracker.view(now_ms()) else {
            return;
        };
        let Some(gateway) = self.settings.read(cx).gateway() else {
            return;
        };
        let muted = !view.muted;
        self.tracker.set_muted(muted);
        if let Some(session) = &self.session {
            session.muted.store(muted, Ordering::Relaxed);
        }
        let call_id = view.call_id;
        let task = cx
            .background_executor()
            .spawn(async move { gateway.set_muted(&call_id, muted) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                this.update(cx, |this, cx| {
                    this.fail("Could not change the microphone", error, cx);
                })
                .ok();
            }
        })
        .detach();
        cx.notify();
    }

    // ---- views ---------------------------------------------------------------

    fn button(
        theme: Theme,
        id: &'static str,
        label: &'static str,
        fill: gpui::Hsla,
        cx: &mut Context<Self>,
        tap: impl Fn(&mut Self, &mut Context<Self>) + 'static,
    ) -> AnyElement {
        let white = theme
            .is_dark()
            .then(gpui::black)
            .unwrap_or_else(gpui::white);
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(label)
            .flex_1()
            .h(px(48.0))
            .rounded_full()
            .bg(fill)
            .flex()
            .items_center()
            .justify_center()
            .text_size(typography::HEADLINE.size)
            .font_weight(typography::HEADLINE.weight)
            .text_color(white)
            .child(label)
            .on_click(cx.listener(move |this, _, _, cx| tap(this, cx)))
            .into_any_element()
    }
}

impl Render for WaCallUi {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let Some(view) = self.tracker.view(now_ms()).filter(|_| !self.incall_mounted) else {
            return div().into_any_element();
        };
        let theme = current_theme(window);
        let palette = colors(theme);
        let calls = call_colors(theme);
        let neutral = rovsti_ui::tokens::mix(palette.label, palette.background, 0.78);

        let buttons = if view.is_ringing_in() {
            vec![
                Self::button(
                    theme,
                    "wa-decline",
                    "Decline",
                    calls.danger,
                    cx,
                    |this, cx| {
                        this.hang_up(true, cx);
                    },
                ),
                Self::button(
                    theme,
                    "wa-answer",
                    if self.answering {
                        "Answering…"
                    } else {
                        "Answer"
                    },
                    calls.answer,
                    cx,
                    |this, cx| {
                        this.answer(cx);
                    },
                ),
            ]
        } else if view.phase.is_ended() {
            Vec::new()
        } else {
            vec![
                Self::button(
                    theme,
                    "wa-mute",
                    if view.muted { "Unmute" } else { "Mute" },
                    neutral,
                    cx,
                    Self::toggle_mute,
                ),
                Self::button(theme, "wa-end", "End", calls.danger, cx, |this, cx| {
                    this.hang_up(false, cx);
                }),
            ]
        };

        let mut card = flat_glass(theme, None)
            .rounded(px(28.0))
            .p(px(16.0))
            .flex()
            .flex_col()
            .gap(px(12.0))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(2.0))
                    .child(
                        div()
                            .text_size(typography::TITLE3.size)
                            .font_weight(typography::TITLE3.weight)
                            .text_color(palette.label)
                            .child(SharedString::from(view.peer_label.clone())),
                    )
                    .child(
                        div()
                            .text_size(typography::SUBHEADLINE.size)
                            .text_color(palette.secondary_label)
                            .child(SharedString::from(status_line(&view))),
                    ),
            );
        if let Some(error) = &self.error {
            card = card.child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(calls.warning)
                    .child(*error),
            );
        }
        if !buttons.is_empty() {
            card = card.child(div().flex().gap(px(12.0)).children(buttons));
        }
        // Absolute and only as big as the card: nothing else in Phone loses touches.
        div()
            .id("wa-call-card")
            .absolute()
            .top(px(56.0))
            .left(px(12.0))
            .right(px(12.0))
            .child(card)
            .into_any_element()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use CallDirection::{In, Out};

    #[test]
    fn a_speaker_request_counts_unless_android_refuses_it() {
        assert!(speaker_applied(&Ok(true)));
        assert!(speaker_applied(&Err(AndroidError::Unsupported)));
        assert!(speaker_applied(&Err(AndroidError::NotInitialized)));
        assert!(!speaker_applied(&Ok(false)));
    }

    fn update(id: &str, direction: CallDirection, phase: CallPhase) -> CallUpdate {
        CallUpdate {
            call_id: id.into(),
            peer: "905551234567@s.whatsapp.net".into(),
            peer_name: Some("Ayşe".into()),
            direction,
            phase,
            is_video: false,
        }
    }

    #[test]
    fn a_ringing_call_is_shown_and_answerable() {
        let mut t = Tracker::default();
        assert_eq!(
            t.on_update(update("a", In, CallPhase::Ringing), 0),
            Change::Shown
        );
        let view = t.view(0).unwrap();
        assert!(view.is_ringing_in());
        assert_eq!(view.peer_label, "Ayşe");
        assert_eq!(status_line(&view), "WhatsApp call");
    }

    #[test]
    fn a_second_call_and_stale_updates_are_ignored() {
        let mut t = Tracker::default();
        assert_eq!(
            t.on_update(update("old", In, CallPhase::Ended(EndReason::Missed)), 0),
            Change::Ignored
        );
        assert!(t.is_empty());
        t.on_update(update("a", In, CallPhase::Ringing), 0);
        assert_eq!(
            t.on_update(update("b", In, CallPhase::Ringing), 1),
            Change::Ignored
        );
        assert_eq!(t.view(1).unwrap().call_id, "a");
    }

    #[test]
    fn the_timer_starts_when_the_call_becomes_active() {
        let mut t = Tracker::default();
        t.on_update(update("a", In, CallPhase::Ringing), 0);
        t.on_update(update("a", In, CallPhase::Connecting), 1_000);
        assert_eq!(t.view(2_000).unwrap().elapsed_s, 0);
        t.on_update(update("a", In, CallPhase::Active), 3_000);
        t.on_update(update("a", In, CallPhase::Active), 4_000);
        let view = t.view(68_000).unwrap();
        assert_eq!(view.elapsed_s, 65);
        assert_eq!(status_line(&view), "1:05");
    }

    #[test]
    fn a_placed_call_shows_at_once_and_keeps_one_card() {
        let mut t = Tracker::default();
        assert_eq!(
            t.on_placed("a", "905551234567@s.whatsapp.net", 0),
            Change::Shown
        );
        let view = t.view(0).unwrap();
        assert_eq!(view.direction, Out);
        assert_eq!(status_line(&view), "Calling…");
        assert_eq!(
            t.on_update(update("a", Out, CallPhase::Active), 2_000),
            Change::Shown
        );
        assert_eq!(t.view(5_000).unwrap().elapsed_s, 3);
        assert_eq!(t.view(5_000).unwrap().call_id, "a");
    }

    #[test]
    fn a_second_placed_call_while_busy_is_ignored() {
        let mut t = Tracker::default();
        t.on_placed("a", "x@s.whatsapp.net", 0);
        assert_eq!(t.on_placed("b", "y@s.whatsapp.net", 1), Change::Ignored);
        assert_eq!(t.view(1).unwrap().call_id, "a");
        let mut incoming = Tracker::default();
        incoming.on_update(update("i", In, CallPhase::Ringing), 0);
        assert_eq!(
            incoming.on_placed("b", "y@s.whatsapp.net", 1),
            Change::Ignored
        );
    }

    #[test]
    fn can_place_gives_a_reason() {
        assert_eq!(can_place(true, false, "+905551234567"), Ok(()));
        assert_eq!(can_place(true, false, "  "), Err("No number to call"));
        assert_eq!(
            can_place(false, false, "1"),
            Err("WhatsApp is not connected")
        );
        assert_eq!(
            can_place(true, true, "1"),
            Err("Another call is in progress")
        );
    }

    #[test]
    fn mute_is_kept_and_ending_is_reported() {
        let mut t = Tracker::default();
        t.on_update(update("a", Out, CallPhase::Active), 0);
        t.set_muted(true);
        assert!(t.view(0).unwrap().muted);
        let ended = update("a", Out, CallPhase::Ended(EndReason::Remote));
        assert_eq!(t.on_update(ended, 5), Change::Ended);
        assert_eq!(status_line(&t.view(5).unwrap()), "Call ended");
        t.clear();
        assert!(t.view(5).is_none());
    }

    #[test]
    fn elapsed_formats() {
        assert_eq!(format_elapsed(0), "0:00");
        assert_eq!(format_elapsed(59), "0:59");
        assert_eq!(format_elapsed(600), "10:00");
        assert_eq!(format_elapsed(3_725), "1:02:05");
    }

    #[test]
    fn status_lines_cover_the_phases() {
        let line = |dir, phase| status_line(&WaCallView::from_update(&update("a", dir, phase), 0));
        assert_eq!(line(Out, CallPhase::Ringing), "Calling…");
        assert_eq!(line(In, CallPhase::Connecting), "Connecting…");
        assert_eq!(line(In, CallPhase::Ended(EndReason::Rejected)), "Declined");
        assert_eq!(line(In, CallPhase::Ended(EndReason::Missed)), "Missed call");
        assert_eq!(
            line(In, CallPhase::Ended(EndReason::ElsewhereAnswered)),
            "Answered on another device"
        );
        assert_eq!(
            line(In, CallPhase::Ended(EndReason::Failed("x".into()))),
            "Call failed"
        );
    }

    #[test]
    fn mic_steps() {
        assert_eq!(mic_step(Ok(Some(vec![1, 2]))), MicStep::Frame(vec![1, 2]));
        assert_eq!(mic_step(Ok(None)), MicStep::Idle);
        assert_eq!(
            mic_step(Err(AndroidError::Unsupported)),
            MicStep::Unavailable
        );
        assert_eq!(
            mic_step(Err(AndroidError::NotInitialized)),
            MicStep::Unavailable
        );
        let bad = rovsti_wa_proto::audio::FrameError::BadBase64;
        assert_eq!(mic_step(Err(AndroidError::Frame(bad))), MicStep::Idle);
    }

    #[test]
    fn the_speaker_stays_on_unless_the_platform_has_none() {
        assert!(play_available(&Ok(true)));
        assert!(play_available(&Ok(false)));
        assert!(!play_available(&Err(AndroidError::Unsupported)));
    }
    #[test]
    fn an_action_runs_once_per_call() {
        let mut once = AnswerOnce::default();
        assert!(once.offer("a", WaCallAction::Answer, 0));
        // The same intent seen again (onCreate, then onNewIntent) is dropped.
        assert!(!once.offer("a", WaCallAction::Answer, 1));
        assert_eq!(
            once.due(Some(("a", true)), 2),
            Some(("a".into(), WaCallAction::Answer))
        );
        assert!(!once.offer("a", WaCallAction::Answer, 3));
        assert!(!once.offer("a", WaCallAction::Decline, 3));
        assert_eq!(once.due(Some(("a", true)), 4), None);
    }

    #[test]
    fn an_answer_waits_for_its_call_to_ring() {
        let mut once = AnswerOnce::default();
        once.offer("a", WaCallAction::Answer, 0);
        assert_eq!(once.due(None, 1), None);
        assert_eq!(once.due(Some(("other", true)), 2), None);
        assert_eq!(once.due(Some(("a", false)), 3), None);
        assert_eq!(
            once.due(Some(("a", true)), 4),
            Some(("a".into(), WaCallAction::Answer))
        );
    }

    #[test]
    fn a_decline_needs_no_call_on_screen() {
        let mut once = AnswerOnce::default();
        once.offer("a", WaCallAction::Decline, 0);
        assert_eq!(once.due(None, 1), Some(("a".into(), WaCallAction::Decline)));
    }

    #[test]
    fn a_stale_or_moot_answer_is_dropped() {
        let mut once = AnswerOnce::default();
        once.offer("a", WaCallAction::Answer, 0);
        assert_eq!(once.due(Some(("a", true)), ACTION_TTL_MS + 1), None);
        // Expired: nothing is left, and the call may be offered again.
        assert!(once.offer("a", WaCallAction::Answer, ACTION_TTL_MS + 2));
        once.call_ended("a");
        assert_eq!(once.due(Some(("a", true)), ACTION_TTL_MS + 3), None);
    }

    #[test]
    fn the_guard_forgets_old_calls() {
        let mut once = AnswerOnce::default();
        for n in 0..=ONCE_MEMORY {
            let id = format!("c{n}");
            once.offer(&id, WaCallAction::Decline, 0);
            once.due(None, 0);
        }
        assert!(once.offer("c0", WaCallAction::Decline, 0));
        assert!(!once.offer(&format!("c{ONCE_MEMORY}"), WaCallAction::Decline, 0));
    }

    fn summary(
        id: &str,
        direction: CallDirection,
        phase: CallPhase,
        started_ms: i64,
    ) -> CallSummary {
        let ended = phase.is_ended();
        CallSummary {
            update: update(id, direction, phase),
            started_ms,
            ended_ms: ended.then_some(started_ms + 5_000),
        }
    }

    #[test]
    fn an_answer_queued_before_the_snapshot_runs_when_it_shows_the_call() {
        let mut once = AnswerOnce::default();
        let mut tracker = Tracker::default();
        once.offer("a", WaCallAction::Answer, 0);
        assert_eq!(once.due(None, 1), None);
        let list = [summary("a", In, CallPhase::Ringing, 1)];
        let plan = plan_snapshot(!tracker.is_empty(), &list, &WaCallHistory::new());
        assert_eq!(tracker.on_update(plan.show.unwrap(), 2), Change::Shown);
        let view = tracker.view(2).unwrap();
        let shown = Some((view.call_id.as_str(), view.is_ringing_in()));
        assert_eq!(once.due(shown, 2), Some(("a".into(), WaCallAction::Answer)));
        assert!(once.handled("a"));
    }

    #[test]
    fn dial_numbers_come_from_phone_jids_only() {
        assert_eq!(
            dial_number("905551234567@s.whatsapp.net").as_deref(),
            Some("+905551234567")
        );
        assert_eq!(
            dial_number("905551234567:12@s.whatsapp.net").as_deref(),
            Some("+905551234567")
        );
        assert_eq!(dial_number("123@lid"), None);
        assert_eq!(dial_number("abc@s.whatsapp.net"), None);
        assert_eq!(dial_number("905551234567"), None);
    }

    #[test]
    fn handoff_numbers_need_a_call_to_a_phone_jid() {
        let handoff = |action, jid: &str| Handoff {
            action,
            jid: jid.into(),
            name: Some("Ayse".into()),
        };
        assert_eq!(
            handoff_number(&handoff(
                HandoffAction::WaCall,
                "905551234567@s.whatsapp.net"
            ))
            .as_deref(),
            Some("+905551234567")
        );
        assert_eq!(
            handoff_number(&handoff(
                HandoffAction::WaChat,
                "905551234567@s.whatsapp.net"
            )),
            None
        );
        assert_eq!(
            handoff_number(&handoff(
                HandoffAction::Unknown,
                "905551234567@s.whatsapp.net"
            )),
            None
        );
        assert_eq!(
            handoff_number(&handoff(HandoffAction::WaCall, "12@lid")),
            None
        );
    }

    #[test]
    fn history_becomes_recents_extras() {
        let mut history = WaCallHistory::new();
        let ended = |id: &str, peer: &str, name: Option<&str>, phase| {
            let mut u = update(id, In, phase);
            u.peer = peer.into();
            u.peer_name = name.map(str::to_owned);
            WaCallView::from_update(&u, 0)
        };
        history.record_end(
            &ended(
                "a",
                "905551234567@s.whatsapp.net",
                Some("Ayşe"),
                CallPhase::Ended(EndReason::Missed),
            ),
            10,
            &EndReason::Missed,
        );
        history.record_end(
            &ended(
                "b",
                "905550000000@s.whatsapp.net",
                None,
                CallPhase::Ended(EndReason::Rejected),
            ),
            20,
            &EndReason::Rejected,
        );
        history.record_end(
            &ended("c", "9@lid", None, CallPhase::Ended(EndReason::Missed)),
            30,
            &EndReason::Missed,
        );
        let extras = extra_calls(&history);
        assert_eq!(extras.len(), 2, "the @lid peer has no number");
        assert_eq!(extras[0].number_raw, "+905550000000");
        assert_eq!(extras[0].label, None);
        assert_eq!(extras[1].label.as_deref(), Some("Ayşe"));
        assert!(extras[1].missed);
    }
}
