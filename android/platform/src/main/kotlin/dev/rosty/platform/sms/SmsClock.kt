package dev.rosty.platform.sms

import dev.rosty.design.time.RostyClock
import dev.rosty.design.time.SystemRostyClock

/** The SMS sources' "now": the shared [RostyClock] seam, replaceable in tests. */
object SmsClock {
    @Volatile
    var clock: RostyClock = SystemRostyClock

    fun nowMillis(): Long = clock.nowMillis()
}
