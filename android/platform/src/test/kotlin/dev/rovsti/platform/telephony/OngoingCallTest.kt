package dev.rovsti.platform.telephony

import dev.rovsti.platform.wire.CallInfo
import dev.rovsti.platform.wire.Focus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OngoingCallTest {
    private fun call(
        id: Int,
        state: String,
        name: String? = null,
        raw: String = "+905551112233",
        connect: Long = 0,
    ) = CallInfo(id = id, state = state, numberRaw = raw, displayName = name, connectTimeMs = connect)

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
    fun anUnnamedCallReadsFormattedAndANamedOneCarriesItsContact() {
        // F39: an outgoing call to a stranger shows "+90 555 111 22 33", not the digits typed.
        val calls = listOf(call(1, "dialing"))
        val stranger = OngoingCall.of(calls, Focus.of(calls)) { "+90 555 111 22 33" }!!
        assertEquals("+90 555 111 22 33", stranger.title)
        assertNull(stranger.contactId)
        val named = listOf(call(1, "active", name = "Ada").copy(contactId = 3))
        assertEquals(3L, OngoingCall.of(named, Focus.of(named))?.contactId)
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

    @Test
    fun theNotificationFollowsTheForegroundAndCountsLiveCalls() {
        val n = of(call(1, "holding", name = "Ada"), call(2, "active", name = "Bob"))!!
        assertEquals(2, n.callCount)
        assertEquals("Bob", n.title)
        assertFalse(n.key == of(call(2, "active", name = "Bob"))!!.key)
    }

    @Test
    fun aConferenceCountsAsOneCall() {
        val n =
            of(
                CallInfo(id = 1, state = "active", parentId = 3),
                CallInfo(id = 2, state = "active", parentId = 3),
                CallInfo(id = 3, state = "active", children = listOf(1, 2), props = 1),
            )!!
        assertEquals(1, n.callCount)
        assertEquals(3, n.callId)
    }

    @Test
    fun aWaitingCallDoesNotChangeTheCount() {
        assertEquals(1, of(call(1, "active"), call(2, "ringing"))!!.callCount)
    }
}
