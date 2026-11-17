// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// (src/internal/refraction.ts: `profile`, `selectionMaps`; src/internal/lens.ts: `distance`, `arc`, `slope`, `puller`).
//! Glass optics for the one-shot card GIF: the maps an SVG `feImage` feeds to `feDisplacementMap` and
//! `feSpecularLighting`, and the filter markup that uses them.
//!
//! Everything here is pure and platform-free: shapes in, RGBA pixels or PNG bytes out. Three maps:
//!
//! - [`edge_band_map`]: the panel's bezel. Light bends only in a thin band along the rim (glisse's "selection lens",
//!   `refraction.ts`), by Snell's law through a squircle surface of index 1.5. The band's shift is stored in red
//!   (across) and green (down) around the neutral grey 128; the map's `scale` is twice the largest shift in pixels.
//! - [`highlight_map`]: white with an alpha ring along the rim, brightest on the corner the light falls on
//!   (`refraction.ts`, `shine`). It is the bump the specular glint is lit from.
//! - [`lens_map`]: a dome lens (`lens.ts`). Inside the shape the content is pulled by the slope of a circular arc,
//!   scaled so the slope averages 0.5 over the half span ([`arc`]), faded in by an `erf` ramp from the rim.
//!
//! The filter builders ([`panel_filter`], [`lens_filter`]) return a `url(data:image/svg+xml;base64,...)` token for
//! Takumi's `backdrop-filter`: three displacement passes (red, green, blue at slightly different scales, a chromatic
//! fringe of at most [`MAX_FRINGE_PX`]) summed back together, and for the panel an `feSpecularLighting` rim glint.

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use image::ImageEncoder as _;
use image::codecs::png::PngEncoder;

use super::CardError;

/// Largest gap, in pixels, between the red and blue copies of a displaced pixel.
pub const MAX_FRINGE_PX: f32 = 1.5;
/// The neutral byte of a displacement map: no shift.
const NEUTRAL: u8 = 128;

/// A rounded rectangle centred on the origin, in pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Shape {
    pub width: f32,
    pub height: f32,
    pub radius: f32,
}

/// RGBA pixels of a map, straight alpha, row by row.
#[derive(Clone, Debug, PartialEq)]
pub struct Raster {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

impl Raster {
    fn new(width: u32, height: u32) -> Self {
        Self {
            width,
            height,
            rgba: vec![0; (width * height * 4) as usize],
        }
    }

    fn put(&mut self, x: u32, y: u32, px: [u8; 4]) {
        let i = ((y * self.width + x) * 4) as usize;
        self.rgba[i..i + 4].copy_from_slice(&px);
    }

    /// The pixel at (`x`, `y`).
    pub fn at(&self, x: u32, y: u32) -> [u8; 4] {
        let i = ((y * self.width + x) * 4) as usize;
        [
            self.rgba[i],
            self.rgba[i + 1],
            self.rgba[i + 2],
            self.rgba[i + 3],
        ]
    }

    /// The raster as PNG bytes.
    pub fn png(&self) -> Result<Vec<u8>, CardError> {
        let mut out = Vec::new();
        PngEncoder::new(&mut out)
            .write_image(
                &self.rgba,
                self.width,
                self.height,
                image::ExtendedColorType::Rgba8,
            )
            .map_err(|e| CardError::Encode(e.to_string()))?;
        Ok(out)
    }
}

/// A displacement map with the filter `scale` that goes with it.
#[derive(Clone, Debug, PartialEq)]
pub struct Displacement {
    pub raster: Raster,
    /// `feDisplacementMap` scale in pixels for a full-strength map: the largest shift is half of it.
    pub scale: f32,
}

/// Signed distance to a rounded rectangle centred on the origin; negative inside (`lens.ts`, `distance`).
pub fn distance(x: f32, y: f32, half_w: f32, half_h: f32, radius: f32) -> f32 {
    let qx = x.abs() - half_w + radius;
    let qy = y.abs() - half_h + radius;
    qx.max(0.0).hypot(qy.max(0.0)) + qx.max(qy).min(0.0) - radius
}

/// `erf(x)` as `tanh(sqrt(pi) x)`, the approximation glisse uses.
fn erf(x: f32) -> f32 {
    (1.772_453_9 * x).tanh()
}

/// A circular arc that sags by some amount across a half span, and the scale that makes its slope average 0.5.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Arc {
    pub radius: f32,
    pub scale: f32,
}

