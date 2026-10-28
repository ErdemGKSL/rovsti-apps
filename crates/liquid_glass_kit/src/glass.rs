//! Liquid-glass surfaces for gpui-fusion.
//!
//! Two ways to paint the same look:
//! - [`paint_glass`]: our own shader (`shaders/liquid_glass.wgsl`), with
//!   iOS-style edge-band refraction, |n·L| rim, dark rim and press glow.
//! - [`GlassSpec::to_fusion`]: map the spec onto gpui-fusion's ready-made
//!   `LiquidGlassAppearance` and call `gpui_effects::paint_liquid_glass`.
//!
//! Both must run during paint, before children, e.g. inside
//! `div().on_paint_before_children(..)`. Both fall back to a translucent quad
//! when `window.supports_backdrop_blur()` is false.

use std::sync::OnceLock;

use gpui::{
    BackdropShader, Bounds, Corners, Div, EffectUniforms, Hsla, PaintBackdropEffect, Pixels, Point,
    Rgba, Styled, Window, div, fill, hsla, point, px,
};

use crate::tokens::Theme;

/// Optical recipe for one glass surface. Lengths are logical pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct GlassSpec {
    /// Gaussian blur radius of the backdrop. iOS glass is *barely* blurred
    /// (2–8 px); refraction and saturation do most of the work.
    pub blur: Pixels,
    /// Width of the refracting band along the edge.
    pub lens_height: Pixels,
    /// Maximum inward displacement at the rim.
    pub lens_amount: Pixels,
    /// Bend normals toward the centre for a domed, thicker look.
    pub depth: bool,
    /// RGB separation at refracted corners, 0..~0.5.
    pub dispersion: f32,
    /// Backdrop saturation multiplier (vibrancy). 1.5–2.0 is typical.
    pub saturation: f32,
    pub brightness: f32,
    /// 0 = fully blurred interior, 1 = sharp backdrop.
    pub clarity: f32,
    /// Surface fill mixed over the backdrop (alpha = strength).
    pub tint: Hsla,
    /// Specular rim width and strength.
    pub rim_width: Pixels,
    pub rim_alpha: f32,
    /// Exponent on |n·L|; 1 = broad, 3+ = tight highlight at the light angle.
    pub rim_falloff: f32,
    /// Light direction in degrees, screen space (y down). 225° = from top-left.
    pub light_angle: f32,
    /// Darkening on the edge facing away from the light, 0..1.
    pub inner_shade: f32,
    /// Alpha of the dark outline (2 × `rim_width`, centred on the edge, half
    /// outside the shape). It swells at all four corners and fades along the
    /// edges, so it reads as a crisp outline, not a shadow. 0.32 matches iOS.
    pub dark_rim: f32,
}

impl GlassSpec {
    /// Navigation chrome: tab bars, toolbars, floating buttons.
    pub fn chrome(theme: Theme) -> Self {
        Self {
            blur: px(2.0),
            lens_height: px(12.0),
            lens_amount: px(24.0),
            depth: false,
            dispersion: 0.0,
            saturation: 1.5,
            brightness: 1.0,
            clarity: 0.0,
            tint: theme.glass_fill(),
            rim_width: px(0.8),
            rim_alpha: 0.5,
            rim_falloff: 1.0,
            light_angle: 225.0,
            inner_shade: 0.0,
            dark_rim: 0.32,
        }
    }

    /// Interactive controls: buttons, toggles, segmented controls.
    pub fn control(theme: Theme) -> Self {
        Self {
            lens_height: px(16.0),
            lens_amount: px(28.0),
            ..Self::chrome(theme)
        }
    }

    /// Sheets, popovers, menus: more blur so text on top stays legible.
    pub fn sheet(theme: Theme) -> Self {
        Self {
            blur: px(if theme.is_dark() { 14.0 } else { 12.0 }),
            lens_height: px(20.0),
            lens_amount: px(32.0),
            inner_shade: 0.08,
            ..Self::chrome(theme)
        }
    }

    /// Side panel: calmer optics, more opaque.
    pub fn side_panel(theme: Theme) -> Self {
        let mut tint = theme.glass_fill();
        tint.a = 0.75;
        Self {
            blur: px(2.0),
            lens_height: px(14.0),
            lens_amount: px(20.0),
            saturation: 1.2,
            tint,
            ..Self::chrome(theme)
        }
    }

