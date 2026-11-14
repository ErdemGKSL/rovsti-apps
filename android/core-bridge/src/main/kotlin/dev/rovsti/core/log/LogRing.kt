package dev.rovsti.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How serious a line is; [letter] is what the line shows (the level is never told by colour alone). */
enum class LogLevel(
    val letter: Char,
) {
    Debug('D'),
    Info('I'),
    Warn('W'),
    Error('E'),
    ;

    companion object {
        /** A Rust level name (`error`, `warn`, `info`, `debug`, `trace`); anything unknown is [Info]. */
        fun fromRust(name: String): LogLevel =
            when (name) {
                "error" -> Error
                "warn" -> Warn
                "debug", "trace" -> Debug
                else -> Info
            }
    }
}

/** Which side wrote a line. */
enum class LogSource { Kotlin, Rust }

/** One kept line, already masked ([LogMask]). [seq] grows by one per line of the process. */
data class LogEntry(
    val seq: Long,
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val source: LogSource,
    val text: String,
)

/**
 * The always-on memory log: the last [capacity] lines of the process, Kotlin's and Rust's. Appending takes a short lock
 * and never does I/O, so it is safe on any thread, the main thread included. [lastSeq] tells the Logs page that
 * something new arrived.
 */
class LogRing(
    val capacity: Int = CAPACITY,
) {
    private val lock = Any()
    private val lines = ArrayDeque<LogEntry>()
    private var nextSeq = 1L
    private val last = MutableStateFlow(0L)

    /** The seq of the newest line (0: none yet). */
    val lastSeq: StateFlow<Long> = last.asStateFlow()

    /** Keeps one line (cut to [MAX_LINE] characters) and returns it with its seq. */
    fun append(
        timeMs: Long,
        level: LogLevel,
        tag: String,
        source: LogSource,
        text: String,
    ): LogEntry {
        val kept = if (text.length > MAX_LINE) text.substring(0, MAX_LINE) else text
        val entry =
            synchronized(lock) {
                val e = LogEntry(nextSeq++, timeMs, level, tag, source, kept)
                if (lines.size >= capacity) lines.removeFirst()
                lines.addLast(e)
                e
            }
        last.value = entry.seq
        return entry
    }

    /** Every kept line, oldest first. */
    fun snapshot(): List<LogEntry> = synchronized(lock) { lines.toList() }

    /** Forgets every line; the seq keeps counting. */
    fun clear() {
        synchronized(lock) { lines.clear() }
    }

    companion object {
        const val CAPACITY = 2_000

        /** Longer lines are cut. */
        const val MAX_LINE = 2_000
    }
}
