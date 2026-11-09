package dev.rovsti.platform.calllog

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import android.telecom.TelecomManager

/**
 * Story 6.4: viewing Recents clears the system missed-call notification.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/MissedCalls.kt (`LocaleTimeInfo` is
 * `platform.system.LocaleTimeInfo`).
 */
object MissedCalls {
    /**
     * Clears the `NEW` flag on missed calls, then asks Telecom to cancel the missed-call notification. Both need
     * privileges the app only has as the default phone app (the notification) or with `WRITE_CALL_LOG` (the flag); a
     * refusal is not an error. True when the notification call ran.
     */
    @SuppressLint("MissingPermission")
    fun markRead(context: Context): Boolean {
        try {
            val values = ContentValues().apply { put(CallLog.Calls.NEW, 0) }
            context.contentResolver.update(
                CallLog.Calls.CONTENT_URI,
                values,
                "${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
            )
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
        return try {
            context.getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification() != null
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
