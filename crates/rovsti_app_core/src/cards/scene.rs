//! The card scene: one node tree and one stylesheet for a template at a given [`Pose`].
//!
//! A static card is the scene at rest; every animation frame is the same scene with a different pose, so the last
//! frame of an animation and the static render cannot drift apart. All sizes here are in reference pixels of a card
//! 720 wide (`REF_W` x `REF_H`); [`render`] scales them to the requested width.

use base64::Engine as _;
use takumi::prelude::{Bitmap, Node, RenderOptions, StyleSheet, Viewport};

use super::{CardError, fonts, refraction};

/// Reference card width in pixels. Every number in this file is in these units.
pub const REF_W: f32 = 720.0;
/// Reference card height (4:5).
pub const REF_H: f32 = 900.0;

/// The cards that animate.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Template {
    /// A birthday wish: name, age line and date line on a frosted panel with confetti.
    Birthday,
    /// A contact card: name, number and a QR code on a frosted panel.
    Contact,
}

/// The words on a card, already in the user's language (Kotlin resolves them from the string resources).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct CardContent {
    /// Small line above the name (birthday) or empty.
    pub kicker: String,
    /// The person's name.
    pub name: String,
    /// Age line (birthday) or number (contact).
    pub line1: String,
    /// Date line (birthday) or the caption under the QR (contact).
    pub line2: String,
    /// What the QR code encodes (contact only).
    pub qr: Option<String>,
}

/// One confetti piece, in reference pixels and degrees.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Piece {
    /// Centre.
    pub x: f32,
    /// Centre.
    pub y: f32,
    /// Rotation in degrees.
    pub rot: f32,
    /// Size.
    pub w: f32,
    /// Size.
    pub h: f32,
    /// Index into [`CONFETTI`].
    pub color: usize,
    /// Opacity.
    pub alpha: f32,
}

/// Confetti colours: sunrise yellow, coral, sky, mint, violet, white.
pub const CONFETTI: [&str; 6] = [
    "#ffd24a", "#ff6f61", "#4cc3ff", "#5be3a4", "#a78bfa", "#ffffff",
];

/// Everything that changes from frame to frame.
#[derive(Clone, Debug, PartialEq)]
pub struct Pose {
    /// Panel offset from rest, reference px (negative is up).
    pub panel_dy: f32,
    /// Panel scale across.
    pub panel_sx: f32,
    /// Panel scale down. `sx * sy` is 1 for the jelly squash, so the area is kept.
    pub panel_sy: f32,
    /// How far the glass bends what is behind it, 0 at rest, 1 at the hardest squash. Drives the blur and the
    /// saturation pull of the panel and the stretch of the fringe at its rim.
    pub refraction: f32,
    /// The lens over the name: `(position 0..1 across the panel, strength 0..1)`.
    pub lens: Option<(f32, f32)>,
    /// The glint on the rim: `(progress 0..1 around the rim, alpha)`.
    pub glint: Option<(f32, f32)>,
    /// Confetti.
    pub confetti: Vec<Piece>,
    /// Background drift, 0 at rest.
    pub drift: f32,
    /// QR opacity (contact).
    pub qr_alpha: f32,
}

impl Pose {
    /// A pose with nothing moving and no confetti.
    pub fn still() -> Self {
        Self {
            panel_dy: 0.0,
            panel_sx: 1.0,
            panel_sy: 1.0,
            refraction: 0.0,
            lens: None,
            glint: None,
            confetti: Vec::new(),
            drift: 0.0,
            qr_alpha: 1.0,
        }
    }
}

/// The panel rectangle at rest: `(left, top, width, height, radius)`.
pub fn panel_rect(template: Template) -> (f32, f32, f32, f32, f32) {
    match template {
        Template::Birthday => (72.0, 270.0, 576.0, 400.0, 64.0),
        Template::Contact => (72.0, 110.0, 576.0, 680.0, 64.0),
    }
}

