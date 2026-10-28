//! The Call aura (signature moment S6): three soft radial blobs behind the
//! avatar that drift on slow loops and change energy with the call state.
//!
//! It is its own view, so its per-frame repaint never re-renders the In-Call
//! screen. Each blob is one radial-gradient quad painted from a canvas with
//! no allocation; it loops only while it is on screen and the window is
//! active, and holds still under reduced motion.

use std::{
    f32::consts::TAU,
    time::{Duration, Instant},
};

use gpui::{
    Bounds, Context, GradientKind, Hsla, IntoElement, Render, Styled, Task, Window, canvas, fill,
    linear_color_stop, linear_gradient, point, px, size,
};
use rovsti_ui::{
    current_theme,
    motion::Motion,
    motion_tokens,
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, mix},
};

/// Where the avatar centre sits below the top of the screen.
pub const CENTER_Y: f32 = 152.0;

const DIAMETERS: [f32; 3] = [320.0, 260.0, 220.0];
const PERIODS: [f32; 3] = [7.0, 9.0, 11.0];
const PHASES: [f32; 3] = [0.0, 2.1, 4.2];
const ORBIT: f32 = 36.0;
/// Seconds after which every loop (7, 9 and 11 s) lines up again.
const WRAP: f32 = 693.0;
const BREATH_HZ: f32 = 0.8;
/// Seconds between the two beats of the waiting-call pulse.
const WAITING_GAP: f32 = 0.32;
/// The drift alone repaints at about 30 Hz: it is a 7 to 11 s loop, far too
/// slow to show the difference, and it halves the cost of a long call.
const DRIFT_FRAME: Duration = Duration::from_millis(33);

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Phase {
    #[default]
    Dialing,
    Active,
    Holding,
    Ended,
    Failed,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct AuraState {
    pub phase: Phase,
    pub hue: Option<usize>,
    pub muted: bool,
    pub speaker: bool,
}

pub struct Aura {
    /// Where the blobs centre, below the top of the screen.
    center_y: f32,
    motion: Motion,
    state: AuraState,
    clock: f32,
    energy: Spring,
    mono: Spring,
    widen: Spring,
    collapse: Spring,
    pulse: Spring,
    speed: Spring,
    breath: Spring,
    danger: Spring,
    intro: Spring,
    /// Seconds since a waiting call rang (the double pulse), while it plays.
    waiting: Option<(f32, bool)>,
    /// When the drift clock was last advanced (`None` while it is not drifting).
    last_drift: Option<Instant>,
    /// A springs-moving frame was requested: vsync repaints cover the drift too.
    animating: bool,
    /// The next drift-only repaint.
    drift_timer: Option<Task<()>>,
}

fn spring(value: f32, params: rovsti_ui::spring::SpringParams) -> Spring {
    Spring::new(value, params).with_epsilon(0.002)
}

impl Aura {
    pub fn new() -> Self {
        let mut intro = spring(0.0, motion_tokens::appear());
        intro.set_target(1.0);
        Self {
            center_y: CENTER_Y,
            motion: Motion::default(),
            state: AuraState::default(),
            clock: 0.0,
            energy: spring(1.0, motion_tokens::select()),
            mono: spring(0.0, motion_tokens::select()),
            widen: spring(1.0, motion_tokens::select()),
            collapse: spring(1.0, motion_tokens::sheet_dismiss()),
            pulse: spring(1.0, motion_tokens::glow()),
            speed: spring(1.0, motion_tokens::select()),
            breath: spring(1.0, motion_tokens::select()),
            danger: spring(0.0, motion_tokens::select()),
            intro,
            waiting: None,
            last_drift: None,
            animating: false,
            drift_timer: None,
        }
    }

    /// An aura centred `y` px below the top (the Incoming Call avatar sits lower).
    pub fn centered_at(mut self, y: f32) -> Self {
        self.center_y = y;
        self
    }

    /// Fades the aura in again (a new call, or the screen coming back). Under
    /// reduced motion the blobs are simply there, static.
    pub fn restart(&mut self, reduced: bool, cx: &mut Context<Self>) {
        if reduced {
            self.intro.snap(1.0);
        } else {
            self.intro.snap(0.0);
            self.intro.set_target(1.0);
        }
        self.collapse.snap(1.0);
        self.pulse.snap(1.0);
        cx.notify();
    }

    /// A second caller is waiting: one soft double pulse.
    pub fn waiting_pulse(&mut self, cx: &mut Context<Self>) {
        self.pulse.snap(1.08);
        self.pulse.set_target(1.0);
        self.waiting = Some((0.0, false));
        cx.notify();
    }

    pub fn set(&mut self, state: AuraState, cx: &mut Context<Self>) {
        if state == self.state {
            return;
        }
        let connected = self.state.phase == Phase::Dialing && state.phase == Phase::Active;
        self.state = state;
        let ended = matches!(state.phase, Phase::Ended | Phase::Failed);
        let energy = if ended {
            0.0
        } else if state.muted {
            0.6
        } else if state.phase == Phase::Holding {
            0.8
        } else {
            1.0
        };
        self.energy.set_target(energy);
        self.mono
            .set_target(f32::from(u8::from(state.phase == Phase::Holding)));
        self.widen
            .set_target(if state.speaker { 1.15 } else { 1.0 });
        self.collapse.set_target(if ended { 0.6 } else { 1.0 });
        self.speed.set_target(match (state.phase, state.muted) {
            (Phase::Holding, _) => 0.4,
            (_, true) => 0.6,
            _ => 1.0,
        });
        self.breath
            .set_target(f32::from(u8::from(state.phase == Phase::Dialing)));
        self.danger
            .set_target(f32::from(u8::from(state.phase == Phase::Failed)));
        if connected {
            // The connect pulse: a quick overshoot that settles back.
            self.pulse.snap(1.12);
            self.pulse.set_target(1.0);
        }
        cx.notify();
    }

    fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        for s in [
            &mut self.energy,
            &mut self.mono,
            &mut self.widen,
            &mut self.collapse,
            &mut self.pulse,
            &mut self.speed,
            &mut self.breath,
            &mut self.danger,
            &mut self.intro,
        ] {
            moving |= s.tick(dt);
        }
        if let Some((t, second)) = &mut self.waiting {
            *t += dt.as_secs_f32();
            moving = true;
            if !*second && *t >= WAITING_GAP {
                *second = true;
                self.pulse.snap(1.08);
                self.pulse.set_target(1.0);
            }
            if *t >= WAITING_GAP + 0.6 {
                self.waiting = None;
            }
        }
        moving
    }

    /// Advances the drift clock by the real time since it last moved.
    fn drift(&mut self) {
        let now = Instant::now();
        let dt = self
            .last_drift
            .map_or(0.0, |t| now.duration_since(t).as_secs_f32().min(0.1));
        self.last_drift = Some(now);
        self.clock = (self.clock + dt * self.speed.value) % WRAP;
    }

    /// The three blob colours for `theme`, before alpha.
    fn colors(&self, theme: Theme) -> [Hsla; 3] {
        let (top, bottom) = theme::hue_pair(theme, self.state.hue);
        let calls = call_colors(theme);
        let neutral = if theme.is_dark() {
            gpui::hsla(0.0, 0.0, 0.6, 1.0)
        } else {
            gpui::hsla(0.0, 0.0, 0.55, 1.0)
        };
        let mono = self.mono.value.clamp(0.0, 1.0);
        let danger = self.danger.value.clamp(0.0, 1.0);
        [top, bottom, theme::accent_glow(theme)].map(|c| {
            let c = mix(c, neutral, 0.8 * mono);
            let c = mix(c, calls.warning, 0.25 * mono);
            mix(c, calls.danger, 0.7 * danger)
        })
    }
}

