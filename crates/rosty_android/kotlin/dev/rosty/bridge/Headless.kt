package dev.rosty.bridge

import android.content.Context

/**
 * The headless path (AD-MSG4): a receiver that may run with no activity asks Rust for a decision
 * through `RostyBridge.nativeHeadless(kind, json)`. The app exports that symbol with
 * `rosty_android::headless_entry!`. Loading the library only runs `JNI_OnLoad`; it does not start GPUI.
 *
 * Every answer is advisory: `null` means "no decision" (library missing, symbol missing, Rust panic,
 * or an empty answer) and callers fall back to a plain notification.
 */
object Headless {
    /** The app's native library name (`System.loadLibrary` argument), set once by the host's Application. */
    @Volatile var libName: String? = null

    @Volatile private var loaded = false

    /** Loads [lib] once; the bridge on success, `null` on [UnsatisfiedLinkError]. */
    fun ensureLoaded(lib: String): RostyBridge? {
        if (loaded) return RostyBridge
        return try {
            System.loadLibrary(lib)
            loaded = true
            RostyBridge
        } catch (_: UnsatisfiedLinkError) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Calls `nativeHeadless(kind, json)`; `null` when there is no usable answer. Blocks: not the main thread. */
    fun call(kind: String, json: String): String? {
        val lib = libName ?: return null
        val bridge = ensureLoaded(lib) ?: return null
        return try {
            answer(bridge.nativeHeadless(kind, json))
        } catch (_: LinkageError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Pure: an empty or blank answer is "no decision". */
    fun answer(raw: String?): String? = raw?.takeIf { it.isNotBlank() }

    /** The directory Rust's `rosty_ui::storage::data_dir` resolves to on Android. */
    fun dataDir(context: Context): String = context.noBackupFilesDir.absolutePath
}
