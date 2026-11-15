package dev.rosty.stops.history

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.design.time.RostyClock
import dev.rosty.design.time.SystemRostyClock
import dev.rosty.platform.location.TripClock
import dev.rosty.stops.home.HomeSheetTags
import dev.rosty.stops.saved.settleRust
import dev.rosty.testing.ShotTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.TimeZone

/** E7.11 screenshots on the real core: the history, its statistics, the filter and a trip's menu. */
@Config(sdk = [35])
class E711ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ShotTest.CHANGE_THRESHOLD))
    private val zone = TimeZone.getDefault()

    @Before fun fixClock() {
        TripClock.clock = RostyClock { HistoryFixtures.NOW }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun resetClock() {
        TripClock.clock = SystemRostyClock
        TimeZone.setDefault(zone)
    }

    private fun historyShot(
        name: String,
        large: Boolean = true,
        then: () -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            val fixture = HistoryFixtures.app()
            composeFrame(dark = dark) { fixture.Content() }
            settleRust()
            compose
                .onNodeWithTag(HomeSheetTags.HistoryRow, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnClick)
            settleRust()
            if (large) {
                compose.onAllNodesWithTag("sheet-grabber").onLast().performSemanticsAction(SemanticsActions.OnClick)
                settleRust()
            }
            then()
            compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png", options)
        }
    }

    @Test fun history() = historyShot("e7.11-history", large = false)

    @Test fun historyStats() = historyShot("e7.11-history-stats")

    @Test fun historyTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        historyShot("e7.11-history-tr")
    }

    @Test fun historyFilter() =
        historyShot("e7.11-history-filter") {
            compose
                .onNodeWithTag(
                    HistoryTags.List,
                    useUnmergedTree = true,
                ).performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, 2000f)
                }
            settleRust(800)
            compose
                .onNode(hasText("Cancelled") and hasAnyAncestor(hasTestTag(HistoryTags.Filters)) and hasClickAction())
                .performSemanticsAction(SemanticsActions.OnClick)
            settleRust()
        }

    @Test fun historyRowMenu() =
        historyShot("e7.11-history-row-menu") {
            compose
                .onNodeWithTag(
                    HistoryTags.List,
                    useUnmergedTree = true,
                ).performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, 2000f)
                }
            settleRust(800)
            compose
                .onNodeWithTag(HistoryTags.row(2), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnClick)
            settleRust(800)
        }
}
