# 02 · The liquid-glass material, step by step

The ready-to-use shader is `templates/shaders/liquid_glass.wgsl`. Its Rust
packer is `templates/src/glass.rs` (`glass_uniforms`, `paint_glass`).
`assets/expected_glass_snapshot.png` shows what it should look like: a chrome tab bar at the bottom, a
pressed dark control in the middle, and a dispersive lens on the right.

## Contents
1. What makes the look iOS-like and not just blurry
2. The pipeline
3. The math for each stage
4. Uniform slot layout
5. Tuning guide
6. Writing your own variant
7. Common mistakes

---

## 1. What makes the look iOS-like and not just blurry

Liquid glass is *not* frosted glass. Frosted glass is a heavy blur with a
translucent fill (macOS vibrancy, Windows acrylic). Liquid glass has these
properties instead:

| Property | Typical value | Why |
|---|---|---|
| Very little blur | 2–3 px for chrome and controls, 8–14 px for sheets | The background stays recognisable |
| Refraction only in an edge band | band 12–20 px, pull 24–32 px | The interior is flat and clear; only the rounded rim bends light |
| Strong saturation | ×1.5–2.0 | Colour shows through vividly ("vibrancy") |
| Light surface fill | dark mode: white 0.15α; light mode: #F5F5F7 0.40α | Dark-mode glass is a white lift, never black smoke |
| Thin specular rim lit on both sides | 0.5–0.8 px, white 0.5α, additive | Reads as a physical edge |
| Dark outline centred on the edge | 1.6 px (2 × rim), black 0.32α, swelling at **all four** corners | Keeps the edge visible over white content; reads as a crisp outline, never as a shadow |
| Dispersion | only on the pressed lens, strongest in the corners | A subtle prismatic edge |
| Soft drop shadow | dark: `0 8 24 rgba(0,0,0,.35)`; light: `0 4 16 rgba(0,0,0,.12)` | Lifts the surface off the page |

## 2. The pipeline

All of this runs per fragment, inside `backdrop_effect()`:

```
p = position − center                         (device px, unsnapped centre)
sd = SDF_roundrect(p, half, r)                 signed distance, < 0 inside
n  = normal_roundrect(p, half, min(1.5·r, minHalf))   smoothed outward normal
──────────── refraction ────────────
inside = −sd
if inside < band:
    t   = 1 − inside / band                    1 at the rim, 0 at the inner edge of the band
    k   = 1 − sqrt(1 − t²)                     circular-arc lens profile
    dir = depth ? normalize(n + p̂) : n
    disp = −dir · amount · k                   sample further inside → background is pulled outward
──────────── sampling ────────────
c = mix(blurred(disp), raw(disp), clarity)
dispersion: c.r = sample(disp + spread).r; c.b = sample(disp − spread).b
            spread = disp · dispersion · |p.x·p.y| / (hx·hy)    (strongest in the corners)
──────────── colour ────────────
c = mix(luma(c), c, saturation) · brightness   Rec.709 luma
c = mix(c, tint.rgb, tint.a)                   surface fill
──────────── light ────────────
rim  = band of width rim_w just inside the edge
lit  = |n·L|^falloff                           both the lit edge and the opposite edge glow
c   += rim_alpha · rim · lit                   additive (like BlendMode.Plus)
c   *= 1 − inner_shade · edge · max(−n·L, 0)²  inner shading opposite the light
c   += press · (0.08 + 0.15 · spot(finger))    press glow
alpha = 1 − smoothstep(−0.5, 0.5, sd)          anti-aliased coverage
stroke = |sd| < rim_w                          dark outline band, half outside the shape
dark  = dark_rim · stroke · (1 − (1−|t₁|)(1−|t₂|))   t₁,t₂ = position along L and L⊥, ±1 at corners
out   = black outline composited over the glass (straight alpha)
```

## 3. The math for each stage

### Rounded-rect SDF with per-corner radii
```wgsl
fn lg_sd_round_rect(p: vec2<f32>, half: vec2<f32>, r: f32) -> f32 {
    let q = abs(p) - half + vec2<f32>(r);
    return length(max(q, vec2<f32>(0.0))) + min(max(q.x, q.y), 0.0) - r;
}
```
Choose the radius by quadrant: top-left, top-right, bottom-right, bottom-left. This
is the same order as GPUI's `Corners`. Clamp it to `min(half.x, half.y)`.
`rounded_full()` resolves to a huge radius, so clamp first and only then use it.

