Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang. Full licence text: `glisse-LICENSE.txt` next to this file.

# Glass: material recipe, AGSL lens and rim, tokens, fallbacks

> **Licence.** Convx's glass glue (`GlassEffect.kt`, `GlassPool.kt`, freeze files, tint tokens) is GPL-3.0 and its
> engine is a *modified* vendored copy of Kyant0/backdrop (Apache-2.0) (`app/src/main/kotlin/com/convx/music/ui/component/backdrop/internal/Shaders.kt:1-24`).
> Neither may be copied. The capture engine below is `ORIGINAL`. The lens maths, rim light and tokens are
> `PORTED FROM GLISSE` (MIT) and keep the header. glisse credits two sources of its maths in comments ("Aave's" web glass,
> `src/internal/lens.ts:3-6,95`; kube.io's CSS/SVG liquid glass, `src/internal/refraction.ts:333-338`); keep those credits in
> the shader header too. Alternative to writing the engine: depend on upstream `io.github.kyant0:backdrop` (Apache-2.0;
> verify licence and Compose compatibility at the pinned version; Convx vendored it because the AAR broke against newer
> Compose, `Shaders.kt:5-7`).

Prefixes: Convx `C/` = `app/src/main/kotlin/com/convx/music/ui/component/`, `B/` = `C/backdrop/`, `T/` = `.../ui/theme/`;
glisse `G/` = `src/`. Deeper notes: `_bmad-output/kotlin-ui/research/convx-glass.md`, `glisse.md` sections 4-5.

---

## 1. The model in one paragraph

A glass surface is **not** a full-screen shader. It is four things drawn by one node: (1) a *replay* of the content
behind it, taken from one recorded source layer and translated so the right region lines up (`B/backdrops/LayerBackdrop.kt:152-192`);
(2) an effect chain on that replay: saturation (free, as a colour filter at composite) -> blur -> refraction lens
(`C/GlassEffect.kt:393-421`, `B/effects/ColorFilter.kt:19-32`); (3) a translucent fill and the content; (4) the edges:
a two-sided specular rim, an outer hairline and a soft shadow (`B/highlight/*`, `B/shadow/*`). Chrome is always a
**sibling drawn after** the content subtree, never inside it (a source-code comment gives recursive RenderNode drawing as the reason, `AppRootContent.kt:~1194`).

glisse gets the same optics on the web with only `blur(3px) + saturate(1.5) + translucent fill` (`G/styles.css:91-130`)
plus its lens bend and rim; the optical character comes from the lens and rim, not from heavy blur. Rovsti follows that:
small blur, real lens, measured rim.

API levels: `RenderEffect` blur/chain 31, `RuntimeShader`/`createRuntimeShaderEffect` 33 (AGSL), Compose
`GraphicsLayer`/`rememberGraphicsLayer`/`requireGraphicsContext` compose-ui 1.7. Rovsti minSdk 34: all available.

---

## 2. Capture engine (ORIGINAL)

```kotlin
// ORIGINAL
package dev.rovsti.ui.glass

/** One per screen root. The content subtree records itself into [layer]; glass siblings replay it. */
@Stable
class GlassSource internal constructor(internal val layer: GraphicsLayer) {
    internal var coordinates: LayoutCoordinates? = null
}

@Composable
fun rememberGlassSource(): GlassSource {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassSource(layer) }
}

/** On the CONTENT root (list, map). Draws normally and keeps a recording for glass to sample. */
fun Modifier.glassSource(source: GlassSource, background: Color): Modifier = this
    .onPlaced { source.coordinates = it }
    .drawWithContent {
        source.layer.record {
            drawRect(background)                 // glass over transparent areas still blurs a solid base
            this@drawWithContent.drawContent()   // (Convx: AppBackdropState.kt:54-62)
        }
        drawLayer(source.layer)
    }
```

The glass node replays the source into its **own** layer, which carries the effects and the clip:

```kotlin
// ORIGINAL
private class GlassNode(
    var source: GlassSource,
    var spec: GlassSpec,          // tokens resolved for the current tier and theme (section 4)
    var cornerPx: Float,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var own: GraphicsLayer? = null
    private var me: LayoutCoordinates? = null
    private var recordedOffset = Offset.Unspecified
    private var recordedSize = Size.Unspecified
    private var effectKey: Any? = null

    override fun onAttach() { own = requireGraphicsContext().createGraphicsLayer() }
    override fun onDetach() { own?.let { requireGraphicsContext().releaseGraphicsLayer(it) }; own = null }
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) { me = coordinates; invalidateDraw() }

    override fun ContentDrawScope.draw() {
        val layer = own
        val src = source.coordinates
        val here = me
        if (layer == null || src == null || here == null || spec.tier == GlassTier.Flat) {
            drawFlat(spec, cornerPx); drawContent(); drawEdges(spec, cornerPx); return
        }
        val offset = src.localPositionOf(here, Offset.Zero)
        // Re-record only when this surface moved or resized relative to the source. Pixel changes inside the
        // source should flow through: the recording references the source's RenderNode. VERIFY this on an
        // emulator with a scrolling list first (JVM renderers cannot show it); if the replay goes stale, drop
        // the cache and re-record on every source draw (accept the cost, then lean on the freeze gate).
        if (offset != recordedOffset || size != recordedSize) {
            val s = spec.captureScale                                   // section 5.2
            // Named args: the first positional parameter of GraphicsLayer.record is the Density, not the size.
            layer.record(
                density = this, layoutDirection = layoutDirection,
                size = IntSize((size.width * s).roundToInt(), (size.height * s).roundToInt()),
            ) {
                scale(s, s, pivot = Offset.Zero) { translate(-offset.x, -offset.y) { drawLayer(source.layer) } }
            }
            recordedOffset = offset; recordedSize = size
        }
        val key = Triple(size, spec, cornerPx)
        if (key != effectKey) {                                          // build effects only when inputs change
            layer.colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(spec.saturation) })
            layer.renderEffect = glassEffect(size * spec.captureScale, cornerPx * spec.captureScale, spec)
            layer.setRoundRectOutline(Offset.Zero, size * spec.captureScale, cornerPx * spec.captureScale)
            layer.clip = true
            effectKey = key
        }
        scale(1f / spec.captureScale, 1f / spec.captureScale, pivot = Offset.Zero) { drawLayer(layer) }
        drawFill(spec, cornerPx)         // translucent base + tint, outline-shaped
        drawContent()
        drawEdges(spec, cornerPx)        // rim shader + hairline (section 3.3)
    }
}
```

Rules learned from Convx:
- The source must not contain any glass that samples it (recursion). Chrome is a later sibling (`AppRootContent.kt:900-1190`).
- Keep one source per screen; a second full-screen source forces extra NavHost redraws during scroll (`AppRootContent.kt:900-922` comment).
- Remember the `onDraw` lambda and colour of the source; a new lambda identity forces a re-record (`AppBackdropState.kt:53`).
- **Pressed or stretched glass must sample under the inverse of its own transform**, or the background moves with the
  button (`B/internal/InverseLayerScope.kt`, used `LayerBackdrop.kt:~160`). If `glassPress` (motion.md 7) sits on the same
  element, take the sampling offset from the *unscaled* parent position and draw the replay inside
  `withTransform({ inset/scale by the inverse of the press values about the centre })`. Verify with a screenshot of a
  pressed button over a striped backdrop: stripes must not move.
- Stops: if the map is drawn by our own Compose tile painter (`rovsti_tiles` + `Canvas`), it can be the glass source.
  A `SurfaceView`/`TextureView` map cannot be recorded into a `GraphicsLayer`; then map chrome uses the Frost-from-tint
  or Flat tier (open risk noted in `convx-glass.md` 12; Convx skips capture over WebView routes, `AppRootContent.kt:~900`).

---

## 3. Effects

### 3.1 Chain

```kotlin
// ORIGINAL (uses the ported lens in 3.2)
fun glassEffect(size: Size, cornerPx: Float, spec: GlassSpec): androidx.compose.ui.graphics.RenderEffect? {
    val blur = if (spec.blurPx > 0f) android.graphics.RenderEffect.createBlurEffect(
        spec.blurPx * spec.captureScale, spec.blurPx * spec.captureScale, android.graphics.Shader.TileMode.CLAMP) else null
    if (spec.tier != GlassTier.Liquid) return blur?.asComposeRenderEffect()
    val lens = GlassLensShader.effect(size, cornerPx, spec.lens, amount = spec.lensAmount)   // API 33
    // createChainEffect(outer, inner): inner runs first. Blur first, then bend the blurred image.
    return (if (blur != null) android.graphics.RenderEffect.createChainEffect(lens, blur) else lens).asComposeRenderEffect()
}
```

Saturation is applied as the layer's `colorFilter` (a colour matrix is linear and commutes with blur and warp, so it is
free at composite; Convx puts it first for this reason, `B/BackdropEffectScope.kt:32-39`). Compose:
`ColorMatrix.setToSaturation` + `ColorFilter.colorMatrix`. Never set an effect to a zero value (blur 0, alpha 1): use
`null`, an identity effect still allocates a pass (`automation/progress/005-perf-120fps-pass.md`, finding F4).

