package dev.rosty.stops.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.Slot
import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.SampleStops
import dev.rosty.stops.pick.PickTags
import dev.rosty.stops.saved.SavedTags
import dev.rosty.stops.saved.settleRust
import dev.rosty.stops.trip.TripFixtures
import dev.rosty.stops.trip.TripTags
import dev.rosty.testing.ShotTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.annotation.Config

/** The menu of a pin in the real app root, on the real core: every row that leaves the menu does its work. */
@Config(sdk = [35])
class PinMenuAppTest : ShotTest() {
    /** A and B on the map; the saved list holds only "School", so this pair is not a stop yet. */
    private fun start(
        end: Destination = TripFixtures.b,
        feed: Boolean = false,
    ): AppFixture {
        val saved = SampleStops.saved.copy(stops = SampleStops.saved.stops.filter { it.name == "School" })
        val fixture =
            AppFixture(FakeStopsCore(saved = saved, draft = Ends(TripFixtures.a, end), real = HostCore.real()))
        if (feed) TripFixtures.writeFeed()
        composeFrame { fixture.Content() }
        settleRust()
        return fixture
    }

    /** The pins are drawn on the map: their menus open through the map's accessibility actions. */
    private fun openPinMenu(slot: Slot): String {
        val actions =
            compose
                .onNodeWithTag(HomeMapTag)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.CustomActions)
                .orEmpty()
        val action = actions[if (slot == Slot.A) 0 else 1]
        compose.runOnUiThread { action.action() }
        settleRust(800)
        return action.label
    }

    private fun menu(text: String) {
        compose.onNode(hasText(text) and hasClickAction() and !hasAnyAncestor(hasTestTag(TripTags.Save))).performClick()
        settleRust(800)
    }

    private fun lastDraft(fixture: AppFixture): Ends? =
        fixture.container.fake.writes
            .lastOrNull { it.file == "draft" }
            ?.let { Json.decodeFromJsonElement(Ends.serializer(), it.value) }

    @Test fun linesOnATransitStopPinOpensItsStopSheetAndALinePutsItsPatternOnB() {
        val fixture = start(TripFixtures.bStop, feed = true)
        assertEquals("Options for Pier East", openPinMenu(Slot.B))
        menu("Lines")
        compose.onNodeWithTag(PickTags.StopSheet, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(PickTags.line(1), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settleRust(2000)
        compose.onNodeWithTag(PickTags.StopSheet, useUnmergedTree = true).assertDoesNotExist()
        val b = lastDraft(fixture)?.b
        assertEquals("PE", b?.gtfsStop)
        assertNotNull(b?.pattern)
    }

    @Test fun aPinThatIsNoTransitStopHasNoLinesRow() {
        start()
        openPinMenu(Slot.B)
        compose.onNode(hasText("Lines") and hasClickAction()).assertDoesNotExist()
    }

    @Test fun saveAsStopSavesTheTwoEndsAndOffersUndo() {
        val fixture = start()
        openPinMenu(Slot.A)
        menu("Save as stop")
        settleRust()
        // The name sheet: the core's suggestion in the field, replaced by the user's own name.
        compose.onNodeWithTag(SavedTags.RenameField, useUnmergedTree = true).assertTextContains("Pier West")
        compose.onNodeWithTag(SavedTags.RenameField, useUnmergedTree = true).performTextReplacement("Ferry to work")
        compose.onNodeWithTag(SavedTags.RenameSave, useUnmergedTree = true).performClick()
        settleRust()
        val stops =
            fixture.container.fake.saved!!
                .stops
        assertEquals(2, stops.size)
        assertEquals("Ferry to work", stops.last().name)
        assertEquals(SampleStops.pierW.coord, stops.last().a.coord)
        assertEquals(SampleStops.pierE.coord, stops.last().b.coord)
        compose.onNode(hasTestTag("toast-text") and hasText("Saved as Ferry to work")).assertExists()
        compose.onNodeWithText("Undo").performClick()
        settleRust()
        assertEquals(
            1,
            fixture.container.fake.saved!!
                .stops.size,
        )
    }

    /** Cancel on the name sheet saves nothing. */
    @Test fun cancelOnTheNameSheetSavesNothing() {
        val fixture = start()
        openPinMenu(Slot.A)
        menu("Save as stop")
        settleRust()
        compose.onNodeWithTag(SavedTags.RenameCancel, useUnmergedTree = true).performClick()
        settleRust()
        compose.onNodeWithTag(SavedTags.Rename, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(
            1,
            fixture.container.fake.saved!!
                .stops.size,
        )
    }

    /** A first run has no saved file at all: the pin menu still offers "Save as stop". */
    @Test fun aFirstRunWithNoSavedFileCanSave() {
        val fixture =
            AppFixture(
                FakeStopsCore(saved = null, draft = Ends(TripFixtures.a, TripFixtures.b), real = HostCore.real()),
            )
        composeFrame { fixture.Content() }
        settleRust()
        openPinMenu(Slot.B)
        menu("Save as stop")
        settleRust()
        compose.onNodeWithTag(SavedTags.RenameSave, useUnmergedTree = true).performClick()
        settleRust()
        assertEquals(
            1,
            fixture.container.fake.saved!!
                .stops.size,
        )
    }

    /** A pair that is a stop already does not offer to save it again. */
    @Test fun aSavedPairHasNoSaveRow() {
        val fixture = AppFixture(FakeStopsCore(draft = Ends(TripFixtures.a, TripFixtures.b), real = HostCore.real()))
        composeFrame { fixture.Content() }
        settleRust()
        openPinMenu(Slot.A)
        compose.onNode(hasText("Save as stop") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("Saved as Pier", useUnmergedTree = true).assertExists()
        assertEquals(
            2,
            fixture.container.fake.saved!!
                .stops.size,
        )
    }

    @Test fun swapPutsBOnA() {
        val fixture = start()
        openPinMenu(Slot.A)
        menu("Swap A and B")
        settleRust()
        assertEquals(SampleStops.pierE.coord, lastDraft(fixture)?.a?.coord)
        assertEquals(SampleStops.pierW.coord, lastDraft(fixture)?.b?.coord)
    }

    @Test fun removeClearsThatEnd() {
        val fixture = start()
        openPinMenu(Slot.B)
        menu("Remove")
        settleRust()
        assertNull(lastDraft(fixture)?.b)
        assertEquals(SampleStops.pierW.coord, lastDraft(fixture)?.a?.coord)
    }

    /** A change the core refuses leaves the pick alone and says so. */
    @Test fun aRefusedChangeSaysSo() {
        val fixture = start()
        fixture.container.fake.core
            .on("stops.pick_replay") { Outcome.Err(RustError.Invalid("refused")) }
        openPinMenu(Slot.A)
        menu("Swap A and B")
        settleRust()
        compose.onNodeWithText("Could not change the points. Try again.").assertExists()
    }
}
