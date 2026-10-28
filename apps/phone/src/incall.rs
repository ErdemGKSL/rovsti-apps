//! The In-Call screen (stories 4.5 to 4.8): who you are talking to, how long,
//! Mute / Keypad / Speaker / Hold, the touch-tone keypad and End.
//!
//! Displayed state is always what Android last reported (the snapshot); a tap
//! only sends a command and shows a short pending press. The call timer and
//! the aura are their own views, so a once-a-second timer tick or a drifting
//! blob never re-renders this screen.

use std::{
    rc::Rc,
    time::{Duration, Instant},
};

use gpui::{
    AnyElement, Context, Entity, HapticFeedback, Hsla, InteractiveElement, IntoElement,
    MouseButton, ParentElement, Render, Role, SharedString, StatefulInteractiveElement, Styled,
    Task, Window, div, linear_color_stop, linear_gradient, prelude::*, px, svg,
};
use rovsti_core::{
    avatar,
    call::{CallState, caller_label, format_number, status_text},
    scene::{CallScene, DisabledReason},
    wire::{AudioRoute, DisconnectCause, PendingOp},
};
use rovsti_ui::{
    Icon, current_theme,
    glass::{GlassInteraction, GlassSpec, glass_interactive},
    motion::{Entrance, Motion, press_scale},
    motion_tokens::{self, scale},
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, mix, typography},
    touch::{pointer_surface, touch_surface},
};

use crate::{
    PhoneApp,
    aura::{Aura, AuraState, Phase},
    backend::{self, CallCommand, now_ms},
    fx::{A_AUDIO, A_AVATAR, A_END},
    zone::{self, Face, Moment, Slot, ZoneState, slots},
};

/// "Call ended" holds this long; a failed call holds longer so it can be read.
/// An answered incoming call shorter than this offers "Block" when it ends (story 17.4).
const SHORT_CALL_MS: i64 = 15_000;
const ENDED_HOLD: Duration = Duration::from_millis(1000);
const FAILED_HOLD: Duration = Duration::from_millis(2000);
const DIGIT_LINE: usize = 24;
/// The in-call digit buffer keeps only the latest keys.
const DIGIT_BUFFER: usize = 64;

/// Appends a key, dropping the oldest ones past [`DIGIT_BUFFER`]. Keys are ASCII.
fn push_digit(buf: &mut String, key: char) {
    buf.push(key);
    if buf.len() > DIGIT_BUFFER {
        let extra = buf.len() - DIGIT_BUFFER;
        buf.drain(..extra);
    }
}

/// The keypad's digit line: the whole buffer, or `…` and its last keys.
fn digit_line(digits: &str) -> String {
    if digits.len() > DIGIT_LINE {
        format!("…{}", &digits[digits.len() - (DIGIT_LINE - 1)..])
    } else {
        digits.to_owned()
    }
}
const CONTROL: f32 = slots::CONTROL;
/// The control circle, for the route popover and S17.
pub(crate) const CONTROL_SIZE: f32 = CONTROL;
const IN_KEY: f32 = 72.0;
/// A pending look (Hold, Swap, Merge, Mute, Audio) holds this long, then reverts
/// if the snapshot did not confirm it.
const PENDING_HOLD: Duration = Duration::from_millis(1000);
/// Long-press on a dimmed control shows its reason after this long.
pub(crate) const REASON_AFTER_MS: u64 = 450;

// ---- The call as the screen draws it ------------------------------------

/// Everything the screen needs about the foreground call. Built only when a
/// snapshot arrives, so rendering never formats or allocates a name.
#[derive(Clone, Debug, PartialEq)]
pub struct CallView {
    pub id: i32,
    pub state: CallState,
    /// The call came in (rang) rather than being placed here.
    pub incoming: bool,
    pub name: SharedString,
    /// The number as Telecom gave it (empty for a private caller); never drawn as is.
    pub number: SharedString,
    pub secondary: Option<SharedString>,
    pub monogram: Option<SharedString>,
    pub hue: Option<usize>,
    pub can_hold: bool,
    pub connect_time_ms: i64,
    pub muted: bool,
    pub route: AudioRoute,
}

/// The second caller of a waiting call (stories 9.2, 15.8) and what the card may offer.
#[derive(Clone, Debug, PartialEq)]
pub struct WaitingView {
    pub id: i32,
    pub name: SharedString,
    pub monogram: Option<SharedString>,
    pub hue: Option<usize>,
    /// The name "End and answer" ends ("Ends Ayşe").
    pub ends: SharedString,
    /// An Active, a Held and a Ringing call: the primary pill ends the held call.
    pub relabelled: bool,
    /// Why "Hold and answer" and "End and answer" are dimmed (a running operation, an
    /// emergency call), or why Hold-and-answer alone is (the current call cannot be held).
    pub hold_reason: Option<DisabledReason>,
    pub end_reason: Option<DisabledReason>,
}

impl WaitingView {
    pub(crate) fn from_scene(scene: &CallScene<'_>) -> Option<Self> {
        use rovsti_core::scene::Control;
        let call = scene.waiting?;
        let label = caller_label(call.display_name.as_deref(), &call.number_raw, false);
        let ends = scene.primary().map_or_else(String::new, |p| {
            if p.has_prop(rovsti_core::wire::props::CONFERENCE) || !p.children.is_empty() {
                "the conference".to_owned()
            } else {
                middle_truncate(
                    &caller_label(p.display_name.as_deref(), &p.number_raw, false),
                    16,
                )
            }
        });
        let hold_reason = scene.reason(Control::HoldAndAnswer);
        // Ending a call is blocked only while something else is running or for an emergency call.
        let end_reason = hold_reason
            .filter(|r| matches!(r, DisabledReason::Emergency | DisabledReason::Pending));
        Some(Self {
            id: call.id,
            name: middle_truncate(&label, 22).into(),
            monogram: call
                .display_name
                .as_deref()
                .and_then(avatar::monogram)
                .map(|c| SharedString::from(c.to_string())),
            hue: avatar::avatar_hue(
                call.contact_id
                    .map_or(avatar::AvatarKey::Unknown, avatar::AvatarKey::Contact),
            ),
            ends: ends.into(),
            relabelled: scene.held().is_some(),
            hold_reason,
            end_reason,
        })
    }
}

fn middle_truncate(text: &str, max: usize) -> String {
    let count = text.chars().count();
    if count <= max {
        return text.to_owned();
    }
    let keep = (max - 1) / 2;
    let head: String = text.chars().take(keep).collect();
    let tail: String = text.chars().skip(count - keep).collect();
    format!("{head}…{tail}")
}

impl CallView {
    pub(crate) fn from_model(model: &rovsti_core::call::CallModel) -> Option<Self> {
        let call = model.foreground()?;
        let audio = model.audio();
        let conference =
            call.has_prop(rovsti_core::wire::props::CONFERENCE) || !call.children.is_empty();
        let label = if conference {
            std::borrow::Cow::Borrowed("Conference")
        } else {
            caller_label(call.display_name.as_deref(), &call.number_raw, false)
        };
        let secondary = call
            .display_name
            .as_deref()
            .filter(|n| !n.trim().is_empty())
            .and_then(|_| rovsti_core::PhoneNumber::parse(&call.number_raw).ok())
            .map(|n| SharedString::from(middle_truncate(&format_number(&n), 28)));
        Some(Self {
            id: call.id,
            state: call.call_state(),
            incoming: call.is_incoming,
            name: middle_truncate(&label, 22).into(),
            number: call.number_raw.clone().into(),
            secondary,
            monogram: call
                .display_name
                .as_deref()
                .filter(|_| !conference)
                .and_then(avatar::monogram)
                .map(|c| SharedString::from(c.to_string())),
            hue: avatar::avatar_hue(
                call.contact_id
                    .filter(|_| !conference)
                    .map_or(avatar::AvatarKey::Unknown, avatar::AvatarKey::Contact),
            ),
            can_hold: call.can_hold,
            connect_time_ms: call.connect_time_ms,
            muted: audio.muted,
            route: audio.route,
        })
    }

    pub(crate) fn phase(&self) -> Phase {
        match self.state {
            CallState::Active => Phase::Active,
            CallState::Holding => Phase::Holding,
            CallState::Disconnected { cause } => {
                if is_failure(cause) {
                    Phase::Failed
                } else {
                    Phase::Ended
                }
            }
            _ => Phase::Dialing,
        }
    }
}

fn is_failure(cause: DisconnectCause) -> bool {
    matches!(
        cause,
        DisconnectCause::Busy
            | DisconnectCause::OutOfService
            | DisconnectCause::Unreachable
            | DisconnectCause::Error
    )
}

// ---- The status line: "Calling...", the timer, "On hold" ----------------