### 3.2 Lens (PORTED FROM GLISSE: `G/internal/lens.ts:96-252,525`)

glisse's displacement-map lens, made analytic: per pixel, rounded-rect SDF; a circular-dome slope scaled so its mean
over the half span is 0.5; optional splay (near top/bottom the pull turns vertical); an `erf` falloff confines the bend to
a rim band; three taps with a spectrum for dispersion. The sampling offset points **inward** (rim shows content from
further inside: a convex lens). glisse bends in two SVG passes; one 2D offset is the intended result and cheaper
(`lens.ts:430-447`).

Tuning table (copy as-is; `G/internal/refraction.ts:14-24`, `segments.tsx:73-95`, `switch.tsx:31-38`, `slider.tsx:34-41`):

| Lens | strength | dispersion | depth | dome | splay | notes |
|---|---|---|---|---|---|---|
| `Glass` (buttons, bars, cards) | 0.45 | 0.4 | 0.25 (relative) | 0.5 (relative) | 1 | relative = share of the shorter side |
| `TabLens` (lifted tab puck) | 0.2 | 2 | 4 dp | 0 | 1 | glisse adds ink mode + 2 px soften; optional |
| `SegmentLens` | 0.3 | 0.1 | 2.5 dp | 0 | 1 | |
| `SwitchThumb` | 0.45 | 1 | 2 dp | 6 dp | 0.4 | samples the stand-in track |
| `SliderThumb` | 0.75 | 0.65 | 2 dp | 5 dp | 0.5 | |

