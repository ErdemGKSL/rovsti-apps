package dev.rovsti.design.overlay

import androidx.compose.ui.geometry.Offset
import dev.rovsti.design.motion.Fling
import kotlin.math.abs

/** Where a released toast goes. */
enum class ToastSwipe { Stay, Down, Left, Right }

/**
 * Pure rules of the toast (ORIGINAL; SKILL overlays 6), kept out of the composables so they can be tested on the JVM.
 */
object ToastMath {
    /**
     * Whether a swipe that ends at [offset] with [velocity] (px/s) throws the toast away. Each axis is judged by where
     * the finger would have come to rest ([Fling.restingPoint]) against its threshold, so a short quick flick counts
     * and a slow drag has to travel. Upward movement never dismisses. When both axes qualify the one that passes its
     * threshold by the larger factor wins.
     */
    fun outcome(
        offset: Offset,
        velocity: Offset,
        downThresholdPx: Float,
        sideThresholdPx: Float,
    ): ToastSwipe {
        val restY = Fling.restingPoint(offset.y, velocity.y)
        val restX = Fling.restingPoint(offset.x, velocity.x)
        val down = if (restY > 0f) restY / downThresholdPx else 0f
        val side = abs(restX) / sideThresholdPx
        if (down < 1f && side < 1f) return ToastSwipe.Stay
        return when {
            down >= side -> ToastSwipe.Down
            restX < 0f -> ToastSwipe.Left
            else -> ToastSwipe.Right
        }
    }

    /**
     * Gap between the toast's bottom edge and the window bottom. With a bar the toast clears the bar's top by [gapPx]:
     * system inset, the bar's own bottom margin, the measured bar height, then the gap. Without a bar it sits [gapPx]
     * above the system inset. [systemBottomPx] is the navigation (or keyboard) inset.
     */
    fun bottomGapPx(
        systemBottomPx: Float,
        barPx: Int,
        barMarginPx: Float,
        gapPx: Float,
    ): Float = systemBottomPx + gapPx + if (barPx > 0) barPx + barMarginPx else 0f

    /** The countdown ring's sweep in degrees for [elapsed] (0..1) of the time: full at the start, empty at the end. */
    fun ringSweep(elapsed: Float): Float = 360f * (1f - elapsed.coerceIn(0f, 1f))

    /** A downward drag is followed 1:1, an upward one is held back to a quarter so the toast feels tethered. */
    fun rubberY(rawY: Float): Float = if (rawY < 0f) rawY * UpResistance else rawY

    private const val UpResistance = 0.25f
}
