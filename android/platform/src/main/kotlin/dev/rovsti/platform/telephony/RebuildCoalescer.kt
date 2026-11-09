package dev.rovsti.platform.telephony

// Ported from apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/RebuildCoalescer.kt (ARCH 7.3).

/**
 * Coalesces snapshot rebuilds (AD-23). Audio, mute and can-add callbacks call [schedule]: one
 * posted task rebuilds within [windowMs], however many callbacks arrive meanwhile. Everything
 * that feeds focus, caps, the incoming notification or `knownIds` calls [now], which cancels a
 * pending scheduled rebuild and rebuilds at once (so a merge never shows a transient scene).
 * Pure Kotlin: the poster is injected so a JVM test drives it with a fake handler.
 */
class RebuildCoalescer(
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val windowMs: Long = WINDOW_MS,
    private val rebuild: () -> Unit,
) {
    private var scheduled = false
    private val task =
        Runnable {
            scheduled = false
            rebuild()
        }

    /** True while a scheduled rebuild is waiting. */
    val isScheduled: Boolean get() = scheduled

    fun schedule() {
        if (scheduled) return
        scheduled = true
        post(task, windowMs)
    }

    fun now() {
        if (scheduled) {
            scheduled = false
            cancel(task)
        }
        rebuild()
    }

    companion object {
        const val WINDOW_MS = 12L
    }
}