```kotlin
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Lens shape after glisse's implementation of Aave's web glass (dome slope, erf falloff).
package dev.rovsti.ui.glass

import kotlin.math.*

data class LensOptions(
    val strength: Float, val dispersion: Float, val depth: Float, val dome: Float,
    val splay: Float, val relative: Boolean = false,
) {
    companion object {
        val Glass = LensOptions(0.45f, 0.4f, 0.25f, 0.5f, 1f, relative = true)
        val TabLens = LensOptions(0.2f, 2f, 4f, 0f, 1f)
        val SegmentLens = LensOptions(0.3f, 0.1f, 2.5f, 0f, 1f)
        val SwitchThumb = LensOptions(0.45f, 1f, 2f, 6f, 0.4f)
        val SliderThumb = LensOptions(0.75f, 0.65f, 2f, 5f, 0.5f)
    }
}

/** Circular arc sagging by [sag] across [half], with the scale that makes its mean slope 0.5. */
data class Arc(val radius: Float, val scale: Float)

fun arc(sag: Float, half: Float): Arc {
    val radius = (half * half + sag * sag) / (2f * sag)
    var sum = 0f
    for (i in 0..200) {
        val x = i / 200f * half
        val slope = x / sqrt(radius * radius - x * x)
        sum += if (i == 0 || i == 200) slope / 2f else slope
    }
    val mean = sum / 200f
    return Arc(radius, if (mean > 0f) 0.5f / mean else 1f)
}

fun slope(x: Float, a: Arc): Float {
    val inside = min(x, 0.999f * a.radius)
    return inside / sqrt(a.radius * a.radius - inside * inside) * a.scale
}

fun roundRectSdf(x: Float, y: Float, hw: Float, hh: Float, r: Float): Float {
    val qx = abs(x) - hw + r
    val qy = abs(y) - hh + r
    return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
}

private fun erf(x: Float) = tanh(1.7724538509f * x)

/** CPU reference of the shader (for JVM property tests). Returns the inward pull (dx, dy) times falloff, or null outside. */
fun lensPull(x: Float, y: Float, w: Float, h: Float, radius0: Float, o: LensOptions): Pair<Float, Float>? {
    val hw = w / 2f; val hh = h / 2f
    val radius = radius0.coerceIn(0f, min(hw, hh))
    if (roundRectSdf(x, y, hw, hh, radius) >= 0f) return null
    val share = if (o.relative) min(w, h) else 1f
    val depth = o.depth * share
    val dome = o.dome * share
    val innerW = max(0f, hw - depth); val innerH = max(0f, hh - depth)
    val innerR = radius.coerceIn(0f, min(innerW, innerH))
    val ramp = if (depth > 0f) 1f / (depth * sqrt(2f)) else 1e6f
    val sag = max(0.01f, min(dome, min(hw, hh) - 1f))
    val across = if (dome > 0f) arc(sag, hw) else null
    val down = if (dome > 0f) arc(sag, hh) else null
    var dx = if (across != null) sign(x) * slope(abs(x), across) else (x / hw).coerceIn(-1f, 1f)
    var dy = if (down != null) sign(y) * slope(abs(y), down) else (y / hh).coerceIn(-1f, 1f)
    val reach = 0.5f * min(hw, hh)
    if (o.splay < 1f && reach > 0f) {
        val keepX = max(0f, 1f - (hh - abs(y)) / reach) * (1f - o.splay)
        val keepY = max(0f, 1f - (hw - abs(x)) / reach) * (1f - o.splay)
        if (keepX > 0.001f || keepY > 0.001f) {
            val before = hypot(dx, dy)
            dx *= 1f - keepX; dy *= 1f - keepY
            val after = hypot(dx, dy)
            if (after > 0.001f) { dx *= before / after; dy *= before / after }
        }
    }
    val falloff = 0.5f * (1f + erf(roundRectSdf(x, y, innerW, innerH, innerR) * ramp))
    return Pair(dx * falloff, dy * falloff)
}

/** glisse spectrum: red bends furthest. */
fun spectrum(dispersion: Float) = floatArrayOf(1f + 0.2f * dispersion, 1f + 0.1f * dispersion, 1f)
```

