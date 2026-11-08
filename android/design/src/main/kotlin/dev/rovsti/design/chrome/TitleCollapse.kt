package dev.rovsti.design.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.motion.gated
import kotlinx.coroutines.flow.collectLatest

/** The collapse is a unit progress, so its spring settles at this distance from the target. */
private const val CollapseThreshold = 0.001f

/**
 * How far the large title has scrolled away: 0 while the list is at the top, 1 once the title item is gone, and
 * linear in between (ORIGINAL). [firstIndex] and [firstOffsetPx] are the list's first visible item and how many pixels
 * of it are scrolled off. A title height of 0 or less counts as 1 px, so the result is always defined.
 */
fun titleCollapseFraction(
    firstIndex: Int,
    firstOffsetPx: Int,
    titleHeightPx: Float,
): Float {
    if (firstIndex > 0) return 1f
    val height = if (titleHeightPx.isNaN()) 1f else titleHeightPx.coerceAtLeast(1f)
    return (firstOffsetPx / height).coerceIn(0f, 1f)
}

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Opacity of the inline (bar) title for collapse fraction [f]: 0 until the large title is 65 % gone, then a smoothstep
 * to 1 (ORIGINAL). It is 0 wherever [largeTitleAlpha] is above 0, so two titles are never readable at once.
 */
fun inlineTitleAlpha(f: Float): Float = smoothstep((f - 0.65f) / 0.35f)

/** Opacity of the large title for collapse fraction [f]: 1 at the top, 0 from 65 % collapse on (ORIGINAL). */
fun largeTitleAlpha(f: Float): Float = 1f - smoothstep(f / 0.65f)

/**
 * The collapse fraction of this list, smoothed by the `titleCollapse` spring so the inline title never jumps when a
 * scroll position is restored or the list is flung. Read the result only inside `graphicsLayer` or draw lambdas.
 * [titleHeightPx] is read lazily, so a height that is measured later does not recompose the caller.
 *
 * With reduce motion on, the fraction follows the scroll directly.
 */
@Composable
fun LazyListState.rememberTitleCollapse(titleHeightPx: () -> Float): State<Float> {
    val state = this
    val raw =
        remember(state) {
            derivedStateOf {
                titleCollapseFraction(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, titleHeightPx())
            }
        }
    val smoothed = remember(state) { Animatable(raw.value) }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(raw, reduce) {
        snapshotFlow { raw.value }.collectLatest { target ->
            smoothed.animateTo(target, RovstiMotion.titleCollapse(CollapseThreshold).gated(reduce))
        }
    }
    return smoothed.asState()
}