**Squircles.** iOS uses continuous (G2) corners. A plain circular radius
looks slightly "boxy". There are two cheap ways to approximate a squircle:
1. Use a larger radius than the design value (about ×1.1). This is good enough for pills.
2. Use a superellipse SDF in the corner region:
   `length(pow(max(q,0), vec2(n))) ^ (1/n)` with n ≈ 2.5–3. This costs a `pow` per pixel.
Both the shader and the GPUI quad clip must use the same shape. Otherwise the
quad fallback and the glass will not line up.

### Smoothed normals
Normals are discontinuous where the straight edge meets the arc. Evaluate the
normal of a *virtual* rectangle whose corner radius is 1.5× larger
(`min(r * 1.5, minHalf)`). Normals then rotate gradually and the refraction has no
visible seams at the corners. The analytic normal is used instead of central
differences of the SDF because it is cheaper and stable inside the shape.

### Edge-band refraction
- Only `inside < lens_height` is displaced. The interior is passed through
  untouched. This flat centre is what makes it read as a *slab* of glass and
  not a fisheye magnifier.
- The profile `k = 1 − sqrt(1 − t²)` is a quarter circle. Displacement grows
  slowly at first and then steeply near the rim, which is how a rounded glass
  edge bends light.
- The displacement points *inward* (`−n`): each fragment samples the
  backdrop from further inside, so background detail looks pulled toward the rim.
- Cap the amount: `amount ≤ 0.45 · minHalf`. Beyond that, samples
  cross the centre and the image folds.
- `depth`: `normalize(n + p̂)` bends the normals toward the centre, which gives a domed
  "thick drop" look. Use it for the pressed puck and lenses, not for wide bars.

### Blur versus clarity
The renderer prepares `t_blurred_backdrop` with `blur_radius` (the
`PaintBackdropEffect` blur) before your shader runs. `clarity` mixes in
the raw (sharp) backdrop. For chrome, use blur 2 px and clarity 0. For a clear lens, use
blur 0 and clarity 1. Blur on its own makes frosted glass. Blur plus refraction plus
saturation makes liquid glass.

### Dispersion (chromatic aberration)
Sample red and blue with the displacement scaled in opposite directions. Weight
the spread by `|p.x·p.y|/(hx·hy)` so it is near zero along the straight edges and
peaks in the corners, where real glass disperses most. Values: 0 for chrome,
0.2–0.5 for the pressed lens. The cost is two extra texture samples per channel pair.

### Vibrancy
```
luma = dot(c, vec3(0.2126, 0.7152, 0.0722))   // Rec.709
c    = mix(vec3(luma), c, saturation)
```
Use 1.5 for controls and up to 2.0 for navigation chrome. For dark themes,
reduce it to about 1.7 if colours start to clip.

### Rim lighting
- The light vector `L` comes from the top-left (225° in screen space, y down). The default in
  `GlassSpec` is `light_angle = 225`.
- `|n·L|` lights the edge facing the light *and* the opposite edge, like the
  internal reflection on real glass. Use `max(n·L, 0)` if you want only one lit side.
- `rim_falloff` 1 gives a broad highlight. 3 or more concentrates it at the light angle.
- The rim must stay *inside* the silhouette. Mask it with
  `1 − smoothstep(rim_w/2, rim_w + 1, inside)`.
- Dark rim: an additive white highlight disappears over white content, so a
  thin black outline frames the shape. Build it like this:
  - It is a **stroke centred on the silhouette**, `|sd| < rim_w` (2 × rim width
    in total). Half of it lies *outside* the shape, so the effect bounds must be
    dilated by `rim_width + 1 device px` (`glass::effect_bounds`).
  - Its strength comes from **two diagonal ramps**: `|t|` along L and along L⊥,
    each 1 at its two corners and 0 in the middle, combined as
    `1 − (1−a₁)(1−a₂)`. It swells at all four corners and fades mid-edge.
  - It stays on in both themes at 0.32α. Over dark backdrops it is barely
    visible, which is intended.
  - **Do not weight it by `1 − |n·L|`.** That darkens only the two corners
    perpendicular to the light, and the result reads as a soft one-sided
    shadow inside the glass (top-right/bottom-left for a top-left light).

