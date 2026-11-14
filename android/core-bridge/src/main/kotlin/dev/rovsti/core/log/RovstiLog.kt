package dev.rovsti.core.log

/**
 * The one log of the app (people sync v2, section 10). Every line is masked ([LogMask]) before it is kept, goes to the
 * always-on memory ring ([ring]) and, while developer mode is on, to the log file. In debuggable builds it is also
 * written to logcat (tag `rovsti`); release builds write nothing to logcat.
 *
 * Policy: never log names, message text or other content people wrote; numbers and keys are masked anyway, but a name
 * is not recognisable as one. Use [event] for structured lines: `event("sync", "batch", "stage" to "lookup", "n" to 15)`
 * gives `batch stage=lookup n=15`.
 */
object RovstiLog {
    /** The memory log every screen reads. */
    val ring = LogRing()

    @Volatile private var file: LogFile? = null

    @Volatile private var logcat = false

    /** The clock lines are stamped with; tests replace it. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    /** Also writes to logcat (debuggable builds only; [DevLog] sets it). */
    fun setLogcat(on: Boolean) {
        logcat = on
    }

    /** Where lines also go while developer mode is on; null: memory only. */
    internal fun setFile(target: LogFile?) {
        file = target
    }

    fun d(
        tag: String,
        msg: String,
        t: Throwable? = null,
    ) = write(LogLevel.Debug, tag, msg, t)

    fun i(
        tag: String,
        msg: String,
        t: Throwable? = null,
    ) = write(LogLevel.Info, tag, msg, t)

    fun w(
        tag: String,
        msg: String,
        t: Throwable? = null,
    ) = write(LogLevel.Warn, tag, msg, t)

    fun e(
        tag: String,
        msg: String,
        t: Throwable? = null,
    ) = write(LogLevel.Error, tag, msg, t)

    /** A structured INFO line `name k=v k=v`; a null value is written as `-`. */
    fun event(
        tag: String,
        name: String,
        vararg fields: Pair<String, Any?>,
    ) = write(LogLevel.Info, tag, render(name, *fields), null)

    /** Like [event] at [level]. */
    fun event(
        level: LogLevel,
        tag: String,
        name: String,
        vararg fields: Pair<String, Any?>,
    ) = write(level, tag, render(name, *fields), null)

    /** `name k=v k=v`. */
    fun render(
        name: String,
        vararg fields: Pair<String, Any?>,
    ): String =
        buildString {
            append(name)
            for ((k, v) in fields) append(' ').append(k).append('=').append(v ?: "-")
        }

    /** A line Rust logged (drained by [RustLogPump]); masked again here, defensively. */
    internal fun rust(
        timeMs: Long,
        level: LogLevel,
        tag: String,
        text: String,
    ) {
        keep(timeMs, level, tag, LogSource.Rust, LogMask.mask(text))
    }

    private fun write(
        level: LogLevel,
        tag: String,
        msg: String,
        t: Throwable?,
    ) {
        val text = if (t == null) msg else "$msg: ${t.stackTraceToString()}"
        keep(clock(), level, tag, LogSource.Kotlin, LogMask.mask(text))
    }

    private fun keep(
        timeMs: Long,
        level: LogLevel,
        tag: String,
        source: LogSource,
        text: String,
    ) {
        val entry = ring.append(timeMs, level, tag, source, text)
        file?.write(entry)
        if (logcat) toLogcat(entry)
    }

    private fun toLogcat(entry: LogEntry) {
        val priority =
            when (entry.level) {
                LogLevel.Debug -> android.util.Log.DEBUG
                LogLevel.Info -> android.util.Log.INFO
                LogLevel.Warn -> android.util.Log.WARN
                LogLevel.Error -> android.util.Log.ERROR
            }
        try {
            android.util.Log.println(priority, LOGCAT_TAG, "[${entry.tag}] ${entry.text}")
        } catch (_: RuntimeException) {
            // Plain JVM tests have no logcat.
        }
    }

    private const val LOGCAT_TAG = "rovsti"
}
