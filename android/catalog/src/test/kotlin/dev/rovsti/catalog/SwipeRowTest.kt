package dev.rovsti.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.list.SwipeAction
import dev.rovsti.design.list.SwipeRow
import dev.rovsti.design.list.SwipeRowGroup
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.29: swipe row reveal, full swipe, settle back, accessibility actions; screenshots revealed and full. */
class SwipeRowTest : ShotTest() {
    private val haptics = RecordingHaptics()
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun open(dark: Boolean = false) {
        composeFrame(dark = dark) {
            CompositionLocalProvider(
                LocalRovstiHaptics provides haptics,
            ) { CatalogApp(initialPage = CatalogPage.Swipe) }
        }
        advance(600)
    }

    private fun log() =
        compose
            .onNodeWithTag("swipe-log")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString { it.text } ?: ""

    private fun row() = compose.onNodeWithTag("row-Ada Lovelace")

    @Test
    fun dragRevealsAndSettlesOnActionWidth() {
        open()
        row().performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(-50f, 0f), 100) }
            advance(300)
            up()
        }
        advance(800)
        // revealed: Delete is tappable and nothing fired yet
        compose.onAllNodesWithTag("swipe-action-Delete")[0].assertExists()
        assertTrue(log().startsWith("Swipe a row"))
        compose.onAllNodesWithTag("swipe-action-Delete")[0].performTouchInput { click() }
        advance(600)
        assertEquals("Delete Ada Lovelace", log())
    }

    @Test
    fun fullSwipeFiresFirstTrailingActionOnceWithHaptic() {
        open()
        row().performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(-width * 0.1f, 0f), 16) }
            up()
        }
        advance(800)
        assertEquals("Archive Ada Lovelace", log())
        assertEquals(1, haptics.events.count { it == HapticEvent.Confirm })
    }

    @Test
    fun smallDragSettlesBack() {
        open()
        row().performTouchInput {
            down(center)
            moveBy(Offset(-10f, 0f))
            up()
        }
        advance(800)
        assertTrue(log().startsWith("Swipe a row"))
    }

    @Test
    fun accessibilityActionsListedAndWork() {
        open()
        val actions = row().fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Pin", "Archive", "Delete"), actions)
        val del = row().fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Delete" }
        del.action()
        advance(200)
        assertEquals("Delete Ada Lovelace", log())
    }

    @Test
    fun screenshots() {
        for (dark in listOf(false, true)) {
            val s = if (dark) "dark" else "light"
            open(dark)
            row().performTouchInput {
                down(center)
                repeat(8) { moveBy(Offset(-50f, 0f), 100) }
                advance(300)
                up()
            }
            advance(800)
            compose.onRoot().captureRoboImage("screenshots/e1.29-swipe-revealed-$s.png", roborazziOptions = options)
            open(dark)
            row().performTouchInput {
                down(center)
                repeat(8) { moveBy(Offset(-width * 0.105f, 0f), 16) }
            }
            advance(100)
            compose.onRoot().captureRoboImage("screenshots/e1.29-swipe-full-$s.png", roborazziOptions = options)
            row().performTouchInput { up() }
        }
    }

    // ---- E1.19: SwipeRow facade and SwipeRowGroup ----

    private val clicks = mutableListOf<String>()

    @androidx.compose.runtime.Composable
    private fun GroupRows() {
        SwipeRowGroup {
            androidx.compose.foundation.layout.Column {
                for (n in listOf("A", "B")) {
                    SwipeRow(
                        modifier =
                            androidx.compose.ui.Modifier
                                .testTag("group-$n"),
                        trailing =
                            persistentListOf(
                                SwipeAction("Mute", androidx.compose.ui.graphics.Color.Blue) { clicks += "mute-$n" },
                                SwipeAction("Delete", androidx.compose.ui.graphics.Color.Red) { clicks += "delete-$n" },
                            ),
                    ) {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clickable { clicks += "tap-$n" }
                                .testTag("content-$n"),
                        )
                    }
                }
            }
        }
    }

    private fun contentX(n: String) =
        compose
            .onNodeWithTag("content-$n")
            .fetchSemanticsNode()
            .positionInRoot.x

    private fun openRow(n: String) {
        compose.onNodeWithTag("group-$n").performTouchInput {
            down(center)
            // The row follows the finger through a coroutine, so only the first moves are applied before the lift:
            // a few big ones put the row well past half of the 456 px of actions.
            repeat(4) { moveBy(Offset(-150f, 0f), 100) }
            advance(300)
            up()
        }
        advance(800)
    }

    @Test
    fun onlyOneRowOfAGroupIsOpen() {
        clicks.clear()
        composeFrame { GroupRows() }
        advance(300)
        openRow("A")
        assertTrue("A is open at ${contentX("A")} clicks=$clicks", contentX("A") < -100f)
        openRow("B")
        assertTrue("B is open", contentX("B") < -100f)
        assertEquals("A closed when B opened", 0f, contentX("A"), 1f)
    }

    @Test
    fun aTapOnAnOpenRowOnlyClosesItAndAClosedRowStillClicks() {
        clicks.clear()
        composeFrame { GroupRows() }
        advance(300)
        compose.onNodeWithTag("content-A").performTouchInput { click() }
        advance(300)
        assertEquals(listOf("tap-A"), clicks)
        openRow("A")
        compose.onNodeWithTag("content-A").performTouchInput { click() }
        advance(800)
        assertEquals("the tap closed the row and did not click it", listOf("tap-A"), clicks)
        assertEquals(0f, contentX("A"), 1f)
        // Another row's tap while A is open closes A and does not reach B either.
        openRow("A")
        compose.onNodeWithTag("content-B").performTouchInput { click() }
        advance(800)
        assertEquals(listOf("tap-A"), clicks)
        assertEquals(0f, contentX("A"), 1f)
    }

    @Test
    fun theLastTrailingActionRunsOnAFullSwipe() {
        clicks.clear()
        composeFrame { GroupRows() }
        advance(300)
        compose.onNodeWithTag("group-A").performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(-width * 0.1f, 0f), 16) }
            up()
        }
        advance(800)
        assertEquals(listOf("delete-A"), clicks)
    }
}
