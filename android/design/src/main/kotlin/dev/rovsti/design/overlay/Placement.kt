package dev.rovsti.design.overlay

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/** Which side of its anchor a panel opened on. */
enum class Side { Below, Above }

/**
 * Where a panel goes: its [topLeft] in window pixels, the [side] it opened on and the [origin] (fractions 0..1 of the
 * panel's own size) that points back at the anchor's centre, for scale-from-anchor animations.
 */
data class Placement(
    val topLeft: Offset,
    val side: Side,
    val origin: Offset,
)

/**
 * Places a [panel] next to its [anchor] inside the [safe] rectangle (ORIGINAL, pure and JVM-tested).
 *
 * - Prefers below the anchor (iOS pull-down), flips above when there is no room below, and when neither fits (a panel
 *   taller than the free space) clamps below so the top stays inside the margin; the panel is expected to scroll, see
 *   [panelMaxHeight].
 * - Horizontally the panel lines up with the anchor edge nearest to the window edge: the trailing edge for an anchor in
 *   the trailing half, mirrored in RTL ([rtl]). It is then clamped inside [margin] of the safe area, so a display
 *   cutout or a rounded corner never clips it.
 * - [gap] separates panel and anchor; [margin] keeps the panel off the safe area's edges. Both in pixels.
 *
 * Pass a [safe] rectangle that already excludes system bars, cutout and the IME; the panel then reacts to the keyboard
 * by flipping.
 */
fun placePanel(
    anchor: Rect,
    panel: Size,
    window: Size,
    safe: Rect,
    gap: Float,
    margin: Float,
    rtl: Boolean = false,
): Placement {
    val left = safe.left + margin
    val right = safe.right - margin
    val top = safe.top + margin
    val bottom = safe.bottom - margin
    val below = anchor.bottom + gap
    val above = anchor.top - gap - panel.height
    val y: Float
    val side: Side
    when {
        below + panel.height <= bottom -> {
            y = below
            side = Side.Below
        }

        above >= top -> {
            y = above
            side = Side.Above
        }

        else -> {
            y = below.coerceIn(top, maxOf(top, bottom - panel.height))
            side = Side.Below
        }
    }
    val trailingHalf = (anchor.center.x > window.width / 2f) != rtl
    val preferredX = if (trailingHalf) anchor.right - panel.width else anchor.left
    val x = preferredX.coerceIn(left, maxOf(left, right - panel.width))
    val origin =
        Offset(
            fraction(anchor.center.x - x, panel.width),
            fraction(anchor.center.y - y, panel.height),
        )
    return Placement(Offset(x, y), side, origin)
}

private fun fraction(
    value: Float,
    extent: Float,
): Float = if (extent <= 0f) 0.5f else (value / extent).coerceIn(0f, 1f)

/** The tallest a panel may be: the safe area minus a [margin] on both ends. Taller content scrolls inside the panel. */
fun panelMaxHeight(
    safe: Rect,
    margin: Float,
): Float = (safe.height - 2f * margin).coerceAtLeast(0f)

/**
 * The bounds of a trigger in window pixels. A plain field, not state: it is written on every layout pass and read
 * once when an overlay is presented, so scrolling a list of triggers never recomposes anything.
 */
@Stable
class AnchorHolder {
    var rect: Rect = Rect.Zero
}

/** Records this element's (unclipped) window bounds into [holder] (a field write per layout, no recomposition). */
fun Modifier.captureAnchor(holder: AnchorHolder): Modifier =
    onGloballyPositioned {
        // Not boundsInWindow(): that is clipped by a scrolling parent, and a half-visible row must keep its full size.
        holder.rect = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }
