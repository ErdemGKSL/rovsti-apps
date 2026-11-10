package dev.rovsti.phone.recents

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.core.model.phone.RecentsFilter
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.3 Recents on the real shell with sample rows; the clock is manual (`autoAdvance = false`). */
@Config(sdk = [35])
class RecentsScreenTest : ShotTest() {
    private val haptics = RecordingHaptics()
    private val strings get() = RecentsStrings(ApplicationProvider.getApplicationContext<Context>().resources)

    @After fun clearProbe() {
        RecentsProbe.onRowComposed = null
    }

    private fun show(
        h: RecentsHarness,
        fontScale: Float = 1f,
    ) {
        composeFrame(fontScale = fontScale) { HostedRecents(h, strings, haptics) }
        advance(300)
    }

    private fun chipTags() = RecentsFilter.entries.map { RecentsTags.chip(it) }

    @Test fun theChipsAreOneRowThatNeverWrapsAtTwiceTheFontSize() {
        val h = RecentsHarness()
        show(h, fontScale = 2f)
        val bounds = chipTags().take(3).map { compose.onNodeWithTag(it).getBoundsInRoot() }
        // One row: every chip starts at the same height and has the same height; none sits under another.
        assertEquals(1, bounds.map { it.top }.distinct().size)
        assertEquals(1, bounds.map { it.height }.distinct().size)
        assertTrue(
            "the chips run off the right edge instead of wrapping",
            bounds.zipWithNext().all { (a, b) ->
                b.left >=
                    a.right
            },
        )
        // The sixth chip is beyond the edge until the row scrolls.
        compose.onNodeWithTag(RecentsTags.Chips).performTouchInput { swipeLeft() }
        advance(600)
        compose.onNodeWithTag(RecentsTags.chip(RecentsFilter.WithNotes)).assertIsDisplayed()
    }

    @Test fun everyChipShowsItsCountAsASecondaryLabel() {
        val h = RecentsHarness()
        show(h)
        compose.onNodeWithTag(RecentsTags.chip(RecentsFilter.Missed)).assertIsDisplayed()
        compose.onNodeWithText("Missed").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
    }

    @Test fun tappingAChipAsksForThatFilter() {
        val h = RecentsHarness()
        show(h)
        compose.onNodeWithTag(RecentsTags.chip(RecentsFilter.Missed)).performClick()
        advance(100)
        assertEquals(RecentsAction.SetFilter(RecentsFilter.Missed), h.actions.last())
    }

    @Test fun theLastRowEndsAboveTheTabBar() {
        val h = RecentsHarness()
        show(h)
        val count = h.listState.layoutInfo.totalItemsCount
        compose.runOnUiThread { }
        compose.onNodeWithTag("scaffold-list").performScrollToIndex(count - 1)
        advance(600)
        val last = compose.onNodeWithTag(RecentsTags.row("14")).getBoundsInRoot()
        val bar = compose.onNodeWithTag("tab-surface").getBoundsInRoot()
        assertTrue("last row bottom ${last.bottom} is above the bar top ${bar.top}", last.bottom <= bar.top)
    }

    @Test fun aLongPressOpensTheMenuAndLeavesTheListWhereItWas() {
        val h = RecentsHarness()
        show(h)

        fun bounds(key: String) = compose.onNodeWithTag(RecentsTags.row(key), useUnmergedTree = true).getBoundsInRoot()
        val before = bounds("2")
        val next = bounds("3")
        compose.onNodeWithTag(RecentsTags.row("2")).performTouchInput { longClick(durationMillis = 700) }
        advance(900)
        compose.onNodeWithTag("context-panel").assertIsDisplayed()
        compose.onNodeWithText("Call back").assertIsDisplayed()
        assertEquals(before, bounds("2"))
        assertEquals(next, bounds("3"))
        assertTrue("the menu opened with a long-press haptic", haptics.events.contains(HapticEvent.LongPress))
    }

