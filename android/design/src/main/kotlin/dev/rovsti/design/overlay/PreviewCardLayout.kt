package dev.rovsti.design.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Dimensions of the preview-card variant of the context menu, in dp (ORIGINAL; numbers from SKILL overlays 3.3). */
object PreviewCardMetrics {
    /** Space between the card or panel and the safe area's side edges. */
    const val SideMargin = 16f

    /** Space between the card and the action panel under it. */
    const val Gap = 8f

    /** The card never gets narrower or lower than this. */
    const val MinCard = 140f

    /** The widest a card gets. */
    const val MaxCardWidth = 340f

    /** Share of the window width the card takes when there is room. */
    const val WidthFraction = 0.78f

    /** Room kept above the card below the status bar. */
    const val TopClearance = 8f

    /** Room kept under the group above the navigation bar. */
    const val BottomClearance = 16f

    /** Corner radius of the card. */
    const val CardRadius = 22f
}

/**
 * Where the preview card and the action panel go: [card] and [panel] in window pixels, and whether the group hangs on
 * the [trailing] (right) side of the window, which decides the transform origin of the panel.
 */
data class PreviewCardPlacement(
    val card: Rect,
    val panel: Rect,
    val trailing: Boolean,
)

/**
 * Lays out the card that previews a long-pressed [item] and the action panel under it (ORIGINAL, pure and JVM-tested).
 *
 * - The card is `min(0.78 * window width, 340 dp)` wide and never narrower than 140 dp; it never wider than the safe
 *   width minus both side margins. Its height starts at [cardHeight] and shrinks so that card, a gap and the panel fit
 *   between the top limit (safe top + 8 dp) and the bottom limit (safe bottom - 16 dp), but never below 140 dp.
 * - The group starts as high as the item (the card grows out of it) and slides up when that would push it past the
 *   bottom limit, never above the top limit.
 * - An item in the right half of the window right-aligns the card and the panel; one in the left half left-aligns
 *   them. An item centred in the window (a full-width row) follows [rtl]: leading side.
 *
 * All lengths in pixels; [pxPerDp] converts the dp constants of [PreviewCardMetrics].
 */
fun previewCardLayout(
    item: Rect,
    window: Size,
    safe: Rect,
    panelSize: Size,
    cardHeight: Float,
    pxPerDp: Float,
    rtl: Boolean = false,
): PreviewCardPlacement {
    val side = PreviewCardMetrics.SideMargin * pxPerDp
    val gap = PreviewCardMetrics.Gap * pxPerDp
    val minCard = PreviewCardMetrics.MinCard * pxPerDp
    val topLimit = safe.top + PreviewCardMetrics.TopClearance * pxPerDp
    val bottomLimit = safe.bottom - PreviewCardMetrics.BottomClearance * pxPerDp
    val left = safe.left + side
    val right = safe.right - side

    val wanted = min(PreviewCardMetrics.WidthFraction * window.width, PreviewCardMetrics.MaxCardWidth * pxPerDp)
    val cardWidth = max(minCard, min(wanted, right - left))

    val room = bottomLimit - topLimit - gap - panelSize.height
    val height = cardHeight.coerceIn(minCard, max(minCard, room))

    val centred = abs(item.center.x - window.width / 2f) < 1f
    val trailing = if (centred) rtl else item.center.x > window.width / 2f
    val cardLeft = if (trailing) right - cardWidth else left
    val panelWidth = min(panelSize.width, right - left)
    val panelLeft = if (trailing) cardLeft + cardWidth - panelWidth else cardLeft

    val total = height + gap + panelSize.height
    val top = item.top.coerceIn(topLimit, max(topLimit, bottomLimit - total))
    val card = Rect(Offset(cardLeft, top), Size(cardWidth, height))
    val panel = Rect(Offset(panelLeft, top + height + gap), Size(panelWidth, panelSize.height))
    return PreviewCardPlacement(card, panel, trailing)
}

/**
 * How far a dragged card has gone back toward its [home] (0 at its resting place, 1 at the item), by projecting the
 * applied [drag] on the way home. Pure; used for the 40 % dismissal rule.
 */
fun progressTowardHome(
    drag: Offset,
    home: Offset,
): Float {
    val lengthSquared = home.x * home.x + home.y * home.y
    if (lengthSquared <= 0f) return 0f
    return (drag.x * home.x + drag.y * home.y) / lengthSquared
}

/** Fraction of the way home at which a released card is dismissed. */
const val DismissProgress = 0.4f

/**
 * Whether releasing a dragged card dismisses it: the finger went down more than [thresholdPx] (64 dp) or the card has
 * gone 40 % of the way home.
 */
fun shouldDismissPreview(
    drag: Offset,
    home: Offset,
    thresholdPx: Float,
): Boolean = drag.y > thresholdPx || progressTowardHome(drag, home) >= DismissProgress