/// Which kind of text the status line shows; a change slides the text.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Headline {
    Calling,
    Timer,
    Hold,
    Over,
    Blank,
}

/// The status text under the name. Owns the once-a-second timer tick and the
/// "timer lands" slide (S9), so neither touches the parent screen.
pub struct CallStatus {
    state: CallState,
    connect_time_ms: i64,
    kind: Headline,
    /// Text that is sliding out, with the time it was captured.
    leaving: Option<SharedString>,
    swap: Spring,
    motion: Motion,
    tick_for: Option<i64>,
    task: Option<Task<()>>,
}

impl CallStatus {
    fn new() -> Self {
        Self {
            state: CallState::Dialing,
            connect_time_ms: 0,
            kind: Headline::Calling,
            leaving: None,
            swap: Spring::new(1.0, motion_tokens::appear()).with_epsilon(0.002),
            motion: Motion::default(),
            tick_for: None,
            task: None,
        }
    }

    fn kind_of(state: CallState) -> Headline {
        match state {
            CallState::Dialing | CallState::Ringing => Headline::Calling,
            CallState::Active => Headline::Timer,
            CallState::Holding => Headline::Hold,
            CallState::Disconnected { .. } => Headline::Over,
            CallState::Unknown => Headline::Blank,
        }
    }

    fn headline_now(&self) -> (SharedString, Option<&'static str>) {
        let text = status_text(self.state, now_ms(), self.connect_time_ms);
        (text.headline().to_owned().into(), text.reason())
    }

    /// `ticking`: the screen is visible, so the timer may run.
    fn update(
        &mut self,
        state: CallState,
        connect_time_ms: i64,
        ticking: bool,
        cx: &mut Context<Self>,
    ) {
        let kind = Self::kind_of(state);
        if kind != self.kind && !matches!(kind, Headline::Blank) {
            self.leaving = Some(self.headline_now().0);
            self.swap.snap(0.0);
            self.swap.set_target(1.0);
        }
        self.state = state;
        self.connect_time_ms = connect_time_ms;
        self.kind = kind;
        let want = (ticking && kind == Headline::Timer).then_some(connect_time_ms);
        if want != self.tick_for {
            self.tick_for = want;
            self.task = want.map(|connect| {
                cx.spawn(async move |this, cx| {
                    loop {
                        let wait = ms_to_next_second(connect);
                        cx.background_executor()
                            .timer(Duration::from_millis(wait))
                            .await;
                        if this.update(cx, |_, cx| cx.notify()).is_err() {
                            break;
                        }
                    }
                })
            });
        }
        cx.notify();
    }
}

/// Milliseconds until the elapsed time next rolls over a whole second.
pub(crate) fn ms_to_next_second(connect_time_ms: i64) -> u64 {
    if connect_time_ms <= 0 {
        return 1000;
    }
    let elapsed = (now_ms() - connect_time_ms).max(0);
    u64::try_from(1000 - elapsed % 1000).unwrap_or(1000) + 4
}

impl Render for CallStatus {
    fn render(&mut self, window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame(window, |dt| self.swap.tick(dt));
        self.motion = motion;

        let (text, reason) = self.headline_now();
        let tone = match self.kind {
            Headline::Hold => call_colors(theme).warning,
            Headline::Over if reason.is_some() => call_colors(theme).danger,
            _ => colors.secondary_label,
        };
        let t = self.swap.value.clamp(0.0, 1.0);
        let line = |text: SharedString, opacity: f32, offset: f32| {
            div()
                .absolute()
                .top(px(offset))
                .w_full()
                .flex()
                .justify_center()
                .opacity(opacity)
                .text_size(typography::HEADLINE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(tone)
                .child(text)
        };
        let mut root = div()
            .id("call-status")
            .role(Role::Status)
            .aria_label(text.clone())
            .relative()
            .w_full()
            .h(px(44.0));
        if let (true, Some(old)) = (t < 1.0, self.leaving.clone()) {
            root = root.child(line(old, 1.0 - t, -8.0 * t));
        }
        root = root.child(line(text, t, 8.0 * (1.0 - t)));
        if let Some(reason) = reason {
            root = root.child(
                div()
                    .absolute()
                    .top(px(24.0))
                    .w_full()
                    .flex()
                    .justify_center()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(reason),
            );
        }
        root
    }
}

/// The Muted state ring: eight dashes (and eight gaps) round the avatar.
fn dashed_ring(color: Hsla, diameter: f32) -> impl IntoElement {
    gpui::canvas(
        |_, _, _| (),
        move |bounds, (), window, _| {
            let width = 3.0_f32;
            let r = (diameter - width) * 0.5;
            let c = bounds.center();
            let top = gpui::point(c.x, c.y - px(r));
            let bottom = gpui::point(c.x, c.y + px(r));
            let dash = px(std::f32::consts::TAU * r / 16.0);
            let mut path = gpui::PathBuilder::stroke(px(width)).dash_array(&[dash, dash]);
            path.move_to(top);
            let radii = gpui::point(px(r), px(r));
            path.arc_to(radii, px(0.0), false, true, bottom);
            path.arc_to(radii, px(0.0), false, true, top);
            if let Ok(path) = path.build() {
                window.paint_path(path, color);
            }
        },
    )
    .absolute()
    .top(px(-3.0))
    .left(px(-3.0))
    .size(px(diameter))
}

// ---- Screen state ----------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq)]
enum RingKind {
    Dialing,
    Active,
    Holding,
    Over,
}

pub(crate) struct Ending {
    _task: Task<()>,
}

pub struct InCall {
    /// The screen the owner wants to see right now.
    pub shown: bool,
    pub(crate) view: Option<CallView>,
    pub(crate) ending: Option<Ending>,
    keypad_open: bool,
    digits: String,
    /// Which `clear_pending_later` timer is the latest.
    pending_gen: u64,
    /// What the keypad shows of `digits`, rebuilt only when a key is pressed.
    digit_line: SharedString,
    pub(crate) last_id: Option<i32>,
    /// The call that just ended; a lagging snapshot of it must not reopen the screen.
    pub(crate) done_id: Option<i32>,
    /// The owner pressed Back for this call: stay on the tabs until the
    /// app is opened again.
    hidden_for: Option<i32>,
    began_locked: bool,
    /// Locked with a call: only call screens may show (AD-3).
    pub call_only: bool,
    /// After a locked call ends: paint nothing while the task goes back.
    pub blank: bool,
    /// When each grid slot was tapped, for its pending look (indexed by [`Slot`]).
    pending: [Option<Instant>; 6],
    dtmf_down: bool,
    pub(crate) aura: Entity<Aura>,
    pub(crate) status: Entity<CallStatus>,
    /// The waiting caller; kept while the card rises away.
    pub(crate) waiting: Option<WaitingView>,
    /// 0 = card above the screen, 1 = in place (overshoots on the way in, S23).
    pub(crate) waiting_in: Spring,
    pub(crate) waiting_open: bool,
    /// Seconds since the Waiting card appeared (its pills stagger 40 ms).
    pub(crate) waiting_age: f32,
    /// The caller left before an answer or decline of ours: say so in a pill.
    pub(crate) waiting_acted: bool,
    /// The keyguard was up at the last snapshot (a re-lock closes sheets).
    was_locked: bool,
    /// The multi-call zone: view-models and the springs of S19 to S22.
    pub(crate) zone: ZoneState,

