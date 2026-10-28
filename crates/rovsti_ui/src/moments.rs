//! Building blocks for signature moments (DESIGN.md S1 to S16).
//!
//! A moment is a short choreography of transforms, opacity and colour mixes
//! on shapes that are already painted; none changes layout or adds a blur.
//! Everything here is plain data in fixed-size storage: ticking and reading a
//! moment never allocates, and a view that owns one only asks for animation
//! frames (through [`crate::motion::Motion`], the one frame driver) while
//! something is actually moving.
//!
//! * [`Tween`], [`RippleRing`] and [`Fade`]: time-based curves.
//! * [`Scheduler`]: at most two moments at once; a third waits 120 ms.
//! * [`SpringBank`] and [`Flights`]: spring groups with fixed capacity.
//! * [`Glide`]: a shared element flying between two rectangles.
//! * [`Anchors`]: where things were last painted, so a moment can start from
//!   (and land on) the real element.

use std::{cell::Cell, rc::Rc, time::Duration};

use gpui::{Bounds, IntoElement, Pixels, Styled, Window, canvas, point, px, size};
use liquid_glass_kit::{
    motion_tokens::{self, curve},
    spring::Spring,
};

/// Whether the system asked for reduced motion (animation scale 0 on
/// Android). Every moment has a fallback for this: usually a 120 ms
/// cross-fade, never a loop.
pub fn reduced(window: &Window) -> bool {
    window.prefers_reduced_motion()
}

/// The cross-fade that replaces a moment under reduced motion.
pub const FALLBACK_FADE: Duration = Duration::from_millis(120);

/// A later moment waits this long when two are already running.
pub const COLLISION_WAIT: Duration = Duration::from_millis(120);

/// Moments that may run at the same time on one screen.
pub const MAX_CONCURRENT: usize = 2;

fn spring(value: f32, params: liquid_glass_kit::spring::SpringParams) -> Spring {
    Spring::new(value, params).with_epsilon(0.002)
}

// ---- Time-based curves ------------------------------------------------------

/// A 0 to 1 progress over a fixed time with a cubic-bezier ease.
#[derive(Clone, Copy, Debug)]
pub struct Tween {
    elapsed: f32,
    duration: f32,
    bezier: [f32; 4],
    running: bool,
}

impl Tween {
    /// An idle tween: progress is 1 until [`Tween::start`].
    pub const fn new(duration: Duration, bezier: [f32; 4]) -> Self {
        Self {
            elapsed: 0.0,
            duration: duration.as_secs_f32(),
            bezier,
            running: false,
        }
    }

    /// Linear progress over `duration`.
    pub const fn linear(duration: Duration) -> Self {
        Self::new(duration, [0.0, 0.0, 1.0, 1.0])
    }

    pub fn start(&mut self) {
        self.elapsed = 0.0;
        self.running = true;
    }

    pub fn stop(&mut self) {
        self.running = false;
    }

    pub fn running(&self) -> bool {
        self.running
    }

    /// Advances by `dt`; returns whether it is still running.
    pub fn tick(&mut self, dt: Duration) -> bool {
        if !self.running {
            return false;
        }
        self.elapsed += dt.as_secs_f32();
        if self.elapsed >= self.duration {
            self.running = false;
        }
        self.running
    }

    /// Raw time progress, 0 to 1 (1 when idle).
    pub fn time(&self) -> f32 {
        if !self.running || self.duration <= 0.0 {
            1.0
        } else {
            (self.elapsed / self.duration).clamp(0.0, 1.0)
        }
    }

    /// Eased progress, 0 to 1 (1 when idle).
    pub fn progress(&self) -> f32 {
        let t = self.time();
        if self.bezier == [0.0, 0.0, 1.0, 1.0] {
            t
        } else {
            curve::cubic_bezier(self.bezier)(t)
        }
    }
}

/// A ring that spreads out of a control and fades (S1 key ripple, S3 bloom).
#[derive(Clone, Copy, Debug)]
pub struct RippleRing {
    tween: Tween,
    from: f32,
    to: f32,
    alpha: f32,
}

