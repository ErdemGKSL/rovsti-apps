package dev.rovsti.phone

import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.RovstiHaptics
import dev.rovsti.phone.keypad.Notice
import dev.rovsti.phone.keypad.isSuccess
import dev.rovsti.phone.recents.RecentsNotice
import dev.rovsti.phone.recents.isSuccess
import dev.rovsti.phone.setup.SetupMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E5.14 (U23): which pills are paired with the confirm haptic, and when the setup-complete moment plays. */
class PillFeedbackTest {
    @Test fun onlyDoneNoticesGetTheConfirmHaptic() {
        assertEquals(
            setOf(Notice.Cleared, Notice.Copied, Notice.CopiedE164),
            Notice.entries.filter { it.isSuccess() }.toSet(),
        )
        assertEquals(
            setOf(RecentsNotice.Copied, RecentsNotice.CopiedWithCountry),
            RecentsNotice.entries.filter { it.isSuccess() }.toSet(),
        )
    }

    @Test fun theSetupMomentPlaysOnlyWhenTheLastRowTurnsDoneWhileOpen() {
        assertTrue(SetupMoment.shouldPlay(previous = false, complete = true))
        assertFalse("already complete when the app opened", SetupMoment.shouldPlay(previous = null, complete = true))
        assertFalse(SetupMoment.shouldPlay(previous = true, complete = true))
        assertFalse(SetupMoment.shouldPlay(previous = false, complete = false))
    }

    @Test fun aCoreRefusalPlaysRejectNeverConfirm() {
        val played = mutableListOf<HapticEvent>()
        val haptics =
            object : RovstiHaptics {
                override fun play(event: HapticEvent) {
                    played += event
                }
            }
        // Recents, PersonSheet, Keypad and Favorites all route their *Effect.Core through this one function.
        repeat(4) { playRefusalHaptic(haptics) }
        assertEquals(List(4) { HapticEvent.Reject }, played)
        assertFalse(played.contains(HapticEvent.Confirm))
    }
}
