//! Share cards (X.2): a platform-free renderer that turns a node tree plus CSS into a PNG with Takumi.
//!
//! This module is the only code that names Takumi types. Step 1 holds the spike: bundled fonts and `render_probe`,
//! which draws a gradient, a frosted panel (blur and saturate only) and a Turkish line.
//!
//! # Takumi 2.18.2 call sites (read in `takumi-2.18.2/src/lib.rs`, `takumi-core-0.34.0` and `takumi-raster-0.5.11`)
//!
//! - Fonts: `Fonts::default()` loads no system font; `Fonts::register(FontResource::new(FontSource::from_static(bytes)))`
//!   takes `include_bytes!` data (`takumi-core/src/resources/font.rs`, `FontSource::from_static`). One `Fonts` is
//!   shared by all renders.
//! - Tree: `Node::container(children)`, `Node::text(string)`, `Node::image(data)`, each with
//!   `.with_class_name("x")`. The crate is built without `from-html`, so trees are assembled in Rust
//!   (`takumi-core/src/layout/node/mod.rs`).
//! - Style: a CSS string through `StyleSheet::parse(&css)` handed to the render options as `.stylesheet(sheet.into())`.
//!   Class selectors work (`takumi-2.18.2/tests/filter_clips.rs`), as does `backdrop-filter`
//!   (`takumi-core/src/style/stylesheets.rs`, `backdrop_filter: Filters`).
//! - Render: `RenderOptions::builder().viewport(Viewport::new((w, h))).node(node).fonts(&fonts).stylesheet(sheet).build()`
//!   then `takumi::render(options) -> Result<Bitmap>`; `Bitmap` gives `width()`, `height()` and RGBA `as_raw()`
//!   (`takumi-raster/src/render.rs`, `write.rs`).
//! - Encode: `takumi::write_image(&bitmap, &mut writer, OutputFormat::Png)`; other formats are
//!   `OutputFormat::Jpeg { quality }` and `WebP { quality }`.
//! - Animation (later steps): `render_animation`, `write_animation`, `write_animated_gif`, `write_animated_png`,
//!   `write_animated_webp` with `AnimatedGifOptions`, `AnimatedPngOptions` and `AnimatedWebpOptions`; the loop
//!   setting is read in Step 4.

pub mod fonts;

use takumi::prelude::{Bitmap, Node, OutputFormat, RenderOptions, StyleSheet, Viewport};

/// Why a card could not be rendered.
#[derive(Debug, thiserror::Error)]
pub enum CardError {
    /// A bundled font failed to register.
    #[error("font: {0}")]
    Font(String),
    /// The CSS did not parse.
    #[error("style: {0}")]
    Style(String),
    /// Takumi failed to lay out or draw the tree.
    #[error("render: {0}")]
    Render(String),
    /// The encoder failed.
    #[error("encode: {0}")]
    Encode(String),
    /// A size outside what a card may be.
    #[error("size {0}x{1} is outside 16..=4096")]
    Size(u32, u32),
}

/// Smallest and largest side of a probe or card, in pixels.
const SIDE: std::ops::RangeInclusive<u32> = 16..=4096;

/// The Turkish line the probe draws; covers ğ, ü, İ and ı.
pub const PROBE_LINE: &str = "Doğum günün kutlu olsun, İlkay!";

/// Pixel box of the probe text inside a `w` x `h` render: `(x, y, width, height)`. Tests compare pixels inside it.
pub fn probe_text_box(w: u32, h: u32) -> (u32, u32, u32, u32) {
    let panel = probe_panel(w, h);
    (panel.0 + 16, panel.1 + 16, panel.2 - 32, panel.3 - 32)
}

/// The frosted panel: centred, 80 percent of the width, 40 percent of the height.
fn probe_panel(w: u32, h: u32) -> (u32, u32, u32, u32) {
    let (pw, ph) = (w * 4 / 5, h * 2 / 5);
    ((w - pw) / 2, (h - ph) / 2, pw, ph)
}

/// The probe tree and its CSS for `line` in a `w` x `h` viewport.
fn probe_tree(line: &str, w: u32, h: u32) -> (Node, String) {
    let (px, py, pw, ph) = probe_panel(w, h);
    let size = (ph / 5).max(8);
    let css = format!(
        ".bg {{ position: absolute; left: 0; top: 0; width: {w}px; height: {h}px; \
            background: linear-gradient(135deg, #ff9a6b 0%, #c86dd7 50%, #3f8efc 100%); }}
         .blob {{ position: absolute; left: {bx}px; top: {by}px; width: {bs}px; height: {bs}px; \
            border-radius: 50%; background: #fff3a8; }}
         .panel {{ position: absolute; left: {px}px; top: {py}px; width: {pw}px; height: {ph}px; \
            border-radius: 28px; background: rgba(255, 255, 255, 0.28); \
            backdrop-filter: blur(24px) saturate(1.8); \
            border: 1px solid rgba(255, 255, 255, 0.55); }}
         .line {{ position: absolute; left: 16px; top: 16px; width: {tw}px; height: {th}px; \
            display: flex; align-items: center; justify-content: center; text-align: center; \
            font-family: Inter; font-weight: 600; font-size: {size}px; color: #101828; }}",
        bx = w / 10,
        by = h / 8,
        bs = h / 3,
        tw = pw - 32,
        th = ph - 32,
    );
    let tree = Node::container([
        Node::container(vec![]).with_class_name("blob"),
        Node::container([Node::container([Node::text(line)]).with_class_name("line")])
            .with_class_name("panel"),
    ])
    .with_class_name("bg");
    (tree, css)
}

/// Renders the probe to a bitmap (RGBA, `w` x `h`).
pub fn render_probe_bitmap(line: &str, w: u32, h: u32) -> Result<Bitmap, CardError> {
    if !SIDE.contains(&w) || !SIDE.contains(&h) {
        return Err(CardError::Size(w, h));
    }
    let fonts = fonts::bundled()?;
    let (node, css) = probe_tree(line, w, h);
    let sheet = StyleSheet::parse(&css).map_err(|e| CardError::Style(e.to_string()))?;
    let options = RenderOptions::builder()
        .viewport(Viewport::new((w, h)))
        .node(node)
        .fonts(fonts)
        .stylesheet(sheet.into())
        .build();
    takumi::render(options).map_err(|e| CardError::Render(e.to_string()))
}

/// Renders the probe (gradient, frosted panel, `line`) and encodes it as PNG.
pub fn render_probe(line: &str, w: u32, h: u32) -> Result<Vec<u8>, CardError> {
    let bitmap = render_probe_bitmap(line, w, h)?;
    let mut png = Vec::new();
    takumi::write_image(&bitmap, &mut png, OutputFormat::Png)
        .map_err(|e| CardError::Encode(e.to_string()))?;
    Ok(png)
}

#[cfg(test)]
mod tests;