AGSL (store as `res/raw/glass_lens.agsl` or a Kotlin string). One `RuntimeShader` **instance per glass surface** (instances
carry their own uniforms; sharing one between two surfaces makes them overwrite each other), created once in `onAttach`, never
per frame. After changing uniforms, assign a fresh `RenderEffect.createRuntimeShaderEffect(...)` to the layer, because an
already-built effect does not reliably pick up later uniform writes. Cache by key if many identical surfaces exist
(Convx does, `B/RuntimeShaderCache.kt`):

```glsl
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Lens shape after glisse's implementation of Aave's web glass (dome slope, erf falloff).
uniform shader content;     // blurred backdrop, in this layer's px
uniform float2 size;
uniform float radius;
uniform float ramp;         // 1/(depth*sqrt2), 1e6 when depth == 0
uniform float2 innerHalf;   // max(0, half - depth)
uniform float innerRadius;
uniform float2 arcR;        // dome arc radius across / down; <= 0 means linear pull
uniform float2 arcS;        // arc scale across / down
uniform float splay;
uniform float splayReach;   // 0.5 * min(halfW, halfH)
uniform float reach;        // strength * min(w, h) * amount, px
uniform float3 spectrum;    // 1+.2d, 1+.1d, 1

float sdRound(float2 p, float2 hb, float r) {
    float2 q = abs(p) - hb + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float tanhA(float x) { float e = exp(2.0 * clamp(x, -9.0, 9.0)); return (e - 1.0) / (e + 1.0); }
float slopeOf(float a, float R, float S) { float i = min(a, 0.999 * R); return i / sqrt(R * R - i * i) * S; }

half4 main(float2 coord) {
    float2 hb = size * 0.5;
    float2 p = coord - hb;
    if (sdRound(p, hb, radius) >= 0.0) return content.eval(coord);
    float2 a = abs(p);
    float dx = arcR.x > 0.0 ? sign(p.x) * slopeOf(a.x, arcR.x, arcS.x) : clamp(p.x / hb.x, -1.0, 1.0);
    float dy = arcR.y > 0.0 ? sign(p.y) * slopeOf(a.y, arcR.y, arcS.y) : clamp(p.y / hb.y, -1.0, 1.0);
    if (splay < 1.0 && splayReach > 0.0) {
        float kx = max(0.0, 1.0 - (hb.y - a.y) / splayReach) * (1.0 - splay);
        float ky = max(0.0, 1.0 - (hb.x - a.x) / splayReach) * (1.0 - splay);
        float before = length(float2(dx, dy));
        dx *= 1.0 - kx; dy *= 1.0 - ky;
        float after = length(float2(dx, dy));
        if (after > 0.001) { dx *= before / after; dy *= before / after; }
    }
    float fall = 0.5 * (1.0 + tanhA(1.7724538509 * sdRound(p, innerHalf, innerRadius) * ramp));
    float2 off = -0.5 * float2(dx, dy) * fall * reach;      // inward sampling offset
    half4 r = content.eval(coord + off * spectrum.x);
    half4 g = content.eval(coord + off * spectrum.y);
    half4 b = content.eval(coord + off * spectrum.z);
    return half4(r.r, g.g, b.b, max(r.a, max(g.a, b.a)));
}
```

