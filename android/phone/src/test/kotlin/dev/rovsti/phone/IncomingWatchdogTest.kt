package dev.rovsti.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The error boundary of the Incoming screen: no first frame in time means the native screen. */
class IncomingWatchdogTest {
    private class Clock {
        var now = 0L
        val pending = mutableListOf<Pair<Long, Runnable>>()

        fun post(
            r: Runnable,
            delay: Long,
        ) {
            pending += (now + delay) to r
        }

        fun cancel(r: Runnable) {
            pending.removeAll { it.second === r }
        }

        fun advance(ms: Long) {
            now += ms
            val due = pending.filter { it.first <= now }
            pending.removeAll(due)
            due.forEach { it.second.run() }
        }
    }

    private val clock = Clock()
    private val stuck = mutableListOf<Int>()
    private val dog = IncomingWatchdog(clock::post, clock::cancel, { stuck += it })

    @Test fun aScreenThatNeverDrawsShowsTheNativeOne() {
        dog.arm(4)
        clock.advance(IncomingWatchdog.TIMEOUT_MS - 1)
        assertTrue(stuck.isEmpty())
        clock.advance(1)
        assertEquals(listOf(4), stuck)
        assertFalse(dog.isArmed)
    }

    @Test fun aFirstFrameInTimeDisarms() {
        dog.arm(4)
        clock.advance(800)
        dog.frame(4)
        clock.advance(10_000)
        assertTrue(stuck.isEmpty())
    }

    @Test fun aFrameOfAnotherCallDoesNotDisarm() {
        dog.arm(4)
        dog.frame(9)
        clock.advance(IncomingWatchdog.TIMEOUT_MS)
        assertEquals(listOf(4), stuck)
    }

    @Test fun aCallThatAlreadyDrewIsNeverWatchedAgain() {
        dog.frame(4)
        dog.arm(4)
        assertFalse(dog.isArmed)
        clock.advance(10_000)
        assertTrue(stuck.isEmpty())
    }

    @Test fun armingTwiceKeepsOneTimer() {
        dog.arm(4)
        clock.advance(1_000)
        dog.arm(4)
        clock.advance(IncomingWatchdog.TIMEOUT_MS - 1_000)
        assertEquals("the first timer still counts", listOf(4), stuck)
    }

    @Test fun theCallEndingDisarms() {
        dog.arm(4)
        dog.disarm()
        clock.advance(10_000)
        assertTrue(stuck.isEmpty())
    }
}
