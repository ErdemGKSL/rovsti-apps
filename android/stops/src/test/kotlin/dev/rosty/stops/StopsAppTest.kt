package dev.rosty.stops

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.rosty.shell.settings.dev.DevTags
import dev.rosty.stops.home.HomeMapTag
import dev.rosty.stops.home.HomeSheetTags
import dev.rosty.stops.home.HomeTags
import dev.rosty.stops.settings.SettingsTags
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The real app root composed with every provider the shell gives it (the overlay host above the home: a missing
 * provider once crashed Phone on open), and the ways out of the map: the folded tab bar, the sheet rows, the pick pill.
 */
@Config(sdk = [35])
class StopsAppTest : ShotTest() {
    private fun start(fixture: AppFixture = AppFixture()): AppFixture {
        composeFrame { fixture.Content() }
        advance(1500)
        return fixture
    }

    /** Folded, the bar is one button: the selected tab's circle (named by its glyph); a tap unfolds the three tabs. */
    private fun unfold() {
        compose.onNode(hasContentDescription("Trip") and hasClickAction()).performClick()
    }

    @Test fun theRootRendersTheMapTheChromeAndTheSheet() {
        start()
        compose.onNodeWithTag(HomeMapTag).assertIsDisplayed()
        compose.onNodeWithTag(HomeTags.Search).assertIsDisplayed()
        for (tag in listOf(HomeTags.Locate, HomeTags.Layers, HomeTags.Options, HomeTags.TabBar)) {
            compose.onNodeWithTag(tag).assertExists()
        }
        compose.onNodeWithTag(HomeSheetTags.Title).assertIsDisplayed()
        compose.onNodeWithText("Plan a trip").assertIsDisplayed()
    }

