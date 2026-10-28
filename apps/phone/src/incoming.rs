//! The Incoming Call screen (stories 5.2 to 5.5): who is calling, Decline and
//! Answer, and "Missed call from ..." when the caller gives up.
//!
//! It is solid and light on purpose (DESIGN.md Performance Budget): the wash,
//! the caller's aura, plain circles. The looping parts (sonar rings, the
//! breathing avatar, the Answer glow) live in their own view, [`RingFx`], so
//! their per-frame repaint never re-renders this screen.
//!
//! Answer and Decline act when the finger lifts (a stray touch must not answer
//! a call), but press feedback runs on the raw finger-down. Neither waits for
//! an animation: the command is issued first, the animation runs alongside.

use std::{f32::consts::TAU, time::Duration};

use gpui::{
    AnyElement, Bounds, Context, Corners, Edges, Entity, HapticFeedback, Hsla, InteractiveElement,
    IntoElement, MouseButton, ParentElement, Render, Role, SharedString,
    StatefulInteractiveElement, Styled, Task, Window, canvas, div, linear_color_stop,
    linear_gradient, point, prelude::*, px, size, svg,
};
use rovsti_core::{
    call::{CallState, incoming_announcement, is_missed, missed_headline},
    wire::DisconnectCause,
};
use rovsti_ui::moments::Fade;
use rovsti_ui::{
    Icon,
    components::backdrop,
    current_theme,
    motion::{Entrance, Motion, press_scale},
    motion_tokens::{self, scale},
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, mix, typography},
    touch::touch_surface,
};

use crate::{
    PhoneApp,
    aura::{Aura, AuraState, Phase},
    backend::{self, CallCommand},
    flight::{ButtonFrom, Look},
    fx::{A_ANSWER, A_INCOMING_AVATAR, Marks},
    incall::CallView,
};

/// The "Missed call from ..." line is held this long before the screen leaves.
const MISSED_HOLD: Duration = Duration::from_millis(1200);
/// Decline then Answer spring in this far apart.
const STAGGER: Duration = Duration::from_millis(80);

const LABEL_TOP: f32 = 84.0;
const LABEL_H: f32 = 22.0;
const AVATAR: f32 = 120.0;
/// Top of the avatar: label, then a 20 px gap.
const AVATAR_TOP: f32 = LABEL_TOP + LABEL_H + 20.0;
const BUTTON: f32 = 76.0;
const BUTTON_GAP: f32 = 96.0;
const CAPTION_H: f32 = 24.0;
const BOTTOM_PAD: f32 = 56.0;
/// Seconds after which every loop (1.6 s rings, 2 s breath, 1.2 s glow) lines up again.
const WRAP: f32 = 24.0;
const RING_PERIOD: f32 = 1.6;
const GLOW_PERIOD: f32 = 1.2;

fn sp(value: f32, params: rovsti_ui::spring::SpringParams) -> Spring {
    Spring::new(value, params).with_epsilon(0.002)
}

// ---- The looping parts ---------------------------------------------------------

/// What the avatar looks like: the monogram on the contact's hue pair.
#[derive(Clone, Debug, Default, PartialEq)]
struct AvatarSpec {
    monogram: Option<SharedString>,
    hue: Option<usize>,
}

/// Sonar rings, the breathing avatar and the Answer glow (signature moment S7).
/// Its own view; it asks for frames only while the call rings.
pub struct RingFx {
    motion: Motion,
    clock: f32,
    running: bool,
    reduced: bool,
    spec: AvatarSpec,
    /// Vertical offset of the avatar: drops in from -40 and settles with a visible overshoot.
    drop: Spring,
    /// Master alpha of the loops: 1 while ringing, back to 0 once it stops.
    live: Spring,
    /// S8: the avatar copy flying to the In-Call slot replaces this one.
    avatar_hidden: bool,
    marks: Marks,
}

impl RingFx {
    fn new(marks: Marks) -> Self {
        Self {
            avatar_hidden: false,
            marks,
            motion: Motion::default(),
            clock: 0.0,
            running: false,
            reduced: false,
            spec: AvatarSpec::default(),
            drop: sp(0.0, motion_tokens::release()),
            live: sp(0.0, motion_tokens::appear()),
        }
    }

