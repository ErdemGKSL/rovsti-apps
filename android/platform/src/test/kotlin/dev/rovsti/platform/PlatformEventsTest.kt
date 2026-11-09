package dev.rovsti.platform

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlatformEventsTest {
    @Test fun emitReachesACollectorWithTheSameKindAndJson() =
        runTest(UnconfinedTestDispatcher()) {
            val got = mutableListOf<PlatformEvent>()
            val job = launch { PlatformEvents.events.toList(got) }
            assertTrue(PlatformEvents.emit("call_log_changed", """{"v":1}"""))
            job.cancel()
            assertEquals(listOf(PlatformEvent("call_log_changed", """{"v":1}""")), got)
        }

    @Test fun emitWithNoCollectorNeverSuspendsOrFails() {
        repeat(PlatformEvents.BUFFER * 3) { assertTrue(PlatformEvents.emit("k$it", "{}")) }
    }

    @Test fun uiAttachedIsAFlag() {
        assertFalse(PlatformEvents.uiAttached)
        PlatformEvents.uiAttached = true
        assertTrue(PlatformEvents.uiAttached)
        PlatformEvents.uiAttached = false
    }
}
