package dev.rosty.phone

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.rosty.platform.wa.WaPush

/**
 * FCM wake for WhatsApp (AD-WA6). The push carries no readable text: to an install with a push key it is sealed
 * (round 7b) and [WaPush.onMessage] opens it in memory with the Keystore key. It posts the incoming-call notification
 * for `kind=call` (so a cold process still rings; while the vault is locked over the lock screen through
 * `WaLockedCallActivity`) and then wakes `:wa` with the `wa_push` event. Compiled into the `firebase` flavor only.
 */
class RostyMessagingService : FirebaseMessagingService() {
    /** A new FCM token: the gateway must learn it, or pushes go to the old one. */
    override fun onNewToken(token: String) {
        try {
            (application as? dev.rosty.shell.HasContainer<*>)?.container?.waPush?.refresh()
        } catch (e: RuntimeException) {
            Log.e("Rosty", "push token refresh failed", e)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Runs in a process that may have no UI at all: a throw here is a "keeps stopping" dialog on every push.
        try {
            WaPush.onMessage(applicationContext, message.data)
        } catch (e: RuntimeException) {
            Log.e("Rosty", "WhatsApp push failed", e)
        }
    }
}
