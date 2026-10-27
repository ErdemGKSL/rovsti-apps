# 06 · Interactions

These are the gesture patterns that make glass feel liquid. Each one is a small amount of
state, one or more springs, and a few lines in an event handler.
`templates/examples/glass_demo.rs` implements 1–6, plus the release ring
(the demo's "sonar ping") and the container lean.

## Contents
1. Press: scale plus glow
2. Selection puck: spring between targets
3. Puck drag: 1:1 tracking, velocity, squash and stretch
4. Elastic pull (tanh) on buttons
5. Fling projection and snapping
6. Rubber banding at the bounds
7. Sheet detents
8. Hover on desktop
9. Event plumbing in GPUI
10. Common mistakes

---

## 1. Press: scale plus glow

| Element | Pressed scale | Spring |
|---|---|---|
| icon button | 0.97 | `press` down, `release` up |
| card / cell | 0.94 | medium bounce |
| glass droplet button | **grows** by `1 + 4px/height` | `press` |
| tab puck | **grows** ×1.39 into a lens | `press` |

Glass controls tend to *grow* when pressed: a liquid drop swelling under the finger.
Opaque controls shrink.

```rust
// mouse down
self.press.retarget(1.0, motion_tokens::press());
self.focus = Some(ev.position);
// mouse up and mouse up outside (both!)
self.press.retarget(0.0, motion_tokens::release());

// render
let s = 1.0 + 0.06 * self.press.value;
// apply the scale about the centre: adjust left/top by -(size*(s-1))/2,
// or paint with glass::scale_about_center(bounds, s, s) in a paint hook
```
Glow: pass `GlassInteraction { press, focus }` to `glass_interactive`. Use
`motion_tokens::glow()` (ζ 0.5) for the glow and a stiffer spring for the scale,
so the light "breathes" after the shape has settled.

GPUI has no transform-scale for layout boxes, so scale by changing the size and
position, or paint scaled bounds in a paint hook. Do not scale text: keep labels at
their size and only grow the glass behind them, or the text shimmers.

## 2. Selection puck: spring between targets

- Lay the options out normally. Store each option's bounds, either computed (equal widths)
  or measured with a `canvas` child that writes `bounds` into an `Rc<Cell<..>>` during prepaint.
- Keep springs for the puck's x and width (add y/height for 2D grids).
- On selection: `x.set_target(option.left)` and `w.set_target(option.width)` with
  `motion_tokens::select()`.
- Paint the puck as glass *inside* the bar's glass, so it refracts the bar, then paint the labels
  on top. Inset it by 4 px, with concentric radius = bar radius − 4.
- Selected label colour: tint the label under the puck with the accent colour. For the
  pixel-exact version, use `gpui_effects::liquid_glass_content`, which recolours only the
  part under the moving glass.

## 3. Puck drag: 1:1 tracking, velocity, squash and stretch

1. **Mouse down on the selected option**: remember `grab = pointer − puck.x`. The puck
   starts growing (press spring, ×1.39 scale) and morphs into the lens spec:
   `GlassSpec::lerp(rest, GlassSpec::lens(theme), press)`. The morph covers blur 2 → 0,
   clarity 0 → 1, dispersion 0 → 0.35, depth on, and inner shade.
2. **Mouse move with the button held**: `x.snap(pointer − grab)`. Estimate the velocity from the last two
   samples, `(Δx / Δt)`, and store it in `x.velocity` after the snap. The spring is not ticking toward
   anything, but the velocity drives the squash.
3. **Squash and stretch** every frame from the normalised velocity
   (`v / 1500 px/s`, clamped to −1..1):
   ```
   sx = 1 / (1 − min(|v|·0.75, 0.2))     stretch along the motion (up to +25%)
   sy = 1 − min(|v|·0.25, 0.2)           thin across it (down to −20%)
   ```
   Feed these as *targets* to `puck_scale_x` (ζ 0.6) and `puck_scale_y` (ζ 0.7), so the
   drop wobbles back when it stops. (`motion_tokens::gesture::squash_stretch`.)
4. **Release**: pick the target with fling projection (§5), call `set_target(tab.x)`, and keep
   the velocity (`kick(v)`). Press goes to 0 with the `release` spring.
5. **Container response** (optional): while pressed, the bar grows by `1 + 16px/width` and drifts
   up to 4 px toward the drag direction. Spring it back with ζ 1, k 300 on release.

## 4. Elastic pull (tanh) on buttons

A pressed glass button follows the finger a little, with diminishing returns, and
stretches along the drag:
```rust
// offset = pointer − press point (springs: snap while dragging, glow() back to 0 on release)
let base = 1.0 + 4.0 / h * press;                                   // pressed droplet grows 4 px
let (sx, sy) = gesture::elastic_scale((ox, oy), (w, h), base, 4.0 / h);
let (tx, ty) = (gesture::elastic_offset(ox, w.min(h)), gesture::elastic_offset(oy, w.min(h)));
// paint at centre + (tx, ty), size (w·sx, h·sy)
```
`elastic_offset(o, max) = max · tanh(0.05 · o / max)`: it follows at 5% at first and
saturates at `max` (the button's smaller side). A 160 px pull moves the button
about 8 px and stretches it about 7%, which reads as surface tension, not as a drag.

**Release ring.** On release, start a `Timeline` (460 ms for UI, about 1.1 s for a playful
"ping") and draw `border_2()` circles grown by `e · 18…80 px` with alpha `0.6 · (1 − e)`,
where `e = cubic_bezier(curve::RELEASE_RING.1)(p)`. Two rings offset by 30% of the
timeline read as a sonar wave.

**Container lean.** While a puck is held, the bar grows by `1 + 16px/width` (uniform, about the
centre) and drifts `4 · tanh(dx / 40)` px toward the drag (`container_settle` spring,
back to 0 on release). Inset the bar's children by half the growth, so labels stay put.
`gesture::rubber_band(offset, limit) = limit · tanh(offset / limit)` is the same
shape. It is linear near zero and asymptotic to `limit`.

## 5. Fling projection and snapping

Choose the destination from where the motion *would* end, not from where the finger
lifted:
```rust
let landing = gesture::project(position, velocity_px_per_s, 0.998); // UIKit normal deceleration
let target = nearest(detents_or_tabs, landing);
spring.set_target(target); spring.kick(velocity_px_per_s);
```
`0.998` per millisecond is `UIScrollView.DecelerationRate.normal`. Use `0.99` for "fast".
With this, a quick flick reaches the next detent even if the finger barely moved.

## 6. Rubber banding at the bounds

Past the first or last option (or the scroll bounds), map the overshoot through
`rubber_band(over, 24.0)` instead of clamping. On release, the spring brings it back.
The template demo does this for the tab puck.

## 7. Sheet detents

- The detents are fractions of the window height, for example `[0.5, 0.92]` plus "dismissed".
- While dragging, the sheet follows 1:1 inside its range and rubber-bands outside it.
- On release: `project(y, vy, 0.998)` → nearest detent → `sheet_snap` spring
  (ζ 0.86, k 380) with the release velocity. To dismiss, use the `sheet_dismiss` spring (ζ 1, k 400).
- The backdrop dims to black 0.2–0.4α, proportional to the sheet's progress, and the
  presenting view scales to about 0.94 at the top detent (iOS card stack).
- The sheet glass uses the `sheet` preset (more blur), with a radius of 38 and a grabber of 36×5.

## 8. Hover on desktop

iOS has no hover, but desktop GPUI does. Keep it subtle:
- Pointer-follow glow at about 0.3× the press glow strength (`press = 0.3` while hovered).
- No scale change on hover. Change the cursor with `.cursor_pointer()`.
- `BackdropInput.pointer` / `pointer_active` (set through `PaintBackdropEffect::pointer`) lets
  a shader react to proximity without re-rendering.

## 9. Event plumbing in GPUI

```rust
div().id("x")
    .on_mouse_down(MouseButton::Left, cx.listener(|this, ev: &MouseDownEvent, window, cx| { .. cx.notify(); }))
    .on_mouse_move(cx.listener(|this, ev: &MouseMoveEvent, window, cx| {
        if ev.pressed_button == Some(MouseButton::Left) { .. }
    }))
    .on_mouse_up(MouseButton::Left, cx.listener(..))
    .on_mouse_up_out(MouseButton::Left, cx.listener(..))   // released outside: end the press!
    .on_click(cx.listener(|this, _ev, window, cx| ..))       // needs .id()
```
- Event positions are in window coordinates (logical px). Subtract your element's
  origin, which you know from layout math or captured in prepaint/paint.
- **Pattern used by the demo:** start gestures in the element's `on_mouse_down`, but put
  `on_mouse_move` and `on_mouse_up` on the **root** div, which covers the window. Drags
  then continue outside the element, and a release anywhere ends every active gesture.
  You do not need `on_mouse_up_out`.
- `on_mouse_move` fires only while the pointer is over the element. For drags that leave
  the element, register a window-level listener in a paint hook with
  `window.on_mouse_event(...)`, or make the hit area large.
- Call `cx.notify()` after changing a spring target so `render` runs and starts ticking.

## 10. Common mistakes

- Handling only `on_mouse_up`. A release outside the element leaves the control stuck pressed.
- Scaling text with the glass. Glyphs shimmer. Scale only the glass.
- Snapping to the nearest option at the release point. Flicks feel ignored. Project first.
- Dropping the release velocity, so the puck stops dead and then starts again.
- Giving squash and stretch the same spring on both axes. It looks rubbery and not liquid. Make X looser than Y.