    /// A new call rings: drop the avatar in and start the loops.
    fn start(&mut self, spec: AvatarSpec, reduced: bool, cx: &mut Context<Self>) {
        self.spec = spec;
        self.reduced = reduced;
        self.avatar_hidden = false;
        self.running = true;
        self.clock = 0.0;
        if reduced {
            self.drop.snap(0.0);
        } else {
            self.drop.snap(-40.0);
            self.drop.set_target(0.0);
        }
        self.live.snap(0.0);
        self.live.set_target(1.0);
        cx.notify();
    }

    /// The avatar is flying to the In-Call screen: stop drawing this one.
    pub(crate) fn hide_avatar(&mut self, cx: &mut Context<Self>) {
        self.avatar_hidden = true;
        cx.notify();
    }

    /// The caller's name resolved or the screen came back: new look, no re-drop.
    fn restyle(&mut self, spec: AvatarSpec, cx: &mut Context<Self>) {
        if self.spec != spec {
            self.spec = spec;
            cx.notify();
        }
    }

    /// Answered, declined or the caller gave up: the loops fade out and stop.
    fn stop(&mut self, cx: &mut Context<Self>) {
        self.running = false;
        self.live.set_target(0.0);
        cx.notify();
    }

    fn tick(&mut self, dt: Duration, loops: bool) -> bool {
        let mut moving = self.drop.tick(dt);
        moving |= self.live.tick(dt);
        if loops && self.running {
            self.clock = (self.clock + dt.as_secs_f32()) % WRAP;
            moving = true;
        }
        moving
    }
}

/// Scale and alpha of one sonar ring `phase` seconds behind the first.
fn ring_state(clock: f32, phase: f32) -> (f32, f32) {
    let p = ((clock + RING_PERIOD - phase) % RING_PERIOD) / RING_PERIOD;
    (1.0 + 0.9 * p, 0.35 * (1.0 - p))
}

/// The avatar breathes 1.0 to 1.04 at 0.5 Hz.
fn breath(clock: f32) -> f32 {
    1.0 + 0.02 * (1.0 - (TAU * 0.5 * clock).cos())
}

/// The Answer glow pulses between alpha 0 and 0.25 every 1.2 s.
fn glow_alpha(clock: f32) -> f32 {
    0.125 * (1.0 - (TAU * clock / GLOW_PERIOD).cos())
}

