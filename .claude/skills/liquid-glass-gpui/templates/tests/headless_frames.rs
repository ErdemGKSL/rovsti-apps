//! Drives the demo headlessly, frame by frame, with real input events and
//! real time, and writes PNG screenshots of each interesting moment. Use it
//! to *see* animations (mid-morph, mid-fade, a held puck) and to assert on
//! their geometry. No window ever opens.
//!
//!   cargo test --features headless-tests --test headless_frames -- --ignored --nocapture
//!   → target/frames/*.png and target/frames/contact_sheet.png
//!
//! Needs a GPU adapter (wgpu) and system fonts (cosmic-text).
//!
//! How it works: GPUI's `HeadlessAppContext` runs the app on the test platform.
//! On Linux and Windows the fork ships no headless renderer, so a small adapter
//! plugs `gpui_wgpu::WgpuOffscreenRenderer` in as `PlatformHeadlessRenderer`.
//! The test window reports no backdrop support, so the kit's
//! `force_backdrop_support(true)` makes glass paint for real.

#[path = "../examples/glass_demo.rs"]
#[allow(dead_code)]
mod demo;

use std::{
    path::PathBuf,
    sync::Arc,
    thread::sleep,
    time::{Duration, Instant},
};

use anyhow::Context as _;
use gpui::{
    AnyWindowHandle, AppContext as _, DevicePixels, HeadlessAppContext, Modifiers, MouseButton, MouseDownEvent,
    MouseMoveEvent, MouseUpEvent, PlatformAtlas, PlatformHeadlessRenderer, PlatformInput, Pixels,
    Point, Scene, Size, WindowHandle, point, px, size,
};
use gpui_wgpu::{CosmicTextSystem, WgpuOffscreenRenderer};
use image::RgbaImage;
use liquid_glass_kit::glass::force_backdrop_support;

const W: f32 = 900.0;
const H: f32 = 640.0;

/// `WgpuOffscreenRenderer` as a GPUI headless renderer.
struct WgpuHeadless {
    renderer: WgpuOffscreenRenderer,
    size: Size<DevicePixels>,
}

impl PlatformHeadlessRenderer for WgpuHeadless {
    fn render_scene_to_image(&mut self, scene: &Scene, size: Size<DevicePixels>) -> anyhow::Result<RgbaImage> {
        if size != self.size {
            self.renderer.resize(size);
            self.size = size;
        }
        let rgba = self.renderer.render_rgba(scene)?;
        RgbaImage::from_raw(size.width.0 as u32, size.height.0 as u32, rgba)
            .context("offscreen buffer size mismatch")
    }

    fn render_scene(&mut self, scene: &Scene, size: Size<DevicePixels>) -> anyhow::Result<()> {
        self.render_scene_to_image(scene, size).map(|_| ())
    }

    fn sprite_atlas(&self) -> Arc<dyn PlatformAtlas> {
        self.renderer.sprite_atlas()
    }
}

struct Harness {
    cx: HeadlessAppContext,
    window: WindowHandle<demo::Demo>,
    out: PathBuf,
    shots: Vec<(String, RgbaImage)>,
}

impl Harness {
    fn new() -> anyhow::Result<Self> {
        force_backdrop_support(true);
        let text = Arc::new(CosmicTextSystem::new("Noto Sans"));
        let mut cx = HeadlessAppContext::with_platform(text, Arc::new(()), || {
            let size = size(DevicePixels(W as i32), DevicePixels(H as i32));
            WgpuOffscreenRenderer::new(size)
                .ok()
                .map(|renderer| Box::new(WgpuHeadless { renderer, size }) as Box<dyn PlatformHeadlessRenderer>)
        });
        let window = cx.open_window(size(px(W), px(H)), |_, cx| cx.new(|_| demo::Demo::new()))?;
        let out = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("target/frames");
        std::fs::create_dir_all(&out)?;
        Ok(Self { cx, window, out, shots: Vec::new() })
    }

    fn any(&self) -> AnyWindowHandle {
        self.window.into()
    }

    /// Lays out and paints one frame (render → tick → paint).
    fn frame(&mut self) {
        self.cx
            .update_window(self.any(), |_, window, cx| {
                window.refresh();
                window.draw(cx).clear();
            })
            .expect("draw frame");
        self.cx.run_until_parked();
    }

    /// Keeps drawing at ~60 fps for `ms` of real time, like a display would.
    fn run_for(&mut self, ms: u64) {
        let end = Instant::now() + Duration::from_millis(ms);
        loop {
            self.frame();
            if Instant::now() >= end {
                break;
            }
            sleep(Duration::from_millis(16));
        }
    }

    fn shot(&mut self, name: &str) -> anyhow::Result<()> {
        self.frame();
        let img = self.cx.capture_screenshot(self.any())?;
        img.save(self.out.join(format!("{name}.png")))?;
        self.shots.push((name.to_string(), img));
        Ok(())
    }

    fn input(&mut self, event: PlatformInput) {
        self.cx
            .update_window(self.any(), |_, window, cx| {
                window.dispatch_event(event, cx);
            })
            .expect("dispatch event");
        self.cx.run_until_parked();
    }

    fn down(&mut self, p: Point<Pixels>) {
        self.input(PlatformInput::MouseDown(MouseDownEvent {
            button: MouseButton::Left,
            position: p,
            modifiers: Modifiers::default(),
            click_count: 1,
            first_mouse: false,
        }));
    }

