package dev.rovsti.catalog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.rovsti.design.chrome.ChromeMetrics
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.RovstiTabBar
import dev.rovsti.design.glass.glassSource
import dev.rovsti.design.glass.rememberGlassSource
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1: Flat chrome (the tier every test runs in) must not let busy content be read through it. */
class FlatLegibilityTest : ShotTest() {
    private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    /** Max minus min luminance over a patch of [side] px at ([x], [y]). */
    private fun spread(
        image: ImageBitmap,
        x: Int,
        y: Int,
        side: Int,
    ): Float {
        val map = image.toPixelMap()
        var lo = 1f
        var hi = 0f
        for (j in y until y + side) {
            for (i in x until x + side) {
                val l = luminance(map[i, j])
                lo = minOf(lo, l)
                hi = maxOf(hi, l)
            }
        }
        return hi - lo
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    @androidx.compose.runtime.Composable
    private fun Stripes(modifier: Modifier = Modifier) {
        Canvas(modifier) {
            val h = 4.dp.toPx()
            var y = 0f
            var black = true
            while (y < size.height) {
                drawRect(if (black) Color.Black else Color.White, Offset(0f, y), Size(size.width, h))
                y += h
                black = !black
            }
        }
    }

    @Test
    fun tabBarShowsAtMostFourPercentOfHighContrastBackdrop() {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) {
                val glass = rememberGlassSource()
                val chrome = remember { ChromeMetrics() }
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize().glassSource(glass, RovstiTheme.colors.page),
                    ) { Stripes(Modifier.fillMaxSize()) }
                    RovstiTabBar(
                        tabs = CatalogTabs,
                        selected = 3,
                        onSelect = {},
                        onReselect = {},
                        glass = glass,
                        modifier = Modifier.align(Alignment.BottomCenter).testTag("tab-bar"),
                        chrome = chrome,
                        imeVisible = { false },
                    )
                }
            }
            advance(500)
            val g = BarGeometry(this)
            val image = compose.onNodeWithTag("tab-surface").captureToImage()
            // A 6 x 6 dp patch on the boundary between slot 0 and slot 1: no label, no puck (selected is slot 3).
            val x = (g.padding + g.slot - 3f * density).toInt()
            val y = (image.height / 2f - 3f * density).toInt()
            val side = (6f * density).toInt()
            val s = spread(image, x, y, side)
            assertTrue("dark=$dark luminance spread $s must be < 0.05", s < 0.05f)
        }
    }

    @Test
    fun collapsedTopBandIsOpaque() {
        for (dark in listOf(false, true)) {
            val state = LazyListState()
            composeFrame(dark = dark) {
                val glass = rememberGlassSource()
                LargeTitleScaffold(
                    title = "Title",
                    state = state,
                    actions = persistentListOf(),
                    glass = glass,
                ) {
                    items(80) {
                        Box(
                            Modifier.fillMaxWidth().height(4.dp).background(
                                if (it % 2 ==
                                    0
                                ) {
                                    Color.Black
                                } else {
                                    Color.White
                                },
                            ),
                        )
                    }
                }
            }
            val titleHeight =
                state.layoutInfo.visibleItemsInfo
                    .first()
                    .size
            compose.runOnIdle { runBlocking { state.scrollBy(titleHeight * 3f + 400f) } }
            advance(1000)
            val image = compose.onRoot().captureToImage()
            // Left of the centred inline title, below the status bar, inside the 52 dp band.
            val x = (4f * density).toInt()
            val y = ((28f + 20f) * density).toInt()
            val s = spread(image, x, y, (6f * density).toInt())
            assertTrue("dark=$dark band luminance spread $s must be < 0.05", s < 0.05f)
        }
    }
}
