# 08 · Fallbacks, accessibility, performance, and stock GPUI

## Contents
1. When glass cannot render
2. Reduced transparency and reduced motion
3. Performance budget
4. Path B: approximating the look on upstream GPUI (no custom shaders)
5. Testing and debugging
6. Common mistakes

---

## 1. When glass cannot render

`window.supports_backdrop_blur()` is false on some Linux surfaces, on software adapters
and on unsupported backends. Backdrop paints are then **silently skipped**.
Always branch:
```rust
if window.supports_backdrop_blur() && !settings.reduce_transparency {
    paint_glass(bounds, corners, &spec, interaction, window);
} else {
    paint_glass_fallback(bounds, corners, &spec, window);   // tint + 0.55α, 0.5px hairline
}
```
The fallback must keep the **same geometry, radius and contrast**, so the layout and
legibility do not change. Only the optics disappear.

## 2. Reduced transparency and reduced motion

- **Reduce transparency**: there is no portable GPUI query, so expose an app setting. When it is on,
  use the fallback everywhere and raise label contrast.
- **Reduce motion**: `window.prefers_reduced_motion()` (fork) or `cx.reduce_motion()`
  (upstream). Snap the springs. Use cross-fades of 150 ms or less. Disable squash/stretch, the glow
  breathing, parallax and the morph choreography (show the end state instead).
- **Increase contrast**: raise `dark_rim` and the rim alpha, and add a 1 px label-coloured
  border at 0.2α.
- Hit targets stay at least 44 px even when the glass is visually smaller.

## 3. Performance budget

Each glass surface costs the following, per frame it is visible:
- a capture of the scene behind it (copy),
- a Gaussian blur pass sized by `blur_radius` (larger radius means more taps),
- a full-screen-quad fragment pass over its bounds (2 samples, plus 2 more with dispersion).

Guidelines:
- Keep **5–8 glass surfaces on screen or fewer**. Merge small adjacent controls into one pill.
- Keep chrome blur at 2–3 px. A large blur costs the most and looks like frosted glass anyway.
- Only animate the glass while it moves. When springs settle, stop
  `request_animation_frame`. A static glass surface is only re-rendered when something else
  makes the window redraw.
- **Avoid glass over video or constantly animating content** unless you need it. Every frame
  re-runs the passes.
- Cache shaders (`OnceLock`) and avoid rebuilding `GlassSpec` from strings each frame.
- Do not stack more than 2 levels of glass.
- Measure with the fork's `WgpuOffscreenRenderer::memory_stats()`, or with the platform GPU profilers
  (Xcode Metal capture, RenderDoc for Vulkan).

## 4. Path B: approximating the look on upstream GPUI

Upstream GPUI has no custom shaders and no in-window backdrop sampling. You can
still get about 70% of the feel:

| Ingredient | How |
|---|---|
| Blur of what is behind | `window.set_background_appearance(WindowBackgroundAppearance::Blurred)` with a transparent root. This blurs the *desktop* behind the window (macOS, Windows Mica/Acrylic, some Wayland compositors). It cannot blur in-window content. |
| Glass fill | translucent `bg()` using the theme glass fill (white 0.15α dark, #F5F5F7 0.40α light) |
| Fake refraction rim | an inner 1 px border with a **gradient**: top-left light (white 0.5α) to bottom-right lighter (white 0.15α), via `border_color` and a gradient overlay div |
| Dark rim | a second outer 1 px border at black 0.08–0.3α (light mode) |
| Specular sheen | an absolutely positioned child with `linear_gradient(160°, white 0.25α → transparent 40%)`, clipped by `rounded_*` + `overflow_hidden` |
| Depth | `BoxShadow` as in the tokens, plus an inset shadow (`inset: true`) of black 0.15α, 24 px blur, offset y 24 |
| Press glow | a radial-ish highlight: a small `rounded_full` white 0.15α div at the pointer, blurred via `BoxShadow` spread |
| Motion | full fidelity: `with_spring` + `SpringConfig` (convert with `SpringParams::physical`), or the kit's `spring.rs` |

Be honest with the user that Path B gives no refraction or in-window blur. If those are
required, switch to gpui-fusion.

## 5. Testing and debugging

| Check | Command |
|---|---|
| Shader parses and validates as the renderer sees it | `cargo test --test shader_validates` |
| Look at the real pixels without a window | `cargo test --test glass_snapshot -- --ignored --nocapture` → `target/glass_snapshot.png`; compare with `assets/expected_glass_snapshot.png` |
| Spring maths (settling, overshoot, frame-rate independence, retarget) | `cargo test --lib` |
| **Animations, frame by frame, headless**: real input events and real time, PNG per moment, geometry asserts | `cargo test --features headless-tests --test headless_frames -- --ignored --nocapture` → `target/frames/*.png` + `contact_sheet.png`; compare with `assets/demo_frames_contact_sheet.png` |
| The full interactive demo | `cargo run --example glass_demo` |

How `tests/headless_frames.rs` works, so you can copy it to test your own views:
- It uses `gpui::HeadlessAppContext` (behind gpui's `test-support` feature). The template keeps
  that feature behind an opt-in `headless-tests` cargo feature, so it never leaks into
  normal builds.
- The fork ships a headless renderer for macOS only. A roughly 20-line adapter implements
  `PlatformHeadlessRenderer` with `gpui_wgpu::WgpuOffscreenRenderer` (`render_rgba`,
  `resize`, `sprite_atlas`), and text uses `gpui_wgpu::CosmicTextSystem`.
- The test window answers `false` to `supports_backdrop_blur()`. Call
  `glass::force_backdrop_support(true)` in tests only, or you will screenshot the fallback.
- To drive a frame, call `update_window(.., |_, w, cx| { w.refresh(); w.draw(cx).clear(); })`, then
  `capture_screenshot`. `run_for(ms)` draws every 16 ms of real time, so springs and timelines
  advance exactly as on a display. Inject input with `window.dispatch_event(PlatformInput::MouseDown(..), cx)`.
- The headless window is HiDPI (2×), which also checks your device-pixel conversions.
- Include the example as a module (`#[path = "../examples/glass_demo.rs"] mod demo;`)
  and expose a few `pub` hooks (`toggle_hud`, `rects`) for assertions.

Debugging tricks inside the shader (temporarily):
- `return vec4(vec3(edge), 1.0);` shows the refraction band.
- `return vec4(n * 0.5 + 0.5, 0.0, 1.0);` shows the normals. Look for corner seams.
- `return vec4(abs(displacement) / 32.0, 0.0, 1.0);` shows the displacement magnitude.
- If the glass is invisible, check that `supports_backdrop_blur()` is true, that the shader was cached as
  failed (watch the logs for a pipeline error), that the bounds are non-zero, and that the paint
  happens in a paint hook.

## 6. Common mistakes

- Shipping without a fallback. The text then floats on a transparent panel on some Linux setups.
- Leaving a dozen glass chips on screen, each with a 20 px blur. The frame time doubles.
- Keeping a continuous `request_animation_frame` running for a static UI. The battery drains.
  (The Tidepool demo animates its backdrop continuously on purpose. It pauses under reduced
  motion. Do not copy that into chrome that sits over static content.)
- Promising refraction on upstream GPUI.
