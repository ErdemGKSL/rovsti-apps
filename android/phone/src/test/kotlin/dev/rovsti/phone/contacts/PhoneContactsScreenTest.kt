package dev.rovsti.phone.contacts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rovsti.design.list.AlphabetRailTags
import dev.rovsti.phone.PhoneTags
import dev.rovsti.phone.favorites.HostedContacts
import dev.rovsti.phone.favorites.PeopleHarness
import dev.rovsti.phone.favorites.PeopleUiSample
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.5 Contacts tab on the real shell: sections, the rail, a tap, "+", search results and the empty states. */
@Config(sdk = [35])
class PhoneContactsScreenTest : ShotTest() {
    private fun show(h: PeopleHarness) {
        composeFrame { HostedContacts(h) }
        advance(400)
    }

    @Test fun aTapOpensThePersonAndPlusHandsOverToTheEditor() {
        val h = PeopleHarness(PeopleUiSample.ui())
        show(h)
        compose.onNodeWithTag(PeopleTags.row(3)).performClick()
        assertEquals(listOf(3L), h.opened)
        compose.onNodeWithTag("toolbar-add").performClick()
        assertEquals(1, h.created)
    }

    @Test fun sectionsHaveOneHeaderEachAndTheRailShowsForALongList() {
        val h = PeopleHarness(PeopleUiSample.ui())
        show(h)
        compose.onNodeWithTag(PeopleTags.header("A")).assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithTag(PeopleTags.header("A")).fetchSemanticsNodes().size)
        compose.onNodeWithTag(AlphabetRailTags.Rail, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun searchHasNoSectionsAndNoRail() {
        val rows = PeopleUiSample.rows(listOf("Ayse Yilmaz", "Kerem Aydin"), sections = false)
        val h = PeopleHarness(PeopleUiSample.ui(rows, query = "ay"))
        composeFrame { HostedContacts(h, initialQuery = "ay") }
        advance(400)
        assertEquals(0, compose.onAllNodesWithTag(PeopleTags.header("A")).fetchSemanticsNodes().size)
        assertEquals(
            0,
            compose.onAllNodesWithTag(AlphabetRailTags.Rail, useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag(PeopleTags.row(1)).assertIsDisplayed()
        assertTrue(
            "the field reports the text as a query",
            h.actions.any {
                it is PeopleAction.SetQuery &&
                    it.text == "ay"
            },
        )
    }

    @Test fun noMatchSaysSoWithTheQuery() {
        val h = PeopleHarness(PeopleUiSample.ui(emptyList(), query = "zzz", empty = PeopleEmpty.NoResults))
        composeFrame { HostedContacts(h, initialQuery = "zzz") }
        advance(400)
        compose.onNodeWithText("No contact matches \"zzz\"").assertIsDisplayed()
    }

    @Test fun nothingSitsUnderTheTabBar() {
        val h = PeopleHarness(PeopleUiSample.ui())
        show(h)
        compose.runOnUiThread {
            kotlinx.coroutines.runBlocking { h.listState.scrollToItem(h.listState.layoutInfo.totalItemsCount - 1) }
        }
        advance(600)
        val last = compose.onNodeWithTag(PeopleTags.row(25)).getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag(PhoneTags.root("contacts")).getUnclippedBoundsInRoot()
        assertTrue("the last row ends above the bar: $last in $root", last.bottom.value < root.bottom.value - 40f)
    }
}
