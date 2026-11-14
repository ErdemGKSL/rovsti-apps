//! One-shot animated cards: a [`Timeline`], a [`Choreography`] per template, and the GIF, APNG and WebP writers.
//!
//! Every effect is a pure function of the time `t` in seconds, so frame `n` never depends on frame `n - 1` and the
//! output is deterministic. The numbers come from the motion tokens in `spring.rs`; the constants that were tuned by
//! looking at the frames are in [`tuned`].
//!
//! Play once, in all three formats: the GIF has no NETSCAPE looping block (a block, even with count 0, would loop
//! it), the APNG has `num_plays = 1` and the WebP `loop_count = 1`.

use std::borrow::Cow;

use takumi::prelude::{AnimatedPngOptions, AnimatedWebpOptions, AnimationFrame, Bitmap};

use super::CardError;
use super::scene::{self, CardContent, Piece, Pose, Template};
use super::spring;

/// A fixed frame rate timeline.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Timeline {
    /// Frames per second.
    pub fps: u32,
    /// Number of frames, the last one is the still.
    pub frames: u32,
}

impl Timeline {
    /// Birthday: 2.6 s, 39 frames at 15 fps.
    pub const BIRTHDAY: Timeline = Timeline {
        fps: 15,
        frames: 39,
    };
    /// Contact: 1.8 s, 27 frames at 15 fps.
    pub const CONTACT: Timeline = Timeline {
        fps: 15,
        frames: 27,
    };

    /// Total length in milliseconds.
    pub fn duration_ms(&self) -> u32 {
        self.frames * 1000 / self.fps
    }

    /// Start time of `frame` in seconds. Frame 0 is at 0 and the last frame at `(frames - 1) / fps`.
    pub fn time(&self, frame: u32) -> f64 {
        f64::from(frame) / f64::from(self.fps)
    }

    /// How long `frame` shows, in milliseconds. The remainders are spread so the sum is exactly `duration_ms`
    /// (66, 67, 67 at 15 fps).
    pub fn frame_ms(&self, frame: u32) -> u32 {
        self.boundary(frame + 1, 1000) - self.boundary(frame, 1000)
    }

    /// How long `frame` shows, in GIF hundredths of a second (7, 7, 6 at 15 fps; the sum is exact too).
    pub fn frame_cs(&self, frame: u32) -> u16 {
        (self.boundary(frame + 1, 100) - self.boundary(frame, 100)) as u16
    }

    fn boundary(&self, frame: u32, unit: u32) -> u32 {
        (frame * unit + self.fps / 2) / self.fps
    }
}

/// Constants chosen by looking at the exported frames (`target/checks/cards/frames`). Times in seconds, lengths in
/// reference pixels (a card 720 wide).
pub mod tuned {
    /// The panel starts this far above its rest position.
    pub const DROP_FROM: f32 = 720.0;
    /// The drop runs on a spring clock slowed to this factor, so it arrives around 0.55 s instead of 0.33 s.
    pub const DROP_CLOCK: f64 = 0.6;
    /// The drop is on its rest (97 percent of the way) when the jelly starts.
    pub const IMPACT_LEVEL: f64 = 0.97;
    /// Peak squash: the panel is this much shorter (and wider by `1 / (1 - x)`) at the impact.
    pub const SQUASH: f32 = 0.085;
    /// The squash relaxes in this many seconds on the `glassPressUp` clock (its own time is stretched).
    pub const SQUASH_CLOCK: f64 = 1.0;
    /// Refraction strength at the hardest squash, and the part of it the squash adds to 1.
    pub const REFRACTION_PEAK: f32 = 1.0;
    /// Lens sweep window.
    pub const LENS: (f64, f64) = (0.5, 1.4);
    /// Rim glint window.
    pub const GLINT: (f64, f64) = (0.9, 1.6);
    /// Confetti burst time and the flight times it spreads over.
    pub const CONFETTI_AT: f64 = 0.3;
    /// Shortest and longest flight, seconds.
    pub const FLIGHT: (f64, f64) = (0.85, 1.15);
    /// Number of confetti pieces.
    pub const PIECES: usize = 40;
    /// Gravity of the confetti, reference px per second squared.
    pub const GRAVITY: f64 = 1400.0;
    /// Seed of the confetti.
    pub const SEED: u64 = 0x5eed_ca4d_2026;
    /// Birthday is still from here.
    pub const BIRTHDAY_STILL: f64 = 2.0;
    /// Contact is still from here.
    pub const CONTACT_STILL: f64 = 1.2;
}

