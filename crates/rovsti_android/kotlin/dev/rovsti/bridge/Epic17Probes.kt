package dev.rovsti.bridge

import android.content.Context
import android.provider.CallLog

/**
 * Pure line builders of the Diagnostics "Blocking" (S6), "Reject text" (S7) and "Silence" (S8) groups
 * (stories 17.1, 18.1, 18.4), so a JVM test covers them. Numbers never appear in a line.
 */
object Epic17Probes {
    /** One reading of the blocklist and the call log for the S6 report. */
    data class Blocking(
        val canBlock: Boolean,
        val rows: Int,
        val newestBlocked: Boolean?,
        val type6Recent: Int,
        val newestType6AgeS: Long?,
        val newestReason: String?,
        val guardHits: Int,
        val guardOn: Boolean,
    )

    fun blockingLines(b: Blocking): List<String> {
        val out = ArrayList<String>()
        out.add("info can_block=${b.canBlock} blocklist_rows=${b.rows} block_guard=${b.guardOn} guard_hits=${b.guardHits}")
        if (!b.canBlock) {
            out.add("fail: this user may block numbers (set Rovsti Phone as the default phone app first)")
            return out
        }
        out.add(
            when (b.newestBlocked) {
                null -> "unknown: isBlocked on the newest entry (block a number in Settings > Blocked numbers first)"
                true -> "pass: isBlocked on the newest entry"
                false -> "fail: isBlocked on the newest entry"
            },
        )
        out.add(
            if (b.type6Recent > 0) {
                "pass: a type-6 call-log row appeared (${b.type6Recent} in 15 min, newest ${b.newestType6AgeS ?: 0} s ago, reason ${b.newestReason ?: "unknown"})"
            } else {
                "unknown: a type-6 call-log row appeared (call from the second phone now, then run again)"
            },
        )
        out.add("unknown: the call did not ring (the owner answers: note ring or no ring in docs/spikes/s6-blocklist.md)")
        out.add("unknown: Xiaomi's own blocked list shows the number (open the system Phone app > Blocked and compare)")
        return out
    }

    /** What the S7 run saw. */
    data class Reject(val found: Boolean, val respondViaText: Boolean, val code: Int, val hadNumber: Boolean)

    fun rejectLines(r: Reject): List<String> {
        if (!r.found) return listOf("unknown: a ringing call (run, then call this phone from the second phone within 40 s)")
        val out = ArrayList<String>()
        out.add("info respond_via_text=${r.respondViaText} caller_has_number=${r.hadNumber} result_code=${r.code}")
        out.add(
            when (r.code) {
                RejectPolicy.SENT -> "pass: reject(true, \"test\") accepted through Telecom"
                RejectPolicy.OPENED -> "fail: Telecom path refused, the SMS app was opened instead (capability or number missing)"
                RejectPolicy.REJECTED_ONLY -> "fail: call rejected without a text"
                else -> "unknown: the call ended before the probe could act"
            },
        )
        out.add("unknown: the SMS arrived on the second phone within 30 s (the owner answers; note the SIM used and the cost)")
        return out
    }

    /** One ring watched by the S8 probe. */
    data class Attempt(val silenced: Boolean, val by: String?)

    fun silenceLines(attempts: List<Attempt>, callbacks: Int): List<String> {
        if (attempts.isEmpty()) return listOf("unknown: a ringing call (run, then call this phone three times within 75 s)")
        val out = ArrayList<String>()
        attempts.forEachIndexed { i, a ->
            out.add("info attempt ${i + 1}: ringer_silenced=${a.silenced} silenced_by=${a.by ?: "none"}")
        }
        out.add("info on_silence_ringer_callbacks=$callbacks")
        val by = attempts.mapNotNull { it.by }.toSet()
        out.add(if ("user" in by) "pass: silenceRinger() from the app was accepted" else "unknown: silenceRinger() from the app (tap Silence on one ring)")
        out.add(if ("volume" in by) "pass: a volume key reported onSilenceRinger" else "unknown: a volume key reported onSilenceRinger (press a volume key on one ring)")
        out.add(if ("flip" in by) "pass: flipping the phone face down silenced it" else "unknown: flipping the phone face down silenced it (once with the screen locked)")
        out.add("unknown: the ringtone actually stopped each time (the owner answers; note it in docs/spikes/s8-silence.md)")
        return out
    }

    /** The Android readings for S6. */
    fun readBlocking(context: Context, guardOn: Boolean, guardHits: Int): Blocking {
        val provider = Blocklist.System(context)
        val can = Blocklist.canBlock(provider)
        val rows = if (can) Blocklist.guard(provider, emptyList<Blocklist.Row>()) { provider.rows() }.second else emptyList()
        val newest = rows.maxByOrNull { it.id }
        val newestBlocked = newest?.let { Blocklist.isBlocked(provider, it.numberRaw) }
        var count = 0
        var age: Long? = null
        var reason: String? = null
        try {
            val since = System.currentTimeMillis() - 15 * 60_000L
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.BLOCK_REASON),
                "${CallLog.Calls.TYPE} = 6 AND ${CallLog.Calls.DATE} > ?", arrayOf(since.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (count == 0) {
                        age = (System.currentTimeMillis() - c.getLong(0)) / 1000
                        reason = Blocklist.reasonName(c.getInt(1))
                    }
                    count++
                }
            }
        } catch (_: RuntimeException) {
        }
        return Blocking(can, rows.size, newestBlocked, count, age, reason, guardHits, guardOn)
    }
}
