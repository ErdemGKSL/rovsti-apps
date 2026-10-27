---
name: liquid-glass-gpui
description: Build iOS-style "liquid glass" interfaces with spring physics in GPUI (Zed's Rust UI framework). Covers refractive glass surfaces (edge-band lens refraction, light blur, saturation/vibrancy, two-sided specular rim, dispersion, press glow) through gpui-fusion's WGSL backdrop shaders, and interruptible spring animation (analytic solver, motion tokens, squash and stretch, fling projection, fixed-duration spring choreography, optional spring-animated window resize). Also covers iOS design tokens and component recipes (floating tab bar with puck, glass buttons, segmented control, sheets, toggles, sidebars) and fallbacks for upstream GPUI. Use when the user wants glassmorphism, Apple/iOS 26-like liquid glass, frosted or refractive panels, backdrop blur, custom shaders, or spring/bouncy motion in a GPUI or gpui-fusion app, or wants to recreate the look of an iOS-style app (e.g. Convx) in Rust. Turkish triggers: "liquid glass", "cam efekti", "buzlu cam", "yay animasyonu", "spring animasyon", "iOS tarzı arayüz", "GPUI tasarım".
---

# Liquid glass + spring motion in GPUI

This skill reproduces the *style* of modern iOS apps (for example Convx, an Android music player whose
UI imitates iOS liquid glass) in GPUI with original code: refractive glass chrome
floating over content, and interruptible spring motion everywhere. It does not
copy any app. The look is a common design language, and everything here is derived from
the underlying maths and public platform conventions.

## Step 0: Pick the path (do this first)

```
Does the project need real refraction or in-window blur?
├─ yes → PATH A: gpui-fusion (GPUI fork with WGSL backdrop shaders)
│        Read references/01-setup-architecture.md, then use templates/
└─ no / can't switch GPUI → PATH B: upstream GPUI approximation
         Read references/08-fallbacks-performance.md §4. Springs work fully on both paths.
```
- Check `Cargo.toml`. If `gpui` comes from `zed-industries/zed` or crates.io, the project is on
  upstream. Custom shaders do not exist there. Tell the user, and offer to move to gpui-fusion (all gpui
  crates must come from the same fork revision) or to use Path B.
- Never invent GPUI APIs. Both forks move fast. When unsure, clone and grep:
  `git clone --depth 1 https://github.com/cradiy/gpui-fusion` (or `zed-industries/zed`, `crates/gpui`).

## Step 0.5: Setup questions (ask the developer before scaffolding)

Some features are product decisions, not defaults. When you set up the project or first add
the kit, ask these in **one** round (use the AskUserQuestion tool if available), then
build only what was chosen:

| Question | Options | If yes |
|---|---|---|
| **Animated window resize?** Should floating chrome glide to its new place when the window resizes, or reflow instantly? | animated (recommended for media/showcase apps) / instant (recommended for editors and dense tools) | `SpringRect` + `motion_tokens::resize()`, `ANIMATED_RESIZE = true`. Read **references/09-animated-resize.md** |
| Animated grid reflow? (only if the app has card grids or galleries) | yes / no | `gpui_effects::animated_layout` (09 §5) |
| Theme | follow the OS / light only / dark only | `Theme::from_appearance` + `observe_window_appearance` (04 §8) |
| Reduced-transparency setting in the app? | yes / no | settings flag → `paint_glass_fallback` (08 §2) |

Do not ask about things that have a clear default: springs, fallbacks and reduced-motion
support are always included. If the developer already answered in their request
("no resize animation", "dark only"), do not ask again. Record the answers as constants or
config near the app's entry point, for example `const ANIMATED_RESIZE: bool = …;`, so the choice is
visible and easy to flip later.

## Step 1: Bring in the kit (Path A)

`templates/` is a verified, compiling crate (`liquid_glass_kit`). It was checked against gpui-fusion
rev `cc05989` on Linux/wgpu with Rust 1.98.

| File | What it gives you | GPUI needed? |
|---|---|---|
| `templates/Cargo.toml` | git deps pinned to one rev **plus the required `[patch.crates-io]` block** | — |
| `templates/src/spring.rs` | analytic damped spring `SpringParams`/`Spring`/`FrameClock`, `Timeline` (fixed-duration choreography), `SpringRect` (optional animated resize), with tests | no (std only) |
| `templates/src/motion_tokens.rs` | named springs (`select`, `press`, `theme_fade`, `container_settle`, `resize`…), bezier curves, `spring_easing`, `morph_axes`, `project`, `rubber_band`, `squash_stretch`, `elastic_offset`/`elastic_scale` | no |
| `templates/src/tokens.rs` | `Theme`, iOS colours, `mix` / `Colors::lerp` (RGB cross-fades), radii, metrics, type ramp, shadows (`glass_mix`) | yes |
| `templates/src/glass.rs` | `GlassSpec` presets and `lerp`, `paint_glass`, `effect_bounds`, `glass()`/`glass_interactive()` divs, fallback, `to_fusion()`, `force_backdrop_support` (tests only) | fork |
| `templates/shaders/liquid_glass.wgsl` | the glass material (SDF, arc lens, dispersion, vibrancy, rims, glow) | fork |
| `templates/examples/glass_demo.rs` | **Tidepool**, a glass submarine HUD over a living ocean: a dive-zone tab bar whose puck is draggable and morphs into a lens (the bar grows and leans, the zone palette cross-fades), a depth HUD ↔ tab bar morph, a sonar button (glow, tanh elastic pull, release rings), a day/night cross-fade, and optional animated resize | fork |
| `templates/tests/shader_validates.rs` | naga validation of the spliced shader | fork |
| `templates/tests/glass_snapshot.rs` | offscreen GPU render to PNG (no window) | fork + GPU |
| `templates/tests/headless_frames.rs` | drives the whole demo headlessly with real input events and real time, writes a PNG per moment and a contact sheet, and asserts on the geometry (`--features headless-tests`) | fork + GPU |
| `assets/expected_glass_snapshot.png` | what the snapshot should look like | — |
| `assets/demo_tidepool.png`, `assets/demo_frames_contact_sheet.png` | what the demo and its animation frames should look like | — |

Copy the whole folder into the workspace, or copy the modules into an existing crate. Then
fix the `use crate::...` paths. Keep `include_str!("../shaders/liquid_glass.wgsl")`
pointing at the shader.

## Step 2: Build surfaces

Minimal glass surface (Path A):
```rust
use liquid_glass_kit::{glass::{glass, GlassSpec}, tokens::{Theme, shadow}};
glass(GlassSpec::chrome(theme))
    .rounded_full().h(px(56.)).px(px(16.))
    .shadow(shadow::glass(theme.is_dark()))
    .child(/* sharp labels, painted after the glass */)
```
How it works: `paint_glass` runs inside `on_paint_before_children`. It samples what was
already painted, runs the WGSL material, and then the children paint on top.
- Optics and maths, tuning, and writing variants: **references/02-glass-shader.md**
- Exact fork APIs (`PaintBackdropEffect`, `LiquidGlassAppearance`, hooks): **references/03-gpui-fusion-api.md**
- Colours, presets, radii, type, shadows: **references/04-design-tokens.md**

## Step 3: Add motion

```rust
// state: clock: FrameClock, x: Spring = Spring::new(0., motion_tokens::select()).with_epsilon(0.1)
let dt = self.clock.tick();
if self.x.tick(dt) | self.press.tick(dt) { window.request_animation_frame() } else { self.clock.reset() }
// events: self.x.set_target(new_x); cx.notify();
```
- Spring parameterisations (upstream `SpringConfig` damping is **not** ζ), driving
  patterns, token table, choreography: **references/05-spring-motion.md**
- Press, puck drag, squash and stretch, fling projection, rubber banding, sheets: **references/06-interactions.md**
- Tab bar, buttons, segmented control, toolbar, sidebar, sheet, toggle, slider, menu, morph:
  **references/07-components.md**
- *Only if the developer opted in (Step 0.5):* layout that glides on window resize, edge pinning,
  relative-unit selection, FLIP grids: **references/09-animated-resize.md**

## Step 4: Ship it safely

- Always handle `!window.supports_backdrop_blur()` with `paint_glass_fallback`.
- Respect `window.prefers_reduced_motion()`.
- Keep 8 or fewer glass surfaces, blur 2–3 px for chrome, and stop animation frames when springs settle.
- Details and the Path B recipe: **references/08-fallbacks-performance.md**

## Non-negotiable rules

1. **Paint glass before the children, during paint.** Use `on_paint_before_children`, the first `canvas` child,
   or a custom `Element`. Never paint it from `render`.
2. **Upload device pixels.** Multiply every logical length by `window.raster_scale_factor()`.
   Give the shader an unsnapped centre and size. Paint at `glass::effect_bounds` (shape + rim width + 1 device px).
3. **Liquid glass means little blur and an edge-band refraction plus saturation.** Heavy blur makes it frosted glass.
4. **Dark-mode glass is a white lift (0.15α)**, not black smoke. Light mode uses #F5F5F7 at 0.40α. Both themes get a thin, symmetric dark outline on the edge, never a one-sided shadow.
5. **Everything the user can interrupt is a spring.** Use the analytic step (never Euler), keep the velocity on
   retarget, and inject the release velocity. Positions are critically damped; sizes may overshoot.
6. **Concentric radii** for nested shapes: `inner = outer − inset`.
7. **Verify by looking.** After shader edits, run `cargo test --test shader_validates`, then
   `cargo test --test glass_snapshot -- --ignored --nocapture` and look at the PNG. After
   motion changes, run `cargo test --features headless-tests --test headless_frames -- --ignored`
   and look at `target/frames/contact_sheet.png` (08 §5).
8. **Only one gpui source** per build (all crates from the same fork rev), and copy the fork's
   `[patch.crates-io]`. The toolchain must be recent stable: `E0658` inside gpui means `rustup update`.
   A debug build of gpui needs several GB of disk. Do not point `CARGO_TARGET_DIR` at a tmpfs `/tmp`.
9. **Optional features are asked, not assumed** (Step 0.5). Animated resize in particular is a
   product decision.

## Where the numbers come from

The parameter values (presets, motion tokens, rim and shadow values) were measured from
Convx (`cosmictaserdev-creator/Convx`, Kotlin/Compose with AGSL shaders via Kyant0/backdrop)
and translated into GPUI terms. The API knowledge comes from gpui-fusion
(`cradiy/gpui-fusion`, MIT/Apache-2.0) and upstream Zed GPUI (Apache-2.0). All code in
`templates/` was written fresh for this skill. Credit those projects if you borrow more directly from them.
