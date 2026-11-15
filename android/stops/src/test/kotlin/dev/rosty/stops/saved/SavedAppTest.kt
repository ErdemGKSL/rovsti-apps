package dev.rosty.stops.saved

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.rosty.core.model.stops.Ends
import dev.rosty.stops.AppFixture
import dev.rosty.stops.SampleStops
import dev.rosty.stops.StopsLink
import dev.rosty.stops.home.HomeSheet
import dev.rosty.testing.ShotTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The Saved sheet in the real app root, on the real core over the fake's files. */
@Config(sdk = [35])
class SavedAppTest : ShotTest() {
    private fun start(fixture: AppFixture = AppFixture(SavedFixtures.core())): AppFixture {
        composeFrame { fixture.Content() }
        settleRust()
        return fixture
    }

    private fun openSaved(fixture: AppFixture) {
        compose.runOnUiThread { fixture.container.pendingSheet.value = HomeSheet.Saved }
        settleRust()
    }

    /** The last draft the map wrote (the pick ends survive a restart through it). */
    private fun lastDraft(fixture: AppFixture): Ends? =
        fixture.container.fake.writes
            .lastOrNull { it.file == "draft" }
            ?.let { Json.decodeFromJsonElement(Ends.serializer(), it.value) }

    @Test fun theListShowsTheStopsWithTheirDetail() {
        val fixture = start()
        openSaved(fixture)
        compose.onNodeWithTag(SavedTags.row(1), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("★ Pier", useUnmergedTree = true).assertExists()
        assertEquals(
            2,
            compose.onAllNodesWithText(" away", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Used", substring = true, useUnmergedTree = true).assertExists()
        // Two stops: a sort pill, no search field (it shows above five).
        compose.onNodeWithTag(SavedTags.Sort, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SavedTags.Search, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun deleteFromTheRowMenuOffersUndo() {
        val fixture = start()
        openSaved(fixture)
        compose.onNodeWithTag(SavedTags.rowMore(2), useUnmergedTree = true).performClick()
        settleRust(600)
        // A real tap: the "..." takes its own touch (the row under it does not open its sheet).
        compose.onNodeWithTag(SavedTags.StopSheet, useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        settleRust()
        compose.onNodeWithText("Deleted School").assertExists()
        assertEquals(
            listOf(1L),
            fixture.container.fake.saved!!
                .stops
                .map { it.id },
        )
        compose.onNodeWithText("Undo").performClick()
        settleRust()
        assertEquals(
            listOf(1L, 2L),
            fixture.container.fake.saved!!
                .stops
                .map { it.id },
        )
    }

    @Test fun aRowOpensItsSheetAndShowOnMapPutsBothEndsOnTheMap() {
        val fixture = start()
        openSaved(fixture)
        compose.onNodeWithTag(SavedTags.row(2), useUnmergedTree = true).performClick()
        settleRust()
        compose.onNodeWithTag(SavedTags.StopSheet, useUnmergedTree = true).assertExists()
        compose
            .onNodeWithTag(
                SavedTags.stopRow("show"),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.OnClick)
        settleRust(2500)
        compose.onNodeWithTag(SavedTags.Sheet, useUnmergedTree = true).assertDoesNotExist()
        val draft = lastDraft(fixture)
        assertEquals(SampleStops.school.coord, draft?.a?.coord)
        assertEquals(SampleStops.library.coord, draft?.b?.coord)
    }

    @Test fun renameFromTheStopSheet() {
        val fixture = start()
        openSaved(fixture)
        compose.onNodeWithTag(SavedTags.row(2), useUnmergedTree = true).performClick()
        settleRust()
        compose
            .onNodeWithTag(
                SavedTags.stopRow("rename"),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.OnClick)
        settleRust()
        compose.onNodeWithTag(SavedTags.RenameField).performTextClearance()
        compose.onNodeWithTag(SavedTags.RenameField).performTextInput("School run")
        compose.onNodeWithTag(SavedTags.RenameSave, useUnmergedTree = true).performClick()
        settleRust()
        assertEquals(
            "School run",
            fixture.container.fake.saved!!
                .stops
                .first { it.id == 2L }
                .name,
        )
    }

    @Test fun homeScreenPinsAShortcut() {
        val fixture = start()
        openSaved(fixture)
        compose.onNodeWithTag(SavedTags.row(1), useUnmergedTree = true).performClick()
        settleRust()
        compose
            .onNodeWithTag(
                SavedTags.stopRow("home"),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.OnClick)
        settleRust()
        assertEquals(
            listOf(1L),
            fixture.container.fakeShortcuts.pinned
                .map { it.stopId },
        )
        compose.onNodeWithText("Added to the home screen").assertExists()
    }

    @Test fun editShowsTheHandlesAndDoneHidesThem() {
        val fixture = start()
        openSaved(fixture)
        compose.onNodeWithTag(SavedTags.Edit, useUnmergedTree = true).performClick()
        settleRust(600)
        compose.onNodeWithTag(SavedTags.handle(1), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SavedTags.Done, useUnmergedTree = true).performClick()
        settleRust(600)
        compose.onNodeWithTag(SavedTags.handle(1), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun searchShowsAboveFiveStops() {
        val fixture = start(AppFixture(SavedFixtures.core(SavedFixtures.many(8))))
        openSaved(fixture)
        compose.onNodeWithTag(SavedTags.Search, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(SavedTags.Search).performTextInput("mark")
        settleRust()
        compose.onNodeWithText("Market", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Bakery", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aStopShortcutPutsItsGateOnTheMap() {
        val fixture = start()
        compose.runOnUiThread { fixture.container.pendingLink.value = StopsLink.Stop(1) }
        settleRust(2500)
        val draft = lastDraft(fixture)
        assertEquals(SampleStops.pierW.coord, draft?.a?.coord)
        assertTrue(fixture.container.pendingLink.value == null)
    }
}
