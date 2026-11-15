package dev.rosty.stops.trip

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import dev.rosty.core.model.stops.Ends
import dev.rosty.platform.location.TripStatus
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.shell.permissions.permissionActionTag
import dev.rosty.stops.AppFixture
import dev.rosty.stops.SampleStops
import dev.rosty.stops.saved.SavedTags
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The trip in the real app root: start with and without the preflight, the live card, the rows, Stop, the line. */
@Config(sdk = [35])
class TripAppTest : ShotTest() {
    private fun start(fixture: AppFixture = TripFixtures.app()): AppFixture {
        composeFrame { fixture.Content() }
        settleRust()
        // The map sheet from peek to large, so the trip rows are on screen.
        repeat(2) {
            compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
            settleRust(800)
        }
        return fixture
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        settleRust()
    }

    private fun running(fixture: AppFixture): AppFixture {
        TripFixtures.grant(fixture, *TripFixtures.foreground, *TripFixtures.notifications)
        settleRust()
        // The start runs on the real core: give it time, and tap again if the sheet was still settling.
        repeat(START_TRIES) {
            if (fixture.container.fakeTrips.started
                    .isEmpty()
            ) {
                click(TripTags.Start)
            }
            repeat(START_WAITS) {
                if (fixture.container.fakeTrips.started
                        .isEmpty()
                ) {
                    settleRust(300)
                }
            }
        }
        assertEquals(1, fixture.container.fakeTrips.started.size)
        return fixture
    }

