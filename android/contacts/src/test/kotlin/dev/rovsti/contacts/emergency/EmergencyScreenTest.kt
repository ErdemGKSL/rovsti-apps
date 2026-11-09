package dev.rovsti.contacts.emergency

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.14 Emergency Contacts on the real shell; the clock is manual. */
@Config(sdk = [35])
class EmergencyScreenTest : ShotTest() {
    private val haptics = RecordingHaptics()

    private fun show(h: EmergencyHarness) {
        composeFrame { HostedEmergency(h, haptics) }
        advance(700)
    }

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .getUnclippedBoundsInRoot()
            .top.value

    @Test fun everyRowHasACallButtonThatAsksToCallThatPerson() {
        val h = EmergencyHarness(EmergencyUi.state())
        show(h)
        for (p in EmergencyUi.people) {
            compose
                .onNodeWithTag(
                    EmergencyTags.call(p.id),
                    useUnmergedTree = true,
                ).assertIsDisplayed()
        }
        compose.onNodeWithTag(EmergencyTags.call(1), useUnmergedTree = true).performClick()
        assertEquals(listOf<EmergencyAction>(EmergencyAction.Call(1)), h.actions)
    }

    @Test fun tappingARowOpensItsCardWhenThereIsNoMenu() {
        val h = EmergencyHarness(EmergencyUi.state())
        composeFrame { HostedEmergency(h) }
        advance(700)
        compose.onNodeWithTag(EmergencyTags.row(3)).performClick()
        advance(300)
        assertTrue(h.actions.any { it == EmergencyAction.Open(3) })
    }

    @Test fun anEmptyListIsAnEmptyStateThatOffersToAdd() {
        val h = EmergencyHarness(EmergencyUi.state(people = emptyList()))
        show(h)
        compose.onNodeWithTag(EmergencyTags.Empty).assertIsDisplayed()
        compose.onNodeWithText("No Emergency Contacts").assertIsDisplayed()
        compose.onNodeWithText("Add Contact").assertIsDisplayed()
        compose.onNodeWithTag(EmergencyTags.Footer).assertDoesNotExist()
    }

    @Test fun editModeSwapsTheCallButtonsForDragHandles() {
        val h = EmergencyHarness(EmergencyUi.state(editing = true))
        show(h)
        for (p in EmergencyUi.people) {
            compose.onNodeWithTag(EmergencyTags.handle(p.id), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag(EmergencyTags.call(p.id), useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag(EmergencyTags.Share).assertDoesNotExist()
    }

    @Test fun dragRowOneBelowRowThreeChangesTheOrderOnceAndTicks() {
        val h = EmergencyHarness(EmergencyUi.state(editing = true))
        show(h)
        val ids = EmergencyUi.people.map { it.id }
        val pitch = top(EmergencyTags.row(1)) - top(EmergencyTags.row(3))
        val density = compose.density.density

        fun handle() = compose.onNodeWithTag(EmergencyTags.handle(3), useUnmergedTree = true)
        handle().performTouchInput { down(center) }
        advance(100)
        val step = pitch * density * 1.3f / 12f
        repeat(12) {
            handle().performTouchInput { moveBy(Offset(0f, step)) }
            advance(120)
        }
        advance(300)
        handle().performTouchInput { up() }
        repeat(6) { advance(300) }
        assertEquals(
            listOf<EmergencyAction>(EmergencyAction.Move(0, 1)),
            h.actions.filterIsInstance<EmergencyAction.Move>(),
        )
        assertEquals(listOf(1L, 3L, 8L), ids.sortedBy { top(EmergencyTags.row(it)) })
        assertEquals("the lift ticked once", 1, haptics.events.count { it == HapticEvent.Tick })
    }

    @Test fun aLongPressOpensCallMoveAndRemove() {
        val h = EmergencyHarness(EmergencyUi.state())
        show(h)
        compose.onNodeWithTag(EmergencyTags.row(1)).performTouchInput { longClick(durationMillis = 700) }
        advance(900)
        compose.onNodeWithText("Move Earlier").assertIsDisplayed()
        compose.onNodeWithText("Move Later").assertIsDisplayed()
        compose.onNodeWithText("Remove from Emergency Contacts").assertIsDisplayed()
        compose.onNodeWithText("Remove from Emergency Contacts").performClick()
        advance(400)
        assertTrue(h.actions.contains(EmergencyAction.Remove(1)))
    }

    @Test fun theFooterAndShareSitUnderTheRowsAndShareAsks() {
        val h = EmergencyHarness(EmergencyUi.state())
        show(h)
        compose.onNodeWithTag(EmergencyTags.Footer).assertIsDisplayed()
        assertTrue(top(EmergencyTags.Share) > top(EmergencyTags.Footer))
        compose.onNodeWithTag(EmergencyTags.Share).performClick()
        assertEquals(listOf<EmergencyAction>(EmergencyAction.Share), h.actions)
    }

    @Test fun theCallButtonNeverOverlapsTheNameAtDoubleFontSize() {
        val h = EmergencyHarness(EmergencyUi.state())
        composeFrame(fontScale = 2f) { HostedEmergency(h) }
        advance(700)
        for (p in EmergencyUi.people) {
            val row = compose.onNodeWithTag(EmergencyTags.row(p.id)).getUnclippedBoundsInRoot()
            val call =
                compose
                    .onNodeWithTag(
                        EmergencyTags.call(p.id),
                        useUnmergedTree = true,
                    ).getUnclippedBoundsInRoot()
            assertTrue("call inside its row $p", call.right <= row.right && call.left >= row.left)
        }
    }
}