/// A tiny deterministic generator (xorshift64*), so the confetti is the same on every device and in every run.
#[derive(Clone, Debug)]
pub struct Rng(u64);

impl Rng {
    /// A generator from a seed (zero is mapped to a fixed odd number).
    pub fn new(seed: u64) -> Self {
        Self(if seed == 0 {
            0x9e37_79b9_7f4a_7c15
        } else {
            seed
        })
    }

    /// Next value in `0..1`.
    pub fn next_f64(&mut self) -> f64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        let v = self.0.wrapping_mul(0x2545_f491_4f6c_dd1d);
        (v >> 11) as f64 / (1u64 << 53) as f64
    }

    /// Next value in `lo..hi`.
    pub fn range(&mut self, lo: f64, hi: f64) -> f64 {
        lo + (hi - lo) * self.next_f64()
    }
}

/// Where one confetti piece starts, lands and how it moves in between.
#[derive(Clone, Copy, Debug)]
struct Flight {
    x0: f64,
    y0: f64,
    x_rest: f64,
    y_rest: f64,
    flight: f64,
    spin: f64,
    rot_rest: f64,
    w: f32,
    h: f32,
    color: usize,
}

/// The motion of one template.
#[derive(Clone, Debug)]
pub struct Choreography {
    template: Template,
    timeline: Timeline,
    still_from: f64,
    flights: Vec<Flight>,
    impact: f64,
}

fn smooth(p: f64) -> f64 {
    let p = p.clamp(0.0, 1.0);
    p * p * (3.0 - 2.0 * p)
}

impl Choreography {
    /// The choreography of `template`.
    pub fn for_template(template: Template) -> Self {
        match template {
            Template::Birthday => Self::birthday(),
            Template::Contact => Self::contact(),
        }
    }

    fn contact() -> Self {
        Self {
            template: Template::Contact,
            timeline: Timeline::CONTACT,
            still_from: tuned::CONTACT_STILL,
            flights: Vec::new(),
            impact: 0.0,
        }
    }

    fn birthday() -> Self {
        let rect = scene::panel_rect(Template::Birthday);
        let (px, py, pw, _, _) = rect;
        let mut rng = Rng::new(tuned::SEED);
        let mut flights = Vec::with_capacity(tuned::PIECES);
        for i in 0..tuned::PIECES {
            // Rest along the top edge, spread evenly with some jitter, so the pile reads as a line of confetti.
            let slot = (i as f64 + 0.5) / tuned::PIECES as f64;
            let x_rest =
                f64::from(px) + 24.0 + slot * (f64::from(pw) - 48.0) + rng.range(-6.0, 6.0);
            let w = rng.range(14.0, 24.0) as f32;
            let h = rng.range(8.0, 12.0) as f32;
            // Most pieces lie on the edge, a third are caught a little further in or out (a few sit inside the rounded
            // corners), so the pile is not a ruler-straight line.
            let spread = if rng.next_f64() < 0.35 {
                rng.range(-16.0, 12.0)
            } else {
                rng.range(-5.0, 3.0)
            };
            let y_rest = f64::from(py) - f64::from(h) * 0.5 + spread;
            let side = if rng.next_f64() < 0.5 { -1.0 } else { 1.0 };
            flights.push(Flight {
                x0: f64::from(px) + f64::from(pw) / 2.0 + rng.range(-30.0, 30.0),
                y0: f64::from(py) + 20.0,
                x_rest,
                y_rest,
                flight: rng.range(tuned::FLIGHT.0, tuned::FLIGHT.1),
                spin: side * rng.range(540.0, 1080.0),
                rot_rest: rng.range(-25.0, 25.0),
                w,
                h,
                color: (rng.next_f64() * scene::CONFETTI.len() as f64) as usize,
            });
        }
        let impact = spring::SHEET_SETTLE
            .first_reach(tuned::IMPACT_LEVEL)
            .unwrap_or(0.3)
            / tuned::DROP_CLOCK;
        Self {
            template: Template::Birthday,
            timeline: Timeline::BIRTHDAY,
            still_from: tuned::BIRTHDAY_STILL,
            flights,
            impact,
        }
    }

