package dev.rosty.platform.location

import android.os.SystemClock
import dev.rosty.design.time.RostyClock
import dev.rosty.design.time.SystemRostyClock

/** The trip service's and the alarm's "now": the shared [RostyClock] seam, replaceable in tests. */
object TripClock {
    @Volatile
    var clock: RostyClock = SystemRostyClock

    /** The monotonic clock, the one of `Location.getElapsedRealtimeNanos` (the trip's speed history). */
    @Volatile
    var elapsed: () -> Long = { SystemClock.elapsedRealtime() }

    fun nowMillis(): Long = clock.nowMillis()

    fun elapsedMillis(): Long = elapsed()
}
