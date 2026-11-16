package dev.rosty.platform.location

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The `trip_fix` request the service sends for a fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TripFixJsonTest {
    private fun json(
        speed: Double? = 8.0,
        locale: String? = null,
    ) = JSONObject(tripFixJson("/data/stops", 41.0, 29.0, 12.0, speed, 5_000L, locale))

    @Test fun theFixAsRustReadsIt() {
        val o = json()
        assertEquals("/data/stops", o.getString("dir"))
        assertEquals(41.0, o.getDouble("lat"), 0.0)
        assertEquals(29.0, o.getDouble("lon"), 0.0)
        assertEquals(12.0, o.getDouble("accuracy_m"), 0.0)
        assertEquals(8.0, o.getDouble("speed_mps"), 0.0)
        assertEquals(5_000L, o.getLong("at_ms"))
    }

    @Test fun theMonotonicClockGoesWithTheFixWhenThereIsOne() {
        val o = JSONObject(tripFixJson("/d", 41.0, 29.0, 12.0, null, 5_000L, elapsedMs = 9_000L))
        assertEquals(9_000L, o.getLong("elapsed_ms"))
        assertFalse(json().has("elapsed_ms"))
        assertFalse(JSONObject(tripFixJson("/d", 41.0, 29.0, 12.0, null, 5_000L, elapsedMs = 0L)).has("elapsed_ms"))
    }

    @Test fun theNoFixRequestAsRustReadsIt() {
        val o = JSONObject(tripNoFixJson("/data/stops", 7_000L, 3_000L))
        assertEquals("/data/stops", o.getString("dir"))
        assertEquals(7_000L, o.getLong("now_ms"))
        assertEquals(3_000L, o.getLong("elapsed_ms"))
    }

    @Test fun theLogLineReadsTheCadence() {
        val answer =
            TripAnswer(
                "Tracking",
                15_000L,
                false,
                false,
                "t",
                "x",
                20_000L,
                distanceM = 702.4,
                speedMps = 1.31,
                assumedMps = 11.24,
                cadence = "bound",
            )
        assertEquals(
            "fix t=12:04:31 d=702m acc=9 v=1.3 assumed=11.2 next=15s high=false basis=bound late=false",
            tripFixLog(answer, "12:04:31", 9.2),
        )
        val missing =
            answer.copy(
                speedMps = null,
                assumedMps = 60.0,
                cadence = "cold",
                late = true,
                highAccuracy = true,
            )
        assertEquals(
            "nofix t=12:05:00 d=702m acc=- v=- assumed=60.0 next=15s high=true basis=cold late=true",
            tripFixLog(missing, "12:05:00", null, fix = false),
        )
    }

    @Test fun aSpeedThePhoneDidNotMeasureIsLeftOut() {
        assertFalse(json(speed = null).has("speed_mps"))
        assertFalse(json(speed = -1.0).has("speed_mps"))
        assertFalse(json(speed = Double.NaN).has("speed_mps"))
    }

    @Test fun thePhoneLanguageGoesWithTheFixWhenThereIsOne() {
        assertEquals("tr-TR", json(locale = "tr-TR").getString("locale"))
        // Without one the trip keeps the language it started in.
        assertFalse(json().has("locale"))
        assertFalse(json(locale = " ").has("locale"))
    }
}
