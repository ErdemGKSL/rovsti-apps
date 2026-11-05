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
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use futures::{FutureExt, StreamExt, channel::mpsc::TrySendError, select_biased};
use gpui::{
    AnyElement, Context, Entity, InteractiveElement, IntoElement, ParentElement, Render, Role,
    SharedString, StatefulInteractiveElement, Styled, Task, Window, div, px,
};
use rovsti_android::Error as AndroidError;
use rovsti_ui::{
    components::flat_glass,
    current_theme,
    theme::{call_colors, colors},
    tokens::{Theme, typography},
};
use rovsti_wa_client::{CallAudio, ClientError};
use rovsti_wa_proto::{
    CallDirection, CallPhase, CallUpdate, ConnectKey, EndReason, GatewayEvent, GatewayUrl,
    WaCallView,
};
use rovsti_wa_ui::{StoredLink, WhatsAppSettings};

/// An ended call stays on screen this long, so "Call ended" can be read.
const ENDED_HOLD: Duration = Duration::from_millis(2000);
/// How often the pump asks Kotlin for microphone frames (a frame is 60 ms).
const MIC_POLL: Duration = Duration::from_millis(20);
/// Frames moved per poll at most, so a stalled consumer cannot spin the pump.
const MIC_BURST: usize = 8;

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

    pub fn view(&self, now_ms: i64) -> Option<WaCallView> {
        self.current.as_ref().map(|t| {
            WaCallView::from_update(&t.update, now_ms)
                .with_muted(t.muted)
                .with_active_since(t.active_since_ms, now_ms)
        })
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

// ---- the entity ------------------------------------------------------------------

pub struct WaCallUi {
    settings: Entity<WhatsAppSettings>,
    tracker: Tracker,
    session: Option<Session>,
    /// Answer was pressed and the audio is being set up.
    answering: bool,
    error: Option<SharedString>,
    _tick: Option<Task<()>>,
    _clear: Option<Task<()>>,
}

impl WaCallUi {
    pub fn new(settings: Entity<WhatsAppSettings>, cx: &mut Context<Self>) -> Self {
        cx.observe(&settings, |this, settings, cx| {
            settings.update(cx, |settings, cx| settings.register_push(cx));
            let events = settings.update(cx, |settings, _| settings.take_events());
            let linked = settings.read(cx).link().is_some();
            this.on_events(events, linked, cx);
        })
        .detach();
        Self {
            settings,
            tracker: Tracker::default(),
            session: None,
            answering: false,
            error: None,
            _tick: None,
            _clear: None,
        }
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
            Change::Shown => self.start_ticking(cx),
            Change::Ended => {
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

    fn fail(&mut self, what: &str, error: impl std::fmt::Display, cx: &mut Context<Self>) {
        log::warn!("{what}: {error}");
        self.error = Some(format!("{what}.").into());
        cx.notify();
    }

    fn answer(&mut self, cx: &mut Context<Self>) {
        let Some(view) = self.tracker.view(now_ms()) else {
            return;
        };
        if !view.is_ringing_in() || self.answering {
            return;
        }
        let settings = self.settings.read(cx);
        let (Some(gateway), Some(link)) = (settings.gateway(), settings.link().cloned()) else {
            return;
        };
        self.answering = true;
        self.error = None;
        let call_id = view.call_id;
        let task = cx.background_executor().spawn(async move {
            gateway.accept_call(&call_id)?;
            let (url, key) = parse_link(&link)?;
            let audio = rovsti_wa_client::call_audio(url, key, &call_id)?;
            match rovsti_android::wa::call_audio_start() {
                Ok(true) => {}
                Ok(false) => log::warn!("call audio did not start: is RECORD_AUDIO granted?"),
                Err(error) => log::debug!("no call audio on this platform: {error}"),
            }
            Ok::<_, ClientError>(audio)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.answering = false;
                match result {
                    Ok(audio) => {
                        // The call may have ended while the audio was opening.
                        if this.tracker.is_empty() {
                            return;
                        }
                        let muted = Arc::new(AtomicBool::new(
                            this.tracker.view(now_ms()).is_some_and(|v| v.muted),
                        ));
                        let executor = cx.background_executor().clone();
                        let timers = executor.clone();
                        let pump =
                            executor.spawn(pump(audio, muted.clone(), move |d| timers.timer(d)));
                        this.session = Some(Session { muted, _pump: pump });
                    }
                    Err(error) => this.fail("Could not answer the call", error, cx),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Decline, or End: the gateway answers with a phase update.
    fn hang_up(&mut self, decline: bool, cx: &mut Context<Self>) {
        let Some(view) = self.tracker.view(now_ms()) else {
            return;
        };
        let Some(gateway) = self.settings.read(cx).gateway() else {
            return;
        };
        self.finish(cx);
        let call_id = view.call_id;
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
                    this.fail("The gateway did not end the call", error, cx);
                })
                .ok();
            }
        })
        .detach();
    }

    fn toggle_mute(&mut self, cx: &mut Context<Self>) {
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
        let Some(view) = self.tracker.view(now_ms()) else {
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
                    Self::answer,
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
                    .child(error.clone()),
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
}
