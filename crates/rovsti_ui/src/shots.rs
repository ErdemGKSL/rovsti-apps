//! Headless screenshot rig shared by the apps' `shots` tests.
//!
//! Real GPUI on the test platform, drawn by `gpui_wgpu`'s offscreen renderer
//! (lavapipe works), with glass forced on and a fixed 60 fps virtual clock.
//! `TestWindow` has a scale factor of 2, so a 412x915 dp window saves an
//! 824x1830 px PNG.
//!
//! Use one `#[test] #[ignore]` per app: the data dir and time zone are
//! process-global environment variables ([`isolate_data_dir`]).
//!
//! Output: `target/shots/<app>/<NN>-<screen>-<dark|light>[-small].png`, and
//! [`contact_sheet`] writes `target/shots/<app>/contact_sheet.png`.

use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use anyhow::Context as _;
use gpui::{
    AnyWindowHandle, App, DevicePixels, Entity, HeadlessAppContext, PlatformAtlas,
    PlatformHeadlessRenderer, Render, Scene, Size, Window, WindowHandle, px, size,
};
use gpui_wgpu::{CosmicTextSystem, WgpuOffscreenRenderer};
use image::RgbaImage;
use liquid_glass_kit::{glass::force_backdrop_support, tokens::Theme};

use crate::{IconAssets, set_theme_override, spring::FrameClock};

/// The test window's fixed scale factor.
pub const SCALE: u32 = 2;
/// Virtual frame length (60 fps).
pub const FRAME: Duration = Duration::from_micros(16_667);
/// Phone-sized window, dp.
pub const PHONE: Size<f32> = Size {
    width: 412.0,
    height: 915.0,
};
/// Small phone, dp.
pub const SMALL_PHONE: Size<f32> = Size {
    width: 360.0,
    height: 640.0,
};

const MAX_FRAMES: usize = 240;
const IDLE_FRAMES: usize = 10;
const RENDER_ATTEMPTS: usize = 4;
const SHEET_COLS: u32 = 5;
const SHEET_TILE_W: u32 = 240;
const SHEET_MAX_W: u32 = 1200;

/// What to render and where it goes.
#[derive(Clone, Debug)]
pub struct ShotSpec {
    pub app: &'static str,
    pub nn: u32,
    pub screen: &'static str,
    pub theme: Theme,
    /// Window size in dp.
    pub size: Size<f32>,
}

// ---- Pure helpers -----------------------------------------------------------

/// `NN-screen-dark.png`, with `-small` appended for the small-phone variant.
pub fn file_name(nn: u32, screen: &str, theme: Theme, small: bool) -> String {
    let theme = if theme.is_dark() { "dark" } else { "light" };
    let small = if small { "-small" } else { "" };
    format!("{nn:02}-{screen}-{theme}{small}.png")
}

/// Contact sheet geometry: `(width, height, top-left of each tile)` for `n`
/// tiles of `tile_w` x `tile_h` in `cols` columns.
pub fn sheet_layout(n: usize, tile_w: u32, tile_h: u32, cols: u32) -> (u32, u32, Vec<(u32, u32)>) {
    let cols = cols.max(1);
    let n = n as u32;
    let used = cols.min(n.max(1));
    let rows = n.div_ceil(cols).max(1);
    let positions = (0..n)
        .map(|i| ((i % cols) * tile_w, (i / cols) * tile_h))
        .collect();
    (used * tile_w, rows * tile_h, positions)
}

/// The offscreen renderer asks for a retry when its instance buffer grew.
pub fn retryable(err: &anyhow::Error) -> bool {
    format!("{err:#}").to_lowercase().contains("retry")
}

fn is_small(spec_size: Size<f32>) -> bool {
    spec_size.height < 700.0
}

/// `$CARGO_TARGET_DIR` or `<workspace>/target`, then `shots`.
pub fn output_root() -> PathBuf {
    let target = std::env::var_os("CARGO_TARGET_DIR").map_or_else(
        || Path::new(env!("CARGO_MANIFEST_DIR")).join("../../target"),
        PathBuf::from,
    );
    target.join("shots")
}