impl Render for RingFx {
    fn render(&mut self, window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let loops = !window.prefers_reduced_motion() && window.is_window_active();
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame(window, |dt| self.tick(dt, loops));
        self.motion = motion;

        let live = self.live.value.clamp(0.0, 1.0);
        let still = !loops || self.reduced;
        let colors = theme::colors(theme);
        let (top, bottom) = theme::hue_pair(theme, self.spec.hue);
        let answer = call_colors(theme).answer;
        let clock = self.clock;
        let size_scale = if still { 1.0 } else { breath(clock) };

        let canvas = canvas(
            |_, _, _| (),
            move |bounds, (), window, _| {
                let cx = bounds.origin.x + bounds.size.width * 0.5;
                let cy = bounds.origin.y + px(AVATAR_TOP + AVATAR * 0.5);
                if still {
                    // One static ring.
                    ring(window, cx, cy, 1.25, 0.2 * live, top);
                } else {
                    for phase in [0.0, 0.8] {
                        let (scale, alpha) = ring_state(clock, phase);
                        ring(window, cx, cy, scale, alpha * live, top);
                    }
                    // The glow behind Answer.
                    let ax = cx + px(BUTTON_GAP * 0.5 + BUTTON * 0.5);
                    let ay = bounds.origin.y + bounds.size.height
                        - px(BOTTOM_PAD + CAPTION_H + BUTTON * 0.5);
                    let d = px(BUTTON * 1.9);
                    let alpha = glow_alpha(clock) * live;
                    let glow = Bounds::new(point(ax - d * 0.5, ay - d * 0.5), size(d, d));
                    window.paint_quad(gpui::fill(
                        glow,
                        linear_gradient(
                            0.0,
                            linear_color_stop(Hsla { a: alpha, ..answer }, 0.0),
                            linear_color_stop(Hsla { a: 0.0, ..answer }, 1.0),
                        )
                        .gradient_kind(gpui::GradientKind::Radial),
                    ));
                }
            },
        )
        .absolute()
        .size_full();

        let fill = if self.spec.hue.is_some() {
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
        let inner = match &self.spec.monogram {
            Some(letter) => div()
                .text_size(px(52.0))
                .font_weight(typography::HEADLINE.weight)
                .text_color(gpui::white())
                .child(letter.clone())
                .into_any_element(),
            None => svg()
                .path(Icon::LucideUser.path())
                .size(px(54.0))
                .text_color(colors.secondary_label)
                .into_any_element(),
        };
        let side = AVATAR * size_scale;
        let marks = self.marks.clone();
        div().absolute().size_full().child(canvas).child(
            div()
                .absolute()
                .top(px(AVATAR_TOP + self.drop.value))
                .w_full()
                .h(px(AVATAR))
                .opacity(1.0 - f32::from(u8::from(self.avatar_hidden)))
                .flex()
                .items_center()
                .justify_center()
                .child(
                    div()
                        .relative()
                        .size(px(side))
                        .rounded_full()
                        .child(marks.probe(A_INCOMING_AVATAR))
                        .border_3()
                        .border_color(Hsla {
                            a: 0.6,
                            ..theme::accent_glow(theme)
                        })
                        .p(px(3.0))
                        .child(
                            div()
                                .size_full()
                                .rounded_full()
                                .bg(fill)
                                .border(px(1.5))
                                .border_color(Hsla {
                                    a: 0.35,
                                    ..gpui::white()
                                })
                                .flex()
                                .items_center()
                                .justify_center()
                                .child(inner),
                        ),
                ),
        )
    }
}

/// One 3 px sonar ring around the avatar.
fn ring(
    window: &mut Window,
    cx: gpui::Pixels,
    cy: gpui::Pixels,
    scale: f32,
    alpha: f32,
    color: Hsla,
) {
    if alpha <= 0.002 {
        return;
    }
    let d = px(AVATAR * scale);
    let bounds = Bounds::new(point(cx - d * 0.5, cy - d * 0.5), size(d, d));
    window.paint_quad(gpui::quad(
        bounds,
        Corners::all(d * 0.5),
        gpui::transparent_black(),
        Edges::all(px(3.0)),
        Edges::all(Hsla { a: alpha, ..color }),
        gpui::BorderStyle::Solid,
    ));
}

// ---- Screen state ----------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RingState {
    /// Ringing: Answer and Decline are live.
    Ringing,
    /// The caller gave up: "Missed call from ..." holds, then the screen leaves.
    Missed,
    /// Answered or declined: fading out.
    Leaving,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Act {
    Answer,
    Decline,
}

pub struct Incoming {
    /// The screen the owner should see right now.
    pub shown: bool,
    view: Option<CallView>,
    state: RingState,
    acted: Option<Act>,
    locked: bool,
    began_locked: bool,
    /// The call whose first frame was reported to Kotlin (ring-latency log).
    reported: Option<i32>,
    missed_text: SharedString,
    announce: SharedString,
    stagger: Option<Duration>,
    pub(crate) fx: Entity<RingFx>,
    aura: Entity<Aura>,
    _leave: Option<Task<()>>,

    fade: Spring,
    enter: Entrance,
    /// Decline, Answer: spring in from 0.8.
    intro: [Spring; 2],
    /// Decline, Answer: 1 hides the button (the other one was chosen, or the call is over).
    hidden: [Spring; 2],
    /// 0 = "Incoming call", 1 = "Missed call from ...".
    swap: Spring,
    /// Reduced motion: the 120 ms fade that brings both buttons in.
    buttons_fade: Fade,
}

impl Incoming {
    pub fn new(cx: &mut gpui::App, marks: Marks) -> Self {
        Self {
            shown: false,
            view: None,
            state: RingState::Ringing,
            acted: None,
            locked: false,
            began_locked: false,
            reported: None,
            missed_text: SharedString::default(),
            announce: SharedString::default(),
            stagger: None,
            fx: cx.new(|_| RingFx::new(marks)),
            aura: cx.new(|_| Aura::new().centered_at(AVATAR_TOP + AVATAR * 0.5)),
            _leave: None,
            fade: sp(0.0, motion_tokens::appear()),
            enter: Entrance::default(),
            intro: [
                sp(1.0, motion_tokens::select()),
                sp(1.0, motion_tokens::select()),
            ],
            hidden: [
                sp(0.0, motion_tokens::appear()),
                sp(0.0, motion_tokens::appear()),
            ],
            swap: sp(0.0, motion_tokens::appear()),
            buttons_fade: Fade::default(),
        }
    }

    /// Advances the reduced-motion button fade by real time.
    pub fn tick_real(&mut self, real: Duration) -> bool {
        self.buttons_fade.tick(real)
    }

