package dev.rosty.messages.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadReturnTest {
    @Test
    fun aShortAbsenceKeepsThePlace() {
        assertFalse(returnsToNewest(leftAtMs = 1_000L, nowMs = 1_000L + THREAD_STALE_MS - 1))
    }

    @Test
    fun fifteenMinutesAwayGoesToTheNewest() {
        assertTrue(returnsToNewest(leftAtMs = 1_000L, nowMs = 1_000L + THREAD_STALE_MS))
        assertTrue(returnsToNewest(leftAtMs = 1_000L, nowMs = 1_000L + 3 * THREAD_STALE_MS))
    }

    @Test
    fun neverLeftOrClockBack() {
        assertFalse(returnsToNewest(leftAtMs = NOT_LEFT, nowMs = 10 * THREAD_STALE_MS))
        assertTrue(returnsToNewest(leftAtMs = 5_000L, nowMs = 1_000L))
    }
}
