package dev.rovsti.design.chrome

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The measured heights of the floating bars, shared with the content layer so that a scrolling list can leave room
 * for them. Only `onSizeChanged` of the bars writes these values, so they change when a bar really resizes and not
 * once per frame.
 *
 * [bottomBarPx] is the height of the bar surface alone: its bottom margin (system inset plus [BarMargin]) is added
 * by [contentInsets], never measured, so it can not be counted twice. A hidden bar writes 0.
 */
@Stable
class ChromeMetrics {
    var bottomBarPx by mutableIntStateOf(0)
    var topBarPx by mutableIntStateOf(0)

    /** The padding every scrolling screen puts on its list as `contentPadding` (rows then scroll under the bars). */
    @Composable
    fun contentInsets(): PaddingValues {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val system = WindowInsets.safeDrawing.asPaddingValues()
        return contentInsetsOf(
            system = SystemInsets.of(system, direction),
            topBar = with(density) { topBarPx.toDp() },
            bottomBar = with(density) { bottomBarPx.toDp() },
        )
    }

    companion object {
        /** The gap between the bar and the system navigation inset below it, and between a list's end and the bar. */
        val BarMargin: Dp = 8.dp
    }
}

/** The system insets of the window in dp (left and right are absolute, not start and end). */
data class SystemInsets(
    val left: Dp,
    val top: Dp,
    val right: Dp,
    val bottom: Dp,
) {
    companion object {
        fun of(
            padding: PaddingValues,
            direction: LayoutDirection,
        ) = SystemInsets(
            left = padding.calculateLeftPadding(direction),
            top = padding.calculateTopPadding(),
            right = padding.calculateRightPadding(direction),
            bottom = padding.calculateBottomPadding(),
        )
    }
}

/**
 * Pure rule behind [ChromeMetrics.contentInsets]: the top is the larger of the status inset and the top bar; the bottom
 * is the system inset plus the bar plus [ChromeMetrics.BarMargin] (just the system inset while no bar is shown).
 */
fun contentInsetsOf(
    system: SystemInsets,
    topBar: Dp,
    bottomBar: Dp,
): PaddingValues =
    PaddingValues.Absolute(
        left = system.left,
        right = system.right,
        top = maxOf(system.top, topBar),
        bottom = system.bottom + if (bottomBar > 0.dp) bottomBar + ChromeMetrics.BarMargin else 0.dp,
    )

/** The padding for the content layer. Lists use it as `contentPadding`, never as outer padding. */
val LocalContentInsets = compositionLocalOf { PaddingValues() }

/** The metrics object of the screen root, for the bars to write into. */
val LocalChromeMetrics = compositionLocalOf<ChromeMetrics?> { null }

/** The clear space between the last card of a page and the floating tab bar (or the navigation bar when there is none). */
val SettingsEndGap: Dp = 16.dp

/**
 * F37: the one end padding of every settings page. The page ends [SettingsEndGap] above the tab bar: the tab bar height,
 * the navigation-bar inset and the bar's margin all come from [LocalContentInsets], and the system inset alone covers a
 * page shown without the bar (or without a host, as in tests). The top of a page is its title block, which the page
 * scaffold lays out itself.
 */
@Composable
fun settingsPageEndPadding(): Dp {
    val insets = LocalContentInsets.current.calculateBottomPadding()
    val system = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return maxOf(insets, system) + SettingsEndGap
}