    /// The timeline of this choreography.
    pub fn timeline(&self) -> Timeline {
        self.timeline
    }

    /// The template this choreography animates.
    pub fn template(&self) -> Template {
        self.template
    }

    /// The time from which the card is a still, seconds.
    pub fn still_from(&self) -> f64 {
        self.still_from
    }

    /// The pose at the end: everything at rest, the confetti lying on the panel's top edge. The static render uses it.
    pub fn rest(&self) -> Pose {
        self.pose(self.still_from + 10.0)
    }

    /// The pose at time `t` seconds. From the still time on it is exactly the rest pose.
    pub fn pose(&self, t: f64) -> Pose {
        match self.template {
            Template::Birthday => self.birthday_pose(t),
            Template::Contact => self.contact_pose(t),
        }
    }

    fn birthday_pose(&self, t: f64) -> Pose {
        let rest = t >= self.still_from;
        let t = if rest { self.still_from + 10.0 } else { t };
        let mut pose = Pose::still();

        // 1. Drop and jelly. The drop is `sheetSettle`; the squash starts when the drop arrives and rides
        // `glassPressUp`, so the panel flattens, springs taller once and settles. Area is kept: sx * sy = 1.
        let drop = spring::SHEET_SETTLE.response(t * tuned::DROP_CLOCK);
        pose.panel_dy = tuned::DROP_FROM * (drop as f32 - 1.0);
        let jelly = {
            let local = (t - self.impact).max(0.0) / tuned::SQUASH_CLOCK;
            // 1 at the impact, 0 at rest, about -0.28 at the rebound peak.
            1.0 - spring::GLASS_PRESS_UP.response(local)
        };
        let a = if t < self.impact {
            0.0
        } else {
            jelly as f32 * tuned::SQUASH
        };
        pose.panel_sy = 1.0 - a;
        pose.panel_sx = 1.0 / pose.panel_sy;
        pose.refraction = tuned::REFRACTION_PEAK * (a.abs() / tuned::SQUASH);

        // 2. Lens across the name: position on `menuMorphOpen`, strength a smooth in-out over the window.
        let (l0, l1) = tuned::LENS;
        if t > l0 && t < l1 {
            let p = (t - l0) / (l1 - l0);
            let pos = spring::MENU_MORPH_OPEN.response((t - l0) / 0.5);
            let pos = pos.min(1.0) * 0.55 + smooth(p) * 0.45;
            pose.lens = Some((
                pos as f32,
                (smooth(p * 4.0) * smooth((1.0 - p) * 4.0)) as f32,
            ));
        }

        // 3. One glint around the rim.
        let (g0, g1) = tuned::GLINT;
        if t > g0 && t < g1 {
            let p = (t - g0) / (g1 - g0);
            pose.glint = Some((
                smooth(p) as f32 * 0.9 + 0.05,
                (std::f64::consts::PI * p).sin() as f32,
            ));
        }

        // 4. Confetti.
        if t >= tuned::CONFETTI_AT {
            pose.confetti = self
                .flights
                .iter()
                .map(|f| piece_at(f, t - tuned::CONFETTI_AT))
                .collect();
        }
        pose
    }

    fn contact_pose(&self, t: f64) -> Pose {
        let rest = t >= self.still_from;
        let t = if rest { self.still_from + 10.0 } else { t };
        let mut pose = Pose::still();
        let morph = spring::MORPH.response(t);
        pose.panel_dy = 48.0 * (1.0 - morph as f32);
        let s = 0.94 + 0.06 * morph as f32;
        pose.panel_sx = s;
        pose.panel_sy = s;
        // The gradient drift eases to rest on the same critical spring, run slower.
        pose.drift = 1.0 - spring::MORPH.response(t * 0.5) as f32;
        pose.qr_alpha = spring::APPEAR.response(t - 0.4) as f32;
        pose.refraction = (1.0 - morph as f32) * 0.6;
        pose
    }

    /// The pose of `frame`.
    pub fn frame_pose(&self, frame: u32) -> Pose {
        self.pose(self.timeline.time(frame))
    }
}

