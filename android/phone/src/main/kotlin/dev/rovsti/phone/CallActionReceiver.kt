package dev.rovsti.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import dev.rovsti.platform.telephony.CallNotifications
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.platform.telephony.MissedCallNotifications
import dev.rovsti.shell.HasContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Notification actions that need no screen. Decline and End go straight to [CallRegistry]: no Rust, no activity (AD-2).
 * Answer is an activity PendingIntent instead, because it must open the In-Call screen.
 *
 * E5.12: the missed-call notification's Call back places the call through Telecom and Remind me arms a reminder an hour
 * from now (the core makes it, after `Rust.init`; the receiver stays alive with `goAsync` for at most [BUDGET_MS]).
 * Both take that caller's notification down.
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

                MissedCallNotifications.ACTION_CALL_BACK -> {
                    callBack(context, intent)
                }

                MissedCallNotifications.ACTION_REMIND -> {
                    remind(context, intent)
                }
            }
        } catch (e: RuntimeException) {
            Log.e("Rovsti", "call action failed", e)
        }
    }

    private fun callBack(
        context: Context,
        intent: Intent,
    ) {
        val number = intent.getStringExtra(MissedCallNotifications.EXTRA_NUMBER).orEmpty()
        if (number.isBlank()) return
        try {
            context
                .getSystemService(TelecomManager::class.java)
                ?.placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: SecurityException) {
            // CALL_PHONE was taken back: the call stays in Recents.
            Log.w("Rovsti", "call back refused", e)
        }
        MissedCallNotifications.dismiss(context, number)
    }

    private fun remind(
        context: Context,
        intent: Intent,
    ) {
        val number = intent.getStringExtra(MissedCallNotifications.EXTRA_NUMBER).orEmpty()
        val name = intent.getStringExtra(MissedCallNotifications.EXTRA_NAME)
        val app = context.applicationContext
        val container = (app as? HasContainer<*>)?.container as? PhoneContainer ?: return
        if (number.isBlank()) return
        val pending = goAsync()
        container.scope.launch {
            try {
                val armed = withTimeoutOrNull(BUDGET_MS) { container.remindInOneHour(number, name) } == true
                if (armed) MissedCallNotifications.dismiss(app, number)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Under the ten seconds Android gives a receiver. */
        const val BUDGET_MS = 9_000L
    }
}
