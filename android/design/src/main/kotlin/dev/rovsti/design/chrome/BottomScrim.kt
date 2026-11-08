package dev.rovsti.design.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.theme.RovstiTheme

/** Stops of the bottom scrim: position along the run (0 top, 1 bottom) and the share of the page colour there. */
internal val ScrimStops = listOf(0f to 0f, 0.35f to 0.15f, 0.6f to 0.4f, 0.85f to 0.7f, 1f to 1f)

/** The brush of a progressive scrim from transparent to [page], in the stops above. */
internal fun progressiveBrush(page: Color): Brush =
    Brush.verticalGradient(
        colorStops =
            ScrimStops
                .map { (at, alpha) -> at to page.copy(alpha = alpha) }
                .toTypedArray(),
    )

/**
 * A progressive fade from transparent to the page colour behind a floating bar, so icons stay legible without a hard
 * edge. It covers the bar, its margin and the navigation inset, plus [run] above the bar. Put it between the content and
 * the bar, never inside the glass source. Nothing is drawn while no bar is shown.
 */
@Composable
fun BottomScrim(
    metrics: ChromeMetrics,
    modifier: Modifier = Modifier,
    run: Dp = 32.dp,
) {
    if (metrics.bottomBarPx <= 0) return
    val density = LocalDensity.current
    val inset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val total = with(density) { metrics.bottomBarPx.toDp() } + ChromeMetrics.BarMargin + inset + run
    Box(modifier.fillMaxWidth().height(total).background(progressiveBrush(RovstiTheme.colors.page)))
}