/// One piece `tau` seconds after the burst: horizontal motion with drag, vertical motion under gravity solved to end
/// on the rest height, spin that dies out, and a small hop on landing.
fn piece_at(f: &Flight, tau: f64) -> Piece {
    let u = (tau / f.flight).clamp(0.0, 1.0);
    let drag = 2.5;
    let x = f.x0 + (f.x_rest - f.x0) * (1.0 - (-drag * u).exp()) / (1.0 - (-drag).exp());
    let g = tuned::GRAVITY;
    let v0 = (f.y_rest - f.y0 - 0.5 * g * f.flight * f.flight) / f.flight;
    let t_air = tau.min(f.flight);
    let mut y = f.y0 + v0 * t_air + 0.5 * g * t_air * t_air;
    if tau > f.flight {
        // A short hop on landing, up at most 5 px and back to the rest height in 0.3 s.
        let s = ((tau - f.flight) / 0.3).clamp(0.0, 1.0);
        y -= 5.0 * (std::f64::consts::TAU * s).sin().abs() * (1.0 - s);
    }
    let rot = f.rot_rest + f.spin * (1.0 - u).powi(3);
    Piece {
        x: x as f32,
        y: y as f32,
        rot: rot as f32,
        w: f.w,
        h: f.h,
        color: f.color,
        alpha: spring::APPEAR.response(tau * 4.0) as f32,
    }
}

/// Which animated format to write.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AnimFormat {
    /// GIF89a, one global palette, no looping block.
    Gif,
    /// APNG with `num_plays = 1`.
    Apng,
    /// Lossless animated WebP with `loop_count = 1`.
    Webp,
}

/// Renders every frame of the animation of `template` at `width` pixels wide.
pub fn render_frames(
    template: Template,
    content: &CardContent,
    width: u32,
) -> Result<Vec<Bitmap>, CardError> {
    let choreography = Choreography::for_template(template);
    (0..choreography.timeline.frames)
        .map(|frame| scene::render(template, content, &choreography.frame_pose(frame), width))
        .collect()
}

/// The static card of `template`: the animation's last pose.
pub fn render_static(
    template: Template,
    content: &CardContent,
    width: u32,
) -> Result<Bitmap, CardError> {
    let choreography = Choreography::for_template(template);
    scene::render(template, content, &choreography.rest(), width)
}

/// Renders and encodes the animation of `template`.
pub fn render_animated(
    template: Template,
    content: &CardContent,
    width: u32,
    format: AnimFormat,
) -> Result<Vec<u8>, CardError> {
    let frames = render_frames(template, content, width)?;
    encode(
        &frames,
        Choreography::for_template(template).timeline,
        format,
    )
}

/// Encodes `frames` (all the same size) in `format`, playing once.
pub fn encode(
    frames: &[Bitmap],
    timeline: Timeline,
    format: AnimFormat,
) -> Result<Vec<u8>, CardError> {
    let mut out = Vec::new();
    match format {
        AnimFormat::Gif => write_gif(frames, timeline, &mut out)?,
        AnimFormat::Apng => {
            let options = AnimatedPngOptions::builder().loop_count(Some(1)).build();
            takumi::write_animated_png(&takumi_frames(frames, timeline), &mut out, options)
                .map_err(|e| CardError::Encode(e.to_string()))?;
        }
        AnimFormat::Webp => {
            let options = AnimatedWebpOptions::builder()
                .loop_count(Some(1))
                .lossless(true)
                .build();
            takumi::write_animated_webp(
                Cow::Owned(takumi_frames(frames, timeline)),
                &mut out,
                options,
            )
            .map_err(|e| CardError::Encode(e.to_string()))?;
        }
    }
    Ok(out)
}

fn takumi_frames(frames: &[Bitmap], timeline: Timeline) -> Vec<AnimationFrame> {
    frames
        .iter()
        .enumerate()
        .map(|(i, bitmap)| AnimationFrame::new(bitmap.clone(), timeline.frame_ms(i as u32)))
        .collect()
}

/// 4x4 Bayer thresholds, 0..15.
const BAYER: [[i32; 4]; 4] = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]];

