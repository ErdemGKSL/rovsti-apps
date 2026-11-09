package dev.rovsti.platform.system

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * Reads MIUI / HyperOS per-app permissions that live in AppOps under private
 * op codes. `AppOpsManager.checkOpNoThrow(int op, int uid, String pkg)` is
 * public API but only takes the codes it knows, so the int overload is reached
 * by reflection, the way open-source MIUI helpers do it.
 *
 * The codes come from community sources (a widely copied `XiaomiUtilities`
 * list: OP_AUTO_START = 10008, OP_SHOW_WHEN_LOCKED = 10020,
 * OP_BACKGROUND_START_ACTIVITY = 10021). They are not documented by Xiaomi and
 * HyperOS behaviour is unverified, so every failure maps to [UNKNOWN] and the
 * app falls back to the owner's manual confirmation.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/XiaomiOps.kt
 */
object XiaomiOps {
    const val OP_AUTO_START = 10008
    const val OP_SHOW_WHEN_LOCKED = 10020
    const val OP_BACKGROUND_START_ACTIVITY = 10021

    const val UNKNOWN = -1
    const val DENIED = 0
    const val GRANTED = 1

    /** Bridge key (see `xiaomi.rs`) to op code; null for an unknown key. */
    fun opFor(key: String): Int? =
        when (key) {
            "autostart" -> OP_AUTO_START
            "lock_screen" -> OP_SHOW_WHEN_LOCKED
            "popups" -> OP_BACKGROUND_START_ACTIVITY
            else -> null
        }

    /**
     * AppOps mode to tri-state. MODE_ALLOWED (0) is granted, MODE_IGNORED (1)
     * and MODE_ERRORED (2) are denied. MODE_DEFAULT (3), MODE_FOREGROUND (4),
     * null (reflection failed) and anything else are not a sane answer.
     */
    fun classify(mode: Int?): Int =
        when (mode) {
            0 -> GRANTED
            1, 2 -> DENIED
            else -> UNKNOWN
        }

    /** The raw mode, or null when the call is unavailable on this device. */
    fun mode(
        context: Context,
        op: Int,
    ): Int? {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return null
        return try {
            val method =
                AppOpsManager::class.java.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            method.invoke(manager, op, Process.myUid(), context.packageName) as? Int
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    fun state(
        context: Context,
        key: String,
    ): Int {
        val op = opFor(key) ?: return UNKNOWN
        return classify(mode(context, op))
    }
}
