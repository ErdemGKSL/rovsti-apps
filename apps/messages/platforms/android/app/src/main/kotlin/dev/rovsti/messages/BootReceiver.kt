package dev.rovsti.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rovsti.bridge.ScheduledSms

/**
 * A reboot or an app update clears AlarmManager. Re-arms every stored scheduled SMS (AD-MSG6); items already due
 * fire at once. The payloads live in SharedPreferences, so no native library is needed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                ScheduledSms.rearmAll(app)
            } finally {
                result.finish()
            }
        }.start()
    }
}