impl RippleRing {
    /// `scale` goes `from` to `to` while alpha goes `alpha` to 0, on the
    /// kit's RELEASE_RING bezier.
    pub const fn new(duration: Duration, from: f32, to: f32, alpha: f32) -> Self {
        Self {
            tween: Tween::new(duration, curve::RELEASE_RING.1),
            from,
            to,
            alpha,
        }
    }

    /// S1: a key's ripple, 1.0 to 1.5 over 380 ms, white 0.5.
    pub const fn key() -> Self {
        Self::new(Duration::from_millis(380), 1.0, 1.5, 0.5)
    }

    /// S3: the Call button's bloom, 1.0 to 2.2 over 460 ms.
    pub const fn bloom() -> Self {
        Self::new(curve::RELEASE_RING.0, 1.0, 2.2, 0.6)
    }

    pub fn start(&mut self) {
        self.tween.start();
    }

    pub fn running(&self) -> bool {
        self.tween.running()
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        self.tween.tick(dt)
    }

    /// `(scale, alpha)` while it plays, `None` at rest.
    pub fn state(&self) -> Option<(f32, f32)> {
        if !self.tween.running() {
            return None;
        }
        // The size follows the bezier (quick out, slow creep); the fade runs
        // on plain time so the ring stays visible while it spreads.
        let p = self.tween.progress();
        let fade = 1.0 - self.tween.time();
        Some((self.from + (self.to - self.from) * p, self.alpha * fade))
    }
}

/// A 0 to 1 opacity ramp over 120 ms: the reduced-motion stand-in for a
/// moment (the real-time clock keeps running under reduced motion).
#[derive(Clone, Copy, Debug)]
pub struct Fade(Tween);

impl Default for Fade {
    fn default() -> Self {
        Self(Tween::linear(FALLBACK_FADE))
    }
}

impl Fade {
    pub fn restart(&mut self) {
        self.0.start();
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        self.0.tick(dt)
    }

    pub fn running(&self) -> bool {
        self.0.running()
    }

    /// Opacity: 1 at rest, 0 to 1 while it plays.
    pub fn value(&self) -> f32 {
        self.0.progress()
    }
}

// ---- The collision scheduler ------------------------------------------------

/// How a moment ranks against others.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Priority {
    /// Counts against the limit and may wait.
    Normal,
    /// S3 and S8 always start at once (they are the call itself).
    Exempt,
}

#[derive(Clone, Copy, Debug)]
struct Slot {
    start: Duration,
    end: Duration,
}

/// Decides when a moment may start so no more than [`MAX_CONCURRENT`] play
/// together. Time is passed in (any monotonic clock), so it is tested with a
/// fake one. Fixed storage: nothing allocates.
#[derive(Clone, Debug, Default)]
pub struct Scheduler {
    slots: [Option<Slot>; 4],
}

impl Scheduler {
    fn running_at(&self, at: Duration) -> usize {
        self.slots
            .iter()
            .flatten()
            .filter(|s| s.start <= at && at < s.end)
            .count()
    }

    /// Books a moment of `length` asked for at `now`; returns when it starts.
    pub fn request(&mut self, now: Duration, length: Duration, priority: Priority) -> Duration {
        self.prune(now);
        let start = if priority == Priority::Exempt || self.running_at(now) < MAX_CONCURRENT {
            now
        } else {
            // Wait 120 ms, or until a place frees, whichever comes first.
            let frees = self
                .slots
                .iter()
                .flatten()
                .filter(|s| s.start <= now)
                .map(|s| s.end)
                .min()
                .unwrap_or(now + COLLISION_WAIT);
            frees.min(now + COLLISION_WAIT)
        };
        let slot = Slot {
            start,
            end: start + length,
        };
        // Reuse a free entry, else replace the one that ends first.
        let index = self
            .slots
            .iter()
            .position(Option::is_none)
            .or_else(|| {
                self.slots
                    .iter()
                    .enumerate()
                    .min_by_key(|(_, s)| s.map_or(Duration::ZERO, |s| s.end))
                    .map(|(i, _)| i)
            })
            .unwrap_or(0);
        self.slots[index] = Some(slot);
        start
    }

    /// Forgets moments that ended before `now`.
    pub fn prune(&mut self, now: Duration) {
        for slot in &mut self.slots {
            if slot.is_some_and(|s| s.end <= now) {
                *slot = None;
            }
        }
    }

