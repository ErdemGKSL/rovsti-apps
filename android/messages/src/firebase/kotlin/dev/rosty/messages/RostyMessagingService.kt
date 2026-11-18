package dev.rosty.messages

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.rosty.messages.notify.WaPushNotices
import dev.rosty.platform.wa.WaPush

/**
 * FCM wake for WhatsApp (AD-WA6, AD-MSG5). A push to an install that registered its push key is sealed to it (round
 * 7b): [WaPush.onMessage] opens it in memory with the Keystore key; FCM itself carries no readable text. It wakes
 * `:wa` with the `wa_push` event (a locked repository opens nothing for it).
 *
 * Since the WhatsApp vault (`plans/wa-vault.md` 13.6) a message push never fetches anything: with the vault open in
 * this process the event socket brings the message and the app posts it; otherwise [WaPushNotices] posts the sender
 * and the preview from the sealed push (as the preview setting allows), or the content-free notice with the count.
 * Compiled into the `firebase` flavor only.
 *
 * Ported from apps/messages/platforms/android/app/src/firebase/kotlin/dev/rosty/messages/RostyMessagingService.kt
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
            val opened = WaPush.onMessage(applicationContext, message.data)
            val container = (application as? dev.rosty.shell.HasContainer<*>)?.container as? MessagesContainer
            WaPushNotices.onPush(this, opened, unlocked = container?.waVaultIfMade?.unlocked == true)
        } catch (e: RuntimeException) {
            Log.e("Rosty", "WhatsApp push failed", e)
        }
    }
}