/// Wipes `target/shots/.data/<app>` and points `XDG_DATA_HOME` at it, and sets
/// `TZ=Europe/Istanbul`, so prefs and times do not depend on the developer's
/// machine. Call once at the start of the app's test, before any window.
pub fn isolate_data_dir(app: &str) -> PathBuf {
    let dir = output_root().join(".data").join(app);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("create the shots data dir");
    // SAFETY: called at the start of a test, before the app starts threads
    // that read the environment; the module docs require one test per app.
    unsafe {
        std::env::set_var("XDG_DATA_HOME", &dir);
        std::env::set_var("TZ", "Europe/Istanbul");
    }
    dir
}

/// A grid of `paths` (5 columns, 240 px tiles, at most 1200 px wide) on
/// neutral grey, in the given order, saved to
/// `target/shots/<app>/contact_sheet.png`.
pub fn contact_sheet(app: &str, paths: &[PathBuf]) -> anyhow::Result<PathBuf> {
    anyhow::ensure!(!paths.is_empty(), "no shots for the contact sheet");
    let images = paths
        .iter()
        .map(|p| image::open(p).with_context(|| p.display().to_string()))
        .collect::<anyhow::Result<Vec<_>>>()?;
    // Tiles share one height, from the tallest aspect ratio.
    let tile_h = images
        .iter()
        .map(|i| (u64::from(SHEET_TILE_W) * u64::from(i.height()) / u64::from(i.width())) as u32)
        .max()
        .unwrap_or(SHEET_TILE_W);
    let (w, h, positions) = sheet_layout(images.len(), SHEET_TILE_W, tile_h, SHEET_COLS);
    debug_assert!(w <= SHEET_MAX_W);
    let mut sheet = RgbaImage::from_pixel(w, h, image::Rgba([96, 96, 100, 255]));
    for (img, (x, y)) in images.iter().zip(positions) {
        let th = (u64::from(SHEET_TILE_W) * u64::from(img.height()) / u64::from(img.width())).max(1)
            as u32;
        let small = image::imageops::resize(
            &img.to_rgba8(),
            SHEET_TILE_W,
            th,
            image::imageops::FilterType::Triangle,
        );
        image::imageops::overlay(&mut sheet, &small, i64::from(x), i64::from(y));
    }
    let out = output_root().join(app).join("contact_sheet.png");
    std::fs::create_dir_all(out.parent().expect("has a parent"))?;
    sheet.save(&out)?;
    Ok(out)
}

// ---- Renderer adapter -------------------------------------------------------

/// `WgpuOffscreenRenderer` as a GPUI headless renderer.
struct WgpuHeadless {
    renderer: WgpuOffscreenRenderer,
    size: Size<DevicePixels>,
}

