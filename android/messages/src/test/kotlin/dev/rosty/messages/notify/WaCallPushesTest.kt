package dev.rosty.messages.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaCallPushesTest {
    @Test
    fun callPushesGoToPhoneWhenItIsInstalled() {
        assertTrue(WaCallPushes.leftToPhone("call", phoneInstalled = true))
        assertTrue(WaCallPushes.leftToPhone("call_end", phoneInstalled = true))
    }

    @Test
    fun messagesRingsWhenPhoneIsAbsent() {
        assertFalse(WaCallPushes.leftToPhone("call", phoneInstalled = false))
        assertFalse(WaCallPushes.leftToPhone("call_end", phoneInstalled = false))
    }

    @Test
    fun messagePushesStayInMessages() {
        assertFalse(WaCallPushes.leftToPhone("message", phoneInstalled = true))
        assertFalse(WaCallPushes.leftToPhone(null, phoneInstalled = true))
    }
}
