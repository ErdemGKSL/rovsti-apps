package dev.rosty.platform.wa

import android.os.SystemClock
import dev.rosty.core.log.RostyLog

/**
 * How long a WhatsApp call answered while the vault was locked takes from the Answer tap to the connected call
 * (round 7b; the target is about 2 s). One log line per step, with the milliseconds since the tap and nothing else: no
 * call id, number or name. Read on a device with `adb logcat -s wa-answer`.
 */
object WaAnswerTiming {
    private const val TAG = "wa-answer"

    /** Seams for tests. */
    @Volatile var clock: () -> Long = SystemClock::elapsedRealtime

    @Volatile var log: (String) -> Unit = { RostyLog.i(TAG, it) }

    private var callId: String? = null
    private var startedAt = 0L

    /** Answer was tapped on the locked ring for [callId]. */
    @Synchronized
    fun start(callId: String) {
        this.callId = callId
        startedAt = clock()
        log("answer tapped (vault locked)")
    }

    /** A step of the answer for [callId] ("screen unlocked", "vault open", ...); nothing when it is not timed. */
    @Synchronized
    fun mark(
        callId: String,
        step: String,
    ) {
        if (callId != this.callId) return
        log("$step after ${clock() - startedAt} ms")
    }

    /** The answer of the timed call connected or gave up: the last line, and the timing ends. */
    @Synchronized
    fun finish(
        callId: String,
        step: String,
    ) {
        if (callId != this.callId) return
        log("$step after ${clock() - startedAt} ms")
        this.callId = null
    }

    /** Whether [callId] is being timed. */
    @Synchronized
    fun isTiming(callId: String): Boolean = callId == this.callId
}
