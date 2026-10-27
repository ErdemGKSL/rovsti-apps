# 05 · Spring motion

Code: `templates/src/spring.rs` (analytic solver, `Spring`, `FrameClock`) and
`templates/src/motion_tokens.rs` (named springs, curves, gesture helpers).

## Contents
1. Why springs
2. Parameterisations and conversions
3. The analytic solver
4. Driving springs in GPUI: three patterns
5. Motion tokens
6. Fixed-duration spring curves (choreography)
7. Reduced motion
8. Common mistakes

---

## 1. Why springs

iOS motion feels "physical" because almost everything is a spring:
- **Interruptible.** When the target changes mid-flight, the motion continues from
  the current position *and velocity*. Nothing jumps and nothing restarts from zero.
- **Gesture continuity.** The release velocity of a drag becomes the spring's
  initial velocity.
- **No duration to tune.** You pick stiffness and damping, and the duration follows.

Use duration-based easing only for choreographed transitions where many
properties must end together (see §6), and for cross-fades.

## 2. Parameterisations and conversions (mass = 1)

| System | Parameters | To ω0, ζ |
|---|---|---|
| SwiftUI `.spring(response:dampingFraction:)` | response T (s), ζ | ω0 = 2π / T |
| Compose `spring(dampingRatio, stiffness)` | ζ, k | ω0 = √k |
| Upstream GPUI `SpringConfig::new(stiffness, damping, mass)` | k, **c** (viscous!), m | ω0 = √(k/m), ζ = c / (2√(km)) |
| gpui-fusion `ElasticOffset::spring(hz, damping)` | f (Hz), ζ | ω0 = 2πf |
| CSS / Framer `spring(stiffness, damping, mass)` | k, c, m | same as GPUI |

`SpringParams` has constructors for each: `response`, `stiffness`, `frequency`.
`physical()` converts back to `(k, c, m)` for upstream GPUI.

**Upstream pitfall.** `SpringConfig::new(170.0, 0.8, 1.0)` does *not* mean ζ 0.8.
It means c = 0.8, which is almost undamped. Use `let (k, c, m) = SpringParams::stiffness(0.8, 170.).physical();`.

Damping ratio cheat sheet:
- ζ = 1.0: critically damped. Fastest with no overshoot. Use it for sheets, appear, morph and position.
- ζ 0.75–0.86: a single, barely visible overshoot. Use it for selection, press and snap.
- ζ 0.5–0.65: playful bounce. Use it for squash/stretch, glow and toggles.
- ζ < 0.4: wobbly. Use it only for deliberate jelly effects.

## 3. The analytic solver

`SpringParams::step(x, v, target, dt)` evaluates the closed-form solution of
`x'' = −ω0²(x − target) − 2ζω0·x'` for the elapsed `dt`, with separate
branches for under-, critically and over-damped springs. Because it is exact:
- results do not depend on the frame rate (a test checks that 30 Hz and 120 Hz agree),
- there is no explicit-Euler blow-up at large `dt` (frames are still clamped to 1/15 s
  so that a hidden window does not teleport), and
- retargeting keeps the velocity.

`Spring` wraps one channel: `value`, `velocity`, `target`, `params` and `epsilon`.
Its methods:
- `set_target`: retarget and keep the velocity.
- `retarget(target, params)`: also switch curve (press spring → release spring).
- `snap(value)`: jump with no animation (first layout, reduced motion, 1:1 drag).
- `kick(v)`: inject a velocity (fling release).
- `tick(dt) -> bool`: returns true while still moving.

Choose epsilon in value units: 0.1–0.5 px for positions and 0.001 for 0..1 progress.

## 4. Driving springs in GPUI

### Pattern A: tick in `render` (works on the fork and upstream)
```rust
struct View { clock: FrameClock, x: Spring, press: Spring, .. }

impl Render for View {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let dt = self.clock.tick();
        let moving = self.x.tick(dt) | self.press.tick(dt);   // `|`, not `||`: tick every spring
        if moving { window.request_animation_frame(); } else { self.clock.reset(); }
        div().left(px(self.x.value)) /* … */
    }
}
// In an event handler: self.x.set_target(new); cx.notify();
```
`request_animation_frame()` schedules a frame and notifies the view, so `render`
runs again next frame. While nothing moves, nothing redraws.
Reset the clock when everything settles, so the next animation's first `dt` is not
the whole idle gap.

### Pattern B: tick in a paint hook (cheaper for paint-only properties)
If only the glass painter needs the value (glow, lens strength), keep the springs in
an `Entity<MotionState>` or an `Rc<RefCell<..>>` and step them inside
`on_paint_before_children`. Then call `window.request_animation_frame()` there. This avoids
rebuilding the element tree each frame. The fork's `GlassSegmentedControl` works this way.

