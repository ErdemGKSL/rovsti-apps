package dev.rovsti.phone

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Timestamps of one ringing call, relative to `onCallAdded` (story 5.6). Pure,
 * so a JVM test pins the log format the owner reads from logcat.
 */
class IncomingTimeline(
    val callId: Int,
    private val locked: Boolean,
    private val processCold: Boolean,
    private val startMs: Long,
) {
    private val marks = LinkedHashMap<String, Long>()

    /** Records [stage] once; returns whether this was its first mark. */
    fun mark(stage: String, nowMs: Long): Boolean {
        if (stage in marks) return false
        marks[stage] = nowMs - startMs
        return true
    }

    fun has(stage: String) = stage in marks

    /**
     * One line: `incoming call=3 locked=true process=cold launch=cold notify_ms=9 ... first_frame_ms=812`.
     * `launch=cold` means the activity (and its GPUI session) was created for this call;
     * `process=cold` means the app process itself was started for it.
     */
    fun summary(): String = buildString {
        append("incoming call=").append(callId)
        append(" locked=").append(locked)
        append(" process=").append(if (processCold) "cold" else "warm")
        append(" launch=").append(if (has(ACTIVITY_CREATED)) "cold" else "warm")
        for ((stage, ms) in marks) append(' ').append(stage).append("_ms=").append(ms)
    }

    companion object {
        const val ACTIVITY_CREATED = "activity_created"
        const val FIRST_FRAME = "first_frame"
    }
}

/**
 * Logs ring latency under logcat tag `RovstiPerf`: `adb logcat -s RovstiPerf`.
 * Marks: `notify` (notification posted), `activity_created` (cold launch only),
 * `activity_resumed`, `first_frame` (Rust drew the Incoming Call screen). The
 * summary prints at `first_frame`. Main thread only. Never logs names or numbers.
 */
object IncomingPerf {
    const val TAG = "RovstiPerf"

    /** A process younger than this when the call arrives was started for it. */
    private const val COLD_PROCESS_MS = 6_000L

    private var timeline: IncomingTimeline? = null

    fun onCallAdded(callId: Int, locked: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val cold = now - Process.getStartElapsedRealtime() < COLD_PROCESS_MS
        timeline = IncomingTimeline(callId, locked, cold, now)
        Log.i(TAG, "incoming call=$callId ring_start locked=$locked")
    }

    fun mark(stage: String) {
        timeline?.mark(stage, SystemClock.elapsedRealtime())
    }

    /** Rust drew the first frame of the Incoming Call screen for [callId]. */
    fun frame(callId: Int) {
        val t = timeline?.takeIf { it.callId == callId } ?: return
        if (t.mark(IncomingTimeline.FIRST_FRAME, SystemClock.elapsedRealtime())) {
            Log.i(TAG, t.summary())
        }
    }

    fun clear(callId: Int) {
        if (timeline?.callId == callId) timeline = null
    }
}
