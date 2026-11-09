package dev.rovsti.contacts.list

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** Select mode (E4.8) on the real shell: entering, tapping, the toolbar, and Back leaving select mode first. */
@Config(sdk = [35])
class SelectModeScreenTest : ShotTest() {
    private fun show(h: ListHarness = ListHarness(SampleBook.state()).also { it.simulateSelect = true }): ListHarness {
        composeFrame { HostedList(h) }
        advance(200)
        return h
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(300)
    }

    @Test fun theToolbarButtonEntersSelectModeAndTheTitleCounts() {
        val h = show()
        compose.onNodeWithTag("toolbar-select").performClick()
        advance(400)
        assertEquals(ContactsListAction.EnterSelect(null), h.actions.last())
        compose.onNodeWithText("Select contacts").assertIsDisplayed()
        compose.onNodeWithTag(SelectTags.Toolbar).assertIsDisplayed()
        compose.onNodeWithTag(SelectTags.Cancel).assertIsDisplayed()
        compose.onNodeWithTag(SelectTags.SelectAll).assertIsDisplayed()
        compose.onNodeWithTag(ListTags.row(1)).performClick()
        advance(300)
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithTag(ListTags.row(1)).assert(isSelected())
        compose.onNodeWithTag(ListTags.row(2)).performClick()
        advance(300)
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        assertEquals(setOf(1L, 2L), h.ui.selectMode!!.selected)
    }

    @Test fun theRowMenuHasSelectAndItEntersWithThatRow() {
        val h = show()
        compose.onNodeWithTag(ListTags.row(11)).performTouchInput { longClick(durationMillis = 700) }
        advance(900)
        compose.onNodeWithText("Select").performClick()
        advance(600)
        assertEquals(ContactsListAction.EnterSelect(11), h.actions.last())
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithTag(ListTags.row(11)).assert(isSelected())
    }

    @Test fun selectAllChangesToDeselectAll() {
        val h = show()
        compose.onNodeWithTag("toolbar-select").performClick()
        advance(400)
        compose.onNodeWithTag(SelectTags.SelectAll).performClick()
        advance(400)
        assertEquals(
            40,
            h.ui.selectMode!!
                .selected.size,
        )
        compose.onNodeWithText("Deselect All").assertIsDisplayed()
        compose.onNodeWithText("40 selected").assertIsDisplayed()
    }

    @Test fun theToolbarButtonsSendTheirActions() {
        val h = show()
        compose.onNodeWithTag("toolbar-select").performClick()
        advance(400)
        compose.onNodeWithTag(ListTags.row(1)).performClick()
        advance(300)
        compose.onNodeWithTag(SelectTags.Share).performClick()
        compose.onNodeWithTag(SelectTags.AddToGroup).performClick()
        compose.onNodeWithTag(SelectTags.Star).performClick()
        compose.onNodeWithTag(SelectTags.Delete).performClick()
        advance(100)
        val sent = h.actions.toSet()
        assertTrue(ContactsListAction.ShareSelected in sent)
        assertTrue(ContactsListAction.AskAddToGroup in sent)
        assertTrue(ContactsListAction.StarSelected in sent)
        assertTrue(ContactsListAction.AskDeleteSelected in sent)
    }

    @Test fun nothingIsSelectedSoTheBulkButtonsDoNothing() {
        val h = show()
        compose.onNodeWithTag("toolbar-select").performClick()
        advance(400)
        compose.onNodeWithTag(SelectTags.Delete).performClick()
        compose.onNodeWithTag(SelectTags.Share).performClick()
        advance(100)
        assertFalse(ContactsListAction.AskDeleteSelected in h.actions)
        assertFalse(ContactsListAction.ShareSelected in h.actions)
    }

    @Test fun backLeavesSelectModeBeforeAnythingElse() {
        val h = show()
        compose.onNodeWithTag("toolbar-select").performClick()
        advance(400)
        compose
            .onNodeWithTag(
                ListTags.row(
                    h.ui.rows
                        .first()
                        .id,
                ),
            ).performClick()
        advance(300)
        back()
        assertEquals("the page asked to leave select mode", ContactsListAction.ExitSelect, h.actions.last())
        assertNull(h.ui.selectMode)
        // The tab stayed where it was: the list is still there, now without the toolbar.
        compose.onNodeWithTag("contacts-list-page").assertIsDisplayed()
        compose.onNodeWithTag("large-title").assertTextEquals("Contacts")
        assertTrue(compose.onAllNodesWithTag(SelectTags.Toolbar).fetchSemanticsNodes().isEmpty())
    }

    @Test fun backDoesNotTouchSelectModeWhenNotSelecting() {
        val h = show()
        back()
        assertFalse(ContactsListAction.ExitSelect in h.actions)
        assertNotNull(h.ui)
    }

    @Test fun theLastRowEndsAboveTheSelectToolbar() {
        val h = show()
        compose.onNodeWithTag("toolbar-select").performClick()
        advance(400)
        compose.runOnUiThread {
            kotlinx.coroutines.runBlocking { h.listState.scrollToItem(h.listState.layoutInfo.totalItemsCount - 1) }
        }
        advance(1500)
        val last =
            h.ui.rows
                .last()
                .id
        val row = compose.onNodeWithTag(ListTags.row(last)).getBoundsInRoot()
        val bar = compose.onNodeWithTag(SelectTags.Toolbar).getBoundsInRoot()
        // The row box may reach 4 dp into the bar's own margin: its 8 dp of vertical padding keeps the drawn content clear.
        assertTrue("last row ${row.bottom} must end above the toolbar ${bar.top}", row.bottom <= bar.top + 4.dp)
    }
}
