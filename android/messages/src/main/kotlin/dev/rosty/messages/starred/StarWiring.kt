package dev.rosty.messages.starred

import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.messages.inbox.InboxWords
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.inbox.numberOfJid
import dev.rosty.messages.inbox.waPreview
import dev.rosty.messages.wa.WaOffline
import dev.rosty.messages.wa.toBody
import dev.rosty.messages.wa.toMeta
import java.io.File

/** The app's [StarCenter]: the prefs file, the offline WhatsApp store and the gateway's star call. */
fun realStarCenter(container: MessagesContainer): StarCenter {
    val res = container.context.resources
    val words = ResourceInboxWords(res)
    return StarCenter(
        chat = container.chat,
        store = container.store,
        wa =
            WaStarSeams(
                online = container.waOffline.online,
                changes = container.waOffline.changes,
                scan = { scanStoredStars(container.waOffline, words, res.getString(R.string.tools_me)) },
                star = { chat, id, starred -> container.waRemote.star(chat, id, starred) is Outcome.Ok },
            ),
        file = File(container.context.filesDir, StarCenter.FILE),
        scope = container.scope,
        now = System::currentTimeMillis,
    )
}

/**
 * The starred messages among the newest [SCAN_ROWS] stored rows of the [SCAN_CHATS] most recent chats. The store has
 * no query for stars across chats, so this is what can be read without one; a star made on this phone is in
 * [StarCenter]'s own file as well, however old the message is.
 */
internal suspend fun scanStoredStars(
    offline: WaOffline,
    words: InboxWords,
    me: String,
): List<WaStarRow>? {
    val store = offline.session.value?.store ?: return null
    val chats = store.chats(includeArchived = true).getOrNull() ?: return null
    return chats.take(SCAN_CHATS).flatMap { chat ->
        val title =
            chat.name?.takeIf { it.isNotBlank() }
                ?: chat.pushName?.takeIf { it.isNotBlank() }
                ?: numberOfJid(chat.jid)
                ?: chat.jid
        store
            .thread(chat.jid, SCAN_ROWS)
            .getOrNull()
            ?.items
            .orEmpty()
            .filter { it.starred && !it.deleted && it.state !in QUEUED }
            .map { row ->
                val author =
                    when {
                        row.fromMe -> {
                            me
                        }

                        chat.isGroup -> {
                            row.senderName?.takeIf { it.isNotBlank() } ?: row.sender?.let(::numberOfJid)
                                ?: title
                        }

                        else -> {
                            title
                        }
                    }
                val preview = row.text?.takeIf { it.isNotBlank() } ?: waPreview(row.toBody(), row.toMeta(), words)
                WaStarRow(chat.jid, row.id, row.timestampMs, author, preview, title)
            }
    }
}

private val QUEUED = setOf(WaSyncRowState.Waiting, WaSyncRowState.Sending, WaSyncRowState.Failed)
private const val SCAN_CHATS = 40
private const val SCAN_ROWS = 200