(`tanh` is written out because AGSL follows GLSL ES 1.0 intrinsics; verify before using built-in hyperbolic functions.)

Kotlin glue: `RuntimeShader(source)`, `setFloatUniform("size", w, h)` etc., `RenderEffect.createRuntimeShaderEffect(shader, "content")`
(API 33). Uniforms from the CPU: `arc()` once per size (cache by `(w, h, dome)`), `reach = strength * min(w,h) * amount`
where `amount` 0..1 is the lift drive, so the bend never pops on at full strength (`G/internal/segments.tsx:161-163`).
Dark/light: glisse's `--lg-lens-shine` +1 (dark, brighten) / -1.5 (light, shade) only matters when the lens lights itself;
glass surfaces paint their own rim (3.3), so leave it off for them.

**Snell selection lens** (light-page tab/segment puck, `G/internal/refraction.ts:333-505`): bezel `clamp(min(w,h)*0.12, 4, 10)`,
thickness `clamp(min(w,h)*0.20, 8, 24)`, a convex squircle height profile, refraction index 1.5, displacement **outward**
along the edge normal, channel factors 1.035 / 1.000 / 0.965. Port it into the same shader file with a `mode` uniform when
the tab bar milestone needs it (glisse.md 5.2 has the full formula); evaluate the closed form per pixel instead of a
128-entry table.

### 3.3 Rim light and hairline (PORTED FROM GLISSE: `G/internal/refraction.ts:28-94`)

Measured by glisse's author from iOS 26.1 `.glassEffect()`: brightest at the very edge, gone 1.22 px in; light from the
top-left lights the left and top sides, its reflection the right and bottom; fades over the other two corners; under it a
0.5 px edge line. Shine 0.3 on light pages, 0.13 on dark. Draw it as a `ShaderBrush(RuntimeShader)` rect over the glass
(after content; the shader's `coord` is in the canvas space of the draw call, so draw the rect at the surface origin or `translate` first so `p = coord - size/2` is centred), cached per (size, radius, shine, edge colour); animate alpha, not geometry (Convx re-records its rim only
on geometry change, `B/highlight/HighlightModifier.kt:118-148`).

```glsl
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
uniform float2 size;
uniform float radius;
uniform float shine;     // 0.3 light page, 0.13 dark page
uniform half4 edge;      // hairline colour, straight alpha: black@0.08 light, white@0.08 dark

float sdRound(float2 p, float2 hb, float r) {
    float2 q = abs(p) - hb + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    const float RIM = 1.22;
    const float EDGE = 0.5;
    float2 hb = size * 0.5;
    float2 p = coord - hb;
    float corner = clamp(radius, 0.0, min(hb.x, hb.y));
    float depth = -sdRound(p, hb, corner);
    if (depth < -0.5 || depth > 2.0 * RIM) return half4(0.0);
    float qx = abs(p.x) - hb.x + corner;
    float qy = abs(p.y) - hb.y + corner;
    float2 n = float2(0.0);
    if (qx > 0.0 && qy > 0.0) n = float2(sign(p.x) * qx, sign(p.y) * qy) / length(float2(qx, qy));
    else if (qx > qy) n.x = sign(p.x);
    else n.y = sign(p.y);
    float facing = abs(n.x + n.y);               // 1 on lit sides, sqrt2 on lit corners, 0 on the others
    float along = max(0.0, depth) / (RIM * sqrt(max(1.0, facing)));
    float cover = clamp(depth + 0.5, 0.0, 1.0);
    float lined = clamp(min(EDGE, depth + 0.5) - max(0.0, depth - 0.5), 0.0, 1.0);
    float light = along < 1.0 ? shine * pow(min(1.0, facing), 1.5) * (1.0 - pow(along, 1.5)) * cover : 0.0;
    float line = edge.a * lined * (1.0 - light);
    return half4(half3(light) + edge.rgb * line, light + line);   // premultiplied
}
```