/// The arc that sags by `sag` across `half`, scaled so its slope averages a half over that span (`lens.ts`, `arc`):
/// `scale = 0.5 / mean`, the mean taken over 200 trapezoid steps.
pub fn arc(sag: f32, half: f32) -> Arc {
    let radius = (half * half + sag * sag) / (2.0 * sag);
    let mut sum = 0.0f32;
    for i in 0..=200 {
        let x = i as f32 / 200.0 * half;
        let slope = x / (radius * radius - x * x).sqrt();
        sum += if i == 0 || i == 200 {
            slope / 2.0
        } else {
            slope
        };
    }
    let mean = sum / 200.0;
    Arc {
        radius,
        scale: if mean > 0.0 { 0.5 / mean } else { 1.0 },
    }
}

/// The scaled slope of `arc` at `x` from the centre (`lens.ts`, `slope`). Clamped just short of the arc's end.
pub fn slope(x: f32, arc: &Arc) -> f32 {
    let inside = x.min(0.999 * arc.radius);
    inside / (arc.radius * arc.radius - inside * inside).sqrt() * arc.scale
}

/// The shift profile of a bezel, 128 samples from the rim (index 0) to `bezel` px in, and its largest value
/// (`refraction.ts`, `profile`): a ray straight down through a squircle surface lands further in by the tangent of
/// how far glass of index 1.5 turned it, times the glass under it (`thickness` plus the surface height times `bezel`).
pub fn profile(bezel: f32, thickness: f32) -> (Vec<f32>, f32) {
    let mut samples = vec![0.0f32; 128];
    let mut maximum = 0.0f32;
    for (i, sample) in samples.iter_mut().enumerate() {
        let t = (i as f32 / 127.0).max(0.001);
        let surface = (1.0 - (1.0 - t).powi(4)).powf(0.25);
        let incident = ((1.0 - t).powi(3) / surface.powi(3)).atan();
        let refracted = (incident.sin() / 1.5).asin();
        *sample = (incident - refracted).tan() * (thickness + surface * bezel);
        maximum = maximum.max(*sample);
    }
    (samples, maximum)
}

/// Where a bezel band lies and how strongly it bends, in pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Band {
    /// Width of the band along the rim.
    pub bezel: f32,
    /// Glass thickness under the band; sets how far the rim pulls (the largest shift is about 1.1 times it).
    pub thickness: f32,
}

/// The unit outward normal of the nearest rim point of a rounded rectangle (the `selectionMaps` normal).
fn normal(px: f32, py: f32, half_w: f32, half_h: f32, radius: f32) -> (f32, f32, f32) {
    let qx = px.abs() - (half_w - radius);
    let qy = py.abs() - (half_h - radius);
    let (ox, oy) = (qx.max(0.0), qy.max(0.0));
    let length = ox.hypot(oy);
    let dist = radius - (length + qx.max(qy).min(0.0));
    let (nx, ny) = if length > 0.0 {
        (ox / length * px.signum(), oy / length * py.signum())
    } else if qx > qy {
        (px.signum(), 0.0)
    } else {
        (0.0, py.signum())
    };
    (nx, ny, dist)
}

