package dev.rosty.platform.location

import dev.rosty.core.model.headless.TripState
import dev.rosty.platform.PlatformEvents
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
        val raw = File(System.getProperty("rosty.ffi.fixtures"), "headless.trip_fix.answer.json").readText()
        val status = TripStatus.parse(raw) as TripStatus.Fixed
        assertEquals(TripState.Tracking, status.answer.state)
        // The cadence's ladder: 60 s, where the old curve asked every 65.6 s; a first fix assumes the top speed.
        assertEquals(60_000L, status.answer.nextFixMs)
        assertEquals("cold", status.answer.cadence)
        assertEquals(60.0, status.answer.assumedMps!!, 0.0)
        assertEquals(10.0, status.answer.speedMps!!, 0.0)
        assertEquals(false, status.answer.late)
    }

    @Test fun anAnswerFromBeforeTheCadenceStillParses() {
        val raw = File(System.getProperty("rosty.ffi.fixtures"), "headless.trip_fix.answer.json").readText()
        val old =
            org.json.JSONObject(raw).apply {
                remove("speed_mps")
                remove("assumed_mps")
                remove("cadence")
                remove("late")
            }
        val status = TripStatus.parse(old.toString()) as TripStatus.Fixed
        assertEquals(null, status.answer.cadence)
        assertEquals(false, status.answer.late)
        val parsed = checkNotNull(TripAnswer.parse(old.toString()))
        assertEquals(null, parsed.assumedMps)
        assertEquals(null, parsed.cadence)
    }

    @Test fun aNoFixAnswerParsesAsAFixWithoutPosition() {
        val raw = File(System.getProperty("rosty.ffi.fixtures"), "headless.trip_no_fix.answer.json").readText()
        val answer = checkNotNull(TripAnswer.parse(raw))
        assertTrue(answer.late)
        assertEquals("cold", answer.cadence)
        val status = TripStatus.parse(raw) as TripStatus.Fixed
        assertEquals(null, status.answer.at)
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
