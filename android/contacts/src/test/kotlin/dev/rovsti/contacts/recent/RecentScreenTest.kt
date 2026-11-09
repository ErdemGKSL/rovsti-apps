package dev.rovsti.contacts.recent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.14 Recently Added / Edited on the real shell. */
@Config(sdk = [35])
class RecentScreenTest : ShotTest() {
    private fun show(h: RecentHarness) {
        composeFrame { HostedRecent(h) }
        advance(700)
    }

    private fun scrollTo(
        h: RecentHarness,
        index: Int,
    ) {
        compose.runOnUiThread { kotlinx.coroutines.runBlocking { h.listState.scrollToItem(index) } }
        advance(400)
    }

    @Test fun theAddedSegmentShowsItsRowsWithTheOpsWhenTexts() {
        val h = RecentHarness(RecentUi.state())
        show(h)
        compose.onNodeWithTag(RecentTags.Segments).assertIsDisplayed()
        compose.onNodeWithText("Selma Aydoğan").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithText("3 days ago").assertIsDisplayed()
        compose.onNodeWithText("Burak Demir").assertDoesNotExist()
    }

    @Test fun tappingEditedAsksForItAndSwapsTheRows() {
        val h = RecentHarness(RecentUi.state())
        show(h)
        compose.onNodeWithText("Edited").performClick()
        advance(700)
        assertEquals(listOf<RecentAction>(RecentAction.SelectTab("edited")), h.actions)
        compose.onNodeWithText("Burak Demir").assertIsDisplayed()
        compose.onNodeWithText("Selma Aydoğan").assertDoesNotExist()
    }

    @Test fun aRowOpensItsCard() {
        val h = RecentHarness(RecentUi.state())
        show(h)
        compose.onNodeWithTag(RecentTags.row(42)).performClick()
        assertTrue(h.actions.contains(RecentAction.Open(42)))
    }

    @Test fun anEmptySegmentShowsTheOpsEmptyText() {
        val h = RecentHarness(RecentUi.state(added = emptyList()))
        show(h)
        compose.onNodeWithTag(RecentTags.Empty).assertIsDisplayed()
        compose.onNodeWithText("Nothing New").assertIsDisplayed()
        compose.onNodeWithText("No contacts added in the last 30 days").assertIsDisplayed()
        compose.onNodeWithTag(RecentTags.Footer).assertDoesNotExist()
        compose.onNodeWithTag(RecentTags.Segments).assertIsDisplayed()
    }

    @Test fun eachSegmentKeepsItsOwnScrollPosition() {
        val h = RecentHarness(RecentUi.state(many = true))
        show(h)
        scrollTo(h, 12)
        val added = h.listState.firstVisibleItemIndex
        assertTrue("the added list scrolled: $added", added in 11..13)
        compose.onNodeWithText("Edited").performClick()
        advance(900)
        assertEquals("a segment that was never scrolled starts at the top", 0, h.listState.firstVisibleItemIndex)
        scrollTo(h, 5)
        val edited = h.listState.firstVisibleItemIndex
        assertTrue("the edited list scrolled: $edited", edited in 4..6)
        compose.onNodeWithText("Added").performClick()
        advance(900)
        assertEquals("back on added", added, h.listState.firstVisibleItemIndex)
        compose.onNodeWithText("Edited").performClick()
        advance(900)
        assertEquals("back on edited", edited, h.listState.firstVisibleItemIndex)
    }

    @Test fun theSegmentLabelsStayOnScreenAtDoubleFontSize() {
        val h = RecentHarness(RecentUi.state())
        composeFrame(fontScale = 2f) { HostedRecent(h) }
        advance(700)
        compose.onAllNodesWithText("Added")[0].assertIsDisplayed()
        compose.onNodeWithText("Edited").assertIsDisplayed()
    }
}