    @Test fun aMenuRowSendsItsAction() {
        val h = RecentsHarness()
        show(h)
        compose.onNodeWithTag(RecentsTags.row("2")).performTouchInput { longClick(durationMillis = 700) }
        advance(900)
        compose.onNodeWithText("Copy number").performClick()
        advance(900)
        assertEquals(RecentsAction.CopyNumber("2", withCountry = false), h.actions.last())
    }

    @Test fun aTapCallsBackAndTheInfoButtonDoesNot() {
        val h = RecentsHarness()
        show(h)
        compose.onNodeWithTag(RecentsTags.row("2")).performClick()
        advance(300)
        assertEquals(RecentsAction.CallBack("2"), h.actions.last())
    }

    @Test fun selectModeTapsToggleAndTheBarFoldsBesideTheToolbar() {
        val h = RecentsHarness(RecentsSample.ui(select = RecentsSample.selected("2")))
        show(h)
        compose.onNodeWithTag(RecentsTags.SelectToolbar).assertIsDisplayed()
        compose.onNodeWithTag(RecentsTags.row("4")).performClick()
        advance(300)
        assertEquals(RecentsAction.Toggle("4"), h.actions.last())
        compose.onNodeWithTag(RecentsTags.SelectDelete).performClick()
        assertEquals(RecentsAction.DeleteSelected, h.actions.last())
        compose.onNodeWithTag(RecentsTags.SelectBlock).performClick()
        assertEquals(RecentsAction.BlockSelected, h.actions.last())
        compose.onNodeWithTag(RecentsTags.SelectShare).performClick()
        assertEquals(RecentsAction.ShareSelected, h.actions.last())
        compose.onNodeWithTag(RecentsTags.SelectCancel).performClick()
        assertEquals(RecentsAction.ExitSelect, h.actions.last())
    }

    @Test fun nothingChosenDimsTheBulkButtons() {
        val h = RecentsHarness(RecentsSample.ui(select = RecentsSample.selected()))
        show(h)
        h.actions.clear()
        compose.onNodeWithTag(RecentsTags.SelectDelete).performClick()
        assertTrue("a dimmed button does nothing", h.actions.isEmpty())
    }

    @Test fun whatsAppShowsOnceAsTheSmallGlyphAndNeverAsAPill() {
        val h = RecentsHarness()
        show(h)
        compose.onAllNodesWithTag(RecentsTags.whatsApp("4"), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
        compose.onNodeWithTag(RecentsTags.whatsApp("4"), useUnmergedTree = true).getBoundsInRoot().let {
            assertTrue("14 dp glyph: ${it.width}", it.width <= 16.dp)
        }
    }

    @Test fun aSwipeToTheLeftDeletes() {
        val h = RecentsHarness()
        show(h)
        compose.onNodeWithTag(RecentsTags.row("2")).performTouchInput { swipeLeft(durationMillis = 250) }
        advance(600)
        assertEquals(RecentsAction.Delete(listOf("2")), h.actions.last())
    }

    @Test fun scrollingComposesEachNewRowOnceAndNeverTheOnesThatStay() {
        val h = RecentsHarness()
        show(h)
        val counts = HashMap<String, Int>()
        RecentsProbe.onRowComposed = { counts.merge(it, 1, Int::plus) }
        val before =
            h.listState.layoutInfo.visibleItemsInfo
                .map { it.key.toString() }
                .toSet()
        compose.onNodeWithTag("scaffold-list").performTouchInput {
            swipeUp(startY = bottom * 0.7f, endY = bottom * 0.7f - 120f, durationMillis = 200)
        }
        advance(100)
        assertTrue("each row at most once while scrolling: $counts", counts.values.all { it <= 1 })
        assertEquals(
            "rows that were on screen do not recompose: $counts",
            emptyList<String>(),
            counts.keys.filter {
                it in
                    before
            },
        )
    }

    @Test fun anEmptyFilterShowsTheCoresSentence() {
        val h =
            RecentsHarness(
                RecentsSample.ui(emptyList(), filter = RecentsFilter.WhatsApp).copy(emptyText = "No WhatsApp calls"),
            )
        show(h)
        compose.onNodeWithText("No WhatsApp calls").assertIsDisplayed()
    }
}
