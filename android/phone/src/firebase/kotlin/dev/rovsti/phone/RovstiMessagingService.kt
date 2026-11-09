package dev.rovsti.phone

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.rovsti.platform.wa.WaPush

/**
 * FCM wake for WhatsApp (AD-WA6). The push carries no message text: [WaPush.onMessage] posts the incoming-call
 * notification for `kind=call` (so a cold process still rings) and then wakes `:wa` with the `wa_push` event. Compiled
 * into the `firebase` flavor only.
 */
class RovstiMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Runs in a process that may have no UI at all: a throw here is a "keeps stopping" dialog on every push.
        try {
            WaPush.onMessage(applicationContext, message.data)
        } catch (e: RuntimeException) {
            Log.e("Rovsti", "WhatsApp push failed", e)
        }
    }
}
