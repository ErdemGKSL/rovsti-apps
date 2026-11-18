package dev.rosty.messages.notify

import android.content.Context
import dev.rosty.core.model.wa.WaIds
import dev.rosty.platform.identity.CallerLookup
import dev.rosty.platform.wa.OpenedPush
import dev.rosty.platform.wa.push.PushContent

/**
 * What a WhatsApp message push does in Messages (`plans/wa-vault.md` 6, 13.6, round 7b). While the vault is open in
 * this process, the app's event socket brings the message and the announcer posts it. Otherwise (locked, or a cold
 * process, where the vault is always locked) nothing is fetched from the gateway, and:
 *
 * - a sealed push that opened (this install's push key) posts its chat's notice with the sender and the text, as the
 *   preview setting allows ([LockedWhatsAppNotice.addChat]);
 * - a push that is not sealed (the gateway has no key for this install) names the chat only when its number belongs to
 *   a device contact (device contacts are not vault secrets), with the count and no text;
 * - anything else, and a sealed push that does not open, adds to the one content-free notice.
 *
 * Nothing a push says is written to disk: it goes into the notification only.
 */
object WaPushNotices {
    /** Handles [opened]; true when a locked notice was posted. Calls and call ends are not messages. */
    fun onPush(
        context: Context,
        opened: OpenedPush,
        unlocked: Boolean,
        mode: WaPreviewMode = WaPreviews.mode(context),
        contactName: (String) -> String? = deviceContacts(context),
        now: () -> Long = System::currentTimeMillis,
    ): Boolean {
        if (opened.data["kind"] != KIND_MESSAGE || unlocked) return false
        val count = opened.data["count"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val content = opened.content
        if (content != null) {
            val chat = content.chat ?: return LockedWhatsAppNotice.add(context, count)
            val notice = lockedChat(context, chat, content, count, contactName, now())
            return LockedWhatsAppNotice.addChat(context, notice, mode)
        }
        if (!opened.sealed) {
            val chat = opened.data["chat"]
            val name = chat?.takeUnless { WaIds.isGroup(it) }?.let { WaIds.number(it) }?.let { contactName(it)?.trim() }
            if (chat != null && !name.isNullOrEmpty()) {
                val notice = LockedChat(chat, name, line = null, count = count, whenMs = now())
                // No line: the name and the count at most.
                return LockedWhatsAppNotice.addChat(context, notice, mode)
            }
        }
        return LockedWhatsAppNotice.add(context, count)
    }

    /**
     * Pure: the notice of a sealed message push. The title is the device contact that owns the chat's number, else the
     * chat's name on the gateway, else the sender's WhatsApp name, else the number, else "WhatsApp"; a group is titled
     * by its subject and its line names the author the same way.
     */
    internal fun lockedChat(
        context: Context,
        chat: String,
        content: PushContent,
        count: Int,
        contactName: (String) -> String?,
        now: Long,
    ): LockedChat {
        val fallback = context.getString(dev.rosty.messages.R.string.wa_locked_notice_title)
        val text = content.text?.trim()?.takeIf { it.isNotEmpty() }
        val group = content.group || WaIds.isGroup(chat)
        val title: String
        val line: String?
        if (group) {
            title = content.chatName.clean() ?: fallback
            val author = nameOf(content.sender, content.senderAlt, content.name, contactName)
            line = text?.let { if (author != null) "$author: $it" else it }
        } else {
            title = nameOf(chat, content.chatAlt, content.chatName ?: content.name, contactName) ?: fallback
            line = text
        }
        return LockedChat(chat, title, line, count, content.ts ?: now)
    }

    private fun nameOf(
        jid: String?,
        alt: String?,
        whatsAppName: String?,
        contactName: (String) -> String?,
    ): String? {
        val number = WaIds.number(jid) ?: WaIds.number(alt)
        return number?.let(contactName).clean() ?: whatsAppName.clean() ?: number
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** The device contact that owns a number (the app's caller-ID, else Android's `PhoneLookup`). */
    private fun deviceContacts(context: Context): (String) -> String? {
        val lookup = CallerLookup.of(context.applicationContext)
        return { number ->
            try {
                lookup.identify(number)?.name
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    private const val KIND_MESSAGE = "message"
}
