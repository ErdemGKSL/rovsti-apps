---
name: convx-motion-compose
description: >-
  Fluid iOS 26 liquid-glass UI in Kotlin + Jetpack Compose (no GPUI) for Rovsti: interruptible springs, drag/fling
  with velocity hand-off, squash and stretch, shared elements, predictive back, AGSL/RenderEffect glass with
  fallbacks, popovers, context menus, sheets, dialogs, toasts, tab bar, large titles, insets, lists, text fields,
  haptics, 120 Hz performance rules and a screen review checklist. From Convx (GPL-3.0, ported with attribution) and
  glisse (MIT: portable with attribution). Use for any Compose screen, animation, overlay or glass work, or when UI
  feels janky or not like iOS. Triggers: fluid animation, spring, liquid glass compose, popover, context menu,
  bottom sheet, tab bar, jank; akıcı animasyon, yay animasyonu, cam efekti, buzlu cam, açılır menü, bağlam menüsü,
  alt sayfa, sekme çubuğu, takılma, iOS tarzı arayüz.
---

# Convx-grade motion, glass and overlays in Compose (Rovsti)

## 0. Licensing rule (read before porting)

Rovsti is **GPL-3.0-or-later** (root `LICENSE`). Convx is GPL-3.0, so its code is portable close to verbatim.

| Source | Licence | What you may do |
|---|---|---|
| **Convx** (`cosmictaserdev-creator/Convx`) | **GPL-3.0** | Port it as close to verbatim as possible (structure, algorithms, constants, shaders, names). Adapt only package names, our theme/tokens, our data models; drop music-player specifics. Keep the original header and add the line below. |
| Apache-2.0 upstreams Convx vendored (Kyant0 backdrop, AndroidLiquidGlass kmp, Capsule, compose-miuix-ui, compose-hig/Compose Cupertino) | Apache-2.0 | Port Convx's copy; keep the original Apache header and copyright, and add the Convx line. `android/THIRD_PARTY_NOTICES.md` lists each with the full Apache text. |
| Flamingo, MeloX, BitChord, Square (GPL-3.0, in Convx `NOTICE`) | GPL-3.0 | Music-player specific: not ported. |
| **glisse** (`github.com/0kzh/glisse`) | **MIT**, Copyright (c) 2026 Kelvin Zhang | May be ported directly (TS -> Kotlin/Compose/AGSL) with the glisse header; `android/THIRD_PARTY_NOTICES.md` carries the full MIT text (`references/glisse-LICENSE.txt`). |
| Public constants (UIKit 0.998 deceleration, rubber-band 0.55, cubic-bezier control points, iOS dp sizes) | facts | Free to use. |

Required header lines (Kotlin or AGSL), placed after the upstream header if there is one:

```
// Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <original path in Convx>
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
```

Written as `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`. `LicenceHeaderTest`
(`android/design/src/test/kotlin/dev/rovsti/design/LicenceHeaderTest.kt`) enforces it for every file under `backdrop/`,
`shapes/` and `ios/`, every file naming Convx in its header, and every glisse file.

Code blocks in `references/` are either Rovsti's own sketches or glisse translations (marked); for Convx features the
references point to the Convx file to port.

## 1. Project constraints

- Kotlin + Jetpack Compose, **no GPUI**, **no Material overlays** (menus, dialogs, sheets are ours). minSdk 34
  (`crates/rovsti_android/kotlin/dev/rovsti/bridge/MediaShare.kt:13`), so `RenderEffect` (31) and AGSL
  `RuntimeShader` (33) are always available on device. Robolectric/Paparazzi may not render them: screenshots use the
  Flat tier (see `references/glass.md` section 7).
- Domain logic stays in the Rust crates; Kotlin UI is thin. Every number policy (placement, projection, collapse
  fraction, rubber band) is a pure Kotlin function with a JVM test.