    /// 0 = tabs, 1 = In-Call screen.
    fade: Entrance,
    fade_value: Spring,
    keypad_t: Spring,
    on: [Spring; 3],
    /// S10: the icon's tilt after a control turned on (1 = 6 degrees, settling through 0).
    wobble: [Spring; 3],
    end_in: Spring,
    over: Spring,
    ring_t: Spring,
    ring_from: RingKind,
    ring_to: RingKind,
    pub(crate) bloom: Spring,
}

/// Press ids of the dimmed controls (long-press shows the reason), by [`Slot`] index.
pub(crate) const DIM_IDS: [&str; 6] = [
    "ix-dim-0", "ix-dim-1", "ix-dim-2", "ix-dim-3", "ix-dim-4", "ix-dim-5",
];

/// The fixed caption box under a control: 96 px wide (it overhangs the 76 px
/// circle evenly), 16 px high, one line, ellipsised (AD-35 slot constants).
fn caption_slot(color: Hsla) -> gpui::Div {
    div()
        .relative()
        .w(px(crate::route::CAPTION_W))
        .h(px(16.0))
        .mx(px(-10.0))
        .overflow_hidden()
        .text_size(typography::CAPTION.size)
        .text_color(color)
        .text_center()
}

fn sp(value: f32, params: rovsti_ui::spring::SpringParams) -> Spring {
    Spring::new(value, params).with_epsilon(0.002)
}

impl InCall {
    pub fn new(cx: &mut gpui::App) -> Self {
        Self {
            shown: false,
            view: None,
            ending: None,
            keypad_open: false,
            digits: String::new(),
            pending_gen: 0,
            digit_line: SharedString::default(),
            last_id: None,
            done_id: None,
            hidden_for: None,
            began_locked: false,
            call_only: false,
            blank: false,
            pending: [None; 6],
            dtmf_down: false,
            aura: cx.new(|_| Aura::new()),
            status: cx.new(|_| CallStatus::new()),
            waiting: None,
            waiting_in: sp(0.0, motion_tokens::release()),
            waiting_open: false,
            waiting_age: 1.0,
            waiting_acted: false,
            was_locked: false,
            zone: ZoneState::new(cx),
            fade: Entrance::default(),
            fade_value: sp(0.0, motion_tokens::appear()),
            keypad_t: sp(0.0, motion_tokens::select()),
            on: [
                sp(0.0, motion_tokens::select()),
                sp(0.0, motion_tokens::select()),
                sp(0.0, motion_tokens::select()),
            ],
            wobble: [
                sp(0.0, motion_tokens::glow()),
                sp(0.0, motion_tokens::glow()),
                sp(0.0, motion_tokens::glow()),
            ],
            end_in: sp(0.0, motion_tokens::release()),
            over: sp(0.0, motion_tokens::sheet_dismiss()),
            ring_t: sp(1.0, motion_tokens::select()),
            ring_from: RingKind::Dialing,
            ring_to: RingKind::Dialing,
            bloom: sp(1.0, motion_tokens::appear()),
        }
    }

    /// The Audio control can be used right now: a live call that is not on hold.
    pub(crate) fn audio_usable(&self) -> bool {
        self.ending.is_none() && self.view.is_some() && self.zone.enablement.enabled(Slot::Audio)
    }

    /// The route Android last reported for the foreground call.
    pub(crate) fn route(&self) -> AudioRoute {
        self.view.as_ref().map_or(AudioRoute::Unknown, |v| v.route)
    }

    /// The Audio control reads as on (tinted); S10's flood and wobble play on
    /// the way in.
    pub(crate) fn set_audio_on(&mut self, on: bool, reduced: bool) {
        let turning_on = on && self.on[1].target < 0.5;
        self.on[1].set_target(f32::from(u8::from(on)));
        if turning_on && !reduced {
            self.wobble[1].snap(1.0);
            self.wobble[1].set_target(0.0);
        }
    }

    /// The screen covers the tabs (fully, or fading).
    pub fn covers(&self) -> bool {
        self.shown || self.fade_value.value > 0.001
    }

    /// The tabs are completely hidden, so they need not be built.
    pub fn hides_tabs(&self) -> bool {
        self.fade_value.value > 0.999
    }

    /// When the live call connected (0 while it has not).
    pub fn connect_time_ms(&self) -> i64 {
        self.view.as_ref().map_or(0, |v| v.connect_time_ms)
    }

    pub fn has_call(&self) -> bool {
        self.view.is_some() && self.ending.is_none()
    }

    /// Back should be handled here (and not by the OS) right now.
    pub fn wants_back(&self, call_only: bool) -> bool {
        self.shown && (self.keypad_open || !call_only)
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.fade_value.tick(dt);
        moving |= self.waiting_in.tick(dt);
        moving |= self.zone.tick(dt);
        if self.waiting_open && self.waiting_age < 1.0 {
            self.waiting_age += dt.as_secs_f32();
            moving = true;
        }
        if !self.waiting_open && self.waiting_in.value < 0.003 && self.waiting.is_some() {
            self.waiting = None;
        }
        for s in [
            &mut self.keypad_t,
            &mut self.end_in,
            &mut self.over,
            &mut self.ring_t,
            &mut self.bloom,
        ] {
            moving |= s.tick(dt);
        }
        for s in self.on.iter_mut().chain(self.wobble.iter_mut()) {
            moving |= s.tick(dt);
        }
        moving |= self.fade.tick(dt);
        moving
    }

    /// The End button at full width (a flight lands on it).
    pub(crate) fn end_full(&mut self) {
        self.end_in.snap(1.0);
    }

    fn set_shown(&mut self, shown: bool) {
        self.shown = shown;
        self.fade_value.set_target(f32::from(u8::from(shown)));
    }

    /// Points the Waiting card at the snapshot's waiting call (S23: it falls
    /// with an overshoot, and rises away on `sheet_dismiss`).
    fn set_waiting(&mut self, waiting: Option<WaitingView>) {
        match waiting {
            Some(w) => {
                let new = self.waiting.as_ref().map(|k| k.id) != Some(w.id) || !self.waiting_open;
                self.waiting = Some(w);
                self.waiting_open = true;
                if new {
                    self.waiting_in.snap(0.0);
                    self.waiting_age = 0.0;
                    self.waiting_acted = false;
                    self.waiting_in.retarget(1.0, motion_tokens::release());
                }
            }
            None => {
                self.waiting_open = false;
                self.waiting_in
                    .retarget(0.0, motion_tokens::sheet_dismiss());
            }
        }
    }

    fn ring_kind(&self) -> RingKind {
        match self.view.as_ref().map(CallView::phase) {
            Some(Phase::Active) => RingKind::Active,
            Some(Phase::Holding) => RingKind::Holding,
            Some(Phase::Ended | Phase::Failed) => RingKind::Over,
            _ => RingKind::Dialing,
        }
    }

    fn ring_color(kind: RingKind, theme: Theme, hue: Option<usize>) -> Hsla {
        let glow = theme::accent_glow(theme);
        match kind {
            RingKind::Dialing => Hsla { a: 0.6, ..glow },
            RingKind::Active => theme::hue_pair(theme, hue).0,
            RingKind::Holding => call_colors(theme).warning,
            RingKind::Over => call_colors(theme).danger,
        }
    }
}

// ---- Behaviour on PhoneApp ----------------------------------------------------

impl PhoneApp {
    /// Brings the screen in line with the newest call snapshot. Runs only when
    /// the backend notifies.
    pub(crate) fn sync_calls(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let diff = self.backend.update(cx, |b, _| b.take_scene_diff());
        let full = !self.incall.zone.synced;
        let (view, locked, call_only, waiting, scene_in) = {
            let backend = self.backend.read(cx);
            let model = backend.calls();
            let scene = CallScene::of(model.snapshot());
            (
                CallView::from_model(model),
                model.keyguard_locked(),
                model.is_call_only_mode(),
                WaitingView::from_scene(&scene),
                (diff.changed() || full)
                    .then(|| zone::SceneIn::of(&scene, model.snapshot().pending_failed)),
            )
        };
        self.incall.call_only = call_only;
        // The route list and the Audio control's face come first: `apply_view`
        // reads whether the control is on from them.
        let route = self
            .backend
            .read(cx)
            .calls()
            .foreground()
            .map(|_| self.backend.read(cx).calls().audio().route);
        match route {
            Some(route) => self.route_sync(route, cx),
            None => self.route.reset(),
        }
        if let Some(scene_in) = scene_in {
            self.apply_scene(scene_in, diff, window, cx);
        }
        let waiting_before = self.incall.waiting.clone();
        let ic = &mut self.incall;
        if waiting.is_some() && !ic.waiting_open && ic.has_call() {
            // A second caller rings: the call screen comes to the front.
            ic.set_shown(true);
            ic.hidden_for = None;
        }
        if waiting.is_some() && !ic.waiting_open {
            ic.aura.update(cx, |a, cx| a.waiting_pulse(cx));
            rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        }
        let waiting_gone = waiting.is_none() && ic.waiting_open;
        ic.set_waiting(waiting);
        if waiting_gone && !self.incall.waiting_acted {
            self.waiting_left(waiting_before, cx);
        }
        let ic = &mut self.incall;
        let view = view.filter(|v| Some(v.id) != ic.done_id);
        if view.is_none() && !self.backend.read(cx).calls().has_calls() {
            self.incall.done_id = None;
        }
        // Ringing calls (and their unanswered end) belong to the Incoming Call screen.
        let view = match view {
            Some(view) => self.route_incoming(view, locked, window, cx),
            None => {
                // The record vanished while ringing: leave without a "missed" claim.
                self.incoming_ended(DisconnectCause::Unknown, cx);
                None
            }
        };
        let ic = &mut self.incall;
        match view {
            Some(view) => {
                let is_new = ic.last_id != Some(view.id);
                if ic.last_id.is_none() {
                    ic.last_id = Some(view.id);
                    ic.ending = None;
                    ic.hidden_for = None;
                    ic.began_locked = locked;
                    ic.keypad_open = false;
                    ic.keypad_t.snap(0.0);
                    ic.digits.clear();
                    ic.digit_line = SharedString::default();
                    ic.blank = false;
                    ic.end_in.snap(0.0);
                    ic.end_in.set_target(1.0);
                    ic.over.snap(0.0);
                    let reduced = window.prefers_reduced_motion();
                    ic.aura.update(cx, |a, cx| a.restart(reduced, cx));
                    ic.set_shown(true);
                    ic.fade.restart();
                    if window.prefers_reduced_motion() {
                        // Reduced motion: a 120 ms cross-fade instead of any flight.
                        self.call_fade.restart();
                    }
                    // The number was handed to Telecom: the field is done.
                    self.dialed.clear();
                    self.status = None;
                    self.delete_shown.set_target(0.0);
                    self.bloom_start();
                } else if is_new {
                    // The foreground call changed inside one conversation (a swap, a
                    // split, a hang-up beside a held call): the screen stays up.
                    ic.last_id = Some(view.id);
                    ic.ending = None;
                }
                let ic = &mut self.incall;
                let was_active = ic.view.as_ref().map(|v| v.state) == Some(CallState::Active);
                if ic.view.as_ref() != Some(&view) {
                    if view.state == CallState::Active && !was_active && !is_new {
                        // S9: a light haptic as the timer lands.
                        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
                    }
                    self.apply_view(view, window, cx);
                }
                if call_only {
                    self.incall.set_shown(true);
                }
            }
            None => {
                if ic.view.is_some() && ic.ending.is_none() {
                    self.begin_ending(DisconnectCause::Unknown, window, cx);
                }
            }
        }
        self.zone_lines(cx);
        self.sync_add_call(window, cx);
        self.relock_closes(locked, window, cx);
    }

