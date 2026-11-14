package dev.rosty.platform.location

import dev.rosty.design.time.RostyClock
import dev.rosty.design.time.SystemRostyClock

/** The trip service's and the alarm's "now": the shared [RostyClock] seam, replaceable in tests. */
object TripClock {
    @Volatile
    var clock: RostyClock = SystemRostyClock

    fun nowMillis(): Long = clock.nowMillis()
}