    fn drag_to(&mut self, from: Point<Pixels>, to: Point<Pixels>, steps: usize) {
        for i in 1..=steps {
            let t = i as f32 / steps as f32;
            let p = point(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t);
            self.input(PlatformInput::MouseMove(MouseMoveEvent {
                position: p,
                pressed_button: Some(MouseButton::Left),
                modifiers: Modifiers::default(),
            }));
            self.frame();
            sleep(Duration::from_millis(16));
        }
    }

    fn up(&mut self, p: Point<Pixels>) {
        self.input(PlatformInput::MouseUp(MouseUpEvent {
            button: MouseButton::Left,
            position: p,
            modifiers: Modifiers::default(),
            click_count: 1,
        }));
    }

    fn demo<R>(&mut self, f: impl FnOnce(&mut demo::Demo) -> R) -> R {
        self.cx
            .update(|app| self.window.update(app, |d, _, cx| {
                let r = f(d);
                cx.notify();
                r
            }))
            .expect("update demo")
    }

    fn rects(&mut self) -> ([f32; 4], [f32; 4], [f32; 4]) {
        self.demo(|d| d.rects())
    }

    /// All shots side by side in a grid, at half size, for one-glance review.
    fn contact_sheet(&self) -> anyhow::Result<()> {
        let (tw, th) = ((W / 2.0) as u32, (H / 2.0) as u32);
        let cols = 3u32;
        let rows = (self.shots.len() as u32).div_ceil(cols);
        let mut sheet = RgbaImage::from_pixel(tw * cols, th * rows, image::Rgba([255, 255, 255, 255]));
        for (i, (_, img)) in self.shots.iter().enumerate() {
            let small = image::imageops::resize(img, tw, th, image::imageops::FilterType::Triangle);
            let (x, y) = ((i as u32 % cols) * tw, (i as u32 / cols) * th);
            image::imageops::overlay(&mut sheet, &small, x as i64, y as i64);
        }
        sheet.save(self.out.join("contact_sheet.png"))?;
        Ok(())
    }
}

fn center(r: [f32; 4]) -> Point<Pixels> {
    point(px(r[0] + r[2] / 2.0), px(r[1] + r[3] / 2.0))
}

fn near(a: [f32; 4], b: [f32; 4], eps: f32) -> bool {
    a.iter().zip(b).all(|(x, y)| (x - y).abs() <= eps)
}

#[test]
#[ignore = "requires a GPU adapter and system fonts"]
fn demo_frames() -> anyhow::Result<()> {
    let mut h = Harness::new()?;
    h.run_for(100);
    h.shot("01_initial_light")?;
    let (bar0, hud0, button0) = h.rects();

    // ---- Theme cross-fade: mid-fade must sit between light and dark.
    h.demo(|d| d.toggle_theme());
    h.run_for(90);
    h.shot("02_theme_mid_fade")?;
    h.run_for(500);
    h.shot("03_dark")?;
    h.demo(|d| d.toggle_theme());
    h.run_for(500);

    // ---- Puck: press the selected tab, drag right; bar grows and leans.
    let tab0 = point(px(bar0[0] + 4.0 + (bar0[2] - 8.0) / 8.0), px(bar0[1] + bar0[3] / 2.0));
    h.down(tab0);
    h.run_for(120);
    let drag_end = point(tab0.x + px(150.0), tab0.y);
    h.drag_to(tab0, drag_end, 8);
    h.shot("04_puck_held_dragging")?;
    let (bar_held, _, _) = h.rects();
    assert!(near(bar_held, bar0, 0.5), "layout rect itself must not move while held");
    h.up(drag_end);
    h.run_for(80);
    h.shot("05_puck_released")?;
    h.run_for(700);
    let selected = h.demo(|d| d.selected);
    assert!(selected >= 1, "a rightward fling must land on a later tab, got {selected}");

    // ---- Button: press, pull right (tanh elastic), release (ring).
    let b = center(button0);
    h.down(b);
    h.run_for(120);
    let pull = point(b.x + px(160.0), b.y + px(30.0));
    h.drag_to(b, pull, 8);
    h.shot("06_button_elastic_pull")?;
    h.up(pull);
    h.run_for(110);
    h.shot("07_release_ring")?;
    h.run_for(700);

    // ---- Mini player morph: collapse, sample mid-flight, then expand back.
    h.demo(|d| d.toggle_hud());
    h.run_for(110);
    h.shot("08_morph_collapse_early")?;
    let (_, p_early, _) = h.rects();
    h.run_for(140);
    h.shot("09_morph_collapse_mid")?;
    h.run_for(500);
    h.shot("10_inline")?;
    let (bar_c, hud_c, _) = h.rects();
    assert!(bar_c[2] < bar0[2], "inline bar must be narrower");
    assert!((hud_c[1] - bar_c[1]).abs() < 0.5, "inline player shares the bar's row");
    assert!(hud_c[2] < hud0[2], "inline player must be compact");
    // Staggered axes: early in a collapse, x has moved further than y.
    let fx = (p_early[0] - hud0[0]) / (hud_c[0] - hud0[0]);
    let fy = (p_early[1] - hud0[1]) / (hud_c[1] - hud0[1]);
    assert!(fx > fy, "collapse leads sideways (x {fx:.2}) before dropping (y {fy:.2})");

    h.demo(|d| d.toggle_hud());
    h.run_for(200);
    h.shot("11_morph_expand_mid")?;
    h.run_for(600);
    h.shot("12_expanded_again")?;
    let (bar_e, hud_e, _) = h.rects();
    assert!(near(bar_e, bar0, 0.5) && near(hud_e, hud0, 0.5), "expand must return home");

    h.contact_sheet()?;
    println!("frames in {}", h.out.display());
    Ok(())
}
