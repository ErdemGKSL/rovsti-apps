package dev.rosty.stops.trip

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.stops.Destination
import dev.rosty.platform.location.TripStatus
import dev.rosty.stops.AppFixture
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * E7.5 screenshots on the real core: the preflight sheet, the live card (light and dark), the recommended background
 * row, the AD-SA8 restart row and the line choice at a transit stop. `e7.5-line-aware-alarm-setting` is
 * `e7.5-line-choice-in-stop-ui`: there is no "N stops before" alarm any more (AD-ST6), the line-aware part is the
 * choice of the line a transit stop is reached by.
 */
@Config(sdk = [35])
class E75ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ShotTest.CHANGE_THRESHOLD))

    private fun tripShot(
        name: String,
        end: Destination = TripFixtures.b,
        feed: Boolean = false,
        settle: Int = LARGE,
        before: (AppFixture) -> Unit = {},
        then: (AppFixture) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            val fixture = TripFixtures.app(end)
            if (feed) TripFixtures.writeFeed()
            before(fixture)
            composeFrame(dark = dark) { remember { fixture }.Content() }
            settleRust()
            repeat(2) {
                compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
                settleRust(800)
            }
            then(fixture)
            // From large the grabber's action goes to peek, then up again to medium.
            val steps =
                when (settle) {
                    PEEK -> 1
                    MEDIUM -> 2
                    else -> 0
                }
            repeat(steps) {
                compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
                settleRust(800)
            }
            compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png", options)
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        settleRust()
    }

    private fun startRunning(fixture: AppFixture) {
        TripFixtures.grant(fixture, *TripFixtures.foreground, *TripFixtures.notifications)
    }

    @Test fun preflightSheet() =
        tripShot("e7.5-preflight-sheet") {
            tap(TripTags.Start)
        }

    @Test fun liveCard() =
        tripShot("e7.5-trip-live-card", settle = MEDIUM, before = { fixture ->
            // Everything recommended is allowed too: the card alone.
            startRunning(fixture)
            fixture.container.fakeSpecial.ignoresBattery = true
            TripFixtures.grant(fixture, *TripFixtures.background)
        }) { fixture ->
            tap(TripTags.Start)
            fixture.container.fakeTrips.push(TripStatus.Fixed(TripFixtures.fix()))
            settleRust()
        }

    /** At peek the map shows the trip: both pins, the circle and the chosen line (Line 7 at Pier East). */
    @Test fun tripOnMap() =
        tripShot(
            "e7.5-trip-on-map",
            end = TripFixtures.bStop,
            feed = true,
            settle = PEEK,
            before = ::startRunning,
        ) { fixture ->
            tap(TripTags.Line)
            compose
                .onNode(
                    hasText("7", substring = true) and hasText("Harbour", substring = true) and hasClickAction(),
                ).performClick()
            settleRust()
            tap(TripTags.Start)
            fixture.container.fakeTrips.push(TripStatus.Fixed(TripFixtures.fix()))
            settleRust()
        }

    @Test fun backgroundRow() =
        tripShot("e7.5-background-row", before = ::startRunning) { fixture ->
            tap(TripTags.Start)
            fixture.container.fakeTrips.push(TripStatus.Fixed(TripFixtures.fix()))
            settleRust()
        }

    @Test fun restartRefusedRow() =
        tripShot("e7.5-restart-refused-row", before = ::startRunning) { fixture ->
            tap(TripTags.Start)
            fixture.container.fakeTrips.push(TripStatus.Fixed(TripFixtures.fix()))
            fixture.container.fakeTrips.push(TripStatus.Error("restart"))
            settleRust()
        }

    @Test fun lineChoice() =
        tripShot("e7.5-line-choice-in-stop-ui", end = TripFixtures.bStop, feed = true) {
            tap(TripTags.Line)
        }

    private companion object {
        /** The detent a shot settles on. */
        const val PEEK = 0
        const val MEDIUM = 1
        const val LARGE = 2
    }
}
