//! Shared-element moments: S3 (the Call circle becomes End), S8 (the Answer
//! circle becomes End) and S4 (an avatar flies from its tile or row to the
//! In-Call slot, and back when the call ends).
//!
//! The call is always placed or answered first; a flight only runs beside it.
//! A flight starts on the first frame the In-Call screen has painted the
//! element it lands on (read from `Marks`), follows that element while it
//! settles, and hands over to the real element in the frame it lands.
//! Reduced motion plays none of this: the screens cross-fade for 120 ms.

use std::{sync::Arc, time::Duration, time::Instant};

use gpui::{
    AnyElement, Bounds, Hsla, IntoElement, ObjectFit, ParentElement, Pixels, RenderImage,
    SharedString, Styled, StyledImage, Window, div, linear_color_stop, linear_gradient, px, svg,
};
use rovsti_ui::{
    Icon,
    moments::Glide,
    motion_tokens,
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, mix, typography},
};

use crate::{
    PhoneApp,
    fx::{A_ANSWER, A_AVATAR, A_CALL, A_END, A_INCOMING_AVATAR},
};

/// A flight that has not found its landing spot within this long is dropped
/// (the call screen never showed); the real elements are drawn as usual.
const GIVE_UP: Duration = Duration::from_millis(2500);
/// Glyph size inside the flying button.
const GLYPH: f32 = 30.0;
/// The call circle rests at this scale while held (`PRESS_CARD`).
const HELD: f32 = motion_tokens::scale::PRESS_CARD;

/// What the flying button started as.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ButtonFrom {
    Call,
    Answer,
}

/// What an avatar looks like in flight.
#[derive(Clone)]
pub(crate) struct Look {
    pub hue: Option<usize>,
    pub monogram: Option<SharedString>,
    pub photo: Option<Arc<RenderImage>>,
}

/// Where a list avatar sits, to fly back to when the call ends.
#[derive(Clone)]
pub(crate) struct Home {
    pub tab: usize,
    pub index: usize,
    pub look: Look,
}

#[derive(Clone, Copy)]
enum Dest {
    /// The In-Call avatar slot (a live anchor).
    InCall,
    /// A fixed rectangle (the row or tile it returns to).
    Fixed(Bounds<Pixels>),
}

struct ButtonFlight {
    from: ButtonFrom,
    start: Bounds<Pixels>,
    since: Instant,
    glide: Glide,
    waiting: bool,
}

struct AvatarFlight {
    look: Look,
    start: Bounds<Pixels>,
    dest: Dest,
    since: Instant,
    glide: Glide,
    waiting: bool,
    /// 1.08 lift at the start (a tapped tile or row).
    lift: Spring,
    /// Drawn from the tap on (a list avatar), not only once it takes off.
    shows_waiting: bool,
    /// The ring is already on the avatar it leaves (the Incoming screen's).
    ring0: f32,
}

#[derive(Default)]
pub(crate) struct Flights {
    button: Option<ButtonFlight>,
    avatar: Option<AvatarFlight>,
    /// Where the last list-placed call's avatar came from.
    pub home: Option<Home>,
}

impl Flights {
    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        if let Some(b) = &mut self.button {
            moving = true;
            if !b.waiting && !b.glide.tick(dt) {
                self.button = None;
            }
        }
        if let Some(a) = &mut self.avatar {
            moving = true;
            moving |= a.lift.tick(dt);
            if !a.waiting && !a.glide.tick(dt) {
                self.avatar = None;
            }
        }
        moving
    }

    /// The Call circle is held, waiting for the In-Call screen.
    pub fn holds_call(&self) -> bool {
        self.button
            .as_ref()
            .is_some_and(|b| b.from == ButtonFrom::Call && b.waiting)
    }

    /// The button is in the air (or just waiting to take off): the source
    /// button and the real End button stay out of sight.
    pub fn button_active(&self, from: ButtonFrom) -> bool {
        self.button.as_ref().is_some_and(|b| b.from == from)
    }

    /// Only the flying copy is drawn (not while it still waits to take off).
    pub fn button_flying(&self, from: ButtonFrom) -> bool {
        self.button
            .as_ref()
            .is_some_and(|b| b.from == from && !b.waiting)
    }

    /// Any button flight owns the real End button.
    pub fn hides_end(&self) -> bool {
        self.button.is_some()
    }

    /// An avatar flight owns the real In-Call avatar.
    pub fn hides_avatar(&self) -> bool {
        self.avatar
            .as_ref()
            .is_some_and(|a| matches!(a.dest, Dest::InCall))
    }
}

fn shrink(b: Bounds<Pixels>, k: f32) -> Bounds<Pixels> {
    let (w, h) = (b.size.width * k, b.size.height * k);
    let c = b.center();
    Bounds::new(gpui::point(c.x - w * 0.5, c.y - h * 0.5), gpui::size(w, h))
}

