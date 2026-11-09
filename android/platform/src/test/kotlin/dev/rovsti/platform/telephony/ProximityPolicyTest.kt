package dev.rovsti.platform.telephony

import dev.rovsti.platform.wire.CallInfo
import dev.rovsti.platform.wire.Focus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityPolicyTest {
    private fun call(
        id: Int,
        state: String,
    ) = CallInfo(id = id, state = state, numberRaw = "5551112233")

    private fun wanted(
        route: String,
        vararg calls: CallInfo,
    ): Boolean {
        val list = calls.toList()
        return ProximityPolicy.wanted(list, Focus.of(list), route)
    }

    @Test
    fun anActiveOrDialingCallOnTheEarpieceBlanksTheScreen() {
        assertTrue(wanted("earpiece", call(1, "active")))
        assertTrue(wanted("earpiece", call(1, "dialing")))
    }

    @Test
    fun otherRoutesKeepTheScreenOn() {
        assertFalse(wanted("speaker", call(1, "active")))
        assertFalse(wanted("bluetooth", call(1, "active")))
        assertFalse(wanted("wired_headset", call(1, "dialing")))
    }

    @Test
    fun holdRingingAndEndedCallsKeepTheScreenOn() {
        assertFalse(wanted("earpiece", call(1, "holding")))
        assertFalse(wanted("earpiece", call(1, "ringing")))
        assertFalse(wanted("earpiece", call(1, "disconnected")))
        assertFalse(wanted("earpiece"))
    }

    @Test
    fun aCallIsInProgressUntilItIsDisconnected() {
        assertTrue(ProximityPolicy.inProgress(listOf(call(1, "holding"))))
        assertFalse(ProximityPolicy.inProgress(listOf(call(1, "disconnected"))))
        assertFalse(ProximityPolicy.inProgress(emptyList()))
    }
}
