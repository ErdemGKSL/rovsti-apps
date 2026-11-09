package dev.rovsti.contacts.trash

import androidx.compose.ui.test.onNodeWithTag
import dev.rovsti.contacts.list.HostedList
import dev.rovsti.contacts.list.ListHarness
import dev.rovsti.contacts.list.ListTags
import dev.rovsti.contacts.list.SampleBook
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * E4.5: the undo pill floats over the end of the page, so the page makes room: with the pill on show the list can be
 * scrolled until its last row ends above the pill, and the pill itself stays above the tab bar. When the pill is gone
 * the room goes with it.
 */
@Config(sdk = [35])
class UndoPillTest : ShotTest() {
    private fun toBottom(h: ListHarness) {
        compose.runOnUiThread {
            kotlinx.coroutines.runBlocking { h.listState.scrollToItem(h.listState.layoutInfo.totalItemsCount - 1) }
        }
        advance(500)
    }

    @Test fun theUndoPillClearsTheLastListRow() {
        val h = ListHarness(SampleBook.state())
        composeFrame { HostedList(h) }
        advance(300)
        val last =
            h.ui.rows
                .last()
                .id
        toBottom(h)
        val before = compose.onNodeWithTag(ListTags.row(last)).fetchSemanticsNode().boundsInRoot
        var undone = 0
        compose.runOnUiThread { h.overlays.showUndo("Deleted Ayşe Yılmaz", onUndo = { undone++ }) }
        advance(900)
        toBottom(h)
        val pill = compose.onNodeWithTag("toast").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag(ListTags.row(last)).fetchSemanticsNode().boundsInRoot
        assertTrue("the last row ($row) ends above the pill ($pill)", row.bottom <= pill.top + 0.5f)
        assertTrue("the row moved up to make room (was $before)", row.bottom < before.bottom)
        assertEquals(0, undone)
    }

    @Test fun theRoomGoesWhenThePillDoes() {
        val h = ListHarness(SampleBook.state())
        composeFrame { HostedList(h) }
        advance(300)
        val last =
            h.ui.rows
                .last()
                .id
        toBottom(h)
        val resting =
            compose
                .onNodeWithTag(ListTags.row(last))
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        compose.runOnUiThread { h.overlays.showUndo("Deleted Ayşe Yılmaz", onUndo = {}, durationMs = 1_000) }
        advance(900)
        toBottom(h)
        advance(1_500)
        assertEquals(0, h.overlays.toastClearancePx)
        toBottom(h)
        assertEquals(
            resting,
            compose
                .onNodeWithTag(ListTags.row(last))
                .fetchSemanticsNode()
                .boundsInRoot.bottom,
            1f,
        )
    }
}
