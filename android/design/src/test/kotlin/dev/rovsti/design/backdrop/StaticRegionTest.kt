package dev.rovsti.design.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.backdrops.LayerBackdrop
import dev.rovsti.design.backdrop.backdrops.backdropStaticRegion
import dev.rovsti.design.backdrop.backdrops.layerBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StaticRegionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun captureInsideOneRegionIsStaticAndStraddlingTwoIsNot() {
        var backdrop: LayerBackdrop? = null
        compose.setContent {
            val b = rememberLayerBackdrop().also { backdrop = it }
            // A second generation: regions only learn their coordinates after the first layout, so the
            // first draw declares nothing (the fail-safe default) and the next one does.
            val tick = remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { tick.intValue = 1 }
            Box(
                Modifier.fillMaxSize().layerBackdrop(b).drawBehind {
                    drawRect(
                        Color.Gray.copy(
                            alpha =
                                0.1f * tick.intValue,
                        ),
                    )
                },
            ) {
                Box(Modifier.offset(0.dp, 0.dp).size(100.dp).backdropStaticRegion(b))
                Box(Modifier.offset(100.dp, 0.dp).size(100.dp).backdropStaticRegion(b))
            }
        }
        compose.waitForIdle()
        // Robolectric only draws on demand: capturing forces the draw passes.
        repeat(2) {
            compose.onRoot().captureToImage()
            compose.waitForIdle()
        }
        val b = backdrop!!
        assertEquals("two declared regions", 2, b.staticRegions.size)
        val px = b.staticRegions.first().width / 100f
        assertTrue(b.isFullyStatic(Rect(10f * px, 10f * px, 90f * px, 90f * px)))
        assertFalse("straddles the seam of two regions", b.isFullyStatic(Rect(60f * px, 10f * px, 140f * px, 90f * px)))
        assertFalse("outside every region", b.isFullyStatic(Rect(10f * px, 150f * px, 90f * px, 190f * px)))
    }
}