    /// The selection puck while pressed: a clear lens with dispersion.
    pub fn lens(theme: Theme) -> Self {
        Self {
            blur: px(0.0),
            lens_height: px(10.0),
            lens_amount: px(14.0),
            depth: true,
            dispersion: 0.35,
            clarity: 1.0,
            tint: hsla(0.0, 0.0, 1.0, if theme.is_dark() { 0.04 } else { 0.10 }),
            rim_alpha: 0.6,
            inner_shade: 0.12,
            ..Self::chrome(theme)
        }
    }

    /// Linear interpolation, used to morph e.g. puck-at-rest → pressed lens.
    pub fn lerp(self, other: Self, t: f32) -> Self {
        let f = |a: f32, b: f32| a + (b - a) * t;
        let p = |a: Pixels, b: Pixels| px(f(a.as_f32(), b.as_f32()));
        Self {
            blur: p(self.blur, other.blur),
            lens_height: p(self.lens_height, other.lens_height),
            lens_amount: p(self.lens_amount, other.lens_amount),
            depth: if t < 0.5 { self.depth } else { other.depth },
            dispersion: f(self.dispersion, other.dispersion),
            saturation: f(self.saturation, other.saturation),
            brightness: f(self.brightness, other.brightness),
            clarity: f(self.clarity, other.clarity),
            tint: crate::tokens::mix(self.tint, other.tint, t),
            rim_width: p(self.rim_width, other.rim_width),
            rim_alpha: f(self.rim_alpha, other.rim_alpha),
            rim_falloff: f(self.rim_falloff, other.rim_falloff),
            light_angle: f(self.light_angle, other.light_angle),
            inner_shade: f(self.inner_shade, other.inner_shade),
            dark_rim: f(self.dark_rim, other.dark_rim),
        }
    }

    /// Maps onto gpui-fusion's built-in material (no custom shader needed).
    pub fn to_fusion(&self) -> gpui_effects::LiquidGlassAppearance {
        let (sin, cos) = self.light_angle.to_radians().sin_cos();
        gpui_effects::LiquidGlassAppearance {
            blur_radius: self.blur,
            clarity: self.clarity,
            refraction: self.lens_amount,
            thickness: self.lens_height,
            dispersion: (self.dispersion * 0.1).clamp(0.0, 0.1),
            tint: self.tint,
            saturation: self.saturation,
            brightness: self.brightness,
            highlight: self.rim_alpha.clamp(0.0, 1.0),
            edge_shadow: self.inner_shade,
            rim_width: self.rim_width,
            light_direction: point(cos, sin),
            ..gpui_effects::LiquidGlassAppearance::regular()
        }
    }
}

/// Per-frame interaction state fed into the shader.
#[derive(Clone, Copy, Debug, Default)]
pub struct GlassInteraction {
    /// 0..1 press progress (drive with a spring, see `motion_tokens::glow`).
    pub press: f32,
    /// Finger/cursor position in window coordinates.
    pub focus: Option<Point<Pixels>>,
}

const SHADER_SOURCE: &str = include_str!("../shaders/liquid_glass.wgsl");
/// The line the variants rewrite (the press glow switch).
const PRESS_GLOW_ON: &str = "const LG_PRESS_GLOW: bool = true;";
const PRESS_GLOW_OFF: &str = "const LG_PRESS_GLOW: bool = false;";
/// The grid entry point renames the single-surface one to `lg_shade`.
const SINGLE_ENTRY: &str = "fn backdrop_effect(input: BackdropInput, params: BackdropParams)";
const GRID_ENTRY: &str = include_str!("../shaders/liquid_glass_grid.wgsl");

const GRID_SHADE: &str = "fn lg_shade(input: BackdropInput, params: BackdropParams)";

/// The template with the press glow compiled out.
fn rest_source() -> String {
    SHADER_SOURCE.replace(PRESS_GLOW_ON, PRESS_GLOW_OFF)
}

/// The rest variant with the entry point renamed, plus the grid entry.
fn grid_source() -> String {
    let mut source = rest_source().replace(SINGLE_ENTRY, GRID_SHADE);
    source.push_str(GRID_ENTRY);
    source
}

/// The template shader, compiled once (hashing the source is not free).
pub fn glass_shader() -> BackdropShader {
    static SHADER: OnceLock<BackdropShader> = OnceLock::new();
    SHADER
        .get_or_init(|| BackdropShader::wgsl(SHADER_SOURCE))
        .clone()
}