    /// Nothing is playing or waiting: no frames are needed on its account.
    pub fn is_idle(&self, now: Duration) -> bool {
        self.slots.iter().flatten().all(|s| s.end <= now)
    }
}

// ---- Spring groups with fixed capacity --------------------------------------

/// `N` springs ticked together.
#[derive(Clone, Copy, Debug)]
pub struct SpringBank<const N: usize> {
    pub springs: [Spring; N],
}

impl<const N: usize> SpringBank<N> {
    pub fn new(value: f32, params: liquid_glass_kit::spring::SpringParams) -> Self {
        Self {
            springs: [spring(value, params); N],
        }
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        for s in &mut self.springs {
            moving |= s.tick(dt);
        }
        moving
    }

    pub fn set_target_all(&mut self, target: f32) {
        for s in &mut self.springs {
            s.set_target(target);
        }
    }
}

/// Up to `N` things in flight, each a 0 to 1 spring with a payload (the three
/// digit copies of S1). A new one takes a free place, else replaces the oldest.
#[derive(Clone, Copy, Debug)]
pub struct Flights<T: Copy, const N: usize> {
    items: [Option<(T, Spring)>; N],
    next: usize,
}

impl<T: Copy, const N: usize> Default for Flights<T, N> {
    fn default() -> Self {
        Self {
            items: [None; N],
            next: 0,
        }
    }
}

impl<T: Copy, const N: usize> Flights<T, N> {
    /// Launches `payload` on `params` (0 to 1).
    pub fn launch(&mut self, payload: T, params: liquid_glass_kit::spring::SpringParams) {
        let index = self
            .items
            .iter()
            .position(Option::is_none)
            .unwrap_or_else(|| {
                let i = self.next;
                self.next = (self.next + 1) % N;
                i
            });
        let mut s = spring(0.0, params);
        s.set_target(1.0);
        self.items[index] = Some((payload, s));
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        for item in &mut self.items {
            if let Some((_, s)) = item {
                if s.tick(dt) {
                    moving = true;
                } else {
                    *item = None;
                }
            }
        }
        moving
    }

    pub fn is_empty(&self) -> bool {
        self.items.iter().all(Option::is_none)
    }

    /// The flights in the air with their progress.
    pub fn active(&self) -> impl Iterator<Item = (T, f32)> + '_ {
        self.items
            .iter()
            .flatten()
            .map(|(payload, s)| (*payload, s.value))
    }
}

// ---- Shared elements --------------------------------------------------------

/// A shared element flying from where it was to where it lands. The landing
/// rectangle is read fresh every frame (`rect`), so a target that is still
/// sliding into place is followed rather than missed.
#[derive(Clone, Copy, Debug)]
pub struct Glide {
    from: Bounds<Pixels>,
    t: Spring,
    live: bool,
}

impl Default for Glide {
    fn default() -> Self {
        Self {
            from: Bounds::default(),
            t: spring(1.0, motion_tokens::morph()),
            live: false,
        }
    }
}

impl Glide {
    /// Starts at `from` and flies on [`motion_tokens::morph`].
    pub fn start(&mut self, from: Bounds<Pixels>) {
        self.from = from;
        self.t = spring(0.0, motion_tokens::morph());
        self.t.set_target(1.0);
        self.live = true;
    }

    pub fn cancel(&mut self) {
        self.live = false;
    }

    /// In the air (or just landed and not yet cleared).
    pub fn live(&self) -> bool {
        self.live
    }

    /// 0 at the start, 1 once landed (overshoots a hair never: critical damping).
    pub fn progress(&self) -> f32 {
        self.t.value.clamp(0.0, 1.0)
    }

    /// Advances; the glide ends when it lands.
    pub fn tick(&mut self, dt: Duration) -> bool {
        if !self.live {
            return false;
        }
        if self.t.tick(dt) {
            true
        } else {
            self.live = false;
            false
        }
    }

    /// The element's rectangle now, given where it is headed.
    pub fn rect(&self, to: Bounds<Pixels>) -> Bounds<Pixels> {
        lerp_bounds(self.from, to, self.t.value)
    }
}

