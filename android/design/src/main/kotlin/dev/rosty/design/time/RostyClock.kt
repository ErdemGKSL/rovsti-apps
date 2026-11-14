package dev.rosty.design.time

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The only source of "now" for UI code. Composables and view models never call
 * `System.currentTimeMillis()`; tests provide a fixed clock so screenshots are deterministic.
 */
fun interface RostyClock {
    fun nowMillis(): Long
}

/** Wall clock used in production. */
object SystemRostyClock : RostyClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

val LocalRostyClock = staticCompositionLocalOf<RostyClock> { SystemRostyClock }
