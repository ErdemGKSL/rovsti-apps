package dev.rosty.phone

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rosty.core.log.RostyLog
import dev.rosty.platform.notify.CallReminders

/**
 * Puts the call-back reminders back (E5.12). A reboot, an app update and a revoked or newly granted exact-alarm
 * permission all clear the app's alarms; the items stay in the store, and [CallReminders.rearmAll] sets every alarm
 * again (a reminder that is already due fires at once). Only the system sends these broadcasts, and no Rust is needed
 * for them. A reboot no longer touches the WhatsApp vault (round 7c: it reopens by itself once the phone is
 * unlocked).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in ACTIONS) return
        try {
            CallReminders.rearmAll(context.applicationContext)
        } catch (e: RuntimeException) {
            RostyLog.e("phone", "re-arming the reminders failed", e)
        }
    }

    companion object {
        val ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )
    }
}
