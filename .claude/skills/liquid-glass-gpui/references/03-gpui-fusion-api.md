# 03 · The gpui-fusion API for glass

Checked against fork rev `cc05989` (October 2026). The fork moves fast. If a name
below fails to compile, grep the fork source and do not guess:
`grep -rn "pub fn paint_backdrop" crates/gpui/src`.

## Contents
1. Three levels of control
2. Level 1: ready-made `LiquidGlass` / `paint_liquid_glass`
3. Level 2: custom `BackdropShader` through `paint_backdrop_effect`
4. Level 3: `gpui_effects::backdrop_effect` element and plain backdrop blur
5. Paint hooks: where to call the painter
6. Other useful fork crates and modules
7. Common mistakes

---

## 1. Three levels of control

| Need | Use |
|---|---|
| "Make this panel liquid glass" quickly | `gpui_effects::LiquidGlass` or `paint_liquid_glass` |
| The exact iOS look described in this skill (edge-band arc, two-sided rim, dark rim, press glow) | Template `glass::paint_glass` (custom WGSL), **or** level 1 with `GlassSpec::to_fusion()` |
| A completely new optical effect | Your own `BackdropShader` (level 2) |
| A frosted, non-refracting sheet | `gpui_effects::FrostedGlass` or `window.paint_backdrop_blur` |

## 2. Level 1: ready-made material

```rust
use gpui_effects::{LiquidGlass, LiquidGlassAppearance, paint_liquid_glass};

// As an element (a styled Div wrapper; children paint on top, sharp):
LiquidGlass::with_appearance(LiquidGlassAppearance::clear())
    .id("play")                       // stateful interactions need an id
    .size(px(56.)).rounded_full()
    .child(icon)

// Or as a painter inside any div:
div().rounded(px(28.)).on_paint_before_children(|bounds, style, window, _| {
    let corners = style.corner_radii.to_pixels(window.rem_size());
    paint_liquid_glass(bounds, corners, LiquidGlassAppearance::regular(), window);
})
```

`LiquidGlassAppearance` fields. Lengths are in logical px; the fork scales them itself.

| Field | `regular()` | Meaning |
|---|---|---|
| `blur_radius` | 8 | backdrop blur |
| `clarity` | 0.22 | sharp-backdrop share in the centre (the edges get more) |
| `refraction` | 18 | max inward displacement |
| `thickness` | 16 | width of the curved edge band |
| `full_refraction` | false | refract the interior too (magnifier) |
| `dispersion` | 0.015 | 0..0.1 RGB split |
| `tint` | white 0.12α | wash |
| `saturation`, `brightness` | 1.04, 1.0 | colour |
| `highlight` | 0.34 | rim reflection strength |
| `edge_shadow` | 0.04 | inner shading opposite the light |
| `rim_width` | 0.8 | reflective rim |
| `edge_tint_*` | off | an outline coloured by nearby backdrop |
| `light_direction` | (-0.6, -0.8) | toward the light (top-left) |
| `compress_highlights` | false | tone-compress bright backdrops |

Presets: `regular()`, `clear()` (blur 3, clarity 0.78, for compact controls) and `dark()`.
The struct derives a chainable builder (`uic_macros::Chainable`), so you can also
use struct-update syntax: `LiquidGlassAppearance { saturation: 1.6, ..LiquidGlassAppearance::clear() }`.

To get the iOS look described in this skill on level 1, raise `saturation` to 1.5,
lower `blur_radius` to 2–3, set `clarity` to 0, set `thickness`/`refraction` to 12/24, and use the theme tint.
`GlassSpec::to_fusion()` does this mapping.

Other level-1 painters:
- `paint_deformed_liquid_glass(bounds, corners, appearance, LiquidGlassDeformation { focus, bulge, ripple, ellipticity }, window)`
  makes a localised bulge toward the finger. This is how the fork's segmented control
  makes the thumb "squish" under a press.
- `LiquidGlass::with_lens(LiquidGlassLensAppearance)` and `paint_liquid_glass_lens` give a convex
  magnifying lens with a clear centre.
- `liquid_glass_content(content, color, region_fn)` with `LiquidGlassRegion` recolours text and icons
  only where they sit under a moving glass shape (for example, selected-tab text turning
  red under the puck).

## 3. Level 2: custom backdrop shader

```rust
use gpui::{BackdropShader, EffectUniforms, PaintBackdropEffect};

static SHADER: OnceLock<BackdropShader> = OnceLock::new();
let shader = SHADER.get_or_init(|| BackdropShader::wgsl(include_str!("my_glass.wgsl"))).clone();

let s = window.raster_scale_factor();
window.paint_backdrop_effect(
    PaintBackdropEffect::new(bounds.dilate(px(1.0 / s)), blur_radius, shader)
        .uniforms(EffectUniforms::new().with_slot(0, [a, b, c, d]) /* up to slot 7 */)
        .time(seconds)                       // input.time, for animated effects
        .pointer(normalised_point, active)   // input.pointer / input.pointer_active
        .corner_radii(corners)               // clip, optional when the shader outputs coverage
        .opacity(0.0..=1.0),
);
```

