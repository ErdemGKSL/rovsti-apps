// iOS-style liquid glass backdrop material for gpui-fusion.
//
// Load with `BackdropShader::wgsl(include_str!("liquid_glass.wgsl"))` and paint
// with `window.paint_backdrop_effect(PaintBackdropEffect::new(..))` from a
// Div's `on_paint_before_children` hook. The renderer splices this file into
// its backdrop wrapper, which provides:
//   struct BackdropInput { uv, position /*device px*/, size, time, pointer, pointer_active }
//   struct BackdropParams { slots: array<vec4<f32>, 8> }
//   fn sample_raw_backdrop(input, displacement_px) -> vec4<f32>
//   fn sample_blurred_backdrop(input, displacement_px) -> vec4<f32>
//
// Pipeline per fragment: rounded-rect SDF → edge-band lens refraction
// (circular-arc profile) → optional dispersion → blur/clarity mix →
// saturation & brightness → tint fill → specular rim → inner shade →
// press glow → anti-aliased coverage → dark outline straddling the edge.
//
// Uniform slots (ALL lengths in DEVICE pixels: multiply logical px by
// `window.raster_scale_factor()` on the Rust side):
//   slot0  [center.x, center.y, width, height]     unsnapped shape rectangle
//   slot1  [r_tl, r_tr, r_br, r_bl]                corner radii
//   slot2  [lens_height, lens_amount, depth, dispersion]
//   slot3  [saturation, brightness, clarity, rim_width]
//   slot4  [tint.r, tint.g, tint.b, tint.a]         straight-alpha surface fill
//   slot5  [light.x, light.y, rim_alpha, rim_falloff]
//   slot6  [inner_shade, dark_rim_alpha, press, glow_radius_scale]
//
// The dark rim straddles the edge, so dilate the effect bounds by at least
// rim_width + 1 device px (`glass::effect_bounds` does this).
//   slot7  [pointer.x, pointer.y, 0, 0]             press focus in device px, absolute

fn lg_corner_radius(p: vec2<f32>, radii: vec4<f32>, half_size: vec2<f32>) -> f32 {
    var r = select(radii.w, radii.x, p.y < 0.0);
    if (p.x >= 0.0) {
        r = select(radii.z, radii.y, p.y < 0.0);
    }
    return clamp(r, 0.0, min(half_size.x, half_size.y));
}

