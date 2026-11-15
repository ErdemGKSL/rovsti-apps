package dev.rosty.messages.notify

import dev.rosty.core.model.messages.SmsSend
import dev.rosty.platform.notify.NotificationSpec
import dev.rosty.platform.sms.SmsRepository

/**
 * Where a reply typed into a notification goes while the UI is attached (GPUI `on_reply`): out through the channel
 * it came from. An SMS goes through the [SmsRepository] (the open thread shows it from the provider), a WhatsApp
 * message into the offline outbox ([wa]), so it is stored, shown at once and sent when the gateway is reachable.
 */
class ReplyRouter(
    private val sms: SmsRepository,
    /** Queues a WhatsApp text for a chat JID; false when WhatsApp is not linked. */
    private val wa: suspend (jid: String, text: String) -> Boolean,
    /** The recipients of an SMS thread. */
    private val recipients: suspend (threadId: Long) -> List<String>,
    /** The SIM chosen for an SMS thread, or null for the default one. */
    private val sim: (threadId: Long) -> Int?,
    private val now: () -> Long,
) {
    /** True when the reply was handed over; false for an unknown tag, a thread with no recipient or a refusal. */
    suspend fun send(
        tag: String,
        text: String,
    ): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        val thread = NotificationSpec.smsThreadId(tag)
        val chat = NotificationSpec.waChat(tag)
        return when {
            thread != null -> {
                val to = recipients(thread)
                to.isNotEmpty() && sms.send(SmsSend(now(), to, body, sim(thread))).accepted
            }

            chat != null -> {
                wa(chat, body)
            }

            else -> {
                false
            }
        }
    }
}
