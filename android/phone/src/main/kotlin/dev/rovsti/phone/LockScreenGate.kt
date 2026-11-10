package dev.rovsti.phone

/**
 * When the activity may show over the lock screen and turn the screen on (AD-1): only while a call is live, and for
 * [lingerMs] after the last one so the "Call ended" summary is still seen. The flags are dynamic, never in the manifest.
 * Time and the flags are seams, so a JVM test drives the policy without an activity.
 */
class LockScreenGate(
    private val setFlags: (Boolean) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val lingerMs: Long = LINGER_MS,
) {
    private val clear = Runnable { setFlags(false) }

    /** The registry's live-call flag changed. */
    fun onLiveCallChanged(live: Boolean) {
        removeCallbacks(clear)
        if (live) setFlags(true) else postDelayed(clear, lingerMs)
    }

    /** Drops the pending clear (the activity is going away). */
    fun cancel() = removeCallbacks(clear)

    companion object {
        const val LINGER_MS = 3_500L
    }
}