The WGSL contract:
```wgsl
// provided by the wrapper (crates/gpui/src/backdrop.wgsl):
struct BackdropInput { uv: vec2<f32>, position: vec2<f32>, size: vec2<f32>,
                       time: f32, pointer: vec2<f32>, pointer_active: f32 }
struct BackdropParams { slots: array<vec4<f32>, 8> }
fn sample_raw_backdrop(input: BackdropInput, displacement_px: vec2<f32>) -> vec4<f32>
fn sample_blurred_backdrop(input: BackdropInput, displacement_px: vec2<f32>) -> vec4<f32>

// you must define:
fn backdrop_effect(input: BackdropInput, params: BackdropParams) -> vec4<f32>
```
- `position` is in device px, window space. `size` is the snapped effect size in device px.
- Output is straight-alpha RGBA. The wrapper applies the corner clip, opacity and
  premultiplication.
- `PaintBackdropEffect::blur_radius` sets how much `sample_blurred_backdrop` is blurred.
  Use 0 when you only need the raw backdrop.
- The shader id is a hash of its source. Identical source means one cached pipeline.
- To check compilation offline, use `gpui::compose_backdrop_shader_wgsl(&shader)` together with naga
  (see `templates/tests/shader_validates.rs`).

Non-backdrop effects use the same idea. `EffectShader::wgsl(..)` with
`window.paint_effect(PaintEffect::new(bounds, shader).uniforms(..))` draws a
fragment shader that does not read the scene (gradients, noise, glows).
`EffectShader::wgsl_image` / `wgsl_mask` take image inputs. Use `.with_msl` / `.with_hlsl`
to supply hand-written native shaders.

## 4. Level 3: element wrappers and plain blur

```rust
// A styled leaf element that runs a BackdropShader over its bounds:
gpui_effects::backdrop_effect(shader).uniform(0, [..]).blur_radius(px(4.)).size_full()

// Plain blur, no shader:
window.paint_backdrop_blur(PaintBackdropBlur::new(bounds, px(20.)) /* .corner_radii .opacity */);

// Frosted material:
gpui_effects::FrostedGlass::new()...      // FrostedGlassAppearance { blur_radius, saturation, brightness, tint, edge, edge_width, sheen, merge_distance }
```

## 5. Paint hooks: where to call the painter

Backdrop painters must run **during paint** and **before the children**.

1. **`div().on_paint_before_children(|bounds, style, window, cx| ...)`** (fork only). This is the
   easiest option. `style.corner_radii.to_pixels(window.rem_size())` gives the div's
   resolved radii, so the glass follows `.rounded_*()`.
2. **`canvas(prepaint, paint)`** as the *first child*, with `.absolute().inset_0()`. Its paint runs
   before the later siblings. Use this to also capture `bounds` for layout-dependent
   animation (`Rc<Cell<Option<Bounds<Pixels>>>>`).
3. **A custom `impl Element`**, which gives full control: in `paint()`, paint the glass, then call
   `child.paint(window, cx)`.

Store animation state in your view or an `Entity`. Read the current spring values
inside the hook, and call `window.request_animation_frame()` from the hook (or from
`render`) while something is still moving.

## 6. Other useful fork crates and modules

- `gpui_effects::ElasticOffset` is a 2D spring the caller drives (`spring(hz, damping)`,
  `grab`, `drag_to`, `release`, `advance(dt) -> bool`). It is good for "pull and snap back".
- `gpui_effects` animation modules: `presence` (mount/unmount transitions),
  `layout_transition` / `animated_layout` (FLIP-style layout morphs),
  `selection_indicator`, `stagger`, `collapse`, `switch`, `MotionLayer`.
  Read their examples before writing your own.
- `uic` has `GlassSegmentedControl` (`uic/src/components/glass/`). This is a full reference for
  measuring option bounds with `canvas`, springing the thumb with velocity,
  pressure deformation, the reduced-transparency fallback, and `liquid_glass_content`.
  Run it with `cargo run -p uic --example glass_segmented`.

## 7. Common mistakes

- Calling `paint_*` from `render()` panics or is ignored, because they assert paint phase. Call them only from paint hooks.
- `LiquidGlass` without `.id(..)` cannot use `on_click` (stateful interactions need an id).
- Expecting `gpui_effects` to work with upstream GPUI. It only builds against the fork's `gpui`.
- Forgetting the fallback. When `supports_backdrop_blur()` is false, the glass paints nothing and
  the text floats on nothing.
