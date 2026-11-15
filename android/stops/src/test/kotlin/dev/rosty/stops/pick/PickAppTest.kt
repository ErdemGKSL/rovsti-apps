package dev.rosty.stops.pick

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import dev.rosty.core.model.stops.Ends
import dev.rosty.stops.AppFixture
import dev.rosty.stops.SampleStops
import dev.rosty.stops.StopsLink
import dev.rosty.stops.home.HomeTags
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The pick sheets in the real app root, on the real core. */
@Config(sdk = [35])
class PickAppTest : ShotTest() {
    private fun start(fixture: AppFixture = PickFixtures.app()): AppFixture {
        composeFrame { fixture.Content() }
        settleRust()
        return fixture
    }

    private fun lastDraft(fixture: AppFixture): Ends? =
        fixture.container.fake.writes
            .lastOrNull { it.file == "draft" }
            ?.let { Json.decodeFromJsonElement(Ends.serializer(), it.value) }

    private fun openPick() {
        compose.onNodeWithTag(HomeTags.Search, useUnmergedTree = true).performClick()
        settleRust()
    }

    @Test fun theSearchPillOpensThePickSheetAndASavedStopFillsBothEnds() {
        val fixture = start()
        openPick()
        compose.onNodeWithTag(PickTags.Sheet, useUnmergedTree = true).assertExists()
        compose
            .onNodeWithTag(
                PickTags.row("saved-1"),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.OnClick)
        settleRust(2000)
        compose.onNodeWithTag(PickTags.Sheet, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(SampleStops.pierW.coord, lastDraft(fixture)?.a?.coord)
        assertEquals(SampleStops.pierE.coord, lastDraft(fixture)?.b?.coord)
    }

    @Test fun aSharedLinkAsksToConfirmAndUseAsBFillsB() {
        val fixture = start()
        compose.runOnUiThread { fixture.container.pendingLink.value = StopsLink.Place(PickFixtures.SHARED) }
        settleRust()
        compose.onNodeWithTag(PickTags.Confirm, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Market Square", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(PickTags.UseB, useUnmergedTree = true).performClick()
        settleRust(2000)
        assertEquals(41.0082, lastDraft(fixture)?.b?.coord?.lat ?: 0.0, 1e-6)
    }

    @Test fun aStopOpensItsSheetAndALinePutsItOnB() {
        val fixture = start(PickFixtures.app(feed = true))
        openPick()
        compose.onNodeWithTag(PickTags.Field).performTextInput("Pier East")
        settleRust()
        compose
            .onNodeWithTag(
                PickTags.row("stop-PE"),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.OnClick)
        settleRust()
        compose.onNodeWithTag(PickTags.StopSheet, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(PickTags.line(1), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settleRust(2000)
        val b = lastDraft(fixture)?.b
        assertEquals("PE", b?.gtfsStop)
        assertTrue(b?.pattern != null)
    }

    @Test fun theNearestChipShowsWhileNothingIsPicked() {
        start()
        compose.onNodeWithText("Nearest: Pier", useUnmergedTree = true).assertExists()
    }
}
