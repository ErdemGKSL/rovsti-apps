package dev.rovsti.phone.favorites

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.phone.PhoneTags
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.5 Favorites on the real shell; the Compose clock is manual (`autoAdvance = false`). */
@Config(sdk = [35])
class FavoritesScreenTest : ShotTest() {
    private val haptics = RecordingHaptics()

    private fun show(h: FavHarness) {
        composeFrame { HostedFavorites(h, haptics) }
        advance(300)
    }

    private fun handle(id: Long) = compose.onNodeWithTag(FavTags.handle(id), useUnmergedTree = true)

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .getUnclippedBoundsInRoot()
            .top.value

    private fun orderOnScreen(ids: List<Long>) = ids.sortedBy { top(FavTags.row(it)) }

    @Test fun dragRowOneBelowRowThreeChangesTheOrderOnceAndTicks() {
        val h = FavHarness(FavUi.state(editing = true))
        show(h)
        val ids = FavUi.favorites.map { it.contactId!! }
        val pitch =
            compose
                .onNodeWithTag(FavTags.row(3))
                .fetchSemanticsNode()
                .boundsInRoot.top -
                compose
                    .onNodeWithTag(FavTags.row(1))
                    .fetchSemanticsNode()
                    .boundsInRoot.top
        handle(1).performTouchInput { down(center) }
        advance(100)
        val step = pitch * 2.4f / 15f
        repeat(15) {
            handle(1).performTouchInput { moveBy(Offset(0f, step)) }
            advance(120)
        }
        advance(300)
        handle(1).performTouchInput { up() }
        repeat(6) { advance(300) }

        assertEquals(
            "one drop, however many rows it passed: ${h.actions} haptics=${haptics.events}",
            listOf(FavoritesAction.Move(0, 2)),
            h.actions.filterIsInstance<FavoritesAction.Move>(),
        )
        assertEquals(listOf(3L, 5L, 1L, 11L, 22L), orderOnScreen(ids))
        assertEquals("the lift ticked once", 1, haptics.events.count { it == HapticEvent.Tick })
        assertEquals("a selection tick for each row passed", 2, haptics.events.count { it == HapticEvent.Selection })
    }

    @Test fun handlesShowInEditModeOnlyAndTheMenuCarriesEditAndAdd() {
        val h = FavHarness(FavUi.state())
        show(h)
        assertEquals(0, compose.onAllNodesWithTag(FavTags.handle(1), useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag(FavTags.info(1), useUnmergedTree = true).assertIsDisplayed()
        // The "+" button opens a menu: Add Favorite and Edit.
        compose.onNodeWithTag("toolbar-add").performClick()
        advance(700)
        compose.onNodeWithText("Add Favorite").assertIsDisplayed()
        compose.onNodeWithText("Edit favorites").performClick()
        advance(900)
        assertEquals(listOf(FavoritesAction.ToggleEdit), h.actions)
        for (row in FavUi.favorites) handle(row.contactId!!).assertIsDisplayed()
        assertEquals(
            "the info buttons give way to handles",
            0,
            compose.onAllNodesWithTag(FavTags.info(1), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("toolbar-done").assertIsDisplayed()
    }

    @Test fun aTapCallsAndInfoOpensThePersonButNotWhileEditing() {
        val h = FavHarness(FavUi.state())
        show(h)
        compose.onNodeWithTag(FavTags.row(3)).performClick()
        assertEquals(FavoritesAction.Call(FavUi.favorites[1].number!!), h.actions.last())
        compose.onNodeWithTag(FavTags.info(3), useUnmergedTree = true).performClick()
        assertEquals(listOf(3L), h.infos.map { it.contactId })
        h.on(FavoritesAction.ToggleEdit)
        advance(400)
        h.actions.clear()
        compose.onNodeWithTag(FavTags.row(3)).performClick()
        assertTrue("a tap in edit mode does nothing: ${h.actions}", h.actions.isEmpty())
    }

    @Test fun theRowMenuOffersMoveEarlierLaterAndRemove() {
        val h = FavHarness(FavUi.state())
        show(h)
        compose.onNodeWithTag(FavTags.row(5)).performTouchInput { longClick(durationMillis = 700) }
        advance(900)
        compose.onNodeWithText("Move Earlier").assertIsDisplayed()
        compose.onNodeWithText("Move Later").assertIsDisplayed()
        compose.onNodeWithText("Remove from Favorites").assertIsDisplayed()
        compose.onNodeWithText("Move Earlier").performClick()
        advance(900)
        assertEquals(FavoritesAction.MoveStep(index = 2, up = true), h.actions.last())
    }

    @Test fun theFirstRowCannotMoveEarlier() {
        val h = FavHarness(FavUi.state())
        show(h)
        compose.onNodeWithTag(FavTags.row(1)).performTouchInput { longClick(durationMillis = 700) }
        advance(900)
        assertEquals(0, compose.onAllNodesWithText("Move Earlier").fetchSemanticsNodes().size)
        compose.onNodeWithText("Move Later").assertIsDisplayed()
    }

    @Test fun frequentRowsCallAndCanBeAdded() {
        val h = FavHarness(FavUi.state())
        show(h)
        compose.onNodeWithTag(FavTags.Frequent).assertIsDisplayed()
        compose.onNodeWithTag(FavTags.frequentRow("freq-2")).performClick()
        assertEquals(FavoritesAction.Call(FavUi.frequent[0].number!!), h.actions.last())
        compose.onNodeWithTag(FavTags.frequentRow("freq-7")).performTouchInput { longClick(durationMillis = 700) }
        advance(900)
        compose.onNodeWithText("Add Favorite").performClick()
        advance(900)
        assertEquals(FavoritesAction.Add(7), h.actions.last())
    }

    @Test fun nothingSitsUnderTheTabBar() {
        val h = FavHarness(FavUi.state())
        show(h)
        compose.runOnUiThread {
            kotlinx.coroutines.runBlocking { h.listState.scrollToItem(h.listState.layoutInfo.totalItemsCount - 1) }
        }
        advance(600)
        val last = compose.onNodeWithTag(FavTags.frequentRow("freq-7")).getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag(PhoneTags.root("favorites")).getUnclippedBoundsInRoot()
        assertTrue("the last row ends above the bar: $last in $root", last.bottom.value < root.bottom.value - 40f)
    }

    @Test fun emptyShowsTheSentenceAndAddButton() {
        val h = FavHarness(FavUi.state(favorites = emptyList(), frequent = emptyList()))
        show(h)
        compose.onNodeWithText("No favorites yet").assertIsDisplayed()
        compose.onNodeWithText("Add Favorite").performClick()
        assertEquals(1, h.added)
    }
}
