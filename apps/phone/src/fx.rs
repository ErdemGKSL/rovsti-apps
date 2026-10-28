//! Keypad effects (signature moments S1 and the S3 bloom ring) and the table
//! of anchors the moments find each other by.
//!
//! `KeyFx` is its own view painted over the page: its frames (a ripple ring
//! spreading, a digit copy dropping into the field) never re-render the
//! Phone app view, and it asks for animation frames only while something
//! plays. Rings and digit copies live in fixed-size storage.

use std::time::Duration;

use gpui::{
    Bounds, Context, Hsla, IntoElement, ParentElement, Pixels, Point, Render, Styled, Window, div,
    px,
};
use rovsti_ui::{
    current_theme,
    moments::{Anchors, Flights, RippleRing, square_at},
    motion::Motion,
    spring::SpringParams,
    theme::{self, call_colors},
};

/// Anchor slots: the first twelve are the dial keys, in `keypad::KEYS` order.
pub const A_KEY: usize = 0;
pub const A_FIELD: usize = 12;
/// The newest digit in the number field.
pub const A_LAST: usize = 13;
pub const A_CALL: usize = 14;
pub const A_END: usize = 15;
pub const A_AVATAR: usize = 16;
pub const A_ANSWER: usize = 17;
pub const A_INCOMING_AVATAR: usize = 18;
pub const A_COUNT: usize = 19;

pub type Marks = Anchors<A_COUNT>;

/// S1: the digit copy drops on `appear`-like critical damping, ζ 1, k 450.
fn drop_spring() -> SpringParams {
    SpringParams::stiffness(1.0, 450.0)
}

const RINGS: usize = 3;
/// Digit copies in flight at once.
const DROPS: usize = 3;

#[derive(Clone, Copy)]
struct Drop {
    digit: &'static str,
    from: Point<Pixels>,
    to: Point<Pixels>,
    from_size: f32,
    to_size: f32,
}

pub struct KeyFx {
    motion: Motion,
    rings: [(RippleRing, Bounds<Pixels>); RINGS],
    next_ring: usize,
    bloom: (RippleRing, Bounds<Pixels>),
    drops: Flights<Drop, DROPS>,
}

impl KeyFx {
    pub fn new() -> Self {
        Self {
            motion: Motion::default(),
            rings: [(RippleRing::key(), Bounds::default()); RINGS],
            next_ring: 0,
            bloom: (RippleRing::bloom(), Bounds::default()),
            drops: Flights::default(),
        }
    }

    /// A key went down: its ripple ring, and (with a landing point) a copy of
    /// its digit dropping into the number field.
    pub fn key_down(
        &mut self,
        key: Bounds<Pixels>,
        digit: &'static str,
        landing: Option<(Point<Pixels>, f32)>,
        cx: &mut Context<Self>,
    ) {
        let slot = self.next_ring;
        self.next_ring = (self.next_ring + 1) % RINGS;
        self.rings[slot].1 = key;
        self.rings[slot].0.start();
        if let Some((to, to_size)) = landing {
            self.drops.launch(
                Drop {
                    digit,
                    from: key.center(),
                    to,
                    from_size: 32.0,
                    to_size,
                },
                drop_spring(),
            );
        }
        cx.notify();
    }

    /// The Call button was tapped (S3): its answer-coloured ring blooms.
    pub fn bloom(&mut self, button: Bounds<Pixels>, cx: &mut Context<Self>) {
        self.bloom.1 = button;
        self.bloom.0.start();
        cx.notify();
    }

    fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        for (ring, _) in &mut self.rings {
            moving |= ring.tick(dt);
        }
        moving |= self.bloom.0.tick(dt);
        moving |= self.drops.tick(dt);
        moving
    }
}

impl Render for KeyFx {
    fn render(&mut self, window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame(window, |dt| self.tick(dt));
        self.motion = motion;

        let theme = current_theme(window);
        let mut layer = div().absolute().size_full();
        for (ring, key) in &self.rings {
            if let Some((scale, alpha)) = ring.state() {
                let side = key.size.width * scale;
                layer = layer.child(
                    ring_at(square_at(key.center(), side))
                        .border_color(Hsla {
                            a: alpha,
                            ..gpui::white()
                        })
                        .border_2(),
                );
            }
        }
        if let Some((scale, alpha)) = self.bloom.0.state() {
            let side = self.bloom.1.size.width * scale;
            let answer = call_colors(theme).answer;
            layer = layer.child(
                ring_at(square_at(self.bloom.1.center(), side))
                    .border_color(Hsla { a: alpha, ..answer })
                    .border_2(),
            );
        }
        let label = theme::colors(theme).label;
        for (drop, p) in self.drops.active() {
            let p = p.clamp(0.0, 1.0);
            let x = drop.from.x + (drop.to.x - drop.from.x) * p;
            let y = drop.from.y + (drop.to.y - drop.from.y) * p;
            let size = drop.from_size + (drop.to_size - drop.from_size) * p;
            let box_side = px(48.0);
            layer = layer.child(
                div()
                    .absolute()
                    .left(x - box_side * 0.5)
                    .top(y - box_side * 0.5)
                    .size(box_side)
                    .flex()
                    .items_center()
                    .justify_center()
                    .opacity(0.9 * (1.0 - p))
                    .text_size(px(size))
                    .font_weight(gpui::FontWeight(300.0))
                    .text_color(label)
                    .child(drop.digit),
            );
        }
        layer
    }
}

fn ring_at(bounds: Bounds<Pixels>) -> gpui::Div {
    div()
        .absolute()
        .left(bounds.origin.x)
        .top(bounds.origin.y)
        .size(bounds.size.width)
        .rounded_full()
}

/// Spring kept here so a test can pin the S1 numbers.
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_digit_copy_is_in_the_field_in_about_220_ms() {
        let mut flights = Flights::<u8, 1>::default();
        flights.launch(0, drop_spring());
        let mut ms = 0;
        let mut arrived = None;
        while flights.tick(Duration::from_millis(4)) {
            ms += 4;
            if arrived.is_none() && flights.active().next().is_some_and(|(_, p)| p >= 0.95) {
                arrived = Some(ms);
            }
            assert!(ms < 1000);
        }
        let arrived = arrived.expect("arrives");
        assert!((120..=260).contains(&arrived), "{arrived} ms");
    }
}