/// The edge-band displacement map of a `shape`, one pixel per pixel (`refraction.ts`, `selectionMaps`).
///
/// Within `band.bezel` of the rim each pixel shifts along the rim's normal by the Snell profile; the shift is
/// stored as `127.5 + n * shift / max * 127.5` in red and green, so the centre of the shape is exactly the
/// neutral grey and the filter `scale` is `2 * max`. The sign pulls content from further in (towards the centre):
/// a filter reading only the pixels inside its own box then never reaches past its edge.
pub fn edge_band_map(shape: Shape, band: Band) -> Displacement {
    let width = shape.width.round().max(1.0) as u32;
    let height = shape.height.round().max(1.0) as u32;
    let (half_w, half_h) = (shape.width / 2.0, shape.height / 2.0);
    let radius = shape.radius.clamp(0.0, half_w.min(half_h));
    let (samples, maximum) = profile(band.bezel, band.thickness);
    let mut raster = Raster::new(width, height);
    for y in 0..height {
        for x in 0..width {
            let px = (x as f32 + 0.5) * shape.width / width as f32 - half_w;
            let py = (y as f32 + 0.5) * shape.height / height as f32 - half_h;
            let (nx, ny, dist) = normal(px, py, half_w, half_h, radius);
            let mut shift = 0.0;
            if dist >= 0.0 && dist < band.bezel {
                let at = dist / band.bezel * (samples.len() - 1) as f32;
                let lower = at.floor() as usize;
                let a = samples[lower];
                let b = samples[(lower + 1).min(samples.len() - 1)];
                shift = a + (b - a) * (at - lower as f32);
            }
            let k = shift / maximum;
            raster.put(
                x,
                y,
                [
                    to_byte(127.5 - nx * k * 127.5),
                    to_byte(127.5 - ny * k * 127.5),
                    NEUTRAL,
                    255,
                ],
            );
        }
    }
    Displacement {
        raster,
        scale: maximum * 2.0,
    }
}

/// The highlight map of a `shape` (`refraction.ts`, `selectionMaps`, `shine`): white where the light catches the
/// rim, with the alpha a sum of three Gaussian bands at 0.18, 0.42 and 0.66 of `rim` px from the edge, scaled by how
/// much the rim faces the light from the top left (`glint`, cubed) and its reflection (`reflected`, cubed). `rim` is
/// how many pixels one glisse pixel of the band spans (1 reproduces glisse at density 1).
pub fn highlight_map(shape: Shape, rim: f32) -> Raster {
    let width = shape.width.round().max(1.0) as u32;
    let height = shape.height.round().max(1.0) as u32;
    let (half_w, half_h) = (shape.width / 2.0, shape.height / 2.0);
    let radius = shape.radius.clamp(0.0, half_w.min(half_h));
    let mut raster = Raster::new(width, height);
    for y in 0..height {
        for x in 0..width {
            let px = (x as f32 + 0.5) * shape.width / width as f32 - half_w;
            let py = (y as f32 + 0.5) * shape.height / height as f32 - half_h;
            let (nx, ny, dist) = normal(px, py, half_w, half_h, radius);
            if dist < 0.0 {
                continue;
            }
            let light = -(nx + ny) * std::f32::consts::FRAC_1_SQRT_2;
            let glint = light.max(0.0).powi(3);
            let reflected = (-light).max(0.0).powi(3);
            let d = dist / rim;
            let bands = [0.18f32, 0.42, 0.66].map(|c| (-((d - c) / 0.22).powi(2)).exp());
            let peak = bands.iter().copied().fold(0.0, f32::max);
            let alpha = peak * (0.06 + 0.8 * glint + 0.35 * reflected) * 0.85;
            raster.put(
                x,
                y,
                [255, 255, 255, to_byte(alpha.clamp(0.0, 1.0) * 255.0)],
            );
        }
    }
    raster
}