impl PhoneApp {
    /// S3 and S8: the circle just tapped will become the End button.
    pub(crate) fn begin_button_flight(&mut self, from: ButtonFrom, window: &Window) {
        if window.prefers_reduced_motion() {
            return;
        }
        let (slot, held) = match from {
            ButtonFrom::Call => (A_CALL, HELD),
            ButtonFrom::Answer => (A_ANSWER, HELD),
        };
        let Some(rect) = self.marks.get(slot) else {
            return;
        };
        // A stale landing spot from an earlier call must not start the flight.
        self.marks.set(A_END, None);
        self.flights.button = Some(ButtonFlight {
            from,
            start: shrink(rect, held),
            since: Instant::now(),
            glide: Glide::default(),
            waiting: true,
        });
    }

    /// S8: the incoming avatar (120 px) flies to the In-Call slot (96 px).
    pub(crate) fn begin_answer_avatar(&mut self, look: Look, window: &Window) {
        if window.prefers_reduced_motion() {
            return;
        }
        let Some(rect) = self.marks.get(A_INCOMING_AVATAR) else {
            return;
        };
        // The probe sits inside the avatar's 3 px ring.
        let rect = shrink(rect, 1.0 + 6.0 / f32::from(rect.size.width));
        self.start_avatar(look, rect, Dest::InCall, 0.0, false);
        if let Some(a) = &mut self.flights.avatar {
            a.ring0 = 1.0;
        }
    }

    /// S4: a tapped tile or row avatar lifts and flies to the In-Call slot.
    pub(crate) fn begin_list_flight(&mut self, home: Home, from: Bounds<Pixels>, window: &Window) {
        if window.prefers_reduced_motion() {
            return;
        }
        self.start_avatar(home.look.clone(), from, Dest::InCall, 1.0, true);
        self.flights.home = Some(home);
    }

    fn start_avatar(
        &mut self,
        look: Look,
        start: Bounds<Pixels>,
        dest: Dest,
        lift: f32,
        shows_waiting: bool,
    ) {
        self.marks.set(A_AVATAR, None);
        let mut spring = Spring::new(0.0, motion_tokens::press()).with_epsilon(0.002);
        spring.set_target(lift);
        self.flights.avatar = Some(AvatarFlight {
            look,
            start,
            dest,
            since: Instant::now(),
            glide: Glide::default(),
            waiting: true,
            lift: spring,
            shows_waiting,
            ring0: 0.0,
        });
    }

    /// The call ended and the tabs return: the avatar flies back to its row
    /// or tile if that is still on screen (else the screens just cross-fade).
    pub(crate) fn begin_return_flight(&mut self) {
        let Some(home) = self.flights.home.take() else {
            return;
        };
        if self.reduced || self.tabs.selected() != home.tab {
            return;
        }
        let (Some(from), Some(to)) = (self.marks.get(A_AVATAR), self.home_rect(&home)) else {
            return;
        };
        self.start_avatar(home.look, from, Dest::Fixed(to), 0.0, true);
        if let Some(a) = &mut self.flights.avatar {
            a.waiting = false;
            a.glide.start(from);
        }
    }

    /// Once per render: flights whose landing spot has been painted take off.
    pub(crate) fn flights_ready(&mut self, cx: &mut gpui::Context<Self>) {
        let call_ready = self.incall.shown && self.incall.has_call();
        if let Some(b) = &mut self.flights.button
            && b.waiting
        {
            if call_ready && self.marks.get(A_END).is_some() {
                b.waiting = false;
                b.glide.start(b.start);
                // The real End button is full size when the circle lands.
                self.incall.end_full();
            } else if b.since.elapsed() > GIVE_UP {
                self.flights.button = None;
            }
        }
        if let Some(a) = &mut self.flights.avatar
            && a.waiting
        {
            let ready = match a.dest {
                Dest::InCall => call_ready && self.marks.get(A_AVATAR).is_some(),
                Dest::Fixed(_) => true,
            };
            if ready {
                a.waiting = false;
                a.glide.start(a.start);
                a.lift.retarget(0.0, motion_tokens::release());
                if a.ring0 > 0.0 {
                    // The Incoming screen's own avatar steps aside for the copy.
                    self.incoming.fx.update(cx, |fx, cx| fx.hide_avatar(cx));
                }
            } else if a.since.elapsed() > GIVE_UP {
                self.flights.avatar = None;
            }
        }
    }

    /// Where the avatar of `home` is painted right now, if it is on screen.
    fn home_rect(&self, home: &Home) -> Option<Bounds<Pixels>> {
        match home.tab {
            crate::RECENTS_TAB => self.recents.avatar_rect(home.index),
            crate::FAVORITES_TAB => self.favorites.avatar_rect(home.index),
            _ => None,
        }
    }