    /// The screen covers the tabs (fully, or fading).
    pub fn covers(&self) -> bool {
        self.shown || self.fade.value > 0.001
    }

    /// The tabs are completely hidden, so they need not be built.
    pub fn hides_tabs(&self) -> bool {
        self.fade.value > 0.999
    }

    /// Back does nothing while the screen is up (it must not look like a decline).
    pub fn wants_back(&self) -> bool {
        self.covers()
    }

    pub fn call_id(&self) -> Option<i32> {
        self.view.as_ref().map(|v| v.id)
    }

    pub fn is_ringing(&self) -> bool {
        self.state == RingState::Ringing && self.shown
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.fade.tick(dt);
        moving |= self.enter.tick(dt);
        moving |= self.swap.tick(dt);
        for s in self.intro.iter_mut().chain(self.hidden.iter_mut()) {
            moving |= s.tick(dt);
        }
        if let Some(elapsed) = &mut self.stagger {
            *elapsed += dt;
            moving = true;
            if *elapsed >= STAGGER {
                self.stagger = None;
                self.intro[1].set_target(1.0);
            }
        }
        moving
    }

    fn avatar_spec(view: &CallView) -> AvatarSpec {
        AvatarSpec {
            monogram: view.monogram.clone(),
            hue: view.hue,
        }
    }

