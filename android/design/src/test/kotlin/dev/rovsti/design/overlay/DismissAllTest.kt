package dev.rovsti.design.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving the screen (a call placed from a sheet, owner bug 2026-11-13): `dismissAll` closes every sheet, popover and
 * alert the way Back would (dismiss callbacks run), keeps the toast, and counts the leave.
 */
class DismissAllTest : ShotTest() {
    private val controller = OverlayController()

    private fun run(ms: Long) = repeat((ms / 100).toInt()) { advance(100) }

    @Test fun everyOpenEntryClosesAndTheToastStays() {
        composeFrame { OverlayHost(controller) { Box(Modifier.fillMaxSize().testTag("page")) } }
        var dismissed = 0
        var cancelled = 0
        compose.runOnUiThread {
            controller.present(
                SheetEntry(
                    title = "Person",
                    detents = persistentListOf(Detent.Medium),
                    onDismissed = { dismissed++ },
                ) { BasicText("sheet body") },
            )
            controller.present(
                AlertEntry(
                    heading = "Delete?",
                    actions = persistentListOf(AlertAction("Cancel", AlertRole.Cancel) { cancelled++ }),
                ),
            )
            controller.showToast("Copied", durationMs = 60_000)
        }
        run(1500)
        compose.onNodeWithText("sheet body").assertExists()
        compose.onNodeWithText("Delete?").assertExists()
        assertEquals(0, controller.leaveCount)

        compose.runOnUiThread { controller.dismissAll() }
        run(2000)

        compose.onNodeWithText("sheet body").assertDoesNotExist()
        compose.onNodeWithText("Delete?").assertDoesNotExist()
        compose.onNodeWithText("Copied").assertExists()
        assertEquals("the sheet reported its dismissal", 1, dismissed)
        assertEquals("the alert ran its cancel action", 1, cancelled)
        assertEquals(1, controller.leaveCount)
        assertTrue("only the toast is left", controller.entries.all { it.passesBack })
        assertFalse(controller.entries.isEmpty())
    }

    @Test fun withNothingOpenItOnlyCounts() {
        controller.dismissAll()
        assertEquals(1, controller.leaveCount)
        assertFalse(controller.hasEntries)
    }
}