impl Render for Aura {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let drift = !window.prefers_reduced_motion() && window.is_window_active();
        let mut motion = std::mem::take(&mut self.motion);
        let mut animating = false;
        motion.frame(window, |dt| {
            animating = self.tick(dt);
            animating
        });
        self.motion = motion;
        self.animating = animating;
        if drift && self.collapse.value > 0.62 {
            self.drift();
            // Springs moving: vsync frames already repaint. Otherwise only the
            // drift moves, and a ~30 Hz timer is enough.
            if !animating && self.drift_timer.is_none() {
                self.drift_timer = Some(cx.spawn(async move |this, cx| {
                    cx.background_executor().timer(DRIFT_FRAME).await;
                    this.update(cx, |this, cx| {
                        this.drift_timer = None;
                        cx.notify();
                    })
                    .ok();
                }));
            }
        } else {
            self.last_drift = None;
            self.drift_timer = None;
        }

        let colors = self.colors(theme);
        let base_alpha = if theme.is_dark() { 0.28 } else { 0.18 };
        let boost = ((self.pulse.value - 1.0) / 0.12).clamp(0.0, 1.0) * 0.15;
        let alpha = ((base_alpha + boost) * self.energy.value * self.intro.value).clamp(0.0, 1.0);
        let intro_scale = 0.6 + 0.4 * self.intro.value;
        let breath = 1.0 + 0.06 * self.breath.value * (TAU * BREATH_HZ * self.clock).sin();
        let scale =
            self.widen.value * self.collapse.value * self.pulse.value * breath * intro_scale;
        let clock = self.clock;
        let center_y = self.center_y;

        canvas(
            |_, _, _| (),
            move |bounds, (), window, _| {
                let cx = bounds.origin.x + bounds.size.width * 0.5;
                let cy = bounds.origin.y + px(center_y);
                for i in 0..3 {
                    let angle = TAU * clock / PERIODS[i] + PHASES[i];
                    let wobble = 1.0 + 0.04 * angle.sin();
                    let diameter = px(DIAMETERS[i] * scale * wobble);
                    let centre = point(cx + px(ORBIT * angle.cos()), cy + px(ORBIT * angle.sin()));
                    let blob = Bounds::new(
                        point(centre.x - diameter * 0.5, centre.y - diameter * 0.5),
                        size(diameter, diameter),
                    );
                    let c = colors[i];
                    let background = linear_gradient(
                        0.0,
                        linear_color_stop(Hsla { a: alpha, ..c }, 0.0),
                        linear_color_stop(Hsla { a: 0.0, ..c }, 1.0),
                    )
                    .gradient_kind(GradientKind::Radial);
                    window.paint_quad(fill(blob, background));
                }
            },
        )
        .absolute()
        .size_full()
    }
}