### Press glow (InteractiveHighlight)
While pressed, add a uniform wash (`0.08·press`) plus a radial spot at the
finger (`0.15·press`, radius = 1.5 × the min dimension). Drive `press` with a
bouncy spring (`motion_tokens::glow()`, ζ 0.5, k 300) so the glow "breathes".
Send the finger position in device px through slot 7.

## 4. Uniform slot layout (template shader)

All lengths are **device px**. `glass_uniforms` multiplies them by `raster_scale_factor`.

| Slot | x | y | z | w |
|---|---|---|---|---|
| 0 | centre.x | centre.y | width | height |
| 1 | r top-left | r top-right | r bottom-right | r bottom-left |
| 2 | lens_height | lens_amount | depth (0/1) | dispersion |
| 3 | saturation | brightness | clarity | rim_width |
| 4 | tint.r | tint.g | tint.b | tint.a |
| 5 | light.x | light.y | rim_alpha | rim_falloff |
| 6 | inner_shade | dark_rim alpha | press | glow radius scale |
| 7 | focus.x | focus.y | — | — |

`EFFECT_UNIFORM_SLOTS = 8`. You cannot add a ninth. To add a feature, pack
values into the free `.zw` of slot 7, or encode booleans as signs.

## 5. Tuning guide

| Symptom | Fix |
|---|---|
| Looks like a plain frosted panel | Lower blur to 2–3 px, raise `lens_amount`, raise saturation |
| The edge looks like a fisheye or the image folds | Lower `lens_amount` below 0.45·minHalf, or widen `lens_height` |
| Seams at the corners | Make sure the normal radius is 1.5× (`min(r*1.5, minHalf)`) |
| The edge vanishes over white content | Raise `dark_rim` (0.32 default, up to about 0.5) |
| A soft "shadow" sits inside two opposite corners | The dark rim was weighted by n·L. Make it a centred, symmetric outline (§3 Rim lighting) |
| The outer half of the dark outline is cut off | Effect bounds are not dilated by `rim_width + 1px`; use `effect_bounds` |
| Text on the glass is hard to read | Use the sheet preset (more blur), raise `tint.a`, or put a 0.6α fill behind the text |
| The glass is too strong on HiDPI | Lengths were sent in logical px. Multiply them by `raster_scale_factor` |
| The surface shimmers while moving | You used `uv*size`. Use an unsnapped centre with `position` |
| Rainbow edges everywhere | Set dispersion to 0 except on the pressed lens |

## 6. Writing your own variant

1. Copy `liquid_glass.wgsl`. Keep the function name `backdrop_effect(input: BackdropInput, params: BackdropParams) -> vec4<f32>`.
2. You may define helper functions. Prefix them (e.g. `lg_`) so they do not clash with the wrapper's
   `backdrop_*` helpers.
3. Only `sample_raw_backdrop(input, disp)` and `sample_blurred_backdrop(input, disp)`
   read the scene. There is no other texture access.
4. Validate with `cargo test --test shader_validates`, then look at the pixels with
   `cargo test --test glass_snapshot -- --ignored --nocapture`, which writes `target/glass_snapshot.png`.
5. Return **straight** (non-premultiplied) colour with alpha as coverage. The wrapper
   multiplies by opacity and premultiplies when needed.

Other effects in the same family:
- **Variable or progressive blur** (top fade under a nav bar): use
  `gpui_effects::progressive_blur(content).top(px(64.))` on the fork. Do not write your own.
- **Frosted (non-refracting) material**: use `gpui_effects::FrostedGlass` with
  `FrostedGlassAppearance { blur_radius, saturation, tint, .. }`, or
  `window.paint_backdrop_blur(PaintBackdropBlur ..)` followed by a tint quad.
- **Merged or morphing shapes** (two pills joining): gpui-fusion's `FrostedGlass::merge`
  supports this for frosted glass. For refraction, compute `smin(sdA, sdB, k)` in a
  custom shader with both rectangles in the slots.

## 7. Common mistakes

- Returning premultiplied colour. The edges get dark halos.
- Using `discard` for coverage. The edges get jaggies. Return alpha instead.
- Painting the glass bounds without dilating them. The anti-aliased edge is clipped by the
  snapped capture rectangle.
- Calling `BackdropShader::wgsl(...)` every frame. It hashes the whole source each time. Cache it in a `OnceLock`
  (`glass_shader()`).
- Adding a big `blur` "to make it look glassy". This turns it into frosted glass and costs more.
