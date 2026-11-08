package dev.rovsti.catalog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.list.AlphabetRailTags
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.19: scrubbing the alphabet rail of the lists page (scroll target, haptics, TalkBack, geometry, keyboard). */
class AlphabetRailTest : ShotTest() {
    private val haptics = RecordingHaptics()

    private fun open(
        dark: Boolean = false,
        ime: Boolean = false,
    ) {
        composeFrame(dark = dark) {
            CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
                OverlayHost(remember { OverlayController() }) { ListsPage(imeVisible = { ime }) }
            }
        }
        advance(300)
    }

    private fun rail() = compose.onNodeWithTag(AlphabetRailTags.Rail)

    /** Rail slot `n` (0 is the top arrow, 1 is A) as a y inside the rail, in pixels. */
    private fun slotY(n: Int): Float {
        val node = rail().fetchSemanticsNode()
        return node.size.height / SLOTS * (n + 0.5f)
    }

    private fun scrubTo(
        letter: Char,
        hold: Boolean = false,
    ) {
        rail().performTouchInput {
            down(Offset(centerX, slotY(1)))
            for (slot in 2..(1 + (letter - 'A'))) moveTo(Offset(centerX, slotY(slot)))
            if (!hold) up()
        }
        advance(300)
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    @Test
    fun scrubbingToMPutsMsHeaderFirst() {
        open()
        compose.onNodeWithTag("header-A").assertIsDisplayed()
        scrubTo('M')
        compose.onNodeWithTag("header-M").assertIsDisplayed()
        // It is the first thing under the status bar; the next header is a full group below.
        assertEquals(28f, top("header-M").value, 2f)
        assertTrue(top("header-N").value > top("header-M").value)
        compose
            .onNode(
                androidx.compose.ui.test
                    .hasText("Mira", substring = true),
            ).assertExists()
    }

    @Test
    fun oneHapticPerLetterVisited() {
        open()
        scrubTo('M')
        // A..M = 13 distinct slots, one tick each; none on release and none for a repeated slot.
        assertEquals(13, haptics.events.count { it == HapticEvent.Tick })
    }

    @Test
    fun staysInsideItsSlotWhenRepeatedMovesDoNotChangeTheLetter() {
        open()
        rail().performTouchInput {
            down(Offset(centerX, slotY(5)))
            repeat(4) { moveTo(Offset(centerX, slotY(5) + 2f * it)) }
            up()
        }
        assertEquals(1, haptics.events.count { it == HapticEvent.Tick })
    }

    @Test
    fun bubbleShowsTheLetterNextToTheFinger() {
        open()
        scrubTo('M', hold = true)
        val bubble = compose.onNodeWithTag(AlphabetRailTags.Bubble)
        bubble.assertIsDisplayed()
        val railBounds = rail().getUnclippedBoundsInRoot()
        val bubbleBounds = bubble.getUnclippedBoundsInRoot()
        // Level with M's slot (the rail has 28 slots), left of the rail.
        val slotMid = railBounds.top.value + (railBounds.bottom.value - railBounds.top.value) / SLOTS * (1 + 12 + 0.5f)
        assertEquals(slotMid, (bubbleBounds.top.value + bubbleBounds.bottom.value) / 2f, 2f)
        assertTrue(bubbleBounds.right.value <= railBounds.left.value)
        rail().performTouchInput { up() }
    }

    @Test
    fun talkBackAdjustsTheLetter() {
        open()
        val config = rail().fetchSemanticsNode().config
        assertEquals("Index", config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull())
        val range = config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, range.current)
        assertEquals(SLOTS - 1f, range.range.endInclusive)
        compose.runOnUiThread { config[SemanticsActions.SetProgress].action!!.invoke(13f) }
        advance(300)
        compose.onNodeWithTag("header-M").assertIsDisplayed()
        val after = rail().fetchSemanticsNode().config
        assertEquals("M", after[SemanticsProperties.StateDescription])
        assertEquals(13f, after[SemanticsProperties.ProgressBarRangeInfo].current)
        // No bubble lingers: nobody is touching the screen.
        compose.onNodeWithTag(AlphabetRailTags.Bubble).assertDoesNotExist()
        assertTrue(haptics.events.any { it == HapticEvent.Tick })
    }

    @Test
    fun theTopSlotScrollsBackToTheStart() {
        open()
        scrubTo('M')
        rail().performTouchInput {
            down(Offset(centerX, slotY(13)))
            moveTo(Offset(centerX, slotY(0)))
            up()
        }
        advance(300)
        compose.onNodeWithTag("header-A").assertIsDisplayed()
    }

    @Test
    fun railEndsAboveTheTabBarAndInsideTheSafeArea() {
        open()
        val railBounds = rail().getUnclippedBoundsInRoot()
        val tabBar = compose.onNodeWithTag("tab-bar").getUnclippedBoundsInRoot()
        assertTrue(
            "rail ${railBounds.bottom} must end above the tab bar ${tabBar.top}",
            railBounds.bottom <= tabBar.top,
        )
        // Below the status bar (28 dp simulated), and not past the right edge.
        assertTrue(railBounds.top.value >= 28f)
        assertTrue(railBounds.right.value <= 411f)
    }

    @Test
    fun theRailIsGoneWhileTheKeyboardIsUp() {
        open(ime = true)
        rail().assertDoesNotExist()
    }

    private companion object {
        /** The arrow slot plus A-Z and the # tail. */
        const val SLOTS = 28f
    }
}
