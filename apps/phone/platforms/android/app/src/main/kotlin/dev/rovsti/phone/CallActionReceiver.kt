package dev.rovsti.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Notification actions that need no screen. Decline and End go straight to
 * [CallRegistry]: no Rust, no GPUI, no activity (AD-2). Answer is an activity
 * PendingIntent instead, because it must open the In-Call screen.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CallNotifications.ACTION_END -> CallRegistry.endAll()
            CallNotifications.ACTION_DECLINE ->
                CallRegistry.declineRinging(intent.getIntExtra(CallNotifications.EXTRA_CALL_ID, -1))
        }
    }
}
