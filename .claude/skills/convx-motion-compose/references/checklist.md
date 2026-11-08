# Review checklist for any new or changed Rovsti Compose screen

Run it before calling a screen done. Every unchecked box needs a fix or a written reason in the PR description.
Section numbers point into the other reference files.

## A. Licence and provenance

- [ ] Every file with Convx code keeps its upstream header and has `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`; `LicenceHeaderTest` passes.
- [ ] Every file containing glisse-derived code starts with
      `// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang`
      (shader files too; lens/rim shaders also keep the Aave / kube.io credit line).
- [ ] `android/THIRD_PARTY_NOTICES.md` contains glisse's full MIT text (`references/glisse-LICENSE.txt`) and every ported
      source (Convx, Kyant0 backdrop/AndroidLiquidGlass/Capsule, compose-miuix-ui, compose-hig, Haze) with its licence.
- [ ] No GPUI, no Material menus/dialogs/sheets, no font that is not redistributable (no SF Pro).

## B. Layout (owner complaints first)

- [ ] Layers: content (full window) -> chrome (siblings drawn after) -> overlay host. Nothing chrome-like inside the glass source.
- [ ] Lists take `LocalContentInsets` as `contentPadding`; the **last row scrolls fully above the tab bar** (screenshot at
      the end of the list proves it) (`navigation-layout.md` 2).
- [ ] One avatar per row; no overlapping avatar stacks.
- [ ] No source pill on every row; the source is shown once (header, glyph, chip).
- [ ] Filter chips are one non-wrapping horizontal row.
- [ ] Spacing uses only the token set (gutter 16, gaps 4/8/12/16/24, text gap 2); no ad-hoc dp values.
- [ ] Rows use `heightIn(min)`; nothing clips at font scale 2.0 (screenshot).
- [ ] Stops: the map is full screen edge to edge; every control floats on top; the sheet allows background interaction at
      small detents; no map in a box (`navigation-layout.md` 9).

## C. Motion (`motion.md`)

- [ ] All specs come from `RovstiMotion`; every spring has a `visibilityThreshold`.
- [ ] Pressing anything changes only transform/alpha/overlay: **no layout shift** (bounds test before/after press and open).
- [ ] Drags: `snapTo` undispatched during the drag, release `animateTo(target, spec, initialVelocity)`, target from `Fling` projection.
- [ ] Interruptions retarget the same `Animatable`; press state deflates in `finally`; late callbacks guarded.
- [ ] Overshoot only on surfaces; text and icons ride critically damped curves.
- [ ] Predictive-back-scrubbed transitions are tweens; sheets preview 35% on back.
- [ ] Reduce motion goes through `.gated(...)`: no squash, parallax or bounce when on.

## D. Overlays (`overlays.md`)

- [ ] Every popover/menu/sheet/alert/toast is an `OverlayHost` entry, not a `Popup`/`Dialog` (or the exception is written down).
- [ ] Anchor rect captured once at open into a plain value; placement via `placePanel` (inset-aware, below then above then clamp).
- [ ] Menus morph out of their anchor (per-axis scale, origin at anchor, clearing blur on open, short plain exit).
- [ ] Press-slide-release works on menus (and from the trigger for tap-open menus); haptic tick on row change.
- [ ] Long-press menus have a TalkBack equivalent (`onLongClick` semantics and custom actions).
- [ ] Modal entries: page behind cleared from semantics, `paneTitle`, back closes only the top entry, focus/IME cleared on close.
- [ ] Exit animations play for programmatic dismiss too.

## E. Glass (`glass.md`)

- [ ] Glass only on chrome (bars, floating controls, popovers); lists and rows use flat translucent fills.
- [ ] Tier chosen by `glassTier(...)`; Flat in tests; Frost while the map pans.
- [ ] Effects built only when size/spec change; no zero-valued effects; saturation as colour filter.
- [ ] Pressed glass samples under the inverse transform (striped-backdrop screenshot shows still stripes).
- [ ] Label contrast >= 4.5:1 on every preset in light and dark (JVM test).

## F. Controls and input (`navigation-layout.md`)

- [ ] Tab bar: puck springs REST/DRAG, hold rule 150 ms, tap slop 24 dp, drag start 6 dp, `Role.Tab` + `selected`, labels drop at 1.5x font scale.
- [ ] Switch/slider/segmented follow the glisse geometry and gesture rules; toggles give haptic feedback.
- [ ] Text fields are `BasicTextField(TextFieldState)`: tap places caret, long-press selects with handles and magnifier,
      platform cut/copy/paste toolbar appears, IME action submits, field stays above the keyboard, clear keeps focus.
- [ ] Haptics through `RovstiHaptics` only, on the events listed in `navigation-layout.md` 8, never during scroll.
- [ ] Touch targets >= 48 dp; semantics merged per row; custom actions for swipe actions.

## G. Performance (`performance.md` 11)

- [ ] Deferred reads everywhere; recomposition-count test for the hottest interaction <= 2 per composable.
- [ ] Lazy items have `key` + `contentType`; models immutable; no allocation in item lambdas.
- [ ] Images/tiles sized from the slot and remembered; no crossfade in rows.
- [ ] No main-thread JNI; tickers off Main and conflated.
- [ ] Ambient animations gated on visible/resumed/frozen/animator scale/reduce motion.

## H. Tests and evidence

- [ ] JVM tests for every new pure function (placement, projection, collapse fraction, tier, geometry).
- [ ] Compose tests with `mainClock.autoAdvance = false` for each gesture, including an interruption mid-flight.
- [ ] Screenshots (Roborazzi or Paparazzi): light/dark, font scale 1.0/2.0, RTL, empty/short/long list, keyboard open (Messages),
      popover open (above and below), sheet at each detent, end of list with the tab bar, Stops with a fake tile layer.
- [ ] **Every PNG was opened with the Read tool and judged** (spacing, overlap, covered rows, clipped puck, rim visibility),
      and what was seen is written in the PR.
- [ ] Repo checks pass: Gradle build + tests, and the Rust checks from `CLAUDE.md` if any crate changed.
