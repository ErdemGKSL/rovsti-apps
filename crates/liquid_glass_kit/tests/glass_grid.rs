//! Pixel-equivalence of the glass variants (AD-21): the rest shader equals the
//! full shader at `press == 0`, and one grid effect equals painting its cells
//! one by one. Renders offscreen with wgpu (needs a GPU adapter).
//!
//!   cargo test --test glass_grid -- --include-ignored --nocapture

use gpui::{
    BackdropBlur, BackdropShader, Background, Bounds, ContentMask, Corners, DevicePixels, Hsla,
    Pixels, Quad, ScaledPixels, Scene, hsla, point, px, size,
};
use gpui_wgpu::WgpuOffscreenRenderer;
use liquid_glass_kit::{
    glass::{
        GlassGrid, GlassInteraction, GlassSpec, effect_bounds, glass_grid_shader,
        glass_grid_uniforms, glass_shader, glass_shader_rest, glass_uniforms,
    },
    tokens::Theme,
};

const W: i32 = 402;
const H: i32 = 480;

fn sp(b: Bounds<Pixels>) -> Bounds<ScaledPixels> {
    Bounds::new(
        point(
            ScaledPixels(b.origin.x.as_f32()),
            ScaledPixels(b.origin.y.as_f32()),
        ),
        size(
            ScaledPixels(b.size.width.as_f32()),
            ScaledPixels(b.size.height.as_f32()),
        ),
    )
}

fn rect(x: f32, y: f32, w: f32, h: f32) -> Bounds<Pixels> {
    Bounds::new(point(px(x), px(y)), size(px(w), px(h)))
}

fn mask() -> ContentMask<ScaledPixels> {
    ContentMask {
        bounds: sp(rect(0.0, 0.0, W as f32, H as f32)),
    }
}

fn quad(scene: &mut Scene, b: Bounds<Pixels>, color: Hsla, radius: f32) {
    scene.insert_primitive(Quad {
        bounds: sp(b),
        content_mask: mask(),
        background: Background::from(color),
        corner_radii: Corners::all(ScaledPixels(radius)),
        ..Default::default()
    });
}

/// A busy background, so refraction and blur show.
fn background(scene: &mut Scene) {
    quad(
        scene,
        rect(0.0, 0.0, W as f32, H as f32),
        hsla(0.55, 0.5, 0.5, 1.0),
        0.0,
    );
    for i in 0..22 {
        quad(
            scene,
            rect(i as f32 * 19.0, 0.0, 9.0, H as f32),
            hsla((i as f32 * 0.07) % 1.0, 0.8, 0.6, 1.0),
            0.0,
        );
    }
    for row in 0..30 {
        quad(
            scene,
            rect(10.0, 6.0 + row as f32 * 16.0, 380.0, 3.0),
            hsla(0.0, 0.0, 1.0, 0.9),
            1.5,
        );
    }
}

fn effect(
    scene: &mut Scene,
    bounds: Bounds<Pixels>,
    blur: Pixels,
    shader: BackdropShader,
    uniforms: gpui::EffectUniforms,
) {
    scene.insert_primitive(BackdropBlur {
        order: 0,
        bounds: sp(bounds),
        content_mask: mask(),
        corner_radii: Corners::default(),
        blur_radius: ScaledPixels(blur.as_f32()),
        opacity: 1.0,
        shader: Some(shader),
        uniforms,
        time: 0.0,
        pointer: point(0.5, 0.5),
        pointer_active: false,
    });
}

fn single(
    scene: &mut Scene,
    b: Bounds<Pixels>,
    spec: &GlassSpec,
    shader: BackdropShader,
    press: f32,
) {
    let corners = Corners::all(b.size.width / 2.0).clamp_radii_for_quad_size(b.size);
    let interaction = GlassInteraction { press, focus: None };
    effect(
        scene,
        effect_bounds(b, spec, 1.0),
        spec.blur,
        shader,
        glass_uniforms(b, corners, spec, interaction, 1.0),
    );
}

fn pad() -> GlassGrid {
    GlassGrid {
        origin: point(px(60.0), px(40.0)),
        cell: size(px(78.0), px(78.0)),
        pitch: size(px(102.0), px(90.0)),
        cols: 3,
        rows: 4,
    }
}

fn render(build: impl FnOnce(&mut Scene)) -> anyhow::Result<Vec<u8>> {
    let mut renderer = WgpuOffscreenRenderer::new(size(DevicePixels(W), DevicePixels(H)))?;
    let mut scene = Scene::default();
    background(&mut scene);
    build(&mut scene);
    scene.finish();
    renderer.render_rgba(&scene)
}

/// Number of channel values that differ by more than `tolerance`.
fn differing(a: &[u8], b: &[u8], tolerance: u8) -> usize {
    a.iter()
        .zip(b)
        .filter(|(x, y)| x.abs_diff(**y) > tolerance)
        .count()
}

#[test]
#[ignore = "requires a GPU adapter"]
fn the_rest_shader_matches_the_full_shader_when_not_pressed() -> anyhow::Result<()> {
    for theme in [Theme::Light, Theme::Dark] {
        let spec = GlassSpec::control(theme);
        let paint = |shader: BackdropShader| {
            render(|scene| {
                single(
                    scene,
                    rect(60.0, 60.0, 78.0, 78.0),
                    &spec,
                    shader.clone(),
                    0.0,
                );
                single(
                    scene,
                    rect(200.0, 60.0, 140.0, 56.0),
                    &GlassSpec::chrome(theme),
                    shader.clone(),
                    0.0,
                );
                single(
                    scene,
                    rect(60.0, 200.0, 120.0, 120.0),
                    &GlassSpec::lens(theme),
                    shader,
                    0.0,
                );
            })
        };
        let full = paint(glass_shader())?;
        let rest = paint(glass_shader_rest())?;
        assert_eq!(differing(&full, &rest, 1), 0, "{theme:?}");
    }
    Ok(())
}