/// A point on the rim of the rounded rectangle `(x, y, w, h, r)`, `p` in `0..1` clockwise from the end of the top-left
/// corner.
pub fn rim_point(rect: (f32, f32, f32, f32, f32), p: f32) -> (f32, f32) {
    let (x, y, w, h, r) = rect;
    let arc = std::f32::consts::FRAC_PI_2 * r;
    let (top, side) = (w - 2.0 * r, h - 2.0 * r);
    let total = 2.0 * top + 2.0 * side + 4.0 * arc;
    let mut d = p.rem_euclid(1.0) * total;
    let corner = |cx: f32, cy: f32, start: f32, along: f32| {
        let a = start + along / r;
        (cx + r * a.cos(), cy + r * a.sin())
    };
    let pi = std::f32::consts::PI;
    if d < top {
        return (x + r + d, y);
    }
    d -= top;
    if d < arc {
        return corner(x + w - r, y + r, -pi / 2.0, d);
    }
    d -= arc;
    if d < side {
        return (x + w, y + r + d);
    }
    d -= side;
    if d < arc {
        return corner(x + w - r, y + h - r, 0.0, d);
    }
    d -= arc;
    if d < top {
        return (x + w - r - d, y + h);
    }
    d -= top;
    if d < arc {
        return corner(x + r, y + h - r, pi / 2.0, d);
    }
    d -= arc;
    if d < side {
        return (x, y + h - r - d);
    }
    d -= side;
    corner(x + r, y + r, pi, d)
}

fn f(v: f32, k: f32) -> String {
    format!("{:.2}", v * k)
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// A QR code as an SVG data URI: dark modules, no quiet zone (the tile around it is the quiet zone).
fn qr_data_uri(text: &str) -> Result<String, CardError> {
    let code = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::M)
        .map_err(|e| CardError::Render(format!("qr: {e}")))?;
    let n = code.width();
    let mut path = String::new();
    for (i, c) in code.to_colors().iter().enumerate() {
        if *c == qrcode::Color::Dark {
            path.push_str(&format!("M{} {}h1v1h-1z", i % n, i / n));
        }
    }
    let svg = format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 {n} {n}\" shape-rendering=\"crispEdges\">\
         <path fill=\"#101828\" d=\"{path}\"/></svg>"
    );
    Ok(format!(
        "data:image/svg+xml;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(svg)
    ))
}

/// Font size for a name, shrinking for long ones so it stays on one line.
fn name_size(name: &str, base: f32) -> f32 {
    let n = name.chars().count() as f32;
    (base - (n - 8.0).max(0.0) * 4.0).max(base * 0.5)
}

