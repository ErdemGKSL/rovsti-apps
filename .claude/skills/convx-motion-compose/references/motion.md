Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang. Full licence text: `glisse-LICENSE.txt` next to this file.

# Motion: springs, interruptibility, gestures, shared elements, predictive back

> **Licence.** Convx (GPL-3.0) is described, never copied: every block marked `ORIGINAL` is Rovsti's own code written
> from behaviour descriptions and public maths. Blocks marked `PORTED FROM GLISSE` are translations of glisse (MIT) and
> must keep the header `// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang`
> in whatever source file they land in.

Citation prefixes. Convx: `U/` = `app/src/main/kotlin/com/convx/music/ui/`. glisse: `G/` = `src/`.
Research reports with more detail: `_bmad-output/kotlin-ui/research/convx-motion.md`, `glisse.md`.

---

## 1. Why it feels fluid (the mechanism, not the vibe)

| # | Mechanism | Evidence |
|---|---|---|
| 1 | Springs are retargeted on the same `Animatable`; value and velocity survive interruption. A tap during a settle, a re-grab during a bounce, a reversed sheet never jumps. | `U/ios/component/IosSheet.kt:198,270,274`; glisse `set()` never touches velocity, `G/internal/spring.ts:76-95` |
| 2 | During a drag the finger writes the value with `snapTo` in the same input event (`CoroutineStart.UNDISPATCHED`); comment says the card trailed one frame without it. | `U/component/BottomSheet.kt:638-644` |
| 3 | Release velocity seeds the settle spring (`initialVelocity`) and the target is picked from the **projected** rest point (`v/1000 * 0.998/(1-0.998)`), not from where the finger lifted. | `U/utils/FlingProjection.kt:19-21`; `IosSheet.kt:265-274`; `U/ios/component/IosSwipeActions.kt:85` |
| 4 | Every per-frame value is read in a layer/draw lambda, so a 120 Hz animation recomposes nothing. | `IosSheet.kt:157-161`; `U/component/IosSheetPresentation.kt:72-100` |
| 5 | Overshoot sits on surfaces, not content. User feedback quoted in code: "overshoot of the surface or body only, not the icons or items". | `U/component/floatingtabbar/BarMotion.kt:176-184` |
| 6 | One clock per visual object. When several values must finish on the same frame Convx uses a tween whose easing is a spring step response, not several springs (earlier multi-clock version caused an "icon pop"). | `U/component/floatingtabbar/FloatingTabBarMotion.kt:56-64`; `BarMotion.kt:146-174` |
| 7 | Press feedback is small (0.94-0.96 scale or glisse's +15 dp grow), springy, and never changes layout. | `U/utils/ModifierExt.kt:136,160`; `U/ios/component/IosButton.kt:123-127`; `G/internal/press.ts:13-16` |
| 8 | Scrubbable transitions (predictive back, drag-to-dismiss) are tweens/progress; only settle phases are springs. Comment: "Tween, not spring: predictive back scrubs this by gesture progress". | `U/screens/NavigationBuilder.kt:779-780` |

---

## 2. Token table (Rovsti picks, with provenance)

Compose `spring(dampingRatio, stiffness)` is a mass-1 oscillator, identical to glisse's `(stiffness, damping coefficient)`:
`dampingRatio = damping / (2 * sqrt(stiffness))` (`G/internal/spring.ts:21-48`). Natural frequency `sqrt(k)` rad/s.

### 2.1 Springs

| Token | dampingRatio / stiffness | Use | Source |
|---|---|---|---|
| `glassPressDown` | 0.601 / 515 (glisse 515, 27.3) | glass button, finger down: grow + stretch toward finger | `G/internal/press.ts:16` |
| `glassPressUp` | 0.373 / 250 (glisse 250, 11.8) | glass button release: bounces past rest once | `G/internal/press.ts:16` |
| `pointerPressDown/Up` | 0.714 / 417.6, 0.781 / 230 | mouse/stylus feel | `G/internal/press.ts:13` |
| `press` | 0.75 / 1200 | scale-only press (icon buttons, cards) to 0.96 | `U/utils/Motion.kt:87-91`, `IosButton.kt:123` |
| `selectRest` | 0.920 / 1024 | puck/segment thumb slide after a tap | `G/internal/segments.tsx:61` |
| `selectDrag` | 0.940 / 2500 | puck tracking while the finger is down | `G/internal/segments.tsx:64` |
| `liftRest` / `liftDrag` | 0.919 / 676, 0.921 / 576 | lens lift of a selection | `G/internal/segments.tsx:61,64` |
| `select` (non-glass) | 0.76 / 750 | chip selection, simple indicators | `U/utils/Motion.kt:77-82` |
| `toggle` | 1.265 / 1000 (over-damped) | switch thumb position and colour | `G/switch.tsx:84,88` |
| `thumbLift` | 0.894 / 2000 | switch/slider thumb lift | `G/switch.tsx:91`, `G/slider.tsx:68` |
| `appear` | 1.0 / 450 | banners, inline errors, chips appearing in place | `U/utils/Motion.kt:97-102` |
| `morph` | 1.0 / 950 | shared container resizes | `U/utils/Motion.kt:42-47` |
| `sheetSettle` | 0.86 / 380 | sheet present and detent settle (+ release velocity) | `IosSheet.kt:198,274` |
| `sheetDismiss` | 1.0 / 400 | sheet off screen (+ release velocity) | `IosSheet.kt:270` |
| `pushBack` | 1.0 / 380 | page behind a sheet scales to 0.91 | `IosSheetPresentation.kt:153-157` |
| `swipeSettle` | 1.0 / 500 | swipe-row open/close | `IosSwipeActions.kt:85` |
| `rebound` | 1.0 / 247; 1.0 / 130 if pull velocity > 5000 px/s | overscroll return, seed velocity clamped to +/-10000 | `U/utils/IosOverscroll.kt:58-67` |
| `menuMorphOpen` / `Close` | 0.939 / 600, 0.950 / 900 | button-anchored menu growing out of its trigger | `G/menu.tsx:83,116-124` |
| `menuCardPress` / `Release` | 0.706 / 650, 0.612 / 600 | finger on an open menu card | `G/menu.tsx:136-144` |
| `menuGrowX` / `menuGrowY` | 0.62 / 560, 0.78 / 330 | long-press context menu panel (width leads) | `U/ios/component/AppleContextMenu.kt:272-274` |
| `menuLift` / `menuLand` | 0.80 / 420, 1.0 / 520 | preview card lift and return | `AppleContextMenu.kt:267` |
| `previewReturn` | 0.75 / 500 | dragged preview springs back | `AppleContextMenu.kt:159` |
| `titleCollapse` | 1.0 / 500 | large title -> inline title | `U/ios/component/IosNavigationBar.kt:58` |
| `barMinimize` | 0.90 / 500 | tab bar slide down 40 dp + 35% fade | `U/ios/component/IosTabBar.kt:121-125` |
| `squashDecay` | 0.5 / 300 | velocity used for squash/stretch decays after release | `U/component/backdrop/catalog/utils/DampedDragAnimation.kt:46` (Apache-2.0 upstream origin) |

Always pass `visibilityThreshold` (0.0001f for scales, 0.001f for unit progress, 0.5f px for offsets) so springs end on
a definite frame (`U/utils/Motion.kt:33-35`; glisse settles at `|x|<1e-4, |v|<1e-3`, `G/internal/spring.ts:56-60`).

### 2.2 Tweens and easings

| Token | ms | Easing | Use | Source |
|---|---|---|---|---|
| `push` | 400 | `CubicBezierEasing(0.32f, 0.72f, 0f, 1f)` | screen push/pop; outgoing moves 30% and dims to 0.85 | `U/utils/Motion.kt:157-179` |
| `cardOpen` / `cardClose` | 340 / 260 | `(0.3, 0.5, 0.4, 1)` | card growing out of a tile and back | `Motion.kt:54-71` |
| `swapIn` / `swapOut` | 200 / 160 | FastOutSlowIn, initial scale 0.3 | icon swaps (play/pause, mute) | `Motion.kt:112-126`; `U/component/AnimatedPlayPauseIcon.kt:40-62` |
| `menuExit` | 140-150 | `(0.4, 0, 0.9, 0.5)` | every menu/popover close, no blur | `U/ios/component/IosContextMenu.kt:147,259-260` |
| `scrimIn` / `scrimOut` | 180 / 140 | as menu | menu scrim | `IosContextMenu.kt:155-161` |
| `menuPlate` | 100 | `(0.2, 0.8, 0.2, 1)` | highlight plate sliding between menu rows | `G/styles.css:1010-1012` |
| `focusFill` | 200 | CSS `ease` = `(0.25, 0.1, 0.25, 1)` | text-field fill on focus, pressed glass fill fading back | `G/styles.css:153,584-589` |
| `glowOut` | 220 | ease-in-out | finger glow fade after release | `G/styles.css:111-130` |
| `ambient` | 900-1100 | `(0.37, 0, 0.63, 1)` | slow colour/background changes | `U/player/PlayerSheetMotion.kt:167-176` |
| `actionSheetIn/Out` | 400 / 150 | `(0.2833, 0.99, 0.31833, 0.99)` / EaseIn | action sheet | `U/component/BottomSheetMenu.kt:93-95` |

Do not inherit: Convx's push KDoc says 260 ms while the constant is 400 (`Motion.kt:148-157`); trust 400.

### 2.3 Token file (PORTED FROM GLISSE for the glisse rows; the rest ORIGINAL)

```kotlin
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.sqrt

/** A glisse spring: stiffness and damping *coefficient* of a mass-1 oscillator. */
data class GlisseSpring(val stiffness: Float, val damping: Float) {
    val dampingRatio: Float get() = damping / (2f * sqrt(stiffness))
    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = dampingRatio, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
}

object RovstiMotion {
    // glisse (G/internal/press.ts, segments.tsx, switch.tsx, slider.tsx, menu.tsx)
    val GlassPressDown = GlisseSpring(515f, 27.3f)
    val GlassPressUp = GlisseSpring(250f, 11.8f)
    val PointerPressDown = GlisseSpring(417.6f, 29.2f)
    val PointerPressUp = GlisseSpring(230f, 23.7f)
    val SelectRest = GlisseSpring(1024f, 58.9f)
    val SelectDrag = GlisseSpring(2500f, 94f)
    val LiftRest = GlisseSpring(676f, 47.8f)
    val LiftDrag = GlisseSpring(576f, 44.2f)
    val Toggle = GlisseSpring(1000f, 80f)
    val ThumbLift = GlisseSpring(2000f, 80f)
    val MenuMorphOpen = GlisseSpring(600f, 46f)
    val MenuMorphClose = GlisseSpring(900f, 57f)
    val MenuCardPress = GlisseSpring(650f, 36f)
    val MenuCardRelease = GlisseSpring(600f, 30f)

    // Rovsti's own picks (numbers observed in Convx; see motion.md 2.1)
    fun <T> press(t: T? = null) = spring(0.75f, 1200f, t)
    fun <T> select(t: T? = null) = spring(0.76f, 750f, t)
    fun <T> appear(t: T? = null) = spring(1f, 450f, t)
    fun <T> morph(t: T? = null) = spring(1f, 950f, t)
    fun <T> sheetSettle(t: T? = null) = spring(0.86f, 380f, t)
    fun <T> sheetDismiss(t: T? = null) = spring(1f, 400f, t)
    fun <T> pushBack(t: T? = null) = spring(1f, 380f, t)
    fun <T> swipeSettle(t: T? = null) = spring(1f, 500f, t)
    fun <T> menuGrowX(t: T? = null) = spring(0.62f, 560f, t)
    fun <T> menuGrowY(t: T? = null) = spring(0.78f, 330f, t)
    fun <T> menuLift(t: T? = null) = spring(0.80f, 420f, t)
    fun <T> menuLand(t: T? = null) = spring(1f, 520f, t)
    fun <T> titleCollapse(t: T? = null) = spring(1f, 500f, t)
    fun <T> barMinimize(t: T? = null) = spring(0.90f, 500f, t)
    fun rebound(velocity: Float) = spring(1f, if (kotlin.math.abs(velocity) > 5000f) 130f else 247f, 0.5f)

    val PushEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    val MenuExitEasing = CubicBezierEasing(0.4f, 0f, 0.9f, 0.5f)
    val MenuEnterEasing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1.03f)
    val PlateEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val AmbientEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
    fun <T> push(): FiniteAnimationSpec<T> = tween(400, easing = PushEasing)
    fun <T> menuExit(): FiniteAnimationSpec<T> = tween(140, easing = MenuExitEasing)

    const val PushParallax = 0.30f
    const val PushDimAlpha = 0.85f
    const val PressScale = 0.96f
}

/** The one reduce-motion gate. Call sites never write `if (reduce) snap() else ...`. */
fun <T> FiniteAnimationSpec<T>.gated(reduceMotion: Boolean): FiniteAnimationSpec<T> =
    if (reduceMotion) snap() else this
```

`spring(dampingRatio, stiffness, visibilityThreshold)` is `androidx.compose.animation.core.spring` (all API levels).

### 2.4 Analytic spring solver (PORTED FROM GLISSE: `G/internal/spring.ts:21-64`)

UI code uses Compose `Animatable` + `spring()` (draw-phase reads, frame clock, cancellation). Use this exact closed-form
solver only for JVM tests (step a spring without a frame clock and compare against `GlisseSpring.spec`), and inside a custom
`Modifier.Node` that must step a spring by hand from `withFrameNanos`. Exact for any `dt`, so it is frame-rate independent.

```kotlin
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.ui.motion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class SpringState(var value: Float, var target: Float = value, var velocity: Float = 0f) {
    /** Retarget without touching value or velocity: this is what makes glisse springs interruptible. */
    fun set(newTarget: Float) { target = newTarget }

    /** Advance by [dt] seconds (glisse clamps dt to 0..0.1 s). Returns true once settled. */
    fun advance(stiffness: Float, damping: Float, dt0: Float): Boolean {
        val dt = dt0.coerceIn(0f, 0.1f)
        val x = value - target
        val v = velocity
        val half = damping / 2f
        val discriminant = half * half - stiffness
        if (abs(discriminant) < 1e-4f) {
            val decay = exp(-half * dt)
            val b = v + half * x
            value = target + decay * (x + b * dt)
            velocity = decay * (v - half * b * dt)
        } else if (discriminant > 0f) {
            val root = sqrt(discriminant)
            val r1 = -half + root
            val r2 = -half - root
            val a = (v - r2 * x) / (r1 - r2)
            val b = x - a
            value = target + a * exp(r1 * dt) + b * exp(r2 * dt)
            velocity = a * r1 * exp(r1 * dt) + b * r2 * exp(r2 * dt)
        } else {
            val omega = sqrt(-discriminant)
            val decay = exp(-half * dt)
            val c = cos(omega * dt)
            val s = sin(omega * dt)
            value = target + decay * (x * c + ((v + half * x) / omega) * s)
            velocity = decay * (v * c - ((half * v + stiffness * x) / omega) * s)
        }
        if (abs(value - target) < 1e-4f && abs(velocity) < 1e-3f) { value = target; velocity = 0f; return true }
        return false
    }
}
```

Tests: each regime (zeta < 1, = 1, > 1) against a fine RK4 integration; one 16 ms step equals two 8 ms steps; retarget
mid-flight keeps velocity; `GlassPressUp` (zeta 0.373) overshoots past rest once by roughly `exp(-zeta*pi/sqrt(1-zeta^2))` ~ 28%
of the travel.

### 2.5 Reduce motion (ORIGINAL)

```kotlin
// Reads the system animator scale once and on change. ValueAnimator.areAnimatorsEnabled(): API 26.
@Composable
fun rememberReduceMotion(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state.value = !ValueAnimator.areAnimatorsEnabled() }
        }
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return state
}
```

Provide it once at the root (`staticCompositionLocalOf`), plus an in-app "Reduce motion" preference ORed in. With it on:
no squash, no parallax, no bounce (use `spring(1f, k)`), menus fade 100 ms (glisse reduced-motion path,
`G/styles.css:1069-1083`). Convx wires reduce-motion to its own blur preference only (`U/component/ConvxIntro.kt:59` is the only system
read), which we do not copy.

---

## 3. Interruptibility rules

1. **One `Animatable` per quantity, owned by a state holder** (`@Stable class`). Never create a new `Animatable` for a
   new target. `Animatable.animateTo` runs under a `MutatorMutex`: a newer call cancels the running one and starts
   from the live value and velocity. That is the whole interruptibility mechanism.
2. **Cancel and replace, never queue.** Convx removed a mutex-queued settle because it stranded press progress at 1
   and left the puck at 1.39x (`U/component/backdrop/catalog/utils/DampedDragAnimation.kt:146-181`). Always deflate
   pressed state in `finally`.
3. **Generation guard for late callbacks** (a cancelled fling finishing after a new touch-down):
   `IosOverscroll.kt:160-163`, `U/utils/MotionModifiers.kt:200-205`.
4. **Progress-driven geometry.** If several things move together, drive one progress value 0..1 and compute every
   position as a pure function of it. Reversal mid-flight retraces the same path
   (`U/component/floatingtabbar/FoldGeometry.kt:19-59`). Allow the progress to overshoot 0..1 under a spring; clamp sizes,
   not positions.
5. **Hand-over of input ownership.** While a morph flies, decide who receives touches: the side that started a drag
   keeps it; mid-flight nobody does (taps cannot fight the spring) (`U/player/PlayerSheetMotion.kt:60-87`).
6. **Keep composed until settled.** Exit animations need the node: an overlay stays in the tree until
   `currentState == targetState == false` (`MutableTransitionState`), glisse's `leaving -> closed` phase (`G/menu.tsx:29,43`).

```kotlin
// ORIGINAL
class Generation {
    private var current = 0
    fun next(): Int = ++current
    fun isCurrent(token: Int) = token == current
}
// usage: val g = generation.next(); scope.launch { animate(...); if (generation.isCurrent(g)) onSettled() }
```

Compose test for continuity (ORIGINAL): with `composeRule.mainClock.autoAdvance = false`, start `animateTo(1f)`,
advance 40% of its duration, retarget to `0f`, advance one frame, assert `|value_after - value_before| <` one frame of
travel at the recorded velocity.

---

## 4. Momentum projection and rubber band (ORIGINAL, pure Kotlin, JVM-tested)

Apple's "Designing Fluid Interfaces" (WWDC 2018) projection, which Convx implements and explicitly prefers over the
textbook `v^2/2a` (`U/utils/FlingProjection.kt:19-56`). UIKit's rubber band with c = 0.55 scaled by the viewport
(`U/utils/IosOverscroll.kt:47,70-75`).

```kotlin
// ORIGINAL
package dev.rovsti.ui.motion

import kotlin.math.abs
import kotlin.math.sign

object Fling {
    /** Distance a flick travels before coming to rest. 0.998 = UIKit normal, 0.99 = fast. */
    fun projectedDistance(velocityPxPerSec: Float, decelerationRate: Float = 0.998f): Float =
        (velocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)

    fun restingPoint(position: Float, velocityPxPerSec: Float, rate: Float = 0.998f): Float =
        position + projectedDistance(velocityPxPerSec, rate)

    /** Snap point closest to the projected rest; a tie goes in the direction of travel. */
    fun nearestSnap(points: FloatArray, rest: Float, velocityPxPerSec: Float): Float {
        require(points.isNotEmpty())
        var best = points[0]
        var bestDistance = abs(points[0] - rest)
        for (i in 1 until points.size) {
            val d = abs(points[i] - rest)
            val tieTowardTravel = d == bestDistance && sign(points[i] - best) == sign(velocityPxPerSec)
            if (d < bestDistance || tieTowardTravel) { best = points[i]; bestDistance = d }
        }
        return best
    }
}

object Rubber {
    /** Displayed offset for a raw pull. Slope 1 at 0, asymptote dimension / c. */
    fun band(raw: Float, dimension: Float, c: Float = 0.55f): Float {
        if (dimension <= 0f || raw == 0f) return 0f
        val x = abs(raw)
        return sign(raw) * (1f - 1f / (x * c / dimension + 1f)) * dimension / c
    }
}
```

Tests: `projectedDistance(1000f) == 499f` (+-0.5); `nearestSnap` with ties; `band` monotonic, `band'(0) ~ 1`,
`band(1e9, D) < D / 0.55`.

---

## 5. Gesture-driven motion

### 5.1 The drag/release pattern (every draggable thing)

`AwaitPointerEventScope` is `@RestrictsSuspension`: you cannot call `snapTo` inside it, and `detectDragGestures`
callbacks are not suspending. So launch the write on the outer scope with `CoroutineStart.UNDISPATCHED`, which runs it
synchronously inside the current input event (Convx's reason: `U/component/BottomSheet.kt:638-644`).

```kotlin
// ORIGINAL
@Stable
class DragAxis(initial: Float, private val scope: CoroutineScope) {
    val value = Animatable(initial)
    private val tracker = VelocityTracker()

    fun onDown() { tracker.resetTracking() }

    fun onDrag(change: PointerInputChange, delta: Float, min: Float, max: Float) {
        tracker.addPointerInputChange(change)
        val next = (value.value + delta).coerceIn(min, max)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { value.snapTo(next) }
    }

    /** Release: project, pick a snap point, spring there with the release velocity. */
    fun onRelease(snaps: FloatArray, axisVelocity: (Velocity) -> Float, spec: AnimationSpec<Float>, onSettled: (Float) -> Unit) {
        val v = axisVelocity(tracker.calculateVelocity())
        val target = Fling.nearestSnap(snaps, Fling.restingPoint(value.value, v), v)
        scope.launch {
            value.animateTo(target, spec, initialVelocity = v)
            onSettled(target)
        }
    }
}
```

APIs: `androidx.compose.ui.input.pointer.util.VelocityTracker` (`addPointerInputChange`, `calculateVelocity`,
`resetTracking`), `Animatable.snapTo/animateTo(targetValue, animationSpec, initialVelocity)`. All API levels.

Rules:
- Drag writes clamp at hard bounds (Convx: rubber-banding the live drag made the player sheet jitter, `BottomSheet.kt:620-650`);
  overshoot comes from the settle spring, whose `Animatable` has no upper bound. Exception: iOS sheets resist past the top
  detent; use `Rubber.band` on the overflow, not a hard clamp (Convx lacks this: `IosSheet.kt:203`).
- Tap and drag must feel identical: tap-driven open/close uses the same spring as the drag settle (`BottomSheet.kt:464-472`).
- Read `value.value` only in `graphicsLayer {}` / `offset {}`.

### 5.2 Selection puck with squash and stretch (tab bar, segmented, switch, slider)

Combine glisse's measured springs (REST vs DRAG retuning, `G/internal/segments.tsx:61-64,175-190`) with Convx's
physical feel: position in item units, `snapTo` while dragging, velocity-driven squash, the press bulge waits for the
puck to arrive (max 400 ms) before deflating (`DampedDragAnimation.kt:110-128`), whole bar tugs a few dp toward the drag
(`U/component/floatingtabbar/FloatingTabBarTabs.kt:155-163,205`).

```kotlin
// ORIGINAL (uses glisse constants from RovstiMotion, which lives in a file with the glisse header)
@Stable
class SelectionPuck(initialIndex: Int, private val count: Int, private val scope: CoroutineScope) {
    val position = Animatable(initialIndex.toFloat())      // in slots
    val lift = Animatable(0f)                               // 0 rest, 1 held
    val velocity = Animatable(0f)                           // slots per second, for squash only
    private val tracker = VelocityTracker()
    private var dragging = false

    fun press() {
        dragging = true
        tracker.resetTracking()
        scope.launch { lift.animateTo(1f, RovstiMotion.LiftDrag.spec(0.001f)) }
    }

    fun drag(change: PointerInputChange, deltaSlots: Float, slotWidthPx: Float) {
        tracker.addPointerInputChange(change)
        val next = (position.value + deltaSlots).coerceIn(0f, (count - 1).toFloat())
        val vSlots = tracker.calculateVelocity().x / slotWidthPx
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            position.snapTo(next)
            velocity.snapTo(vSlots)                 // live squash while dragging
        }
    }

    /** Called with the slot width so the tracker's px/s become slots/s. */
    fun release(slotWidthPx: Float, onSelect: (Int) -> Unit) {
        dragging = false
        val vSlots = tracker.calculateVelocity().x / slotWidthPx
        val rest = Fling.restingPoint(position.value * slotWidthPx, vSlots * slotWidthPx) / slotWidthPx
        val target = rest.roundToInt().coerceIn(0, count - 1)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { velocity.snapTo(vSlots) }
        scope.launch { velocity.animateTo(0f, spring(0.5f, 300f, 0.01f)) }
        scope.launch { position.animateTo(target.toFloat(), RovstiMotion.SelectRest.spec(0.001f), initialVelocity = vSlots) }
        scope.launch {
            withTimeoutOrNull(400) { snapshotFlow { abs(position.value - target) < 0.025f }.first { it } }
            lift.animateTo(0f, RovstiMotion.LiftRest.spec(0.001f))
        }
        onSelect(target)
    }

    /** Programmatic or tap selection: same bulge as a drag. */
    fun select(index: Int) {
        scope.launch { position.animateTo(index.toFloat(), RovstiMotion.SelectRest.spec(0.001f)) }
    }
}

// Drawing, in the puck's layer block only:
Modifier.graphicsLayer {
    val stretch = (abs(puck.velocity.value) / 12f).coerceAtMost(0.2f)   // own constants; ~20% at a hard flick
    val bulge = 1f + 0.35f * puck.lift.value                             // tab puck: Convx 1.39x, thumbs 1.5x
    scaleX = bulge * (1f + stretch)
    scaleY = bulge * (1f - 0.35f * stretch)
    translationX = puck.position.value * slotWidthPx
}
```

Glisse (no velocity) picks the target from the release point (`G/internal/segments.tsx:268`, `G/switch.tsx:163`):
we deliberately use the projection instead (glisse.md 2.4).

### 5.3 Swipe-to-reveal rows (Messages, Phone recents, Contacts)

Behaviour from `U/ios/component/IosSwipeActions.kt:49-149`: actions sit *behind* the row pinned to the edge; the row is
translated in `graphicsLayer`; release projects the flick; past 55% of row width = full swipe (fire first action and
close); else snap to `{-trailingWidth, 0, +leadingWidth}` nearest to the projected point with `swipeSettle` and the release
velocity. Action width 76 dp. Use `DragAxis` above with `snaps = floatArrayOf(-trailingW, 0f, leadingW)`; check the full-swipe
rule before snapping. Add a medium haptic tick when the row crosses the full-swipe threshold (Convx has none; iOS does).
Accessibility: expose every action as `customActions` on the row (`overlays.md` 8).

### 5.4 Sheets

See `overlays.md` 5 (detents, projection, 12% dismiss rule, resistance, nested scroll, pushback, grabber stretch).

### 5.5 Overscroll rubber band

Behaviour (`U/utils/IosOverscroll.kt:81-376`): two accumulators per axis (raw finger pull and the part still springing
back); displayed offset = `Rubber.band(raw + returning, viewport)`; stretch only during user-input drags, fling deltas
pass through so leftover velocity reaches `applyToFling`; opposite-direction scroll first pays down stretch; rebound
`animate(from, 0, initialVelocity = clamp(leftover, +-10000))` with `RovstiMotion.rebound(v)`; offsets applied in
`placeWithLayer { translationY = ... }` of a layout node; "in progress" only while the finger is down, so a returning
list does not eat the next tap.

Compose API: implement `androidx.compose.foundation.OverscrollEffect` and provide it via `LocalOverscrollFactory`.
**The interface changed in Compose 1.8** (node-based `val node: DelegatableNode`, `applyToScroll`, `applyToFling`,
`isInProgress`); grep the pinned foundation sources before writing it. Ship it in step 2 of the build order: every
list in every app gets it at once.

### 5.6 Elastic floating buttons (Stops map buttons, search pill)

Per-frame layer transform from the finger offset, saturating with `tanh` (behaviour in `U/component/GlassCircleButton.kt:63-91`;
magnitudes toned down after user feedback, `U/player/FloatingMiniPlayer.kt:~254`). Rovsti uses glisse's press maths
instead (section 7), which already gives grow + directional stretch + 8 dp translate; it is the cheaper, MIT-portable option.

---

## 6. Squash, stretch, morphs, staggering

- **Fixed-duration spring easing** (one clock for many values). Step response of a damped oscillator squeezed into
  `[0,1]` with `omega*T = 7.8` (residual under 0.6%) plus a linear correction so `f(1) == 1` (`BarMotion.kt:146-174`).
  Overshoot `exp(-zeta*pi/sqrt(1-zeta^2))`: zeta 0.66 -> 6%, 0.74 -> 3%.

```kotlin
// ORIGINAL
class SpringStepEasing(private val dampingRatio: Float, private val omegaT: Float = 7.8f) : Easing {
    private val endResidual = 1f - raw(1f)
    private fun raw(t: Float): Float {
        val w = omegaT * t
        val z = dampingRatio
        if (z >= 0.999f) return 1f - (1f + w) * exp(-w)
        val root = sqrt(1f - z * z)
        return 1f - exp(-z * w) * (cos(root * w) + (z / root) * sin(root * w))
    }
    override fun transform(fraction: Float): Float =
        if (fraction >= 1f) 1f else raw(fraction) + endResidual * fraction
}
/** Critically damped curve used for shared artwork/title bounds (x = 9t, normalised). */
val CriticalEasing = Easing { t -> (1f - (1f + 9f * t) * exp(-9f * t)) / (1f - 10f * exp(-9f)) }
```
  Tests: `f(0)=0`, `f(1)=1`, max overshoot within 1% of the analytic value.
- **Surface vs content.** Container extent uses the bouncy easing; its centre, icons and text use a critically damped
  one (`BarMotion.kt:185-209`). Labels get their own faster clock: gone on the first frame of a collapse, last to arrive
  on expand (keyframes alpha 0 until 50%, 1 at 80% of 150 ms, `FloatingTabBarTabs.kt:570-614`).
- **Per-axis stagger inside one object, not cascades across list items.** Menus widen before they grow tall (x stiffer
  than y). Accessory slides: x leads on collapse, y 67 ms later; y leads on expand, x 33 ms later (`BarMotion.kt:296,319-330`).
  No per-item list cascade anywhere in Convx; lists use `Modifier.animateItem()` (`U/player/Queue.kt:1244`).
- **Icon swap**: `AnimatedContent(fadeIn(tween(200)) + scaleIn(initialScale = 0.3f) togetherWith fadeOut(tween(160)) + scaleOut(targetScale = 0.3f))`
  with `SizeTransform(clip = false)`. At 0.85 start scale the swap reads as a hard cut (`U/utils/Motion.kt:112-124`).
- **Record-and-replay morphs**: for a big morph (mini call bar -> full call screen), record both ends into
  `GraphicsLayer`s (`rememberGraphicsLayer()`, Compose 1.7) and draw one interpolated clipped rect in `drawWithCache`,
  instead of composing two UIs per frame (`U/player/PlayerMorphLayers.kt:24-60`, `PlayerContainerMorph.kt:123-240`). Rect
  interpolation is *linear* because the spring on progress already shapes it (`PlayerSheetMotion.kt:43-58`).
  Content cross-fade windows in progress units: old content gone by 0.3, new content 0.1-0.6 (`BottomSheet.kt:64-80`).

---

## 7. Glass press (PORTED FROM GLISSE: `G/internal/press.ts:1-118`)

Four springs (scaleX, scaleY, translateX, translateY). Press grows the longer side by 15 dp (finger) or 6 dp (mouse),
stretches along the drag axis with a saturating rubber function, and translates up to 8 dp toward the finger; release
bounces (zeta 0.373). Layout never changes. Use on glass buttons, the Stops floating buttons and pills.

```kotlin
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.rovsti.ui.motion.GlisseSpring
import dev.rovsti.ui.motion.RovstiMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

internal class PressFeel(val growDp: Float, val press: GlisseSpring, val release: GlisseSpring)
internal val TouchFeel = PressFeel(15f, RovstiMotion.GlassPressDown, RovstiMotion.GlassPressUp)
internal val PointerFeel = PressFeel(6f, RovstiMotion.PointerPressDown, RovstiMotion.PointerPressUp)

/** Pure (JVM-testable): [scaleX, scaleY, translateX, translateY] for a press moved by (dx, dy) from its down point. */
fun pressTargets(width: Float, height: Float, dx0: Float, dy0: Float, grow: Float, stretchPx: Float, maxShift: Float): FloatArray {
    val base = 1f + grow / max(width, height)
    val aspect = width / height
    val dx = dx0 / aspect                      // horizontal drag counts less on wide buttons
    val dy = dy0
    val length = hypot(dx, dy)
    if (length == 0f) return floatArrayOf(base, base, 0f, 0f)
    val nx = dx / length
    val ny = dy / length
    val rubber = 1f - 1f / (1f + length / height / (5f * aspect))
    val stretch = 1f + (stretchPx / height) * rubber * abs(abs(nx) - abs(ny))
    val horizontal = abs(nx) > abs(ny)
    return floatArrayOf(
        base * if (horizontal) stretch else 1f / stretch,
        base * if (horizontal) 1f / stretch else stretch,
        nx * maxShift * rubber,
        ny * maxShift * rubber,
    )
}

private class GlassPressNode(var enabled: Boolean) : DelegatingNode(), LayoutModifierNode {
    private val values = arrayOf(Animatable(1f), Animatable(1f), Animatable(0f), Animatable(0f))

    private fun animate(targets: FloatArray, spring: GlisseSpring) {
        for (i in 0..3) coroutineScope.launch { values[i].animateTo(targets[i], spring.spec(if (i < 2) 0.0001f else 0.05f)) }
    }

    init {
        delegate(SuspendingPointerInputModifierNode {
            awaitEachGesture {
                // Observe only (Final pass, never consume) so the clickable on the same element still fires.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                if (!enabled) return@awaitEachGesture
                val feel = if (down.type == PointerType.Mouse) PointerFeel else TouchFeel
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val grow = feel.growDp.dp.toPx()
                val stretch = 6.dp.toPx()
                val shift = 8.dp.toPx()
                animate(pressTargets(w, h, 0f, 0f, grow, stretch, shift), feel.press)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    // A scroll parent took the gesture: let go.
                    if (change.isConsumed && change.positionChange() != Offset.Zero) break
                    val d = change.position - down.position
                    animate(pressTargets(w, h, d.x, d.y, grow, stretch, shift), feel.press)
                }
                animate(floatArrayOf(1f, 1f, 0f, 0f), feel.release)
            }
        })
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = values[0].value; scaleY = values[1].value
                translationX = values[2].value; translationY = values[3].value
            }
        }
    }
}

private data class GlassPressElement(val enabled: Boolean) : ModifierNodeElement<GlassPressNode>() {
    override fun create() = GlassPressNode(enabled)
    override fun update(node: GlassPressNode) { node.enabled = enabled }
}

/** Put it before `clickable`/`semantics` on glass controls. Never changes layout size. */
fun Modifier.glassPress(enabled: Boolean = true): Modifier = this then GlassPressElement(enabled)
```

APIs used: `SuspendingPointerInputModifierNode(...)` factory and `ModifierNodeElement` (compose-ui 1.5+),
`placeWithLayer` (all). Glass sampling under a pressed surface must use the *inverse* transform so the background does
not move with the button (Convx `B/internal/InverseLayerScope.kt` idea; `glass.md` 2).

The finger glow that goes with it (glisse `G/styles.css:111-130`): radial gradient radius 240 dp at the touch point,
white 0.035 -> 0.025 at 50% -> transparent, alpha 1 instantly on press and 0 over 220 ms; draw with
`drawWithContent { drawContent(); drawCircle(Brush.radialGradient(...), radius, center = touch) }`.

---

## 8. Shared elements (Contacts avatar -> detail, Messages chat header, Stops stop card -> stop detail)

APIs (Compose 1.7+, `@ExperimentalSharedTransitionApi` at some versions; verify): `SharedTransitionLayout`,
`Modifier.sharedBounds(sharedContentState = rememberSharedContentState(key), animatedVisibilityScope, boundsTransform,
renderInOverlayDuringTransition = true, zIndexInOverlay = 2f)`, `Modifier.sharedElement(...)`. Navigation Compose's
`composable { }` content lambda is an `AnimatedContentScope`, usable as the `animatedVisibilityScope`.

```kotlin
// ORIGINAL
private val AvatarBounds = BoundsTransform { _, _ -> tween(350, easing = CriticalEasing) }

with(sharedScope) {
    Avatar(
        contact,
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState("avatar-${contact.id}"),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = AvatarBounds,
            renderInOverlayDuringTransition = true,
            zIndexInOverlay = 2f,
        ),
    )
}
```

Rules from Convx (`U/utils/SharedArtwork.kt:137-140,182-190,320-330`): 350 ms (title, small art) / 500 ms (big art)
critically damped; expose `isTransitionActive` so screens pause heavy work (map tile decode, image prefetch) during the
flight; the source keeps its layout slot (hidden, not removed).

---

## 9. Predictive back

Manifest: `android:enableOnBackInvokedCallback="true"` (Convx `app/src/main/AndroidManifest.xml:59`). For NavHost
screen transitions (navigation-compose 2.8+ animates predictive back for you) keep push/pop as **tweens** so the
gesture can scrub them. For our own sheets and the call screen:

```kotlin
// ORIGINAL. androidx.activity.compose.PredictiveBackHandler (activity-compose 1.8+), BackEventCompat.progress 0..1.
PredictiveBackHandler(enabled = sheet.isOpen) { events: Flow<BackEventCompat> ->
    val start = sheet.offset.value
    try {
        events.collect { e ->
            val eased = 1f - (1f - e.progress) * (1f - e.progress)       // gentle first pixels
            sheet.offset.snapTo(start + (sheet.dismissedOffset - start) * 0.35f * eased)   // preview 35%
        }
        sheet.dismiss()                       // commit: same spring as a drag dismiss
    } catch (c: CancellationException) {
        sheet.settleBack()                    // user cancelled the back gesture
        throw c
    }
}
```

Numbers from `U/component/BottomSheet.kt:91,490-500` (35% travel, `1-(1-f)^2`). Overlays (menus, popovers) close on back
with their normal exit; only sheets preview.

---

## 10. Motion review checklist

- [ ] No raw spring/tween numbers at call sites: everything comes from `RovstiMotion`.
- [ ] Every spring passes a `visibilityThreshold`.
- [ ] Draggable things: `snapTo` (undispatched) while dragging, `animateTo(target, spec, initialVelocity)` after, target from `Fling`.
- [ ] Only one `Animatable` per quantity; cancellation deflates press state in `finally`.
- [ ] Overshoot on surfaces only; text/icons critically damped.
- [ ] Scrubbable transitions are tweens; settle phases are springs.
- [ ] Reduce motion goes through `.gated(...)`; no squash/parallax/bounce when on.
- [ ] Animated values read only in layer/draw/layout lambdas (`performance.md`).
- [ ] Compose test with manual clock covers interruption (retarget mid-flight) for each new gesture.