// Signed distance to a rounded rectangle centred at the origin. Negative inside.
fn lg_sd_round_rect(p: vec2<f32>, half_size: vec2<f32>, r: f32) -> f32 {
    let q = abs(p) - half_size + vec2<f32>(r);
    return length(max(q, vec2<f32>(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

// Analytic outward normal of the same shape, evaluated with a *larger* corner
// radius so normals turn smoothly around corners instead of snapping at the
// arc/straight junction.
fn lg_round_rect_normal(p: vec2<f32>, half_size: vec2<f32>, r: f32) -> vec2<f32> {
    let s = vec2<f32>(select(-1.0, 1.0, p.x >= 0.0), select(-1.0, 1.0, p.y >= 0.0));
    let q = abs(p) - half_size + vec2<f32>(r);
    if (q.x > 0.0 && q.y > 0.0) {
        return normalize(q) * s;
    }
    if (q.x > q.y) {
        return vec2<f32>(s.x, 0.0);
    }
    return vec2<f32>(0.0, s.y);
}

// Circular-arc lens profile: 0 at the inner edge of the band, 1 at the rim.
// Mimics the cross-section of a rounded slab of glass.
fn lg_arc(x: f32) -> f32 {
    let c = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(1.0 - c * c, 0.0));
}

fn lg_sample(input: BackdropInput, offset: vec2<f32>, clarity: f32) -> vec3<f32> {
    let blurred = sample_blurred_backdrop(input, offset).rgb;
    if (clarity <= 0.0) {
        return blurred;
    }
    return mix(blurred, sample_raw_backdrop(input, offset).rgb, clarity);
}

fn backdrop_effect(input: BackdropInput, params: BackdropParams) -> vec4<f32> {
    let shape = params.slots[0];
    let radii = params.slots[1];
    let lens = params.slots[2];
    let optics = params.slots[3];
    let tint = params.slots[4];
    let light = params.slots[5];
    let extra = params.slots[6];
    let focus = params.slots[7];

    let half_size = max(shape.zw * 0.5, vec2<f32>(1.0));
    let min_half = min(half_size.x, half_size.y);
    let p = input.position - shape.xy;
    let r = lg_corner_radius(p, radii, half_size);
    let sd = lg_sd_round_rect(p, half_size, r);
    let inside = max(-sd, 0.0);

    let n = lg_round_rect_normal(p, half_size, min(r * 1.5, min_half));

    // ---- Refraction: only a band of `lens_height` along the edge bends.
    let band = min(lens.x, min_half);
    var displacement = vec2<f32>(0.0);
    var edge = 0.0;
    if (band > 0.0 && inside < band) {
        edge = lg_arc(1.0 - inside / band);
        var dir = n;
        if (lens.z > 0.5) {
            // Depth: bend normals toward the centre for a domed look.
            let radial = p / max(length(p), 0.0001);
            dir = normalize(n + radial);
        }
        // Sample from further inside the shape → background appears pulled
        // toward the rim, like looking through a convex edge.
        displacement = -dir * lens.y * edge;
    }

    // ---- Sampling with optional chromatic dispersion, strongest in corners.
    let clarity = clamp(optics.z, 0.0, 1.0);
    var color = lg_sample(input, displacement, clarity);
    if (lens.w > 0.0 && edge > 0.0) {
        let corner = abs(p.x * p.y) / (half_size.x * half_size.y);
        let spread = displacement * lens.w * corner;
        color.r = lg_sample(input, displacement + spread, clarity).r;
        color.b = lg_sample(input, displacement - spread, clarity).b;
    }

    // ---- Vibrancy: saturation around Rec.709 luma, then brightness.
    let luma = dot(color, vec3<f32>(0.2126, 0.7152, 0.0722));
    color = mix(vec3<f32>(luma), color, optics.x) * optics.y;

    // ---- Surface fill (white lift in dark mode, light grey in light mode).
    color = mix(color, tint.rgb, clamp(tint.a, 0.0, 1.0));

    // ---- Specular rim: thin band inside the silhouette, lit by |n·L| so both
    // the lit edge and the opposite edge catch light (as on real glass).
    var l = vec2<f32>(-0.7071, -0.7071);
    if (length(light.xy) > 0.0001) {
        l = normalize(light.xy);
    }
    let facing = dot(n, l);
    let rim_w = optics.w;
    if (rim_w > 0.0) {
        let rim_mask = 1.0 - smoothstep(rim_w * 0.5, rim_w + 1.0, inside);
        let lit = pow(abs(facing), max(light.w, 0.01));
        color = color + vec3<f32>(light.z * rim_mask * lit);
    }

    // ---- Inner shade: soft darkening on the edge facing away from the light.
    if (extra.x > 0.0) {
        let shade_band = max(lens.x, 1.0) * 1.5;
        let shade = (1.0 - smoothstep(0.0, shade_band, inside)) * pow(max(-facing, 0.0), 2.0);
        color = color * (1.0 - extra.x * shade);
    }

    // ---- Press glow: additive wash + radial highlight at the finger.
    if (extra.z > 0.0) {
        let radius = min_half * 2.0 * max(extra.w, 0.01);
        let d = length(input.position - focus.xy) / radius;
        let spot = 1.0 - smoothstep(0.0, 1.0, d);
        color = color + vec3<f32>(extra.z * (0.08 + 0.15 * spot));
    }

    // Edge anti-aliasing: a linear ramp ~1.5 device px wide centred on the
    // silhouette. A 1 px smoothstep reads as stair-stepped on curves at 1x
    // scale; a linear ramp keeps the gradient even across the arc.
    var coverage = lg_edge_coverage(sd);
    color = clamp(color, vec3<f32>(0.0), vec3<f32>(1.0));

    // ---- Dark rim: a thin black outline CENTRED on the silhouette (half of it
    // outside the shape), 2 × rim width. Its strength follows two diagonal
    // axes (the light axis and the one perpendicular to it), black at both
    // ends and clear in the middle, so it swells symmetrically at all four
    // corners and fades along the straight edges. It keeps the edge readable
    // over white content, where the additive highlight vanishes.
    // It must NOT depend on n·L: that would darken only two opposite corners
    // and read as a one-sided shadow.
    if (extra.y > 0.0 && rim_w > 0.0) {
        let half_w = rim_w;
        // Both sides of the stroke get the same soft ramp as the silhouette.
        let stroke = lg_edge_coverage(abs(sd) - half_w);
        let a1 = lg_flank(p, half_size, l);
        let a2 = lg_flank(p, half_size, vec2<f32>(-l.y, l.x));
        let profile = 1.0 - (1.0 - a1) * (1.0 - a2);
        let dark = clamp(extra.y * stroke * profile, 0.0, 1.0);
        // Composite the black outline over the glass (straight alpha).
        let out_a = coverage + (1.0 - coverage) * dark;
        let rgb = color * (1.0 - dark) * coverage / max(out_a, 0.0001);
        return vec4<f32>(rgb, out_a);
    }
    return vec4<f32>(color, coverage);
}

// Fraction of a device pixel covered by the shape at signed distance `sd`
// (device px, negative inside).
const LG_AA_WIDTH: f32 = 1.5;
fn lg_edge_coverage(sd: f32) -> f32 {
    return clamp(0.5 - sd / LG_AA_WIDTH, 0.0, 1.0);
}

// 0 in the middle of the shape, 1 at the two corners that lie on `axis`.
// A linear ramp |t|, like a linear gradient black → clear → black.
fn lg_flank(p: vec2<f32>, half_size: vec2<f32>, axis: vec2<f32>) -> f32 {
    let reach = abs(axis.x) * half_size.x + abs(axis.y) * half_size.y;
    return clamp(abs(dot(p, axis)) / max(reach, 0.0001), 0.0, 1.0);
}
