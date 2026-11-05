package dev.rovsti.phone

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.rovsti.bridge.WaPush

/**
 * FCM wake for WhatsApp (AD-WA6). The push carries no message text: `WaPush.onMessage` posts the
 * incoming-call notification for `kind=call` (so a cold process still rings) and then wakes Rust
 * with the `wa_push` event. Compiled only when `app/google-services.json` exists.
 */
class RovstiMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        WaPush.onMessage(message.data)
    }
}
