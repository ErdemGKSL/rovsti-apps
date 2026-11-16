package dev.rosty.stops.trip

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.rosty.core.model.headless.TripState
import dev.rosty.design.time.RostyClock
import dev.rosty.design.time.SystemRostyClock
import dev.rosty.platform.location.TripClock
import dev.rosty.platform.location.TripStatus
import dev.rosty.stops.AppFixture
import dev.rosty.stops.SampleStops
import dev.rosty.stops.saved.SavedTags
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** The in-app alarm and the last trip card in the real app root (real core, fake alarm and service). */
@Config(sdk = [35])
class AlarmAppTest : ShotTest() {
    @After fun resetClock() {
        TripClock.clock = SystemRostyClock
    }

    private fun start(fixture: AppFixture = TripFixtures.app()): AppFixture {
        composeFrame { fixture.Content() }
        settleRust()
        repeat(2) {
            compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
            settleRust(800)
        }
        return fixture
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        settleRust()
    }

    /** A trip that has just rung: the service's fix said Arrived with ring. */
    private fun ringing(): AppFixture {
        val fixture = start()
        TripFixtures.grant(fixture, *TripFixtures.foreground, *TripFixtures.notifications)
        tap(TripTags.Start)
        fixture.container.fakeTrips.push(TripStatus.Fixed(TripFixtures.fix(0.0, TripState.Arrived)))
        settleRust()
        compose.onNodeWithTag(AlarmTags.Screen, useUnmergedTree = true).assertExists()
        return fixture
    }

    @Test fun dismissAnswersTheAlarmAndOffersReArm() {
        val fixture = ringing()
        compose.onNodeWithText("Crossed Pier West – Pier East", useUnmergedTree = true).assertExists()
        tap(AlarmTags.Dismiss)
        assertEquals(1, fixture.container.fakeAlarm.dismissed)
        compose.onNodeWithTag(AlarmTags.Screen, useUnmergedTree = true).assertDoesNotExist()
        fixture.container.fakeAlarm.push(AlarmEvent.Dismissed)
        settleRust()
        compose.onNodeWithText("Alarm dismissed", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Re-arm", useUnmergedTree = true).assertExists()
    }

    @Test fun snoozeAnswersTheAlarmAndSaysWhen() {
        val fixture = ringing()
        compose.onNodeWithText("Snooze 5 min", useUnmergedTree = true).assertExists()
        tap(AlarmTags.Snooze)
        assertEquals(1, fixture.container.fakeAlarm.snoozed)
        fixture.container.fakeAlarm.push(AlarmEvent.Later(TripClock.nowMillis() + 5 * 60_000))
        settleRust()
        compose.onNodeWithText("Snoozed. Ringing again in 5 min.", useUnmergedTree = true).assertExists()
    }

    /** S27: the trip that ended in the last 12 hours, until Close. */
    @Test fun theLastTripCardShowsUntilClosed() {
        TripClock.clock = RostyClock { LAST_TRIP_NOW }
        start()
        compose.onNodeWithTag(AlarmTags.LastTrip, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Arrived at", substring = true, useUnmergedTree = true).assertExists()
        tap(AlarmTags.Close)
        compose.onNodeWithTag(AlarmTags.LastTrip, useUnmergedTree = true).assertDoesNotExist()
    }

    /** S27: "Save as stop" on the last trip card saves the trip's two ends, with Undo. */
    @Test fun theLastTripCardSavesItsTripAsAStop() {
        TripClock.clock = RostyClock { LAST_TRIP_NOW }
        val fixture = TripFixtures.app()
        fixture.container.fake.saved =
            SampleStops.saved.copy(stops = SampleStops.saved.stops.filter { it.name == "School" })
        start(fixture)
        tap(AlarmTags.Save)
        compose.onNodeWithTag(SavedTags.RenameField, useUnmergedTree = true).assertExists()
        tap(SavedTags.RenameSave)
        val stops =
            fixture.container.fake.saved!!
                .stops
        assertEquals(2, stops.size)
        assertEquals(SampleStops.pierE.coord, stops.last().b.coord)
        compose.onNode(hasTestTag("toast-text") and hasText("Saved as Pier West")).assertExists()
    }

    companion object {
        /** Twenty minutes after the sample history's one trip ended. */
        const val LAST_TRIP_NOW = 1_790_001_200_000L
    }
}
