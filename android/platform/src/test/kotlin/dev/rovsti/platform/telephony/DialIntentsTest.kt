package dev.rovsti.platform.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialIntentsTest {
    @Test
    fun dialWithDataIsForwardedUnchanged() {
        assertEquals("tel:+905551112233", DialIntents.forwardedUri("android.intent.action.DIAL", "tel:+905551112233"))
        assertEquals("tel:*21%23", DialIntents.forwardedUri("android.intent.action.DIAL", "tel:*21%23"))
    }

    @Test
    fun dialWithoutDataOpensTheEmptyKeypad() {
        assertEquals("tel:", DialIntents.forwardedUri("android.intent.action.DIAL", null))
        assertEquals("tel:", DialIntents.forwardedUri("android.intent.action.DIAL", "  "))
    }

    @Test
    fun otherActionsAreLeftAlone() {
        assertNull(DialIntents.forwardedUri("android.intent.action.VIEW", "tel:1"))
        assertNull(DialIntents.forwardedUri("android.intent.action.MAIN", null))
        assertNull(DialIntents.forwardedUri(null, null))
    }
}
