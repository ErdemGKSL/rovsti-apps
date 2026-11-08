package dev.rovsti.design.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.sign

/**
 * Decides when the tab bar steps out of the way. It watches the scroll that the list already consumed and never takes
 * any of it: scrolling down by [collapsePx] minimises the bar, scrolling up by [expandPx] (or pulling past the top)
 * brings it back. The running total restarts whenever the direction flips, so a long slow wobble does nothing.
 *
 * [minimized] is written on the edge only, never per scroll event. Attach with `Modifier.nestedScroll(minimizer)` on an
 * ancestor of the list. Call from the main thread.
 */
@Stable
class TabBarMinimizer(
    private val collapsePx: Float,
    private val expandPx: Float,
) : NestedScrollConnection {
    var minimized by mutableStateOf(false)
        private set

    private var accumulated = 0f

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val dy = consumed.y
        if (dy == 0f) {
            // Pulling down at the very top: the list could not move, so the bar returns.
            if (available.y > PullPx && minimized) expand()
            return Offset.Zero
        }
        if (accumulated != 0f && sign(dy) != sign(accumulated)) accumulated = 0f
        accumulated += dy
        // Content moving up (dy < 0) is the user scrolling down.
        if (!minimized && accumulated < -collapsePx) {
            minimized = true
            accumulated = 0f
        } else if (minimized && accumulated > expandPx) {
            expand()
        }
        return Offset.Zero
    }

    /** Programmatic expand (a tap on the folded bar, a tab reselect). */
    fun expand() {
        accumulated = 0f
        if (minimized) minimized = false
    }

    companion object {
        const val CollapseDp = 40f
        const val ExpandDp = 8f
        private const val PullPx = 0.5f
    }
}

@Composable
fun rememberTabBarMinimizer(): TabBarMinimizer {
    val density = LocalDensity.current
    return remember(density) {
        TabBarMinimizer(
            collapsePx = TabBarMinimizer.CollapseDp * density.density,
            expandPx = TabBarMinimizer.ExpandDp * density.density,
        )
    }
}
