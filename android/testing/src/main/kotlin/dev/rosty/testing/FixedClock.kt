package dev.rosty.testing

import dev.rosty.design.time.RostyClock
import java.time.Instant

/** A clock that never moves, so relative timestamps in screenshots are stable. */
class FixedClock(
    private val millis: Long = DEFAULT_MILLIS,
) : RostyClock {
    override fun nowMillis(): Long = millis

    companion object {
        /** 2026-01-15T10:30:00Z */
        val DEFAULT_MILLIS: Long = Instant.parse("2026-01-15T10:30:00Z").toEpochMilli()
    }
}
