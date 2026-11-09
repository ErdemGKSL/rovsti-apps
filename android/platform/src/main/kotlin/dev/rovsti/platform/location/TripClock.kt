package dev.rovsti.platform.location

import dev.rovsti.design.time.RovstiClock
import dev.rovsti.design.time.SystemRovstiClock

/** The trip service's and the alarm's "now": the shared [RovstiClock] seam, replaceable in tests. */
object TripClock {
    @Volatile
    var clock: RovstiClock = SystemRovstiClock

    fun nowMillis(): Long = clock.nowMillis()
}
