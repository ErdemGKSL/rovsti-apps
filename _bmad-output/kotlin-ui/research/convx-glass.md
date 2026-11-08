# Convx research: liquid glass rendering, modules, design tokens

Scope: how Convx draws "liquid glass" on Android (Compose), what the `hig`, `canvas` and `spine` modules are, and the design tokens it uses. This is a research input for the Rovsti Kotlin UI rewrite.

Source: `cosmictaserdev-creator/Convx` checked out at `$REF` = `/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/ref/Convx`. All paths below are relative to `$REF`. `C/` is shorthand for `app/src/main/kotlin/com/convx/music/ui/component/`, `B/` for `C/backdrop/`, `T/` for `app/src/main/kotlin/com/convx/music/ui/theme/`.

## 0. Licence and provenance (read first)

- Convx is GPL-3.0 (`LICENSE`; file headers such as `C/GlassEffect.kt:1-4`, `T/GlassTintTokens.kt:1-4`, `C/TopFadeBlur.kt:1-4` say "Licensed under GPL-3.0"). Rovsti is MIT/Apache. Nothing in this report is a copy of Convx code. The pseudo-code is written from scratch and parameters (numbers) are facts, not expression.
- Most of the glass engine is NOT original Convx work. It is vendored from permissively licensed upstreams, with Convx modifications on top:
  - `B/**` (backdrop engine): "Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop) ... Apache License 2.0" with Convx additions: backdrop resolution scale, KMP merge, composite colour filter, pool, freeze (`B/internal/Shaders.kt:1-24`, `B/DrawBackdropModifier.kt:1-9`, `B/BackdropEffectScope.kt` header).
  - `C/backdrop/catalog/components/LiquidBottomTabs.kt:1-17`, `LiquidToggle.kt:1-14`: "Vendored from Kyant0/AndroidLiquidGlass (kmp branch) ... Apache License 2.0" (catalog demo components).
  - `C/shapes/ContinuousRoundedRectangle.kt:2-9`: vendored from Kyant0/Capsule (Apache-2.0), the squircle shape.
  - `hig/` is a fork of Compose Cupertino (Apache-2.0; `hig/LICENSE`, `hig/src/main/kotlin/zone/ien/hig/Surface.kt:1-16`). Its `HazePlatform.kt:21-22` carries Haze's Apache-2.0 notice.
  - `C/backdrop/catalog/utils/InteractiveHighlight.kt` header says "Copyright 2026, compose-miuix-ui contributors, SPDX Apache-2.0" (behaviour copied from that project).
