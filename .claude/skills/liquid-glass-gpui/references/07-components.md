# 07 · Component recipes

These recipes assume the kit in `templates/src` (`glass`, `glass_interactive`,
`GlassSpec`, `Spring`, `motion_tokens`, `tokens`). Each recipe gives its anatomy,
paint order, springs and code. Adapt the code; do not paste it blindly.

## Contents
1. Floating tab bar with puck
2. Glass circle button
3. Segmented control
4. Toolbar / navigation bar (top)
5. Sidebar (desktop)
6. Sheet
7. Toggle (switch)
8. Slider
9. Context menu / popover
10. Mini player ↔ tab bar morph
11. Composition rules

---

## 1. Floating tab bar with puck
```
[ bar glass (chrome, rounded_full, shadow) ]
   ├─ puck glass (rest: fill-tinted chrome → pressed: lens), inset 4, concentric radius
   └─ labels row (icons + caption2, selected = accent)
```
- Height 56, floating 12 px above the bottom edge and centred. Width = tabs × 72–96.
- Springs: `puck_x` (select), `puck_press` (press/release), `puck_sx` / `puck_sy` (squash).
- Collapse on scroll: when the content scrolls down, shrink to an "inline" bar (icons only,
  smaller width) using the fixed-duration choreography in 05 §6.
- Full code: `templates/examples/glass_demo.rs` (`bar`, `on_bar_down/move/up`).

## 2. Glass circle button
```rust
glass_interactive(GlassSpec::control(theme), move || GlassInteraction { press, focus })
    .id("play").size(px(56.)).rounded_full()
    .shadow(tokens::shadow::glass(theme.is_dark()))
    .flex().items_center().justify_center()
    .child(svg().path("icons/play.svg").size(px(22.)).text_color(colors.label))
```
- Diameter 44–56. Press: grow 1 + 4px/h, glow follows the finger.
- For a primary or "prominent" button, use an accent tint at 0.82α instead of the theme fill
  (`GlassSpec { tint: accent_with_alpha(0.82), ..control }`) and white glyphs.

## 3. Segmented control
- Use the same structure as the tab bar but horizontal and compact. Height 32–36, radius full,
  thumb inset 2–3 px.
- Measure the options with `canvas` (their widths can differ) and spring both `x` and `width`.
- Thumb at rest: `GlassSpec::control` with a slightly stronger fill. Pressed: lens morph,
  scale 1.35 (gpui-fusion uses this value too).
- The fork ships it ready-made: `uic::components::glass::GlassSegmentedControl::new(id, selected)…` (check the re-export path in `uic/src/components/mod.rs`). Read
  `uic/src/components/glass/segmented.rs` before writing your own.

## 4. Toolbar / navigation bar (top)
- Large-title style: the title (`LARGE_TITLE`) scrolls with the content and the bar becomes a compact
  glass strip (44–52 px) once the title has scrolled under it.
- Use a **progressive blur** under the strip, not a hard edge:
  `gpui_effects::progressive_blur(content).top(px(64.))`.
- Toolbar buttons are separate circle glass buttons (44 px), or one pill grouping
  2–3 icons. Never use a full-width opaque bar.

## 5. Sidebar (desktop)
- Floating panel inset 8–12 px from the window edges, radius 20–26,
  `GlassSpec::side_panel` (calmer: saturation 1.2, 0.75 fill).
- The selection highlight is a puck that springs between rows (`select`), radius
  concentric with the panel's padding.
- On macOS, pair it with `WindowBackgroundAppearance::Blurred` and a transparent root, so the
  desktop shows faintly through the window and the glass refracts the app content above it.

## 6. Sheet
```
dim layer (black 0..0.35α by progress)
sheet glass (GlassSpec::sheet, radius 38 top corners, grabber 36×5 at top+6)
   └─ content (opaque-ish list sections on grouped background)
```
- Springs: `offset_y` with `sheet_snap` / `sheet_dismiss`, plus fling projection to detents (06 §7).
- Present with `appear` from off-screen. The presenting view scales to 0.94 and gets radius 10.

## 7. Toggle (switch)
- Track 51×31, radius full: fill when off, accent when on.
- The thumb is a *glass* pill, 27×27 at rest. When pressed it stretches to 35×27 (lens
  morph, grows ×1.3) and slides with `select`. When released it shrinks back.
- Track colour cross-fade: drive `t` with `appear` and lerp the colours.

## 8. Slider
- Track 4–6 px, radius full. The filled part is accent.
- The knob is glass, 28×28 at rest and a lens 40×28 while dragging. It follows the pointer 1:1,
  with squash and stretch from its velocity.
- Value changes are immediate (no spring on the value). Only the knob's visuals spring.

## 9. Context menu / popover
- Morph from the source button: animate bounds from the button's rect to the menu's rect
  with `morph` (ζ 1, k 950). Width and height use a slightly bouncy `spring_easing(0.8, 8)` if you
  use the fixed-duration form.
- Menu glass: `GlassSpec::sheet` with radius 14, rows 44 px, separators 0.33 px.
- On release, a ring pulse spreads out over 460 ms (optional).
- Dismiss: reverse the morph and fade the content out in the first 40%.

## 10. Companion card ↔ tab bar morph (mini player, HUD, now-playing…)
This is the signature Convx-style motion. A companion card (Convx: a mini
player; the demo: a depth HUD) sits stacked above the tab bar and collapses
into an inline pill beside a compacted bar, then expands back.
Implemented in the demo (`toggle_hud`, `tick`). Its geometry is checked by
`tests/headless_frames.rs`, which asserts that the axes are staggered.
- One 500 ms timeline. The x/y centres use `critical_easing(7.8)`. Widths use
  `spring_easing(0.62, 7.8)` when collapsing and `0.58` when expanding. The bar body uses ζ 0.74.
- Stagger: when collapsing, horizontal first and vertical 67 ms later; when expanding, vertical
  first and widen 33 ms later.
- Both shapes are glass. Paint the background shape first and the morphing shape
  second, so it refracts the first.
- The recipe:
  1. `layout(viewport, collapsed)` gives each state's rects as a pure function.
  2. On toggle, snapshot the **current** rects as `from` (so interrupting a running
     morph never jumps) and start a `Timeline::new(curve::MORPH)`.
  3. Every frame, take `a = curve::morph_axes(progress, collapsing)` and set each rect
     to `lerp(from, to, a.x / a.y / a.width / a.bar_width)`, with the target recomputed
     from the live viewport. Feed it to `SpringRect::follow(rect, false, ..)`: the
     timeline *is* the animation.
  4. Keep selection state in relative units (tab index), so the puck stays on its tab
     while the bar's width morphs.
  5. Fade text that no longer fits (`opacity = ((w − 220) / 60).clamp(0, 1)`), and keep the
     always-visible readout in a `flex_none` child.

## 11. Composition rules
1. **Layering, back to front:** content → glass surface → glass puck/lens → labels/icons.
2. **One level of glass over content and at most one inside it** (bar + puck). Glass inside
   glass inside glass turns into mush, and each layer costs a pass.
3. **Glass floats.** Chrome does not take layout space. Content scrolls under it, with
   scroll padding.
4. **Contrast first.** If a label is hard to read, increase the fill alpha or switch to
   the `sheet` preset before you touch the optics.
5. **Interaction lives in the glass.** Glow, lens morph and squash all happen on the glass
   layer. Labels only change colour.
