package dev.rovsti.catalog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
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
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
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
}
