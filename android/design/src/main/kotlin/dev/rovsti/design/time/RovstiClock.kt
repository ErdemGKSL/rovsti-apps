package dev.rovsti.design.time

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The only source of "now" for UI code. Composables and view models never call
 * `System.currentTimeMillis()`; tests provide a fixed clock so screenshots are deterministic.
 */
fun interface RovstiClock {
    fun nowMillis(): Long
}

/** Wall clock used in production. */
object SystemRovstiClock : RovstiClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

val LocalRovstiClock = staticCompositionLocalOf<RovstiClock> { SystemRovstiClock }
