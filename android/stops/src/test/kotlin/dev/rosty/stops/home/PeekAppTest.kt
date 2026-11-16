package dev.rosty.stops.home

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rosty.core.model.stops.Ends
import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.SampleStops
import dev.rosty.stops.saved.settleRust
import dev.rosty.stops.trip.TripFixtures
import dev.rosty.stops.trip.TripTags
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The owner's request: the map sheet at peek shows everything down to its buttons. With no plan the title and the
 * hint, with A and B the plan's Start, Clear and "Save as stop", during a trip the live card's key line and its
 * buttons; what follows starts under the sheet's edge. (The camera's padding follows the peek: `HomeGestureTest`.)
 */
@Config(sdk = [35])
class PeekAppTest : ShotTest() {
    private fun open(core: FakeStopsCore) {
        val fixture = AppFixture(core)
        composeFrame { fixture.Content() }
        settleRust(1500)
    }

    private fun sheetTop(): Dp = compose.onNodeWithTag("sheet").getUnclippedBoundsInRoot().top

    /** The visible bottom of the sheet: the window less the simulated 24 dp gesture bar. */
    private fun visibleBottom(): Dp = compose.onRoot().getUnclippedBoundsInRoot().bottom - GESTURE_BAR

    private fun assertShownAtPeek(vararg tags: String) {
        val top = sheetTop()
        val bottom = visibleBottom()
        for (tag in tags) {
            val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("$tag starts under the sheet's top ($bounds, top $top)", bounds.top >= top)
            assertTrue("$tag ends above the safe area ($bounds, bottom $bottom)", bounds.bottom <= bottom)
        }
    }

    @Test fun withNoPlanPeekShowsTheTitleAndTheHint() {
        open(FakeStopsCore(saved = null, history = null, transitImportedMs = null))
        assertShownAtPeek(HomeSheetTags.Title, HomeSheetTags.Subtitle)
    }

    @Test fun withAAndBPeekShowsStartClearAndSave() {
        val onlySchool = SampleStops.saved.copy(stops = SampleStops.saved.stops.filter { it.name == "School" })
        open(
            FakeStopsCore(
                saved = onlySchool,
                draft = Ends(TripFixtures.a, TripFixtures.b),
                real = HostCore.real(),
            ),
        )
        assertShownAtPeek(
            HomeSheetTags.Title,
            HomeSheetTags.Subtitle,
            TripTags.Start,
            TripTags.Clear,
            TripTags.Save,
        )
        // The rule line under the buttons waits under the sheet's edge.
        val rule = compose.onNodeWithTag(TripTags.PlanRule, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(rule.top >= visibleBottom() - 1.dp)
    }

    @Test fun duringATripPeekShowsTheKeyLineAndTheButtons() {
        open(FakeStopsCore(trip = SampleStops.pierW to SampleStops.pierE))
        assertShownAtPeek(
            HomeSheetTags.Title,
            HomeSheetTags.Subtitle,
            TripTags.Distance,
            TripTags.Copy,
            TripTags.Share,
            TripTags.Stop,
        )
    }

    private companion object {
        val GESTURE_BAR = 24.dp
    }
}
