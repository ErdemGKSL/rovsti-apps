//! The three card templates on one liquid-glass panel, as still PNGs and one-shot GIFs.
//!
//! # Layout
//!
//! A card is 4:5. Sizes below are for the 720 px still and scale with the width ([`Geometry::u`]). The background is
//! the palette's three-stop gradient with two soft blobs; the frosted panel sits 44 px from the sides and 72 px from
//! the top and bottom, with 44 px of padding inside. Every text block is fitted to the panel's inner width
//! ([`super::fit`]): it wraps on at most two or three lines, then shrinks to a floor, then ends in "…". The panel
//! clips its own content, so nothing can cross its edge.
//!
//! # Glass
//!
//! The panel's frosting is `backdrop-filter: blur() saturate()` on the `.glass` layer and the bending is a sibling `.optic` layer with `backdrop-filter: url(...)` only (never both in one chain), with a white (light palettes) or ink (Ink) tint, a 1.5 px
//! light rim, an inner top highlight and a soft shadow. Takumi has no shader, so the bending is an SVG filter it
//! applies to the backdrop (`backdrop-filter: url(data:image/svg+xml...)`, takumi-raster `filter.rs`,
//! `apply_svg_filter`): an edge-band `feDisplacementMap` with a chromatic fringe, and in the Birthday GIF an
//! `feSpecularLighting` rim glint ([`super::refraction`]). Takumi 2.18.2 does apply it, so no clipped background copy
//! is needed. The Birthday lens that sweeps the name is a second element after the panel with the same kind of
//! filter, so its backdrop includes the panel's text.
//!
//! # Motion
//!
//! A moving card is a one-shot GIF at 15 fps without a NETSCAPE loop block (it plays once). Its last frame is the
//! still render at the same size, held for [`HOLD_MS`]. Birthday: the panel drops in with a jelly squash, a lens
//! sweeps the name, a glint travels the panel's rim once and confetti bursts and fades. Contact: the panel settles and the QR code fades in.

use std::borrow::Cow;
use std::fmt::Write as _;
use std::path::Path;

use takumi::prelude::{
    AnimatedGifOptions, AnimationFrame, Bitmap, ImageData, ImageSource, ImageSourceInput, Node,
    OutputFormat, RenderOptions, RgbaImage, StyleSheet, Viewport,
};

use super::fit::{Fitted, TextRule, fit};
use super::model::{CardSpec, Template};
use super::motion::{Motion, Timeline};
use super::refraction::{self, Band, Glint, Shape};
use super::{CardError, fonts};

/// Size of a still card.
/// Most frames drawn at once when a card moves.
const RENDER_LANES: usize = 4;

pub const STILL: (u32, u32) = (720, 900);
/// Size of a moving card (smaller, so a GIF stays small and fast).
pub const ANIMATED: (u32, u32) = (540, 675);
/// How long the last (still) frame of a moving card is held.
pub const HOLD_MS: u32 = 600;
/// Side padding of the date pill, in design pixels.
const PILL_X: f32 = 22.0;
/// Room for the QR code's modules in the Contact card, in design pixels (the white box adds its padding; 240 px at
/// least at 720 wide).
const QR_ROOM: f32 = 264.0;
/// Largest photo file read; a bigger one draws initials.
const MAX_PHOTO_BYTES: u64 = 24 * 1024 * 1024;

/// What a finished render wrote.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rendered {
    pub width: u32,
    pub height: u32,
    pub bytes: u64,
    pub frames: u32,
}

/// The fixed boxes of a card of width `w`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Geometry {
    pub w: u32,
    pub h: u32,
    /// One design pixel (the 720 px card's pixel) in output pixels.
    pub u: f32,
}

/// An axis-aligned box: `x`, `y`, width, height.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rect {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

impl Rect {
    /// Whether `other` lies inside this box (with `slack` pixels of tolerance).
    pub fn contains(&self, other: &Rect, slack: f32) -> bool {
        other.x >= self.x - slack
            && other.y >= self.y - slack
            && other.x + other.w <= self.x + self.w + slack
            && other.y + other.h <= self.y + self.h + slack
    }
}

