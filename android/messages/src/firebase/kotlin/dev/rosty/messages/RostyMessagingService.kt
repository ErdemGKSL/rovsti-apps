package dev.rosty.messages

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.rosty.messages.notify.MessagesNotifier
import dev.rosty.messages.notify.NotificationNames
import dev.rosty.platform.Headless
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.wa.WaPush
import org.json.JSONObject

/**
 * FCM wake for WhatsApp (AD-WA6, AD-MSG5). The push carries no message text. [WaPush.onMessage] wakes `:wa` with the
 * `wa_push` event (and rings for `kind=call`).
 *
 * For `kind=message` while the UI is not attached nobody is listening to that event, so the real text is fetched here:
 * `headless("wa_digest", {dir, chat})` asks Rust to read the last messages from the gateway and answers a
 * `NotificationSpec`, which [MessagesNotifier] renders under the name [NotificationNames] gives the chat. With the UI
 * attached the app posts the notification itself from the event stream, so nothing is posted here. Compiled into the
 * `firebase` flavor only.
 *
 * Ported from apps/messages/platforms/android/app/src/firebase/kotlin/dev/rosty/messages/RostyMessagingService.kt
 */
class RostyMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Runs in a process that may have no UI at all: a throw here is a "keeps stopping" dialog on every push.
        try {
            val data = message.data
            WaPush.onMessage(applicationContext, data)
            val chat = data["chat"]
            if (data["kind"] != "message" || chat.isNullOrEmpty() || PlatformEvents.uiAttached) return
            // onMessageReceived runs on a worker thread with a ~20 s budget: blocking on the gateway is fine.
            val request = JSONObject().put("dir", Headless.dataDir(this)).put("chat", chat).toString()
            val spec = Headless.call("wa_digest", request) ?: return
            // The core titles a person by the push name; a saved contact's name comes first (the name-order rule).
            NotificationNames.showWaPush(this, chat, spec)
        } catch (e: RuntimeException) {
            Log.e("Rosty", "WhatsApp push failed", e)
        }
    }
}
