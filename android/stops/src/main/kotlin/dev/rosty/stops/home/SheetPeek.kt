package dev.rosty.stops.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.max
import kotlin.math.min

/**
 * How far down the map sheet's content its peek detent reaches (the owner's request: peek shows the title, the hint
 * and the plan's or the trip's buttons). The content's column registers itself with [root]; each element that must
 * show at peek carries [rememberPeekEnd]. [contentBottomPx] is the lowest bottom edge of those elements, in px from
 * the top of the content, so it does not move with the sheet or the scroll; it is written only when it changes.
 */
@Stable
class MapSheetPeek {
    private var root: LayoutCoordinates? = null
    private val marks = HashMap<Any, LayoutCoordinates>()

    /** The bottom of the last element that shows at peek, px from the content top; 0 until measured. */
    var contentBottomPx by mutableFloatStateOf(0f)
        private set

    /** The content column's coordinates (`onGloballyPositioned` after its `verticalScroll`). */
    fun root(coordinates: LayoutCoordinates) {
        root = coordinates
        refresh()
    }

    internal fun mark(
        key: Any,
        coordinates: LayoutCoordinates,
    ) {
        marks[key] = coordinates
        refresh()
    }

    internal fun drop(key: Any) {
        marks.remove(key)
        refresh()
    }

    private fun refresh() {
        val content = root?.takeIf { it.isAttached } ?: return
        var bottom = 0f
        for (mark in marks.values) {
            if (!mark.isAttached) continue
            bottom = max(bottom, content.localPositionOf(mark, Offset(0f, mark.size.height.toFloat())).y)
        }
        if (bottom != contentBottomPx) contentBottomPx = bottom
    }
}

/** The map sheet's peek measure, provided around its content; null elsewhere (a marker is then a no-op). */
val LocalMapSheetPeek = staticCompositionLocalOf<MapSheetPeek?> { null }

/** A modifier for an element the map sheet's peek must show in full (a no-op outside the map sheet). */
@Composable
fun rememberPeekEnd(): Modifier {
    val peek = LocalMapSheetPeek.current ?: return Modifier
    val key = remember { Any() }
    DisposableEffect(peek, key) { onDispose { peek.drop(key) } }
    return remember(peek, key) { Modifier.onGloballyPositioned { peek.mark(key, it) } }
}

/**
 * The map sheet's peek height in px (pure): the grabber ([grabberPx]), the content down to the last peek element
 * ([contentBottomPx]), a gap under it ([gapPx]) and the bottom safe area ([bottomInsetPx]): short content (the hint alone)
 * gives a short peek that ends right under it. It stays [maxGapPx] under the medium detent (half of [windowPx]), a cap
 * that never goes below [minPx], so peek stays the lowest detent at large text and in landscape (it is then cut, and the
 * sheet scrolls).
 * Before the content is measured it is [minPx].
 */
fun mapSheetPeekPx(
    contentBottomPx: Float,
    grabberPx: Float,
    gapPx: Float,
    bottomInsetPx: Float,
    windowPx: Float,
    minPx: Float,
    maxGapPx: Float,
): Float {
    if (contentBottomPx <= 0f) return minPx
    val wanted = grabberPx + contentBottomPx + gapPx + bottomInsetPx
    val most = max(minPx, windowPx * MEDIUM_SHARE - maxGapPx)
    return min(wanted, most)
}

/** The medium detent's share of the window (`Detent.Medium`). */
internal const val MEDIUM_SHARE = 0.5f