    /** The owner's flow: A and B picked, "Save as stop" on the plan, a name of one's own, no trip at all. */
    @Test fun thePlanSavesThePairAsAStopWithoutATrip() {
        val fixture = TripFixtures.app()
        fixture.container.fake.saved =
            SampleStops.saved.copy(stops = SampleStops.saved.stops.filter { it.name == "School" })
        start(fixture)
        click(TripTags.Save)
        compose.onNodeWithTag(SavedTags.RenameField, useUnmergedTree = true).assertTextContains("Pier West")
        // An empty name cannot be saved: Save waits.
        compose.onNodeWithTag(SavedTags.RenameField, useUnmergedTree = true).performTextReplacement("   ")
        settleRust(300)
        click(SavedTags.RenameSave)
        compose.onNodeWithTag(SavedTags.RenameField, useUnmergedTree = true).assertExists()
        assertEquals(
            1,
            fixture.container.fake.saved!!
                .stops.size,
        )
        compose.onNodeWithTag(SavedTags.RenameField, useUnmergedTree = true).performTextReplacement("Morning ferry")
        settleRust(300)
        click(SavedTags.RenameSave)
        val stops =
            fixture.container.fake.saved!!
                .stops
        assertEquals(listOf("School", "Morning ferry"), stops.map { it.name })
        assertTrue(
            fixture.container.fakeTrips.started
                .isEmpty(),
        )
        compose.onNode(hasTestTag("toast-text") and hasText("Saved as Morning ferry")).assertExists()
        // The plan now says which stop this pair is, and offers no second save.
        compose.onNodeWithTag(TripTags.Save, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(TripTags.Saved, useUnmergedTree = true).assertExists()
        // Undo takes the stop away and the offer comes back.
        compose.onNodeWithText("Undo").performClick()
        settleRust()
        assertEquals(
            1,
            fixture.container.fake.saved!!
                .stops.size,
        )
        compose.onNodeWithTag(TripTags.Save, useUnmergedTree = true).assertExists()
    }

    @Test fun startRunsTheTripWhenNothingBlocks() {
        val fixture = running(start())
        compose.onNodeWithTag(TripTags.Card, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Locating", useUnmergedTree = true).assertExists()
        fixture.container.fakeTrips.push(TripStatus.Fixed(TripFixtures.fix()))
        settleRust()
        compose.onNodeWithTag(TripTags.Distance, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("420 m", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Left side", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Next check in 20 s", useUnmergedTree = true).assertExists()
    }

    /** The running trip is drawn by the pick controller (its ends go on the map, as the draft shows) and picking is off. */
    @Test fun theRunningTripIsOnTheMap() {
        val fixture = running(start(TripFixtures.app(TripFixtures.bStop)))
        val draft =
            fixture.container.fake.writes
                .last { it.file == "draft" }
                .let { Json.decodeFromJsonElement(Ends.serializer(), it.value) }
        assertEquals(TripFixtures.a.coord, draft.a?.coord)
        assertEquals(TripFixtures.b.coord, draft.b?.coord)
        // No hint pill while a trip runs: the map is not picking.
        compose.onNodeWithTag(dev.rosty.stops.home.HomeTags.Hint, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun withoutLocationThePreflightSheetComesFirst() {
        val fixture = start()
        click(TripTags.Start)
        compose.onNodeWithTag(TripTags.Preflight, useUnmergedTree = true).assertExists()
        // Start is off while location is missing: a tap starts nothing.
        click(TripTags.PreflightStart)
        assertTrue(
            fixture.container.fakeTrips.started
                .isEmpty(),
        )
        TripFixtures.grant(fixture, *TripFixtures.foreground, *TripFixtures.notifications)
        settleRust()
        click(TripTags.PreflightStart)
        assertEquals(1, fixture.container.fakeTrips.started.size)
    }

    /** AD-SA8: the row shows, the trip stays, Resume starts the service again. */
    @Test fun aRefusedRestartShowsTheRowAndResumes() {
        val fixture = running(start())
        fixture.container.fakeTrips.push(TripStatus.Error("restart"))
        settleRust()
        compose.onNodeWithTag(TripTags.Restart, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TripTags.Card, useUnmergedTree = true).assertExists()
        click(TripTags.Resume)
        assertEquals(2, fixture.container.fakeTrips.started.size)
        compose.onNodeWithTag(TripTags.Restart, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aGrantRevokedWhileRunningKeepsTheTripAndShowsTheRow() {
        val fixture = running(start())
        TripFixtures.grant(fixture, *TripFixtures.foreground, granted = false)
        settleRust()
        compose.onNodeWithTag(TripTags.Card, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(permissionActionTag(PermissionGroup.LOCATION), useUnmergedTree = true).assertExists()
        assertTrue(fixture.container.fakeTrips.running)
    }

    @Test fun theBackgroundRowIsRecommended() {
        running(start())
        compose
            .onNodeWithTag(
                permissionActionTag(PermissionGroup.BACKGROUND_LOCATION),
                useUnmergedTree = true,
            ).assertExists()
    }

    @Test fun stopEndsTheTrip() {
        val fixture = running(start())
        click(TripTags.Stop)
        compose.onNodeWithText("Trip stopped").assertExists()
        compose.onNodeWithTag(TripTags.Card, useUnmergedTree = true).assertDoesNotExist()
        assertTrue(!fixture.container.fakeTrips.running)
    }

    /** The line-aware part of E7.5 (AD-ST6): the lines at the transit stop B; choosing one keeps its pattern on B. */
    @Test fun choosingALinePutsItsPatternOnThePoint() {
        val fixture = TripFixtures.app(TripFixtures.bStop)
        TripFixtures.writeFeed()
        start(fixture)
        compose.onNodeWithTag(TripTags.Line, useUnmergedTree = true).performClick()
        settleRust(800)
        compose
            .onNode(
                hasText("7", substring = true) and hasText("Harbour", substring = true) and hasClickAction(),
            ).performClick()
        settleRust()
        val draft =
            fixture.container.fake.writes
                .last { it.file == "draft" }
                .let { Json.decodeFromJsonElement(Ends.serializer(), it.value) }
        assertEquals("PE", draft.b?.gtfsStop)
        assertTrue(draft.b?.pattern != null)
    }

    private companion object {
        const val START_TRIES = 2
        const val START_WAITS = 5
    }
}
