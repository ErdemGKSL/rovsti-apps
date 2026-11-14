package dev.rosty.catalog

import androidx.compose.runtime.SideEffect
import dev.rosty.design.time.LocalRostyClock
import dev.rosty.testing.FixedClock
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** Pins what makes goldens reproducible. Records nothing. */
class DeterminismTest : ShotTest() {
    @Test
    fun localeZoneAndClockArePinned() {
        var seenClock: Long? = null
        composeFrame {
            val now = LocalRostyClock.current.nowMillis()
            SideEffect { seenClock = now }
        }
        assertEquals(Locale.US, Locale.getDefault())
        assertEquals("UTC", TimeZone.getDefault().id)
        assertNotNull(seenClock)
        assertEquals(FixedClock.DEFAULT_MILLIS, seenClock)
        assertEquals(1_768_473_000_000L, FixedClock.DEFAULT_MILLIS)
    }

    @Test
    fun windowIsThePinnedPhone() {
        composeFrame { }
        val metrics = compose.activity.resources.displayMetrics
        // 411 x 891 dp at xxhdpi (3.0x).
        assertEquals(1233, metrics.widthPixels)
        assertEquals(2673, metrics.heightPixels)
    }
}
