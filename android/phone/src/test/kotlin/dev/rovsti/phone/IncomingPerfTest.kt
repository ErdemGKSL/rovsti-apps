package dev.rovsti.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ring-latency log line the owner reads from logcat (`adb logcat -s RovstiPerf`), story 5.6. */
class IncomingPerfTest {
    @Test fun aColdLockedCallPrintsEveryStageInOrder() {
        val t = IncomingTimeline(callId = 3, locked = true, processCold = true, startMs = 1_000)
        assertTrue(t.mark("notify", 1_009))
        assertTrue(t.mark(IncomingTimeline.ACTIVITY_CREATED, 1_300))
        assertTrue(t.mark(IncomingTimeline.ACTIVITY_RESUMED, 1_450))
        assertTrue(t.mark(IncomingTimeline.FIRST_FRAME, 1_812))
        assertEquals(
            "incoming call=3 locked=true process=cold launch=cold notify_ms=9 activity_created_ms=300 " +
                "activity_resumed_ms=450 first_frame_ms=812",
            t.summary(),
        )
        assertEquals(
            listOf("notify 9 ms", "activity_created 300 ms", "activity_resumed 450 ms", "first_frame 812 ms"),
            t.lines(),
        )
    }

    @Test fun aStageCountsOnlyTheFirstTime() {
        val t = IncomingTimeline(1, locked = false, processCold = false, startMs = 0)
        assertTrue(t.mark(IncomingTimeline.FIRST_FRAME, 100))
        assertFalse(t.mark(IncomingTimeline.FIRST_FRAME, 900))
        assertEquals("incoming call=1 locked=false process=warm launch=warm first_frame_ms=100", t.summary())
    }

    @Test fun noActivityStageMeansAWarmLaunch() {
        val t = IncomingTimeline(2, locked = false, processCold = true, startMs = 5)
        t.mark("notify", 10)
        assertTrue("process=cold launch=warm" in t.summary())
    }
}
