// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Sizes and rules after glisse src/styles.css:233-239,358-377,512-535 and src/internal/segments.tsx:55-339.
package dev.rovsti.design.chrome

import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The tab bar's fixed numbers, in dp (glisse `.lg-tab-bar[data-icons]`). */
object TabBarSpec {
    const val CellWidthDp = 72f
    const val CellHeightDp = 52f
    const val PaddingDp = 4f
    const val GrowXDp = 24f
    const val GrowYDp = 16f

    /** A tap counts only when it ends within this distance of the bar (glisse `segments.tsx` `near`). */
    const val TapSlopDp = 24f

    /** Horizontal travel before a touch becomes a drag. */
    const val DragStartDp = 6f

    /** How far the lens lifts before it drops under the labels on a light page. */
    const val Under = 0.5f

    /** The most the puck may be squashed or stretched by speed. */
    const val MaxSquash = 0.2f

    /** The puck stays this far inside the bar's edge, so its corners follow the bar's own (radius h/2 + padding). */
    const val PuckInsetDp = 2f

    /** Bar height: cell plus padding on both sides. */
    const val BarHeightDp = CellHeightDp + 2 * PaddingDp

    /** Outer radius, glisse `calc(var(--lg-tab-height) / 2 + var(--lg-tab-inset))`. */
    const val CornerDp = CellHeightDp / 2f + PaddingDp
}

private fun clamp(
    v: Float,
    lo: Float,
    hi: Float,
) = min(hi, max(lo, v))

/** Width of one slot for a bar [barWidth] wide holding [count] tabs. */
fun slotWidth(
    barWidth: Float,
    count: Int,
    padding: Float,
): Float = (barWidth - 2f * padding) / max(1, count)

/**
 * The slot under bar-local [x] (glisse picks the tab of the pointer-down; this is the same lookup). Clamped to the
 * first and last slot, so a touch on the padding belongs to the nearest tab.
 */
fun slotUnder(
    x: Float,
    barWidth: Float,
    count: Int,
    padding: Float,
): Int {
    val slot = slotWidth(barWidth, count, padding)
    if (slot <= 0f) return 0
    return ((x - padding) / slot).toInt().coerceIn(0, max(0, count - 1))
}

/** The slot a drag released at [position] (in slots) lands on. */
fun slotAt(
    position: Float,
    count: Int,
): Int = position.roundToInt().coerceIn(0, max(0, count - 1))

/** True when a tap released at bar-local [releaseY] counts: within [slop] of the bar's top and bottom edges. */
fun isTap(
    releaseY: Float,
    barHeight: Float,
    slop: Float,
): Boolean = releaseY >= -slop && releaseY <= barHeight + slop

/**
 * The area the puck may occupy: the bar less [inset] on every side. Because the corner of the puck is concentric with
 * the bar's, anything inside this rect looks like it sits in the bar (owner complaint X4: the old puck stuck out).
 */
fun innerRect(
    barWidth: Float,
    barHeight: Float,
    inset: Float,
): Rect = Rect(inset, inset, max(inset, barWidth - inset), max(inset, barHeight - inset))

/**
 * The puck's rectangle in bar-local px (glisse `paint()`: `translateX(x) scale(1 + growX / width * lift, 1 + growY /
 * height * lift)` about the cell centre).
 *
 * [position] is in slots. [lift] 0..1 grows the cell by [growX] x [growY]. [squash] (clamped to [TabBarSpec.MaxSquash])
 * stretches it along x and thins it along y by a third of that. The result is then clamped into [innerRect], so no
 * combination of lift and squash can leave the bar.
 */
fun puckRect(
    position: Float,
    lift: Float,
    squash: Float,
    barWidth: Float,
    barHeight: Float,
    count: Int,
    padding: Float,
    cellHeight: Float,
    growX: Float,
    growY: Float,
    inset: Float,
): Rect {
    val slot = slotWidth(barWidth, count, padding)
    val l = clamp(lift, 0f, 1f)
    val s = clamp(squash, 0f, TabBarSpec.MaxSquash)
    val inner = innerRect(barWidth, barHeight, inset)
    val width = min((slot + growX * l) * (1f + s), inner.width)
    val height = min((cellHeight + growY * l) * (1f - s / 3f), inner.height)
    val centreX = padding + (clamp(position, 0f, max(0, count - 1).toFloat()) + 0.5f) * slot
    val centreY = barHeight / 2f
    val left = clamp(centreX - width / 2f, inner.left, inner.right - width)
    val top = clamp(centreY - height / 2f, inner.top, inner.bottom - height)
    return Rect(left, top, left + width, top + height)
}

/**
 * How a bar folds into the circle of its selected tab (Stops). Every value is a pure function of one [progress]
 * (0 expanded, 1 folded), so nothing can disagree with another.
 *
 * - [width]: bar width from the full width to a circle of the bar's height.
 * - [labelAlpha]: labels are gone in the first third, so they never cross-fade with anything.
 * - [otherAlpha]: the unselected icons fade out between 10 % and 60 %.
 * - [puckAlpha]: the puck is not needed once folded.
 * - [anchorBias]: -1 puts the bar at the start edge when folded, 0 centres it when expanded.
 */
data class FoldGeometry(
    val width: Float,
    val labelAlpha: Float,
    val otherAlpha: Float,
    val puckAlpha: Float,
    val anchorBias: Float,
)

fun foldGeometry(
    progress: Float,
    fullWidth: Float,
    foldedWidth: Float,
): FoldGeometry {
    val p = clamp(progress, 0f, 1f)
    return FoldGeometry(
        width = fullWidth + (foldedWidth - fullWidth) * p,
        labelAlpha = clamp(1f - p * 3f, 0f, 1f),
        otherAlpha = clamp(1f - (p - 0.1f) / 0.5f, 0f, 1f),
        puckAlpha = clamp(1f - p * 2f, 0f, 1f),
        anchorBias = -p,
    )
}