#[test]
#[ignore = "requires a GPU adapter"]
fn one_grid_effect_matches_twelve_single_surfaces() -> anyhow::Result<()> {
    let grid = pad();
    for theme in [Theme::Light, Theme::Dark] {
        let spec = GlassSpec::control(theme);
        let corners = Corners::all(px(39.0));
        let singles = render(|scene| {
            for i in 0..grid.cells() {
                single(scene, grid.cell_bounds(i), &spec, glass_shader(), 0.0);
            }
        })?;
        let merged = render(|scene| {
            effect(
                scene,
                effect_bounds(grid.bounds(), &spec, 1.0),
                spec.blur,
                glass_grid_shader(),
                glass_grid_uniforms(&grid, corners, &spec, 0, 1.0),
            );
        })?;
        let bad = differing(&singles, &merged, 2);
        assert_eq!(
            bad, 0,
            "{theme:?}: {bad} channel values differ by more than 2/255"
        );
        // Not a blank result: the glass changed the background.
        let plain = render(|_| {})?;
        assert!(
            differing(&plain, &merged, 8) > 1000,
            "{theme:?}: glass did nothing"
        );
    }
    Ok(())
}

#[test]
#[ignore = "requires a GPU adapter"]
fn a_skipped_cell_is_left_to_its_own_effect() -> anyhow::Result<()> {
    let grid = pad();
    let theme = Theme::Light;
    let spec = GlassSpec::control(theme);
    let corners = Corners::all(px(39.0));
    let pressed = 4; // the middle key
    let expected = render(|scene| {
        for i in 0..grid.cells() {
            let press = if i == pressed { 0.6 } else { 0.0 };
            let spec = if i == pressed {
                spec.lerp(GlassSpec::lens(theme), 0.54)
            } else {
                spec
            };
            let shader = if i == pressed {
                glass_shader()
            } else {
                glass_shader_rest()
            };
            single(scene, grid.cell_bounds(i), &spec, shader, press);
        }
    })?;
    let merged = render(|scene| {
        effect(
            scene,
            effect_bounds(grid.bounds(), &spec, 1.0),
            spec.blur,
            glass_grid_shader(),
            glass_grid_uniforms(&grid, corners, &spec, 1 << pressed, 1.0),
        );
        single(
            scene,
            grid.cell_bounds(pressed),
            &spec.lerp(GlassSpec::lens(theme), 0.54),
            glass_shader(),
            0.6,
        );
    })?;
    assert_eq!(differing(&expected, &merged, 2), 0);
    Ok(())
}

#[test]
fn grid_geometry_is_row_major() {
    let grid = pad();
    assert_eq!(grid.cells(), 12);
    assert_eq!(grid.cell_bounds(0).origin, point(px(60.0), px(40.0)));
    assert_eq!(grid.cell_bounds(4).origin, point(px(162.0), px(130.0)));
    assert_eq!(
        grid.bounds(),
        rect(60.0, 40.0, 78.0 + 2.0 * 102.0, 78.0 + 3.0 * 90.0)
    );
}

/// Average render time of a 12-key dial pad: twelve effects against one.
/// This GPU is not the phone's; what carries over is the ratio and that one
/// effect means one backdrop copy, one blur and one composite instead of 12.
///
///   cargo test --release --test glass_grid -- --ignored --nocapture pad_cost
#[test]
#[ignore = "requires a GPU adapter, prints timings"]
fn pad_cost() -> anyhow::Result<()> {
    use std::time::Instant;

    let grid = pad();
    let theme = Theme::Light;
    let spec = GlassSpec::control(theme);
    let corners = Corners::all(px(39.0));
    let mut renderer = WgpuOffscreenRenderer::new(size(DevicePixels(W), DevicePixels(H)))?;
    let mut time = |name: &str, build: &dyn Fn(&mut Scene)| -> anyhow::Result<f64> {
        let mut scene = Scene::default();
        background(&mut scene);
        build(&mut scene);
        scene.finish();
        for _ in 0..10 {
            renderer.render_rgba(&scene)?;
        }
        let runs = 100;
        let start = Instant::now();
        for _ in 0..runs {
            renderer.render_rgba(&scene)?;
        }
        let ms = start.elapsed().as_secs_f64() * 1000.0 / f64::from(runs);
        println!("{name:<28} {ms:.3} ms per frame");
        Ok(ms)
    };
    let none = time("background only", &|_| {})?;
    let twelve = time("12 single effects", &|scene| {
        for i in 0..grid.cells() {
            single(scene, grid.cell_bounds(i), &spec, glass_shader(), 0.0);
        }
    })?;
    let twelve_rest = time("12 single effects (rest)", &|scene| {
        for i in 0..grid.cells() {
            single(scene, grid.cell_bounds(i), &spec, glass_shader_rest(), 0.0);
        }
    })?;
    let one = time("1 grid effect", &|scene| {
        effect(
            scene,
            effect_bounds(grid.bounds(), &spec, 1.0),
            spec.blur,
            glass_grid_shader(),
            glass_grid_uniforms(&grid, corners, &spec, 0, 1.0),
        );
    })?;
    println!(
        "glass cost over background: 12 effects {:.3} ms, rest {:.3} ms, grid {:.3} ms",
        twelve - none,
        twelve_rest - none,
        one - none
    );
    Ok(())
}
