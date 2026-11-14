//! A lens made of an SVG `feDisplacementMap`, for the one lens that sweeps across a card in the animations.
//!
//! The map is a PNG whose red and green channels hold the displacement of each pixel, centred on 128: a dome that
//! samples from nearer the lens centre, so what is behind looks magnified and bends smoothly to nothing at the rim.
//! The strength is not in the map but in the filter's `scale`, so one map serves every frame of a sweep and the
//! squash of the panel can drive the strength per frame.
//!
//! This is a small map of our own for the animation. The full glisse edge-band maps for the static panel belong to the
//! static templates (plan step 2).

use base64::Engine as _;
use takumi::prelude::{Bitmap, OutputFormat};

use super::CardError;

/// How far a pixel at half the lens radius moves at `scale = 1`, as a fraction of its distance from the centre, at
/// the full-strength dome. The filter scale multiplies this.
const DOME: f32 = 1.0;

/// The displacement map of a `w` x `h` lens (an ellipse inscribed in the box) as PNG bytes.
pub fn lens_map_png(w: u32, h: u32) -> Result<Vec<u8>, CardError> {
    let (cx, cy) = (w as f32 / 2.0, h as f32 / 2.0);
    let mut data = Vec::with_capacity((w * h * 4) as usize);
    for y in 0..h {
        for x in 0..w {
            let (u, v) = ((x as f32 + 0.5 - cx) / cx, (y as f32 + 0.5 - cy) / cy);
            let r2 = u * u + v * v;
            let g = if r2 < 1.0 {
                DOME * (1.0 - r2 * r2)
            } else {
                0.0
            };
            // Sample from nearer the centre: displacement against the position. Full range is 1 (= `scale` px).
            let dx = (-u * g * 0.5 + 0.5).clamp(0.0, 1.0);
            let dy = (-v * g * 0.5 + 0.5).clamp(0.0, 1.0);
            data.extend_from_slice(&[
                (dx * 255.0).round() as u8,
                (dy * 255.0).round() as u8,
                128,
                255,
            ]);
        }
    }
    let bitmap =
        Bitmap::from_raw(w, h, data).ok_or_else(|| CardError::Render("lens map size".into()))?;
    let mut png = Vec::new();
    takumi::write_image(&bitmap, &mut png, OutputFormat::Png)
        .map_err(|e| CardError::Encode(e.to_string()))?;
    Ok(png)
}

/// Percent-encodes everything but unreserved characters, for a `data:` URI body.
fn encode(markup: &str) -> String {
    let mut out = String::with_capacity(markup.len() * 2);
    for b in markup.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// The CSS `url(...)` value of a filter that displaces its input by `map_png` (a `w` x `h` lens map) with `scale`
/// pixels of full displacement. Used as a `backdrop-filter` it bends what is behind the element.
pub fn lens_filter_url(map_png: &[u8], w: u32, h: u32, scale: f32) -> String {
    let frac = scale / w as f32;
    let _ = h;
    let href = format!(
        "data:image/png;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(map_png)
    );
    let markup = format!(
        "<filter id=\"takumi-filter\" color-interpolation-filters=\"sRGB\" primitiveUnits=\"objectBoundingBox\" x=\"0\" y=\"0\" width=\"100%\" height=\"100%\">\
         <feImage href=\"{href}\" x=\"0\" y=\"0\" width=\"1\" height=\"1\" preserveAspectRatio=\"none\" result=\"map\"/>\
         <feDisplacementMap in=\"SourceGraphic\" in2=\"map\" scale=\"{frac:.4}\" xChannelSelector=\"R\" yChannelSelector=\"G\"/>\
         </filter>"
    );
    format!("url(data:image/svg+xml,{})", encode(&markup))
}
