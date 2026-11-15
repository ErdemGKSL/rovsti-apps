package dev.rosty.stops.history

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.design.time.RostyClock
import dev.rosty.design.time.SystemRostyClock
import dev.rosty.platform.location.TripClock
import dev.rosty.stops.AppFixture
import dev.rosty.stops.SampleStops
import dev.rosty.stops.home.HomeSheetTags
import dev.rosty.stops.saved.SavedTags
import dev.rosty.stops.saved.settleRust
import dev.rosty.stops.trip.AlarmTags
import dev.rosty.testing.ShotTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config

/** The History sheet in the real app root, on the real core. */
@Config(sdk = [35])
class HistoryAppTest : ShotTest() {
    @Before fun fixClock() {
        TripClock.clock = RostyClock { HistoryFixtures.NOW }
    }

    @After fun resetClock() {
        TripClock.clock = SystemRostyClock
    }

    private fun start(saved: SavedStops = SampleStops.saved): AppFixture {
        val fixture = HistoryFixtures.app()
        fixture.container.fake.saved = saved
        composeFrame { fixture.Content() }
        settleRust()
        compose
            .onNodeWithTag(HomeSheetTags.HistoryRow, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        settleRust()
        // The statistics come first: the trips are below them.
        compose
            .onNodeWithTag(
                HistoryTags.List,
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.ScrollBy) {
                it(0f, SCROLL)
            }
        settleRust(800)
        return fixture
    }

    /** "Save as stop" of the open trip menu (the last trip card has a button with the same words). */
    private fun menuSave() {
        compose
            .onNode(
                hasText("Save as stop") and hasClickAction() and !hasAnyAncestor(hasTestTag(AlarmTags.LastTrip)),
            ).performClick()
        settleRust()
    }

    private fun menu(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performClick()
        settleRust()
    }

    @Test fun theSheetShowsStatisticsAndTheTrips() {
        start()
        compose.onNodeWithTag(HistoryTags.Stats, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(HistoryTags.row(0), useUnmergedTree = true).assertExists()
    }

    @Test fun aTripsMenuDeletesItWithUndo() {
        val fixture = start()
        compose
            .onNodeWithTag(
                HistoryTags.row(0),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.OnClick)
        settleRust(800)
        menu("Delete")
        assertEquals(
            5,
            fixture.container.fake.history!!
                .records.size,
        )
        compose.onNodeWithText("Undo").performClick()
        settleRust()
        assertEquals(
            6,
            fixture.container.fake.history!!
                .records.size,
        )
    }

    @Test fun showOnMapPutsTheTripsEndsOnTheMap() {
        val fixture = start()
        compose
            .onNodeWithTag(
                HistoryTags.row(0),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.OnClick)
        settleRust(800)
        menu("Show on map")
        compose.onNodeWithTag(HistoryTags.Sheet, useUnmergedTree = true).assertDoesNotExist()
        val draft =
            fixture.container.fake.writes
                .last { it.file == "draft" }
                .let { Json.decodeFromJsonElement(Ends.serializer(), it.value) }
        assertEquals(SampleStops.pierE.coord, draft.b?.coord)
    }

    /** S15: "Save as stop" in a trip's menu saves its two ends as a stop, with Undo. */
    @Test fun saveAsStopInATripsMenuSavesItsEnds() {
        val fixture = start(SampleStops.saved.copy(stops = SampleStops.saved.stops.filter { it.name == "School" }))
        compose
            .onNodeWithTag(
                HistoryTags.row(0),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.OnClick)
        settleRust(800)
        menuSave()
        // The name sheet, prefilled; Save keeps the suggestion.
        compose.onNodeWithTag(SavedTags.RenameField, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SavedTags.RenameSave, useUnmergedTree = true).performClick()
        settleRust()
        val stops =
            fixture.container.fake.saved!!
                .stops
        assertEquals(2, stops.size)
        assertEquals(SampleStops.pierE.coord, stops.last().b.coord)
        assertEquals("Pier West", stops.last().name)
        compose.onNode(hasTestTag("toast-text") and hasText("Saved as Pier West")).assertExists()
    }

    /** A trip whose ends are a stop already says so instead of saving a twin. */
    @Test fun saveAsStopOfASavedTripSaysItIsSaved() {
        val fixture = start()
        compose
            .onNodeWithTag(
                HistoryTags.row(0),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.OnClick)
        settleRust(800)
        menuSave()
        compose.onNodeWithTag(SavedTags.Rename, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(
            2,
            fixture.container.fake.saved!!
                .stops.size,
        )
        compose.onNodeWithText("These points are already saved as Pier.").assertExists()
    }

    private companion object {
        const val SCROLL = 1800f
    }
}
