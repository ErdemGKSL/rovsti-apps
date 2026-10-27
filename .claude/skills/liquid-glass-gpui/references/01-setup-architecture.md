# 01 · Setup and architecture

## Contents
1. Which GPUI can do what
2. Adding gpui-fusion to a project
3. How backdrop effects work inside the renderer
4. Platform support
5. Common mistakes

---

## 1. Which GPUI can do what

| Capability | Upstream GPUI (zed-industries/zed `crates/gpui`) | gpui-fusion (cradiy/gpui-fusion fork) |
|---|---|---|
| Custom fragment shaders | ✗ | ✓ `EffectShader`, `BackdropShader` (WGSL) |
| Backdrop blur of in-window content | ✗ | ✓ `paint_backdrop_blur`, `paint_backdrop_effect` |
| Refraction / lens / dispersion | ✗ | ✓ (custom shader or `gpui_effects::LiquidGlass`) |
| Paint hook before children | ✗ (write a custom `Element`) | ✓ `Div::on_paint_before_children` |
| Window-level OS blur | ✓ `WindowBackgroundAppearance::Blurred` / `MicaBackdrop` | ✓ same |
| Box shadows, gradients, rounded quads | ✓ | ✓ |
| `with_animation` (duration + easing) | ✓ | ✓ (older API: no `with_max_fps`) |
| Physical springs `with_spring`, `SpringConfig` | ✓ | ✗ → use `templates/src/spring.rs` |
| `window.request_animation_frame()` | ✓ | ✓ |

**Rule:** real liquid glass needs gpui-fusion. In upstream GPUI you can only
approximate it (see `08-fallbacks-performance.md` §4, "Path B").

gpui-fusion is a *fork*, not a plugin. You cannot mix `gpui` from crates.io
or from Zed with `gpui_effects` from the fork. Every gpui crate must come from
the same fork revision.

## 2. Adding gpui-fusion to a project

The fork is not published to crates.io. Use git dependencies pinned to one
revision. `templates/Cargo.toml` is a complete working manifest. It was verified
with `cargo check` on Linux at rev `cc05989`.

```toml
[dependencies]
gpui         = { git = "https://github.com/cradiy/gpui-fusion", rev = "<REV>", default-features = false }
gpui_effects = { git = "https://github.com/cradiy/gpui-fusion", rev = "<REV>" }  # optional: ready-made glass
gpui_platform = { git = "https://github.com/cradiy/gpui-fusion", rev = "<REV>", features = ["font-kit", "wayland", "x11"] }

# REQUIRED. The fork's workspace patches these crates, and a `[patch]` table
# inside a dependency is ignored. Copy the fork's root Cargo.toml [patch.crates-io] block.
[patch.crates-io]
async-process = { git = "https://github.com/zed-industries/async-process.git", rev = "0b6d6713570af61806e1e5cb40e0f757cb93fd9d" }
async-task    = { git = "https://github.com/smol-rs/async-task.git",           rev = "b4486cd71e4e94fbda54ce6302444de14f4d190e" }
windows-capture = { git = "https://github.com/zed-industries/windows-capture.git", rev = "f0d6c1b6691db75461b732f6d5ff56eed002eeb9" }
calloop       = { git = "https://github.com/zed-industries/calloop" }
```

Notes:
- **Toolchain.** The fork tracks recent stable Rust (edition 2024). Rust 1.96
  failed with `E0658 result_option_map_or_default`, and 1.98.1 worked. If you see
  E0658 errors inside `gpui`, run `rustup update stable`.
- **Disk space.** A debug build of gpui-fusion and its dependencies (wgpu, naga, image,
  fonts) takes several GB in `target/`. On systems where `/tmp` is a RAM tmpfs (often
  16 GB or less), `CARGO_TARGET_DIR=/tmp/...` fails halfway with
  `Disk quota exceeded (os error 122)`. Use the project's `target/` or
  `~/.cache/<name>-target` instead.
- **The patch block changes over time.** Re-copy it from the fork's root
  `Cargo.toml` whenever you bump `rev`. Unused-patch warnings are harmless.
- **gpui_platform features.** Use `font-kit` on macOS. On Linux use `wayland` and/or `x11`.
  On macOS during shader development, also enable `runtime_shaders` to skip
  precompiled Metal shaders.
- **Entry point.** Use `gpui_platform::application().run(|cx: &mut App| { cx.open_window(..) })`.
  The fork does not use upstream's `Application::new()`.
- **The kit as a crate.** `templates/` is itself a crate (`liquid_glass_kit`). You can copy the
  whole folder into a workspace, or copy single modules. `spring.rs` and
  `motion_tokens.rs` depend on nothing but `std`, plus `crate::spring` in motion_tokens.