    /// A re-lock closes any open sheet or popover (AD-3): the lock screen shows only the call.
    fn relock_closes(&mut self, locked: bool, window: &mut Window, cx: &mut Context<Self>) {
        let was = std::mem::replace(&mut self.incall.was_locked, locked);
        if locked && !was && self.incall.view.is_some() {
            if self.sheet.is_open() {
                self.close_sheet(cx);
            }
            if self.route.is_open() {
                self.route_close(window, cx);
            }
        }
    }

    /// Points the two ticking lines at their times; they run only while the screen shows.
    pub(crate) fn zone_lines(&mut self, cx: &mut Context<Self>) {
        let shown = self.incall.shown;
        let z = &self.incall.zone;
        let held = z
            .held
            .as_ref()
            .or(z.held_ghost.as_ref())
            .map(|p| p.hold_since_ms);
        let conf = z.conference.as_ref().map(|c| c.connect_time_ms);
        let (held_line, conf_line) = (z.held_line.clone(), z.conf_line.clone());
        held_line.update(cx, |l, cx| {
            l.set(held.unwrap_or(0), shown && held.is_some(), cx)
        });
        conf_line.update(cx, |l, cx| {
            l.set(conf.unwrap_or(0), shown && conf.is_some(), cx)
        });
    }

    /// Applies a changed scene to the zone: view-models, enablement, and which
    /// moment (S19 to S21) plays.
    fn apply_scene(
        &mut self,
        si: zone::SceneIn,
        diff: rovsti_core::scene::SceneDiff,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let reduced = window.prefers_reduced_motion();
        let ic = &mut self.incall;
        let moving_part = diff.primary || diff.held || diff.participants || diff.audio;
        if moving_part {
            // Reported state arrived: pending looks are no longer needed.
            ic.pending = [None; 6];
        }
        let z = &mut ic.zone;
        let first = !z.synced;
        z.synced = true;
        let old = (z.face, z.fg_id, z.bg_id);
        let new = (si.face, si.fg_id, si.bg_id);
        let moment = if first {
            Moment::None
        } else {
            zone::moment(old, new)
        };
        let old_primary = z.primary.take();
        let old_held = z.held.take();
        z.enablement = si.enablement;
        z.face = si.face;
        z.fg_id = si.fg_id;
        z.bg_id = si.bg_id;
        z.primary = si.primary.clone();
        z.held = si.held.clone();
        z.conference = si.conference;
        // ---- The Held card's presence ----
        if z.held.is_some() {
            if old_held.is_none() && moment == Moment::None {
                z.held_t.snap(1.0);
            } else {
                z.held_t.set_target(1.0);
            }
            z.held_ghost = None;
        } else if let Some(gone) = old_held.clone() {
            // The held party hung up (or was merged): the card fades out in 120 ms.
            z.held_ghost = Some(gone);
            z.held_t.set_target(0.0);
        }
        // ---- Moments ----
        match moment {
            Moment::HoldAway => {
                if reduced {
                    z.layout.snap(1.0);
                    z.drop.snap(1.0);
                    z.cross.restart();
                } else {
                    z.layout.snap(0.0);
                    z.layout.set_target(1.0);
                    z.drop.snap(0.0);
                    z.drop.set_target(1.0);
                }
                ic.aura.update(cx, |a, cx| a.bloom(cx));
            }
            Moment::Swap => {
                if reduced {
                    z.swap.snap(1.0);
                    z.cross.restart();
                } else if let (Some(out), Some(inn)) = (old_primary, old_held.clone()) {
                    z.crossing = Some(zone::Crossing { out, inn });
                    z.swap.snap(0.0);
                    z.swap.set_target(1.0);
                }
            }
            Moment::Merge => {
                z.weave_from = old_held.clone();
                if reduced {
                    z.weave.snap(1.0);
                    z.cross.restart();
                } else {
                    z.weave.snap(0.0);
                    z.weave.set_target(1.0);
                    z.weave_age = 0.0;
                    ic.aura.update(cx, |a, cx| a.pulse_once(1.12, cx));
                }
            }
            Moment::None => match (old.0, si.face) {
                (_, Face::Conference) => {
                    z.weave.snap(1.0);
                    z.layout.snap(1.0);
                }
                (_, Face::Two) => {
                    z.weave.snap(0.0);
                    z.layout.snap(1.0);
                }
                (_, Face::Single | Face::Empty) => {
                    z.weave.snap(0.0);
                    if z.ended.is_none() {
                        z.layout.snap(0.0);
                    }
                }
            },
        }
        if si.face != Face::Conference && moment != Moment::Merge {
            z.weave.snap(0.0);
        }
        // ---- A call that ended beside a live one: "Call ended" for a second ----
        if diff.ended
            && z.ended.is_none()
            && let Some(party) = si.ended
            && si.face == Face::Single
            && !first
        {
            z.ended = Some(zone::EndedParty { party });
            z.layout.snap(1.0);
            z.held = z.primary.clone();
            z.held_ghost = None;
            z.held_t.snap(1.0);
            z.ended_task = Some(cx.spawn(async move |this, cx| {
                cx.background_executor().timer(ENDED_HOLD).await;
                this.update(cx, |this, cx| this.zone_ended_done(cx)).ok();
            }));
        }
        // ---- A failed operation: the control returns and a pill says why ----
        match si.failed {
            Some(PendingOp::Merge) => {
                self.show_notice_pill("Couldn't merge calls", None, false, cx)
            }
            Some(PendingOp::Swap) => self.show_notice_pill("Couldn't swap calls", None, false, cx),
            Some(PendingOp::Answer) => {
                self.show_notice_pill("Couldn't answer the call", None, false, cx);
            }
            _ => {}
        }
        cx.notify();
    }

    /// The "Call ended" second is over: the held party takes the active block.
    fn zone_ended_done(&mut self, cx: &mut Context<Self>) {
        let z = &mut self.incall.zone;
        if z.ended.take().is_some() {
            z.ended_task = None;
            z.held = None;
            if self.reduced {
                z.layout.snap(0.0);
                z.cross.restart();
            } else {
                z.layout.set_target(0.0);
            }
            z.held_t.set_target(0.0);
            cx.notify();
        }
    }

    /// The waiting caller left without our answer or decline: a pill says so for 2 s.
    fn waiting_left(&mut self, was: Option<WaitingView>, cx: &mut Context<Self>) {
        let Some(was) = was else {
            return;
        };
        // Still there and no longer ringing: it was answered (here or elsewhere).
        let answered = self
            .backend
            .read(cx)
            .calls()
            .call(was.id)
            .is_some_and(|c| c.state != rovsti_core::wire::WireCallState::Ringing);
        if !answered {
            self.show_notice_pill(format!("Missed call from {}", was.name), None, false, cx);
        }
    }

    fn bloom_start(&mut self) {
        self.incall.bloom.snap(0.0);
        self.incall.bloom.set_target(1.0);
    }