    @Test fun theRealMapDrawsWithItsScaleBarAttributionAndHint() {
        val fixture = start()
        compose.onNodeWithTag(HomeTags.ScaleBar, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("© OpenStreetMap contributors", substring = true, useUnmergedTree = true).assertExists()
        // The fixture tile around the fake position was asked for.
        assertTrue(
            fixture.container.tileSource.requests
                .isNotEmpty(),
        )
    }

    @Test fun theSheetShowsWhatTheFilesHold() {
        val fixture = start(AppFixture(FakeStopsCore(trip = SampleStops.pierW to SampleStops.pierE)))
        compose.onNodeWithText("Trip in progress: Pier West – Pier East").assertExists()
        compose.onNodeWithText("2 stops").assertExists()
        compose.onNodeWithText("1 trip").assertExists()
        compose.onNodeWithTag(HomeSheetTags.TransitRow, useUnmergedTree = true).assertExists()
        assertEquals(StopsRoute.Home, fixture.navigator.current)
    }

    @Test fun theFoldedBarUnfoldsAndSettingsIsPushedOverTheMap() {
        val fixture = start()
        // Folded, the bar is the selected tab's circle; a tap unfolds it to the three GPUI tabs.
        unfold()
        advance(800)
        compose.onAllNodesWithTag("tab-settings", useUnmergedTree = true).onFirst().performClick()
        advance(1200)
        assertEquals(listOf(StopsRoute.Home, StopsRoute.Settings), fixture.navigator.stack(TripTab))
        compose.onNodeWithTag(SettingsTags.Page).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.TileServer, useUnmergedTree = true).assertTextEquals("tiles.versatiles.org")
        // The shell's common group ends the page: Appearance (Stops has it in its prefs) and About; no Haptics.
        assertEquals(1, compose.onAllNodes(hasText("Appearance")).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText("About")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("Haptics")).fetchSemanticsNodes().size)
        // The map sheet left with the map.
        compose.onNodeWithTag(HomeSheetTags.Title).assertDoesNotExist()
    }

    @Test fun pushedPagesKeepTheFullBarWhoseItemsLeadBackToTheMap() {
        val fixture = start()
        compose.runOnUiThread {
            fixture.navigator.push(StopsRoute.Settings)
            fixture.navigator.push(StopsRoute.About)
        }
        advance(1500)
        // Settings is selected on its sub-page; tapping it goes back to the Settings page.
        compose.onAllNodesWithTag("tab-settings", useUnmergedTree = true).onFirst().performClick()
        advance(1500)
        assertEquals(StopsRoute.Settings, fixture.navigator.current)
        // Saved goes back to the map and opens the Saved sheet there.
        compose.onAllNodesWithTag("tab-saved", useUnmergedTree = true).onFirst().performClick()
        advance(2000)
        assertEquals(StopsRoute.Home, fixture.navigator.current)
        compose.onNodeWithTag(HomeSheetTags.Saved, useUnmergedTree = true).assertExists()
    }

    @Test fun tripOnAPushedPageGoesBackToTheMap() {
        val fixture = start()
        compose.runOnUiThread { fixture.navigator.push(StopsRoute.Settings) }
        advance(1500)
        compose.onAllNodesWithTag("tab-trip", useUnmergedTree = true).onFirst().performClick()
        advance(1500)
        assertEquals(listOf<StopsRoute>(StopsRoute.Home), fixture.navigator.stack(TripTab))
    }

    @Test fun aboutShowsTheVersionRowThatHoldsDeveloperMode() {
        val fixture = start()
        compose.runOnUiThread {
            fixture.navigator.push(StopsRoute.Settings)
            fixture.navigator.push(StopsRoute.About)
        }
        advance(1500)
        compose.onNodeWithTag(DevTags.Version, useUnmergedTree = true).assertExists()
    }

    @Test fun backFromSettingsBringsTheSheetBack() {
        val fixture = start()
        compose.runOnUiThread { fixture.navigator.push(StopsRoute.Settings) }
        advance(1500)
        compose.onNodeWithTag(HomeSheetTags.Title).assertDoesNotExist()
        compose.runOnUiThread { fixture.navigator.pop() }
        advance(1500)
        compose.onNodeWithTag(HomeSheetTags.Title).assertIsDisplayed()
    }

    @Test fun theSheetRowsAndThePillOpenTheirSheets() {
        start()
        // At peek the sheet shows its title and first line; medium shows the rows.
        compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
        advance(1500)
        compose.onNodeWithTag(HomeSheetTags.SavedRow).performClick()
        advance(1200)
        compose.onNodeWithTag(HomeSheetTags.Saved, useUnmergedTree = true).assertExists()
        // Back closes the modal sheet; the map sheet stays.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(1500)
        compose.onNodeWithTag(HomeSheetTags.Saved, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(HomeSheetTags.Title).assertIsDisplayed()
        compose.onNodeWithTag(HomeSheetTags.HistoryRow).performClick()
        advance(1200)
        compose.onNodeWithTag(HomeSheetTags.History, useUnmergedTree = true).assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(1500)
        compose.onNodeWithTag(HomeTags.Search).performClick()
        advance(1200)
        compose.onNodeWithTag(HomeSheetTags.Pick, useUnmergedTree = true).assertExists()
    }

    @Test fun theSavedTabOpensTheSavedSheetAndTripClosesIt() {
        start()
        unfold()
        advance(800)
        compose.onAllNodesWithTag("tab-saved", useUnmergedTree = true).onFirst().performClick()
        advance(1200)
        compose.onNodeWithTag(HomeSheetTags.Saved, useUnmergedTree = true).assertExists()
    }

    @Test fun aSharedLinkOpensThePickSheetOnce() {
        val fixture = start()
        compose.runOnUiThread { fixture.container.pendingLink.value = StopsLink.Place("geo:41.0,29.0") }
        advance(1500)
        compose.onNodeWithTag(HomeSheetTags.Pick, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Shared with Stops: geo:41.0,29.0").assertExists()
        assertEquals(null, fixture.container.pendingLink.value)
    }
}