JVM property tests (from glisse `test/glass.test.tsx:209-240`, ported as a Kotlin CPU twin of this shader): alpha at the
lit edge > 0.55 of shine-scaled max, ~0 at 1.4 px in, lit corners > 0.45, unlit corners < 0.03.

Convx's rim is the same idea generalised to any light angle (`|dot(n, lightDir)|^falloff`, Plus blend, 45 degrees,
`B/highlight/HighlightStyle.kt:46-77`) plus a dark companion rim (1.6 dp black @0.32 with diagonal gradients) because a
Plus-blended black rim is invisible (`utils/ModifierExt.kt:279-336`). Add the dark rim only if screenshots over light maps
show the edge disappearing.

### 3.4 Shadows

Floating chrome: radius 24 dp, y 4 dp, black 0.18 (`C/GlassCircleButton.kt:102-109`); menus: radius 40 dp, y 8 dp, black 0.12
(`ios/foundation/IosMenuMaterial.kt:83`); glisse base `0 1px 3px` black 0.06 light / none dark, menu `0 12px 40px` (`G/styles.css`).
Use `Modifier.shadow(elevation, shape, clip = false, ambientColor, spotColor)` (platform shadow, cheap) or the Compose 1.9+
drop-shadow modifier if the pinned version has it (verify the API name). Never a per-row shadow in lists.

### 3.5 Thumb material (switch/slider, glisse `G/styles.css:661-673`)

`blur(2px*settled + .5px*lift) brightness(1 + .15*settled) saturate(1.5)`, `settled = 1 - lift`; shadows
`0 6 24 black .2`, inset `1.5 white .5`, inset `0 2 1 white .45`, inset `2 7 24 black .09`, inset `-2 -7 24 white .16`;
face (opaque white) opacity `1 - .85*lift` so a held thumb turns into clear glass showing the magnified stand-in track.

---

## 4. Tokens

### 4.1 glisse tokens (PORTED FROM GLISSE: `G/styles.css:1-89`)

```kotlin
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
@Immutable
data class GlassTokens(
    val foreground: Color, val muted: Color, val glass: Color, val glassActive: Color, val edge: Color,
    val drop: Color, val selection: Color, val lens: Color, val lensDrop: Color, val segmentTrack: Color,
    val segmentThumb: Color, val surface: Color, val surfaceEdge: Color, val shadow: Color, val highlight: Color,
    val separator: Color, val destructive: Color, val track: Color, val rail: Color, val on: Color,
    val fill: Color, val ring: Color, val lensShine: Float, val rimShine: Float,
) {
    companion object {
        val Light = GlassTokens(
            foreground = Color(0f, 0f, 0f, .88f), muted = Color(60, 60, 67).copy(alpha = .6f),
            glass = Color.White.copy(alpha = .6f), glassActive = Color(236, 236, 240).copy(alpha = .85f),
            edge = Color.Black.copy(alpha = .08f), drop = Color.Black.copy(alpha = .06f),
            selection = Color.Black.copy(alpha = .10f), lens = Color.Black.copy(alpha = .03f),
            lensDrop = Color.Black.copy(alpha = .11f), segmentTrack = Color(118, 118, 128).copy(alpha = .12f),
            segmentThumb = Color.White, surface = Color(250, 250, 252).copy(alpha = .86f),
            surfaceEdge = Color.Black.copy(alpha = .08f), shadow = Color(25, 25, 35).copy(alpha = .12f),
            highlight = Color(60, 60, 67).copy(alpha = .09f), separator = Color(60, 60, 67).copy(alpha = .15f),
            destructive = Color(0xFFFF383C), track = Color(59, 59, 66).copy(alpha = .3f),
            rail = Color.Black.copy(alpha = .1f), on = Color(0xFF34C759), fill = Color(0xFF0088FF),
            ring = Color.Black.copy(alpha = .4f), lensShine = -1.5f, rimShine = 0.3f,
        )
        val Dark = GlassTokens(
            foreground = Color.White, muted = Color(235, 235, 245).copy(alpha = .6f),
            glass = Color(40, 40, 40).copy(alpha = .62f), glassActive = Color(70, 70, 70).copy(alpha = .62f),
            edge = Color.White.copy(alpha = .08f), drop = Color.Transparent,
            selection = Color.White.copy(alpha = .145f), lens = Color.White.copy(alpha = .055f),
            lensDrop = Color.Transparent, segmentTrack = Color.Transparent,
            segmentThumb = Color(235, 235, 245).copy(alpha = .3f), surface = Color(28, 28, 31).copy(alpha = .86f),
            surfaceEdge = Color.White.copy(alpha = .13f), shadow = Color.Black.copy(alpha = .38f),
            highlight = Color(235, 235, 245).copy(alpha = .13f), separator = Color(235, 235, 245).copy(alpha = .16f),
            destructive = Color(0xFFFF4245), track = Color(235, 235, 245).copy(alpha = .3f),
            rail = Color.White.copy(alpha = .1f), on = Color(0xFF30D158), fill = Color(0xFF0091FF),
            ring = Color.White.copy(alpha = .5f), lensShine = 1f, rimShine = 0.13f,
        )
    }
}
```

