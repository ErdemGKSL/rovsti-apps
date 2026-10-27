//! Tidepool: a liquid-glass submarine HUD for gpui-fusion.
//!
//! Glass chrome floats over a living deep-sea backdrop. Bioluminescent orbs
//! drift behind the glass, and the moving content is what makes the refraction
//! readable.
//!
//! - **Dive zones tab bar.** Reef · Kelp · Trench · Wreck. The puck springs
//!   between zones and can be dragged. While held, it grows into a clear,
//!   dispersive lens and squashes and stretches with its velocity, and the bar
//!   grows and leans toward the drag. Changing zone cross-fades the whole
//!   ocean palette and rolls the depth readout with a spring.
//! - **Depth HUD ↔ tab bar morph.** A full card (depth, O₂, water temperature)
//!   morphs into an inline pill beside the bar. One 500 ms timeline drives it,
//!   with spring-shaped, staggered axes. The "HUD" chip toggles it, and so does
//!   tapping the HUD.
//! - **Sonar button.** Press spring, a glow that follows the finger, a tanh
//!   elastic pull when dragged, and on release two sonar rings spread out.
//! - **Day dive / Night dive.** Glass tint, labels, the water and the shadows
//!   cross-fade through a spring, so toggling mid-fade reverses smoothly.
//! - **OPTIONAL animated window resize** (`ANIMATED_RESIZE`, toggled by the
//!   "Resize" chip). See references/09-animated-resize.md.
//!
//! Run with: cargo run --example glass_demo
//! On macOS during development, add `--features gpui_platform/runtime_shaders`
//! to the gpui_platform dependency.

use std::time::{Duration, Instant};

use gpui::{
    App, Bounds, Context, Div, Hsla, MouseButton, MouseDownEvent, MouseMoveEvent, MouseUpEvent,
    Pixels, Point, Render, Size, Stateful, Window, WindowBounds, WindowOptions, div, hsla,
    linear_color_stop, linear_gradient, prelude::*, px, rgb, size,
};
use liquid_glass_kit::{
    glass::{GlassInteraction, GlassSpec, glass, glass_interactive},
    motion_tokens::{self, curve, gesture},
    spring::{FrameClock, Spring, SpringRect, Timeline},
    tokens::{Colors, Theme, metrics, mix, shadow, typography},
};

/// Project option: animate layout when the window is resized. Set to `false`
/// for apps that want classic instant reflow (the chip can still toggle it).
pub const ANIMATED_RESIZE: bool = true;

const TABS: [&str; 4] = ["Reef", "Kelp", "Trench", "Wreck"];
const BAR_PAD: f32 = 4.0;
const BUTTON: f32 = 96.0;
const ROW_H: f32 = 56.0;
const GAP: f32 = 10.0;
const INLINE_TAB_W: f32 = 60.0;
const INLINE_HUD_W: f32 = 142.0;
/// A sonar ping lasts longer than a UI release ring and spreads further.
const SONAR_PING: Duration = Duration::from_millis(1100);

/// One ocean look per dive zone.
#[derive(Clone, Copy)]
struct Palette {
    surface: Hsla,
    floor: Hsla,
    orbs: [Hsla; 4],
    depth_m: f32,
}

fn zone_palette(zone: usize) -> Palette {
    let c = |hex: u32| -> Hsla { rgb(hex).into() };
    match zone {
        0 => Palette {
            surface: c(0x2fd6ff),
            floor: c(0x0a4fb8),
            orbs: [c(0xff7e5f), c(0xfeb47b), c(0xff6fd8), c(0x3ee7c1)],
            depth_m: 12.0,
        },
        1 => Palette {
            surface: c(0x5fd3a8),
            floor: c(0x0b3d33),
            orbs: [c(0xc6f36b), c(0xf9d423), c(0x7cffcb), c(0x2fa36b)],
            depth_m: 24.0,
        },
        2 => Palette {
            surface: c(0x2a3a8c),
            floor: c(0x04060f),
            orbs: [c(0x9b4dff), c(0x00f0ff), c(0xff2fd0), c(0x4d7cff)],
            depth_m: 860.0,
        },
        _ => Palette {
            surface: c(0x3f8a8c),
            floor: c(0x0c1720),
            orbs: [c(0xffb36b), c(0xe2553f), c(0x35d0b8), c(0xffd27a)],
            depth_m: 48.0,
        },
    }
}

