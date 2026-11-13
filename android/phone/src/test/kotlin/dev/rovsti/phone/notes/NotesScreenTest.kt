package dev.rovsti.phone.notes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import dev.rovsti.phone.HostRust
import dev.rovsti.testing.ShotTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * E5.11 on the real shell over the REAL core; the Compose clock is manual (`autoAdvance = false`), so each wait lets the
 * core answer in real time and then moves the clock.
 */
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [35])
class NotesScreenTest : ShotTest() {
    private val host by lazy { HostRust("rovsti-notes-screen") }
    private var open: NotesPageRig? = null

    @After fun stop() {
        open?.close()
    }

    private fun show(
        notes: dev.rovsti.core.model.phone.Notes = sampleNotes(),
        fontScale: Float = 1f,
    ): NotesPageRig {
        val page = NotesPageRig(host, notes = notes).also { open = it }
        runBlocking { page.ready() }
        composeFrame(fontScale = fontScale) { page.Content() }
        settle { compose.onAllNodesWithTag(NotesTags.Page).fetchSemanticsNodes().isNotEmpty() }
        settle { page.rig.model.state.value.ready }
        advance(400)
        return page
    }

    /** Moves the clock until [done], giving the core real time to answer between frames. */
    private fun settle(done: () -> Boolean) {
        repeat(200) {
            if (done()) return
            Thread.sleep(20)
            advance(50)
        }
        throw AssertionError("did not settle")
    }

    private fun key(at: Int) = sampleNotes().notes[at].let { NotesViewModel.rowKey(it.number, it.atMs) }

    @Test fun theRowsShowThePersonTheDateAndTheFirstWords() {
        show()
        compose.onNodeWithTag(NotesTags.row(key(0))).assertIsDisplayed()
        compose.onNodeWithTag(NotesTags.row(key(2))).assertIsDisplayed()
        val nodes = compose.onAllNodesWithTag(NotesTags.row(key(0)), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, nodes.size)
    }

    @Test fun theLastRowEndsAboveTheTabBar() {
        show()
        val last = compose.onNodeWithTag(NotesTags.row(key(2))).getBoundsInRoot()
        val bar = compose.onNodeWithTag("tab-surface").getBoundsInRoot()
        assertTrue("last row bottom ${last.bottom} is above the bar top ${bar.top}", last.bottom <= bar.top)
    }

    @Test fun aSwipeDeletesTheNoteAndTheUndoPillRestoresIt() {
        val page = show()
        compose.onNodeWithTag(NotesTags.row(key(1))).performTouchInput { swipeLeft(durationMillis = 250) }
        settle { page.rig.model.state.value.total == 2 }
        advance(900)
        assertTrue(
            "the row left the list",
            compose.onAllNodesWithTag(NotesTags.row(key(1))).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("toast").assertIsDisplayed()
        compose.onNodeWithTag("toast-action").performClick()
        settle { page.rig.model.state.value.total == 3 }
        advance(900)
        compose.onNodeWithTag(NotesTags.row(key(1))).assertIsDisplayed()
        assertEquals(3, page.rig.base.store.notes.value.notes.size)
    }

    @Test fun typingInTheSearchFieldFiltersTheRows() {
        val page = show()
        compose.onNodeWithTag(NotesTags.Search).performTextInput("invoice")
        settle { page.rig.model.state.value.query == "invoice" && page.rig.model.state.value.rows.size == 1 }
        advance(600)
        compose.onNodeWithTag(NotesTags.row(key(1))).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(NotesTags.row(key(0))).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(NotesTags.Search).performTextInput("zzz")
        settle { page.rig.model.state.value.empty }
        advance(600)
        compose.onNodeWithTag(NotesTags.Empty).assertIsDisplayed()
    }

    @Test fun tappingARowOpensItsCallAndTheSheetsCloseGivesTheSearchBack() {
        val page = show()
        compose.onNodeWithTag(NotesTags.row(key(0))).performClick()
        settle { page.host.opened.isNotEmpty() }
        assertEquals(
            AYSE,
            page.host.opened
                .single()
                .line.number,
        )
        assertEquals(
            "Ayse Yilmaz",
            page.host.opened
                .single()
                .who,
        )
        compose.runOnUiThread { page.host.dismissOpened!!.invoke() }
        advance(300)
    }

    @Test fun shareAllAndExportInTheTopBarHandTheCoresTextAndCsvToAndroid() {
        val page = show()
        compose.onNodeWithTag(NotesTags.Share).performClick()
        settle { page.host.shared.isNotEmpty() }
        assertTrue(
            page.host.shared
                .single()
                .startsWith("Ayse Yilmaz, "),
        )
        compose.onNodeWithTag(NotesTags.Export).performClick()
        settle { page.host.saved.isNotEmpty() }
        assertEquals(
            "rovsti-call-notes-2023-11-14.csv",
            page.host.saved
                .single()
                .first,
        )
        assertTrue(
            page.host.saved
                .single()
                .second
                .startsWith("date,time,name,number,note"),
        )
    }

    @Test fun noNotesShowTheEmptyStateAndNoTopBarButtons() {
        show(
            notes =
                dev.rovsti.core.model.phone
                    .Notes(1, emptyList()),
        )
        compose.onNodeWithTag(NotesTags.Empty).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(NotesTags.Share).fetchSemanticsNodes().isEmpty())
    }
}