To learn the fork's APIs, clone it and read the sources. Do not guess:
```
git clone --depth 1 https://github.com/cradiy/gpui-fusion
crates/gpui/src/backdrop.rs          BackdropShader, PaintBackdropEffect
crates/gpui/src/backdrop.wgsl        wrapper your WGSL is spliced into
crates/gpui/src/effect.rs            EffectUniforms (8 × vec4), EffectShader
crates/gpui/src/window/effects.rs    paint_backdrop_blur / paint_backdrop_effect / paint_effect
crates/gpui_effects/src/surface/     liquid_glass.rs, glass.rs (frosted), backdrop.rs
crates/gpui_effects/examples/        liquid_glass.rs, frosted_glass.rs, lens.rs …
uic/src/components/glass/            segmented control: springs + glass in practice
docs/liquid_glass.md
```
Run the reference examples with `cargo run -p gpui_effects --example liquid_glass`
(add `--features gpui_platform/runtime_shaders` on macOS).

## 3. How backdrop effects work inside the renderer

```
frame N scene, in paint order
 ├─ background, content …                  ← painted first
 ├─ BackdropEffect primitive (your glass)  ← painted by paint_backdrop_effect
 │    renderer: copy what has been drawn so far → t_raw_backdrop
 │              Gaussian blur(blur_radius)     → t_blurred_backdrop
 │              run backdrop_effect() per fragment within bounds
 └─ children of the glass (text, icons)    ← painted after, so they stay sharp
```

Consequences:
- **Glass only sees what was painted before it, in the same window.** It cannot see
  the desktop or other windows. To get glass over the desktop, combine it with
  `WindowBackgroundAppearance::Blurred`.
- **Paint order is everything.** Call the glass painter in `on_paint_before_children`
  (or before painting children in a custom `Element::paint`). If you paint it
  after the children, it blurs and refracts your own labels.
- **Glass over glass works.** A later surface samples the earlier surface's result.
  This is how the tab-bar puck refracts the bar under it.
- **Every surface costs its own capture and blur pass.** See
  `08-fallbacks-performance.md`.
- **Coordinates.** `BackdropInput.position` is the fragment's **device-pixel** position in
  the window. `BackdropInput.uv` is 0..1 across the (snapped) effect bounds.
  Displacements passed to `sample_raw_backdrop` / `sample_blurred_backdrop` are
  in **device pixels**. Multiply every logical length you upload by
  `window.raster_scale_factor()`.
- **Snapping.** The renderer snaps effect bounds to device pixels. Upload the
  unsnapped centre and size yourself, then compute the SDF from `position`.
  If you use `uv * size` instead, motion jitters by a pixel. Dilate the bounds by
  about 1 device px so the shader's anti-aliased edge is not clipped, plus anything
  the shader draws outside the shape (the template's dark outline needs the rim width
  on top of that, see `glass::effect_bounds`).
- **Clipping.** `PaintBackdropEffect::corner_radii` clips the result. If your shader
  returns its own coverage in alpha, as the template does, you can leave it at the
  default. An ancestor's content mask (overflow hidden) still clips the effect.

## 4. Platform support (fork rev cc05989)

| Backend | `window.supports_backdrop_blur()` | Shader path |
|---|---|---|
| macOS (Metal) | always `true` | WGSL → MSL via naga at runtime |
| Linux Wayland/X11 (wgpu: Vulkan/GL) | `true` if the surface supports `COPY_SRC`; otherwise an offscreen target is used | WGSL directly |
| Windows (DirectX 11) | `true` on the native DirectX path | WGSL → HLSL via naga |
| Web / Android | delegates to the renderer | WGSL |

When the check is false, backdrop paints are **no-ops**. You must paint a fallback
yourself (`paint_glass_fallback` in the template). If a shader fails to compile,
the renderer caches it as failed and the surface silently disappears. Validate
shaders with `templates/tests/shader_validates.rs`.

## 5. Common mistakes

- **Mixing GPUI sources.** Using upstream Zed `gpui` together with fork `gpui_effects` gives
  type mismatches like "expected `gpui::Window`, found `gpui::Window`".
- **Forgetting `[patch.crates-io]`.** You get resolution errors or duplicate `async-task`
  versions.
- **Logical px in shader uniforms.** The glass looks right at 1× and is half-strength
  on Retina or HiDPI screens.
- **Painting glass in `paint` after `children`.** Your own text gets blurred.
- **Expecting glass to show the desktop.** It only samples the window's own scene.
