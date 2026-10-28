package dev.rovsti.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallEventRingTest {
    @Test fun wrapsAndKeepsTheNewest() {
        var t = 0L
        val ring = CallEventRing { t++ }
        for (i in 0 until 120) ring.record("onStateChanged", i, "active", 3, 1, 2)
        assertEquals(CallEventRing.CAPACITY, ring.size)
        val lines = ring.dump()
        assertEquals(CallEventRing.CAPACITY, lines.size)
        assertTrue(lines.first().contains("id=70 "))
        assertTrue(lines.last().contains("id=119 "))
    }

    @Test fun holdsOnlyConstantsAndNumbers() {
        val ring = CallEventRing { 0 }
        ring.record("onCallAdded", 1, "ringing", 5, 1, 0)
        val line = ring.dump().single()
        // The format has fixed fields only: nothing a caller could put a number or name into.
        assertEquals("0 onCallAdded id=1 ringing caps=5 calls=1 ep=0", line)
        assertFalse(line.contains("+90"))
    }

    @Test fun capsDefaultsAreTheSafeOnes() {
        assertEquals("HANDOFF", Caps.REJECT_PATH)
        assertTrue(Caps.BLOCK_GUARD)
        assertFalse(Caps.VOICEMAIL_RECEIVER)
        assertFalse(Caps.LEGACY_AUDIO_FORCED)
        assertFalse(Caps.CONTACT_RINGTONE)
    }
}
