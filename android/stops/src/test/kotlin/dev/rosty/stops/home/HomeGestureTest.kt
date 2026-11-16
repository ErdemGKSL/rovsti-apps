package dev.rosty.stops.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.stops.HomeTab
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The map home's gestures (manual clock): the sheet moves between its detents, the map keeps taking taps at peek and
 * medium, the button column rides the sheet by a layer translation and the camera padding changes on settle only.
 */
@Config(sdk = [35])
class HomeGestureTest : ShotTest() {
    private val map = HomeTestMap()
    private val camera = MapCameraPadding()
    private var locate = 0

    private fun start(sheetContent: (@Composable () -> Unit)? = null) {
        val controller = OverlayController()
        composeFrame {
            OverlayHost(controller) {
                StopsHome(
                    state = HomeState(loading = false),
                    map = map.layer,
                    tabs = homeTabItems(),
                    selectedTab = HomeTab.Trip,
                    actions = HomeActions(onLocate = { locate++ }),
                    camera = camera,
                    sheetContent = sheetContent ?: { MapSheetContent(HomeState(loading = false), {}, {}) },
                )
            }
        }
        run(1500)
    }

    private fun run(ms: Long) = repeat((ms / 100).toInt()) { advance(100) }

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot.top

    private fun bottom(tag: String) =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot.bottom

    private fun windowHeight() =
        compose
            .onNodeWithTag(HomeTags.Root)
            .fetchSemanticsNode()
            .boundsInRoot.height

    @Test fun theSheetOpensAtPeekWithTheButtonsAboveIt() {
        start()
        val sheetTop = top("sheet")
        // Peek ends a gap under the hint (the measured peek), above the 24 dp gesture bar.
        val hint = bottom(HomeSheetTags.Subtitle)
        assertTrue(hint <= windowHeight() - 24f * density)
        assertEquals(windowHeight() - 24f * density, hint + 12f * density, 2f)
        assertTrue("the buttons sit above the sheet", bottom(HomeTags.Buttons) <= sheetTop)
        assertEquals((windowHeight() - sheetTop) / density, camera.insets.calculateBottomPadding().value, 1f)
    }

    @Test fun theMapTakesTapsAtPeekAndMedium() {
        start()
        compose.onNodeWithTag(HomeMapTag).performTouchInput { click(Offset(centerX, height * 0.3f)) }
        run(500)
        assertEquals(1, map.taps)
        // The grabber's accessibility action steps the sheet up to medium.
        compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
        run(1500)
        assertEquals(windowHeight() * 0.5f, top("sheet"), 2f)
        compose.onNodeWithTag(HomeMapTag).performTouchInput { click(Offset(centerX, height * 0.25f)) }
        run(500)
        assertEquals(2, map.taps)
        // The buttons rode up with it and still take taps.
        assertTrue(bottom(HomeTags.Buttons) <= top("sheet"))
        compose.onNodeWithTag(HomeTags.Locate).performClick()
        assertEquals(1, locate)
    }

    @Test fun theCameraPaddingFollowsTheSettledDetentNotTheFinger() {
        start()
        val before = camera.updates
        val peek = camera.insets.calculateBottomPadding().value
        val grabber = compose.onNodeWithTag("sheet-grabber")
        grabber.performTouchInput { down(center) }
        repeat(12) {
            grabber.performTouchInput { moveBy(Offset(0f, -30f * density)) }
            advance(16)
        }
        // Mid-drag: the sheet moved, the camera did not.
        assertTrue(top("sheet") < windowHeight() - (peek + 150f) * density)
        assertEquals(before, camera.updates)
        assertEquals(peek, camera.insets.calculateBottomPadding().value, 0.01f)
        grabber.performTouchInput { up() }
        run(1500)
        assertEquals(windowHeight() * 0.5f / density, camera.insets.calculateBottomPadding().value, 2f)
        assertEquals(before + 1, camera.updates)
        // The top inset is the pill's bottom edge, the end inset the button column.
        assertTrue(camera.insets.calculateTopPadding().value > 28f + 48f)
        assertTrue(
            camera.insets
                .calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                .value >= 48f,
        )
    }

    @Test fun theRideIsATranslationOnly() {
        start()
        val buttons = compose.onNodeWithTag(HomeTags.Bottom)
        val sizeAtPeek = buttons.fetchSemanticsNode().size
        compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
        run(1500)
        assertEquals("no relayout: the block keeps its size", sizeAtPeek, buttons.fetchSemanticsNode().size)
    }

    /** The owner's request: peek reaches down to the content's last peek element; the camera clears the new peek. */
    @Test fun thePeekReachesTheMarkedContentAndTheCameraFollows() {
        var tall by mutableStateOf(false)
        start {
            MapSheetContent(HomeState(loading = false), {}, {}, trip = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (tall) TALL else SHORT)
                        .then(rememberPeekEnd())
                        .testTag("block"),
                )
            })
        }
        val first = camera.insets.calculateBottomPadding().value
        assertTrue("a short block still peeks", first > 120f)
        assertTrue("the block shows in full", bottom("block") <= windowHeight() - 24f * density)

        tall = true
        run(1500)
        val second = camera.insets.calculateBottomPadding().value
        assertEquals((TALL - SHORT).value, second - first, 1.5f)
        assertEquals(windowHeight() - second * density, top("sheet"), 2f)
        assertTrue("the taller block shows in full", bottom("block") <= windowHeight() - 24f * density)
        assertTrue("the buttons ride above the sheet", bottom(HomeTags.Buttons) <= top("sheet"))
    }

    private companion object {
        val SHORT = 80.dp
        val TALL = 200.dp
    }
}
