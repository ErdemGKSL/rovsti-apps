package dev.rosty.messages.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a drag of the held microphone means: toward the start edge cancels, up locks. */
class MicGestureTest {
    private val cancel = 300f
    private val lock = 200f

    private fun of(
        dx: Float,
        dy: Float,
        rtl: Boolean = false,
    ) = micGesture(dx, dy, cancel, lock, rtl)

    @Test fun shortMovesMeanNothing() {
        assertEquals(MicGesture.None, of(0f, 0f))
        assertEquals(MicGesture.None, of(-299f, 0f))
        assertEquals(MicGesture.None, of(0f, -199f))
        // Toward the end edge or down never cancels or locks.
        assertEquals(MicGesture.None, of(900f, 900f))
    }

    @Test fun towardTheStartEdgeCancels() {
        assertEquals(MicGesture.Cancel, of(-300f, 0f))
        assertEquals(MicGesture.Cancel, of(-400f, -50f))
        // Right to left: the start edge is on the right.
        assertEquals(MicGesture.Cancel, of(300f, 0f, rtl = true))
        assertEquals(MicGesture.None, of(-300f, 0f, rtl = true))
    }

    @Test fun upLocks() {
        assertEquals(MicGesture.Lock, of(0f, -200f))
        assertEquals(MicGesture.Lock, of(-100f, -250f))
        assertEquals(MicGesture.Lock, of(0f, -200f, rtl = true))
    }

    @Test fun pastBothLinesTheFurtherMoveWins() {
        // 1.5 of the cancel line against 1.25 of the lock line.
        assertEquals(MicGesture.Cancel, of(-450f, -250f))
        assertEquals(MicGesture.Lock, of(-310f, -400f))
    }
}
