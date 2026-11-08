// Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/IosPageSheet.kt
// (behaviour asserted here)
package dev.rovsti.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rovsti.design.ios.component.presentPageSheet
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.28: Convx's page sheet as a modal overlay entry: rises on the spring, closes by button, scrim and Back. */
@OptIn(ExperimentalTestApi::class)
class PageSheetTest : ShotTest() {
    private val controller = OverlayController()
    private var dismissed = 0

    private fun start() {
        composeFrame { OverlayHost(controller) { Box(Modifier.fillMaxSize()) { RovstiText("Page") } } }
    }

    private fun run(ms: Long) = repeat((ms / 100).toInt()) { advance(100) }

    private fun open() =
        compose
            .runOnUiThread {
                controller.presentPageSheet("New list", onDismissed = { dismissed++ }) { RovstiText("Body") }
            }.also { run(2000) }

    @Test
    fun itRisesToRestAndIsModal() {
        start()
        val entry = open()
        assertEquals(0f, entry.offset.value, 0.01f)
        assertTrue(entry.modal)
        compose.onNodeWithTag("page-sheet").assertExists()
    }

    @Test
    fun theCloseButtonSlidesItOutAndRemovesIt() {
        start()
        open()
        compose.onNodeWithTag("page-sheet-close").performClick()
        run(1000)
        compose.onNodeWithTag("page-sheet").assertDoesNotExist()
        assertFalse(controller.hasEntries)
        assertEquals(1, dismissed)
    }

    @Test
    fun backClosesIt() {
        start()
        open()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        run(1000)
        assertFalse(controller.hasEntries)
        assertEquals(1, dismissed)
    }
}