/// A dome lens (`lens.ts`, `puller` without the bezel option): inside the shape each point shows content from
/// nearer the centre by the slope of a shallow circular dome there, faded in by an `erf` ramp over `depth` px from
/// the rim so the rim itself is flush with its surroundings. The pull is stored in red (across) and green (down)
/// as `0.5 - 0.5 * pull * falloff`; outside the shape, and exactly at its centre line, the map is neutral. Maps are
/// opaque. The filter `scale` is the largest shift times two: scale by the strength you want, in pixels.
pub fn lens_map(shape: Shape, dome: f32, depth: f32) -> Raster {
    let width = shape.width.round().max(1.0) as u32;
    let height = shape.height.round().max(1.0) as u32;
    let (half_w, half_h) = (shape.width / 2.0, shape.height / 2.0);
    let radius = shape.radius.clamp(0.0, half_w.min(half_h));
    let inner_w = (half_w - depth).max(0.0);
    let inner_h = (half_h - depth).max(0.0);
    let inner_r = radius.clamp(0.0, inner_w.min(inner_h));
    let ramp = if depth > 0.0 {
        1.0 / (depth * std::f32::consts::SQRT_2)
    } else {
        1e6
    };
    let sag = dome.min(half_w.min(half_h) - 1.0).max(0.01);
    let across = arc(sag, half_w);
    let down = arc(sag, half_h);
    let mut raster = Raster::new(width, height);
    for y in 0..height {
        for x in 0..width {
            let px = (x as f32 + 0.5) * shape.width / width as f32 - half_w;
            let py = (y as f32 + 0.5) * shape.height / height as f32 - half_h;
            let edge = distance(px, py, half_w, half_h, radius);
            let (mut gx, mut gy) = (NEUTRAL, NEUTRAL);
            if edge < 0.0 {
                let dx = px.signum() * slope(px.abs(), &across);
                let dy = py.signum() * slope(py.abs(), &down);
                let fall = 0.5 * (1.0 + erf(distance(px, py, inner_w, inner_h, inner_r) * ramp));
                // Smoothed over the last pixel at the edge, as glisse does.
                let inside = (-edge).clamp(0.0, 1.0);
                let fall = fall * inside;
                gx = to_byte((0.5 - 0.5 * dx.clamp(-1.0, 1.0) * fall) * 255.0);
                gy = to_byte((0.5 - 0.5 * dy.clamp(-1.0, 1.0) * fall) * 255.0);
            }
            raster.put(x, y, [gx, gy, NEUTRAL, 255]);
        }
    }
    raster
}

fn to_byte(v: f32) -> u8 {
    v.round().clamp(0.0, 255.0) as u8
}

/// The red and blue scale factors of a filter of `scale` px: the green pass keeps 1; the red and blue passes differ
/// from it by at most 3.5 percent, and by few enough pixels that the red/blue gap stays within [`MAX_FRINGE_PX`].
pub fn chroma(scale: f32) -> f32 {
    if scale <= 0.0 {
        0.0
    } else {
        (MAX_FRINGE_PX / 2.0 / scale).min(0.035)
    }
}

fn data_uri(mime: &str, bytes: &[u8]) -> String {
    format!("data:{mime};base64,{}", STANDARD.encode(bytes))
}

/// A PNG as a `data:` URI. Encode a map once per card and hand the string to the filter builders on every frame: the
/// base64 of a 640 px map is the costly part of a frame's markup.
pub fn png_uri(png: &[u8]) -> String {
    data_uri("image/png", png)
}

/// The three displacement passes and their sum, reading `SourceGraphic` through the map named `field`.
fn rays(out: &mut String, scale: f32, field: &str) {
    let f = chroma(scale);
    let channels = [
        ("r", 1.0 + f, "1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0"),
        ("g", 1.0, "0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0 1 0"),
        ("b", 1.0 - f, "0 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0 0 1 0"),
    ];
    for (name, factor, values) in channels {
        out.push_str(&format!(
            "<feDisplacementMap in=\"SourceGraphic\" in2=\"{field}\" scale=\"{:.3}\" xChannelSelector=\"R\" \
             yChannelSelector=\"G\" result=\"{name}-ray\"/>\
             <feColorMatrix in=\"{name}-ray\" type=\"matrix\" values=\"{values}\" result=\"{name}\"/>",
            scale * factor
        ));
    }
    out.push_str(
        "<feComposite in=\"r\" in2=\"g\" operator=\"arithmetic\" k2=\"1\" k3=\"1\" result=\"rg\"/>\
         <feComposite in=\"rg\" in2=\"b\" operator=\"arithmetic\" k2=\"1\" k3=\"1\" result=\"bent\"/>",
    );
}

/// A moving specular glint on the rim.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Glint {
    /// Light position in the filter's pixels.
    pub x: f32,
    pub y: f32,
    pub z: f32,
    /// How much of the glint is added, 0 to 1.
    pub strength: f32,
}

fn wrap(filter: &str) -> String {
    let svg = format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">{filter}</svg>"
    );
    format!("url(\"{}\")", data_uri("image/svg+xml", svg.as_bytes()))
}

