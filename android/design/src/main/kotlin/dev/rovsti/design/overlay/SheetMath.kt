package dev.rovsti.design.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.motion.Fling
import dev.rovsti.design.motion.Rubber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/** Numbers of the sheet (SKILL overlays 5). */
object SheetMetrics {
    /** Opacity of the grouped-surface wash over the sheet glass: a bare glass sheet is unreadable under a list. */
    const val GlassWash = 0.92f

    val CornerRadius = 28.dp
    val GrabberWidth = 36.dp
    val GrabberHeight = 5.dp

    /** Space above the grabber and below it. */
    val GrabberTop = 5.dp
    val GrabberBottom = 8.dp

    /** The Large detent stops this far under the status bar (the page behind shows as a strip). */
    val LargeGap = 24.dp

    /** Default height of the Peek detent. */
    val PeekHeight = 120.dp

    /** Windows shorter than this (landscape phones) get the Large detent only. */
    const val CompactHeightDp = 480f

    const val ScrimAlpha = 0.32f

    /** A fling that would end this far (as a share of the window) below the lowest detent dismisses. */
    const val DismissMargin = 0.12f

    /** How far a back gesture previews the dismissal, as a share of the travel. */
    const val BackPreview = 0.35f

    /** Scale of the grabber stretch reference: `tanh(pull / 320 dp)`. */
    val GrabberStretchReference = 320.dp

    // Pushback of the page behind a modal sheet.
    const val PushBackScale = 0.91f
    val PushBackCorner = 32.dp
    const val PushBackDim = 0.22f

    // A sheet presented over a sheet steps the lower one back (ORIGINAL; iOS behaviour, no source).
    const val StackScale = 0.94f
    val StackLift = 10.dp
    const val StackDim = 0.12f

    /** The upper sheet's top stays at least this far under the stepped-back top of the lower one. */
    val StackMinGap = 10.dp

    /** How far above the top detent a drag may stretch, as a share of the window (the rubber-band scale). */
    const val RubberShare = 0.15f
}

/** A resting height of a sheet. Heights are measured from the bottom of the window. */
@Immutable
sealed interface Detent {
    /** A short sheet that peeks over the page, [height] tall. */
    data class Peek(
        val height: Dp = SheetMetrics.PeekHeight,
    ) : Detent

    /** Half of the window. */
    data object Medium : Detent

    /** As tall as it gets: the window minus the status bar and 24 dp. */
    data object Large : Detent

    data class Fixed(
        val height: Dp,
    ) : Detent

    data class Fraction(
        val fraction: Float,
    ) : Detent
}

/**
 * Where each detent sits as a distance from the top of the window, ascending: index 0 is the tallest sheet, the last
 * index the shortest. [density] is pixels per dp. Heights above Large are clamped to Large and detents that land on
 * the same pixel are merged. A window shorter than 480 dp gets the Large detent alone, however many were asked for.
 */
fun detentOffsets(
    detents: List<Detent>,
    containerHeight: Float,
    topInset: Float,
    density: Float,
): FloatArray {
    val largeTop = (topInset + SheetMetrics.LargeGap.value * density).coerceIn(0f, containerHeight)
    if (containerHeight / density < SheetMetrics.CompactHeightDp) return floatArrayOf(largeTop)
    val largeHeight = containerHeight - largeTop
    val offsets = ArrayList<Float>(detents.size)
    for (detent in detents) {
        val height =
            when (detent) {
                is Detent.Peek -> detent.height.value * density
                Detent.Medium -> containerHeight * 0.5f
                Detent.Large -> largeHeight
                is Detent.Fixed -> detent.height.value * density
                is Detent.Fraction -> containerHeight * detent.fraction
            }.coerceIn(0f, largeHeight)
        offsets += containerHeight - height
    }
    if (offsets.isEmpty()) offsets += largeTop
    offsets.sort()
    val merged = ArrayList<Float>(offsets.size)
    for (o in offsets) if (merged.isEmpty() || o - merged.last() >= 1f) merged += o
    return merged.toFloatArray()
}

/** What a released sheet does next. */
sealed interface SheetTarget {
    data class Snap(
        val offset: Float,
    ) : SheetTarget

    data object Dismiss : SheetTarget
}

/**
 * The release rule. The flick is projected with UIKit's normal deceleration (0.998); if it would end further than 12 %
 * of the window below the lowest detent and the sheet may be dismissed, the sheet leaves; otherwise it settles on the
 * detent nearest to the projected point. [offsets] is ascending as [detentOffsets] returns it.
 */
fun sheetReleaseTarget(
    offset: Float,
    velocity: Float,
    offsets: FloatArray,
    containerHeight: Float,
    dismissible: Boolean,
): SheetTarget {
    val rest = Fling.restingPoint(offset, velocity, Fling.NormalRate)
    if (dismissible && rest > offsets.last() + SheetMetrics.DismissMargin * containerHeight) return SheetTarget.Dismiss
    return SheetTarget.Snap(Fling.nearestSnap(offsets, rest, velocity))
}

/** 0 when dismissed, 1 at the lowest detent or higher: drives the scrim and the pushback of the page. */
fun sheetFraction(
    offset: Float,
    lowestOffset: Float,
    containerHeight: Float,
): Float {
    if (containerHeight <= 0f) return 0f
    val span = max(containerHeight - lowestOffset, 1f)
    return (1f - max(offset - lowestOffset, 0f) / span).coerceIn(0f, 1f)
}

/** Offset after a finger moved the sheet to [raw]: resists above [top] and, when it cannot be dismissed, below [bottom]. */
fun sheetDragOffset(
    raw: Float,
    top: Float,
    bottom: Float,
    containerHeight: Float,
    dismissible: Boolean,
): Float {
    val scale = containerHeight * SheetMetrics.RubberShare
    return when {
        raw < top -> top - Rubber.band(top - raw, scale)
        !dismissible && raw > bottom -> bottom + Rubber.band(raw - bottom, scale)
        else -> min(raw, containerHeight)
    }
}

/** Scale of the grabber while the sheet is pulled [pull] px from where it rests: wider and flatter, `tanh` saturating. */
fun grabberStretch(
    pull: Float,
    referencePx: Float,
): Pair<Float, Float> {
    val s = tanh(abs(pull) / referencePx)
    return (1f + 0.7f * s) to (1f - 0.3f * s)
}

/** Offset the sheet previews at back-gesture [progress]: 35 % of the way to [dismissed], eased with `1-(1-p)^2`. */
fun backPreviewOffset(
    start: Float,
    dismissed: Float,
    progress: Float,
): Float {
    val p = progress.coerceIn(0f, 1f)
    val eased = 1f - (1f - p) * (1f - p)
    return start + (dismissed - start) * SheetMetrics.BackPreview * eased
}

/** Top edge in px of a sheet that has stepped back: it keeps its top centre as pivot and moves up by [stepBackPx]. */
fun stackedTopPx(
    lowerTopPx: Float,
    stepBackPx: Float,
): Float = lowerTopPx - stepBackPx

/**
 * Clamps a detent [offset] (px from the window top) of a sheet presented over another so that its top is at least
 * [minGapPx] below the stepped-back top of the lower sheet; the lower sheet's grabber then always shows above it.
 */
fun upperDetentOffset(
    offset: Float,
    lowerTopPx: Float,
    stepBackPx: Float,
    minGapPx: Float,
): Float = if (lowerTopPx.isNaN()) offset else max(offset, stackedTopPx(lowerTopPx, stepBackPx) + minGapPx)
