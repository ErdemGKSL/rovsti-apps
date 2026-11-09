package dev.rovsti.platform.sms

import dev.rovsti.design.time.RovstiClock
import dev.rovsti.design.time.SystemRovstiClock

/** The SMS sources' "now": the shared [RovstiClock] seam, replaceable in tests. */
object SmsClock {
    @Volatile
    var clock: RovstiClock = SystemRovstiClock

    fun nowMillis(): Long = clock.nowMillis()
}
