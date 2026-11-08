package dev.rovsti.catalog

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import dev.rovsti.design.theme.GlassTintTokens
import dev.rovsti.design.theme.RovstiColors
import dev.rovsti.design.theme.contrastRatio
import dev.rovsti.testing.ShotTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F6: once the large title has collapsed, rows pass under the inline bar. Convx's TopFadeBlur blurs and fades them, so
 * (1) the strip of the bar around the inline title has far less luminance variance than the same-sized window of an
 * unblurred row lower on the page, and (2) the inline title reads at 4.5:1 or better against the page colour.
 */
class CollapsedBarLegibilityTest : ShotTest() {
    private fun variance(
        image: ImageBitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Double {
        val pixels = image.toPixelMap()
        val values = ArrayList<Double>()
        for (y in y0 until y1) for (x in x0 until x1) values += pixels[x, y].luminance().toDouble()
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    @Test
    fun theRegionAroundTheInlineTitleIsBlurredAndTheTitleReadsAtFourPointFive() {
        for (dark in listOf(false, true)) {
            val state = LazyListState()
            var page = Color.Unspecified
            composeFrame(dark = dark) {
                page = dev.rovsti.design.theme.RovstiTheme.colors.page
                LargeTitlePage(listState = state)
            }
            val titleHeight =
                state.layoutInfo.visibleItemsInfo
                    .first()
                    .size
            compose.runOnIdle { runBlocking { state.scrollBy(titleHeight * 3f) } }
            advance(16)
            advance(800)

            val image = compose.onRoot().captureToImage()
            val density = compose.activity.resources.displayMetrics.density
            val title = compose.onNodeWithTag("inline-title", useUnmergedTree = true).getBoundsInRoot()
            val bar = compose.onNodeWithTag("title-bar").getBoundsInRoot()

            // The strip of the bar between its top and the title's own box, across the left half (no toolbar there).
            val x0 = (16 * density).toInt()
            val x1 = ((bar.right - bar.left).value * density / 2).toInt()
            val y0 = 0
            val y1 = (title.top.value * density).toInt().coerceAtLeast(y0 + 8)
            val underBar = variance(image, x0, y0, x1, y1)

            // The same window shape on unblurred rows, below the fade: take the busiest of several positions.
            val h = y1 - y0
            val start = ((bar.bottom.value + 64 + 8) * density).toInt()
            val reference =
                (0 until 6)
                    .map { start + it * h }
                    .filter { it + h < image.height }
                    .maxOf { variance(image, x0, it, x1, it + h) }
            assertTrue("reference rows must have text to compare against ($reference)", reference > 1e-4)
            assertTrue(
                "dark=$dark: variance under the bar $underBar must be well below an unblurred row's $reference",
                underBar < reference * 0.4,
            )

            val titleColor = GlassTintTokens.glassContentColor(isDark = dark, isTitle = true)
            val ratio = contrastRatio(titleColor, page)
            assertTrue("dark=$dark: title contrast $ratio", ratio >= 4.5)
        }
    }
}