/// The tree and stylesheet for `template` with `content` at `pose`, scaled by `k` (render width / `REF_W`).
pub fn build(
    template: Template,
    content: &CardContent,
    pose: &Pose,
    k: f32,
) -> Result<(Node, String), CardError> {
    let rect = panel_rect(template);
    let (px, py, pw, ph, pr) = rect;
    let drift = pose.drift;
    let refr = pose.refraction.clamp(0.0, 1.5);
    let blur = 22.0 + 10.0 * refr;
    let sat = 1.7 + 0.5 * refr;
    let mut css = format!(
        ".bg {{ position: absolute; left: 0; top: 0; width: {w}px; height: {h}px; overflow: hidden; \
            background: linear-gradient({ang}deg, #ffb48a 0%, #f47fb6 52%, #7b7df2 100%); }}
         .blob1 {{ position: absolute; left: {b1x}px; top: {b1y}px; width: {b1s}px; height: {b1s}px; \
            border-radius: 50%; background: #ffe08a; filter: blur({bb}px); opacity: 0.85; }}
         .blob2 {{ position: absolute; left: {b2x}px; top: {b2y}px; width: {b2s}px; height: {b2s}px; \
            border-radius: 50%; background: #5f8cff; filter: blur({bb}px); opacity: 0.8; }}
         .panel {{ position: absolute; left: {px}px; top: {py}px; width: {pw}px; height: {ph}px; \
            border-radius: {pr}px; overflow: hidden; background: rgba(255, 255, 255, 0.30); \
            backdrop-filter: blur({blur}px) saturate({sat}); border: {bw}px solid rgba(255, 255, 255, 0.60); \
            box-shadow: 0 {sy}px {sb}px rgba(40, 20, 80, 0.22); \
            transform-origin: 50% 100%; transform: translate(0px, {dy}px) scale({sx}, {sy2}); }}
         .row {{ position: absolute; left: 0; width: {pw}px; display: flex; align-items: center; \
            justify-content: center; text-align: center; color: #1b1740; font-family: Inter; }}
         .kicker {{ top: {kt}px; height: {kh}px; font-weight: 600; font-size: {kf}px; opacity: 0.72; }}
         .name {{ top: {nt}px; height: {nh}px; font-weight: 700; font-size: {nf}px; }}
         .l1 {{ top: {l1t}px; height: {l1h}px; font-weight: 600; font-size: {l1f}px; }}
         .l2 {{ top: {l2t}px; height: {l2h}px; font-weight: 500; font-size: {l2f}px; opacity: 0.72; }}",
        w = f(REF_W, k),
        h = f(REF_H, k),
        ang = f(150.0 + 12.0 * drift, 1.0),
        b1x = f(-90.0 + 70.0 * drift, k),
        b1y = f(60.0 - 30.0 * drift, k),
        b1s = f(420.0, k),
        b2x = f(380.0 - 60.0 * drift, k),
        b2y = f(560.0 + 40.0 * drift, k),
        b2s = f(440.0, k),
        bb = f(70.0, k),
        px = f(px, k),
        py = f(py, k),
        pw = f(pw, k),
        ph = f(ph, k),
        pr = f(pr, k),
        blur = f(blur, k),
        sat = f(sat, 1.0),
        bw = f(2.0, k),
        sy = f(18.0, k),
        sb = f(48.0, k),
        dy = f(pose.panel_dy, k),
        sx = f(pose.panel_sx, 1.0),
        sy2 = f(pose.panel_sy, 1.0),
        kt = f(0.0, k),
        kh = f(0.0, k),
        kf = f(28.0, k),
        nt = f(0.0, k),
        nh = f(0.0, k),
        nf = f(name_size(&content.name, 88.0), k),
        l1t = f(0.0, k),
        l1h = f(0.0, k),
        l1f = f(34.0, k),
        l2t = f(0.0, k),
        l2h = f(0.0, k),
        l2f = f(28.0, k),
    );
    // Row positions differ per template; later rules win over the placeholders above.
    let rows: [(&str, f32, f32); 4] = match template {
        Template::Birthday => [
            ("kicker", 66.0, 40.0),
            ("name", 124.0, 120.0),
            ("l1", 256.0, 48.0),
            ("l2", 312.0, 40.0),
        ],
        Template::Contact => [
            ("kicker", 40.0, 0.0),
            ("name", 56.0, 80.0),
            ("l1", 140.0, 44.0),
            ("l2", 612.0, 40.0),
        ],
    };
    for (class, top, height) in rows {
        css.push_str(&format!(
            "\n.panel .{class}.{class} {{ top: {}px; height: {}px; }}",
            f(top, k),
            f(height, k)
        ));
    }
    css.push_str(&format!(
        "\n.glint {{ position: absolute; border-radius: 50%; \
            background: radial-gradient(circle, rgba(255,255,255,0.95) 0%, rgba(255,255,255,0.55) 18%, rgba(255,255,255,0.14) 50%, rgba(255,255,255,0.0) 100%); }}
         .piece {{ position: absolute; border-radius: {cr}px; }}
         .qrtile {{ position: absolute; left: {qx}px; top: {qy}px; width: {qs}px; height: {qs}px; \
            border-radius: {qr}px; background: #ffffff; display: flex; align-items: center; justify-content: center; }}
         .qr {{ width: {qi}px; height: {qi}px; }}",
        cr = f(3.0, k),
        qx = f((pw - 372.0) / 2.0, k),
        qy = f(214.0, k),
        qs = f(372.0, k),
        qr = f(44.0, k),
        qi = f(316.0, k),
    ));
    let mut panel_children: Vec<Node> = Vec::new();
    if !content.kicker.is_empty() {
        panel_children.push(
            Node::container([Node::text(content.kicker.clone())]).with_class_name("row kicker"),
        );
    }
    panel_children
        .push(Node::container([Node::text(content.name.clone())]).with_class_name("row name"));
    panel_children
        .push(Node::container([Node::text(content.line1.clone())]).with_class_name("row l1"));
    panel_children
        .push(Node::container([Node::text(content.line2.clone())]).with_class_name("row l2"));

    if let (Template::Contact, Some(text)) = (template, &content.qr) {
        let tile = Node::container([Node::image(qr_data_uri(text)?).with_class_name("qr")])
            .with_class_name("qrtile");
        css.push_str(&format!(
            "\n.qrtile {{ opacity: {}; }}",
            f(pose.qr_alpha, 1.0)
        ));
        panel_children.push(tile);
    }

    let mut bg_children = vec![
        Node::container(vec![]).with_class_name("blob1"),
        Node::container(vec![]).with_class_name("blob2"),
        Node::container(panel_children).with_class_name("panel"),
    ];

    if let (Template::Birthday, Some((pos, strength))) = (template, pose.lens) {
        // The lens is a sibling of the panel, not a child: Takumi bends nothing for a backdrop filter nested inside
        // another backdrop filter, and a sibling painted after the panel sees the panel and its words.
        let (lw, lh) = (150.0, 132.0);
        let name_mid = 124.0 + 60.0;
        let in_panel = lerp(14.0, pw - lw - 14.0, pos);
        let (bottom_x, bottom_y) = (px + pw / 2.0, py + ph);
        let cx = px + in_panel + lw / 2.0;
        let cy = py + name_mid;
        let cx = bottom_x + (cx - bottom_x) * pose.panel_sx;
        let cy = bottom_y + (cy - bottom_y) * pose.panel_sy + pose.panel_dy;
        // Up to 44 px of displacement range at full strength, a little more while the panel is squashed.
        let (map_w, map_h) = ((lw * k).round() as u32, (lh * k).round() as u32);
        let map = refraction::lens_map_png(map_w, map_h)?;
        let filter = refraction::lens_filter_url(
            &map,
            map_w,
            map_h,
            44.0 * k * strength * (1.0 + 0.5 * refr),
        );
        css.push_str(&format!(
            "\n.lens {{ position: absolute; left: {l}px; top: {t}px; width: {w}px; height: {h}px; border-radius: 50%; \
                border: {lb}px solid rgba(255, 255, 255, {ba}); background: rgba(255, 255, 255, {bg}); \
                backdrop-filter: {filter} saturate(1.35); \
                box-shadow: 0 {ly}px {lsb}px rgba(40, 20, 80, {sa}); }}",
            l = f(cx - lw / 2.0, k),
            t = f(cy - lh / 2.0, k),
            w = f(lw, k),
            h = f(lh, k),
            lb = f(1.5, k),
            ba = f(0.7 * strength, 1.0),
            bg = f(0.10 * strength, 1.0),
            ly = f(6.0, k),
            lsb = f(18.0, k),
            sa = f(0.18 * strength, 1.0),
        ));
        bg_children.push(Node::container(vec![]).with_class_name("lens"));
    }

    if let Some((p, alpha)) = pose.glint {
        // The glint rides the panel's rim; the rim moves with the panel, so scale the point the same way.
        let (gx, gy) = rim_point(rect, p);
        let (bottom_x, bottom_y) = (px + pw / 2.0, py + ph);
        let sx = bottom_x + (gx - bottom_x) * pose.panel_sx;
        let sy = bottom_y + (gy - bottom_y) * pose.panel_sy + pose.panel_dy;
        let size = 96.0;
        css.push_str(&format!(
            "\n.glint1 {{ left: {}px; top: {}px; width: {}px; height: {}px; opacity: {}; }}",
            f(sx - size / 2.0, k),
            f(sy - size / 2.0, k),
            f(size, k),
            f(size, k),
            f(alpha, 1.0),
        ));
        bg_children.push(Node::container(vec![]).with_class_name("glint glint1"));
    }

    for (i, piece) in pose.confetti.iter().enumerate() {
        css.push_str(&format!(
            "\n.c{i} {{ left: {}px; top: {}px; width: {}px; height: {}px; background: {}; opacity: {}; \
                transform: rotate({}deg); }}",
            f(piece.x - piece.w / 2.0, k),
            f(piece.y - piece.h / 2.0, k),
            f(piece.w, k),
            f(piece.h, k),
            CONFETTI[piece.color % CONFETTI.len()],
            f(piece.alpha, 1.0),
            f(piece.rot, 1.0),
        ));
        bg_children.push(Node::container(vec![]).with_class_name(format!("piece c{i}")));
    }

    Ok((Node::container(bg_children).with_class_name("bg"), css))
}

/// Renders `template` at `pose`, `width` pixels wide (the height keeps 4:5).
pub fn render(
    template: Template,
    content: &CardContent,
    pose: &Pose,
    width: u32,
) -> Result<Bitmap, CardError> {
    let height = width * 5 / 4;
    if !(16..=4096).contains(&width) || !(16..=4096).contains(&height) {
        return Err(CardError::Size(width, height));
    }
    let k = width as f32 / REF_W;
    let (node, css) = build(template, content, pose, k)?;
    let sheet = StyleSheet::parse(&css).map_err(|e| CardError::Style(e.to_string()))?;
    let options = RenderOptions::builder()
        .viewport(Viewport::new((width, height)))
        .node(node)
        .fonts(fonts::bundled()?)
        .stylesheet(sheet.into())
        .build();
    takumi::render(options).map_err(|e| CardError::Render(e.to_string()))
}