/// Rectangle interpolation by centre and size.
pub fn lerp_bounds(a: Bounds<Pixels>, b: Bounds<Pixels>, t: f32) -> Bounds<Pixels> {
    let f = |x: Pixels, y: Pixels| x + (y - x) * t;
    let (aw, ah, bw, bh) = (a.size.width, a.size.height, b.size.width, b.size.height);
    let (acx, acy) = (a.origin.x + aw * 0.5, a.origin.y + ah * 0.5);
    let (bcx, bcy) = (b.origin.x + bw * 0.5, b.origin.y + bh * 0.5);
    let (w, h) = (f(aw, bw), f(ah, bh));
    let (cx, cy) = (f(acx, bcx), f(acy, bcy));
    Bounds::new(point(cx - w * 0.5, cy - h * 0.5), size(w, h))
}

/// A square of side `side` centred on `centre`.
pub fn square_at(centre: gpui::Point<Pixels>, side: Pixels) -> Bounds<Pixels> {
    Bounds::new(
        point(centre.x - side * 0.5, centre.y - side * 0.5),
        size(side, side),
    )
}

// ---- Where things are -------------------------------------------------------

/// The last painted rectangle of `N` named things. Clone it (cheap) into the
/// views that need to look each other up.
#[derive(Clone)]
pub struct Anchors<const N: usize>(Rc<[Cell<Option<Bounds<Pixels>>>; N]>);

impl<const N: usize> Default for Anchors<N> {
    fn default() -> Self {
        Self(Rc::new(std::array::from_fn(|_| Cell::new(None))))
    }
}

impl<const N: usize> Anchors<N> {
    pub fn get(&self, slot: usize) -> Option<Bounds<Pixels>> {
        self.0.get(slot).and_then(Cell::get)
    }

    pub fn set(&self, slot: usize, bounds: Option<Bounds<Pixels>>) {
        if let Some(cell) = self.0.get(slot) {
            cell.set(bounds);
        }
    }

    /// An invisible layer filling its (positioned) parent that records the
    /// parent's bounds into `slot` every time it paints.
    pub fn probe(&self, slot: usize) -> impl IntoElement + use<N> {
        let anchors = self.clone();
        canvas(
            |_, _, _| (),
            move |bounds, (), _, _| anchors.set(slot, Some(bounds)),
        )
        .absolute()
        .top_0()
        .left_0()
        .size_full()
    }
}

/// Pixel helper for callers that hold plain floats.
pub fn px_bounds(x: f32, y: f32, w: f32, h: f32) -> Bounds<Pixels> {
    Bounds::new(point(px(x), px(y)), size(px(w), px(h)))
}

#[cfg(test)]
mod tests {
    use super::*;

    const MS: fn(u64) -> Duration = Duration::from_millis;

    #[test]
    fn ripple_spreads_and_fades_then_rests() {
        let mut ring = RippleRing::key();
        assert_eq!(ring.state(), None);
        ring.start();
        let (s0, a0) = ring.state().expect("playing");
        assert!((s0 - 1.0).abs() < 0.01 && (a0 - 0.5).abs() < 0.01);
        ring.tick(MS(190));
        let (s1, a1) = ring.state().expect("playing");
        assert!(s1 > 1.3 && a1 < 0.3, "{s1} {a1}");
        // RELEASE_RING is front-loaded: past halfway in time, past 80% in size.
        assert!(!ring.tick(MS(200)));
        assert_eq!(ring.state(), None);
    }

    #[test]
    fn the_call_bloom_reaches_two_point_two() {
        let mut ring = RippleRing::bloom();
        ring.start();
        ring.tick(MS(459));
        let (scale, alpha) = ring.state().expect("playing");
        assert!(scale > 2.1 && alpha < 0.01, "{scale} {alpha}");
    }

    #[test]
    fn fade_is_one_at_rest_and_120_ms_long() {
        let mut fade = Fade::default();
        assert_eq!(fade.value(), 1.0);
        fade.restart();
        assert!(fade.value() < 0.01);
        assert!(fade.tick(MS(60)));
        assert!((fade.value() - 0.5).abs() < 0.01);
        assert!(!fade.tick(MS(70)));
        assert_eq!(fade.value(), 1.0);
    }