    fn aura_state(&self, phase: Phase) -> AuraState {
        AuraState {
            phase,
            hue: self.view.as_ref().and_then(|v| v.hue),
            muted: false,
            speaker: false,
        }
    }
}

// ---- Behaviour on PhoneApp ----------------------------------------------------------

impl PhoneApp {
    /// Decides who owns `view`: the Incoming Call screen while the call rings and when it ends
    /// unanswered, the In-Call screen from the moment it is answered. Returns the view when the
    /// In-Call screen should take it.
    pub(crate) fn route_incoming(
        &mut self,
        view: CallView,
        locked: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Option<CallView> {
        let owns = self.incoming.call_id() == Some(view.id);
        match view.state {
            CallState::Ringing => {
                self.incoming_ring(view, locked, window, cx);
                None
            }
            CallState::Disconnected { cause }
                if view.incoming && self.incall.last_id != Some(view.id) =>
            {
                // It never reached the In-Call screen: it ended while ringing. The In-Call
                // screen must not flash "Call ended" for it.
                self.incall.done_id = Some(view.id);
                if owns {
                    self.incoming_ended(cause, cx);
                } else if locked {
                    // Nobody saw it ring (the app started after it ended): hand the lock screen back.
                    self.incall.blank = true;
                    backend::move_task_to_back();
                }
                None
            }
            _ => {
                if owns && self.incoming.shown {
                    self.incoming_answered(cx);
                }
                Some(view)
            }
        }
    }

    /// A call is ringing (or its caller's name just resolved).
    pub(crate) fn incoming_ring(
        &mut self,
        view: CallView,
        locked: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let reduced = window.prefers_reduced_motion();
        let inc = &mut self.incoming;
        inc.locked = locked;
        let is_new = inc.call_id() != Some(view.id) || !inc.shown;
        if is_new {
            inc.began_locked = locked;
            inc.state = RingState::Ringing;
            inc.acted = None;
            inc._leave = None;
            inc.announce = incoming_announcement(&view.name).into();
            inc.missed_text = missed_headline(&view.name).into();
            // First frame: the wash and the name are already there (never blank).
            inc.fade.snap(1.0);
            inc.fade.set_target(1.0);
            inc.shown = true;
            inc.swap.snap(0.0);
            inc.hidden[0].snap(0.0);
            inc.hidden[1].snap(0.0);
            inc.enter.restart();
            if reduced {
                inc.enter = Entrance::default();
                // Static blobs and ring; both buttons fade in together over 120 ms.
                inc.intro[0].snap(1.0);
                inc.intro[1].snap(1.0);
                inc.stagger = None;
                inc.buttons_fade.restart();
            } else {
                // Decline springs in now, Answer 80 ms later (both tappable from the first frame).
                inc.intro[0].snap(0.8);
                inc.intro[0].set_target(1.0);
                inc.intro[1].snap(0.8);
                inc.stagger = Some(Duration::ZERO);
            }
            let spec = Incoming::avatar_spec(&view);
            inc.view = Some(view);
            inc.fx.update(cx, |fx, cx| fx.start(spec, reduced, cx));
            let aura = inc.aura_state(Phase::Dialing);
            inc.aura.update(cx, |a, cx| {
                a.restart(reduced, cx);
                a.set(aura, cx);
            });
        } else if inc.view.as_ref() != Some(&view) {
            // The name resolved: restyle in place; nothing moves.
            inc.announce = incoming_announcement(&view.name).into();
            inc.missed_text = missed_headline(&view.name).into();
            let spec = Incoming::avatar_spec(&view);
            inc.view = Some(view);
            inc.fx.update(cx, |fx, cx| fx.restyle(spec, cx));
            let aura = inc.aura_state(Phase::Dialing);
            inc.aura.update(cx, |a, cx| a.set(aura, cx));
        }
        cx.notify();
    }

    /// The call the screen shows was answered (here, from the notification, or by a headset):
    /// the In-Call screen takes over and this one fades out beneath it.
    pub(crate) fn incoming_answered(&mut self, cx: &mut Context<Self>) {
        let inc = &mut self.incoming;
        if !inc.shown {
            return;
        }
        inc.state = RingState::Leaving;
        inc.shown = false;
        inc.fade.set_target(0.0);
        inc.fx.update(cx, |fx, cx| fx.stop(cx));
        cx.notify();
    }

    /// The ringing call ended without being answered. Missed: say so for 1.2 s, then leave.
    /// Declined or taken elsewhere: leave at once.
    pub(crate) fn incoming_ended(&mut self, cause: DisconnectCause, cx: &mut Context<Self>) {
        let inc = &mut self.incoming;
        if !inc.shown || inc.state != RingState::Ringing {
            return;
        }
        inc.fx.update(cx, |fx, cx| fx.stop(cx));
        inc.aura.update(cx, |a, cx| {
            a.set(
                AuraState {
                    phase: Phase::Ended,
                    ..AuraState::default()
                },
                cx,
            );
        });
        if is_missed(cause) && inc.acted.is_none() {
            inc.state = RingState::Missed;
            inc.swap.set_target(1.0);
            inc.hidden[0].set_target(1.0);
            inc.hidden[1].set_target(1.0);
            inc._leave = Some(cx.spawn(async move |this, cx| {
                cx.background_executor().timer(MISSED_HOLD).await;
                this.update(cx, |this, cx| this.incoming_leave(cx)).ok();
            }));
            cx.notify();
        } else {
            inc.state = RingState::Leaving;
            self.incoming_leave(cx);
        }
    }

    /// The screen is done: back to the tabs, or hand the screen back to the lock screen
    /// when the call arrived locked (AD-3).
    fn incoming_leave(&mut self, cx: &mut Context<Self>) {
        let inc = &mut self.incoming;
        inc.shown = false;
        inc._leave = None;
        inc.fade.set_target(0.0);
        let others = self.backend.read(cx).calls().has_calls();
        if self.incoming.began_locked && !others {
            self.incall.blank = true;
            backend::move_task_to_back();
        }
        self.presses.release_all();
        cx.notify();
    }

    /// Answer or Decline, once the finger lifts (or the mouse is pressed on desktop).
    pub(crate) fn incoming_mouse_down(
        &mut self,
        id: &'static str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !self.presses.touch_seen() {
            self.presses.down(id);
        }
        self.incoming_activate(id, window, cx);
    }

    fn incoming_activate(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        let inc = &self.incoming;
        let Some(call_id) = inc.call_id() else {
            return;
        };
        if !inc.is_ringing() || inc.acted.is_some() {
            return;
        }
        let (act, command) = match id {
            "in-answer" => (Act::Answer, CallCommand::Answer(call_id)),
            "in-decline" => (Act::Decline, CallCommand::Reject(call_id)),
            _ => return,
        };
        // The command first; the animation only runs beside it.
        let outcome = self
            .backend
            .update(cx, |backend, cx| backend.send(command, cx));
        window.perform_haptic_feedback(match act {
            Act::Answer => HapticFeedback::Confirm,
            Act::Decline => HapticFeedback::Selection,
        });
        if outcome != backend::Outcome::Declined {
            let inc = &mut self.incoming;
            inc.acted = Some(act);
            // The other button steps aside (S8: Decline fades while Answer becomes End).
            let other = match act {
                Act::Answer => 0,
                Act::Decline => 1,
            };
            inc.hidden[other].set_target(1.0);
            if act == Act::Answer {
                // S8: the Answer circle becomes End and the avatar flies to its slot.
                let look = inc.view.as_ref().map(|v| Look {
                    hue: v.hue,
                    monogram: v.monogram.clone(),
                    photo: None,
                });
                self.begin_button_flight(ButtonFrom::Answer, window);
                if let Some(look) = look {
                    self.begin_answer_avatar(look, window);
                }
            }
        }
        cx.notify();
    }

