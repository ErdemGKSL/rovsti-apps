package dev.rosty.design.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SheetEntry.updateDetents] on a shown sheet: a sheet resting at peek springs to the new peek height and stays the
 * lowest detent, and a sheet resting on another detent stays there.
 */
class SheetDetentUpdateTest : ShotTest() {
    private val controller = OverlayController()

    private fun run(ms: Long) = repeat((ms / 100).toInt()) { advance(100) }

    private fun present(initial: Detent): SheetEntry {
        val entry =
            SheetEntry(
                title = "Map",
                detents = persistentListOf(Detent.Peek(120.dp), Detent.Medium, Detent.Large),
                initialDetent = initial,
                modal = false,
                dismissible = false,
            ) { BasicText("sheet body") }
        composeFrame { OverlayHost(controller) { Box(Modifier.fillMaxSize()) } }
        compose.runOnUiThread { controller.present(entry) }
        run(1500)
        return entry
    }

    private fun visibleDp(entry: SheetEntry): Float {
        val sheet = entry.sheet!!
        val density = compose.activity.resources.displayMetrics.density
        return (sheet.containerHeight - sheet.offset.value) / density
    }

    @Test fun aSheetAtPeekFollowsTheNewPeekHeight() {
        val entry = present(Detent.Peek(120.dp))
        assertEquals(120f, visibleDp(entry), 1f)

        compose.runOnUiThread {
            entry.updateDetents(persistentListOf(Detent.Peek(260.dp), Detent.Medium, Detent.Large))
        }
        run(1500)
        assertEquals(260f, visibleDp(entry), 1f)
        val sheet = entry.sheet!!
        assertEquals("peek is still the lowest detent", sheet.bottomOffset, sheet.settledOffset, 0.5f)
        assertTrue(entry.detents.first() == Detent.Peek(260.dp))
    }

    @Test fun aSheetAtMediumStaysAtMedium() {
        val entry = present(Detent.Medium)
        val medium = visibleDp(entry)
        compose.runOnUiThread {
            entry.updateDetents(persistentListOf(Detent.Peek(260.dp), Detent.Medium, Detent.Large))
        }
        run(1500)
        assertEquals(medium, visibleDp(entry), 1f)
    }
}