- Old GPUI UIs (`apps/*`, `crates/rovsti_ui`, `rovsti_wa_ui`, `liquid_glass_kit`) are a feature inventory only.
- Verify every Compose API against the pinned version's sources before relying on it (names below were current in
  Compose 1.7-1.10; overscroll, haptic types and shadow APIs moved in 1.8-1.9).

## 2. The seven laws (why Convx feels fluid)

1. **Springs are retargeted, never restarted.** One `Animatable` per physical quantity; new targets call `animateTo`
   on the same object so value and velocity carry over (`motion.md` 3).
2. **The finger owns the value during a drag** (`snapTo`, undispatched), **the spring owns the release**, seeded with
   the release velocity, and the target is chosen from the **projected** rest point (0.998 rule) (`motion.md` 4-5).
3. **Per-frame values never enter composition.** Read them in `graphicsLayer {}`, `draw*`, `offset {}`, layout
   lambdas; pass `() -> Float` (`performance.md` 1).
4. **Overshoot belongs to surfaces, not to content.** Containers and thumbs bounce a little; text and icons ride
   critically damped curves (`motion.md` 2, 6).
5. **Overlays never own layout.** Menus, popovers, sheets, toasts live in one root overlay host; triggers keep their
   size; press feedback is transform/alpha only (`overlays.md` 1).
6. **A menu is a morph, not a fade**: it grows out of its anchor per axis (width leads, height lags), with a clearing
   blur on open and a short plain exit (`overlays.md` 3-4).
7. **Expensive glass is frozen while things move** and falls back gracefully (`glass.md` 5-6).

## 3. Where to look

| Task | Read |
|---|---|
| Any animation, spring token, drag, fling, swipe, sheet physics, shared element, predictive back, reduce motion | `references/motion.md` |
| Glass surface, blur, refraction (AGSL), rim light, tokens, fallbacks, Stops map glass | `references/glass.md` |
| Context menu, popover, dropdown, sheet, alert, action sheet, toast, text-selection toolbar, overlay host, a11y of overlays | `references/overlays.md` |
| Tab bar, puck, segmented control, switch, slider, large titles, nav transitions, insets, lists/rows/chips, text fields, search, haptics, Stops full-screen map layout | `references/navigation-layout.md` |
| Recomposition, lists, images, tiles, ambient animation, frame rate, threading/JNI, startup, perf tests | `references/performance.md` |
| Reviewing or finishing any screen | `references/checklist.md` |

## 4. Owner complaints this skill exists to prevent

| Complaint (GPUI apps) | Rule | Reference |
|---|---|---|
| Messy layout: overlapping avatar stacks, wrapping chips, big source pill per row, inconsistent spacing | One avatar per row, single non-wrapping chip row, source shown once (header/icon/chip), one spacing token set | `navigation-layout.md` 6 |
| Floating tab bar covered the last row | Measured bar height published as content inset; lists use `contentPadding` | `navigation-layout.md` 2 |
| Text boxes worse than an HTML input | Stock `BasicTextField(TextFieldState)` + platform selection, handles, magnifier, toolbar, IME | `navigation-layout.md` 7 |
| No popovers/context menus; tapping pushed content | Root overlay host; press = `graphicsLayer` only | `overlays.md` 1-4 |
| Stops map in a small box | Full-screen map root layer, all controls floating, sheet with background interaction | `navigation-layout.md` 9 |

## 5. Workflow for a new screen

1. Sketch layers: content layer (list or map, full window) / chrome layer (bars, floating buttons) / overlay host.
2. Pull tokens from `RovstiMotion`, `GlassTokens`, spacing tokens. No raw numbers at call sites.
3. Build with the recipes; every gesture uses the drag/release pattern; every popover goes through the host.
4. Add JVM tests for new pure functions, a Compose test for gestures (manual clock), Roborazzi/Paparazzi screenshots
   (light/dark, font scale 1.0/2.0, overlay open, last row visible). **Open the PNGs with the Read tool and judge them.**
5. Run `references/checklist.md`. Check licence headers on any file that took glisse code.