/// The same material without the press glow, for surfaces that are not
/// pressed: identical pixels at `press == 0`, with the glow's branch and its
/// `length`/`smoothstep` work compiled out.
pub fn glass_shader_rest() -> BackdropShader {
    static SHADER: OnceLock<BackdropShader> = OnceLock::new();
    SHADER
        .get_or_init(|| BackdropShader::wgsl(rest_source()))
        .clone()
}

/// The material for a whole grid of identical, unpressed surfaces drawn as one
/// backdrop effect (see [`paint_glass_grid`]).
pub fn glass_grid_shader() -> BackdropShader {
    static SHADER: OnceLock<BackdropShader> = OnceLock::new();
    SHADER
        .get_or_init(|| BackdropShader::wgsl(grid_source()))
        .clone()
}

/// The shader for a surface at `press`: the rest variant unless it is pressed.
fn shader_for(press: f32) -> BackdropShader {
    if press > 0.0 {
        glass_shader()
    } else {
        glass_shader_rest()
    }
}

/// Packs a spec into the shader's 8 uniform slots. `scale` is
/// `window.raster_scale_factor()`; every length is converted to device px.
pub fn glass_uniforms(
    bounds: Bounds<Pixels>,
    corners: Corners<Pixels>,
    spec: &GlassSpec,
    interaction: GlassInteraction,
    scale: f32,
) -> EffectUniforms {
    let d = |v: Pixels| v.as_f32() * scale;
    let center = bounds.center();
    let tint: Rgba = spec.tint.into();
    let (sin, cos) = spec.light_angle.to_radians().sin_cos();
    let focus = interaction.focus.unwrap_or(center);
    EffectUniforms::new()
        .with_slot(
            0,
            [
                d(center.x),
                d(center.y),
                d(bounds.size.width),
                d(bounds.size.height),
            ],
        )
        .with_slot(
            1,
            [
                d(corners.top_left),
                d(corners.top_right),
                d(corners.bottom_right),
                d(corners.bottom_left),
            ],
        )
        .with_slot(
            2,
            [
                d(spec.lens_height),
                d(spec.lens_amount),
                spec.depth as u8 as f32,
                spec.dispersion,
            ],
        )
        .with_slot(
            3,
            [
                spec.saturation,
                spec.brightness,
                spec.clarity,
                d(spec.rim_width),
            ],
        )
        .with_slot(4, [tint.r, tint.g, tint.b, tint.a])
        .with_slot(5, [cos, sin, spec.rim_alpha, spec.rim_falloff])
        .with_slot(
            6,
            [
                spec.inner_shade,
                spec.dark_rim,
                interaction.press.clamp(0.0, 1.0),
                1.5,
            ],
        )
        .with_slot(7, [d(focus.x), d(focus.y), 0.0, 0.0])
}

thread_local! {
    static FORCE_BACKDROP: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// Paint real glass even when the window reports no backdrop support.
/// Only for headless rendering tests: GPUI's test window answers `false` to
/// `supports_backdrop_blur()`, while the offscreen wgpu renderer used for
/// screenshots does support it. Never enable this in an app.
pub fn force_backdrop_support(force: bool) {
    FORCE_BACKDROP.with(|f| f.set(force));
}

fn backdrop_supported(window: &Window) -> bool {
    window.supports_backdrop_blur() || FORCE_BACKDROP.with(|f| f.get())
}

/// Paints the glass material behind `bounds`. Call during paint, before children.
pub fn paint_glass(
    bounds: Bounds<Pixels>,
    corners: Corners<Pixels>,
    spec: &GlassSpec,
    interaction: GlassInteraction,
    window: &mut Window,
) {
    if bounds.size.width <= px(0.0) || bounds.size.height <= px(0.0) {
        return;
    }
    let corners = corners.clamp_radii_for_quad_size(bounds.size);
    if !backdrop_supported(window) {
        paint_glass_fallback(bounds, corners, spec, window);
        return;
    }
    let scale = window.raster_scale_factor();
    let uniforms = glass_uniforms(bounds, corners, spec, interaction, scale);
    window.paint_backdrop_effect(
        PaintBackdropEffect::new(
            effect_bounds(bounds, spec, scale),
            spec.blur,
            shader_for(interaction.press),
        )
        .uniforms(uniforms),
    );
}

/// Most cells a [`GlassGrid`] can hold (the skip mask travels as an f32).
pub const GRID_MAX_CELLS: usize = 24;

/// A regular grid of equal glass surfaces (a dial pad), described by where
/// its first cell sits and how far apart the cells are.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct GlassGrid {
    /// Top-left corner of cell (0, 0).
    pub origin: Point<Pixels>,
    /// Size of every cell.
    pub cell: gpui::Size<Pixels>,
    /// Distance from one cell's top-left corner to the next one's, per axis.
    pub pitch: gpui::Size<Pixels>,
    pub cols: u8,
    pub rows: u8,
}