Theme follows the system (`isSystemInDarkTheme()`), matching glisse's `prefers-color-scheme` rule (`G/styles.css:31,60-61`) and
Rovsti's `FOLLOW_SYSTEM_THEME`. Crossfade theme colours over 280 ms read in the draw phase so effects are not rebuilt
(`C/GlassEffect.kt:356-361`).

### 4.2 Rovsti presets (ORIGINAL picks; numbers observed in Convx and glisse)

| Preset | Blur | Saturation | Lens | Fill (light / dark) | Rim | Corner | Source evidence |
|---|---|---|---|---|---|---|---|
| `Bar` (tab bar, top chrome, Stops floating controls) | 3 dp | 1.5 light / 1.7 dark | `LensOptions.Glass` | `tokens.glass` | glisse rim, shine per theme | capsule | glisse `G/styles.css:98`; Convx `T/GlassTintTokens.kt:74-80,103-112` |
| `Popover` (menus, popovers) | 8 dp light / dark (glisse uses 22 px) | 1.8 (glisse 1.9) | none or weak (`Glass` at amount 0.3) | `tokens.surface` | rim, alpha 0.2 | 20-32 dp | `ios/foundation/IosMenuMaterial.kt:78-80`; `G/styles.css:946-950` |
| `Sheet` | 12 dp light / 14 dp dark | 1.5 / 1.7 | none | `tokens.surface` | hairline only | 28 dp top | `T/GlassTintTokens.kt:103-108`; `C/BottomSheetPage.kt:134-153` |
| `Thumb` | section 3.5 | 1.5 | `SwitchThumb` / `SliderThumb` | white face | rim | capsule | glisse |

Content colours on glass: opaque in light (`#111111` icons, black titles), dimmed white in dark (icon 0.85, title 0.92,
secondary 0.60) (`T/GlassTintTokens.kt:35-58`). Contrast check: WCAG split at luminance 0.179, not 0.5 (`T/AppleTokens.kt:129-135`);
JVM-test every preset for >= 4.5:1 label contrast over a white, a black and a mid-grey backdrop.

Shapes: continuous (squircle) corners read as iOS; `RoundedCornerShape` is acceptable, an Apache-2.0 squircle (Kyant0
capsule) is the polish option. Concentric rule: inner radius = outer - inset, floor 4 dp (`utils/ModifierExt.kt:344`).

---

## 5. Performance of glass

1. **Few surfaces.** Real glass only on chrome: tab bar, top bar once scrolled, Stops floating controls, popovers. Lists,
   rows and cards are flat translucent fills with a hairline (`convx-performance.md` 13.5). No glass per row, ever.
2. **Capture scale tied to blur.** `scale = 1 - clamp(blurDp/2.5, 0, 1) * (1 - 0.30)`: clear glass at full resolution (crisp
   lens edges), blur >= 2.5 dp at 30% (`C/GlassEffect.kt:146-164`). Multiply every px parameter (blur, lens depth, reach) by
   the same scale (`GlassEffect.kt:322-328`).
3. **Pool when there are many pieces.** For N small surfaces over one content, record + blur once at ~0.35 screen scale, region-limited
   to the union of the surfaces (+32 dp), refract all in one shader pass (up to 8 rects), mask once (`B/GlassPool.kt:38-42,119-353`).
   Build it only if profiling shows > ~4 ms RenderThread for the bar + buttons; it is not v1.
