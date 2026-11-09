package dev.rovsti.platform.location

import dev.rovsti.core.model.headless.TripState
import dev.rovsti.platform.PlatformEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TripStatusTest {
    @Test fun theServiceStatesParse() {
        assertEquals(TripStatus.Searching, TripStatus.parse("""{"state":"searching"}"""))
        assertEquals(TripStatus.Stopped, TripStatus.parse("""{"state":"stopped"}"""))
        assertEquals(TripStatus.Ended, TripStatus.parse("""{"state":"ended"}"""))
        assertEquals(TripStatus.Error("restart"), TripStatus.parse(tripRestartErrorJson()))
        assertEquals(TripStatus.Error("full_screen"), TripStatus.parse(fullScreenErrorJson()))
        assertTrue(TripStatus.parse("not json") is TripStatus.Other)
        assertTrue(TripStatus.parse("""{"nostate":1}""") is TripStatus.Other)
    }

    @Test fun rustsFixAnswerParsesIntoTheSharedModel() {
        val raw = File(System.getProperty("rovsti.ffi.fixtures"), "headless.trip_fix.answer.json").readText()
        val status = TripStatus.parse(raw) as TripStatus.Fixed
        assertEquals(TripState.Tracking, status.answer.state)
        assertEquals(65_570L, status.answer.nextFixMs)
    }

    @Test fun theRepositoryStatusFlowCarriesTheEmittedStatuses() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<TripStatus>())
        val job =
            CoroutineScope(Dispatchers.Unconfined).launch {
                AndroidTripRepository(RuntimeEnvironment.getApplication()).status.collect {
                    seen +=
                        it
                }
            }
        PlatformEvents.emit("trip_status", """{"state":"searching"}""")
        PlatformEvents.emit("call_log_changed", "{}")
        PlatformEvents.emit("trip_status", """{"state":"stopped"}""")
        job.cancel()
        assertEquals(listOf(TripStatus.Searching, TripStatus.Stopped), seen.toList())
    }
}
