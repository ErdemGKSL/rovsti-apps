package dev.rovsti.platform.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import org.robolectric.Shadows.shadowOf

/** Delivers [intent] to [receiver] as an ordered broadcast with result [code], as the telephony service would. */
internal fun deliverTo(
    context: Context,
    receiver: BroadcastReceiver,
    intent: Intent,
    code: Int,
) {
    context.registerReceiver(receiver, IntentFilter(intent.action), Context.RECEIVER_NOT_EXPORTED)
    try {
        context.sendOrderedBroadcast(intent, null, null, null, code, null, null)
        shadowOf(Looper.getMainLooper()).idle()
    } finally {
        context.unregisterReceiver(receiver)
    }
}