impl GlassGrid {
    pub fn cells(&self) -> usize {
        usize::from(self.cols) * usize::from(self.rows)
    }

    /// Bounds of cell `index` (row major).
    pub fn cell_bounds(&self, index: usize) -> Bounds<Pixels> {
        let cols = usize::from(self.cols).max(1);
        let (col, row) = ((index % cols) as f32, (index / cols) as f32);
        Bounds::new(
            point(
                self.origin.x + self.pitch.width * col,
                self.origin.y + self.pitch.height * row,
            ),
            self.cell,
        )
    }

    /// The smallest rectangle holding every cell.
    pub fn bounds(&self) -> Bounds<Pixels> {
        let first = self.cell_bounds(0);
        let last = self.cell_bounds(self.cells().saturating_sub(1));
        Bounds::from_corners(first.origin, last.bottom_right())
    }

    fn valid(&self) -> bool {
        self.cols > 0
            && self.rows > 0
            && self.cols < 16
            && self.rows < 16
            && self.cells() <= GRID_MAX_CELLS
            && self.cell.width > px(0.0)
            && self.cell.height > px(0.0)
    }
}

/// Packs the grid shader's inputs: slot 0 is the first cell exactly as a
/// single surface would have it, slot 6 carries the skip mask and the grid
/// size (where the single-surface press fields were), slot 7 the pitch.
pub fn glass_grid_uniforms(
    grid: &GlassGrid,
    corners: Corners<Pixels>,
    spec: &GlassSpec,
    skip: u32,
    scale: f32,
) -> EffectUniforms {
    let first = grid.cell_bounds(0);
    let corners = corners.clamp_radii_for_quad_size(grid.cell);
    glass_uniforms(first, corners, spec, GlassInteraction::default(), scale)
        .with_slot(
            6,
            [
                spec.inner_shade,
                spec.dark_rim,
                (skip & ((1 << GRID_MAX_CELLS) - 1)) as f32,
                f32::from(grid.cols) + 16.0 * f32::from(grid.rows),
            ],
        )
        .with_slot(
            7,
            [
                grid.pitch.width.as_f32() * scale,
                grid.pitch.height.as_f32() * scale,
                0.0,
                0.0,
            ],
        )
}

/// Paints every cell of `grid` whose bit in `skip` is clear as ONE backdrop
/// effect: one copy of the backdrop, one blur, one composite, instead of one
/// of each per surface. Pixel-equivalent to painting the cells one by one with
/// [`paint_glass`] at rest, as long as the cells are further apart than the
/// blur reaches (a dial pad's gaps are). Surfaces being pressed are left out
/// with `skip` and painted on their own (they need the press glow and a
/// different size). Call during paint, before the cells' children.
pub fn paint_glass_grid(
    grid: &GlassGrid,
    corners: Corners<Pixels>,
    spec: &GlassSpec,
    skip: u32,
    window: &mut Window,
) {
    if !grid.valid() {
        return;
    }
    let live = (0..grid.cells()).filter(|i| skip & (1 << i) == 0);
    if !backdrop_supported(window) {
        for index in live {
            let bounds = grid.cell_bounds(index);
            let corners = corners.clamp_radii_for_quad_size(bounds.size);
            paint_glass_fallback(bounds, corners, spec, window);
        }
        return;
    }
    // Only the cells that are painted decide the bounds, so a pressed key's
    // cell does not make the effect bigger than it needs to be.
    let mut live = live.map(|i| grid.cell_bounds(i));
    let Some(first) = live.next() else {
        return;
    };
    let area = live.fold(first, |acc, b| acc.union(&b));
    let scale = window.raster_scale_factor();
    window.paint_backdrop_effect(
        PaintBackdropEffect::new(
            effect_bounds(area, spec, scale),
            spec.blur,
            glass_grid_shader(),
        )
        .uniforms(glass_grid_uniforms(grid, corners, spec, skip, scale)),
    );
}

