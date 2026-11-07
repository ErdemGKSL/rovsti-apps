//! Colour helpers of the headless shots.
//!
//! `gpui_wgpu`'s offscreen target is `Rgba8UnormSrgb`, but GPUI already writes
//! sRGB-encoded values, so the hardware encodes them a second time and the
//! readback is too light. [`srgb_decode_lut`] undoes that one extra encode.

use image::RgbaImage;

use crate::layout::geom::contrast_ratio;

/// sRGB-to-linear table for 8-bit channels (`lut[v]` is the decoded `v`).
pub fn srgb_decode_lut() -> [u8; 256] {
    let mut lut = [0u8; 256];
    for (i, out) in lut.iter_mut().enumerate() {
        let c = i as f32 / 255.0;
        let lin = if c <= 0.040_45 {
            c / 12.92
        } else {
            ((c + 0.055) / 1.055).powf(2.4)
        };
        *out = (lin * 255.0).round().clamp(0.0, 255.0) as u8;
    }
    lut
}

/// Decodes R, G and B of an RGBA8 buffer in place; alpha is left alone.
pub fn decode_rgba(rgba: &mut [u8]) {
    let lut = srgb_decode_lut();
    for px in rgba.as_chunks_mut::<4>().0 {
        for c in &mut px[..3] {
            *c = lut[usize::from(*c)];
        }
    }
}

/// WCAG contrast between the darkest and the lightest pixel in
/// `rect = (x, y, w, h)` (px, clamped to the image).
pub fn contrast_in(img: &RgbaImage, rect: (u32, u32, u32, u32)) -> f32 {
    let (x, y, w, h) = rect;
    let (x1, y1) = ((x + w).min(img.width()), (y + h).min(img.height()));
    let lum = |p: &image::Rgba<u8>| [p[0], p[1], p[2]].map(|c| f32::from(c) / 255.0);
    let mut dark: Option<([f32; 3], f32)> = None;
    let mut light: Option<([f32; 3], f32)> = None;
    for py in y.min(y1)..y1 {
        for px in x.min(x1)..x1 {
            let c = lum(img.get_pixel(px, py));
            let l = contrast_ratio(c, [0.0; 3]);
            if dark.is_none_or(|(_, d)| l < d) {
                dark = Some((c, l));
            }
            if light.is_none_or(|(_, d)| l > d) {
                light = Some((c, l));
            }
        }
    }
    match (dark, light) {
        (Some((d, _)), Some((l, _))) => contrast_ratio(d, l),
        _ => 1.0,
    }
}

/// Largest per-channel jump between vertically adjacent pixels in column `x`,
/// rows `y0..y1`. A smooth gradient has small steps; a hard edge a big one.
pub fn max_vertical_step(img: &RgbaImage, x: u32, y0: u32, y1: u32) -> u8 {
    if x >= img.width() {
        return 0;
    }
    let y1 = y1.min(img.height());
    let mut max = 0u8;
    for y in y0..y1.saturating_sub(1) {
        let (a, b) = (img.get_pixel(x, y), img.get_pixel(x, y + 1));
        for c in 0..3 {
            max = max.max(a[c].abs_diff(b[c]));
        }
    }
    max
}

#[cfg(test)]
mod tests {
    use super::*;

    fn linear_to_srgb(v: u8) -> u8 {
        let l = f32::from(v) / 255.0;
        let c = if l <= 0.003_130_8 {
            l * 12.92
        } else {
            1.055 * l.powf(1.0 / 2.4) - 0.055
        };
        (c * 255.0).round() as u8
    }

    #[test]
    fn lut_endpoints_monotonic_and_inverse() {
        let lut = srgb_decode_lut();
        assert_eq!((lut[0], lut[255]), (0, 255));
        assert!(lut.windows(2).all(|w| w[0] <= w[1]));
        assert!(lut[usize::from(linear_to_srgb(122))].abs_diff(122) <= 1);
        assert!(lut[usize::from(linear_to_srgb(10))].abs_diff(10) <= 1);
    }

    #[test]
    fn decode_leaves_alpha() {
        let mut px = [255, 128, 0, 77];
        decode_rgba(&mut px);
        assert_eq!(px[3], 77);
        assert_eq!((px[0], px[2]), (255, 0));
        assert!(px[1] < 128);
    }

    #[test]
    fn contrast_of_two_colours() {
        let mut img = RgbaImage::from_pixel(10, 10, image::Rgba([255, 255, 255, 255]));
        for x in 0..5 {
            for y in 0..10 {
                img.put_pixel(x, y, image::Rgba([0, 0, 0, 255]));
            }
        }
        assert!((contrast_in(&img, (0, 0, 10, 10)) - 21.0).abs() < 0.01);
        assert!((contrast_in(&img, (6, 0, 4, 10)) - 1.0).abs() < 0.01);
        assert_eq!(contrast_in(&img, (20, 20, 4, 4)), 1.0);
    }

    #[test]
    fn vertical_step() {
        let mut img = RgbaImage::from_pixel(4, 10, image::Rgba([10, 10, 10, 255]));
        for y in 5..10 {
            img.put_pixel(1, y, image::Rgba([50, 12, 10, 255]));
        }
        assert_eq!(max_vertical_step(&img, 1, 0, 10), 40);
        assert_eq!(max_vertical_step(&img, 0, 0, 10), 0);
        assert_eq!(max_vertical_step(&img, 9, 0, 10), 0);
    }
}

/// Run: `cargo test -p rovsti_ui --features shots color_probe -- --ignored`
#[cfg(test)]
mod probe {
    use gpui::{
        AppContext as _, Context, IntoElement, ParentElement as _, Render, Styled as _, Window,
        div, px, rgb,
    };
    use liquid_glass_kit::tokens::Theme;

    use super::super::{PHONE, Rig, ShotSpec, isolate_data_dir};

    struct Probe;

    impl Render for Probe {
        fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
            let swatch = |hex: u32, alpha: f32| {
                div().size(px(40.0)).child(
                    div()
                        .size_full()
                        .bg(gpui::Hsla::from(rgb(hex)).opacity(alpha)),
                )
            };
            div()
                .size_full()
                .bg(rgb(0xFFFFFF))
                .flex()
                .child(swatch(0x007AFF, 1.0))
                .child(swatch(0x0A84FF, 1.0))
                .child(swatch(0x3C3C43, 0.74))
        }
    }

    #[test]
    #[ignore = "needs a GPU adapter (lavapipe works)"]
    fn color_probe() {
        isolate_data_dir("rovsti_ui");
        let spec = ShotSpec {
            app: "rovsti_ui",
            nn: 4,
            screen: "color-probe",
            theme: Theme::Light,
            size: PHONE,
        };
        let mut rig = Rig::open(spec, |_, cx| cx.new(|_| Probe));
        rig.theme();
        rig.settle();
        let img = rig.capture();
        rig.save_image(&img);
        // Swatches are 40 dp = 80 px; centres at 40, 120, 200 px; y = 40.
        let want = [(0, 122, 255), (10, 132, 255), (111, 111, 115)];
        for (i, w) in want.iter().enumerate() {
            let p = img.get_pixel(40 + 80 * i as u32, 40);
            let got = (p[0], p[1], p[2]);
            println!("swatch {i}: {got:?} want {w:?}");
            assert!(
                got.0.abs_diff(w.0) <= 3 && got.1.abs_diff(w.1) <= 3 && got.2.abs_diff(w.2) <= 3,
                "swatch {i}: {got:?} vs {w:?}"
            );
        }
    }
}
