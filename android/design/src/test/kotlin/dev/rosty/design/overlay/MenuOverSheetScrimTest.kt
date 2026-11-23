package dev.rosty.design.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A menu that opens off a button does not dim a sheet below it (owner, 2026-11-19); over the plain page it keeps its
 * scrim, and a tap outside the menu closes only the menu.
 */
class MenuOverSheetScrimTest : ShotTest() {
    private val controller = OverlayController()

    private fun run(ms: Long) = repeat((ms / 100).toInt()) { advance(100) }

    private fun menu() =
        ButtonMenuEntry(
            Rect(300f, 120f, 400f, 200f),
            persistentListOf<MenuItem>(
                MenuItem.Action("Copy", onClick = {
                }),
            ),
        )

    private fun sheet() =
        SheetEntry(title = "Sheet", detents = persistentListOf(Detent.Large)) { BasicText("sheet body") }

    private fun start(dark: Boolean) =
        composeFrame(dark = dark) {
            OverlayHost(controller) { Box(Modifier.fillMaxSize().background(Color.White)) }
        }

    /** The ARGB of the window at ([x], [y]) px. */
    private fun pixel(
        x: Int,
        y: Int,
    ): Int {
        val image = compose.onRoot().captureToImage()
        val map = image.toPixelMap()
        return map[x, y].toArgb()
    }

    private fun assertSame(
        a: Int,
        b: Int,
    ) {
        for (shift in listOf(16, 8, 0)) {
            val d = abs(((a shr shift) and 255) - ((b shr shift) and 255))
            assertTrue("channel $shift moved by $d (${a.toString(16)} vs ${b.toString(16)})", d <= 2)
        }
    }

    private fun sheetDoesNotDim(dark: Boolean) {
        start(dark)
        compose.runOnUiThread { controller.present(sheet()) }
        run(1500)
        val w = compose.onRoot().captureToImage().width
        val h = compose.onRoot().captureToImage().height
        val x = 24
        val y = h - 24
        val before = pixel(x, y)
        compose.runOnUiThread { controller.present(menu()) }
        run(1000)
        assertSame(before, pixel(x, y))
        assertTrue(w > 0)
    }

    @Test fun overAnOpenSheetLightKeepsTheSurface() = sheetDoesNotDim(false)

    @Test fun overAnOpenSheetDarkKeepsTheSurface() = sheetDoesNotDim(true)

    @Test fun overThePlainPageItStillDims() {
        start(false)
        val h = compose.onRoot().captureToImage().height
        val before = pixel(24, h - 24)
        compose.runOnUiThread { controller.present(menu()) }
        run(1000)
        val after = pixel(24, h - 24)
        val beforeR = (before shr 16) and 255
        val afterR = (after shr 16) and 255
        // 0.14 black over white is 255 * 0.86 = 219.
        assertEquals(255, beforeR)
        assertEquals(219f, afterR.toFloat(), 3f)
    }

    @Test fun anOutsideTapClosesTheMenuAndKeepsTheSheet() {
        start(false)
        compose.runOnUiThread { controller.present(sheet()) }
        run(1500)
        val entry = menu()
        compose.runOnUiThread { controller.present(entry) }
        run(1000)
        val h = compose.onRoot().captureToImage().height
        compose.onRoot().performTouchInput { click(Offset(24f, (h - 24).toFloat())) }
        run(1500)
        assertFalse("the menu is gone", entry in controller.entries)
        assertTrue("the sheet stays", controller.entries.any { it is SheetEntry })
        compose.onNodeWithText("sheet body").assertExists()
    }
}