### Pattern C: upstream GPUI `with_spring` (upstream only)
```rust
let (k, c, m) = motion_tokens::select().physical();
div().absolute()
    .with_spring("puck-x",
        SpringAnimation::new(SpringConfig::new(k, c, m)).to(px(target_x)).with_epsilon(0.25),
        |this, x| this.left(x))
```
GPUI stores position and velocity per element id. Retargeting keeps the velocity.
For a 0..1 progress, use `.to(pressed /* bool */)`, which gives an `AnimationPhase` (`.0` is the f32).
`sampled_easing(config, eps)` turns a spring into `(Duration, easing)` for
`Animation::new(d).with_easing(e)`. Note that this restarts from zero on retarget.

Upstream `Animation` (and the fork's) is duration-plus-easing:
```rust
div().with_animation("fade", Animation::new(Duration::from_millis(400)).with_easing(curve::cubic_bezier(curve::PUSH.1)),
    |this, t| this.opacity(t))
```
The element id must change to restart the animation (`("fade", generation)`).

## 5. Motion tokens (`motion_tokens.rs`)

| Token | ζ | k | ≈ response | Use |
|---|---|---|---|---|
| `select` | 0.76 | 750 | 0.23 s | tab puck, segmented thumb, picker |
| `press` | 0.75 | 1220 | 0.18 s | finger down (scale to 0.97, or the puck grows ×1.39) |
| `release` | 0.60 | 420 | 0.31 s | finger up |
| `appear` | 1.0 | 450 | 0.30 s | sheets, menus, chips appearing |
| `morph` | 1.0 | 950 | 0.20 s | shape morphs (button → menu) |
| `collection_push` | 1.0 | 380 | 0.32 s | grid/list pushes |
| `sheet_snap` | 0.86 | 380 | 0.32 s | detent snap after a fling |
| `sheet_dismiss` | 1.0 | 400 | 0.31 s | sheet dismissal |
| `puck_scale_x` / `_y` | 0.6 / 0.7 | 250 | 0.40 s | squash/stretch (X looser than Y) |
| `glow` | 0.5 | 300 | 0.36 s | press glow and its follow position |
| `resize` | 1.0 | 420 | 0.31 s | *optional* animated window resize (`SpringRect`, see 09) |
| `theme_fade` | 1.0 | 600 | 0.26 s | light ↔ dark cross-fade (and any palette swap); reverses smoothly mid-fade |
| `container_settle` | 1.0 | 300 | 0.36 s | a container leaning or growing under a held control, then settling |

Duration curves (`motion_tokens::curve`):
- Navigation push: 400 ms, `cubic-bezier(0.32, 0.72, 0, 1)`. The outgoing screen moves
  30% of the width and dims to 0.85.
- Card enter: 340 ms. Card exit: 260 ms. Both `(0.3, 0.5, 0.4, 1)`.
- UIKit-ish default: 400 ms, `(0.2833, 0.99, 0.31833, 0.99)`.
- Icon swap: 200 ms in / 160 ms out, starting from scale 0.3 with a cross-fade.

## 6. Fixed-duration spring curves (choreography)

When one gesture changes several properties (for example, a tab bar collapsing into a
mini-player: x, y, width, height and corner radius), independent springs end at
different times and the shape tears. Instead, run **one** timeline of 450–560 ms and
give each property a spring-*shaped* easing:

```rust
use motion_tokens::curve::{spring_easing, critical_easing, staggered};
let pos    = critical_easing(7.8);                       // centres: no overshoot
let width  = spring_easing(0.62, 7.8);                   // size: a little wobble
let height = staggered(67.0 / 500.0, spring_easing(0.58, 7.8)); // y follows 67 ms later
```
- `spring_easing(ζ, ωT)` is the step response normalised to t ∈ 0..1, with the
  residual at t = 1 removed linearly, so it ends exactly at 1.
- **Rule: centres move critically damped and only sizes overshoot.** Otherwise the
  shape visibly drifts past its destination.
- Stagger the axes. When collapsing, move sideways first and drop about 67 ms later. When expanding,
  rise first and widen about 33 ms later.

Drive the timeline with `Animation::new(Duration::from_millis(500))` and
`with_animation`, or with the kit's `spring::Timeline` (`start(now)`, `progress(now)`,
`running(now)`; `now` is passed in, so it is testable). For the bar ↔ card morph,
`curve::morph_axes(t, collapsing)` returns all four staggered, spring-shaped axes at
once (see 07 §10).

## 7. Reduced motion

- Fork: `window.prefers_reduced_motion()`. Upstream: `cx.reduce_motion()`. Upstream
  `with_animation` already respects it.
- When it is on, `snap()` every spring, replace motion with cross-fades of 150 ms or less, and
  disable squash/stretch, parallax and glow pulses. Keep state changes visible.

## 8. Common mistakes

- Integrating with explicit Euler `v += a*dt; x += v*dt` at variable frame rates
  makes the spring jitter or explode on dropped frames. Use the analytic step.
- `a.tick(dt) || b.tick(dt)` short-circuits, so `b` never advances while `a` moves. Use `|`.
- Requesting animation frames forever. Stop when every spring is settled.
- Starting a spring from 0 on first render. `snap` it to the initial layout value instead,
  or the UI flies in from the corner on launch.
- Using a duration easing for something the user can interrupt (a drag, a tab change). It
  will jump. Use a spring.
- Using upstream `SpringConfig` damping as if it were ζ (see §2).
