package dev.rovsti.phone

/**
 * The error boundary of the Incoming Call screen (AD-17, story 5.6). Compose cannot catch an exception thrown while a
 * screen composes, so the boundary watches for the one thing a healthy screen always does: it reports its first frame
 * ([frame]) soon after the activity resumes on a ringing call. If that report does not come within [timeoutMs], the
 * activity shows `NativeIncoming` instead, so the call can still be answered or declined.
 *
 * Pure: the clock is [post]/[cancel] (the main handler in the app, a fake in the tests). Main thread only.
 */
class IncomingWatchdog(
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onStuck: (callId: Int) -> Unit,
    val timeoutMs: Long = TIMEOUT_MS,
) {
    private var watching: Int? = null
    private var framed: Int? = null
    private val fire =
        Runnable {
            val id = watching ?: return@Runnable
            watching = null
            onStuck(id)
        }

    /** The screen of [callId] is expected to draw. Does nothing for a call that already drew, or one being watched. */
    fun arm(callId: Int) {
        if (callId == framed || callId == watching) return
        watching = callId
        cancel(fire)
        post(fire, timeoutMs)
    }

    /** The screen drew its first frame for [callId]. */
    fun frame(callId: Int) {
        framed = callId
        if (watching == callId) disarm()
    }

    /** The call is gone, or the activity is. */
    fun disarm() {
        watching = null
        cancel(fire)
    }

    val isArmed: Boolean get() = watching != null

    companion object {
        /** A cold Compose start draws well inside this; past it the call is better served by plain Views. */
        const val TIMEOUT_MS = 2_500L
    }
}
