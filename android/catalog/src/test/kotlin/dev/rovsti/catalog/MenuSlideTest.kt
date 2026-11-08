package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.design.overlay.menuTrigger
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.10 part 2: one finger presses the trigger, slides over the rows and lifts on one; the keyboard path. */
@OptIn(ExperimentalTestApi::class)
class MenuSlideTest : ShotTest() {
    private val haptics = RecordingHaptics()
    private val log = mutableListOf<String>()

    private val items =
        persistentListOf<MenuItem>(
            MenuItem.Action("One", onClick = { log += "One" }),
            MenuItem.Action("Two", onClick = { log += "Two" }),
            MenuItem.Action("Three", onClick = { log += "Three" }),
            MenuItem.Action("Delete", destructive = true, onClick = { log += "Delete" }),
        )

    private fun show(menu: kotlinx.collections.immutable.ImmutableList<MenuItem> = items) {
        composeFrame {
            CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
                val controller = remember { OverlayController() }
                OverlayHost(controller) {
                    Box(Modifier.fillMaxSize().background(RovstiTheme.colors.page)) {
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 24.dp, top = 120.dp)
                                .size(44.dp)
                                .menuTrigger(controller, menu)
                                .testTag("trigger"),
                        )
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** The page is cleared from the merged tree while a modal menu is open, so the trigger is found unmerged. */
    private fun trigger() = compose.onNodeWithTag("trigger", useUnmergedTree = true)

    private fun cardExists() = compose.onAllNodesWithTagCount("menu-card") > 0

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(
            androidx.compose.ui.test
                .hasTestTag(tag),
        ).fetchSemanticsNodes().size

    /** The finger goes down on the trigger and the menu settles under it. */
    private fun pressTrigger() {
        trigger().performTouchInput { down(center) }
        advance(16)
        advance(16)
        advance(1200)
    }

    /** Moves the held finger to the centre of [tag]. */
    private fun slideTo(tag: String) {
        val target = bounds(tag).center
        val origin = bounds("trigger").topLeft
        trigger().performTouchInput { moveTo(target - origin) }
        advance(40)
    }

    /** Time for an exit: with the manual clock a dismissal needs a small advance before a large one. */
    private fun closed() {
        advance(16)
        advance(1500)
    }

    private fun lift() {
        trigger().performTouchInput { up() }
        advance(40)
        advance(1500)
    }

    @Test
    fun slidingToTheThirdRowAndLiftingRunsItOnceAndCloses() {
        show()
        pressTrigger()
        compose.onNodeWithTag("menu-card").assertIsDisplayed()
        slideTo("menu-row-2")
        lift()
        assertEquals(listOf("Three"), log)
        assertTrue("the menu closed", !cardExists())
    }

    @Test
    fun eachNewRowUnderTheFingerGivesOneTick() {
        show()
        pressTrigger()
        assertTrue("nothing yet", haptics.events.isEmpty())
        slideTo("menu-row-0")
        slideTo("menu-row-1")
        slideTo("menu-row-2")
        assertEquals(List(3) { HapticEvent.TextHandle }, haptics.events)
        slideTo("menu-row-2")
        assertEquals("staying on a row is silent", 3, haptics.events.size)
        lift()
        assertEquals(listOf("Three"), log)
    }

    @Test
    fun liftingOutsideTheCardCommitsNothing() {
        show()
        pressTrigger()
        slideTo("menu-row-1")
        trigger().performTouchInput { moveTo(Offset(-300f, 40f)) }
        advance(40)
        trigger().performTouchInput { up() }
        advance(1500)
        assertTrue(log.isEmpty())
        assertTrue("the menu stays open", cardExists())
    }

    @Test
    fun aPlainTapOnTheTriggerOpensTheMenuAndCommitsNothing() {
        show()
        pressTrigger()
        lift()
        assertTrue(log.isEmpty())
        assertTrue("the menu stays open", cardExists())
        assertTrue(haptics.events.isEmpty())
    }

    @Test
    fun aDestructiveRowIsConfirmedWithAHaptic() {
        show()
        pressTrigger()
        slideTo("menu-row-3")
        lift()
        assertEquals(listOf("Delete"), log)
        assertEquals(listOf(HapticEvent.TextHandle, HapticEvent.Confirm), haptics.events)
    }

    @Test
    fun tappingARowOnTheOpenMenuRunsIt() {
        show()
        pressTrigger()
        lift()
        compose.onNodeWithTag("menu-row-1").performClick()
        advance(1500)
        assertEquals(listOf("Two"), log)
        assertTrue(!cardExists())
    }

    @Test
    fun arrowsMoveWithWrapAndEnterCommits() {
        val menu =
            persistentListOf<MenuItem>(
                MenuItem.Action("One", onClick = { log += "One" }),
                MenuItem.Action("Off", enabled = false, onClick = { log += "Off" }),
                MenuItem.Separator,
                MenuItem.Action("Three", onClick = { log += "Three" }),
            )
        show(menu)
        pressTrigger()
        lift()
        val card = compose.onNodeWithTag("menu-card")
        card.performKeyInput { pressKey(Key.DirectionDown) }
        card.performKeyInput { pressKey(Key.DirectionDown) } // wraps back to One, skipping Off
        card.performKeyInput { pressKey(Key.DirectionUp) } // wraps to Three
        advance(200)
        assertTrue("the keyboard path is silent", haptics.events.isEmpty())
        card.performKeyInput { pressKey(Key.Enter) }
        closed()
        assertEquals(listOf("Three"), log)
        assertTrue(!cardExists())
    }

    @Test
    fun enterWithoutAnArrowChoosesTheFirstEnabledRow() {
        show()
        pressTrigger()
        lift()
        compose.onNodeWithTag("menu-card").performKeyInput { pressKey(Key.Enter) }
        advance(1500)
        assertEquals(listOf("One"), log)
    }

    @Test
    fun escapeClosesWithoutRunningAnything() {
        show()
        pressTrigger()
        lift()
        compose.onNodeWithTag("menu-card").performKeyInput { pressKey(Key.Escape) }
        closed()
        assertTrue(log.isEmpty())
        assertTrue(!cardExists())
    }

    @Test
    fun rowsAreButtonsCheckRowsAreCheckboxesAndEveryRowIsTouchSized() {
        val menu =
            persistentListOf<MenuItem>(
                MenuItem.Action("Plain", onClick = {}),
                MenuItem.Action("Sorted", checked = true, onClick = {}),
                MenuItem.Action("Unsorted", checked = false, onClick = {}),
                MenuItem.Action("Off", enabled = false, onClick = {}),
            )
        show(menu)
        pressTrigger()
        lift()
        val role = { r: Role -> SemanticsMatcher.expectValue(SemanticsProperties.Role, r) }
        compose.onNodeWithTag("menu-row-0").assert(role(Role.Button))
        compose.onNodeWithTag("menu-row-1").assert(role(Role.Checkbox)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        )
        compose.onNodeWithTag("menu-row-2").assert(role(Role.Checkbox)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, false),
        )
        compose.onNodeWithTag("menu-row-3").assertIsNotEnabled()
        val density = compose.activity.resources.displayMetrics.density
        for (i in 0..3) {
            assertTrue("row $i >= 48 dp", bounds("menu-row-$i").height >= 48f * density - 1f)
        }
    }

    @Test
    fun theSemanticsClickRunsTheRowForScreenReaders() {
        show()
        pressTrigger()
        lift()
        compose.onNodeWithTag("menu-row-2").performSemanticsAction(SemanticsActions.OnClick)
        advance(1500)
        assertEquals(listOf("Three"), log)
    }
}