/// The panel's glass filter as a `url(...)` token for `backdrop-filter`: the edge-band displacement of `field` at
/// `scale` pixels with its chromatic fringe, and, when `glint` is set, an `feSpecularLighting` rim glint lit
/// from `rim`'s alpha. `field_uri` and `rim_uri` ([`png_uri`]) are stretched over the whole filtered box.
pub fn panel_filter(field_uri: &str, scale: f32, rim_uri: &str, glint: Option<Glint>) -> String {
    let mut f = String::from(
        "<filter id=\"p\" x=\"0\" y=\"0\" width=\"100%\" height=\"100%\" filterUnits=\"userSpaceOnUse\" \
         primitiveUnits=\"userSpaceOnUse\" color-interpolation-filters=\"sRGB\">",
    );
    f.push_str(&format!(
        "<feImage href=\"{}\" x=\"0\" y=\"0\" width=\"100%\" height=\"100%\" preserveAspectRatio=\"none\" result=\"field\"/>",
        field_uri
    ));
    rays(&mut f, scale, "field");
    match glint {
        Some(g) if g.strength > 0.0 => {
            f.push_str(&format!(
                "<feImage href=\"{}\" x=\"0\" y=\"0\" width=\"100%\" height=\"100%\" preserveAspectRatio=\"none\" result=\"rim\"/>\
                 <feSpecularLighting in=\"rim\" surfaceScale=\"6\" specularConstant=\"{:.3}\" specularExponent=\"12\" \
                 lighting-color=\"#ffffff\" result=\"spec\"><fePointLight x=\"{:.1}\" y=\"{:.1}\" z=\"{:.1}\"/></feSpecularLighting>\
                 <feComposite in=\"spec\" in2=\"rim\" operator=\"in\" result=\"glint\"/>\
                 <feComposite in=\"bent\" in2=\"glint\" operator=\"arithmetic\" k2=\"1\" k3=\"1\"/>",
                rim_uri,
                g.strength * 5.0,
                g.x,
                g.y,
                g.z
            ));
        }
        _ => f.push_str("<feOffset in=\"bent\" dx=\"0\" dy=\"0\"/>"),
    }
    f.push_str("</filter>");
    wrap(&f)
}

