package dev.rovsti.contacts.favorites

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.core.model.contacts.EventKind
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.6 on the real shell; the clock is manual (`autoAdvance = false`). */
@Config(sdk = [35])
class FavoritesScreenTest : ShotTest() {
    private val haptics = RecordingHaptics()

    private fun show(h: FavHarness) {
        composeFrame { HostedFavorites(h, haptics) }
        advance(300)
    }

    private fun handle(id: Long) = compose.onNodeWithTag(FavTags.handle(id), useUnmergedTree = true)

    private fun scrollToEnd(h: FavHarness) {
        compose.runOnUiThread {
            kotlinx.coroutines.runBlocking {
                h.listState.scrollToItem(h.listState.layoutInfo.totalItemsCount - 1)
            }
        }
        advance(600)
    }

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .getUnclippedBoundsInRoot()
            .top.value

    private fun orderOnScreen(ids: List<Long>) = ids.sortedBy { top(FavTags.row(it)) }

    @Test fun dragRowOneBelowRowThreeChangesTheOrderOnceAndTicks() {
        val h = FavHarness(FavUi.state(editing = true))
        show(h)
        val ids = FavUi.favorites.map { it.id }
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

        val moves = h.actions.filterIsInstance<FavoritesAction.Move>()
        assertEquals(
            "one drop, however many rows it passed: ${h.actions} haptics=${haptics.events} order=${orderOnScreen(ids)}",
            listOf(FavoritesAction.Move(0, 2)),
            moves,
        )
        assertEquals(listOf(3L, 5L, 1L, 11L, 22L), orderOnScreen(ids))
        assertEquals("the lift ticked once", 1, haptics.events.count { it == HapticEvent.Tick })
        assertEquals("a selection tick for each row passed", 2, haptics.events.count { it == HapticEvent.Selection })
    }

    @Test fun handlesShowInEditModeOnly() {
        val view = FavHarness(FavUi.state())
        show(view)
        assertEquals(0, compose.onAllNodesWithTag(FavTags.handle(1), useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("toolbar-edit").performClick()
        advance(400)
        assertEquals(listOf(FavoritesAction.ToggleEdit), view.actions)
        for (id in FavUi.favorites.map { it.id }) handle(id).assertIsDisplayed()
        compose.onNodeWithTag("toolbar-done").assertIsDisplayed()
        assertEquals(
            "Add and Edit are gone while editing",
            0,
            compose.onAllNodesWithTag("toolbar-add").fetchSemanticsNodes().size,
        )
    }

    @Test fun aTapOpensTheCardButNotWhileEditing() {
        val h = FavHarness(FavUi.state())
        show(h)
        compose.onNodeWithTag(FavTags.row(3)).performClick()
        assertEquals(FavoritesAction.Open(3), h.actions.last())
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

    @Test fun aStarOnAFrequentRowAddsIt() {
        val h = FavHarness(FavUi.state())
        show(h)
        compose.onNodeWithTag(FavTags.star(2)).performClick()
        assertEquals(FavoritesAction.Star(2), h.actions.last())
    }

    @Test fun upcomingShowsWishOnlyWhereThereIsANumberAndSeeAllOpensThePage() {
        val h = FavHarness(FavUi.state())
        show(h)
        val keys = FavUi.upcoming.map { it.key }
        compose.onNodeWithTag(FavTags.wish(keys[0]), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithTag(FavTags.wish(keys[2]), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag(FavTags.wish(keys[0]), useUnmergedTree = true).performClick()
        assertEquals(FavoritesAction.Wish(2, EventKind.Birthday, "Burak Demir"), h.actions.last())
        compose.onNodeWithTag(FavTags.SeeAll).performClick()
        assertEquals(1, h.upcomingOpened)
    }

    @Test fun nothingSitsUnderTheTabBar() {
        val h = FavHarness(FavUi.state())
        show(h)
        compose.runOnUiThread {
            kotlinx.coroutines.runBlocking {
                h.listState.scrollToItem(h.listState.layoutInfo.totalItemsCount - 1)
            }
        }
        advance(600)
        val seeAll = compose.onNodeWithTag(FavTags.SeeAll).getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag(FavTags.Screen).getUnclippedBoundsInRoot()
        assertTrue("See All ends above the bar: $seeAll in $root", seeAll.bottom.value < root.bottom.value - 40f)
    }

    @Test fun emptyShowsTheSentenceAndAddButton() {
        val h =
            FavHarness(
                FavUi.state(favorites = emptyList(), frequent = emptyList(), upcoming = FavUi.upcoming).copy(
                    upcoming = kotlinx.collections.immutable.persistentListOf(),
                ),
            )
        show(h)
        compose.onNodeWithText("No Favorites").assertIsDisplayed()
        compose.onNodeWithText("Add Favorites").assertIsDisplayed()
    }
}
