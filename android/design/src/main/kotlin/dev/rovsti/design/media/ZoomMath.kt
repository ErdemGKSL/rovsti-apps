// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code (not ported).
package dev.rovsti.design.media

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.rovsti.design.motion.Fling
import dev.rovsti.design.motion.Rubber
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * What a photo is doing under the fingers: [scale] relative to the fitted image (1 = the whole photo letterboxed in
 * the viewport) and [offset], the translation in px of the image centre from the viewport centre.
 */
@Immutable
data class ZoomState(
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
) {
    val isZoomed: Boolean get() = scale > ZoomMath.ZoomedEpsilon

    companion object {
        val Fit = ZoomState()
    }
}

/** The result of a pan: the new state and the part of the movement the image could not absorb at its edges. */
@Immutable
data class PanResult(
    val state: ZoomState,
    val overflow: Offset,
)

/**
 * The maths of the photo viewer (pure, ORIGINAL; it follows the behaviour of `rovsti_core::zoom` rather than its code).
 * Everything is in px of the viewport; focus points are relative to the viewport centre. Gestures feed it the deltas
 * of one frame and draw the state it returns, so it holds no state of its own.
 */
object ZoomMath {
    const val MinScale = 1f
    const val MaxScale = 5f

    /** Scale a pinch may be pulled to (it rubber-bands towards these and springs back on release). */
    const val RubberMinScale = 0.8f
    const val RubberMaxScale = 6f
    const val DoubleTapScale = 2.5f

    /** Below this the photo counts as not zoomed: a vertical drag dismisses instead of panning. */
    const val ZoomedEpsilon = 1.01f

    /** A drag down this fraction of the viewport height is a dismissal; it is also where the dim reaches zero. */
    const val DismissFraction = 0.25f

    /** A flick must be at least this fast (px/s) to dismiss by projection alone. */
    const val DismissMinVelocity = 900f

    private const val RubberSpan = 0.2f
    private const val RubberSpanUp = 1f

    /** The size the photo takes when letterboxed into [viewport] (aspect kept, nothing cropped). */
    fun fit(
        content: Size,
        viewport: Size,
    ): Size {
        if (content.width <= 0f || content.height <= 0f || viewport.width <= 0f ||
            viewport.height <= 0f
        ) {
            return viewport
        }
        val ratio = min(viewport.width / content.width, viewport.height / content.height)
        return Size(content.width * ratio, content.height * ratio)
    }

    /** How far the image centre may sit from the viewport centre at [scale] without exposing an edge. */
    fun maxOffset(
        fitted: Size,
        viewport: Size,
        scale: Float,
    ): Offset =
        Offset(
            max(0f, (fitted.width * scale - viewport.width) / 2f),
            max(0f, (fitted.height * scale - viewport.height) / 2f),
        )

    /** [raw] scale pulled through the rubber band: 1..5 as is, asymptotes 0.8 below and 6 above. */
    fun rubberScale(raw: Float): Float =
        when {
            raw < MinScale -> MinScale - RubberSpan * (1f - exp(-(MinScale - raw) / RubberSpan))
            raw > MaxScale -> MaxScale + RubberSpanUp * (1f - exp(-(raw - MaxScale) / RubberSpanUp))
            else -> raw
        }

    /**
     * One pinch frame: [zoomChange] multiplies the scale and [panChange] moves the fingers' centroid, which sits at
     * [focus]. The image point under the focus stays under it. The offset is kept inside the bounds of the new scale.
     */
    fun pinch(
        state: ZoomState,
        zoomChange: Float,
        focus: Offset,
        panChange: Offset,
        fitted: Size,
        viewport: Size,
    ): ZoomState {
        // Undo the rubber of the previous frame, apply the change, rubber again: the band never drifts.
        val raw = unRubber(state.scale) * zoomChange
        val scale = rubberScale(raw)
        val ratio = scale / state.scale
        val moved = focus + panChange - (focus - state.offset) * ratio
        val limit = maxOffset(fitted, viewport, scale)
        return ZoomState(scale, Offset(moved.x.coerceIn(-limit.x, limit.x), moved.y.coerceIn(-limit.y, limit.y)))
    }