impl Geometry {
    pub fn new(w: u32, h: u32) -> Self {
        Self {
            w,
            h,
            u: w as f32 / STILL.0 as f32,
        }
    }

    /// The glass panel.
    pub fn panel(&self) -> Rect {
        let (mx, my) = (44.0 * self.u, 72.0 * self.u);
        Rect {
            x: mx,
            y: my,
            w: self.w as f32 - 2.0 * mx,
            h: self.h as f32 - 2.0 * my,
        }
    }

    /// Padding inside the panel.
    pub fn pad(&self) -> f32 {
        44.0 * self.u
    }

    /// The room text may use: the panel less its padding.
    pub fn inner(&self) -> Rect {
        let p = self.panel();
        let pad = self.pad();
        Rect {
            x: p.x + pad,
            y: p.y + pad,
            w: p.w - 2.0 * pad,
            h: p.h - 2.0 * pad,
        }
    }

    fn radius(&self) -> f32 {
        56.0 * self.u
    }
}

/// A text rule in design pixels (line height 1.18).
const fn rule(max_px: f32, floor_px: f32, max_lines: u32, weight: u16, wrap: bool) -> TextRule {
    TextRule {
        max_px,
        floor_px,
        max_lines,
        weight,
        line_height: 1.18,
        wrap,
    }
}

type BlockSpec = (&'static str, &'static str, TextRule);

const BIRTHDAY_BLOCKS: &[BlockSpec] = &[
    ("title", "title", rule(60.0, 34.0, 3, 700, true)),
    ("age_line", "line", rule(34.0, 24.0, 2, 600, true)),
    ("date_line", "date", rule(28.0, 22.0, 1, 600, true)),
];
const CONTACT_BLOCKS: &[BlockSpec] = &[
    ("title", "title", rule(52.0, 30.0, 2, 700, true)),
    ("numbers", "number", rule(30.0, 20.0, 1, 600, false)),
];
const NEW_NUMBER_BLOCKS: &[BlockSpec] = &[
    ("subtitle", "line", rule(36.0, 24.0, 2, 600, true)),
    ("numbers", "big", rule(64.0, 28.0, 1, 700, false)),
    ("title", "title", rule(40.0, 26.0, 2, 600, true)),
];

/// The text blocks of a template, top to bottom: key, class and rule (in design pixels).
fn blocks(template: Template) -> &'static [BlockSpec] {
    match template {
        Template::Birthday => BIRTHDAY_BLOCKS,
        Template::Contact => CONTACT_BLOCKS,
        Template::NewNumber => NEW_NUMBER_BLOCKS,
    }
}

/// One text block of a card, fitted.
#[derive(Clone, Debug)]
struct Block {
    class: &'static str,
    rule: TextRule,
    /// Width of each line's box.
    width: f32,
    fitted: Vec<Fitted>,
}

/// Panel bezel: how wide the bending band is and how thick the glass under it is, in design pixels.
const BEZEL: Band = Band {
    bezel: 30.0,
    thickness: 12.0,
};
/// Where the glint's light stands: this share of the panel height from its top, and this high above it (design px).
const GLINT_Y: f32 = 0.0;
const GLINT_Z: f32 = 20.0;
/// Width in pixels of one glisse rim pixel when the rim map is drawn for the card.
const RIM_PX: f32 = 4.0;
/// Largest shift of the sweeping lens in design pixels (the filter scale is twice it).
const LENS_SHIFT: f32 = 9.0;

/// The optics maps of a card, drawn once per size.
struct Optics {
    field_png: Vec<u8>,
    /// Filter scale of the panel's edge band at rest.
    scale: f32,
    rim_png: Vec<u8>,
    lens: Option<LensOptics>,
}

/// The sweeping lens: its box (the title block, padded) and its map.
struct LensOptics {
    png: Vec<u8>,
    rect: Rect,
}

/// A spec resolved for one size: fitted text, the photo bytes and the QR pixels. Built once per render, reused by
/// every frame.
pub struct Prepared {
    pub geometry: Geometry,
    spec: CardSpec,
    blocks: Vec<Block>,
    photo: Option<ImageSource>,
    qr: Option<(Vec<u8>, u32)>,
    optics: Optics,
}

