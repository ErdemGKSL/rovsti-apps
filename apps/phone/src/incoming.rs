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

use std::{cell::Cell, f32::consts::TAU, rc::Rc, sync::Arc, time::Duration, time::Instant};

use gpui::{
    AnyElement, Bounds, Context, Corners, Edges, Entity, HapticFeedback, Hsla, InteractiveElement,
    IntoElement, MouseButton, ObjectFit, ParentElement, Render, RenderImage, Role, SharedString,
    StatefulInteractiveElement, Styled, StyledImage, Task, Window, canvas, div, linear_color_stop,
    linear_gradient, point, prelude::*, px, size, svg,
};
use rovsti_core::{
    call::{CallState, incoming_announcement, is_missed, missed_headline},
    gesture::{self, Dir, Release, Tick},
    wire::{DisconnectCause, SilencedBy},
};
use rovsti_ui::moments::Fade;
use rovsti_ui::{
    Icon,
    components::{backdrop, flat_glass},
    current_theme,
    motion::{Entrance, Motion, press_scale},
    motion_tokens::{self, scale},
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, mix, typography},
    touch::{TapGuard, drag_surface, touch_surface},
};

use crate::{
    PhoneApp,
    aura::{Aura, AuraState, Phase},
    backend::{self, CallCommand},
    flight::{ButtonFrom, Look},
    fx::{A_ANSWER, A_INCOMING_AVATAR, Marks},
    incall::{CallSource, CallView},
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
/// The secondary row (Message, Silence): 56 px circles 24 px apart, captions under them.
const SECONDARY: f32 = 56.0;
const SECONDARY_GAP: f32 = 24.0;
const SECONDARY_H: f32 = SECONDARY + 24.0;
/// Space between the secondary row and Decline/Answer.
const SECONDARY_LIFT: f32 = 28.0;
/// The drag surface covers the card: avatar, name and number.
const CARD_TOP: f32 = AVATAR_TOP - 24.0;
const CARD_H: f32 = AVATAR + 24.0 + 24.0 + 34.0 + 20.0 + 20.0;
/// How many incoming calls show the drag hint.
const HINT_CALLS: u32 = 3;
const HINT_KEY: &str = "incoming_drag";
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
    /// S30: 0 ringing, 1 hushed (rings, breath and glow ease to rest).
    calm: Spring,
    /// The caller's picture and its opacity, written by the screen before each
    /// frame (this view renders in the same frame, so no notify is needed).
    photo: Option<(Arc<RenderImage>, f32)>,
    /// Where the card is dragged to (S29); written by the screen each frame, read here.
    offset: Rc<Cell<(f32, f32, f32)>>,
}