    fn ring_button(
        &self,
        theme: Theme,
        id: &'static str,
        icon: Icon,
        caption: &'static str,
        slot: usize,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let colors = theme::colors(theme);
        let calls = call_colors(theme);
        let inc = &self.incoming;
        let held_on = slot == 1 && self.flights.button_active(ButtonFrom::Answer);
        let press = if held_on {
            1.0
        } else {
            self.presses.value(id).clamp(0.0, 1.2)
        };
        let intro = inc.intro[slot].value.clamp(0.0, 1.2);
        let hidden = inc.hidden[slot].value.clamp(0.0, 1.0);
        let live = inc.is_ringing() && inc.acted.is_none() && hidden < 0.5;
        let side = BUTTON * intro * press_scale(press, scale::PRESS_CARD);
        let base = if slot == 0 {
            calls.danger
        } else {
            calls.answer
        };
        let fill = mix(base, gpui::white(), 0.18 * press.min(1.0));
        // DESIGN.md: white glyph in light mode; black on the bright Answer green in dark mode.
        let glyph = if slot == 1 && theme.is_dark() {
            gpui::black()
        } else {
            gpui::white()
        };
        let flying = slot == 1 && self.flights.button_flying(ButtonFrom::Answer);
        let mut slot_el = div()
            .id(id)
            .role(Role::Button)
            .aria_label(caption)
            .w(px(BUTTON))
            .flex()
            .flex_col()
            .items_center()
            .opacity(
                intro.min(1.0)
                    * inc.buttons_fade.value()
                    * (1.0 - hidden)
                    * (1.0 - f32::from(u8::from(flying))),
            );
        if live {
            slot_el = slot_el
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.incoming_mouse_down(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))));
        }
        slot_el
            .child(
                div()
                    .relative()
                    .size(px(BUTTON))
                    .flex()
                    .items_center()
                    .justify_center()
                    .children((slot == 1).then(|| self.marks.probe(A_ANSWER)))
                    .child(
                        div()
                            .size(px(side))
                            .rounded_full()
                            .bg(fill)
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(svg().path(icon.path()).size(px(30.0)).text_color(glyph)),
                    ),
            )
            .child(
                div()
                    .mt(px(8.0))
                    .h(px(CAPTION_H - 8.0))
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.label)
                    .child(caption),
            )
    }

    /// The Incoming Call screen, or nothing when the tabs are fully showing.
    pub(crate) fn render_incoming(
        &mut self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.incoming.covers() {
            return None;
        }
        let colors = theme::colors(theme);
        let calls = call_colors(theme);
        let view = self.incoming.view.clone();
        let fade = self.incoming.fade.value.clamp(0.0, 1.0);
        let root = div()
            .id("incoming")
            .absolute()
            .inset_0()
            .opacity(fade)
            .occlude()
            .child(backdrop(theme, theme::accent_glow(theme)))
            .child(self.incoming.aura.clone())
            .child(self.incoming.fx.clone());
        let Some(view) = view else {
            return Some(root.into_any_element());
        };
        // The first frame of this call's screen is on its way: tell Kotlin (ring-latency log).
        if self.incoming.reported != Some(view.id) {
            self.incoming.reported = Some(view.id);
            backend::incoming_frame(view.id);
        }

        let inc = &self.incoming;
        let swap = inc.swap.value.clamp(0.0, 1.0);
        // The name is visible from the first frame; it only drifts the last few pixels.
        let text_alpha = 0.4 + 0.6 * inc.enter.progress();
        let lift = inc.enter.offset(12.0);
        let label = |child: AnyElement, opacity: f32, offset: f32| {
            div()
                .absolute()
                .top(px(offset))
                .w_full()
                .h(px(LABEL_H))
                .flex()
                .items_center()
                .justify_center()
                .opacity(opacity)
                .child(child)
        };
        let incoming_label = div()
            .flex()
            .items_center()
            .gap(px(5.0))
            .text_size(typography::FOOTNOTE.size)
            .text_color(colors.secondary_label)
            .children(inc.locked.then(|| {
                svg()
                    .path(Icon::LucideLock.path())
                    .size(px(12.0))
                    .text_color(colors.secondary_label)
            }))
            .child("Incoming call")
            .into_any_element();
        let missed_label = div()
            .text_size(typography::HEADLINE.size)
            .font_weight(typography::HEADLINE.weight)
            .text_color(calls.danger)
            .child(inc.missed_text.clone())
            .into_any_element();

        let content = div()
            .relative()
            .size_full()
            .flex()
            .flex_col()
            .items_center()
            .pt(px(LABEL_TOP))
            .pb(px(BOTTOM_PAD))
            .child(
                div()
                    .relative()
                    .w_full()
                    .h(px(LABEL_H))
                    .child(label(incoming_label, 1.0 - swap, -6.0 * swap))
                    .child(label(missed_label, swap, 6.0 * (1.0 - swap))),
            )
            // The avatar is drawn by `RingFx` in this gap.
            .child(div().h(px(AVATAR + 20.0 + 20.0)))
            .child(
                div()
                    .id("in-caller")
                    .role(Role::Alert)
                    .aria_label(inc.announce.clone())
                    .relative()
                    .top(px(lift))
                    .opacity(text_alpha * (1.0 - 0.5 * swap))
                    .w_full()
                    .flex()
                    .flex_col()
                    .items_center()
                    .child(
                        div()
                            .max_w(px(340.0))
                            .px(px(16.0))
                            .h(px(34.0))
                            .text_size(px(28.0))
                            .font_weight(gpui::FontWeight(700.0))
                            .text_color(colors.label)
                            .truncate()
                            .child(view.name.clone()),
                    )
                    .child(
                        div()
                            .h(px(20.0))
                            .text_size(typography::SUBHEADLINE.size)
                            .text_color(colors.secondary_label)
                            .children(view.secondary.clone()),
                    ),
            )
            .child(div().flex_1())
            // Answer is first in reading order (TalkBack), Decline sits on the left.
            .child(
                div()
                    .flex()
                    .flex_row_reverse()
                    .gap(px(BUTTON_GAP))
                    .child(self.ring_button(theme, "in-answer", Icon::LucidePhone, "Answer", 1, cx))
                    .child(self.ring_button(
                        theme,
                        "in-decline",
                        Icon::LucidePhoneOff,
                        "Decline",
                        0,
                        cx,
                    )),
            );
        Some(root.child(content).into_any_element())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rings_expand_and_fade_over_one_period() {
        let (start_scale, start_alpha) = ring_state(0.0, 0.0);
        assert!((start_scale - 1.0).abs() < 1e-4);
        assert!((start_alpha - 0.35).abs() < 1e-4);
        let (mid_scale, mid_alpha) = ring_state(0.8, 0.0);
        assert!((mid_scale - 1.45).abs() < 1e-3);
        assert!((mid_alpha - 0.175).abs() < 1e-3);
        // The second ring runs 0.8 s behind the first.
        let (late_scale, _) = ring_state(0.8, 0.8);
        assert!((late_scale - 1.0).abs() < 1e-4);
    }

    #[test]
    fn rings_never_leave_their_range() {
        let mut clock = 0.0;
        while clock < WRAP {
            for phase in [0.0, 0.8] {
                let (scale, alpha) = ring_state(clock, phase);
                assert!((1.0..=1.9).contains(&scale), "{scale}");
                assert!((0.0..=0.35).contains(&alpha), "{alpha}");
            }
            clock += 0.037;
        }
    }

    #[test]
    fn the_avatar_breathes_between_one_and_one_oh_four() {
        assert!((breath(0.0) - 1.0).abs() < 1e-5);
        assert!((breath(1.0) - 1.04).abs() < 1e-5);
        assert!((breath(2.0) - 1.0).abs() < 1e-5);
    }

    #[test]
    fn the_answer_glow_pulses_up_to_a_quarter() {
        assert!(glow_alpha(0.0).abs() < 1e-5);
        assert!((glow_alpha(0.6) - 0.25).abs() < 1e-5);
    }

    #[test]
    fn every_loop_lines_up_at_the_wrap() {
        for period in [RING_PERIOD, 2.0, GLOW_PERIOD] {
            let turns = WRAP / period;
            assert!((turns - turns.round()).abs() < 1e-4, "{period}");
        }
    }
}