impl std::fmt::Debug for Prepared {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Prepared")
            .field("geometry", &self.geometry)
            .field("template", &self.spec.template)
            .finish_non_exhaustive()
    }
}

/// Checks the spec: the template's required texts are there.
pub fn validate(spec: &CardSpec) -> Result<(), CardError> {
    for key in spec.template.required_texts() {
        if spec.text(key).is_none() {
            return Err(CardError::TextMissing((*key).to_owned()));
        }
    }
    Ok(())
}

impl Prepared {
    /// Fits the texts of `spec` to a `w` x `h` card and loads its photo and QR code.
    pub fn new(spec: &CardSpec, w: u32, h: u32) -> Result<Self, CardError> {
        validate(spec)?;
        let geometry = Geometry::new(w, h);
        let inner_w = geometry.inner().w;
        let u = geometry.u;
        let mut fitted_blocks = Vec::new();
        for &(key, class, ref rule) in blocks(spec.template) {
            let Some(text) = spec.text(key) else { continue };
            let rule = TextRule {
                max_px: (rule.max_px * u).round(),
                floor_px: (rule.floor_px * u).round(),
                ..*rule
            };
            // Numbers are one per line, each fitted on its own; other blocks are one paragraph.
            let lines: Vec<String> = if key == "numbers" {
                text.lines()
                    .map(str::trim)
                    .filter(|l| !l.is_empty())
                    .take(3)
                    .map(str::to_owned)
                    .collect()
            } else {
                vec![text.split_whitespace().collect::<Vec<_>>().join(" ")]
            };
            // The date sits in a pill whose padding comes off the width.
            let width = if class == "date" {
                (inner_w - 2.0 * PILL_X * u).max(1.0)
            } else {
                inner_w
            };
            let fitted = lines
                .iter()
                .map(|line| fit(line, width, &rule))
                .collect::<Result<Vec<_>, _>>()?;
            // Numbers share the smallest size, so the column reads as one.
            let fitted = if key == "numbers" {
                let smallest = fitted.iter().map(|f| f.size_px).fold(f32::MAX, f32::min);
                fitted
                    .iter()
                    .map(|f| Fitted {
                        size_px: smallest,
                        ..f.clone()
                    })
                    .collect()
            } else {
                fitted
            };
            fitted_blocks.push(Block {
                class,
                rule,
                width,
                fitted,
            });
        }
        // Decoded once here: a picture Takumi cannot read draws initials, and every frame reuses the pixels.
        let photo = spec
            .photo
            .as_deref()
            .and_then(read_photo)
            .and_then(|bytes| ImageSource::from_bytes(&bytes).ok());
        let qr = match spec.template {
            Template::Contact => spec
                .qr
                .as_deref()
                .filter(|q| !q.trim().is_empty())
                .and_then(|q| qr_pixels(q, (QR_ROOM * u) as u32)),
            _ => None,
        };
        let mut prepared = Self {
            geometry,
            spec: spec.clone(),
            blocks: fitted_blocks,
            photo,
            qr,
            optics: Optics {
                field_png: Vec::new(),
                scale: 0.0,
                rim_png: Vec::new(),
                lens: None,
            },
        };
        prepared.optics = prepared.build_optics()?;
        Ok(prepared)
    }

    /// Draws the panel's displacement and rim maps and, for Birthday, the lens map over the title block.
    fn build_optics(&self) -> Result<Optics, CardError> {
        let g = self.geometry;
        let u = g.u;
        let panel = g.panel();
        let shape = Shape {
            width: panel.w,
            height: panel.h,
            radius: g.radius(),
        };
        let band = Band {
            bezel: BEZEL.bezel * u,
            thickness: BEZEL.thickness * u,
        };
        let field = refraction::edge_band_map(shape, band);
        let lens = match self.spec.template {
            Template::Birthday => self
                .title_box()?
                .map(|rect| -> Result<LensOptics, CardError> {
                    let shape = Shape {
                        width: rect.w,
                        height: rect.h,
                        radius: rect.h / 2.0,
                    };
                    let side = rect.h.min(rect.w);
                    let map = refraction::lens_map(shape, 0.5 * side, 0.4 * side);
                    Ok(LensOptics {
                        png: map.png()?,
                        rect,
                    })
                }),
            _ => None,
        }
        .transpose()?;
        Ok(Optics {
            field_png: field.raster.png()?,
            scale: field.scale,
            rim_png: refraction::highlight_map(shape, RIM_PX).png()?,
            lens,
        })
    }

