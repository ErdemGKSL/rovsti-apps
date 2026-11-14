package dev.rovsti.core.log

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import dev.rovsti.core.RustCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Whether developer mode is on in this app (each app has its own; hold the Version row of About for 10 s to switch
 * it). Persisted in the SharedPreferences file [PREFS].
 */
class DevMode(
    private val prefs: SharedPreferences,
) {
    private val state = MutableStateFlow(prefs.getBoolean(KEY, false))

    val enabled: StateFlow<Boolean> = state.asStateFlow()

    fun set(on: Boolean) {
        if (state.value == on) return
        prefs.edit().putBoolean(KEY, on).apply()
        state.value = on
    }

    fun toggle(): Boolean {
        val on = !state.value
        set(on)
        return on
    }

    companion object {
        /** The SharedPreferences file name; reserved for developer mode. */
        const val PREFS = "rovsti_dev"
        private const val KEY = "enabled"
    }
}

/**
 * Wires developer mode to the log: while it is on, [RovstiLog] also writes [file] and [pump] brings in Rust's lines;
 * turning it off stops both and deletes the files. The memory ring is always on either way.
 */
class DevLog(
    val devMode: DevMode,
    val file: LogFile,
    private val pump: RustLogPump,
    private val scope: CoroutineScope,
) {
    @Volatile private var started = false

    /** Starts following [devMode]. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            devMode.enabled.collect { on ->
                if (on) {
                    RovstiLog.setFile(file)
                    pump.start()
                    RovstiLog.event("core", "dev_mode", "on" to true)
                } else {
                    RovstiLog.setFile(null)
                    pump.stop()
                    file.deleteAll()
                }
            }
        }
    }

    /** Forgets every line: the memory ring and the files. */
    fun clear() {
        RovstiLog.ring.clear()
        file.deleteAll()
    }

    companion object {
        /** The log files' directory: under `noBackupFilesDir`, so they never go into a backup. */
        fun logDir(context: Context): File = File(context.noBackupFilesDir, "logs")

        /** The real wiring of one app process; logcat only in debuggable builds. */
        fun create(
            context: Context,
            core: () -> RustCore,
            scope: CoroutineScope,
        ): DevLog {
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            RovstiLog.setLogcat(debuggable)
            val prefs = context.getSharedPreferences(DevMode.PREFS, Context.MODE_PRIVATE)
            val source = RustLogSource { core().let(RustLogSource::of).drain(it) }
            return DevLog(DevMode(prefs), LogFile(logDir(context)), RustLogPump(source, scope), scope)
        }
    }
}