/// Maps pixels to palette indices with an ordered dither. The dither depends on the pixel position only, so pixels
/// that do not change between frames map to the same index and nothing shimmers.
struct Quantizer {
    net: color_quant::NeuQuant,
    cache: std::collections::HashMap<u32, u8>,
}

impl Quantizer {
    /// A palette of 256 colours learned from `reference`.
    fn new(reference: &Bitmap) -> Self {
        Self {
            net: color_quant::NeuQuant::new(10, 256, reference.as_raw()),
            cache: std::collections::HashMap::new(),
        }
    }

    fn palette(&self) -> Vec<u8> {
        self.net.color_map_rgb()
    }

    fn indices(&mut self, bitmap: &Bitmap) -> Vec<u8> {
        let width = bitmap.width() as usize;
        let raw = bitmap.as_raw();
        let mut out = Vec::with_capacity(raw.len() / 4);
        for (i, px) in raw.as_chunks::<4>().0.iter().enumerate() {
            let (x, y) = (i % width, i / width);
            let bias = (BAYER[y & 3][x & 3] - 7) / 4;
            let shifted = |c: u8| (i32::from(c) + bias).clamp(0, 255) as u8;
            let key = u32::from_be_bytes([0, shifted(px[0]), shifted(px[1]), shifted(px[2])]);
            let net = &self.net;
            let index = *self.cache.entry(key).or_insert_with(|| {
                net.index_of(&[(key >> 16) as u8, (key >> 8) as u8, key as u8, 255]) as u8
            });
            out.push(index);
        }
        out
    }
}

/// Writes a GIF with one global palette learned from the last frame (so the held still is clean), and no NETSCAPE
/// extension: without it viewers play the file once and stop on the last frame.
pub fn write_gif(
    frames: &[Bitmap],
    timeline: Timeline,
    out: &mut Vec<u8>,
) -> Result<(), CardError> {
    let encode_err = |e: gif::EncodingError| CardError::Encode(e.to_string());
    let last = frames
        .last()
        .ok_or_else(|| CardError::Encode("no frames".into()))?;
    let (w, h) = (last.width(), last.height());
    let mut quantizer = Quantizer::new(last);
    let palette = quantizer.palette();
    let mut encoder =
        gif::Encoder::new(&mut *out, w as u16, h as u16, &palette).map_err(encode_err)?;
    // Each frame after the first stores only the box that changed since the previous one (disposal Keep leaves the
    // rest as it was), which keeps a card with a still background small. A frame that did not change at all is a
    // single pixel written again, so its delay still counts.
    let mut previous: Option<Vec<u8>> = None;
    for (i, bitmap) in frames.iter().enumerate() {
        let indices = quantizer.indices(bitmap);
        let (left, top, fw, fh) = match &previous {
            None => (0, 0, w as usize, h as usize),
            Some(before) => changed_box(before, &indices, w as usize, h as usize),
        };
        let mut buffer = Vec::with_capacity(fw * fh);
        for row in top..top + fh {
            buffer
                .extend_from_slice(&indices[row * w as usize + left..row * w as usize + left + fw]);
        }
        let frame = gif::Frame {
            left: left as u16,
            top: top as u16,
            width: fw as u16,
            height: fh as u16,
            buffer: Cow::Owned(buffer),
            delay: timeline.frame_cs(i as u32),
            dispose: gif::DisposalMethod::Keep,
            ..gif::Frame::default()
        };
        encoder.write_frame(&frame).map_err(encode_err)?;
        previous = Some(indices);
    }
    drop(encoder);
    Ok(())
}

/// The smallest box `(left, top, width, height)` that holds every index that differs; a 1 x 1 box if none does.
fn changed_box(before: &[u8], after: &[u8], w: usize, h: usize) -> (usize, usize, usize, usize) {
    let (mut x0, mut y0, mut x1, mut y1) = (w, h, 0, 0);
    for y in 0..h {
        let row = y * w;
        if before[row..row + w] == after[row..row + w] {
            continue;
        }
        for x in 0..w {
            if before[row + x] != after[row + x] {
                x0 = x0.min(x);
                x1 = x1.max(x + 1);
                y0 = y0.min(y);
                y1 = y1.max(y + 1);
            }
        }
    }
    if x1 == 0 {
        (0, 0, 1, 1)
    } else {
        (x0, y0, x1 - x0, y1 - y0)
    }
}