    /// The flying copies, drawn over everything.
    pub(crate) fn flight_layer(&self, theme: Theme) -> Option<AnyElement> {
        let button = self.flights.button.as_ref().filter(|b| !b.waiting);
        let avatar = self.flights.avatar.as_ref();
        let avatar = avatar.filter(|a| a.shows_waiting || !a.waiting);
        if button.is_none() && avatar.is_none() {
            return None;
        }
        let mut layer = div().absolute().inset_0();
        if let Some(b) = button
            && let Some(to) = self.marks.get(A_END)
        {
            layer = layer.child(button_copy(theme, b, to));
        }
        if let Some(a) = avatar {
            let rect = match a.dest {
                Dest::InCall => self.marks.get(A_AVATAR),
                Dest::Fixed(to) => Some(to),
            };
            if let Some(to) = rect {
                layer = layer.child(avatar_copy(theme, a, to));
            }
        }
        Some(layer.into_any_element())
    }
}

/// The circle on its way to becoming the End button: it slides, widens,
/// its colour mixes answer to danger and the glyph cross-fades.
fn button_copy(theme: Theme, flight: &ButtonFlight, to: Bounds<Pixels>) -> AnyElement {
    let calls = call_colors(theme);
    let p = flight.glide.progress();
    let rect = flight.glide.rect(to);
    let glyph_from = if theme.is_dark() {
        gpui::black()
    } else {
        gpui::white()
    };
    // Call keeps a white glyph in both themes (the keypad button).
    let glyph_from = match flight.from {
        ButtonFrom::Call => gpui::white(),
        ButtonFrom::Answer => glyph_from,
    };
    div()
        .absolute()
        .left(rect.origin.x)
        .top(rect.origin.y)
        .w(rect.size.width)
        .h(rect.size.height)
        .rounded_full()
        .bg(mix(
            calls.answer,
            calls.danger,
            ((p - 0.3) / 0.5).clamp(0.0, 1.0),
        ))
        .flex()
        .items_center()
        .justify_center()
        .child(
            div().absolute().opacity(1.0 - p).child(
                svg()
                    .path(Icon::LucidePhone.path())
                    .size(px(GLYPH))
                    .text_color(glyph_from),
            ),
        )
        .child(
            div().absolute().opacity(p).child(
                svg()
                    .path(Icon::LucidePhoneOff.path())
                    .size(px(GLYPH))
                    .text_color(gpui::white()),
            ),
        )
        .into_any_element()
}

/// The avatar in flight: the disc grows its ring and rim as it lands.
fn avatar_copy(theme: Theme, flight: &AvatarFlight, to: Bounds<Pixels>) -> AnyElement {
    let colors = theme::colors(theme);
    let p = if flight.waiting {
        0.0
    } else {
        flight.glide.progress()
    };
    let from = flight.start;
    let rect = if flight.waiting {
        from
    } else {
        flight.glide.rect(to)
    };
    // The tapped avatar lifts to 1.08 before it leaves.
    let lift = 1.0 + 0.08 * flight.lift.value.max(0.0);
    let rect = shrink(rect, lift);
    let side = f32::from(rect.size.width);
    let unit = side / 96.0;
    let (top, bottom) = theme::hue_pair(theme, flight.look.hue);
    let fill = if flight.look.hue.is_some() {
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
    let inner = match &flight.look.monogram {
        Some(letter) => div()
            .text_size(px(42.0 * unit))
            .font_weight(typography::HEADLINE.weight)
            .text_color(gpui::white())
            .child(letter.clone())
            .into_any_element(),
        None => svg()
            .path(Icon::LucideUser.path())
            .size(px(44.0 * unit))
            .text_color(if flight.look.hue.is_some() {
                gpui::white()
            } else {
                colors.secondary_label
            })
            .into_any_element(),
    };
    let r = flight.ring0 + (1.0 - flight.ring0) * p;
    let ring = Hsla {
        a: 0.6 * r,
        ..theme::accent_glow(theme)
    };
    let pad = 3.0 * unit * r;
    div()
        .absolute()
        .left(rect.origin.x)
        .top(rect.origin.y)
        .size(rect.size.width)
        .rounded_full()
        .border(px(3.0 * unit * r))
        .border_color(ring)
        .p(px(pad))
        .child(
            div()
                .relative()
                .size_full()
                .rounded_full()
                .bg(fill)
                .border(px(1.5))
                .border_color(Hsla {
                    a: 0.35 * r,
                    ..gpui::white()
                })
                .flex()
                .items_center()
                .justify_center()
                .child(inner)
                .children(flight.look.photo.clone().map(|image| {
                    gpui::img(image)
                        .absolute()
                        .top_0()
                        .left_0()
                        .size_full()
                        .rounded_full()
                        // Opaque all the way: the avatar it lands on shows the same photo.
                        .object_fit(ObjectFit::Cover)
                })),
        )
        .into_any_element()
}