/// The sweeping lens's filter as a `url(...)` token: the dome displacement of `field_uri` at `scale` pixels with its
/// chromatic fringe.
pub fn lens_filter(field_uri: &str, scale: f32) -> String {
    let mut f = String::from(
        "<filter id=\"l\" x=\"0\" y=\"0\" width=\"100%\" height=\"100%\" filterUnits=\"userSpaceOnUse\" \
         primitiveUnits=\"userSpaceOnUse\" color-interpolation-filters=\"sRGB\">",
    );
    f.push_str(&format!(
        "<feImage href=\"{}\" x=\"0\" y=\"0\" width=\"100%\" height=\"100%\" preserveAspectRatio=\"none\" result=\"field\"/>",
        field_uri
    ));
    rays(&mut f, scale, "field");
    f.push_str("<feOffset in=\"bent\" dx=\"0\" dy=\"0\"/></filter>");
    wrap(&f)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SHAPE: Shape = Shape {
        width: 200.0,
        height: 120.0,
        radius: 40.0,
    };
    const BAND: Band = Band {
        bezel: 14.0,
        thickness: 6.0,
    };

    /// The byte shift from neutral; 127.5 is the true none, so a neutral pixel reads 0 or -1.
    fn shift(raster: &Raster, x: u32, y: u32) -> (i32, i32) {
        let p = raster.at(x, y);
        let (sx, sy) = (i32::from(p[0]) - 128, i32::from(p[1]) - 128);
        (if sx == -1 { 0 } else { sx }, if sy == -1 { 0 } else { sy })
    }

    #[test]
    fn the_arc_slope_averages_one_half() {
        let a = arc(30.0, 100.0);
        let mean: f32 = (0..=200)
            .map(|i| slope(i as f32 / 200.0 * 100.0, &a))
            .sum::<f32>()
            / 201.0;
        assert!((mean - 0.5).abs() < 0.03, "{mean}");
        assert!(slope(0.0, &a).abs() < 1e-6);
        assert!(slope(80.0, &a) > slope(20.0, &a));
        assert!(slope(1000.0, &a).is_finite());
    }

    #[test]
    fn the_edge_band_map_is_symmetric_and_neutral_in_the_middle() {
        let d = edge_band_map(SHAPE, BAND);
        let (w, h) = (d.raster.width, d.raster.height);
        assert_eq!((w, h), (200, 120));
        // Zero displacement at the centre, and everywhere deeper than the band.
        assert_eq!(shift(&d.raster, 100, 60), (0, 0));
        assert_eq!(shift(&d.raster, 100, 40), (0, 0));
        // Mirror pairs shift in opposite directions by the same amount (within one byte of rounding).
        for (x, y) in [(2u32, 60u32), (3, 50), (100, 1), (60, 2), (15, 15)] {
            let (a, b) = (shift(&d.raster, x, y), shift(&d.raster, w - 1 - x, y));
            assert!((a.0 + b.0).abs() <= 1, "x {x},{y}: {a:?} {b:?}");
            assert!((a.1 - b.1).abs() <= 1, "y {x},{y}: {a:?} {b:?}");
            let (c, e) = (shift(&d.raster, x, y), shift(&d.raster, x, h - 1 - y));
            assert!((c.1 + e.1).abs() <= 1, "{x},{y}: {c:?} {e:?}");
        }
        // The rim pulls hardest, along the normal, and the scale is twice the largest pixel shift.
        let edge = shift(&d.raster, 1, 60);
        assert!(edge.0.abs() > 60, "{edge:?}");
        assert!(d.scale > 5.0 && d.scale < 30.0, "{}", d.scale);
    }

    #[test]
    fn the_lens_map_is_neutral_at_the_centre_and_flush_at_the_rim() {
        let r = lens_map(SHAPE, 60.0, 30.0);
        assert_eq!(shift(&r, 100, 60), (0, 0));
        assert_eq!(shift(&r, 0, 0), (0, 0), "outside the round corner");
        // Content is pulled from the middle: left of the centre reads from the right (a greater byte).
        let left = shift(&r, 15, 60);
        let right = shift(&r, 184, 60);
        assert!(left.0 > 10 && right.0 < -10, "{left:?} {right:?}");
        assert!(
            (left.0 + right.0).abs() <= 1,
            "symmetric: {left:?} {right:?}"
        );
        assert!(r.rgba.chunks(4).all(|p| p[3] == 255));
    }

    #[test]
    fn the_highlight_is_a_ring_lit_from_the_top_left() {
        let r = highlight_map(SHAPE, 1.0);
        assert_eq!(r.at(100, 60)[3], 0, "no light in the middle");
        let top_left = (0..20).map(|i| r.at(10 + i, 10 + i)[3]).max().unwrap_or(0);
        let bottom_right = (0..20)
            .map(|i| r.at(189 - i, 109 - i)[3])
            .max()
            .unwrap_or(0);
        assert!(top_left > bottom_right, "{top_left} vs {bottom_right}");
    }

    #[test]
    fn the_chromatic_fringe_stays_within_the_limit() {
        for scale in [1.0f32, 5.0, 12.0, 28.0, 80.0] {
            let f = chroma(scale);
            let fringe = scale * 2.0 * f;
            assert!(fringe <= MAX_FRINGE_PX + 1e-4, "{scale}: {fringe}");
        }
        assert_eq!(chroma(0.0), 0.0);
    }

    #[test]
    fn maps_encode_to_png_of_their_size() {
        let png = edge_band_map(SHAPE, BAND).raster.png().unwrap();
        let decoded = image::load_from_memory_with_format(&png, image::ImageFormat::Png).unwrap();
        assert_eq!((decoded.width(), decoded.height()), (200, 120));
    }

    #[test]
    fn maps_are_deterministic() {
        assert_eq!(edge_band_map(SHAPE, BAND), edge_band_map(SHAPE, BAND));
        assert_eq!(lens_map(SHAPE, 60.0, 30.0), lens_map(SHAPE, 60.0, 30.0));
    }
}