    /// The box of the first text block (the Birthday title), padded: where the lens sweeps. `None` without text.
    fn title_box(&self) -> Result<Option<Rect>, CardError> {
        let Some(title) = self.blocks.first().and_then(|b| b.fitted.first()) else {
            return Ok(None);
        };
        let words: Vec<&str> = title.text.split_whitespace().collect();
        let mut boxes = self
            .text_boxes()?
            .into_iter()
            .filter(|(text, _)| text.split_whitespace().all(|w| words.contains(&w)))
            .map(|(_, r)| r);
        let Some(first) = boxes.next() else {
            return Ok(None);
        };
        let (mut x0, mut y0, mut x1, mut y1) =
            (first.x, first.y, first.x + first.w, first.y + first.h);
        for r in boxes {
            x0 = x0.min(r.x);
            y0 = y0.min(r.y);
            x1 = x1.max(r.x + r.w);
            y1 = y1.max(r.y + r.h);
        }
        let pad = 10.0 * self.geometry.u;
        let inner = self.geometry.inner();
        // The lens is a third of the text column wide, and at least as wide as it is tall.
        let w = (inner.w * 0.36).max(y1 - y0 + 2.0 * pad);
        Ok(Some(Rect {
            x: x0.min(inner.x),
            y: y0 - pad,
            w,
            h: y1 - y0 + 2.0 * pad,
        }))
    }

    /// The text boxes the layout gives each block line, in card pixels, for the layout tests.
    pub fn text_boxes(&self) -> Result<Vec<(String, Rect)>, CardError> {
        let (node, css) = self.tree(&Motion::rest());
        let sheet = StyleSheet::parse(&css).map_err(|e| CardError::Style(e.to_string()))?;
        let options = RenderOptions::builder()
            .viewport(Viewport::new((self.geometry.w, self.geometry.h)))
            .node(node)
            .fonts(fonts::bundled()?)
            .stylesheet(sheet.into())
            .build();
        let measured = takumi::measure(options).map_err(|e| CardError::Render(e.to_string()))?;
        let mut out = Vec::new();
        collect_boxes(&measured, &mut out);
        Ok(out)
    }

    /// Draws one frame.
    pub fn bitmap(&self, motion: &Motion) -> Result<Bitmap, CardError> {
        match self.draw(motion, self.photo.as_ref()) {
            // A photo that still fails to draw falls back to initials instead of failing the card.
            Err(_) if self.photo.is_some() => self.draw(motion, None),
            other => other,
        }
    }

    fn draw(&self, motion: &Motion, photo: Option<&ImageSource>) -> Result<Bitmap, CardError> {
        let (node, css) = self.tree_with(motion, photo);
        let sheet = StyleSheet::parse(&css).map_err(|e| CardError::Style(e.to_string()))?;
        let options = RenderOptions::builder()
            .viewport(Viewport::new((self.geometry.w, self.geometry.h)))
            .node(node)
            .fonts(fonts::bundled()?)
            .stylesheet(sheet.into())
            .build();
        takumi::render(options).map_err(|e| CardError::Render(e.to_string()))
    }

    fn tree(&self, motion: &Motion) -> (Node, String) {
        self.tree_with(motion, self.photo.as_ref())
    }

