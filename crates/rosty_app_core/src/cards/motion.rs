//! One-shot card motion: a damped spring, the frame timeline and the per-frame changes each template makes.
//!
//! Springs use the app's motion tokens as (damping ratio, response in seconds): the panel drop is `sheetSettle`
//! (0.86, 0.38), the jelly squash `glassPressUp` (0.373, 0.25), the lens sweep `menuMorphOpen` (0.939, 0.6), the
//! Contact settle `morph` (1.0, 0.95) and fades `appear` (1.0, 0.45). Squash keeps the area (`sx * sy = 1`).
//!
//! Birthday, 2.6 s (0.0-0.9 s drop and squash; 0.5-1.4 s the lens sweeps the name; 0.9-1.6 s a specular glint travels
//! the rim; 0.3-1.8 s confetti). The glass optics ([`super::refraction`]) follow: the panel's displacement scale
//! follows the squash ([`Motion::refract`]) and the lens bends what is under it by [`Lens::amount`].

use super::model::Template;

/// Frames per second of a moving card.
pub const FPS: u32 = 15;

/// A spring from 0 to 1 with mass 1: `damping` is the damping ratio, `response` the undamped period in seconds.
/// Answers the position at `t` seconds (0 before the start).
pub fn spring(t: f32, damping: f32, response: f32) -> f32 {
    if t <= 0.0 {
        return 0.0;
    }
    let w0 = std::f32::consts::TAU / response;
    if damping >= 1.0 {
        // Critically damped (an overdamped token is treated as critical: the tokens use 1.0 at most).
        return 1.0 - (-w0 * t).exp() * (1.0 + w0 * t);
    }
    let wd = w0 * (1.0 - damping * damping).sqrt();
    let decay = (-damping * w0 * t).exp();
    1.0 - decay * ((wd * t).cos() + (damping * w0 / wd) * (wd * t).sin())
}

/// A damped wobble that starts at 0 and dies out: the jelly squash of a landing panel.
pub fn wobble(t: f32, damping: f32, response: f32) -> f32 {
    if t <= 0.0 {
        return 0.0;
    }
    let w0 = std::f32::consts::TAU / response;
    let wd = w0 * (1.0 - damping.min(0.99) * damping.min(0.99)).sqrt();
    (-damping * w0 * t).exp() * (wd * t).sin()
}

/// The lens that sweeps across the name once.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Lens {
    /// Where the lens is along its track, 0 (start) to 1 (end), from the `menuMorphOpen` spring.
    pub progress: f32,
    /// How strongly it bends, 0 to 1: nothing at either end of the track, most over the middle.
    pub amount: f32,
}

/// One piece of confetti in design pixels.
#[derive(Clone, Debug, PartialEq)]
pub struct Piece {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
    pub rotation: f32,
    pub alpha: f32,
    pub color: &'static str,
}

/// What changes from frame to frame. [`Motion::rest`] is the still card.
#[derive(Clone, Debug, PartialEq)]
pub struct Motion {
    /// Vertical offset of the panel in design pixels.
    pub panel_dy: f32,
    pub sx: f32,
    pub sy: f32,
    pub panel_alpha: f32,
    /// Where the glint's light stands, as a fraction of the panel width; `None` draws no glint.
    pub glint_x: Option<f32>,
    pub glint_alpha: f32,
    /// How strongly the panel's edge bends, 1 at rest; it grows with the squash (a wider, flatter panel is thinner
    /// glass at the rim, so the displacement scale follows the squash).
    pub refract: f32,
    /// The sweeping lens, if one is out.
    pub lens: Option<Lens>,
    pub qr_alpha: f32,
    pub confetti: Vec<Piece>,
}

impl Motion {
    /// The still card: everything at rest.
    pub fn rest() -> Self {
        Self {
            panel_dy: 0.0,
            sx: 1.0,
            sy: 1.0,
            panel_alpha: 1.0,
            glint_x: None,
            glint_alpha: 0.0,
            refract: 1.0,
            lens: None,
            qr_alpha: 1.0,
            confetti: Vec::new(),
        }
    }
}