impl PlatformHeadlessRenderer for WgpuHeadless {
    fn render_scene_to_image(
        &mut self,
        scene: &Scene,
        size: Size<DevicePixels>,
    ) -> anyhow::Result<RgbaImage> {
        if size != self.size {
            self.renderer.resize(size);
            self.size = size;
        }
        let mut attempt = 1;
        let rgba = loop {
            match self.renderer.render_rgba(scene) {
                Ok(rgba) => break rgba,
                Err(err) if attempt < RENDER_ATTEMPTS && retryable(&err) => attempt += 1,
                Err(err) => return Err(err),
            }
        };
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

// ---- The rig ----------------------------------------------------------------

/// One headless window showing a view `V`.
pub struct Rig<V: 'static> {
    /// Declared before `cx`: fields drop in order, and the entity handle must
    /// go before the app context or GPUI reports a leaked handle.
    pub root: Entity<V>,
    pub window: WindowHandle<V>,
    pub cx: HeadlessAppContext,
    spec: ShotSpec,
    /// The theme override, blink flag and frame clock are process globals, so
    /// only one rig may exist at a time (cargo runs tests on parallel threads).
    /// Declared last: released after the window is gone.
    _exclusive: std::sync::MutexGuard<'static, ()>,
}

/// Simulated status-bar and home-indicator insets of every shot (dp).
const SIM_SAFE_TOP: f32 = 24.0;
const SIM_SAFE_BOTTOM: f32 = 16.0;

static RIG_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

impl<V: Render + 'static> Rig<V> {
    /// Opens the window described by `spec`; `build` creates the root view.
    pub fn open(
        spec: ShotSpec,
        build: impl FnOnce(&mut Window, &mut App) -> Entity<V> + 'static,
    ) -> Self {
        let exclusive = RIG_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        force_backdrop_support(true);
        crate::field::set_blink(false);
        FrameClock::set_fixed_step(Some(FRAME));
        set_theme_override(Some(spec.theme));
        // A typical gesture-nav phone: status bar on top, home indicator below.
        crate::layout::set_simulated_safe_area(Some(crate::layout::SafeArea {
            top: SIM_SAFE_TOP,
            bottom: SIM_SAFE_BOTTOM,
            left: 0.0,
            right: 0.0,
        }));
        let px_size = size(
            DevicePixels((spec.size.width as i32) * SCALE as i32),
            DevicePixels((spec.size.height as i32) * SCALE as i32),
        );
        let mut cx = HeadlessAppContext::with_platform(
            Arc::new(CosmicTextSystem::new("Noto Sans")),
            Arc::new(IconAssets::new()),
            move || {
                WgpuOffscreenRenderer::new(px_size).ok().map(|renderer| {
                    Box::new(WgpuHeadless {
                        renderer,
                        size: px_size,
                    }) as Box<dyn PlatformHeadlessRenderer>
                })
            },
        );
        let mut root = None;
        let window = cx
            .open_window(
                size(px(spec.size.width), px(spec.size.height)),
                |window, app| {
                    let view = build(window, app);
                    root = Some(view.clone());
                    view
                },
            )
            .expect("open the headless window");
        Self {
            cx,
            window,
            root: root.expect("build ran"),
            spec,
            _exclusive: exclusive,
        }
    }

    fn any(&self) -> AnyWindowHandle {
        self.window.into()
    }

    /// Re-applies the theme override (an app may have replaced it while it was
    /// constructed or loaded its prefs); the next draw picks it up.
    pub fn theme(&mut self) {
        set_theme_override(Some(self.spec.theme));
        self.update(|_, window| window.refresh());
    }

    /// Runs `f` with the app and window, like an event handler would.
    pub fn update<R>(&mut self, f: impl FnOnce(&mut App, &mut Window) -> R) -> R {
        self.cx
            .update_window(self.any(), |_, window, app| f(app, window))
            .expect("update the window")
    }

    fn draw(&mut self) {
        self.cx
            .update_window(self.any(), |_, window, cx| window.draw(cx).clear())
            .expect("draw a frame");
    }

    /// Draws on the virtual 60 fps clock, at most 240 frames. GPUI exposes no
    /// "is anything still animating" query, so it compares the rendered image
    /// every 10 frames and stops once 10 consecutive frames changed nothing.
    /// Returns the frames drawn.
    pub fn settle(&mut self) -> usize {
        let mut drawn = 0;
        let mut previous: Option<RgbaImage> = None;
        while drawn < MAX_FRAMES {
            for _ in 0..IDLE_FRAMES {
                self.cx.run_until_parked();
                self.cx.advance_clock(FRAME);
                self.cx.run_until_parked();
                self.draw();
                drawn += 1;
            }
            let img = self
                .cx
                .capture_screenshot(self.any())
                .expect("capture the frame");
            if previous.as_ref() == Some(&img) {
                break;
            }
            previous = Some(img);
        }
        drawn
    }

    /// Draws a frame and returns it.
    pub fn capture(&mut self) -> RgbaImage {
        self.draw();
        self.cx
            .capture_screenshot(self.any())
            .expect("capture the frame")
    }

    /// Writes the current frame to `target/shots/<app>/<name>.png`.
    pub fn save(&mut self) -> PathBuf {
        let img = self.capture();
        self.save_image(&img)
    }

    /// Writes `img` (a frame, or one composed from several) under this
    /// shot's name.
    pub fn save_image(&self, img: &RgbaImage) -> PathBuf {
        let name = file_name(
            self.spec.nn,
            self.spec.screen,
            self.spec.theme,
            is_small(self.spec.size),
        );
        let dir = output_root().join(self.spec.app);
        std::fs::create_dir_all(&dir).expect("create the shots dir");
        let path = dir.join(name);
        img.save(&path).expect("write the png");
        path
    }
}

#[cfg(test)]
mod field_touch;
#[cfg(test)]
mod fields;