    /// The node tree and its CSS for one frame.
    fn tree_with(&self, motion: &Motion, photo: Option<&ImageSource>) -> (Node, String) {
        let g = self.geometry;
        let u = g.u;
        let colors = self.spec.palette.colors();
        let dark = self.spec.palette.dark();
        let panel = g.panel();
        let inner_w = g.inner().w;
        let ink = if dark { "#f5f6fa" } else { "#14161c" };
        let soft_ink = if dark {
            "rgba(245, 246, 250, 0.78)"
        } else {
            "rgba(20, 22, 28, 0.72)"
        };
        let (tint, rim, highlight) = if dark {
            (
                "rgba(24, 26, 34, 0.46)",
                "rgba(255, 255, 255, 0.22)",
                "rgba(255, 255, 255, 0.10)",
            )
        } else {
            (
                "rgba(255, 255, 255, 0.34)",
                "rgba(255, 255, 255, 0.62)",
                "rgba(255, 255, 255, 0.38)",
            )
        };
        let avatar = match self.spec.template {
            Template::Birthday => 176.0 * u,
            _ => 112.0 * u,
        };
        // The panel's glass bends what is under it at the rim and, mid-flight, lights a glint on it. It is a layer of
        // its own over the frosted one: a `backdrop-filter` chain that blurs pads the filtered box, which would
        // shift the maps. The layer is left out while the panel is partly off the card (the first frames of the
        // drop), where Takumi clips the filtered box and the maps would no longer line up.
        let landed = panel.y + motion.panel_dy * u >= 0.0
            && panel.y + motion.panel_dy * u + panel.h <= g.h as f32;
        let panel_fx = (landed && !self.optics.field_png.is_empty()).then(|| {
            let glint = motion.glint_x.map(|x| Glint {
                x: x * panel.w,
                y: panel.h * GLINT_Y,
                z: GLINT_Z * u,
                strength: motion.glint_alpha,
            });
            refraction::panel_filter(
                &self.optics.field_png,
                self.optics.scale * motion.refract,
                &self.optics.rim_png,
                glint,
            )
        });
        let mut css = String::new();
        let _ = write!(
            css,
            ".bg {{ position: absolute; left: 0; top: 0; width: {w}px; height: {h}px; overflow: hidden; \
                background: linear-gradient(150deg, {s0} 0%, {s1} 52%, {s2} 100%); }}
             .blob1 {{ position: absolute; left: {b1x}px; top: {b1y}px; width: {b1s}px; height: {b1s}px; \
                border-radius: 50%; background: {b1}; opacity: 0.85; }}
             .blob2 {{ position: absolute; left: {b2x}px; top: {b2y}px; width: {b2s}px; height: {b2s}px; \
                border-radius: 50%; background: {b2}; opacity: 0.7; }}
             .panel {{ position: absolute; left: {px}px; top: {py}px; width: {pw}px; height: {ph}px; \
                border-radius: {r}px; overflow: hidden; border: {rimw}px solid {rim}; \
                box-shadow: 0 {sy}px {sb}px rgba(0, 0, 0, 0.16); \
                transform: translate(0px, {dy}px) scale({sx}, {sy2}); opacity: {alpha}; \
                display: flex; flex-direction: column; align-items: center; justify-content: center; \
                padding: {pad}px; gap: {gap}px; }}
             .glass {{ position: absolute; left: {px}px; top: {py}px; width: {pw}px; height: {ph}px; \
                border-radius: {r}px; overflow: hidden; background: {tint}; \
                backdrop-filter: blur({blur}px) saturate(1.6); \
                transform: translate(0px, {dy}px) scale({sx}, {sy2}); opacity: {alpha}; }}
             .optic {{ position: absolute; left: {px}px; top: {py}px; width: {pw}px; height: {ph}px; \
                border-radius: {r}px; \
                transform: translate(0px, {dy}px) scale({sx}, {sy2}); opacity: {alpha}; }}
             .shine {{ position: absolute; left: 0; top: 0; width: {pw}px; height: {shine_h}px; \
                background: linear-gradient(180deg, {highlight} 0%, rgba(255, 255, 255, 0) 100%); }}
             .avatar {{ width: {av}px; height: {av}px; border-radius: 50%; overflow: hidden; flex-shrink: 0; \
                display: flex; align-items: center; justify-content: center; \
                background: linear-gradient(150deg, {s0} 0%, {accent} 100%); \
                border: {avb}px solid rgba(255, 255, 255, 0.75); }}
             .photo {{ width: {av}px; height: {av}px; object-fit: cover; }}
             .initials {{ font-family: {family}; font-weight: 700; font-size: {ini}px; color: #ffffff; }}
             .stack {{ display: flex; flex-direction: column; align-items: center; flex-shrink: 0; \
                max-width: {inner_w}px; color: {ink}; }}
             .title, .big {{ color: {ink}; }}
             .line, .number {{ color: {soft_ink}; }}
             .date {{ color: {ink}; }}
             .pill {{ display: flex; padding: {pill_y}px {pill_x}px; border-radius: 999px; flex-shrink: 0; \
                background: {pill_bg}; max-width: {inner_w}px; }}
             .qr {{ display: flex; padding: {qr_pad}px; border-radius: {qr_r}px; background: #ffffff; \
                flex-shrink: 0; opacity: {qr_alpha}; }}",
            w = g.w,
            h = g.h,
            s0 = colors.stops[0],
            s1 = colors.stops[1],
            s2 = colors.stops[2],
            b1 = colors.blobs[0],
            b2 = colors.blobs[1],
            b1x = -60.0 * u,
            b1y = 40.0 * u,
            b1s = 360.0 * u,
            b2x = 380.0 * u,
            b2y = 560.0 * u,
            b2s = 420.0 * u,
            px = panel.x,
            py = panel.y,
            pw = panel.w,
            ph = panel.h,
            r = g.radius(),
            blur = 8.0 * u,
            rimw = 1.5 * u,
            sy = 24.0 * u,
            sb = 60.0 * u,
            dy = motion.panel_dy * u,
            sx = motion.sx,
            sy2 = motion.sy,
            alpha = motion.panel_alpha,
            pad = g.pad(),
            gap = 18.0 * u,
            shine_h = 140.0 * u,
            av = avatar,
            avb = 4.0 * u,
            accent = colors.accent,
            family = fonts::FAMILY,
            ini = (avatar * 0.38).round(),
            pill_y = 8.0 * u,
            pill_x = PILL_X * u,
            pill_bg = if dark {
                "rgba(255, 255, 255, 0.12)"
            } else {
                "rgba(255, 255, 255, 0.5)"
            },
            qr_pad = 12.0 * u,
            qr_r = 22.0 * u,
            qr_alpha = motion.qr_alpha,
        );

        if let Some(fx) = &panel_fx {
            let _ = write!(css, "\n.optic {{ backdrop-filter: {fx}; }}");
        }
        let mut panel_children = vec![Node::container(vec![]).with_class_name("shine")];
        // The avatar.
        let face = match photo {
            Some(source) => Node::image(source.clone()).with_class_name("photo"),
            None => Node::text(initials(&self.spec.initials)).with_class_name("initials"),
        };
        panel_children.push(Node::container([face]).with_class_name("avatar"));

        // The text blocks, each line its own fitted box.
        for (bi, block) in self.blocks.iter().enumerate() {
            let mut lines = Vec::new();
            for (li, fitted) in block.fitted.iter().enumerate() {
                let class = format!("t{bi}_{li}");
                // The date hugs its text inside the pill instead of filling the panel's width.
                let hug = if block.class == "date" {
                    " width: auto;"
                } else {
                    ""
                };
                let _ = write!(
                    css,
                    "\n.{class} {{ {}{hug} }}",
                    fitted.css(&block.rule, block.width)
                );
                lines.push(
                    Node::container([Node::text(fitted.text.as_str())])
                        .with_class_name(format!("{} {class}", block.class)),
                );
            }
            let stack = Node::container(lines).with_class_name("stack");
            if block.class == "date" {
                panel_children.push(Node::container([stack]).with_class_name("pill"));
            } else {
                panel_children.push(stack);
            }
        }

        if let Some((rgba, side)) = &self.qr
            && let Ok(image) = RgbaImage::new(rgba.clone(), *side, *side, false)
        {
            let _ = write!(css, "\n.qrimg {{ width: {side}px; height: {side}px; }}");
            let code = Node::image(ImageData {
                src: ImageSourceInput::Rgba(image),
                width: Some(*side as f32),
                height: Some(*side as f32),
            })
            .with_class_name("qrimg");
            panel_children.push(Node::container([code]).with_class_name("qr"));
        }

        let mut bg_children = vec![
            Node::container(vec![]).with_class_name("blob1"),
            Node::container(vec![]).with_class_name("blob2"),
            // The glass layer is a sibling under the panel, not a child: a child's backdrop filter blurs the panel's
            // own text. It carries the blur and the tint and is clipped to the panel's radius, so no blurred colour
            // leaks past the rounded corner.
            Node::container(vec![]).with_class_name("glass"),
            Node::container(vec![]).with_class_name("optic"),
            Node::container(panel_children).with_class_name("panel"),
        ];
        // The lens sweeps the name once, after the panel so its backdrop holds the panel's text.
        if let (Some(lens), Some(fx)) = (motion.lens, self.optics.lens.as_ref())
            && lens.amount > 0.01
        {
            let r = fx.rect;
            let inner = g.inner();
            let x = (inner.x - 0.3 * r.w) + lens.progress * (inner.w - 0.4 * r.w);
            let scale = 2.0 * LENS_SHIFT * u * lens.amount;
            let _ = write!(
                css,
                "\n.lens {{ position: absolute; left: {x}px; top: {y}px; width: {w}px; height: {h}px; \
                   border-radius: {rad}px; transform: translate(0px, {dy}px); \
                   border: {bw}px solid rgba(255, 255, 255, {ba}); backdrop-filter: {filter}; }}",
                y = r.y,
                w = r.w,
                h = r.h,
                rad = r.h / 2.0,
                dy = motion.panel_dy * u,
                bw = 1.2 * u,
                ba = 0.5 * lens.amount,
                filter = refraction::lens_filter(&fx.png, scale),
            );
            bg_children.push(Node::container(vec![]).with_class_name("lens"));
        }
        for (i, piece) in motion.confetti.iter().enumerate() {
            let _ = write!(
                css,
                "\n.c{i} {{ position: absolute; left: {x}px; top: {y}px; width: {pw}px; height: {ph}px; \
                   border-radius: {r}px; background: {c}; opacity: {a}; transform: rotate({rot}deg); }}",
                x = piece.x * u,
                y = piece.y * u,
                pw = piece.w * u,
                ph = piece.h * u,
                r = 2.0 * u,
                c = piece.color,
                a = piece.alpha,
                rot = piece.rotation,
            );
            bg_children.push(Node::container(vec![]).with_class_name(format!("c{i}")));
        }
        (Node::container(bg_children).with_class_name("bg"), css)
    }

    /// Renders the card to `out` (PNG for a still, GIF for a moving card). `cancelled` is asked between frames; a
    /// cancelled render leaves no file.
    pub fn write(
        &self,
        out: &Path,
        timeline: Option<&Timeline>,
        cancelled: &dyn Fn() -> bool,
    ) -> Result<Rendered, CardError> {
        let mut bytes = Vec::new();
        let frames = match timeline {
            None => {
                let bitmap = self.bitmap(&Motion::rest())?;
                takumi::write_image(&bitmap, &mut bytes, OutputFormat::Png)
                    .map_err(|e| CardError::Encode(e.to_string()))?;
                1
            }
            Some(timeline) => {
                let motions = timeline.frames();
                let mut frames = Vec::with_capacity(motions.len());
                // Frames are independent, so a chunk of them renders on scoped threads (one per core, at most
                // four); `cancelled` is asked between chunks, so a cancel waits for at most one chunk of frames.
                let lanes = std::thread::available_parallelism()
                    .map_or(1, std::num::NonZero::get)
                    .min(RENDER_LANES);
                for chunk in motions.chunks(lanes) {
                    if cancelled() {
                        return Err(CardError::Cancelled);
                    }
                    let drawn: Vec<Result<Bitmap, CardError>> = std::thread::scope(|scope| {
                        let tasks: Vec<_> = chunk
                            .iter()
                            .map(|frame| scope.spawn(|| self.bitmap(&frame.motion)))
                            .collect();
                        tasks
                            .into_iter()
                            .map(|task| {
                                task.join().unwrap_or_else(|_| {
                                    Err(CardError::Render("frame panicked".into()))
                                })
                            })
                            .collect()
                    });
                    for (frame, bitmap) in chunk.iter().zip(drawn) {
                        frames.push(AnimationFrame::new(bitmap?, frame.duration_ms));
                    }
                }
                if cancelled() {
                    return Err(CardError::Cancelled);
                }
                let count = frames.len() as u32;
                // `Some(0)` is "no NETSCAPE block": the GIF plays once.
                let options = AnimatedGifOptions::builder().loop_count(Some(0)).build();
                takumi::write_animated_gif(Cow::Owned(frames), &mut bytes, options)
                    .map_err(|e| CardError::Encode(e.to_string()))?;
                count
            }
        };
        if cancelled() {
            return Err(CardError::Cancelled);
        }
        write_atomically(out, &bytes)?;
        Ok(Rendered {
            width: self.geometry.w,
            height: self.geometry.h,
            bytes: bytes.len() as u64,
            frames,
        })
    }
}

/// Writes through a sibling `.part` file and renames it, so a reader never sees half a card.
fn write_atomically(out: &Path, bytes: &[u8]) -> Result<(), CardError> {
    let unwritable = |e: std::io::Error| CardError::OutUnwritable(e.to_string());
    if let Some(dir) = out.parent().filter(|d| !d.as_os_str().is_empty()) {
        std::fs::create_dir_all(dir).map_err(unwritable)?;
    }
    let mut part = out.as_os_str().to_owned();
    part.push(".part");
    let part = std::path::PathBuf::from(part);
    if let Err(e) = std::fs::write(&part, bytes) {
        let _ = std::fs::remove_file(&part);
        return Err(unwritable(e));
    }
    std::fs::rename(&part, out).map_err(|e| {
        let _ = std::fs::remove_file(&part);
        unwritable(e)
    })
}

/// At most two letters, upper-cased; a blank name draws a dot.
fn initials(raw: &str) -> String {
    let letters: String = raw
        .chars()
        .filter(|c| c.is_alphanumeric())
        .take(2)
        .collect();
    if letters.is_empty() {
        "•".to_owned()
    } else {
        letters.to_uppercase()
    }
}

/// The photo bytes, or `None` when the file is missing, empty or too big.
fn read_photo(path: &Path) -> Option<Vec<u8>> {
    let meta = std::fs::metadata(path).ok()?;
    if !meta.is_file() || meta.len() == 0 || meta.len() > MAX_PHOTO_BYTES {
        return None;
    }
    std::fs::read(path).ok()
}

/// A QR code of `text` as straight RGBA pixels, at most `room` pixels a side, a whole number of pixels per module
/// and no quiet zone (the white box around it is the quiet zone). `None` when the text does not fit a code.
fn qr_pixels(text: &str, room: u32) -> Option<(Vec<u8>, u32)> {
    let code =
        qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::M).ok()?;
    let side = code.width() as u32;
    let px = (room / side).max(1);
    let dim = side * px;
    let colors = code.to_colors();
    let mut rgba = vec![255u8; (dim * dim * 4) as usize];
    for (i, color) in colors.iter().enumerate() {
        if *color != qrcode::Color::Dark {
            continue;
        }
        let (mx, my) = (i as u32 % side, i as u32 / side);
        for dy in 0..px {
            for dx in 0..px {
                let p = (((my * px + dy) * dim + mx * px + dx) * 4) as usize;
                rgba[p] = 0x14;
                rgba[p + 1] = 0x16;
                rgba[p + 2] = 0x1c;
            }
        }
    }
    Some((rgba, dim))
}

/// Every text run's box, absolute in card pixels.
fn collect_boxes(node: &takumi::prelude::MeasuredNode, out: &mut Vec<(String, Rect)>) {
    let t = node.transform;
    for run in &node.runs {
        if run.text.trim().is_empty() {
            continue;
        }
        // The run is in the node's space; the transform is [a, b, c, d, e, f].
        let x = t[0] * run.x + t[2] * run.y + t[4];
        let y = t[1] * run.x + t[3] * run.y + t[5];
        out.push((
            run.text.clone(),
            Rect {
                x,
                y,
                w: run.width * t[0],
                h: run.height * t[3],
            },
        ));
    }
    for child in &node.children {
        collect_boxes(child, out);
    }
}