/// One frame of a timeline and how long it shows.
#[derive(Clone, Debug, PartialEq)]
pub struct Frame {
    pub motion: Motion,
    pub duration_ms: u32,
}

/// The frames of one moving card.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Timeline {
    pub template: Template,
    /// Seconds of motion before the held still frame.
    pub moving_s: f32,
    pub hold_ms: u32,
}

impl Timeline {
    /// The timeline of `template`, or `None` for one that does not move.
    pub fn of(template: Template, hold_ms: u32) -> Option<Self> {
        let moving_s = match template {
            Template::Birthday => 2.6,
            Template::Contact => 1.2,
            Template::NewNumber => return None,
        };
        Some(Self {
            template,
            moving_s,
            hold_ms,
        })
    }

    /// `moving_s * FPS` frames in all (39 for Birthday): the moving ones at [`FPS`], then the still frame held for
    /// `hold_ms` in place of the last step. The last frame is exactly [`Motion::rest`].
    pub fn frames(&self) -> Vec<Frame> {
        let step_ms = 1000 / FPS;
        let count = (self.moving_s * FPS as f32).round() as u32 - 1;
        let mut frames: Vec<Frame> = (0..count)
            .map(|i| Frame {
                motion: self.at(i as f32 / FPS as f32),
                duration_ms: step_ms,
            })
            .collect();
        frames.push(Frame {
            motion: Motion::rest(),
            duration_ms: self.hold_ms,
        });
        frames
    }

    /// The motion at `t` seconds.
    pub fn at(&self, t: f32) -> Motion {
        match self.template {
            Template::Birthday => birthday(t),
            Template::Contact => contact(t),
            Template::NewNumber => Motion::rest(),
        }
    }
}

/// Distance the panel drops from, in design pixels.
const DROP: f32 = 120.0;

fn birthday(t: f32) -> Motion {
    // 0-0.9 s: the panel drops in (`sheetSettle`) and lands with a squash (`glassPressUp`).
    let p = spring(t, 0.86, 0.38);
    let squash = 0.06 * wobble(t - 0.18, 0.373, 0.25);
    let sx = 1.0 + squash;
    // 0.5-1.4 s: the lens sweeps the name once (`menuMorphOpen`), strongest over the name.
    let sweep = (t - LENS_START) / LENS_SECONDS;
    let lens = (0.0..1.0).contains(&sweep).then(|| {
        let progress = spring(t - LENS_START, 0.939, 0.6).min(1.0);
        // Strongest while the lens is over the name (mid-track), gone at both ends of the track.
        Lens {
            progress,
            amount: (std::f32::consts::PI * progress).sin().powf(0.8),
        }
    });
    // 0.9-1.6 s: a specular glint travels the rim once.
    let glint = (t - 0.9) / 0.7;
    let (glint_x, glint_alpha) = if (0.0..1.0).contains(&glint) {
        let eased = glint * glint * (3.0 - 2.0 * glint);
        (
            Some(-0.2 + 1.4 * eased),
            (std::f32::consts::PI * glint).sin(),
        )
    } else {
        (None, 0.0)
    };
    Motion {
        panel_dy: -DROP * (1.0 - p),
        sx,
        sy: 1.0 / sx,
        panel_alpha: (0.35 + p).min(1.0),
        glint_x,
        glint_alpha,
        refract: (1.0 + 5.0 * squash).clamp(0.5, 1.6),
        lens,
        qr_alpha: 1.0,
        confetti: confetti(t),
    }
}

/// When the lens starts its sweep, in seconds, and how long it takes.
pub const LENS_START: f32 = 0.5;
pub const LENS_SECONDS: f32 = 0.9;

