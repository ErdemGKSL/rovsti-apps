package dev.rovsti.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiOpsTest {
    @Test
    fun allowedIsGrantedAndIgnoredOrErroredAreDenied() {
        assertEquals(XiaomiOps.GRANTED, XiaomiOps.classify(0))
        assertEquals(XiaomiOps.DENIED, XiaomiOps.classify(1))
        assertEquals(XiaomiOps.DENIED, XiaomiOps.classify(2))
    }

    @Test
    fun anythingElseIsUnknown() {
        assertEquals(XiaomiOps.UNKNOWN, XiaomiOps.classify(null))
        assertEquals(XiaomiOps.UNKNOWN, XiaomiOps.classify(3))
        assertEquals(XiaomiOps.UNKNOWN, XiaomiOps.classify(4))
        assertEquals(XiaomiOps.UNKNOWN, XiaomiOps.classify(-7))
    }

    @Test
    fun keysMapToTheCommunityOpCodes() {
        assertEquals(10008, XiaomiOps.opFor("autostart"))
        assertEquals(10020, XiaomiOps.opFor("lock_screen"))
        assertEquals(10021, XiaomiOps.opFor("popups"))
        assertNull(XiaomiOps.opFor("battery"))
    }
}
