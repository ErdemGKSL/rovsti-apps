package dev.rovsti.core.log

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
import dev.rovsti.core.model.core.LogsDrainAnswer
import dev.rovsti.core.model.core.LogsDrainRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Asks Rust for its log lines after a seq: `core.logs_drain`. */
fun interface RustLogSource {
    suspend fun drain(req: LogsDrainRequest): Outcome<LogsDrainAnswer>

    companion object {
        fun of(core: RustCore): RustLogSource =
            RustLogSource {
                core.call(
                    "core.logs_drain",
                    it,
                    LogsDrainRequest.serializer(),
                    LogsDrainAnswer.serializer(),
                )
            }
    }
}

/**
 * Brings Rust's log lines into [RovstiLog] while developer mode is on (D10: pull). [start] drains at once (the history
 * since the process started, as far as Rust's ring of 2 000 lines reaches) and then every [intervalMs]; [stop] ends the
 * polling, so with developer mode off it costs nothing. Lines keep Rust's time and level; the tag is the part after
 * `rovsti::` of the target (`rovsti::sync` -> `sync`), `core` for everything else.
 */
class RustLogPump(
    private val source: RustLogSource,
    private val scope: CoroutineScope,
    private val intervalMs: Long = INTERVAL_MS,
    private val sink: (timeMs: Long, level: LogLevel, tag: String, text: String) -> Unit = RovstiLog::rust,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null

    /** The seq of the last Rust line taken; survives [stop], so switching on again does not repeat lines. */
    @Volatile var afterSeq: Long = 0
        private set

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job =
            scope.launch {
                while (isActive) {
                    pumpOnce()
                    delay(intervalMs)
                }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Drains until Rust has nothing newer; false when the call failed (the next tick tries again). */
    suspend fun pumpOnce(): Boolean {
        repeat(MAX_ROUNDS) {
            val answer =
                when (val got = source.drain(LogsDrainRequest(afterSeq, BATCH))) {
                    is Outcome.Err -> return false
                    is Outcome.Ok -> got.value
                }
            for (line in answer.entries) {
                sink(line.timeMs, LogLevel.fromRust(line.level), tagOf(line.target), line.text)
            }
            if (answer.dropped > 0) {
                sink(clock(), LogLevel.Warn, CORE_TAG, "rust_logs_lost n=${answer.dropped}")
            }
            afterSeq = maxOf(afterSeq, answer.lastSeq)
            if (answer.entries.size < BATCH) return true
        }
        return true
    }

    companion object {
        const val INTERVAL_MS = 1_000L
        private const val BATCH = 500
        private const val MAX_ROUNDS = 10
        private const val CORE_TAG = "core"
        private const val PREFIX = "rovsti::"

        /** `rovsti::sync::pace` -> `sync`; any other target -> `core`. */
        fun tagOf(target: String): String =
            if (target.startsWith(PREFIX)) {
                target.removePrefix(PREFIX).substringBefore("::").ifEmpty { CORE_TAG }
            } else {
                CORE_TAG
            }
    }
}
