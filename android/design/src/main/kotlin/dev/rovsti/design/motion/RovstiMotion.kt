// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.sqrt

/** A glisse spring: stiffness and damping coefficient of a mass-1 oscillator. */
@Immutable
data class GlisseSpring(
    val stiffness: Float,
    val damping: Float,
) {
    /** Compose damping ratio: `damping / (2 sqrt(stiffness))`. Below 1 the spring overshoots. */
    val dampingRatio: Float get() = damping / (2f * sqrt(stiffness))

    /** A Compose spec; the threshold is required so a spring always ends on a definite frame. */
    fun <T : Any> spec(visibilityThreshold: T): SpringSpec<T> =
        spring(dampingRatio = dampingRatio, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
}

/**
 * Every motion number of the app (SKILL motion.md 2.1-2.3). Glisse springs are the `GlisseSpring` values;
 * the functions are Rovsti's own picks, written as [dampingRatio / stiffness] pairs. Each spring function
 * requires a `visibilityThreshold` (0.0001 for scales, 0.001 for unit progress, 0.5 for px offsets).
 */
object RovstiMotion {
    // Glisse (src/internal/press.ts, segments.tsx, switch.tsx, slider.tsx, menu.tsx).
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

    // Rovsti's own picks. Names say what the spring is for; the numbers are pinned by a test.
    fun <T : Any> press(t: T): SpringSpec<T> = spring(0.75f, 1200f, t)

    fun <T : Any> select(t: T): SpringSpec<T> = spring(0.76f, 750f, t)

    fun <T : Any> appear(t: T): SpringSpec<T> = spring(1f, 450f, t)

    fun <T : Any> morph(t: T): SpringSpec<T> = spring(1f, 950f, t)

    fun <T : Any> sheetSettle(t: T): SpringSpec<T> = spring(0.86f, 380f, t)

    fun <T : Any> sheetDismiss(t: T): SpringSpec<T> = spring(1f, 400f, t)

    fun <T : Any> pushBack(t: T): SpringSpec<T> = spring(1f, 380f, t)

    fun <T : Any> swipeSettle(t: T): SpringSpec<T> = spring(1f, 500f, t)

    fun <T : Any> menuGrowX(t: T): SpringSpec<T> = spring(0.62f, 560f, t)

    fun <T : Any> menuGrowY(t: T): SpringSpec<T> = spring(0.78f, 330f, t)

    fun <T : Any> menuLift(t: T): SpringSpec<T> = spring(0.80f, 420f, t)

    fun <T : Any> menuLand(t: T): SpringSpec<T> = spring(1f, 520f, t)

    fun <T : Any> previewReturn(t: T): SpringSpec<T> = spring(0.75f, 500f, t)

    fun <T : Any> titleCollapse(t: T): SpringSpec<T> = spring(1f, 500f, t)

    fun <T : Any> barMinimize(t: T): SpringSpec<T> = spring(0.90f, 500f, t)

    /** Decay of the velocity that drives squash and stretch after release. */
    fun <T : Any> squashDecay(t: T): SpringSpec<T> = spring(0.5f, 300f, t)

    /** Overscroll return: critically damped, stiffer when the pull was fast (px/s). */
    fun rebound(velocity: Float): SpringSpec<Float> =
        spring(1f, if (abs(velocity) > ReboundFastVelocity) 130f else 247f, 0.5f)

    const val ReboundFastVelocity = 5000f
    const val ReboundMaxSeedVelocity = 10_000f

    // Easings.
    val PushEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    val MenuExitEasing = CubicBezierEasing(0.4f, 0f, 0.9f, 0.5f)
    val MenuEnterEasing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1.03f)
    val PlateEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val AmbientEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

    /** CSS `ease`. */
    val FocusFillEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** CSS `ease-in-out`. */
    val GlowEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    // Tweens (milliseconds).
    const val PushMs = 400
    const val MenuExitMs = 140
    const val ScrimInMs = 180
    const val ScrimOutMs = 140
    const val PlateMs = 100
    const val FocusFillMs = 200
    const val GlowOutMs = 220

    fun <T> push(): FiniteAnimationSpec<T> = tween(PushMs, easing = PushEasing)

    fun <T> menuExit(): FiniteAnimationSpec<T> = tween(MenuExitMs, easing = MenuExitEasing)

    fun <T> scrimIn(): FiniteAnimationSpec<T> = tween(ScrimInMs, easing = MenuExitEasing)

    fun <T> scrimOut(): FiniteAnimationSpec<T> = tween(ScrimOutMs, easing = MenuExitEasing)

    fun <T> menuPlate(): FiniteAnimationSpec<T> = tween(PlateMs, easing = PlateEasing)

    fun <T> focusFill(): FiniteAnimationSpec<T> = tween(FocusFillMs, easing = FocusFillEasing)

    fun <T> glowOut(): FiniteAnimationSpec<T> = tween(GlowOutMs, easing = GlowEasing)

    // Constants.
    const val PushParallax = 0.30f
    const val PushDimAlpha = 0.85f
    const val PressScale = 0.96f
}
