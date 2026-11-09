package dev.rovsti.platform.telephony

/**
 * Ring-latency marks of one ringing call (story 5.6). `:phone` implements it with its `IncomingPerf` (E5, the
 * logcat tag `RovstiPerf`) and installs it in [CallRegistry.timing]; the platform layer only calls it. All calls
 * come on the main thread and carry ids and stage names, never a name or a number.
 */
interface IncomingTiming {
    /** A ringing call arrived. */
    fun onCallAdded(
        callId: Int,
        locked: Boolean,
    )

    /** A stage was reached (`notify` when the notification is posted). */
    fun mark(stage: String)

    /** The UI drew the first frame of the Incoming Call screen for [callId]. */
    fun frame(callId: Int)

    /** The ringing call is over. */
    fun clear(callId: Int)
}