fn mix_palette(a: &Palette, b: &Palette, t: f32) -> Palette {
    Palette {
        surface: mix(a.surface, b.surface, t),
        floor: mix(a.floor, b.floor, t),
        orbs: [0, 1, 2, 3].map(|i| mix(a.orbs[i], b.orbs[i], t)),
        depth_m: lerp(a.depth_m, b.depth_m, t),
    }
}

pub struct Demo {
    // Theme: target plus a 0 (light) → 1 (dark) cross-fade spring.
    pub theme: Theme,
    theme_t: Spring,
    pub animated_resize: bool,
    clock: FrameClock,
    started: Instant,
    // Ocean: palette cross-fade between dive zones, and a rolling depth readout.
    palette_from: Palette,
    zone_fade: Spring,
    depth: Spring,
    // Layout springs: [x, y, w, h] in window px.
    bar_rect: SpringRect,
    hud_rect: SpringRect,
    button_rect: SpringRect,
    // Depth HUD morph.
    pub collapsed: bool,
    morph: Timeline,
    morph_from: Option<([f32; 4], [f32; 4])>,
    // Tab puck. Position is in tab units (0.0 = first tab), so it stays on its
    // tab while the bar resizes or morphs.
    pub selected: usize,
    puck_pos: Spring,
    puck_press: Spring,
    puck_sx: Spring,
    puck_sy: Spring,
    bar_drift: Spring,
    dragging: Option<f32>, // grab offset inside the puck, in tab units
    drag_start_x: f32,     // window px, for the bar's drift
    last_drag: Option<(f32, Instant)>,
    drag_velocity: f32, // tab units per second
    // Round button.
    button_press: Spring,
    button_focus: Option<Point<Pixels>>,
    button_grab: Option<Point<Pixels>>,
    button_ox: Spring,
    button_oy: Spring,
    ring: Timeline,
}

/// Target layout derived from the viewport and the bar state. Pure function.
struct Layout {
    bar: [f32; 4],
    hud: [f32; 4],
    button: [f32; 4],
}