4. **Freeze while content moves.** Convx freezes backdrop re-recording during scroll (+900 ms safety), screen entry (300 ms),
   nav push (330 ms), sheet drag; measured 47 ms of `layer.record` per scroll frame without the freeze vs 1.9 ms with it
   (`B/backdrops/BackdropFreeze.kt:14-25`, `NavTransitionFreeze.kt:17`). In the engine above re-recording happens only on
   geometry change, so freezing applies to *surfaces that move* (a sheet dragging glass over a list) and to expensive
   tiers: while `MotionGate.frozen` is true (performance.md 5), draw the last recording and do not re-record on small
   offset changes below 1 px. The freeze flag must be a plain field or an edge-only state; a snapshot read that changes per
   frame in draw re-invalidates every frame (`BackdropFreeze.kt:25-34`).
5. **Effects built only when inputs change** (key on size/spec/corner). Stable lambda identities (`C/GlassEffect.kt:374-420`).
6. **Stops map**: while the user pans/pinches, keep the floating glass at Frost tier (no lens) or reuse the last frame; switch back
   to Liquid when the camera settles.

---

## 6. Fallback tiers (decide in one pure function)

| Tier | What | When |
|---|---|---|
| `Liquid` | saturation + blur + lens + rim | default on device (minSdk 34) |
| `Frost` | saturation + blur, hairline, no lens, no rim shader | power saver on, low-RAM device, map being panned, user "reduce transparency" off but "reduce effects" on |
| `Flat` | no capture; fill `base @ 0.85` + hairline (white 0.12 dark / black 0.08 light) + shadow | user "reduce transparency", screenshot/JVM tests, no source available (SurfaceView map), `ActivityManager.isLowRamDevice` with power saver |

Convx tiers: LIQUID / BLUR / TRANSPARENT, fallback rule `TRANSPARENT || !renderEffectSupported -> flat`, flat fill
`base @ 0.85` (`C/GlassEffect.kt:127-140,301-308,363-368`); disables glass on low-RAM entirely (`GlassEffect.kt:170-183`),
which we soften to Frost.

```kotlin
// ORIGINAL
enum class GlassTier { Liquid, Frost, Flat }

data class GlassConditions(
    val sdk: Int, val lowRam: Boolean, val powerSave: Boolean, val reduceTransparency: Boolean,
    val reduceEffects: Boolean, val sourceAvailable: Boolean, val contentMoving: Boolean, val forTests: Boolean,
)

fun glassTier(c: GlassConditions): GlassTier = when {
    c.forTests || c.reduceTransparency || !c.sourceAvailable || c.sdk < 31 -> GlassTier.Flat
    c.lowRam && c.powerSave -> GlassTier.Flat
    c.sdk < 33 || c.lowRam || c.powerSave || c.reduceEffects || c.contentMoving -> GlassTier.Frost
    else -> GlassTier.Liquid
}
```

Inputs: `ActivityManager.isLowRamDevice()`, `PowerManager.isPowerSaveMode` (+ `ACTION_POWER_SAVE_MODE_CHANGED`), our own
settings toggles (Android has no public "reduce transparency" setting). JVM-test the table.

---

## 7. Testing glass without a device

- JVM: `glassTier`, capture-scale curve, `lensPull` property tests ported from glisse `test/lens.test.tsx:10-49`
  (for a 60x30 r15 lens, depth 2, dome 6, splay .4: centre pull exactly 0; right rim pulls left (dx > 0 means sampling
  from inside), left rim the opposite; vertical antisymmetry; `relative` band is the same fraction for 40 and 160 px glass),
  `spectrum(0.4)[0] == 1.08` (`test/glass.test.tsx:123-124`), rim CPU twin (3.3), token contrast.
- Uniform packing: unit-test the Kotlin function that turns `(size, corner, LensOptions, amount)` into the uniform list
  (names and order) without compiling AGSL.
- Screenshots (Roborazzi/Paparazzi): JVM renderers may not execute `RenderEffect`/`RuntimeShader` like a GPU. Snapshot the
  **Flat** tier for layout and contrast, and draw the rim with the CPU twin into a bitmap to snapshot rim shape. Verify
  what the chosen renderer supports with one throwaway test before trusting a Liquid screenshot. Backdrop for glass
  screenshots: a colourful striped/gradient fake map so refraction or its absence is visible. Open every PNG and judge it.