    #[test]
    fn two_moments_run_the_third_waits() {
        let mut s = Scheduler::default();
        let t0 = MS(1000);
        assert_eq!(s.request(t0, MS(400), Priority::Normal), t0);
        assert_eq!(s.request(t0, MS(400), Priority::Normal), t0);
        // The third waits 120 ms.
        assert_eq!(
            s.request(t0, MS(400), Priority::Normal),
            t0 + COLLISION_WAIT
        );
    }

    #[test]
    fn exempt_moments_never_wait() {
        let mut s = Scheduler::default();
        let t0 = MS(0);
        s.request(t0, MS(500), Priority::Normal);
        s.request(t0, MS(500), Priority::Normal);
        assert_eq!(s.request(t0, MS(460), Priority::Exempt), t0);
        // And they count: a normal moment still waits.
        assert!(s.request(t0, MS(100), Priority::Normal) > t0);
    }

    #[test]
    fn a_place_that_frees_sooner_is_used() {
        let mut s = Scheduler::default();
        s.request(MS(0), MS(50), Priority::Normal);
        s.request(MS(0), MS(500), Priority::Normal);
        assert_eq!(s.request(MS(10), MS(100), Priority::Normal), MS(50));
    }

    #[test]
    fn idle_scheduler_and_settled_springs_want_no_frames() {
        let mut s = Scheduler::default();
        let mut bank = SpringBank::<3>::new(0.0, motion_tokens::press());
        let mut flights = Flights::<u8, 3>::default();
        let mut ring = RippleRing::key();
        let now = MS(5);
        assert!(s.is_idle(now));
        assert!(!bank.tick(MS(8)) && !flights.tick(MS(8)) && !ring.tick(MS(8)));
        // Busy while a moment plays, idle again after it ends (fake clock).
        s.request(now, MS(300), Priority::Normal);
        bank.set_target_all(1.0);
        flights.launch(1, motion_tokens::appear());
        ring.start();
        assert!(!s.is_idle(now + MS(299)));
        let mut t = now;
        let mut frames = 0;
        while bank.tick(MS(8)) | flights.tick(MS(8)) | ring.tick(MS(8)) {
            t += MS(8);
            frames += 1;
            assert!(frames < 600, "never settled");
        }
        assert!(s.is_idle(now + MS(300)));
        assert!(flights.is_empty());
        // Once everything is quiet, a tick asks for nothing.
        assert!(!bank.tick(MS(8)) && !flights.tick(MS(8)) && !ring.tick(MS(8)));
        let _ = t;
    }

    #[test]
    fn flights_hold_three_and_replace_the_oldest() {
        let mut f = Flights::<u8, 3>::default();
        for n in 1..=4 {
            f.launch(n, motion_tokens::appear());
        }
        let mut kept: Vec<u8> = f.active().map(|(n, _)| n).collect();
        kept.sort_unstable();
        assert_eq!(kept, [2, 3, 4]);
    }

    #[test]
    fn glide_lands_on_its_target() {
        let from = px_bounds(10.0, 10.0, 78.0, 78.0);
        let to = px_bounds(100.0, 500.0, 220.0, 64.0);
        let mut g = Glide::default();
        assert!(!g.live());
        g.start(from);
        assert!(g.live() && g.progress() == 0.0);
        assert_eq!(g.rect(to), from);
        let mut frames = 0;
        while g.tick(MS(8)) {
            frames += 1;
            assert!(frames < 400);
        }
        assert!(!g.live());
        let r = g.rect(to);
        assert!((f32::from(r.size.width) - 220.0).abs() < 0.5);
        assert!((f32::from(r.origin.y) - 500.0).abs() < 0.5);
    }

    #[test]
    fn anchors_remember_per_slot() {
        let a = Anchors::<2>::default();
        assert_eq!(a.get(0), None);
        let b = px_bounds(1.0, 2.0, 3.0, 4.0);
        a.clone().set(1, Some(b));
        assert_eq!(a.get(1), Some(b));
        assert_eq!(a.get(0), None);
        assert_eq!(a.get(9), None);
    }
}