    fn apply_view(&mut self, view: CallView, window: &mut Window, cx: &mut Context<Self>) {
        if view.state == CallState::Holding || view.state.is_disconnected() {
            // Hold (or the end) closes the route list; the control dims.
            self.route.close();
        }
        let audio_on = self.route.face.on || self.route.open;
        let speaker = self.route.face.speaker || view.route == AudioRoute::Speaker;
        let ic = &mut self.incall;
        let disconnected = view.state.is_disconnected();
        let failed = matches!(view.phase(), Phase::Failed);
        let cause = match view.state {
            CallState::Disconnected { cause } => cause,
            _ => DisconnectCause::Unknown,
        };
        let aura_state = AuraState {
            phase: view.phase(),
            hue: view.hue,
            muted: view.muted,
            speaker,
            hushed: false,
        };
        let on = [view.muted, audio_on, view.state == CallState::Holding];
        // S10 plays only for the state Android reported: a press that was
        // reverted never changes `on`, so it never floods or wobbles.
        let reduced = window.prefers_reduced_motion();
        for ((spring, wobble), on) in ic.on.iter_mut().zip(&mut ic.wobble).zip(on) {
            let turning_on = on && spring.target < 0.5;
            spring.set_target(f32::from(u8::from(on)));
            if turning_on && !reduced {
                wobble.snap(1.0);
                wobble.set_target(0.0);
            }
        }
        // Reported state arrived: the pending look is no longer needed.
        ic.pending = [None; 6];

        let before = ic.ring_kind();
        let status_args = (view.state, view.connect_time_ms);
        ic.view = Some(view);
        let after = ic.ring_kind();
        if after != before {
            ic.ring_from = ic.ring_to;
            ic.ring_to = after;
            ic.ring_t.snap(0.0);
            ic.ring_t.set_target(1.0);
        }
        let visible = ic.shown;
        ic.aura.update(cx, |a, cx| a.set(aura_state, cx));
        ic.status.update(cx, |s, cx| {
            s.update(status_args.0, status_args.1, visible, cx);
        });
        if disconnected && ic.ending.is_none() {
            self.begin_ending_with(failed, cause, cx);
        }
        let _ = cause;
        cx.notify();
    }

    fn begin_ending(
        &mut self,
        cause: DisconnectCause,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        // The record vanished without a Disconnected snapshot: show the last
        // state as ended.
        if let Some(view) = &mut self.incall.view {
            view.state = CallState::Disconnected { cause };
        }
        let view = self.incall.view.clone();
        if let Some(view) = view {
            self.apply_view(view, _window, cx);
        }
        if self.incall.ending.is_none() {
            self.begin_ending_with(false, cause, cx);
        }
    }