impl RingFx {
    fn new(marks: Marks, offset: Rc<Cell<(f32, f32, f32)>>) -> Self {
        Self {
            avatar_hidden: false,
            marks,
            calm: sp(0.0, motion_tokens::select()),
            photo: None,
            offset,
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
        self.calm.snap(0.0);
        cx.notify();
    }

    /// S30: the ringer was silenced (or the call rings again).
    fn hush(&mut self, hushed: bool, reduced: bool, cx: &mut Context<Self>) {
        if reduced {
            self.calm.snap(f32::from(u8::from(hushed)));
        } else {
            self.calm.set_target(f32::from(u8::from(hushed)));
        }
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
        moving |= self.calm.tick(dt);
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
        let calm = self.calm.value.clamp(0.0, 1.0);
        let rest = 1.0 - calm;
        let (off_x, off_y, card_alpha) = self.offset.get();
        let size_scale = if still {
            1.0
        } else {
            1.0 + (breath(clock) - 1.0) * rest
        };

        let canvas = canvas(
            |_, _, _| (),
            move |bounds, (), window, _| {
                let cx = bounds.origin.x + bounds.size.width * 0.5 + px(off_x);
                let cy = bounds.origin.y + px(AVATAR_TOP + AVATAR * 0.5 + off_y);
                if still {
                    // One static ring.
                    ring(window, cx, cy, 1.25, 0.2 * live * rest, top);
                } else {
                    for phase in [0.0, 0.8] {
                        let (scale, alpha) = ring_state(clock, phase);
                        ring(window, cx, cy, scale, alpha * live * rest, top);
                    }
                    // The glow behind Answer.
                    let ax = bounds.origin.x
                        + bounds.size.width * 0.5
                        + px(BUTTON_GAP * 0.5 + BUTTON * 0.5);
                    let ay = bounds.origin.y + bounds.size.height
                        - px(BOTTOM_PAD + CAPTION_H + BUTTON * 0.5);
                    let d = px(BUTTON * 1.9);
                    let alpha = glow_alpha(clock) * live * rest;
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
                .top(px(AVATAR_TOP + self.drop.value + off_y))
                .left(px(off_x))
                .w_full()
                .h(px(AVATAR))
                .opacity(
                    (1.0 - f32::from(u8::from(self.avatar_hidden))) * card_alpha.clamp(0.0, 1.0),
                )
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
                                .relative()
                                .flex()
                                .items_center()
                                .justify_center()
                                .child(inner)
                                .children(self.photo.clone().map(|(image, opacity)| {
                                    gpui::img(image)
                                        .absolute()
                                        .inset_0()
                                        .rounded_full()
                                        .object_fit(ObjectFit::Cover)
                                        .opacity(opacity)
                                })),
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
    /// "Sent", "Opened Messages" or "Declined": held 1.2 s in the label's place (S31).
    reply_label: Option<SharedString>,
    /// The held line is a reply result, not a missed call: it is not drawn in the danger colour.
    hold_reply: bool,
    /// Who silenced the ringer, from the snapshot (S30).
    silenced: Option<SilencedBy>,
    /// 0 ringing, 1 hushed: the Silence control's accent flood.
    hush: Spring,
    /// The card drag (S29): the finger's rules, the follow it produced and the
    /// springs that carry the card back (or away).
    drag: Option<gesture::Drag>,
    off_x: Spring,
    off_y: Spring,
    card_alpha: Spring,
    offset: Rc<Cell<(f32, f32, f32)>>,
    epoch: Instant,
    /// Every finger on the screen: a drag or a flick is never a tap on Answer or Decline,
    /// whatever the host synthesises when it lifts.
    tap: TapGuard,
    /// The first calls show "Drag the card to answer or decline".
    hint: bool,
}

impl Incoming {
    pub fn new(cx: &mut gpui::App, marks: Marks) -> Self {
        let offset = Rc::new(Cell::new((0.0, 0.0, 1.0)));
        Self {
            reply_label: None,
            hold_reply: false,
            silenced: None,
            hush: sp(0.0, motion_tokens::select()),
            drag: None,
            off_x: sp(0.0, motion_tokens::release()),
            off_y: sp(0.0, motion_tokens::release()),
            card_alpha: sp(1.0, motion_tokens::sheet_dismiss()),
            epoch: Instant::now(),
            tap: TapGuard::default(),
            hint: false,
            offset: Rc::clone(&offset),
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
            fx: cx.new(|_| RingFx::new(marks, offset)),
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
        self.view.as_ref().and_then(|v| v.source.telecom_id())
    }

    /// Which call the screen shows, whichever stack it comes from.
    pub(crate) fn source(&self) -> Option<&CallSource> {
        self.view.as_ref().map(|v| &v.source)
    }

    pub fn is_ringing(&self) -> bool {
        self.state == RingState::Ringing && self.shown
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.fade.tick(dt);
        moving |= self.enter.tick(dt);
        moving |= self.swap.tick(dt);
        moving |= self.hush.tick(dt);
        moving |= self.off_x.tick(dt);
        moving |= self.off_y.tick(dt);
        moving |= self.card_alpha.tick(dt);
        self.offset
            .set((self.off_x.value, self.off_y.value, self.card_alpha.value));
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
            hushed: self.silenced.is_some(),
        }
    }

    /// Desktop preview: where the previewed finger is now.
    #[cfg(not(target_os = "android"))]
    pub(crate) fn drag_point(&self) -> (f32, f32) {
        (
            200.0 + self.off_x.target / gesture::GAIN,
            260.0 + self.off_y.target / gesture::GAIN,
        )
    }

    /// Milliseconds since the screen was made, for the drag's velocity.
    fn now_ms(&self) -> f32 {
        self.epoch.elapsed().as_secs_f32() * 1000.0
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
        let owns = self.incoming.source() == Some(&view.source);
        match view.state {
            CallState::Ringing => {
                self.incoming_ring(view, locked, window, cx);
                None
            }
            CallState::Disconnected { cause }
                if view.incoming && self.incall.last_id.as_ref() != Some(&view.source) =>
            {
                // It never reached the In-Call screen: it ended while ringing. The In-Call
                // screen must not flash "Call ended" for it.
                self.incall.done_id = Some(view.source.clone());
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
        // The first calls teach the drag: a counter in `ui.hint_counters`.
        let new_call = self.incoming.source() != Some(&view.source) || !self.incoming.shown;
        let hint = new_call
            && self
                .backend
                .read(cx)
                .prefs()
                .ui
                .hint_counters
                .get(HINT_KEY)
                .copied()
                .unwrap_or(0)
                < HINT_CALLS;
        if hint {
            self.backend.update(cx, |b, cx| {
                b.update_prefs(cx, |p| {
                    *p.ui.hint_counters.entry(HINT_KEY.to_owned()).or_insert(0) += 1;
                });
            });
        }
        let inc = &mut self.incoming;
        inc.locked = locked;
        let is_new = inc.source() != Some(&view.source) || !inc.shown;
        if is_new {
            inc.began_locked = locked;
            inc.state = RingState::Ringing;
            inc.acted = None;
            inc._leave = None;
            inc.reply_label = None;
            inc.hold_reply = false;
            inc.silenced = None;
            inc.hush.snap(0.0);
            inc.drag = None;
            inc.off_x.snap(0.0);
            inc.off_y.snap(0.0);
            inc.card_alpha.snap(1.0);
            inc.offset.set((0.0, 0.0, 1.0));
            inc.hint = hint;
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
        self.incoming_sync_hush(window, cx);
        cx.notify();
    }

    /// S30: the snapshot says the ringer was silenced (by the button, a volume key
    /// or the flip): the loops ease to rest, the aura loses colour, the control floods.
    fn incoming_sync_hush(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let silenced = {
            let snapshot = self.backend.read(cx).calls().snapshot();
            snapshot
                .ringer_silenced
                .then(|| snapshot.silenced_by.unwrap_or(SilencedBy::User))
        };
        let reduced = window.prefers_reduced_motion();
        let inc = &mut self.incoming;
        if inc.silenced == silenced || !inc.shown {
            return;
        }
        inc.silenced = silenced;
        let hushed = silenced.is_some();
        if reduced {
            inc.hush.snap(f32::from(u8::from(hushed)));
        } else {
            inc.hush.set_target(f32::from(u8::from(hushed)));
        }
        inc.fx.update(cx, |fx, cx| fx.hush(hushed, reduced, cx));
        let aura = inc.aura_state(Phase::Dialing);
        inc.aura.update(cx, |a, cx| a.set(aura, cx));
        if silenced == Some(SilencedBy::Flip) {
            rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        }
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
        if let Some(label) = inc.reply_label.take() {
            // S31: the result of declining with a text, held for 1.2 s like "Missed call from".
            inc.state = RingState::Missed;
            inc.missed_text = label;
            inc.hold_reply = true;
            inc.swap.set_target(1.0);
            inc.hidden[0].set_target(1.0);
            inc.hidden[1].set_target(1.0);
            inc._leave = Some(cx.spawn(async move |this, cx| {
                cx.background_executor().timer(MISSED_HOLD).await;
                this.update(cx, |this, cx| this.incoming_leave(cx)).ok();
            }));
            cx.notify();
        } else if is_missed(cause) && inc.acted.is_none() {
            inc.hold_reply = false;
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
        // Story 17.4: after a call that rang and ended, the pill offers "Block". Never over
        // the keyguard, and deliberately never while the call rings.
        if let Some(view) = self.incoming.view.clone()
            && !self.incoming.began_locked
            && !self.incoming.locked
        {
            self.offer_block_after_call(&view.number, &view.name, false, cx);
        }
        cx.notify();
    }

    /// Answer or Decline, once the finger lifts (or the mouse is pressed on desktop).
    pub(crate) fn incoming_mouse_down(
        &mut self,
        id: &'static str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        // A finger that dragged (or flicked) across the screen never taps a button on lift.
        if !self.incoming.tap.allow_click(0.0) {
            return;
        }
        if !self.presses.touch_seen() {
            self.presses.down(id);
        }
        self.incoming_activate(id, window, cx);
    }

    /// "Decline with message" over the ringing card: the Message control or an upward drag.
    fn incoming_open_reply(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let inc = &self.incoming;
        let Some(call_id) = inc.call_id() else { return };
        let has_number = inc
            .view
            .as_ref()
            .is_some_and(|v| !v.number.trim().is_empty());
        if !inc.is_ringing() || inc.acted.is_some() || !has_number {
            return;
        }
        self.reply_open(call_id, false, window, cx);
    }

    /// Silence: the ringer stops, the call stays answerable (S30). The look follows the snapshot.
    fn incoming_silence(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let inc = &self.incoming;
        // Silencing is Telecom's ringer; a WhatsApp call rings from the gateway's push.
        if inc.call_id().is_none()
            || !inc.is_ringing()
            || inc.acted.is_some()
            || inc.silenced.is_some()
            || self.backend.read(cx).silence_refused()
        {
            return;
        }
        let outcome = self.backend.update(cx, |b, cx| b.silence_ringer(cx));
        if outcome != backend::Outcome::Declined {
            rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        }
        cx.notify();
    }

    /// The reply sheet chose "Sent", "Opened Messages" or "Declined": the label takes the place of
    /// "Incoming call" at once and holds for 1.2 s after the call ends (S31).
    pub(crate) fn incoming_reply_label(&mut self, label: &'static str) {
        let inc = &mut self.incoming;
        inc.reply_label = Some(label.into());
        inc.missed_text = label.into();
        inc.hold_reply = true;
        inc.acted = Some(Act::Decline);
        inc.swap.set_target(1.0);
        inc.hidden[0].set_target(1.0);
        inc.hidden[1].set_target(1.0);
    }

    /// Every touch on the screen, for the tap guard.
    pub(crate) fn incoming_touch_all(
        &mut self,
        phase: gpui::TouchPhase,
        position: gpui::Point<gpui::Pixels>,
    ) {
        use gpui::TouchPhase;
        let (x, y) = (f32::from(position.x), f32::from(position.y));
        let tap = &mut self.incoming.tap;
        match phase {
            TouchPhase::Started => tap.begin(x, y, 0.0),
            TouchPhase::Moved => {
                tap.moved(x, y);
            }
            _ => tap.finish(x, y),
        }
    }

    /// The card drag (S29). The finger decides nothing until it lifts; the buttons never move.
    pub(crate) fn incoming_drag(
        &mut self,
        phase: gpui::TouchPhase,
        position: gpui::Point<gpui::Pixels>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        use gpui::TouchPhase;
        let (x, y) = (f32::from(position.x), f32::from(position.y));
        let live = {
            let inc = &self.incoming;
            inc.is_ringing() && inc.acted.is_none() && !self.sheet.is_visible()
        };
        let reduced = window.prefers_reduced_motion();
        match phase {
            TouchPhase::Started => {
                let inc = &mut self.incoming;
                if live && inc.drag.is_none() {
                    inc.drag = Some(gesture::Drag::new(x, y, inc.now_ms()));
                }
            }
            TouchPhase::Moved => {
                let inc = &mut self.incoming;
                let ms = inc.now_ms();
                let Some(drag) = inc.drag.as_mut() else {
                    return;
                };
                if !live {
                    inc.drag = None;
                    inc.off_x.set_target(0.0);
                    inc.off_y.set_target(0.0);
                    return;
                }
                let follow = drag.moved(x, y, ms);
                if !reduced {
                    // Direct while the finger is down: no spring.
                    inc.off_x.snap(follow.offset.0);
                    inc.off_y.snap(follow.offset.1);
                }
                match follow.tick {
                    Some(Tick::Half) => {
                        rovsti_ui::haptics::perform(window, HapticFeedback::Selection)
                    }
                    Some(Tick::Commit) => {
                        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm)
                    }
                    None => {}
                }
                cx.notify();
            }
            TouchPhase::Ended | TouchPhase::Cancelled => {
                let inc = &mut self.incoming;
                let ms = inc.now_ms();
                let Some(drag) = inc.drag.take() else {
                    return;
                };
                let release = if phase == TouchPhase::Ended && live {
                    drag.release(x, y, ms)
                } else {
                    Release::Cancel
                };
                // Everything springs back; a commit also acts, exactly like its button.
                inc.off_x.set_target(0.0);
                inc.off_y.set_target(0.0);
                match release {
                    Release::Cancel => {}
                    Release::Commit(Dir::Right) => self.incoming_activate("in-answer", window, cx),
                    Release::Commit(Dir::Left) => {
                        self.incoming_activate("in-decline", window, cx);
                        // The card flies off and fades while the screen leaves.
                        if !reduced && self.incoming.acted.is_some() {
                            let width = f32::from(window.viewport_size().width);
                            self.incoming.off_x.set_target(-width);
                            self.incoming.card_alpha.set_target(0.0);
                        }
                    }
                    Release::Commit(Dir::Up) => self.incoming_open_reply(window, cx),
                }
                cx.notify();
            }
        }
    }

    fn incoming_activate(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        match id {
            "in-msg" => return self.incoming_open_reply(window, cx),
            "in-silence" => return self.incoming_silence(window, cx),
            _ => {}
        }
        let inc = &self.incoming;
        let Some(source) = inc.source().cloned() else {
            return;
        };
        if !inc.is_ringing() || inc.acted.is_some() {
            return;
        }
        let act = match id {
            "in-answer" => Act::Answer,
            "in-decline" => Act::Decline,
            _ => return,
        };
        // The command first; the animation only runs beside it.
        let outcome = match source {
            CallSource::Telecom(call_id) => {
                let command = match act {
                    Act::Answer => CallCommand::Answer(call_id),
                    Act::Decline => CallCommand::Reject(call_id),
                };
                self.backend
                    .update(cx, |backend, cx| backend.send(command, cx))
            }
            CallSource::WhatsApp(_) => {
                self.wa_call.update(cx, |ui, cx| match act {
                    Act::Answer => ui.answer(cx),
                    Act::Decline => ui.hang_up(true, cx),
                });
                backend::Outcome::Done
            }
        };
        rovsti_ui::haptics::perform(
            window,
            match act {
                Act::Answer => HapticFeedback::Confirm,
                Act::Decline => HapticFeedback::Selection,
            },
        );
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
                let look = self.incoming.view.as_ref().map(|v| Look {
                    hue: v.hue,
                    monogram: v.monogram.clone(),
                    photo: self.caller_photo(&v.photo, cx).map(|(image, _)| image),
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

    /// One of the two secondary controls: Message (left) or Silence (right). A hidden one
    /// leaves its slot blank, so nothing ever moves (UIQ-2).
    fn secondary_control(
        &self,
        theme: Theme,
        id: &'static str,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let inc = &self.incoming;
        let message = id == "in-msg";
        let slot = div().w(px(SECONDARY)).h(px(SECONDARY_H)).flex_none();
        let has_number = inc
            .view
            .as_ref()
            .is_some_and(|v| !v.number.trim().is_empty());
        let visible = if message {
            has_number && !self.backend.read(cx).prefs().quick_replies.is_empty()
        } else {
            !self.backend.read(cx).silence_refused()
        };
        if !visible {
            return slot.into_any_element();
        }
        let press = self.presses.value(id).clamp(0.0, 1.2);
        let hush = if message {
            0.0
        } else {
            inc.hush.value.clamp(0.0, 1.0)
        };
        let silenced = !message && inc.silenced.is_some();
        let caption: &'static str = if message {
            "Message"
        } else {
            match inc.silenced {
                Some(SilencedBy::Flip) if hush > 0.5 => "Silenced by flip",
                Some(_) if hush > 0.5 => "Silenced",
                _ => "Silence",
            }
        };
        let live = inc.is_ringing() && inc.acted.is_none() && !silenced;
        let icon = if message {
            Icon::LucideMessageCircle
        } else {
            Icon::LucideBellOff
        };
        let side = SECONDARY * press_scale(press, scale::PRESS);
        // S10: the control floods with the accent tint when it is used.
        let plate = flat_glass(theme, Some((colors.accent, 0.55 * hush)));
        let mut el = div()
            .id(id)
            .role(Role::Button)
            .aria_label(if message {
                "Message"
            } else if silenced {
                "Silenced"
            } else {
                "Silence ringer"
            })
            .aria_disabled(!live)
            .relative()
            .w(px(SECONDARY))
            .h(px(SECONDARY_H))
            .flex_none()
            .flex()
            .flex_col()
            .items_center()
            .opacity(inc.buttons_fade.value() * (1.0 - inc.hidden[0].value.clamp(0.0, 1.0)))
            .child(
                div()
                    .size(px(SECONDARY))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        plate
                            .size(px(side))
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(svg().path(icon.path()).size(px(24.0)).text_color(
                                if silenced {
                                    colors.accent
                                } else {
                                    colors.label
                                },
                            )),
                    ),
            )
            .child(
                div()
                    .w(px(110.0))
                    .mt(px(4.0))
                    .h(px(16.0))
                    .flex()
                    .justify_center()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child(caption),
            );
        if live {
            el = el
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.incoming_mouse_down(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))));
        }
        el.into_any_element()
    }

    /// The wash that grows with the drag (alpha 0 to 0.35), and the bubble that says what
    /// letting go would do: Answer, Decline, or the reply sheet rising.
    fn drag_wash(&self, theme: Theme, progress_x: f32, progress_up: f32) -> Vec<AnyElement> {
        let calls = call_colors(theme);
        let colors = theme::colors(theme);
        let mut out = Vec::new();
        let p = progress_x.abs().clamp(0.0, 1.0);
        if p > 0.004 && !self.reduced {
            let (tint, icon, side) = if progress_x > 0.0 {
                (calls.answer, Icon::LucidePhone, 1.0)
            } else {
                (calls.danger, Icon::LucidePhoneOff, -1.0)
            };
            out.push(
                div()
                    .absolute()
                    .inset_0()
                    .bg(Hsla {
                        a: 0.35 * p,
                        ..tint
                    })
                    .into_any_element(),
            );
            // The bubble grows 0.6 to 1.2 on the side the card is heading to.
            let k = 0.6 + 0.6 * p;
            out.push(
                div()
                    .absolute()
                    .top(px(AVATAR_TOP + AVATAR * 0.5 - BUTTON * 0.5))
                    .when(side > 0.0, |d| d.right(px(28.0)))
                    .when(side < 0.0, |d| d.left(px(28.0)))
                    .size(px(BUTTON))
                    .opacity(p)
                    .child(
                        div()
                            .size(px(BUTTON * k))
                            .ml(px(BUTTON * (1.0 - k) * 0.5))
                            .mt(px(BUTTON * (1.0 - k) * 0.5))
                            .rounded_full()
                            .bg(tint)
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(svg().path(icon.path()).size(px(30.0 * k)).text_color(
                                if side > 0.0 && theme.is_dark() {
                                    gpui::black()
                                } else {
                                    gpui::white()
                                },
                            )),
                    )
                    .into_any_element(),
            );
        }
        let up = progress_up.clamp(0.0, 1.0);
        if up > 0.004 && !self.reduced {
            // The reply sheet's edge rising under the finger (detent: medium).
            out.push(
                flat_glass(theme, None)
                    .absolute()
                    .left(px(10.0))
                    .right(px(10.0))
                    .bottom(px(10.0 - 240.0 * (1.0 - up)))
                    .h(px(240.0))
                    .rounded(px(38.0))
                    .flex()
                    .justify_center()
                    .pt(px(14.0))
                    .text_size(typography::HEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.label)
                    .child("Decline with message")
                    .into_any_element(),
            );
        }
        out
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
        let photo = view.as_ref().and_then(|v| {
            self.want_caller_photo(&v.photo, cx);
            self.caller_photo(&v.photo, cx)
        });
        self.incoming.fx.update(cx, |fx, _| fx.photo = photo);
        let Some(view) = view else {
            return Some(root.into_any_element());
        };
        // The first frame of this call's screen is on its way: tell Kotlin (ring-latency log).
        if let Some(id) = view.source.telecom_id()
            && self.incoming.reported != Some(id)
        {
            self.incoming.reported = Some(id);
            backend::incoming_frame(id);
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
            .text_color(if inc.hold_reply {
                colors.label
            } else {
                calls.danger
            })
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
                    .top(px(lift + inc.off_y.value))
                    .left(px(inc.off_x.value))
                    .opacity(text_alpha * (1.0 - 0.5 * swap) * inc.card_alpha.value.clamp(0.0, 1.0))
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
            // The first calls teach the drag; the line never moves anything above it.
            .child(
                div()
                    .h(px(22.0))
                    .pt(px(4.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.tertiary_label)
                    .opacity(if inc.hint && inc.is_ringing() {
                        1.0
                    } else {
                        0.0
                    })
                    .child("Drag the card to answer or decline"),
            )
            .child(div().flex_1())
            // Reading order for TalkBack: Answer, Decline, Message, Silence. The secondary
            // row sits above the buttons (flex_col_reverse), in slots that exist even when empty.
            .child(
                div()
                    .flex()
                    .flex_col_reverse()
                    .items_center()
                    .child(
                        // Answer is first in reading order (TalkBack), Decline sits on the left.
                        div()
                            .flex()
                            .flex_row_reverse()
                            .gap(px(BUTTON_GAP))
                            .child(self.ring_button(
                                theme,
                                "in-answer",
                                Icon::LucidePhone,
                                "Answer",
                                1,
                                cx,
                            ))
                            .child(self.ring_button(
                                theme,
                                "in-decline",
                                Icon::LucidePhoneOff,
                                "Decline",
                                0,
                                cx,
                            )),
                    )
                    .child(
                        div()
                            .mb(px(SECONDARY_LIFT))
                            .h(px(SECONDARY_H))
                            .flex()
                            .gap(px(SECONDARY_GAP))
                            .child(self.secondary_control(theme, "in-msg", cx))
                            .child(self.secondary_control(theme, "in-silence", cx)),
                    ),
            );
        // S29: the wash grows with the drag, the bubble names what lifting would do.
        let (progress_x, progress_up) = (
            inc.off_x.value / (gesture::COMMIT_PX * gesture::GAIN),
            -inc.off_y.value / (gesture::COMMIT_PX * gesture::GAIN),
        );
        let entity = cx.entity();
        let tap_entity = entity.clone();
        let root = root
            .child(drag_surface(move |phase, position, _, cx| {
                tap_entity.update(cx, |this, _| this.incoming_touch_all(phase, position));
            }))
            .children(self.drag_wash(theme, progress_x, progress_up));
        let root = root.child(
            div()
                .absolute()
                .top(px(CARD_TOP))
                .left_0()
                .right_0()
                .h(px(CARD_H))
                .child(drag_surface(move |phase, position, window, cx| {
                    entity.update(cx, |this, cx| {
                        this.incoming_drag(phase, position, window, cx)
                    });
                })),
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

    /// The v0.1 positions (label, avatar, Decline, Answer) are constants the new rows must not
    /// touch: the secondary row and the hint live in the free space between the name and the
    /// buttons, on every screen height the phone has.
    #[test]
    fn the_new_rows_fit_between_the_name_and_the_buttons() {
        for height in [740.0_f32, 800.0, 874.0, 932.0] {
            // The number line ends the v0.1 block: label, avatar, gaps, name 34, number 20.
            let number_bottom = AVATAR_TOP + AVATAR + 20.0 + 20.0 + 34.0 + 20.0;
            let hint_bottom = number_bottom + 22.0;
            let buttons_top = height - BOTTOM_PAD - CAPTION_H - BUTTON;
            let secondary_bottom = buttons_top - SECONDARY_LIFT;
            let secondary_top = secondary_bottom - SECONDARY_H;
            assert!(
                hint_bottom < secondary_top,
                "{height}: {hint_bottom} {secondary_top}"
            );
            assert!(secondary_bottom < buttons_top);
        }
        // The two slots are 24 px apart and centred: Message left, Silence right.
        assert_eq!(SECONDARY_GAP, 24.0);
        assert_eq!(SECONDARY, 56.0);
    }

    /// The drag surface covers the card only: the buttons are never inside it.
    #[test]
    fn the_drag_surface_never_covers_a_button() {
        for height in [740.0_f32, 874.0] {
            let buttons_top = height - BOTTOM_PAD - CAPTION_H - BUTTON;
            assert!(CARD_TOP + CARD_H < buttons_top - SECONDARY_LIFT - SECONDARY_H);
        }
    }
}