static WARMED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// An invisible element that makes the renderer compile every glass pipeline
/// during the first frame (while only the backdrop shows), instead of on the
/// first touch of a glass control. It paints three one-pixel effects outside
/// the window, once; afterwards it paints nothing.
pub fn warm_up_layer() -> Div {
    div()
        .absolute()
        .size(px(1.0))
        .on_paint_before_children(|_, _, window, _| {
            if !backdrop_supported(window)
                || WARMED.swap(true, std::sync::atomic::Ordering::Relaxed)
            {
                return;
            }
            let off_screen = Bounds::new(point(px(-8.0), px(-8.0)), gpui::size(px(1.0), px(1.0)));
            for shader in [glass_shader(), glass_shader_rest(), glass_grid_shader()] {
                window.paint_backdrop_effect(PaintBackdropEffect::new(off_screen, px(0.0), shader));
            }
        })
}

/// Bounds to hand the renderer: the shape plus room for the anti-aliased edge
/// and for the outer half of the dark rim, which the snapped capture rectangle
/// would otherwise clip. The shader computes its own coverage.
pub fn effect_bounds(bounds: Bounds<Pixels>, spec: &GlassSpec, scale: f32) -> Bounds<Pixels> {
    bounds.dilate(spec.rim_width + px(1.0 / scale))
}

/// Reduced-transparency / unsupported-backend version: an opaque-ish fill
/// and a hairline, so layout and contrast stay identical.
pub fn paint_glass_fallback(
    bounds: Bounds<Pixels>,
    corners: Corners<Pixels>,
    spec: &GlassSpec,
    window: &mut Window,
) {
    let mut fill_color = spec.tint;
    fill_color.a = (fill_color.a + 0.55).min(0.94);
    let border = if spec.tint.l > 0.5 {
        hsla(0.0, 0.0, 0.0, 0.08)
    } else {
        hsla(0.0, 0.0, 1.0, 0.12)
    };
    window.paint_quad(
        fill(bounds, fill_color)
            .corner_radii(corners)
            .border_widths(px(0.5))
            .border_color(border),
    );
}

/// A `Div` whose background is glass. Style it like any div; children paint
/// on top, sharp.
///
/// ```ignore
/// glass(GlassSpec::chrome(theme)).rounded_full().h(px(56.)).px_4().child(..)
/// ```
pub fn glass(spec: GlassSpec) -> Div {
    div().on_paint_before_children(move |bounds, style, window, _cx| {
        let corners = style.corner_radii.to_pixels(window.rem_size());
        paint_glass(bounds, corners, &spec, GlassInteraction::default(), window);
    })
}

/// Same, but reads press progress and focus from a closure every paint, so
/// a spring stored in your view drives the glow without re-rendering.
pub fn glass_interactive(
    spec: GlassSpec,
    interaction: impl Fn() -> GlassInteraction + 'static,
) -> Div {
    div().on_paint_before_children(move |bounds, style, window, _cx| {
        let corners = style.corner_radii.to_pixels(window.rem_size());
        paint_glass(bounds, corners, &spec, interaction(), window);
    })
}

/// Scales `bounds` about its centre (press/puck scale without re-layout).
pub fn scale_about_center(bounds: Bounds<Pixels>, sx: f32, sy: f32) -> Bounds<Pixels> {
    let c = bounds.center();
    let w = bounds.size.width * sx;
    let h = bounds.size.height * sy;
    Bounds::new(point(c.x - w / 2.0, c.y - h / 2.0), gpui::size(w, h))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `str::replace` is a silent no-op when the pattern is gone: these fail
    /// loudly if the WGSL text drifts from the strings the variants rewrite.
    #[test]
    fn rest_variant_really_switches_the_press_glow_off() {
        assert!(SHADER_SOURCE.contains(PRESS_GLOW_ON));
        let rest = rest_source();
        assert_ne!(rest, SHADER_SOURCE);
        assert!(rest.contains(PRESS_GLOW_OFF));
        assert!(!rest.contains(PRESS_GLOW_ON));
    }

    #[test]
    fn grid_variant_renames_the_entry_and_appends_the_grid_entry() {
        assert_eq!(SHADER_SOURCE.matches(SINGLE_ENTRY).count(), 1);
        let grid = grid_source();
        assert_ne!(grid, rest_source());
        // Only the appended grid entry defines `backdrop_effect` now.
        let template = &grid[..grid.len() - GRID_ENTRY.len()];
        assert!(!template.contains(SINGLE_ENTRY));
        assert!(template.contains(GRID_SHADE));
        assert!(GRID_ENTRY.contains(SINGLE_ENTRY));
        assert!(grid.contains(GRID_SHADE));
        assert!(grid.ends_with(GRID_ENTRY));
        assert!(!grid.contains(PRESS_GLOW_ON));
    }
}
