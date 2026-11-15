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
