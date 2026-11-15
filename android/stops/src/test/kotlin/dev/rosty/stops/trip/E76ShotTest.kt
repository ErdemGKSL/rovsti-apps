package dev.rosty.stops.trip

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.headless.TripState
import dev.rosty.design.time.RostyClock
import dev.rosty.design.time.SystemRostyClock
import dev.rosty.platform.location.TripClock
import dev.rosty.platform.location.TripStatus
import dev.rosty.stops.AppFixture
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * E7.6 screenshots: the in-app alarm, the S27 arrival summary (the last trip card) and S21 (`e7.6-ring-again-menu`:
 * the "Alarm dismissed" pill with Re-arm; GPUI has no ring-again menu, its S21 is this pill).
 */
@Config(sdk = [35])
class E76ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ShotTest.CHANGE_THRESHOLD))

    @After fun resetClock() {
        TripClock.clock = SystemRostyClock
    }

    private fun alarmShot(
        name: String,
        then: (AppFixture) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            val fixture = TripFixtures.app()
            composeFrame(dark = dark) { remember { fixture }.Content() }
            settleRust()
            repeat(2) {
                compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
                settleRust(800)
            }
            then(fixture)
            compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png", options)
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        settleRust()
    }

    private fun ring(fixture: AppFixture) {
        TripFixtures.grant(fixture, *TripFixtures.foreground, *TripFixtures.notifications)
        tap(TripTags.Start)
        fixture.container.fakeTrips.push(TripStatus.Fixed(TripFixtures.fix(0.0, TripState.Arrived)))
        settleRust()
    }

    @Test fun alarmInApp() = alarmShot("e7.6-alarm-in-app", ::ring)

    @Test fun arrivalSummary() {
        TripClock.clock = RostyClock { AlarmAppTest.LAST_TRIP_NOW }
        // The circle behind the sheet comes from the core a call or two later: wait for it, so the golden holds.
        alarmShot("e7.6-arrival-summary") { settleRust(2500) }
    }

    @Test fun ringAgain() =
        alarmShot("e7.6-ring-again-menu") { fixture ->
            ring(fixture)
            // The view has followed the gate to its last settle before the answer.
            settleRust(1500)
            tap(AlarmTags.Dismiss)
            // What the platform does on Dismiss: the trip ends into the history, then the event.
            kotlinx.coroutines.runBlocking {
                fixture.container.ops.storeTripEnd(
                    dev.rosty.core.model.stops.StoreTripEndRequest(
                        "arrived",
                        TripClock.nowMillis(),
                        fixture.container.dataDir,
                    ),
                )
            }
            fixture.container.fakeAlarm.push(AlarmEvent.Dismissed)
            settleRust(1500)
        }
}