fn contact(t: f32) -> Motion {
    let p = spring(t, 1.0, 0.95);
    let appear = spring(t - 0.3, 1.0, 0.45);
    let scale = 0.94 + 0.06 * p;
    Motion {
        panel_dy: 0.4 * DROP * (1.0 - p),
        sx: scale,
        sy: scale,
        panel_alpha: (0.5 + p).min(1.0),
        glint_x: None,
        glint_alpha: 0.0,
        refract: 1.0,
        lens: None,
        qr_alpha: appear.clamp(0.0, 1.0),
        confetti: Vec::new(),
    }
}

const CONFETTI: usize = 40;
const COLORS: [&str; 6] = [
    "#ffd84d", "#ff6f8e", "#5bc8ff", "#7ee08a", "#b48cff", "#ffffff",
];

/// A tiny deterministic generator, so every render of a card is the same.
struct Lcg(u32);

impl Lcg {
    fn next(&mut self) -> f32 {
        self.0 = self.0.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
        (self.0 >> 8) as f32 / (1u32 << 24) as f32
    }
}

/// 0.3-1.8 s: confetti bursts from above the panel, falls with drag and fades out.
fn confetti(t: f32) -> Vec<Piece> {
    let local = t - 0.3;
    if !(0.0..1.5).contains(&local) {
        return Vec::new();
    }
    let mut rng = Lcg(0x5eed_cafe);
    let fade = if local > 1.0 {
        1.0 - (local - 1.0) / 0.5
    } else {
        1.0
    };
    (0..CONFETTI)
        .map(|i| {
            let x0 = 360.0 + (rng.next() - 0.5) * 120.0;
            let vx = (rng.next() - 0.5) * 900.0;
            let vy = -500.0 - rng.next() * 500.0;
            let spin = (rng.next() - 0.5) * 900.0;
            let w = 8.0 + rng.next() * 8.0;
            // Ballistic with linear drag k: v(t) = v0 e^{-kt}, gravity g.
            let k = 2.2;
            let g = 900.0;
            let drag = (1.0 - (-k * local).exp()) / k;
            let x = x0 + vx * drag;
            let y = 120.0 + vy * drag + g * (local / k - drag / k);
            Piece {
                x,
                y,
                w,
                h: w * 0.45,
                rotation: spin * local,
                alpha: fade.clamp(0.0, 1.0),
                color: COLORS[i % COLORS.len()],
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_critical_spring_never_overshoots() {
        for i in 0..400 {
            let v = spring(i as f32 / 100.0, 1.0, 0.45);
            assert!(v <= 1.0 + 1e-6, "{v}");
        }
        assert!((spring(4.0, 1.0, 0.45) - 1.0).abs() < 1e-4);
    }

    #[test]
    fn the_press_spring_overshoots() {
        let peak = (0..300)
            .map(|i| spring(i as f32 / 300.0, 0.373, 0.25))
            .fold(0.0, f32::max);
        assert!(peak > 1.01, "{peak}");
    }

    #[test]
    fn timelines_end_on_the_still_frame() {
        for template in [Template::Birthday, Template::Contact] {
            let frames = Timeline::of(template, 600).expect("moves").frames();
            assert!(frames.len() >= 18, "{template:?}: {}", frames.len());
            let last = frames.last().expect("frames");
            assert_eq!(last.motion, Motion::rest());
            assert_eq!(last.duration_ms, 600);
            let total: u32 = frames.iter().map(|f| f.duration_ms).sum();
            assert!((1500..=3600).contains(&total), "{template:?}: {total} ms");
        }
        assert!(Timeline::of(Template::NewNumber, 600).is_none());
    }

    #[test]
    fn confetti_is_deterministic_and_gone_at_rest() {
        assert_eq!(confetti(0.8), confetti(0.8));
        assert_eq!(confetti(0.8).len(), CONFETTI);
        assert!(confetti(1.9).is_empty());
        assert!(birthday(2.0).confetti.is_empty());
    }
}
