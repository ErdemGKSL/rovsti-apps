# 09 · Animated window resize (optional module)

**This feature is opt-in.** Ask the developer during project setup (see SKILL.md
"Setup questions") and add it only if they want it. Some apps, such as editors and dense
tools, must reflow instantly. Media, launcher-like and showcase apps often look better
when floating chrome glides to its new place as the window changes size.

Code: `SpringRect` and `motion_tokens::resize()` (`templates/src/spring.rs`,
`motion_tokens.rs`) and the `ANIMATED_RESIZE` option in `templates/examples/glass_demo.rs`.

## Contents
1. What animates and what does not
2. The pattern: layout as a pure function, then springs
3. Pinning edge-anchored axes
4. Values that depend on animated geometry
5. Reflowing content (flex/grid)
6. Wiring the option
7. Performance and accessibility
8. Common mistakes

---

## 1. What animates and what does not

| Element | On resize |
|---|---|
| Floating chrome whose position is **derived** from the window size (a centred tab bar, a responsive width, a centred button, a split-view divider at a fraction) | **glides** (spring) |
| Elements **anchored to an edge** (top-right chips, a bar's distance from the bottom, a sidebar at x = 12) | **pinned** (snaps, stays attached to the edge) |
| Text reflow inside containers | instant (GPUI layout); animating glyph positions shimmers |
| Cards in a flex/grid that move to new rows | optional: `gpui_effects::animated_layout` (§5) |
| The window itself | never: the OS owns it |

Rule of thumb: **things attached to an edge move with the edge. Things computed from the
size glide.** If an edge-anchored axis lagged, the element would briefly hang off-screen
or float away from its edge while the window shrinks.

## 2. The pattern: layout as a pure function, then springs

```rust
struct Layout { bar: [f32; 4], button: [f32; 4] }          // [x, y, w, h]
fn layout(viewport: Size<Pixels>) -> Layout { /* pure maths, no state */ }

struct View { bar_rect: SpringRect, button_rect: SpringRect, animated_resize: bool, clock: FrameClock, .. }

// render(): GPUI re-renders on every resize, so retargeting here is enough.
let target = layout(window.viewport_size());
let animate = self.animated_resize && !window.prefers_reduced_motion();
self.bar_rect.follow(target.bar, animate, [false, true, false, true]); // pin y and h
self.button_rect.follow(target.button, animate, [false; 4]);
let moving = self.bar_rect.tick(dt) | self.button_rect.tick(dt) /* | other springs */;
if moving { window.request_animation_frame() } else { self.clock.reset() }

// paint at the animated values
let [x, y, w, h] = self.bar_rect.value();
glass(spec).absolute().left(px(x)).top(px(y)).w(px(w)).h(px(h))
```

How `SpringRect::follow` behaves:
- **On the first call it snaps**, so nothing flies in from (0,0) at launch.
- `animate == false` snaps every axis, for instant reflow or reduced motion.
- `pinned[i]` snaps one axis while the others glide.
- Retargeting mid-flight keeps the velocity. A live drag-resize therefore produces a smooth,
  slightly trailing motion and not a stutter.

Use `motion_tokens::resize()` (ζ 1.0, k 420, about 0.31 s). It is critically damped on purpose:
an overshoot would push chrome past the window edge. If the developer asks for a
"liquid" feel, give **sizes** ζ 0.85 but keep **positions** at ζ 1.0 (05 §6 rule).

You do not need `cx.observe_window_bounds(window, ..)` for this, because GPUI calls `render` on
resize anyway. Use it only when the layout targets live outside `render` (for example, cached
in an `Entity` that another view reads).

## 3. Pinning edge-anchored axes

The pin mask is `[x, y, w, h]`:

| Element | Mask |
|---|---|
| bottom tab bar, centred | `[false, true, false, true]`: x/w glide, y/h pinned |
| centred floating button | `[false; 4]` |
| left sidebar with a responsive width | `[true, true, false, true]`: only the width glides |
| top-right chips | no spring at all: use `.top().right()` CSS-style anchoring |

## 4. Values that depend on animated geometry

Anything computed from a gliding rect must read the **animated** value, not the target.
Otherwise children jump while their parent glides:
- The tab width comes from `bar_rect.value()[2]`, not from `layout()`.
- **Store selection positions in relative units** (tab index or fraction), not in px. The
  demo's `puck_pos` is in *tab units*, and pixels are computed every frame as
  `pos * tab_w + pad`. While the bar resizes, the puck stays on its tab. Squash velocity
  converts with `velocity_tabs * tab_w`.
- Hit-testing uses the same live geometry (`pointer_to_tabs`), so a click during the glide
  lands on the tab under the cursor.
- Glass needs nothing special. `paint_glass` takes the bounds it is painted with, so the lens band,
  rim and corner radii follow the animated size automatically.

## 5. Reflowing content (flex/grid)

For grids whose items wrap to new rows on resize, gpui-fusion has a FLIP container:
```rust
gpui_effects::animated_layout("cards")
    .w_full().flex().flex_wrap().gap(px(16.))
    .duration(Duration::from_millis(320))             // duration + cubic ease-out (not a spring)
    .enabled(animated_resize && !window.prefers_reduced_motion())
    .item(card.id, card_view)                          // stable unique keys
```
It animates **positions only**: sizes snap, and removed items vanish without an exit animation. Use it for
card grids and galleries. Do not use it for text paragraphs. See
`cargo run -p gpui_effects --example animated_layout` in the fork.

## 6. Wiring the option

- Use a constant or config field (`ANIMATED_RESIZE: bool`) chosen at setup, plus an optional runtime
  toggle in settings. The demo has a "Resize: animated / instant" chip.
- Keep the code path identical in both modes. Only the `animate` flag passed to `follow`
  changes. With the option off, `SpringRect` behaves as plain layout at zero cost, because snapped
  springs report "settled" immediately.
- When the developer declined the feature at setup, skip `SpringRect` entirely and use
  ordinary flex/absolute layout. Do not add dead code.

## 7. Performance and accessibility

- During a live resize the window redraws every frame anyway. The springs add a few frames
  (about 0.3 s) after the drag stops. Every glass surface re-captures on those frames, so keep
  the glass count low (08 §3).
- `prefers_reduced_motion()` must force `animate = false`.
- Never animate on the very first layout, or when restoring the window or entering or leaving
  fullscreen if the developer finds it distracting. Snap on fullscreen transitions by
  calling `follow(target, false, ..)` once when `window.is_fullscreen()` changes.

## 8. Common mistakes

- Gliding an edge-anchored axis, so the bar hangs off the bottom while the window shrinks.
- Storing the puck or selection position in px, so it slides off its tab while the bar resizes.
- Computing child geometry from `layout()` targets instead of the animated values.
- An overshooting resize spring, so chrome pokes past the window edge.
- Animating text reflow, which shimmers and is unreadable.
- Adding the feature without asking. It is a product decision, not a default.
