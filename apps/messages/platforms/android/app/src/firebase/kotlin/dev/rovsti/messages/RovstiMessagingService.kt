package dev.rovsti.messages

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.rovsti.bridge.Headless
import dev.rovsti.bridge.MessageNotifications
import dev.rovsti.bridge.RovstiBridge
import dev.rovsti.bridge.WaPush
import org.json.JSONObject

/**
 * FCM wake for WhatsApp (AD-WA6, AD-MSG5). The push carries no message text. `WaPush.onMessage` posts the
 * incoming-call notification for `kind=call` and wakes Rust with the `wa_push` event.
 *
 * For `kind=message` while the UI is not attached nobody is listening to that event, so the real text is
 * fetched here: `nativeHeadless("wa_digest", {dir, chat})` asks Rust to read the last messages from the
 * gateway and answers a `NotificationSpec`, which `MessageNotifications` renders. With the UI attached the
 * app posts the notification itself from the event stream, so nothing is posted here. If the library cannot
 * be loaded or the gateway is unreachable the answer is empty and the push is dropped silently.
 *
 * Compiled only when `app/google-services.json` exists.
 */
class RovstiMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        WaPush.onMessage(data)
        val chat = data["chat"]
        if (data["kind"] != "message" || chat.isNullOrEmpty() || RovstiBridge.uiReady) return
        // onMessageReceived runs on a worker thread with a ~20 s budget: blocking on the gateway is fine.
        val request = JSONObject().put("dir", Headless.dataDir(this)).put("chat", chat).toString()
        val spec = Headless.call("wa_digest", request) ?: return
        MessageNotifications.show(this, spec)
    }
}
