package dev.rovsti.phone

import dev.rovsti.bridge.CallInfo
import dev.rovsti.bridge.Focus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OngoingCallTest {
    private fun call(id: Int, state: String, name: String? = null, raw: String = "+905551112233", connect: Long = 0) =
        CallInfo(id = id, state = state, numberRaw = raw, displayName = name, connectTimeMs = connect)

    private fun of(vararg calls: CallInfo) = OngoingCall.of(calls.toList(), Focus.of(calls.toList()))

    @Test
    fun noCallsMeansNoNotification() {
        assertNull(of())
    }

    @Test
    fun ringingAndDisconnectedCallsDoNotKeepTheService() {
        assertNull(of(call(1, "ringing")))
        assertNull(of(call(1, "disconnected")))
    }

    @Test
    fun contactNameBeatsTheNumber() {
        assertEquals("Ada", of(call(1, "dialing", name = "Ada"))?.title)
        assertEquals("+905551112233", of(call(1, "dialing"))?.title)
        assertEquals("Unknown", of(call(1, "dialing", raw = ""))?.title)
    }

    @Test
    fun chronometerStartsWhenTheCallConnects() {
        assertEquals(0L, of(call(1, "dialing"))?.connectTimeMs)
        assertEquals(1234L, of(call(1, "active", connect = 1234))?.connectTimeMs)
    }

    @Test
    fun holdingIsFlagged() {
        assertTrue(of(call(1, "holding"))!!.holding)
        assertFalse(of(call(1, "active"))!!.holding)
    }

    @Test
    fun keyChangesOnlyWhenWhatIsShownChanges() {
        val a = of(call(1, "dialing", name = "Ada"))!!
        val b = of(call(1, "dialing", name = "Ada"))!!
        val connected = of(call(1, "active", name = "Ada", connect = 99))!!
        assertEquals(a.key, b.key)
        assertFalse(a.key == connected.key)
    }
}
