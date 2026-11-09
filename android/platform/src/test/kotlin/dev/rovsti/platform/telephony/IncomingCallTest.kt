package dev.rovsti.platform.telephony

import dev.rovsti.platform.wire.CallInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallTest {
    private fun call(
        id: Int,
        state: String,
        name: String? = null,
        raw: String = "+905551112233",
    ) = CallInfo(id = id, state = state, numberRaw = raw, displayName = name)

    @Test
    fun onlyARingingCallIsIncoming() {
        assertNull(IncomingCall.of(emptyList()))
        assertNull(IncomingCall.of(listOf(call(1, "active"), call(2, "dialing"))))
        assertEquals(2, IncomingCall.of(listOf(call(1, "active"), call(2, "ringing")))?.callId)
    }

    @Test
    fun aContactShowsItsNameWithTheNumberUnder() {
        val c = IncomingCall.of(listOf(call(1, "ringing", name = " Ada ")))!!
        assertEquals("Ada", c.title)
        assertEquals("+905551112233", c.subtitle)
    }

    @Test
    fun anUnknownCallerShowsTheNumberAndNoSubtitle() {
        val c = IncomingCall.of(listOf(call(1, "ringing")))!!
        assertEquals("+905551112233", c.title)
        assertNull(c.subtitle)
        assertEquals("Unknown", IncomingCall.of(listOf(call(1, "ringing", raw = "")))?.title)
    }

    @Test
    fun theKeyChangesWhenTheNameResolves() {
        val before = IncomingCall.of(listOf(call(1, "ringing")))!!
        val after = IncomingCall.of(listOf(call(1, "ringing", name = "Ada")))!!
        assertFalse(before.key == after.key)
    }

    @Test
    fun answerIsHonouredExactlyOnce() {
        val once = AnswerOnce()
        assertFalse(once.accept(-1, true))
        assertFalse("stale id: the call is not ringing", once.accept(4, false))
        assertTrue(once.accept(4, true))
        assertFalse("redelivered intent", once.accept(4, true))
        assertTrue(once.accept(5, true))
    }
}