- The Convx-original (GPL) parts are the glue: `C/GlassEffect.kt`, `B/GlassPool.kt`, `B/backdrops/BackdropFreeze.kt`, `NavTransitionFreeze.kt`, `BackdropStaticRegion.kt`, `T/GlassTintTokens.kt`, `T/AppleTokens.kt`, `C/TopFadeBlur.kt`, `C/OverlayMenu.kt`, `ios/foundation/IosMenuMaterial.kt`, and the Convx edits to the vendored files (mixed-licence; treat as GPL).
- Consequence for Rovsti (matches the owner's "MIT-licensed library, copy from there" option): depend on the permissive upstreams directly (Kyant0 backdrop, Capsule, Haze, compose-cupertino) or write our own engine from the technique descriptions below. Do not take the Convx-modified copies. Before depending, re-check each upstream repo's LICENSE file at the pinned version (this report only saw the headers inside Convx's copies, not the upstream repos).

## 1. The rendering model in one page

A glass surface is not a shader over the screen. It is four layers composed in one Compose draw node:

1. Backdrop capture. The content that sits behind the glass is recorded once into a `GraphicsLayer` (the "source"), then each glass surface replays that layer, translated so the right region lines up under itself (`B/backdrops/LayerBackdrop.kt:152-192`, `B/backdrops/LayerBackdropModifier.kt:75-86`).
2. Effect chain on the replayed copy: saturation/vibrancy colour matrix -> blur -> lens refraction (AGSL), applied as `RenderEffect` on the surface's own `GraphicsLayer` (`C/GlassEffect.kt:393-421`, `B/DrawBackdropModifier.kt:591-602`).
3. Surface fill: a base colour plus an optional tint drawn over the processed backdrop, then the content (`C/GlassEffect.kt:466-480`, draw order at `B/DrawBackdropModifier.kt:541-553`: behind -> backdrop -> surface -> content -> front).
4. Edges: a specular rim (light), an outer dark rim, an outer drop shadow and optionally an inner shadow (`B/highlight/*`, `B/shadow/*`, `utils/ModifierExt.kt:293`).

Everything is driven through `Modifier.drawBackdrop(backdrop, shape, effects, highlight, shadow, innerShadow, layerBlock, onDrawSurface, backdropScale, frozen, loopBucket)` (`B/DrawBackdropModifier.kt:107-173`) and the Convx wrapper `Modifier.liquidGlass(config, shape, ...)` (`C/GlassEffect.kt:283-506`).

## 2. Backdrop capture

### 2.1 Source: `layerBackdrop` + `LayerBackdrop`

- One `LayerBackdrop` wraps a `GraphicsLayer`. The modifier `Modifier.layerBackdrop(backdrop)` goes on the SCROLLING CONTENT subtree (not on the glass). On draw it records the content into that layer and then draws the layer to the screen as normal (`B/backdrops/LayerBackdropModifier.kt:75-86`). Glass siblings (drawn later, outside this subtree) replay the same recorded display list. Comment at `C/` call site: chrome must live outside the captured subtree "to avoid a recursive RenderNode draw" (`AppRootContent.kt:1194` area; also `docs/CODEBASE_REVIEW_2026-10.md` line ~280 on preview/backdrop sibling relationship).
- The root app backdrop is created with an `onDraw` that first paints the page background colour, then the content, so glass over transparent areas still blurs a solid base (`AppBackdropState.kt:54-62`). The lambda is `remember`ed on the colour because a new lambda identity forces a re-record (`AppBackdropState.kt:53`).
- Glass reads through `Backdrop.drawBackdrop(density, coordinates, layerBlock)`. For a `LayerBackdrop` it works out where the glass sits inside the source with `localPositionOf`, translates by the negative offset, and `drawLayer(source)` (`B/backdrops/LayerBackdrop.kt:152-192`). Fallback to window positions if `localPositionOf` throws (line ~168).
- Other `Backdrop` kinds exist: `CanvasBackdrop` (draw a lambda), `CombinedBackdrop` (draw two backdrops stacked), `EmptyBackdrop` (`B/backdrops/*.kt`). Combined is how the sliding "puck" in the tab bar sees both the page and a tinted copy of the tab row (see 7.2).
- `layerBlock` support: if the glass surface itself is scaled/translated by a `graphicsLayer` (press stretch), the backdrop draw is wrapped in the INVERSE transform so the sampled pixels do not move with the glass (`B/internal/InverseLayerScope.kt`, used at `B/backdrops/LayerBackdrop.kt:~160`). This is why a stretched button still shows a stationary background.

### 2.2 Per-surface capture layer

`DrawBackdropNode` (`B/DrawBackdropModifier.kt:299-629`) owns one `GraphicsLayer`, created `onAttach` and released `onDetach` (lines 604-629). In `draw()` (541-554):

```
effectScope.update(...)            // density/size/shape changed? -> re-run the effects lambda
onDrawBehind
drawBackdropLayer()                // maybe re-record, then drawLayer(own layer)
onDrawSurface   (tint)
drawContent
onDrawFront
```

The own layer's `renderEffect` and `colorFilter` are set from the effect scope result (`updateEffects`, lines 591-602). Effects are collected by `observeReads`, so reading snapshot state inside the `effects { }` lambda (for example an animated press progress) re-runs only the effect build, not recomposition (lines 578-589).

Padding: when an effect samples outside the layer (blur with non-clamp edge, chained effects), `padding` grows and the capture is recorded `padding` px larger on every side, then `layer.topLeft` is shifted back (lines 387-405, 513-515; `B/effects/Blur.kt:19-30`). The lens subtracts its own height from padding (`B/effects/Lens.kt:36-38`).

### 2.3 When does it re-record? (the core performance idea)

Re-recording a capture is expensive (the in-code note measures 47 ms of `layer.record` per scroll frame without the freeze versus 1.9 ms with it, `B/backdrops/BackdropFreeze.kt:24-25`). Convx therefore separates:

- Content changes: samplers hold a RenderNode reference to the source layer, so pixel changes in the source show through automatically with NO re-record (`B/backdrops/LayerBackdrop.kt:49-62`, comment on `contentVersion`).
- Geometry changes: re-record only when the glass moved/resized relative to the source, the effects changed, padding changed, or the shape changed (`geometryChanged` in `drawBackdropLayer`, lines 462-501).
- `frozen: () -> Boolean`: while true and a recording exists, skip re-recording even if the source "version" bumped, UNLESS the surface itself moved or resized (then it must re-capture or it shows the wrong region; lines 480-490). If frozen and the version changed, `scheduleThawRedraw()` polls every 48 ms (`ThawPollMs`, line 632) until frozen turns false, then invalidates once (358-365).
- `backdropStaticRegion`: children can declare "this rectangle did not change this generation"; a glass whose capture rect lies entirely inside one declared region skips the re-record triggered by changes elsewhere (`B/backdrops/BackdropStaticRegion.kt`, `LayerBackdrop.kt:94-107`, used in `DrawBackdropModifier` 468-479). Only a single covering rect counts, so a miss costs only a skipped optimisation, never a stale pixel.
- Draw-phase rule: the `frozen` lambda is read during DRAW, so it must not read snapshot state (a snapshot read there registers a draw dependency and re-invalidates every frame). Convx keeps the scroll clock in a plain `LongArray` (`B/backdrops/BackdropFreeze.kt:18-21`, and see its pointer to the "trap documented" in MainActivity). Composition-level observers get a separate `MutableState` mirrored by a 120 ms poll loop (`AppBackdropState.kt:79-102`).

Freeze sources combined into the app-wide `frozenProvider` (`AppBackdropState.kt:64-77`):

| source | what | numbers |
|---|---|---|
| scroll | nested-scroll connection stamps a nano clock in `onPreScroll`, clears in `onPostFling`; safety timeout if no fling arrives | safety 900 ms (`BackdropFreeze.kt:14`) |
| screen entry | a freshly composed screen is frozen right after construction because the NavHost is animating it in | 300 ms (`BackdropFreeze.kt:20`) |
| nav push | frozen from route change unless it was a pop (predictive back is gesture-driven) | 330 ms = 260 ms push + slop (`B/backdrops/NavTransitionFreeze.kt:7,17`) |
| pager | while `isScrollInProgress`, mark every frame | `NavTransitionFreeze.kt:36-53` |
| floating bar scrolling | `floatingNavBarScrollConnection.isScrolling()` | `AppBackdropState.kt:75` |
| player morph | glass under the player card frozen until morph progress 0.75, then goes live so it is warm on arrival | `C/BottomSheet.kt:80`, `player/PlayerMorphLayers.kt:140-150` (derived state so draw readers only invalidate on flip) |

Also: the recording of the app backdrop itself is skipped entirely when no glass consumer is visible, on WebView routes, or when glass is not allowed (`AppRootContent.kt:900-922` region, comment "Keep one backdrop source. Extra full-screen layers force Compose to redraw the NavHost during scroll").

### 2.4 Lower-resolution capture (`backdropScale`)

`backdropScale` (clamped 0.05..1) records the layer at a fraction of its size, then scales it back up on draw; blur hides the upscale (`B/DrawBackdropModifier.kt:387-405, 507-513`). All pixel-sized effect parameters must be multiplied by the same scale (comment at line 66; `C/GlassEffect.kt:322-328`). Convx derives the scale from blur strength:

```
t = clamp(blurDp / 2.5, 0, 1)
scale = 1 - t * (1 - 0.30)        // blur 0 -> 1.0 ; blur >= 2.5dp -> 0.30
```
(`C/GlassEffect.kt:155-164`; constants `MIN_GLASS_RESOLUTION_SCALE=0.30`, `FULL_QUALITY_BLUR_DP=2.5`.) Clear glass (no blur) keeps full resolution so edges stay crisp.

### 2.5 Loop bucket (video backdrops)

For looping animated backdrops (canvas video) the processed layer is cached per integer "bucket" (frame slot) and reused, capped at 64 entries (`B/DrawBackdropModifier.kt:62, 417-460`). Not needed for Rovsti unless we blur live video.

## 3. The effect chain

### 3.1 Platform gating

- `RenderEffect` (blur, colour filter chain, layer effects): API 31 (`B/Platform.kt:15-16`).
- `RuntimeShader`/AGSL (lens, rim shader, variable blur): API 33 (`B/Platform.kt:18-19`).
- Convx minSdk is 26 (`app/build.gradle.kts:37`), hence all the fallbacks. Rovsti's own bridge says minSdk 34 (`/home/user/rovsti-apps/crates/rovsti_android/kotlin/dev/rovsti/bridge/MediaShare.kt:13`), so AGSL is always available for us and we can delete the whole pre-33 branch.
- Glass is also disabled on low-RAM devices: `isGlassAllowed() = API>=31 && !ActivityManager.isLowRamDevice` (`C/GlassEffect.kt:170-183`).

### 3.2 Order and parameters

Effect lambda order inside `liquidGlass` (`C/GlassEffect.kt:393-421`): `colorControls(saturation)` -> `blur(px)` -> `lens(height, amount)`. The first colour filter is not a render pass: it is stored as `compositeColorFilter` and applied when the layer is composited, because a colour matrix is linear and commutes with blur/warp (`B/effects/ColorFilter.kt:19-32`, `B/BackdropEffectScope.kt:32-39`). Only a colour filter that comes AFTER another effect becomes a chained `RenderEffect` (render pass). Lesson: put saturation first so it is free.

Saturation: matrix built from luminance weights 0.213 / 0.715 / 0.072 (`B/effects/ColorFilter.kt:64-92`). Vibrancy preference 0..2 maps to saturation `1 + 0.5 * v` (so default vibrancy 2.0 gives 2.0x; `C/GlassEffect.kt:186`, default at line 55). `vibrancy()` helper = 1.5x (`ColorFilter.kt:58-62`).

Blur: `BlurEffect(r, r, edgeTreatment)` with Clamp edges; non-Clamp edges or a pre-existing effect increase `padding` (`B/effects/Blur.kt:17-37`).

Nav bar glass physics: blur 3 dp, lens height 0.60, lens amount 0.69, vibrancy 2.0; "other" glass lens 0.35/0.40 (`T/GlassTintTokens.kt:74-80`). Lens slider values 0..1 map to 0..48 dp (`LENS_MAX_DP`, `C/GlassEffect.kt:149`), so the nav bar uses roughly 29 dp lens height and 33 dp refraction amount.

### 3.3 Lens / refraction shader (AGSL) - how it works

Source: `B/internal/Shaders.kt:59-103` (single shape), 105-153 (pooled multi-shape), 155-205 (with dispersion). Described in my own words:

- Input: the layer after blur+saturation as shader `content`. The shader runs per pixel in the glass's local coordinates.
- Geometry: a signed-distance function of a rounded rectangle with per-corner radii (top-left, top-right, bottom-right, bottom-left; radius picked from the quadrant the pixel is in). Negative inside, 0 on the edge.
- Only a band near the edge is distorted: if a pixel is deeper than `refractionHeight` from the edge it samples itself unchanged. Inside the band, with `u = 1 - depth/refractionHeight` (0 at the inner band boundary, 1 at the edge), the displacement magnitude is `amount * (1 - sqrt(1 - u*u))` (a quarter circle profile, so it is gentle then ramps steeply at the rim) or `u*u` in the "fast circle map" mode (line 77). The sign of `amount` is passed negative (`B/effects/Lens.kt:64`) so pixels are pulled from further INSIDE, which magnifies/bends the interior outward like a convex lens.
- Direction: the SDF gradient (outward normal). For straight edges it is the axis normal; for corners it is the radial direction from the corner centre. To avoid a degenerate normal on very pill-like shapes the gradient radius is capped at `min(radius*1.5, minHalfSize)` (line 93).
- `depthEffect` option: add the normalised vector from the centre to the gradient before renormalising, which bends the whole interior toward the middle (lines 96-100).
- Dispersion (chromatic aberration): sample the content three times; the red and blue taps are offset ± along the refraction vector scaled by `k * (x*y)/(halfW*halfH)`, so the colour fringe flips sign between quadrants and is zero on the axes (lines 198-204). Convx keeps it off by default (`GlassEffectConfig.chromaticAberration=false`, `C/GlassEffect.kt:62`), the interactive puck turns it on while pressed (`LiquidBottomTabs.kt:~228`).
- Uniforms computed on CPU once (half size, min half size, offset, radii) rather than per pixel (comment at `Shaders.kt:63`).
- The shader is cached per scope by string key (`B/RuntimeShaderCache.kt`), updated by setting uniforms, not recompiled.

### 3.4 Specular rim ("pluck")

`HighlightModifier` draws a thin stroked outline of the shape into its own `GraphicsLayer`, blurred, with a shader that makes the stroke brightest where the edge normal faces a light direction (`B/highlight/HighlightModifier.kt:101-180`, `HighlightStyle.kt:46-77`, shader `Shaders.kt:207-228`):

```
intensity = |dot(edgeNormal, lightDir)| ^ falloff   // both facing and opposing sides light up
colour    = rimColor * intensity   (blend = Plus)
```
- `|d|` makes it two-sided (top-left and bottom-right glow), matching iOS 26 glass. Light angle 45 deg default (`HighlightStyle.kt:59`); Convx's nominal drift range 25..65 deg is frozen at the midpoint (45) because animating it would re-record (`C/GlassEffect.kt:203-209, 372`).
- Defaults: width 0.5 dp, blur = width/2, alpha 1 (`B/highlight/Highlight.kt:20-23`); Convx glass uses 0.8 dp (`C/GlassEffect.kt:200`). Rim colour `white @ 0.5` with `BlendMode.Plus` (`HighlightStyle.kt:57-58`). Stroke width is doubled and clipped to the outline so only the inner half shows (`HighlightModifier.kt:182`).
- Rim is recorded into a layer padded by 1 px and re-recorded only when width/blur/style/size/outline/density change; alpha is applied at composite (`HighlightModifier.kt:118-148`). Cheap to animate alpha, expensive to animate geometry.
- Variants: `Plain` (uniform `white@0.38`, Plus), `Default` (directional), `Ambient` (adds only on the lit side, flat white, `Shaders.kt:230-251`; used for the toggle thumb).
- Dark companion rim: a 1.6 dp normal-blend black stroke at 0.32 alpha, drawn twice with two perpendicular diagonal gradients (dark at both ends, clear in the middle), so every side has a dark outer edge that swells and thins (`utils/ModifierExt.kt:279-336`, constants `C/GlassEffect.kt:514-515`). Needed because a Plus-blended black rim is invisible. For pooled surfaces only the inner half is drawn (`insideOnly`).
- Rim colour tokens: white, alpha 0.30/0.40 neutral dark/light, 0.48/0.55 on tinted "hero" screens (`T/GlassTintTokens.kt:40-46, 145-170`). On hero screens the rim takes the artwork hue with HSL saturation clamped 0.40..0.55 and lightness 0.62..0.74 (dark) or 0.75..0.90 (light) (lines 153-159).

### 3.5 Shadows

- Outer: blurred copy of the outline drawn under the surface with the interior cut out via a difference clip (not a Clear blend, which would need an offscreen buffer per surface). Layer sized `size + 4*radius + offset`; compositing strategy ModulateAlpha so alpha is free; re-recorded only when shadow/size/outline change (`B/shadow/ShadowModifier.kt:88-136, 147`). Default radius 24 dp, offset y = radius/6, colour black @ 0.10 (`B/shadow/Shadow.kt:24-26`).
- Inner: layer with a blur `RenderEffect` (TileMode.Decal), draws the outline then punches a shifted copy out with `BlendMode.Clear`, giving a soft inner edge shading; offscreen compositing (`B/shadow/InnerShadowModifier.kt:87-150, 165`). Defaults radius 24 dp, offset y = radius, black @ 0.15 (`InnerShadow.kt:27-29`). Only the pressed toggle/puck uses it, with radius and alpha multiplied by press progress (`LiquidBottomTabs.kt:~238`, `LiquidToggle.kt:196`).
- Floating chrome shadow used by top-bar buttons: radius 24 dp, offset (0, 4 dp), black @ 0.18 (`C/GlassCircleButton.kt:102-109`). Menu panel shadow: radius 40 dp, offset (0, 8 dp), black @ 0.12 (`ios/foundation/IosMenuMaterial.kt:83`). Token spec notes: dark `0 8px 24px rgba(0,0,0,.35)`, light `0 4px 16px rgba(0,0,0,.12)` (`T/GlassTintTokens.kt:114-116`).

### 3.6 Noise / dither

Convx glass has no noise texture. The only dithering is in the variable blur shader (interleaved gradient noise, +/- 0.5/255 added to colour to kill banding, 7.3). Haze calls set `noiseFactor = 0f` (`C/TopFadeBlur.kt:152`). So "noise" in the brief: not used for glass; add our own tiny dither only on large gradients.

## 4. The shared glass pool (second-generation optimisation)

Problem measured by Convx: with 4 glass surfaces on the bottom bar each surface recorded its own capture layer, ran its own blur and its own lens (two offscreen passes per surface). Solution `glassPool` (`B/GlassPool.kt`):

1. One invisible full-screen node records the app backdrop ONCE, at `GlassPoolScale = 0.35` of screen size (lowered from 0.5 after jank profiling; "recheck lens edge sharpness if changing it", `GlassPool.kt:39-42`), with saturation + blur (blur radius pre-multiplied by 0.35) applied as the capture layer's effect (`AppRootContent.kt:307-331`, `GlassPool.kt:~110`). It must be placed after the content and before the chrome (`AppRootContent.kt:1145-1157`).
2. It records only the "coverage region": the union of the rects of the samplers that asked for pixels (grown on demand, never shrunk, inflated 32 dp for blur reach and stretch, clipped to the pool bounds). The capture keeps full-screen coordinates, but replay is clipped to the region so Skia rejects every op outside the bar. Note in code: the full second render of every op at one third of the pixels was the dominant RenderThread cost; scale cuts GPU fill, not op submission (`GlassPool.kt:119-131, 208-231`).
3. Layers are sized to the region, not the screen, because on tiled GPUs every pass loads/stores its whole render target (`GlassPool.kt:285-292`).
4. Refraction is applied for ALL samplers in one shader pass: the AGSL takes up to 8 rounded rects (uniform arrays `rects`, `radii`, `lens`, `count`) and for each pixel finds which rect contains it (`Shaders.kt:105-153`, `MultiRefractionMaxShapes=8`). Samplers register via `lensRequest(key, rect, lens, radii, outline)` during their own draw (`GlassPool.kt:147-170`, `LayerBackdrop.kt:164-170`), so a pooled surface sets `poolLens` and carries no effect of its own (`BackdropEffectScope.kt:41-46`).
5. A "flatten" step masks the pooled output to the union of sampler outlines (outline fill in black, capture composited with `SrcIn`, compositing Offscreen), so pooled surfaces need no per-frame path clip (CPU mask upload) and never flash a full rectangle while geometry changes (`GlassPool.kt:328-353`). Pooled samplers therefore clip to a plain rectangle (`DrawBackdropModifier.kt:333-342`) and fill their tint using the outline directly (`GlassEffect.kt:466-480`).
6. A surface may use the pool only if its blur radius and vibrancy equal the pool's and it does not need dispersion; otherwise it keeps its own chain (`C/GlassEffect.kt:309-317`). The full-screen player background asks for a heavier blur (config blur x 4, max 100 dp; `PLAYER_BLUR_MULTIPLIER`, `C/GlassEffect.kt:152`, `player/Player.kt:~962`) and explicitly clears `LocalPooledGlass` (`player/Player_v2.kt:716`, `Player.kt:1588`).

Takeaway: for N small floating glass pieces over the same content, record + blur once at low resolution, refract in one pass, mask once.

## 5. Glass over scrolling content, bars, the player

- Layout of the root: `Box { content(layerBackdrop) ; glassPool node ; player sheet ; floating nav bar (glass) }`. Content is the capture source; all chrome is a later sibling, never a child of the capture (`AppRootContent.kt:900-1190`). A bottom progressive scrim (transparent -> page colour at stops 0 / .35 / .6 / .85 / 1.0 with alpha 0, .15, .4, .7, 1) sits under the bar for legibility (`AppRootContent.kt:1124-1141`).
- Floating nav bar: glass pill, `outerShadow=false` because the pool draws it, rim tint animated 300 ms FastOutSlowIn (`C/FloatingNavBar.kt:239-257`), max width 500 dp, 8 dp above the inset, horizontal gutter token (`AppRootContent.kt:1170-1180`).
- Top bar blur strip (`C/TopFadeBlur.kt`): two implementations. (a) Haze progressive blur + scrim; (b) a single AGSL pass "variable blur": per pixel, blur sigma falls with vertical position via smoothstep (`sigma = sigmaMax*(1 - smoothstep)`), opacity holds until half the ramp then fades, 40 samples placed on a golden-angle spiral (2.39996 rad) with radius from a Gaussian inverse CDF `sigma*sqrt(-2 ln(1-k))`, rotated by interleaved-gradient noise per pixel, dithered (`TopFadeBlur.kt:229-267`). Capture scale 0.25 (line 227), fade run 64 dp below the bar (line 49), scrim peak alpha 0.42 following an ease-out-cubic over 12 stops (lines 62-166), side fade of 28 dp half-width where a side bar docks (line 59). Mask uses smoothstep too (line 221).
- Player: the player background is itself a `layerBackdrop(playerBackdrop)` that samples the app backdrop through `liquidGlass(applyEdgeEffects=false, blur = nav blur * 4)` so the "GLASS" background is a heavily blurred live copy of the page behind the sheet; its static artwork child is declared `backdropStaticRegion` (`player/Player.kt:950-985`). Popups opened above the expanded player must sample the PLAYER's backdrop instead of the page (`OverlayMenu.kt:121-125`, `AppRootContent.kt:824`).
- Sheets as cards: the screen behind a presented sheet scales to 0.91 from the top-centre, slides down by the status bar inset, gets 32 dp continuous corners and a 0.22 black dim; the black fill is drawn before the layer in unscaled coordinates to fill the gap (`C/IosSheetPresentation.kt:30-76`). Draw-phase version takes `progressProvider: () -> Float` so a drag updates at full frame rate with zero recomposition.
- Sheet and menu materials (all via `liquidGlass`): floating sheet page 28 dp continuous corners, blur 12 dp over the player else config (3 dp), rim 0.16, scrim black 0.20 (player) / 0.12 (`C/BottomSheetPage.kt:134-153, 174`). Context menu panel: see 6.4.
- Gooey morph: `Modifier.gooey(blurPx)` composites into an offscreen layer, blurs, then thresholds alpha so overlapping shapes melt into one blob; no-op (no layer) at radius 0 (`C/floatingtabbar/GooeyTransition.kt:55-85`). Used for the tab bar collapse/expand.

## 6. Fallbacks and quality tiers

- `GlassStyle`: LIQUID (blur + saturation + lens + rim), BLUR (blur + saturation only; cheaper, no lens, no rim), TRANSPARENT (flat translucent tint, no capture) (`C/GlassEffect.kt:127-140`). Fallback rule: `TRANSPARENT || !renderEffectSupported -> flat` (line 139).
- Flat fallback (unsupported / low RAM): clip to shape, paint `base @ 0.85` (dark base white lift / light #F5F5F7), plus tint at the configured opacity (`C/GlassEffect.kt:301-308, 363-368`).
- Per-component switches: mini player, nav bar, side panel (`GlassEffectConfig.isEnabledFor`, lines 112-117). Settings screen has a live preview (`ui/screens/settings/GlassEffectSettings.kt`).
- Pre-33 but 31+: blur + saturation only (lens guarded by `isRuntimeShaderSupported()`, `B/effects/Lens.kt:33`; rim shader replaced by `null` shader -> plain colour stroke, `HighlightModifier.kt:~184-194`).
- Floating buttons also have a Haze fallback with `HazeInputScale.Fixed(0.2f)` ("a small circular button hides the upscale completely", `C/GlassCircleButton.kt:162-171`). Haze fallback tint for old versions is an opaque scrim (`hig/.../HazePlatform.kt:130-157`).
- Light/dark crossfade: base, tint and rim colours animate 280 ms with `animateColorAsState`, read in the draw phase so the fade never rebuilds effects (`C/GlassEffect.kt:356-361, 438`).
- Sampled-appearance trick: chrome can sample what is behind it (`LocalSampledChromeDark`) to pick dark/light content colours; the nav bar deliberately uses theme-only dark/light because the sampled value flipped during scroll and flickered tabs (`C/FloatingNavBar.kt:226`).

## 7. Interactive glass components

### 7.1 Press feel: `InteractiveHighlight`
(`C/backdrop/catalog/utils/InteractiveHighlight.kt`, and `hig/.../utils/InteractiveHighlight.kt`)
- Press progress is a spring (damping 0.5, stiffness 300). The finger position is tracked with `snapTo` while dragging and springs back to the start on release.
- Draw: a `Plus` white wash at `sheenAlpha * progress` (0.08 default) plus a radial gradient "spot" at the finger, radius `minDimension * radiusScale` (1.5 default; menus use 0.6 with sheen 0.06 and spot 0.32), spot alpha 0.15 default, stops 0 / 0.5 flat then transparent at 1.
- Menus add a release pulse: a ring that grows from `0.5x` to `1.8x` radius and fades over 460 ms FastOutSlowIn (lines ~100-126).
- Elastic stretch of the button: scale grows by `4dp/height` with press; translation is `size.min * tanh(0.05 * dragOffset / size.min)`, giving a rubber-band that saturates; drag-dependent scale per axis (`C/GlassCircleButton.kt:63-95`, "magnitude" x2 for bar pills, x2.6 lean for menus `IosMenuMaterial.kt:~169`). It is a per-frame `graphicsLayer` transform only; because the backdrop is sampled with the inverse transform, no re-capture occurs.

### 7.2 Segmented/tab "puck" (`LiquidBottomTabs`)
(`C/backdrop/catalog/components/LiquidBottomTabs.kt`; the production bar in `C/floatingtabbar/` follows the same idea.)
- Three stacked layers: (1) outer glass pill (height 64 dp, vibrancy + blur 8 dp + lens 24/24, container colour `#FAFAFA/#121212 @ 0.4`, grows by 16 dp while pressed); (2) a hidden, `alpha 0` copy of the tab row tinted with the accent colour and given its own `layerBackdrop` (this is what the puck "sees": tinted icons magnified); (3) the puck, 1/tabs of the width, height = 0.875 x pill, sampling `Combined(pageBackdrop, tintedRowBackdrop)` with lens 10/14 dp scaled by press progress, dispersion ON, rim and drop shadow alpha = press, inner shadow radius 8 dp x press.
- Puck motion: `DampedDragAnimation` with spring specs for value (damping 1, stiffness 1000), velocity (0.5, 300), press (1, 1000), scale X (0.6, 250) and scale Y (0.7, 250); pressed scale 78/56 = 1.39 (`hig/.../utils/DampedDragAnimation.kt:16-45`, `LiquidBottomTabs.kt:133`). Squash and stretch from velocity: `scaleX /= 1 - clamp(v*0.75, ±0.2)`, `scaleY *= 1 - clamp(v*0.25, ±0.2)` where v = velocity/10 (`LiquidBottomTabs.kt:~243-248`).
- Rest-state puck fill is white/black @ 0.10 (fades out with press) plus black @ 0.03 x press.
- Overscroll: the whole row shifts by `4dp * easeOut(offset/width)` while dragging past the ends (`LiquidBottomTabs.kt:~105-110`).

### 7.3 Toggle (`LiquidToggle`)
Track 64x28 dp, green `#34C759` light / `#30D158` dark, thumb `pressedScale 1.5`, thumb samples the track backdrop scaled (2/3 x 0 at rest -> 0.75 x 0.75 pressed) so it looks like clear glass only while held, blur 8 dp x (1 - press), lens 5/10 dp x press with dispersion, ambient rim, shadow radius 4 dp black @ .05, inner shadow x press (`LiquidToggle.kt:69-210`).

### 7.4 Context menu / popover material
(`C/OverlayMenu.kt`, `ios/foundation/IosMenuMaterial.kt`; popover motion is another agent's topic, listed for the material only)
- Panel width 270 dp (menu rows) / `IosMenuMaterial.Width` 238 dp, corner 32 dp using G2 continuity smoothing 0.6 (`IosMenuMaterial.kt:63-72`, `OverlayMenu.kt:231`).
- Material: blur 8 dp (reduced from the bar's 3 dp for text legibility; "Frost only", `IosMenuMaterial.kt:78`), saturation 1.8 (line 80), liquid variant lens height 8 dp / amount 12 dp (115-116), long-press variant lens 12/12 dp and fill alpha 0.80 (109-111). Dark fill `#1C1C1E @ 0.82`, light fill `#F5F5F5 @ 0.72` frost or `white @ 0.55` liquid. Separator white 0.06-0.12 / black 0.10, row highlight white 0.09-0.13 / black 0.07 (`IosMenuMaterial.kt:89-132`). Rim alpha 0.20 (`OverlayMenu.kt:204`).
- Dim behind the menu: black @ 0.18 x alpha, no blur (`OverlayMenu.kt:~125`).
- In dark, menu glyph "vibrancy" is emulated with additive white drawn twice (`utils/ModifierExt.kt:52-77`), or colour-dodge by 50% grey which doubles the backdrop (more saturated, not washed out; `vibrantChrome`, lines 92-110).

## 8. Modules

### 8.1 `hig` (`hig/`, package `zone.ien.hig`, Apache-2.0 fork of Compose Cupertino)
Provides the iOS component vocabulary in Compose, independent of Material: `CupertinoScaffold`, `CupertinoTopBars` (large/inline nav bars), `CupertinoButton`, `CupertinoSwitch`, `CupertinoCheckbox`, `CupertinoSlider` (1154 lines), `CupertinoSegmentedControl` (594), `CupertinoTextField` (1160) and `CupertinoSearchTextField`, `CupertinoDropdownMenu` (657), `CupertinoDialogs` (814), `CupertinoBottomSheet`/`ModalBottomSheet` (693/184), `CupertinoActivityIndicator` (spinner), `CupertinoIndication` (press fade), `SwipeableV2`, section lists (`section/CupertinoSectionDefaults`), `Separator`, `CupertinoIcon` + an SF-Symbol-style icon set (`icons/filled|outlined`), haptics wrapper, `Haze` fallback shim (`HazePlatform.kt`), and utils: `DampedDragAnimation`, `InteractiveHighlight`, `DragGestureInspector`, `Backdrop` (util). Theme: `CupertinoTheme` with `ColorScheme` (iOS system colours with accessible variants), `Typography` (HIG text styles), `Shapes`. Relevance to us: reference for tokens and for how HIG components behave, NOT a dependency (it is the "classic" iOS look; glass is layered on top in `app/`).
- HIG type scale (sp/line): largeTitle 34/41, title1 28/34, title2 22/28, title3 20/25, headline 17/22 SemiBold, body 17/22, callout 16/21, subhead 15/20, footnote 13/18, caption1 12/16, caption2 11/13 (`hig/.../theme/Typography.kt:45-101`).
- HIG shapes: 4 / 8 / 12 / 16 / 24 dp continuous rounded rects (`hig/.../theme/Shapes.kt` ShapeDefaults).
- iOS system colour values: light separator `#3C3C43 @ 0.12`, opaque separator `#C6C6C8`, system fill `#787880 @ 0.20/0.16/0.12/0.08`, link `#007AFF`; dark: label secondary `#EBEBF5 @ 0.60`, link `#0984FF`, separator `#545458 @ 0.6`, grouped bg `#1C1C1E`, secondary `#2C2C2E` (`hig/.../theme/ColorScheme.kt:270-393`).

### 8.2 `canvas` (`canvas/`)
Not rendering. It resolves looping artwork videos ("canvases" / animated artwork) for tracks and artist backgrounds from Apple Music (`AppleMusicCanvasProvider.kt`, `AppleMusicCatalog.kt`, `AppleMusicArtistBackgroundProvider.kt`) and Tidal (`TidalCanvasProvider.kt`), plus playlist export helpers. The UI consumes them as video backgrounds, which is why the glass code has the loop-bucket cache (2.5). Irrelevant to Rovsti (we have no music or video backdrop).

### 8.3 `spine` (`spine/`)
Not UI. A plugin/module host: downloads JS "modules" (index/manifest, Eclipse addon format) and executes them in QuickJS to answer `searchTracks` / `getTrackStreamUrl`, with shared-call dedup caches and rate limits (`ModuleManager.kt:15-30`, `QuickJsExecutor.kt`, `EclipseAddon.kt`, `SharedModuleCalls.kt`). Irrelevant to Rovsti.

Other modules `app` (everything), `artwork`, `lyrics`, `innertube`, `integrations`, `spotify`, `network-storage` are content/backend and out of scope for this topic.

## 9. Design tokens (facts to re-derive for Rovsti)

All values are numbers from Convx; re-evaluate against iOS 26 and our own contrast needs.

Glass material (`T/GlassTintTokens.kt`):
- Dark glass is a white lift, not smoke: base `white @ 0.15` (measured on-device 0.09-0.11 at 0.13, so raised; lines 83-90); light base `#F5F5F7 @ 0.40`; neutral tint dark = transparent, light `#3C3C43 @ 0.05`; hero tint (artwork-coloured screens) dark 0, light 0.25 (lines 18-29).
- Reference web recipe noted in code for the dark bar: `rgba(255,255,255,.13) + blur 12px + saturate 170%` (lines 83-87); saturation tokens 1.70 dark / 1.50 light (111-112).
- Blur radii tokens: nav/tab bar 3 dp; sheets 14 dp dark / 12 dp light (103-108). Config default blur 3 dp.
- Fill opacities: nav pill 0.55 dark / 0.68 light (fallback); search field 0.60 / 0.72; selected pill 0.70 / 0.80 (93-100).
- Hairline border: `white @ 0.12` dark, `black @ 0.08` light (49-50).
- Content colours on glass: light mode opaque (icon `#111111`, title `#000000`, tab label `#1C1C1E`); dark mode dimmed white (icon 0.85, title 0.92, unselected tab label 0.75, selected 0.92, secondary label 0.60, drag handle 0.30; text tinted toward the ambient hue with HSL sat clamp 0.18..0.38 and lightness 0.92/0.96) (lines 35-58, 180-233). Selected tab/label red `#FF453A` dark / `#FF3B30` light.
- Defaults per theme: dark surface `#171717 @ 0.50`, puck `#2E2E2E @ 0.80`, highlight `#E8E8E8 @ 0.33`; light surface `#FBFBFB @ 0.40`, puck `#D1D1D1 @ 0.67`, highlight white `@ 0.60` (60-72).
- Readability helper: pick content colour by luminance of the tint composited over what is behind (`C/GlassEffect.kt:189-197`); WCAG split is 0.179, not 0.5 (`T/AppleTokens.kt:129-135`).

Layout/type (`T/AppleTokens.kt`, `T/PageMetrics.kt`):
- Single screen gutter 20 dp; sibling gap 16; section gap 24; text gap 2 (lines 41-47). Home page rhythm: item spacing 10, section gap +14, header->row 16 and row->next header 24, shelf card gap 14, hero card width = min(70% of width, 320 dp) (`PageMetrics.kt:34-52`).
- Type: screen title 34/41, section header 22/28, item title 15/20, item subtitle 13/18, caption 12/16 (lines 54-67). Font: SF Pro Text (regular/medium/semibold/bold) via bundled font files (`T/Type.kt:17-24`); other optional families exist (Google Sans Flex, Outfit, Jakarta).
- Shapes: only four corners: artwork/control 8, card 14, large card 18 (lines 70-73), all CONTINUOUS (squircle) via vendored Capsule; nav bar uses a continuous capsule; menu 32 dp with G2 smoothing 0.6; floating sheet 28 dp; presented screen 32 dp. Concentric rule: inner radius = outer - inset, floor 4 dp (`utils/ModifierExt.kt:344`).
- Elevation: handled by blurred shadows (3.5), not Material elevation; hairlines and rims carry separation.
- Accent: app red `#FA2D48` (Apple Music) (line 21); page backgrounds `#121212 / #1A1A1A`, cards `#1C1C1E / #2C2C2E`, secondary text iOS systemGray `#8E8E93` (lines 24-27, 177).
- Motion tokens (SwiftUI-modelled): all springs share stiffness 158 (= (2*pi/0.5s)^2); standard = critically damped (1.0), emphasis 0.85, expressive 0.76; fade tween 200 ms (lines 75-92). Glass-specific springs: press 0.5/300, drag value 1.0/1000, scale 0.6-0.7/250, menu open X 0.72/700, Y 0.78/420, alpha tween 90 ms (`OverlayMenu.kt:106-112`).
- Adaptive text on artwork-tinted surfaces: HSL shift keeps the hue, caps saturation at 0.16 (body) / 0.42 (secondary) / 0.55 (headings), then walks lightness in 0.04 steps up to 12 times until contrast >= 4.5 (`AppleTokens.kt:96-169`). Page background from artwork: darken each channel by `165.9 * luminance` if luminance > 0.1 (`T/AppleColors.kt:23-52`). Overkill for Rovsti (no artwork), but the contrast loop is a good idea for avatars/WA backgrounds.

## 10. What to take, what to skip

Take (techniques):
1. Single source layer + sampler replay + inverse-layer transform for stretching glass.
2. Order: saturation (free, composite filter) -> blur -> AGSL lens. Cache shaders by key.
3. Re-record only on geometry/effect change; freeze during scroll/transition via a draw-phase plain-field flag.
4. Low-resolution capture tied to blur strength; scale pixel parameters accordingly.
5. Pool + region-limited capture + one multi-shape refraction + one outline mask, for a screen with several small floating pieces.
6. Two-sided specular rim, plus a dark outer rim; both as cached layers; animate alpha not geometry.
7. Squircle shapes, hairlines, blurred shadows, concentric radii.
8. Press feel: sheen + finger spot, tanh rubber-band, spring-driven press progress.

Skip: Haze, loop buckets, canvas, spine, HIG components, artwork-driven tinting, SF fonts (licence; use system font or an OFL font), the 40-sample variable blur unless we need a progressive top blur (a gradient mask + normal blur is fine at first).

Known weak points in Convx we should not inherit: glass over WebView needs special-casing (`AppRoutePolicy.isWebViewRoute`), low-RAM devices lose glass entirely (no cheap tier), the freeze means glass can look stale for up to 300-900 ms after scroll, `GlassEffectConfigTest`/`GlassTintTokensTest` had unresolved failing assertions at the last review (`docs/CODEBASE_REVIEW_2026-10.md:82`), and there are no device perf numbers committed (same file).

## 11. Recipe for Rovsti (Kotlin, Compose, minSdk 34, no GPUI)

Goal: iOS 26-style glass for the 4 apps, with the specific owner complaints in mind: floating tab bar must never cover the last row (use content padding, section 11.6), Stops is a FULL-SCREEN map with floating glass controls, buttons must not push content when tapped (glass pieces are overlays, never in flow), popovers and context menus exist.

### 11.1 Dependency decision
Option A (recommended): depend on upstream `io.github.kyant0:backdrop` (Apache-2.0 per the vendored header; verify the upstream LICENSE and version) for `layerBackdrop`, `drawBackdrop`, `blur`, `lens`, `vibrancy`, highlight and shadow, and on Kyant0 `capsule` for squircle shapes. Then write only the Rovsti layer (tokens, pool/freeze, components) ourselves.
Option B: write a small engine ourselves from section 2-3. About 600-900 lines. Needed if upstream is unmaintained or lags our Compose version (Convx vendored it precisely because the binary AAR broke at runtime against newer Compose, `B/internal/Shaders.kt:5-7`).
Either way: no code from Convx.

### 11.2 Module layout (`android/glass/`)
- `GlassTokens` (immutable): blur dp, saturation, lens height/amount dp, tint colour+alpha, rim alpha/width/angle, shadow, shapes. Two presets: `Bar` (blur 3, sat 1.7 dark/1.5 light, lens ~28/32 dp, rim 0.30-0.40) and `Popover` (blur 8, sat 1.8, lens 8/12, fill 0.55 light / `#1C1C1E@0.82` dark, rim 0.20, corner 32).
- `rememberGlassSource()`: returns the capture handle. `Modifier.glassSource(handle)` on the content root (map, list, whatever). 
- `Modifier.glass(handle, shape, preset, state)`: draw-node that replays the capture. Takes `frozen: () -> Boolean`.
- `GlassFreeze`: plain-field scroll clock + `NestedScrollConnection` + `navTransition` stamp. Never read snapshot state in the lambda.
- `GlassPool` only if profiling on a mid-range phone shows the tab bar + buttons costing > ~4 ms RenderThread; first version does not need it. If added, copy the idea (record once at 0.35 scale, region-limited, multi-shape lens, single outline mask), not the code.
- Components built on it: `GlassButton`/`GlassIconButton` (44 dp), `GlassPill`, `GlassTabBar` with puck, `GlassSegmented`, `GlassToggle`, `GlassSheet`, `GlassPopover`/`GlassMenu`, `GlassSearchField`.

### 11.3 Pseudo-code of the core node (our own words)

```
fun Modifier.glass(source, shape, spec, frozen): Modifier =
  this.then(GlassElement(...))

class GlassNode : DrawModifierNode, GlobalPositionAwareModifierNode {
  layer: GraphicsLayer                         // created in onAttach, released in onDetach
  draw():
    if (effectsInputsChanged) rebuildEffects() // saturation matrix, blur, lens shader uniforms
    needsRecord = geometryChanged || effectsChanged || (!frozen() && source.version != seen)
    if (needsRecord) layer.record(size*scale) {
        scale(scale); translate(-posInSource)  // position via localPositionOf
        inverseOf(selfLayerTransform) { drawLayer(source.layer) }
    }
    drawLayer(layer, scaledBackUp)
    drawSurfaceFill(spec.baseColor, spec.tint)  // outline-shaped
    drawContent()
    drawRim(spec)                               // cached rim layer; re-record on size/shape only
}
rebuildEffects():
    layer.colorFilter  = saturationMatrix(spec.saturation)        // free pass
    effect = blur(spec.blurPx * scale)
    effect = chain(effect, lensShader(rects, radii, height, amount))   // API 33+, always on for us
    layer.renderEffect = effect
```

Lens shader (write our own AGSL, same idea as 3.3, not a copy): inputs `content`, half-size, per-corner radii, refraction height, amount, optional dispersion; compute rounded-rect SDF; if deeper than height pass through; else displacement = amount * profile(1 - depth/height) along the SDF normal; sample `content` at `coord - displacement*normal`; optional 3-tap RGB split.

Rim shader: SDF normal dot light direction, `abs`, power `falloff`, multiplied by rim colour; draw as blurred outline stroke with Plus blend into a cached layer.

### 11.4 Fallback tiers (decide at runtime, expose in settings)
1. `Liquid`: full chain (default; minSdk 34 so always available).
2. `Frost`: blur + saturation, no lens, no rim (cheaper; used when the user turns on "Reduce Motion/Transparency" or Battery Saver, or low-RAM flag).
3. `Flat`: no capture. Fill `base @ 0.85` + hairline border `white@0.12 / black@0.08` + shadow. Also used for Roborazzi/Paparazzi screenshots (see 11.7) and when `Settings.Global.ANIMATOR_DURATION_SCALE == 0`/accessibility transparency is reduced.
Read `isLowRamDevice`, `PowerManager.isPowerSaveMode` and the accessibility "reduce transparency" analogue. Keep the decision in one `GlassPolicy` object, test it on the JVM.

### 11.5 Performance budget (start values, then profile with Perfetto/JankStats on a real mid-range phone when available)
- Total glass cost target: < 3 ms RenderThread per frame while scrolling with the bar visible on a mid-range device (assumption, not a Convx number; Convx only publishes the 47 ms -> 1.9 ms freeze result).
- Capture scale 0.30-0.50 whenever blur >= 2.5 dp; 1.0 for unblurred controls.
- Max live blurred full-width surfaces: 2 (bar + one popover). Small circular buttons: blur 0-2 dp at scale 1.0 is cheaper than any downscale bookkeeping.
- No animating of shader uniforms that force a re-record while content scrolls: freeze for the scroll duration (+900 ms safety), for route transitions (~330 ms), and for sheet drags (freeze until the morph passes ~75%, then thaw).
- Animate press with `graphicsLayer` transforms and layer alpha only (no re-record).
- Keep `effects` lambdas stable (`remember` on primitive keys) so the node does not rebuild every recomposition.
- Measure once via a macrobenchmark or at least `dumpsys gfxinfo` in CI when a device exists. Until then only JVM + screenshot tests (11.7).

### 11.6 Layout rules that avoid the owner's complaints (glass-specific)
- Glass chrome is always an overlay sibling of the scrolling content, drawn after it. Never inside the capture subtree.
- The content gets `contentPadding(bottom = barHeight + gap + navInset)` and top padding for the top bar, so the last row can scroll clear of the floating bar (fixes "tab bar covering the last row"). Use the same inset calculation for the map: Stops is full-screen, map fills the whole window edge-to-edge, controls float with 8-12 dp margin from the safe area and the map camera padding is updated to the glass-covered area so the "my location" point and attributions stay visible.
- Tap feedback never changes layout: only transform/alpha/overlay. Press stretch via `graphicsLayer` (tanh-damped, max ~4 dp growth), plus the sheen + finger spot overlay (7.1).
- Single gutter of 20 dp (16 dp on narrow phones), sibling gap 16, section gap 24, text gap 2 (tokens from 9). One source pill per row is out; use a small leading icon or secondary text colour instead (not a glass topic but the same token file).
- Concentric corner radii: card in sheet = sheet radius - inset (min 4 dp).

### 11.7 Testing (no device)
- JVM: `GlassPolicy` tiering, saturation matrix values, token contrast (WCAG ratio >= 4.5 for label on each preset in light/dark), `glassResolutionScale`-style curve, freeze clock logic (use a fake clock).
- Robolectric/Compose UI tests: composes with `Flat` tier; checks semantics, hit areas (>= 44 dp), bottom padding equals bar height + inset.
- Screenshot tests (Roborazzi or Paparazzi): RenderEffect/AGSL do not render in the JVM layoutlib/Robolectric native graphics for every version, so snapshot the `Flat` tier and the rim/shadow drawing (plain Canvas); for `Liquid` rely on an on-device gold test later. Open the PNGs and judge spacing, covered last rows, clipped puck, and rim visibility on light and dark.
- Keep the AGSL strings in a `.agsl` raw resource per shader and unit-test uniform packing (array sizes, ordering) without compiling the shader.

### 11.8 Build order proposal
1. Tokens + `GlassPolicy` + `Flat` surfaces + shapes + press feel (works without any capture; all four apps usable).
2. Source/sampler with blur + saturation (Frost tier), freeze, bar and buttons over lists and over the Stops map (the map view is a SurfaceView/TextureView, so check whether it can be captured into a `GraphicsLayer` at all; if not, Stops glass is Frost-from-static-snapshot or Flat with a translucent tint, and the same fallback applies to WebViews. This is an open risk, not verified here).
3. AGSL lens + rim + shadows (Liquid tier).
4. Puck/segmented/toggle and popover/menu materials.
5. Pool only if profiling needs it.

## 12. Open questions for the integrator
- Can the Stops map surface be sampled by a `GraphicsLayer` (Convx special-cases WebView and skips recording on those routes, `AppRootContent.kt:~900`)? Needs a spike on MapLibre/own tile renderer. If the map is drawn by our own Compose/Skia tile painter (rovsti_tiles), capture works natively, so the choice of map view affects the glass design.
- Upstream licence check for Kyant0 backdrop + capsule at the version we pin (this report saw only the vendored headers).
- Font: Convx bundles SF Pro, which is not redistributable under MIT/Apache; use the system font or an OFL font.
