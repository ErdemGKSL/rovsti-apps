package dev.rovsti.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rovsti.platform.telephony.CallNotifications
import dev.rovsti.platform.telephony.CallRegistry

/**
 * Notification actions that need no screen. Decline and End go straight to [CallRegistry]: no Rust, no activity (AD-2).
 * Answer is an activity PendingIntent instead, because it must open the In-Call screen.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // A receiver that throws kills the process (and the call's InCallService with it).
        try {
            when (intent.action) {
                CallNotifications.ACTION_END -> {
                    CallRegistry.endAll()
                }

                CallNotifications.ACTION_DECLINE -> {
                    CallRegistry.declineRinging(intent.getIntExtra(CallNotifications.EXTRA_CALL_ID, -1))
                }
            }
        } catch (e: RuntimeException) {
            Log.e("Rovsti", "call action failed", e)
        }
    }
}