    fn begin_ending_with(&mut self, failed: bool, _cause: DisconnectCause, cx: &mut Context<Self>) {
        let hold = if failed { FAILED_HOLD } else { ENDED_HOLD };
        let task = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(hold).await;
            this.update(cx, |this, cx| this.finish_ending(cx)).ok();
        });
        let ic = &mut self.incall;
        ic.over.set_target(1.0);
        ic.keypad_open = false;
        ic.keypad_t.set_target(0.0);
        ic.ending = Some(Ending { _task: task });
        cx.notify();
    }

    /// The hold is over: leave to the tabs, or hand the screen back to the
    /// lock screen when the call began locked (AD-3a).
    fn finish_ending(&mut self, cx: &mut Context<Self>) {
        let model_has_call = self.backend.read(cx).calls().has_calls();
        let ic = &mut self.incall;
        ic.ending = None;
        ic.status.update(cx, |s, cx| {
            s.update(CallState::Unknown, 0, false, cx);
        });
        if model_has_call {
            // Another call is already there; sync_calls shows it.
            ic.view = None;
            ic.last_id = None;
            cx.notify();
            return;
        }
        ic.done_id = ic.last_id;
        // Story 17.4: a short answered call from somebody calling in offers "Block".
        let offer = ic
            .view
            .as_ref()
            .filter(|v| {
                v.incoming
                    && !ic.began_locked
                    && v.connect_time_ms > 0
                    && backend::now_ms() - v.connect_time_ms < SHORT_CALL_MS
            })
            .map(|v| (v.number.clone(), v.name.clone()));
        ic.view = None;
        ic.last_id = None;
        ic.set_shown(false);
        // S4: the avatar flies back to its row or tile if it is still there.
        self.begin_return_flight();
        let ic = &mut self.incall;
        if ic.began_locked {
            ic.blank = true;
            backend::move_task_to_back();
        }
        self.presses.release_all();
        if let Some((number, who)) = offer {
            self.offer_block_after_call(&number, &who, false, cx);
        }
        cx.notify();
    }

    /// Opening the app again (launcher, notification) brings the call back.
    pub(crate) fn app_activated(&mut self, cx: &mut Context<Self>) {
        let ic = &mut self.incall;
        if ic.blank {
            ic.blank = false;
        }
        if ic.has_call() && !ic.shown {
            ic.hidden_for = None;
            ic.set_shown(true);
            ic.fade.restart();
            ic.status.update(cx, |s, cx| {
                s.update(
                    ic.view.as_ref().map_or(CallState::Unknown, |v| v.state),
                    ic.view.as_ref().map_or(0, |v| v.connect_time_ms),
                    true,
                    cx,
                );
            });
        }
        cx.notify();
    }

    /// Back on the In-Call screen: close the keypad, else go to the tabs.
    pub(crate) fn incall_back(&mut self, cx: &mut Context<Self>) -> bool {
        let ic = &mut self.incall;
        if !ic.shown {
            return false;
        }
        if ic.keypad_open {
            ic.keypad_open = false;
            ic.keypad_t.set_target(0.0);
        } else {
            ic.hidden_for = ic.last_id;
            ic.set_shown(false);
            let state = ic.view.as_ref().map(|v| (v.state, v.connect_time_ms));
            if let Some((state, connect)) = state {
                ic.status
                    .update(cx, |s, cx| s.update(state, connect, false, cx));
            }
        }
        self.presses.release_all();
        cx.notify();
        true
    }

    // ---- Presses ------------------------------------------------------------

    pub(crate) fn incall_activate(
        &mut self,
        id: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(view) = self.incall.view.clone() else {
            return;
        };
        let live = self.incall.ending.is_none();
        let zone = self.incall.zone.enablement;
        match id {
            "ic-end" if live => {
                self.send(CallCommand::Disconnect(view.id), cx);
            }
            "ic-mute" if live && zone.enabled(Slot::Mute) => {
                self.start_pending(Slot::Mute, window, cx);
                self.send(CallCommand::Mute(!view.muted), cx);
            }
            "ic-keypad" if live && zone.enabled(Slot::Keypad) => {
                self.incall.keypad_open = true;
                self.incall.keypad_t.set_target(1.0);
            }
            "ic-hide" => {
                self.incall.keypad_open = false;
                self.incall.keypad_t.set_target(0.0);
            }
            _ => {
                if let Some(digit) = id.strip_prefix("ik-").and_then(|d| d.chars().next())
                    && live
                {
                    rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
                    push_digit(&mut self.incall.digits, digit);
                    self.incall.digit_line = digit_line(&self.incall.digits).into();
                    self.incall.dtmf_down = true;
                    self.send(CallCommand::Dtmf(digit), cx);
                }
            }
        }
        cx.notify();
    }

    /// A finger-up control (Add call, Hold or Swap, Merge, the Held card, the Waiting
    /// card's pills): the press look has been showing since finger-down.
    pub(crate) fn incall_tap(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        if self.incall.view.is_none() || self.incall.ending.is_some() {
            return;
        }
        if id.starts_with("iw-") {
            self.waiting_act(id, window, cx);
            return;
        }
        let en = self.incall.zone.enablement;
        match id {
            "ix-add" => {
                if let Some(reason) = en.reason(Slot::AddCall) {
                    self.show_notice_pill(reason.text(), None, false, cx);
                } else {
                    self.open_add_call(window, cx);
                }
            }
            "ix-hold" | "ix-held" => {
                let swapping = en.swap || id == "ix-held";
                if let Some(reason) = en.reason(Slot::HoldSwap) {
                    self.show_notice_pill(reason.text(), None, false, cx);
                } else if swapping {
                    rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
                    self.start_pending(Slot::HoldSwap, window, cx);
                    self.backend.update(cx, |b, cx| {
                        b.swap(cx);
                    });
                } else if let Some(view) = self.incall.view.clone() {
                    let on = view.state != CallState::Holding;
                    self.start_pending(Slot::HoldSwap, window, cx);
                    self.send(CallCommand::Hold(view.id, on), cx);
                }
            }
            "ix-merge" => {
                if let Some(reason) = en.reason(Slot::Merge) {
                    self.show_notice_pill(reason.text(), None, false, cx);
                } else {
                    rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
                    self.start_pending(Slot::Merge, window, cx);
                    self.backend.update(cx, |b, cx| {
                        b.merge(cx);
                    });
                }
            }
            _ => {}
        }
        cx.notify();
    }

    /// The reason a dimmed control is dimmed (long-press, 450 ms).
    pub(crate) fn show_reason(&mut self, slot: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(slot) = Slot::ALL.get(slot).copied() else {
            return;
        };
        if let Some(reason) = self.incall.zone.enablement.reason(slot) {
            rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
            self.show_notice_pill(reason.text(), None, false, cx);
        }
    }

    /// A tapped control keeps its pending look until the snapshot confirms it, at
    /// most a second; then it returns.
    fn start_pending(&mut self, slot: Slot, window: &mut Window, cx: &mut Context<Self>) {
        let now = Instant::now();
        self.incall.pending[slot.index()] = Some(now);
        self.incall.pending_gen = self.incall.pending_gen.wrapping_add(1);
        cx.spawn_in(window, async move |this, cx| {
            cx.background_executor().timer(PENDING_HOLD).await;
            this.update(cx, |this, cx| {
                if this.incall.pending[slot.index()] == Some(now) {
                    this.incall.pending[slot.index()] = None;
                    this.presses.release_all();
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// The Phone/Speaker toggle (only those two routes exist): the pending
    /// look until the next snapshot, the state always from the snapshot.
    pub(crate) fn incall_toggle_speaker(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.incall.audio_usable() {
            return;
        }
        self.start_pending(Slot::Audio, window, cx);
        let target = if self.incall.route() == AudioRoute::Speaker {
            AudioRoute::Earpiece
        } else {
            AudioRoute::Speaker
        };
        self.send(CallCommand::Route(target), cx);
        cx.notify();
    }

    /// The finger lifted: stop the touch tone that is playing.
    pub(crate) fn incall_released(&mut self, cx: &mut Context<Self>) {
        if self.incall.dtmf_down {
            self.incall.dtmf_down = false;
            self.send(CallCommand::DtmfStop, cx);
        }
    }

    fn send(&mut self, command: CallCommand, cx: &mut Context<Self>) {
        self.backend.update(cx, |backend, cx| {
            backend.send(command, cx);
        });
    }

    // ---- Rendering --------------------------------------------------------------

    fn pressed(&self, id: &str, pending: Option<Instant>) -> f32 {
        let held = self.presses.value(id);
        match pending {
            Some(at) if at.elapsed() < PENDING_HOLD => held.max(0.8),
            _ => held,
        }
    }

    /// One grid control. `slot` says which of the six it is; a dimmed one keeps its place
    /// at 0.4 and, held for 450 ms, shows why. Ids starting `ix-` act on finger-up, the
    /// others on finger-down (Mute and Keypad, as in v0.1).
    #[allow(clippy::too_many_arguments)] // one call site per control; a struct would only repeat these
    fn control(
        &self,
        theme: Theme,
        slot: Slot,
        id: &'static str,
        icon: Icon,
        label: &'static str,
        aria: SharedString,
        alt: Option<Icon>,
        on: f32,
        wobble: f32,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let colors = theme::colors(theme);
        let reason = self.incall.zone.enablement.reason(slot);
        let enabled = reason.is_none() && self.incall.ending.is_none();
        let press = self.pressed(id, self.incall.pending[slot.index()]);
        let side = CONTROL * press_scale(press, scale::PRESS_CARD);
        let on = on.clamp(0.0, 1.0);
        let spec = GlassSpec::control(theme).lerp(
            GlassSpec::lens(theme),
            (press.clamp(0.0, 1.0) * 0.6 + on * 0.2).min(1.0),
        );
        let glyph = mix(colors.label, colors.accent, on);
        // S10: the icon tilts 6 degrees and settles back (`glow` spring).
        let tilt = gpui::Transformation::rotate(gpui::radians(wobble * 6.0_f32.to_radians()));
        let draw = |icon: Icon, alpha: f32| {
            svg()
                .path(icon.path())
                .size(px(28.0))
                .text_color(Hsla { a: alpha, ..glyph })
                .with_transformation(tilt)
        };
        // The icon cross-fades with the tint when the control has a second face.
        let face = match alt {
            Some(alt) => div()
                .relative()
                .size(px(28.0))
                .child(div().absolute().inset_0().child(draw(icon, 1.0 - on)))
                .child(div().absolute().inset_0().child(draw(alt, on)))
                .into_any_element(),
            None => draw(icon, 1.0).into_any_element(),
        };
        let ending = self.incall.ending.is_some();
        let label_aria: SharedString = match reason {
            Some(reason) => format!("{aria}, unavailable: {}", reason.text()).into(),
            None => aria,
        };
        let mut slot_el = div()
            .id(id)
            .role(Role::Button)
            .aria_label(label_aria)
            .aria_disabled(!enabled)
            .w(px(CONTROL))
            .flex()
            .flex_col()
            .items_center()
            .gap(px(slots::CAPTION_GAP))
            // S11: when the call ends the controls dim to 0.4 on the `over` spring.
            .opacity({
                let rest: f32 = if enabled { 1.0 } else { 0.4 };
                if ending {
                    rest.min(1.0 - 0.6 * self.incall.over.value.clamp(0.0, 1.0))
                } else {
                    rest
                }
            });
        let circle = div()
            .relative()
            .size(px(CONTROL))
            .flex()
            .items_center()
            .justify_center()
            .child(
                glass_interactive(spec, move || GlassInteraction { press, focus: None })
                    .size(px(side))
                    .rounded_full()
                    .flex()
                    .items_center()
                    .justify_center()
                    // S10: an accent circle (20%) grows from the middle of the control.
                    .child(
                        div()
                            .absolute()
                            .size(px(side * on))
                            .rounded_full()
                            .bg(Hsla {
                                a: 0.20,
                                ..colors.accent
                            }),
                    )
                    .child(face),
            )
            .child(
                div()
                    .absolute()
                    .size(px(side))
                    .rounded_full()
                    .border_2()
                    .border_color(Hsla {
                        a: on,
                        ..colors.accent
                    }),
            );
        if enabled {
            slot_el = if id.starts_with("ix-") {
                slot_el.on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| this.tap_press(id, window, cx)),
                )
            } else {
                slot_el.on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| this.mouse_down(id, window, cx)),
                )
            };
            slot_el = slot_el.child(touch_surface(Self::touch_listener(cx, Some(id.into()))));
        } else if reason.is_some() && !ending {
            // A dimmed control does nothing on a tap; held for 450 ms it says why.
            let dim: &'static str = DIM_IDS[slot.index()];
            slot_el = slot_el
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        if !this.presses.touch_seen() {
                            this.show_reason(slot.index(), window, cx);
                        }
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(dim.into()))));
        }
        slot_el
            .child(circle)
            .child(caption_slot(colors.secondary_label).child(label))
    }

    /// The Audio control (row 1, column 3): the route's icon and caption, a
    /// tap toggles Phone/Speaker or opens the route list, a long press always
    /// opens it. Touches go through a pointer surface and the tap guard, so
    /// press feedback paints on the raw finger-down and the action runs on
    /// finger-up (UIQ-1). The caption slot is fixed (96 px, one line), and the
    /// S17 cross-fade plays inside it.
    fn audio_control(
        &self,
        theme: Theme,
        _view: &CallView,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let ic = &self.incall;
        let colors = theme::colors(theme);
        let id = "ic-audio";
        let enabled = ic.ending.is_none() && ic.zone.enablement.enabled(Slot::Audio);
        let held = self.pressed(id, ic.pending[Slot::Audio.index()]);
        let press = if self.route.has_pending() {
            held.max(0.8)
        } else {
            held
        };
        let side = CONTROL * press_scale(press, scale::PRESS_CARD);
        let on = ic.on[1].value.clamp(0.0, 1.0);
        let wobble = ic.wobble[1].value;
        let spec = GlassSpec::control(theme).lerp(
            GlassSpec::lens(theme),
            (press.clamp(0.0, 1.0) * 0.6 + on * 0.2).min(1.0),
        );
        let glyph = mix(colors.label, colors.accent, on);
        let tilt = gpui::Transformation::rotate(gpui::radians(wobble * 6.0_f32.to_radians()));
        let draw = |icon: Icon, alpha: f32| {
            svg()
                .path(icon.path())
                .size(px(28.0))
                .text_color(Hsla { a: alpha, ..glyph })
                .with_transformation(tilt)
        };
        let (old_icon, old_caption, swap) = self.audio_swap();
        let face = &self.route.face;
        let icon = if swap < 1.0 {
            div()
                .relative()
                .size(px(28.0))
                .child(div().absolute().inset_0().child(draw(old_icon, 1.0 - swap)))
                .child(div().absolute().inset_0().child(draw(face.icon, swap)))
                .into_any_element()
        } else {
            draw(face.icon, 1.0).into_any_element()
        };
        let over = if ic.ending.is_some() {
            1.0 - 0.6 * ic.over.value.clamp(0.0, 1.0)
        } else {
            1.0
        };
        let mut slot = div()
            .id(id)
            .role(Role::Button)
            .aria_label(face.aria.clone())
            .aria_disabled(!enabled)
            .relative()
            .w(px(CONTROL))
            .flex()
            .flex_col()
            .items_center()
            .gap(px(4.0))
            .opacity(if enabled { 1.0 } else { 0.4_f32 }.min(over));
        let circle = div()
            .relative()
            .size(px(CONTROL))
            .flex()
            .items_center()
            .justify_center()
            .child(self.marks.probe(A_AUDIO))
            .child(
                glass_interactive(spec, move || GlassInteraction { press, focus: None })
                    .size(px(side))
                    .rounded_full()
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        div()
                            .absolute()
                            .size(px(side * on))
                            .rounded_full()
                            .bg(Hsla {
                                a: 0.20,
                                ..colors.accent
                            }),
                    )
                    .child(icon),
            )
            .child(
                div()
                    .absolute()
                    .size(px(side))
                    .rounded_full()
                    .border_2()
                    .border_color(Hsla {
                        a: on,
                        ..colors.accent
                    }),
            )
            // S18: the arrival dot at the icon's top-right (3 s, popover closed).
            .children(self.audio_dot(theme));
        if enabled {
            let entity = cx.entity();
            slot = slot.child(pointer_surface(
                Rc::clone(&self.route.control_bounds),
                move |pointer, position, window, cx| {
                    entity.update(cx, |this, cx| {
                        this.audio_pointer(pointer, position, window, cx)
                    });
                },
            ));
        }
        let caption = if swap < 1.0 {
            caption_slot(colors.secondary_label)
                .child(
                    div()
                        .absolute()
                        .top_0()
                        .w_full()
                        .opacity(1.0 - swap)
                        .truncate()
                        .child(old_caption.clone()),
                )
                .child(
                    div()
                        .absolute()
                        .top(px(6.0 * (1.0 - swap)))
                        .w_full()
                        .opacity(swap)
                        .truncate()
                        .child(face.caption.clone()),
                )
        } else {
            caption_slot(colors.secondary_label)
                .child(div().w_full().truncate().child(face.caption.clone()))
        };
        slot.child(circle).child(caption)
    }

    /// The six fixed slots: row 1 Mute, Keypad, Audio; row 2 Add call, Hold (Swap with
    /// two calls), Merge. Every scene uses the same slots; enablement comes from
    /// `zone::Enablement`, never from this code.
    fn controls(&self, theme: Theme, view: &CallView, cx: &mut Context<Self>) -> AnyElement {
        let ic = &self.incall;
        let z = &ic.zone;
        let holding = view.state == CallState::Holding;
        let hold_face = if z.enablement.swap {
            ("Swap", Icon::LucideArrowUpDown, None, 0.0)
        } else if holding {
            (
                "Resume",
                Icon::LucidePause,
                Some(Icon::LucidePlay),
                ic.on[2].value,
            )
        } else {
            (
                "Hold",
                Icon::LucidePause,
                Some(Icon::LucidePlay),
                ic.on[2].value,
            )
        };
        let hold_aria: SharedString = match (&z.held, z.enablement.swap) {
            (Some(held), true) => format!("Swap calls, {} on hold", held.name).into(),
            (_, true) => "Swap calls".into(),
            (_, false) => hold_face.0.into(),
        };
        let merged = z.face == Face::Conference;
        div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(slots::GRID_GAP))
            .child(
                div()
                    .flex()
                    .gap(px(slots::GRID_GAP))
                    .child(self.control(
                        theme,
                        Slot::Mute,
                        "ic-mute",
                        Icon::LucideMic,
                        "Mute",
                        if view.muted { "Mute, on" } else { "Mute, off" }.into(),
                        Some(Icon::LucideMicOff),
                        ic.on[0].value,
                        ic.wobble[0].value,
                        cx,
                    ))
                    .child(self.control(
                        theme,
                        Slot::Keypad,
                        "ic-keypad",
                        Icon::LucideGrid3x3,
                        "Keypad",
                        "Keypad".into(),
                        None,
                        0.0,
                        0.0,
                        cx,
                    ))
                    .child(self.audio_control(theme, view, cx)),
            )
            .child(
                div()
                    .flex()
                    .gap(px(slots::GRID_GAP))
                    .child(self.control(
                        theme,
                        Slot::AddCall,
                        "ix-add",
                        Icon::LucidePlus,
                        "Add call",
                        "Add call".into(),
                        None,
                        0.0,
                        0.0,
                        cx,
                    ))
                    .child(self.control(
                        theme,
                        Slot::HoldSwap,
                        "ix-hold",
                        hold_face.1,
                        hold_face.0,
                        hold_aria,
                        hold_face.2,
                        hold_face.3,
                        ic.wobble[2].value,
                        cx,
                    ))
                    .child(self.control(
                        theme,
                        Slot::Merge,
                        "ix-merge",
                        Icon::LucideMerge,
                        if merged { "Merged" } else { "Merge" },
                        if merged { "Merged" } else { "Merge calls" }.into(),
                        None,
                        f32::from(u8::from(merged)),
                        0.0,
                        cx,
                    )),
            )
            .into_any_element()
    }

    fn in_call_keypad(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let line = self.incall.digit_line.clone();
        div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(12.0))
            .child(
                div()
                    .h(px(36.0))
                    .text_size(px(28.0))
                    .font_weight(gpui::FontWeight(300.0))
                    .text_color(colors.label)
                    .child(line),
            )
            .child(self.dial_pad(theme, IN_KEY, 16.0, true, cx))
            .child(self.text_button(theme, "ic-hide", "Hide", cx))
            .into_any_element()
    }

    fn text_button(
        &self,
        theme: Theme,
        id: &'static str,
        label: &'static str,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let colors = theme::colors(theme);
        let press = self.presses.value(id);
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(label)
            .h(px(44.0))
            .px(px(24.0))
            .flex()
            .items_center()
            .justify_center()
            .opacity(1.0 - 0.5 * press.clamp(0.0, 1.0))
            .text_size(typography::HEADLINE.size)
            .font_weight(typography::HEADLINE.weight)
            .text_color(colors.accent)
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| this.mouse_down(id, window, cx)),
            )
            .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
            .child(label)
    }

    pub(crate) fn avatar_view(&self, theme: Theme, view: &CallView, size: f32) -> impl IntoElement {
        let u = size / 96.0;
        let ic = &self.incall;
        let colors = theme::colors(theme);
        let (top, bottom) = theme::hue_pair(theme, view.hue);
        let ring = mix(
            Self::ring_color_for(ic.ring_from, theme, view.hue),
            Self::ring_color_for(ic.ring_to, theme, view.hue),
            ic.ring_t.value.clamp(0.0, 1.0),
        );
        let over = ic.over.value.clamp(0.0, 1.0);
        let k = 1.0 - 0.1 * over;
        // Muted: the solid ring cross-fades into 8 dashes.
        let muted = ic.on[0].value.clamp(0.0, 1.0);
        let fill = if view.hue.is_some() {
            linear_gradient(
                135.0,
                linear_color_stop(top, 0.0),
                linear_color_stop(bottom, 1.0),
            )
        } else {
            linear_gradient(
                135.0,
                linear_color_stop(colors.fill, 0.0),
                linear_color_stop(colors.fill, 1.0),
            )
        };
        let inner = match &view.monogram {
            Some(letter) => div()
                .text_size(px(42.0 * u))
                .font_weight(typography::HEADLINE.weight)
                .text_color(gpui::white())
                .child(letter.clone())
                .into_any_element(),
            None => svg()
                .path(Icon::LucideUser.path())
                .size(px(44.0 * u))
                .text_color(colors.secondary_label)
                .into_any_element(),
        };
        let flying = f32::from(u8::from(self.flights.hides_avatar()));
        div()
            .relative()
            .size(px(size))
            .opacity((1.0 - 0.4 * over) * (1.0 - flying))
            .child(self.marks.probe(A_AVATAR))
            .child(
                div()
                    .size(px(size * k))
                    .mx_auto()
                    .mt(px(size * (1.0 - k) * 0.5))
                    .rounded_full()
                    .relative()
                    .border(px(3.0 * u.max(0.45)))
                    .border_color(Hsla {
                        a: ring.a * (1.0 - muted),
                        ..ring
                    })
                    .p(px(3.0 * u.max(0.45)))
                    .children((muted > 0.01).then(|| {
                        dashed_ring(
                            Hsla {
                                a: ring.a * muted,
                                ..ring
                            },
                            size * k,
                        )
                    }))
                    .child(
                        div()
                            .size_full()
                            .rounded_full()
                            .bg(fill)
                            .border(px(1.5 * u.max(0.6)))
                            .border_color(Hsla {
                                a: 0.35,
                                ..gpui::white()
                            })
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(inner),
                    ),
            )
    }

    fn ring_color_for(kind: RingKind, theme: Theme, hue: Option<usize>) -> Hsla {
        InCall::ring_color(kind, theme, hue)
    }

    fn end_button(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let ic = &self.incall;
        let danger = call_colors(theme).danger;
        let press = self.presses.value("ic-end");
        let grow = ic.end_in.value.clamp(0.0, 1.2);
        let w =
            slots::END_W * (0.35 + 0.65 * grow.min(1.0)) * press_scale(press, scale::PRESS_CARD);
        let h = slots::END_H * press_scale(press, scale::PRESS_CARD);
        let over = ic.over.value.clamp(0.0, 1.0);
        let color = mix(danger, gpui::white(), 0.18 * press.clamp(0.0, 1.0));
        div()
            .id("ic-end")
            .role(Role::Button)
            .aria_label("End call")
            .relative()
            .w(px(slots::END_W))
            .h(px(slots::END_H))
            .flex()
            .items_center()
            .justify_center()
            .opacity((1.0 - 0.5 * over) * (1.0 - f32::from(u8::from(self.flights.hides_end()))))
            .child(self.marks.probe(A_END))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, _, window, cx| this.mouse_down("ic-end", window, cx)),
            )
            .child(touch_surface(Self::touch_listener(
                cx,
                Some("ic-end".into()),
            )))
            .child(
                div()
                    .w(px(w))
                    .h(px(h))
                    .rounded_full()
                    .bg(color)
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        svg()
                            .path(Icon::LucidePhoneOff.path())
                            .size(px(30.0))
                            .text_color(gpui::white()),
                    ),
            )
    }

    /// The In-Call screen, or nothing when the tabs are fully showing.
    pub(crate) fn render_incall(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.incall.covers() {
            return None;
        }
        let fade = self.incall.fade_value.value.clamp(0.0, 1.0) * self.call_fade.value();
        let lift = self.incall.fade.offset(24.0);
        let view = self.incall.view.clone();
        let mut root = div()
            .id("incall")
            .absolute()
            .inset_0()
            .opacity(fade)
            .occlude()
            .child(rovsti_ui::components::backdrop(
                theme,
                theme::accent_glow(theme),
            ))
            .child(self.incall.aura.clone());
        let Some(view) = view else {
            return Some(root.into_any_element());
        };
        let t = self.incall.keypad_t.value.clamp(0.0, 1.0);
        let keypad_mode = t >= 0.5;
        let zone = self.render_zone(theme, &view, window, cx);
        let content = div()
            .relative()
            .top(px(lift))
            .size_full()
            .flex()
            .flex_col()
            .items_center()
            .pt(px(slots::TOP))
            .pb(px(slots::BOTTOM));
        let content = if keypad_mode {
            // The dial pad takes the grid's place; the caller block stays above it (v0.1).
            content
                .child(
                    div()
                        .relative()
                        .flex_none()
                        .w_full()
                        .h(px(slots::ONE_CHIPS_Y))
                        .child(zone),
                )
                .child(
                    div()
                        .id("ic-middle")
                        .flex_1()
                        .min_h_0()
                        .w_full()
                        .overflow_y_scroll()
                        .child(
                            div()
                                .min_h_full()
                                .flex()
                                .flex_col()
                                .justify_end()
                                .items_center()
                                .pb(px(slots::GRID_TO_END))
                                .opacity(2.0 * t - 1.0)
                                .child(self.in_call_keypad(theme, cx)),
                        ),
                )
        } else {
            // The zone is the flexible space; the grid and End below it never move.
            content
                .child(div().relative().flex_1().min_h_0().w_full().child(zone))
                .child(
                    div()
                        .id("ic-middle")
                        .flex_none()
                        .w_full()
                        .h(px(slots::GRID_H + slots::GRID_TO_END))
                        .pb(px(slots::GRID_TO_END))
                        .flex()
                        .justify_center()
                        .opacity(1.0 - 2.0 * t)
                        .child(self.controls(theme, &view, cx)),
                )
        };
        let content = content.child(self.end_button(theme, cx));
        root = root
            .child(content)
            .children(self.waiting_card(theme, window, cx));
        Some(root.into_any_element())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::{
        call::CallModel,
        wire::{CallSnapshot, WireCall, WireCallState},
    };

    #[test]
    fn digit_buffer_keeps_only_the_latest_keys() {
        let mut buf = String::new();
        for i in 0..200 {
            push_digit(&mut buf, char::from(b'0' + (i % 10) as u8));
        }
        assert_eq!(buf.len(), DIGIT_BUFFER);
        assert!(buf.ends_with("789"));
        assert_eq!(digit_line("123"), "123");
        let line = digit_line(&buf);
        assert_eq!(line.chars().count(), DIGIT_LINE);
        assert!(line.starts_with('…') && line.ends_with("789"));
    }

    #[test]
    fn long_numbers_are_cut_in_the_middle() {
        assert_eq!(
            middle_truncate("+90 555 111 22 33", 22),
            "+90 555 111 22 33"
        );
        let cut = middle_truncate("0123456789012345678901234567", 22);
        assert_eq!(cut.chars().count(), 21);
        assert!(cut.starts_with("0123456789"));
        assert!(cut.ends_with("8901234567") || cut.contains('…'));
    }

    fn model_with(state: WireCallState, name: Option<&str>, muted: bool) -> CallModel {
        let mut model = CallModel::new();
        let mut snapshot = CallSnapshot {
            keyguard_locked: false,
            ..CallSnapshot::default()
        };
        snapshot.audio.muted = muted;
        snapshot.audio.route = AudioRoute::Bluetooth;
        snapshot.audio.routes = vec![AudioRoute::Earpiece, AudioRoute::Bluetooth];
        snapshot.foreground_call_id = Some(7);
        snapshot.calls = vec![WireCall {
            id: 7,
            state,
            number_raw: "+905551112233".to_owned(),
            normalized: "905551112233".to_owned(),
            display_name: name.map(str::to_owned),
            contact_id: name.map(|_| 42),
            connect_time_ms: 5,
            can_hold: true,
            is_incoming: false,
            disconnect_cause: None,
            ..WireCall::default()
        }];
        model.apply(snapshot);
        model
    }

    #[test]
    fn a_contact_gets_a_monogram_hue_and_a_number_line() {
        let model = model_with(WireCallState::Active, Some("ada Lovelace"), true);
        let view = CallView::from_model(&model).expect("foreground call");
        assert_eq!(view.name, "ada Lovelace");
        assert_eq!(view.monogram.as_deref(), Some("A"));
        assert!(view.hue.is_some());
        assert!(view.secondary.is_some());
        assert!(view.muted);
        assert_eq!(view.route, AudioRoute::Bluetooth);
        assert_eq!(view.phase(), Phase::Active);
    }

    #[test]
    fn an_unknown_number_has_no_monogram_or_second_line() {
        let model = model_with(WireCallState::Dialing, None, false);
        let view = CallView::from_model(&model).expect("foreground call");
        assert_eq!(view.monogram, None);
        assert_eq!(view.hue, None);
        assert_eq!(view.secondary, None);
        assert_eq!(view.phase(), Phase::Dialing);
    }

    #[test]
    fn failures_are_told_apart_from_a_normal_end() {
        let mut model = model_with(WireCallState::Disconnected, None, false);
        let mut snapshot = model.snapshot().clone();
        snapshot.seq = 1;
        snapshot.calls[0].disconnect_cause = Some(DisconnectCause::Busy);
        model.apply(snapshot);
        assert_eq!(
            CallView::from_model(&model).expect("call").phase(),
            Phase::Failed
        );
        let remote = model_with(WireCallState::Disconnected, None, false);
        assert_eq!(
            CallView::from_model(&remote).expect("call").phase(),
            Phase::Ended
        );
    }

    #[test]
    fn status_kinds_follow_the_state() {
        assert!(CallStatus::kind_of(CallState::Dialing) == Headline::Calling);
        assert!(CallStatus::kind_of(CallState::Active) == Headline::Timer);
        assert!(CallStatus::kind_of(CallState::Holding) == Headline::Hold);
        assert!(
            CallStatus::kind_of(CallState::Disconnected {
                cause: DisconnectCause::Local
            }) == Headline::Over
        );
    }

    #[test]
    fn timer_ticks_on_the_second() {
        let wait = ms_to_next_second(now_ms() - 400);
        assert!((500..=700).contains(&wait), "{wait}");
        assert_eq!(ms_to_next_second(0), 1000);
    }
}