fn layout(viewport: Size<Pixels>, collapsed: bool) -> Layout {
    let (vw, vh) = (viewport.width.as_f32(), viewport.height.as_f32());
    let margin = metrics::TAB_BAR_MARGIN.as_f32() * 2.0;
    let row_y = vh - margin - ROW_H;
    let (bar, hud) = if collapsed {
        // Inline: compact bar + compact HUD side by side on one row.
        let bar_w = INLINE_TAB_W * TABS.len() as f32 + BAR_PAD * 2.0;
        let x0 = (vw - (bar_w + GAP + INLINE_HUD_W)) / 2.0;
        (
            [x0, row_y, bar_w, ROW_H],
            [x0 + bar_w + GAP, row_y, INLINE_HUD_W, ROW_H],
        )
    } else {
        // Expanded: full-width bar, depth HUD stacked above it.
        let tab_w = ((vw - margin * 2.0 - BAR_PAD * 2.0) / TABS.len() as f32).clamp(64.0, 110.0);
        let bar_w = tab_w * TABS.len() as f32 + BAR_PAD * 2.0;
        let x = (vw - bar_w) / 2.0;
        (
            [x, row_y, bar_w, ROW_H],
            [x, row_y - GAP - ROW_H, bar_w, ROW_H],
        )
    };
    let top = bar[1].min(hud[1]);
    Layout {
        bar,
        hud,
        // Centred in the free space above the chrome.
        button: [(vw - BUTTON) / 2.0, (top - BUTTON) / 2.0, BUTTON, BUTTON],
    }
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

impl Default for Demo {
    fn default() -> Self {
        Self::new()
    }
}

impl Demo {
    pub fn new() -> Self {
        Self {
            theme: Theme::Light,
            theme_t: Spring::new(0.0, motion_tokens::theme_fade()),
            animated_resize: ANIMATED_RESIZE,
            clock: FrameClock::default(),
            started: Instant::now(),
            palette_from: zone_palette(0),
            zone_fade: Spring::new(1.0, motion_tokens::theme_fade()),
            depth: Spring::new(zone_palette(0).depth_m, motion_tokens::sheet_snap())
                .with_epsilon(0.05),
            bar_rect: SpringRect::new(motion_tokens::resize()),
            hud_rect: SpringRect::new(motion_tokens::resize()),
            button_rect: SpringRect::new(motion_tokens::resize()),
            collapsed: false,
            morph: Timeline::new(curve::MORPH),
            morph_from: None,
            selected: 0,
            puck_pos: Spring::new(0.0, motion_tokens::select()).with_epsilon(0.001),
            puck_press: Spring::new(0.0, motion_tokens::press()),
            puck_sx: Spring::new(1.0, motion_tokens::puck_scale_x()),
            puck_sy: Spring::new(1.0, motion_tokens::puck_scale_y()),
            bar_drift: Spring::new(0.0, motion_tokens::container_settle()),
            dragging: None,
            drag_start_x: 0.0,
            last_drag: None,
            drag_velocity: 0.0,
            button_press: Spring::new(0.0, motion_tokens::glow()),
            button_focus: None,
            button_grab: None,
            button_ox: Spring::new(0.0, motion_tokens::glow()).with_epsilon(0.1),
            button_oy: Spring::new(0.0, motion_tokens::glow()).with_epsilon(0.1),
            ring: Timeline::new(SONAR_PING),
        }
    }

    // ---------------------------------------------------------------- state

    pub fn toggle_theme(&mut self) {
        self.theme = if self.theme.is_dark() {
            Theme::Light
        } else {
            Theme::Dark
        };
        self.theme_t
            .set_target(if self.theme.is_dark() { 1.0 } else { 0.0 });
    }

    /// Starts the bar ↔ depth HUD morph from wherever the shapes are now,
    /// so interrupting a running morph never jumps.
    pub fn toggle_hud(&mut self) {
        self.collapsed = !self.collapsed;
        self.morph_from = Some((self.bar_rect.value(), self.hud_rect.value()));
        self.morph.start(Instant::now());
    }

    fn select(&mut self, index: usize) {
        let index = index.min(TABS.len() - 1);
        if index != self.selected {
            // Cross-fade from whatever is on screen now, even mid-fade.
            self.palette_from = self.palette();
            self.zone_fade.snap(0.0);
            self.zone_fade.set_target(1.0);
            self.depth.set_target(zone_palette(index).depth_m);
        }
        self.selected = index;
        self.puck_pos
            .retarget(self.selected as f32, motion_tokens::select());
    }

    /// The ocean palette currently on screen.
    fn palette(&self) -> Palette {
        mix_palette(
            &self.palette_from,
            &zone_palette(self.selected),
            self.zone_fade.value.clamp(0.0, 1.0),
        )
    }

    /// Current animated rects (bar, HUD, button), for headless tests.
    pub fn rects(&self) -> ([f32; 4], [f32; 4], [f32; 4]) {
        (
            self.bar_rect.value(),
            self.hud_rect.value(),
            self.button_rect.value(),
        )
    }

    /// Current cross-fade position, 0 = light … 1 = dark.
    fn dark_t(&self) -> f32 {
        self.theme_t.value.clamp(0.0, 1.0)
    }

    fn colors(&self) -> Colors {
        Colors::lerp(&Theme::Light.colors(), &Theme::Dark.colors(), self.dark_t())
    }

    fn spec(&self, preset: fn(Theme) -> GlassSpec) -> GlassSpec {
        preset(Theme::Light).lerp(preset(Theme::Dark), self.dark_t())
    }

    /// Bar origin x (including drift), y, and tab width, from the live rect.
    fn bar_geometry(&self) -> (f32, f32, f32) {
        let [x, y, w, _] = self.bar_rect.value();
        (
            x + self.bar_drift.value,
            y,
            (w - BAR_PAD * 2.0) / TABS.len() as f32,
        )
    }

    /// Pointer x → position in tab units, relative to the live bar.
    fn pointer_to_tabs(&self, pointer_x: Pixels) -> f32 {
        let (x, _, tab_w) = self.bar_geometry();
        (pointer_x.as_f32() - x - BAR_PAD) / tab_w
    }

    /// Steps every spring and timeline with the real frame delta.
    /// Returns true while anything is still moving.
    pub fn tick(&mut self, window: &Window) -> bool {
        let now = Instant::now();
        let dt = self.clock.tick();
        let reduced = window.prefers_reduced_motion();
        if reduced {
            self.morph.finish();
            self.ring.finish();
        }

        // ---- Layout: morph timeline, or resize springs.
        let target = layout(window.viewport_size(), self.collapsed);
        let animate = self.animated_resize && !reduced;
        match self.morph_from.filter(|_| self.morph.running(now)) {
            Some((from_bar, from_hud)) => {
                let a = curve::morph_axes(self.morph.progress(now), self.collapsed);
                let (tb, tp) = (target.bar, target.hud);
                let bar = [
                    lerp(from_bar[0], tb[0], a.x),
                    lerp(from_bar[1], tb[1], a.y),
                    lerp(from_bar[2], tb[2], a.bar_width),
                    tb[3],
                ];
                let hud = [
                    lerp(from_hud[0], tp[0], a.x),
                    lerp(from_hud[1], tp[1], a.y),
                    lerp(from_hud[2], tp[2], a.width),
                    tp[3],
                ];
                // The timeline is the animation: follow it exactly.
                self.bar_rect.follow(bar, false, [true; 4]);
                self.hud_rect.follow(hud, false, [true; 4]);
            }
            None => {
                self.morph_from = None;
                // Edge-anchored y/h stay pinned; derived x/w glide on resize.
                let pin = [false, true, false, true];
                self.bar_rect.follow(target.bar, animate, pin);
                self.hud_rect.follow(target.hud, animate, pin);
            }
        }
        self.button_rect.follow(target.button, animate, [false; 4]);

        if reduced {
            for s in [
                &mut self.theme_t,
                &mut self.zone_fade,
                &mut self.depth,
                &mut self.puck_pos,
                &mut self.puck_press,
                &mut self.puck_sx,
                &mut self.puck_sy,
                &mut self.bar_drift,
                &mut self.button_press,
                &mut self.button_ox,
                &mut self.button_oy,
            ] {
                s.snap(s.target);
            }
            return false;
        }

        // Squash & stretch follows the puck's live velocity (converted to px/s).
        let (_, _, tab_w) = self.bar_geometry();
        let normalised = (self.puck_pos.velocity * tab_w / 1500.0).clamp(-1.0, 1.0);
        let (sx, sy) = gesture::squash_stretch(normalised);
        self.puck_sx.set_target(sx);
        self.puck_sy.set_target(sy);

        // The ocean drifts continuously (paused under reduced motion above).
        let mut moving = true;
        for s in [
            &mut self.theme_t,
            &mut self.zone_fade,
            &mut self.depth,
            &mut self.puck_pos,
            &mut self.puck_press,
            &mut self.puck_sx,
            &mut self.puck_sy,
            &mut self.bar_drift,
            &mut self.button_press,
            &mut self.button_ox,
            &mut self.button_oy,
        ] {
            moving |= s.tick(dt);
        }
        moving |= self.bar_rect.tick(dt);
        moving |= self.hud_rect.tick(dt);
        moving |= self.button_rect.tick(dt);
        moving |= self.morph.running(now) || self.ring.running(now);
        moving
    }

    // --------------------------------------------------------------- events

    fn on_bar_down(&mut self, ev: &MouseDownEvent, _: &mut Window, cx: &mut Context<Self>) {
        let local = self.pointer_to_tabs(ev.position.x);
        let tab = (local.floor().max(0.0) as usize).min(TABS.len() - 1);
        self.puck_press.retarget(1.0, motion_tokens::press());
        if tab == self.selected {
            // Grab the puck: it now tracks the pointer 1:1.
            self.dragging = Some(local - self.puck_pos.value);
            self.drag_start_x = ev.position.x.as_f32();
            self.last_drag = Some((local, Instant::now()));
        } else {
            self.select(tab);
        }
        cx.notify();
    }

    fn on_button_down(&mut self, ev: &MouseDownEvent, _: &mut Window, cx: &mut Context<Self>) {
        self.button_press.retarget(1.0, motion_tokens::glow());
        self.button_focus = Some(ev.position);
        self.button_grab = Some(ev.position);
        self.button_ox.snap(0.0);
        self.button_oy.snap(0.0);
        cx.notify();
    }

    /// Window-level move handler: drags continue even when the pointer leaves
    /// the element that started them.
    fn on_root_move(&mut self, ev: &MouseMoveEvent, _: &mut Window, cx: &mut Context<Self>) {
        if ev.pressed_button != Some(MouseButton::Left) {
            return;
        }
        if let Some(grab) = self.button_grab {
            self.button_ox.snap((ev.position.x - grab.x).as_f32());
            self.button_oy.snap((ev.position.y - grab.y).as_f32());
            self.button_focus = Some(ev.position);
            cx.notify();
        }
        let Some(grab) = self.dragging else { return };
        let local = self.pointer_to_tabs(ev.position.x);
        let max = (TABS.len() - 1) as f32;
        let raw = local - grab;
        // Rubber-band past the ends instead of a hard clamp (0.25 tab ≈ 24 px).
        let x = if raw < 0.0 {
            gesture::rubber_band(raw, 0.25)
        } else if raw > max {
            max + gesture::rubber_band(raw - max, 0.25)
        } else {
            raw
        };
        let now = Instant::now();
        if let Some((prev, t)) = self.last_drag {
            let dt = (now - t).as_secs_f32().max(1.0 / 240.0);
            self.drag_velocity = (local - prev) / dt;
        }
        self.last_drag = Some((local, now));
        self.puck_pos.snap(x);
        self.puck_pos.velocity = self.drag_velocity; // keep squash & stretch alive
        // The container leans up to 4 px toward the drag.
        let dx = ev.position.x.as_f32() - self.drag_start_x;
        self.bar_drift.set_target(4.0 * (dx / 40.0).tanh());
        cx.notify();
    }

    /// Window-level release: ends whichever gesture is active, wherever the
    /// pointer is.
    fn on_root_up(&mut self, _: &MouseUpEvent, _: &mut Window, cx: &mut Context<Self>) {
        if self.button_grab.take().is_some() {
            self.button_press.retarget(0.0, motion_tokens::glow());
            self.button_ox.set_target(0.0);
            self.button_oy.set_target(0.0);
            self.ring.start(Instant::now());
        }
        if self.dragging.take().is_some() {
            // Choose the tab where a fling would land, not where the finger lifted.
            let landing = gesture::project(self.puck_pos.value, self.drag_velocity, 0.998);
            let tab = landing.round().clamp(0.0, (TABS.len() - 1) as f32) as usize;
            let v = self.puck_pos.velocity;
            self.select(tab);
            self.puck_pos.kick(v);
        }
        self.puck_press.retarget(0.0, motion_tokens::release());
        self.bar_drift.set_target(0.0);
        self.drag_velocity = 0.0;
        cx.notify();
    }
}

fn chip(id: &'static str, spec: GlassSpec, colors: &Colors, label: &'static str) -> Stateful<Div> {
    glass(spec)
        .id(id)
        .px(px(16.0))
        .py(px(8.0))
        .rounded_full()
        .text_color(colors.label)
        .text_size(typography::HEADLINE.size)
        .font_weight(typography::HEADLINE.weight)
        .child(label)
}

impl Render for Demo {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // Drive animation: re-render next frame while anything moves. GPUI
        // also re-renders on every resize, so `tick` sees new layouts.
        if self.tick(window) {
            window.request_animation_frame();
        } else {
            self.clock.reset();
        }
        let now = Instant::now();
        let dark_t = self.dark_t();
        let colors = self.colors();
        let chrome = self.spec(GlassSpec::chrome);
        let glass_shadow = shadow::glass_mix(dark_t);

        // ---- Ocean backdrop: a living scene, so the glass has something to bend.
        // Orbs drift on slow Lissajous paths; depth lines and a big watermark
        // give straight edges and glyphs that show the refraction band.
        let palette = self.palette();
        let viewport = window.viewport_size();
        let (vw_, vh_) = (viewport.width.as_f32(), viewport.height.as_f32());
        let t = if window.prefers_reduced_motion() {
            0.0
        } else {
            self.started.elapsed().as_secs_f32()
        };
        // (x, y as viewport fractions, radius, x/y speed in Hz, phase, palette colour)
        const ORBS: [(f32, f32, f32, f32, f32, f32, usize); 7] = [
            (0.18, 0.30, 130.0, 0.045, 0.030, 0.0, 0),
            (0.72, 0.22, 95.0, 0.035, 0.055, 1.3, 1),
            (0.45, 0.60, 150.0, 0.025, 0.040, 2.1, 2),
            (0.86, 0.66, 110.0, 0.050, 0.020, 0.7, 3),
            (0.28, 0.84, 80.0, 0.040, 0.045, 3.4, 1),
            (0.60, 0.40, 60.0, 0.060, 0.040, 4.2, 0),
            (0.08, 0.58, 70.0, 0.040, 0.060, 5.0, 2),
        ];
        let tau = std::f32::consts::TAU;
        let orbs = ORBS.iter().map(|&(fx, fy, r, sx, sy, phase, ci)| {
            let x = fx * vw_ + 0.12 * vw_ * (t * sx * tau + phase).sin();
            let y = fy * vh_ + 0.08 * vh_ * (t * sy * tau + phase * 1.7).cos();
            let mut c = palette.orbs[ci];
            c.a = 0.9;
            let mut glow = c;
            glow.a = 0.55;
            div()
                .absolute()
                .left(px(x - r))
                .top(px(y - r))
                .size(px(r * 2.0))
                .rounded_full()
                .bg(c)
                // Bioluminescent halo.
                .shadow(vec![gpui::BoxShadow {
                    color: glow,
                    offset: gpui::point(px(0.0), px(0.0)),
                    blur_radius: px(r * 0.6),
                    spread_radius: px(4.0),
                    inset: false,
                }])
        });
        let depth_lines = (1..((vh_ / 48.0) as usize)).map(|i| {
            let y = i as f32 * 48.0;
            div()
                .absolute()
                .left_0()
                .top(px(y))
                .w_full()
                .h(px(1.5))
                .bg(hsla(0.0, 0.0, 1.0, 0.28))
                .child(
                    div()
                        .absolute()
                        .left(px(16.0))
                        .top(px(-18.0))
                        .text_size(typography::CAPTION.size)
                        .text_color(hsla(0.0, 0.0, 1.0, 0.75))
                        .child(format!("— {:.0} m", palette.depth_m + i as f32 * 2.0)),
                )
        });
        let backdrop = div()
            .absolute()
            .size_full()
            .bg(linear_gradient(
                180.0,
                linear_color_stop(palette.surface, 0.0),
                linear_color_stop(palette.floor, 1.0),
            ))
            .children(orbs)
            .child(
                div()
                    .absolute()
                    .left(px(28.0))
                    .top(px(90.0))
                    .text_size(px(112.0))
                    .font_weight(gpui::FontWeight::BLACK)
                    .text_color(hsla(0.0, 0.0, 1.0, 0.22))
                    .child("TIDEPOOL"),
            )
            .children(depth_lines)
            // Night dive: the water darkens as the theme fades.
            .child(
                div()
                    .absolute()
                    .inset_0()
                    .bg(hsla(0.62, 0.6, 0.04, 0.55 * dark_t)),
            );

        // ---- Round button: press scale, glow, tanh elastic pull.
        let press = self.button_press.value;
        let focus = self.button_focus;
        let [bx, by, bw, bh] = self.button_rect.value();
        let (ox, oy) = (self.button_ox.value, self.button_oy.value);
        let base_scale = 1.0 + 4.0 / bh * press;
        let (sx, sy) = gesture::elastic_scale((ox, oy), (bw, bh), base_scale, 4.0 / bh);
        let (tx, ty) = (
            gesture::elastic_offset(ox, bw.min(bh)),
            gesture::elastic_offset(oy, bw.min(bh)),
        );
        let (vw, vh) = (bw * sx, bh * sy);
        let (cx_, cy_) = (bx + bw / 2.0 + tx, by + bh / 2.0 + ty);
        let button = glass_interactive(self.spec(GlassSpec::control), move || GlassInteraction {
            press,
            focus,
        })
        .id("glass-button")
        .absolute()
        .left(px(cx_ - vw / 2.0))
        .top(px(cy_ - vh / 2.0))
        .w(px(vw))
        .h(px(vh))
        .rounded_full()
        .shadow(glass_shadow.clone())
        .flex()
        .items_center()
        .justify_center()
        .text_color(colors.label)
        .text_size(typography::TITLE.size)
        .child("◉")
        .on_mouse_down(MouseButton::Left, cx.listener(Self::on_button_down));

        // ---- Sonar ping on release: two rings spread out of the button and fade.
        let ring_p = self.ring.progress(now); // 1.0 when idle or finished
        let rings = [0.0f32, 0.3].into_iter().filter_map(|delay| {
            let p = ((ring_p - delay) / (1.0 - delay)).clamp(0.0, 1.0);
            (p > 0.0 && p < 1.0).then(|| {
                let e = curve::cubic_bezier(curve::RELEASE_RING.1)(p);
                let grow = 80.0 * e;
                div()
                    .absolute()
                    .left(px(bx - grow))
                    .top(px(by - grow))
                    .size(px(bw + grow * 2.0))
                    .rounded_full()
                    .border_2()
                    .border_color(mix(
                        hsla(0.5, 1.0, 0.85, 0.75),
                        hsla(0.5, 1.0, 0.85, 0.0),
                        e,
                    ))
            })
        });

        // ---- Chips, pinned to the top-right edge (edge-anchored: no resize animation).
        let chips = div()
            .absolute()
            .top(px(24.0))
            .right(px(24.0))
            .flex()
            .flex_col()
            .items_end()
            .gap(px(10.0))
            .child(
                chip(
                    "theme-chip",
                    chrome,
                    &colors,
                    if self.theme.is_dark() {
                        "Night dive"
                    } else {
                        "Day dive"
                    },
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.toggle_theme();
                    cx.notify();
                })),
            )
            .child(
                chip(
                    "resize-chip",
                    chrome,
                    &colors,
                    if self.animated_resize {
                        "Resize: animated"
                    } else {
                        "Resize: instant"
                    },
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.animated_resize = !this.animated_resize;
                    cx.notify();
                })),
            )
            .child(
                chip(
                    "hud-chip",
                    chrome,
                    &colors,
                    if self.collapsed {
                        "HUD: inline"
                    } else {
                        "HUD: expanded"
                    },
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.toggle_hud();
                    cx.notify();
                })),
            );

        // ---- Depth HUD: morphs between a full-width card and an inline pill.
        // The readout rolls with a spring when the zone changes.
        let [px_, py_, pw, ph] = self.hud_rect.value();
        let text_opacity = ((pw - 220.0) / 60.0).clamp(0.0, 1.0);
        let depth = self.depth.value.max(0.0);
        let o2 = (100.0 - depth * 0.02).clamp(60.0, 100.0);
        let temp = (26.0 - depth * 0.025).clamp(2.0, 26.0);
        const ZONE_TITLES: [&str; 4] = ["Coral Reef", "Kelp Forest", "Hadal Trench", "Old Wreck"];
        let hud = glass(chrome)
            .id("depth-hud")
            .absolute()
            .left(px(px_))
            .top(px(py_))
            .w(px(pw))
            .h(px(ph))
            .rounded_full()
            .shadow(glass_shadow.clone())
            .overflow_hidden()
            .flex()
            .items_center()
            .gap(px(10.0))
            .pl(px(8.0))
            .pr(px(16.0))
            .child(
                // Gauge: the zone's glow, as a little porthole.
                div()
                    .flex_none()
                    .size(px(40.0))
                    .rounded_full()
                    .bg(linear_gradient(
                        135.0,
                        linear_color_stop(palette.orbs[2], 0.0),
                        linear_color_stop(palette.orbs[0], 1.0),
                    )),
            )
            .child(
                div()
                    .flex_1()
                    .overflow_hidden()
                    .opacity(text_opacity)
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .whitespace_nowrap()
                            .text_color(colors.label)
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .child(ZONE_TITLES[self.selected]),
                    )
                    .child(
                        div()
                            .whitespace_nowrap()
                            .text_color(colors.secondary_label)
                            .text_size(typography::FOOTNOTE.size)
                            .child(format!("O₂ {o2:.0}% · {temp:.0} °C")),
                    ),
            )
            .child(
                div()
                    .flex_none()
                    .whitespace_nowrap()
                    .text_color(colors.label)
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .child(format!("{depth:.0} m")),
            )
            .on_click(cx.listener(|this, _, _, cx| {
                this.toggle_hud();
                cx.notify();
            }));

        // ---- Floating tab bar: grows and leans while the puck is held.
        let (bar_x, bar_y, tab_w) = self.bar_geometry();
        let [_, _, bar_w, bar_h] = self.bar_rect.value();
        let pressed = self.puck_press.value.clamp(0.0, 1.2);
        let bar_scale = 1.0 + 16.0 / bar_w * pressed.max(0.0);
        let (vis_w, vis_h) = (bar_w * bar_scale, bar_h * bar_scale);
        let (inset_x, inset_y) = ((vis_w - bar_w) / 2.0, (vis_h - bar_h) / 2.0);
        let grow = 1.0 + (motion_tokens::scale::PUCK_PRESSED - 1.0) * pressed;
        let puck_w = tab_w * grow * self.puck_sx.value;
        let puck_h = (bar_h - BAR_PAD * 2.0) * grow * self.puck_sy.value;
        let puck_cx = inset_x + BAR_PAD + self.puck_pos.value * tab_w + tab_w / 2.0;
        let puck_cy = vis_h / 2.0;
        let mut rest_tint = colors.fill;
        rest_tint.a *= 1.4;
        let puck_spec = GlassSpec {
            tint: rest_tint,
            ..chrome
        }
        .lerp(self.spec(GlassSpec::lens), pressed.min(1.0));

        let bar = glass(chrome)
            .id("tab-bar")
            .absolute()
            .left(px(bar_x - inset_x))
            .top(px(bar_y - inset_y))
            .w(px(vis_w))
            .h(px(vis_h))
            .rounded_full()
            .shadow(glass_shadow)
            // Puck first, labels on top of it.
            .child(
                glass(puck_spec)
                    .absolute()
                    .left(px(puck_cx - puck_w / 2.0))
                    .top(px(puck_cy - puck_h / 2.0))
                    .w(px(puck_w))
                    .h(px(puck_h))
                    .rounded_full(),
            )
            .child(
                div()
                    .absolute()
                    .top(px(inset_y))
                    .left(px(inset_x + BAR_PAD))
                    .h(px(bar_h))
                    .flex()
                    .items_center()
                    .children(TABS.iter().enumerate().map(|(i, label)| {
                        div()
                            .w(px(tab_w))
                            .flex()
                            .justify_center()
                            .whitespace_nowrap()
                            .text_size(typography::CAPTION2.size)
                            .font_weight(typography::CAPTION2.weight)
                            .text_color(if i == self.selected {
                                colors.red
                            } else {
                                colors.label
                            })
                            .child(*label)
                    })),
            )
            .on_mouse_down(MouseButton::Left, cx.listener(Self::on_bar_down));

        div()
            .size_full()
            .relative()
            .bg(colors.background)
            .on_mouse_move(cx.listener(Self::on_root_move))
            .on_mouse_up(MouseButton::Left, cx.listener(Self::on_root_up))
            .child(backdrop)
            .child(button)
            .children(rings)
            .child(chips)
            .child(hud)
            .child(bar)
    }
}

#[allow(dead_code)] // unused when this file is included by tests/headless_frames.rs
fn main() {
    gpui_platform::application().run(|cx: &mut App| {
        cx.open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
                    None,
                    size(px(900.0), px(640.0)),
                    cx,
                ))),
                ..Default::default()
            },
            |_, cx| cx.new(|_| Demo::new()),
        )
        .expect("failed to open glass demo window");
        cx.activate(true);
    });
}