    /** Moves the image by [delta]; what the edges refuse comes back as [PanResult.overflow] (it can feed a page drag). */
    fun pan(
        state: ZoomState,
        delta: Offset,
        fitted: Size,
        viewport: Size,
    ): PanResult {
        val limit = maxOffset(fitted, viewport, state.scale)
        val raw = state.offset + delta
        val clamped = Offset(raw.x.coerceIn(-limit.x, limit.x), raw.y.coerceIn(-limit.y, limit.y))
        return PanResult(state.copy(offset = clamped), raw - clamped)
    }

    /** Double tap: from any zoom back to fit; from fit to [DoubleTapScale] with the tapped point staying under the finger. */
    fun doubleTap(
        state: ZoomState,
        focus: Offset,
        fitted: Size,
        viewport: Size,
    ): ZoomState {
        if (state.isZoomed) return ZoomState.Fit
        val limit = maxOffset(fitted, viewport, DoubleTapScale)
        val moved = focus - (focus - state.offset) * (DoubleTapScale / state.scale)
        return ZoomState(
            DoubleTapScale,
            Offset(moved.x.coerceIn(-limit.x, limit.x), moved.y.coerceIn(-limit.y, limit.y)),
        )
    }

    /** Where a released pinch rests: scale clamped to 1..5, the offset back inside the bounds of that scale. */
    fun settled(
        state: ZoomState,
        fitted: Size,
        viewport: Size,
    ): ZoomState {
        val scale = state.scale.coerceIn(MinScale, MaxScale)
        val limit = maxOffset(fitted, viewport, scale)
        val offset =
            if (scale <= MinScale) {
                Offset.Zero
            } else {
                Offset(state.offset.x.coerceIn(-limit.x, limit.x), state.offset.y.coerceIn(-limit.y, limit.y))
            }
        return ZoomState(scale, offset)
    }

    /** 0..1 for how far a vertical drag has gone towards dismissing; 1 at [DismissFraction] of the viewport height. */
    fun dismissProgress(
        dy: Float,
        height: Float,
    ): Float = if (height <= 0f) 0f else (abs(dy) / (height * DismissFraction)).coerceIn(0f, 1f)

    /**
     * Whether letting go at [dy] with [velocity] (px/s) dismisses: the drag is past [DismissFraction] of [height], or a
     * flick in the direction of the drag is projected past it.
     */
    fun shouldDismiss(
        dy: Float,
        velocity: Float,
        height: Float,
    ): Boolean {
        if (height <= 0f) return false
        val threshold = height * DismissFraction
        if (abs(dy) > threshold) return true
        val sameWay = dy == 0f || sign(velocity) == sign(dy)
        if (!sameWay || abs(velocity) < DismissMinVelocity) return false
        return abs(dy + Fling.projectedDistance(velocity, Fling.NormalRate)) > threshold
    }

    /**
     * The displayed offset of the page strip while the finger has dragged [dragPx] from page [index] of [count]:
     * 1:1 between pages, rubber-banded where there is no page to go to.
     */
    fun pageDrag(
        dragPx: Float,
        pageWidth: Float,
        index: Int,
        count: Int,
    ): Float {
        val noPage = (dragPx > 0f && index <= 0) || (dragPx < 0f && index >= count - 1)
        return if (noPage) Rubber.band(dragPx, pageWidth) else dragPx
    }

    /** The page a release settles on: half a page of projected travel turns the page, never past the first or last. */
    fun settlePage(
        dragPx: Float,
        velocity: Float,
        pageWidth: Float,
        index: Int,
        count: Int,
    ): Int {
        if (count <= 0) return 0
        val projected = dragPx + Fling.projectedDistance(velocity, Fling.NormalRate)
        val turned = (-projected / max(pageWidth, 1f)).roundToInt().coerceIn(-1, 1)
        return (index + turned).coerceIn(0, count - 1)
    }

    private fun unRubber(scale: Float): Float =
        when {
            scale < MinScale -> {
                val d = (1f - (MinScale - scale) / RubberSpan).coerceAtLeast(1e-4f)
                MinScale + RubberSpan * kotlin.math.ln(d)
            }

            scale > MaxScale -> {
                val d = (1f - (scale - MaxScale) / RubberSpanUp).coerceAtLeast(1e-4f)
                MaxScale - RubberSpanUp * kotlin.math.ln(d)
            }

            else -> {
                scale
            }
        }
}
