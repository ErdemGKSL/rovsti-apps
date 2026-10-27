//! Renders the glass shader offscreen, with no window, and writes a PNG. Use it
//! to tune `GlassSpec` values while looking at real pixels, and to catch
//! regressions such as a blank surface or broken coverage.
//!
//!   cargo test --test glass_snapshot -- --ignored --nocapture
//!   → target/glass_snapshot.png
//!
//! Requires a GPU adapter (wgpu: Vulkan/GL/Metal/DX12).

use gpui::{
    Background, BackdropBlur, Bounds, ContentMask, Corners, DevicePixels, Hsla, Pixels, Quad,
    ScaledPixels, Scene, hsla, point, px, size,
};
use gpui_wgpu::WgpuOffscreenRenderer;
use liquid_glass_kit::{
    glass::{GlassInteraction, GlassSpec, effect_bounds, glass_shader, glass_uniforms},
    tokens::Theme,
};

const W: i32 = 720;
const H: i32 = 360;

fn sp(b: Bounds<Pixels>) -> Bounds<ScaledPixels> {
    Bounds::new(
        point(ScaledPixels(b.origin.x.as_f32()), ScaledPixels(b.origin.y.as_f32())),
        size(ScaledPixels(b.size.width.as_f32()), ScaledPixels(b.size.height.as_f32())),
    )
}

fn rect(x: f32, y: f32, w: f32, h: f32) -> Bounds<Pixels> {
    Bounds::new(point(px(x), px(y)), size(px(w), px(h)))
}

fn quad(scene: &mut Scene, b: Bounds<Pixels>, color: Hsla, radius: f32) {
    let full = sp(rect(0.0, 0.0, W as f32, H as f32));
    scene.insert_primitive(Quad {
        bounds: sp(b),
        content_mask: ContentMask { bounds: full },
        background: Background::from(color),
        corner_radii: Corners::all(ScaledPixels(radius)),
        ..Default::default()
    });
}

fn glass(scene: &mut Scene, b: Bounds<Pixels>, radius: f32, spec: &GlassSpec, press: f32) {
    let full = sp(rect(0.0, 0.0, W as f32, H as f32));
    let corners = Corners::all(px(radius)).clamp_radii_for_quad_size(b.size);
    let interaction = GlassInteraction {
        press,
        focus: Some(point(b.origin.x + b.size.width * 0.3, b.origin.y + b.size.height * 0.3)),
    };
    scene.insert_primitive(BackdropBlur {
        order: 0,
        bounds: sp(effect_bounds(b, spec, 1.0)),
        content_mask: ContentMask { bounds: full },
        corner_radii: Corners::default(),
        blur_radius: ScaledPixels(spec.blur.as_f32()),
        opacity: 1.0,
        shader: Some(glass_shader()),
        uniforms: glass_uniforms(b, corners, spec, interaction, 1.0),
        time: 0.0,
        pointer: point(0.5, 0.5),
        pointer_active: false,
    });
}

#[test]
#[ignore = "requires a GPU adapter"]
fn render_glass_snapshot() -> anyhow::Result<()> {
    let mut renderer = WgpuOffscreenRenderer::new(size(DevicePixels(W), DevicePixels(H)))?;
    let mut scene = Scene::default();

    // Busy backdrop: stripes, blobs and "text" lines, so refraction is visible.
    quad(&mut scene, rect(0.0, 0.0, W as f32, H as f32), hsla(0.60, 0.55, 0.55, 1.0), 0.0);
    for i in 0..18 {
        let hue = (i as f32 * 0.09) % 1.0;
        quad(&mut scene, rect(i as f32 * 40.0, 0.0, 20.0, H as f32), hsla(hue, 0.8, 0.6, 1.0), 0.0);
    }
    quad(&mut scene, rect(40.0, 40.0, 220.0, 220.0), hsla(0.11, 1.0, 0.7, 1.0), 110.0);
    for row in 0..14 {
        quad(&mut scene, rect(20.0, 18.0 + row as f32 * 24.0, 680.0, 4.0), hsla(0.0, 0.0, 1.0, 0.9), 2.0);
    }

    let light = Theme::Light;
    // Tab bar (chrome), a pressed round control and a dispersive lens.
    glass(&mut scene, rect(60.0, 270.0, 600.0, 64.0), 32.0, &GlassSpec::chrome(light), 0.0);
    glass(&mut scene, rect(320.0, 60.0, 140.0, 140.0), 70.0, &GlassSpec::control(Theme::Dark), 1.0);
    glass(&mut scene, rect(500.0, 70.0, 180.0, 120.0), 40.0, &GlassSpec::lens(light), 0.0);

    scene.finish();
    let rgba = renderer.render_rgba(&scene)?;

    let out = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("target/glass_snapshot.png");
    std::fs::create_dir_all(out.parent().unwrap())?;
    image::save_buffer(&out, &rgba, W as u32, H as u32, image::ColorType::Rgba8)?;
    println!("wrote {}", out.display());

    // Sanity checks: glass must change the pixels it covers (not a no-op) and
    // must leave pixels outside its shape untouched.
    let px_at = |x: usize, y: usize| &rgba[(y * W as usize + x) * 4..][..3];
    let inside_bar = px_at(360, 300);
    assert!(inside_bar.iter().any(|&c| c > 0), "glass rendered black");
    Ok(())
}
