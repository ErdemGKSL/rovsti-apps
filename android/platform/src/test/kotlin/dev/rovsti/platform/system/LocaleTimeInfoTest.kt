package dev.rovsti.platform.system

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleTimeInfoTest {
    @Test
    fun encodesOffsetClockAndDateOrder() {
        assertEquals("10800,24,dmy", LocaleTimeInfo.encode(10_800_000, true, charArrayOf('d', 'M', 'y')))
        assertEquals("-18000,12,mdy", LocaleTimeInfo.encode(-18_000_000, false, charArrayOf('M', 'd', 'y')))
        assertEquals("0,24,ymd", LocaleTimeInfo.encode(0, true, charArrayOf('y', 'M', 'd')))
    }

    @Test
    fun anUnknownOrderFallsBackToDayMonthYear() {
        assertEquals("0,24,dmy", LocaleTimeInfo.encode(0, true, charArrayOf()))
        assertEquals("0,24,dmy", LocaleTimeInfo.encode(0, true, charArrayOf('x', 'y', 'z')))
    }
}
