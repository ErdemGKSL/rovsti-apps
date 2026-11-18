package dev.rosty.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rosty.core.log.RostyLog
import dev.rosty.messages.notify.LockedWhatsAppNotice
import dev.rosty.platform.notify.MessageReminders
import dev.rosty.platform.sms.ScheduledSms
import dev.rosty.shell.VaultBoot

/**
 * A reboot or an app update clears AlarmManager. Re-arms every stored scheduled SMS (AD-MSG6) and message reminder (M33); items already due fire
 * at once. The payloads live in SharedPreferences, so no native library is needed. A reboot also ends the WhatsApp
 * vault's daily unlock (`plans/wa-vault.md` 13.2).
 *
 * Ported from apps/messages/platforms/android/app/src/main/kotlin/dev/rosty/messages/BootReceiver.kt
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                ScheduledSms.rearmAll(app)
                // E6.13: the "Remind me" alarms on messages (M33).
                MessageReminders.rearmAll(app)
            } catch (e: RuntimeException) {
                RostyLog.e(TAG, "Re-arming the scheduled messages failed", e)
            }
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) dropDailyUnlock(app)
            } catch (e: RuntimeException) {
                RostyLog.e(TAG, "Dropping the WhatsApp daily unlock failed", e)
            } finally {
                result.finish()
            }
        }.start()
    }

    /**
     * After a reboot the WhatsApp vault needs its passphrase again (`plans/wa-vault.md` 13.2): the daily blob and its
     * K_daily key go. The vault's own boot count check does the same at the next unlock; this does not wait for it.
     * Nothing to do (and no core started) when the app never made a vault.
     */
    private fun dropDailyUnlock(app: Context) {
        LockedWhatsAppNotice.clear(app)
        VaultBoot.dropDailyUnlock(app)
    }

    private companion object {
        const val TAG = "messages"
    }
}