#[cfg(test)]
mod tests {
    use gpui::AppContext as _;

    use super::*;

    #[test]
    fn file_names() {
        assert_eq!(
            file_name(3, "recents", Theme::Dark, false),
            "03-recents-dark.png"
        );
        assert_eq!(
            file_name(12, "settings", Theme::Light, false),
            "12-settings-light.png"
        );
        assert_eq!(
            file_name(1, "keypad", Theme::Dark, true),
            "01-keypad-dark-small.png"
        );
    }

    #[test]
    fn sheet_grid() {
        let (w, h, pos) = sheet_layout(7, 240, 520, 5);
        assert_eq!((w, h), (1200, 1040));
        assert_eq!(pos[0], (0, 0));
        assert_eq!(pos[4], (960, 0));
        assert_eq!(pos[5], (0, 520));
        assert_eq!(pos.len(), 7);
        // Fewer tiles than columns: no empty columns.
        assert_eq!(sheet_layout(2, 240, 100, 5).0, 480);
        assert_eq!(sheet_layout(0, 240, 100, 5), (240, 100, vec![]));
        assert!(sheet_layout(40, SHEET_TILE_W, 100, SHEET_COLS).0 <= SHEET_MAX_W);
    }

    #[test]
    fn retry_detection() {
        assert!(retryable(&anyhow::anyhow!(
            "GPUI renderer requested a larger instance buffer; retry the frame"
        )));
        assert!(!retryable(&anyhow::anyhow!("device lost")));
    }

    #[test]
    fn small_variant() {
        assert!(is_small(SMALL_PHONE));
        assert!(!is_small(PHONE));
    }

    use gpui::{Context, IntoElement, ParentElement as _, Styled as _, div};

    use crate::{
        components,
        field::{TextField, field_box},
        tab_bar::{Tab, TabBar},
        theme,
    };

    struct Smoke {
        tabs: TabBar,
        field: Entity<TextField>,
        theme: Theme,
    }

    impl Render for Smoke {
        fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
            let theme = crate::current_theme(window);
            self.theme = theme;
            let colors = theme::colors(theme);
            div()
                .size_full()
                .relative()
                .child(components::backdrop(theme, theme::accent_glow(theme)))
                .child(
                    div()
                        .absolute()
                        .top(px(80.0))
                        .left(px(16.0))
                        .right(px(16.0))
                        .flex()
                        .flex_col()
                        .gap(px(16.0))
                        .child(
                            div()
                                .text_color(colors.label)
                                .text_size(px(28.0))
                                .child("Şişli İpek Güneş"),
                        )
                        .child(field_box(
                            theme,
                            "smoke-field",
                            &self.field,
                            "Ara",
                            window,
                            cx,
                        )),
                )
                .child(self.tabs.render(theme, 0.0, |_, _, _| {}, |_, _, _| {}))
        }
    }

    /// Run: `cargo test -p rovsti_ui --features shots smoke -- --ignored --nocapture`
    /// Output: `target/shots/rovsti_ui/00-smoke-{dark,light}.png`.
    #[test]
    #[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
    fn smoke() {
        isolate_data_dir("rovsti_ui");
        for theme in [Theme::Dark, Theme::Light] {
            let spec = ShotSpec {
                app: "rovsti_ui",
                nn: 0,
                screen: "smoke",
                theme,
                size: PHONE,
            };
            let mut rig = Rig::open(spec, |_, cx| {
                cx.new(|cx| Smoke {
                    tabs: TabBar::new([
                        Tab::new(crate::Icon::LucideStar, "Bir"),
                        Tab::new(crate::Icon::LucideStar, "İki"),
                        Tab::new(crate::Icon::LucideStar, "Üç"),
                    ]),
                    field: cx.new(TextField::new),
                    theme: Theme::Light,
                })
            });
            rig.theme();
            rig.settle();
            let path = rig.save();
            let img = image::open(&path).expect("read back").to_rgba8();
            assert_eq!(img.dimensions(), (824, 1830));
            let first = *img.get_pixel(0, 0);
            assert!(
                img.pixels().any(|p| p != &first),
                "the shot is a single flat colour"
            );
            println!("{}", path.display());
        }
    }
}
