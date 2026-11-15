package dev.rosty.messages.starred

import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncStarredCursor
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.messages.inbox.InboxWords
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.inbox.numberOfJid
import dev.rosty.messages.inbox.waPreview
import dev.rosty.messages.wa.WaOffline
import dev.rosty.messages.wa.toBody
import dev.rosty.messages.wa.toMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** The app's [StarCenter]: the prefs file and the offline WhatsApp store with its outbox. */
fun realStarCenter(container: MessagesContainer): StarCenter {
    val res = container.context.resources
    // Builds before the outbox knew stars kept the pending ones in a file of their own; nothing reads it any more.
    container.scope.launch(Dispatchers.IO) { File(container.context.filesDir, LEGACY_FILE).delete() }
    return StarCenter(
        chat = container.chat,
        store = container.store,
        wa = waStarSeams(container.waOffline, ResourceInboxWords(res), res.getString(R.string.tools_me)),
        scope = container.scope,
    )
}

/**
 * The WhatsApp side of [StarCenter] over [offline]: a star is an outbox action like a send (shown at once, sent now or
 * by the outbox job when a network is back), and the Starred list is the store's own query across all chats.
 */
internal fun waStarSeams(
    offline: WaOffline,
    words: InboxWords,
    me: String,
): WaStarSeams =
    WaStarSeams(
        changes = offline.changes,
        starred = { storedStars(offline, words, me) },
        star = { chat, id, starred -> offline.enqueue(chat, WaSyncAction.Star(id, starred)) },
    )

/**
 * The starred messages of every chat of the store (`wa_sync.starred`), newest first, at most [MAX_STARS]; null while
 * WhatsApp is not linked.
 */
internal suspend fun storedStars(
    offline: WaOffline,
    words: InboxWords,
    me: String,
): List<WaStarRow>? {
    val store = offline.session.value?.store ?: return null
    val rows = ArrayList<WaSyncMessage>()
    var before: WaSyncStarredCursor? = null
    while (rows.size < MAX_STARS) {
        val page = store.starred(PAGE, before).getOrNull() ?: return null
        rows += page.items
        before = page.next ?: break
    }
    if (rows.isEmpty()) return emptyList()
    val chats =
        store
            .chats(includeArchived = true)
            .getOrNull()
            .orEmpty()
            .associateBy { it.jid }
    return rows.map { row ->
        val chat = chats[row.chat]
        val title =
            chat?.name?.takeIf { it.isNotBlank() }
                ?: chat?.pushName?.takeIf { it.isNotBlank() }
                ?: numberOfJid(row.chat)
                ?: row.chat
        val author =
            when {
                row.fromMe -> {
                    me
                }

                chat?.isGroup ?: row.chat.endsWith(GROUP_SERVER) -> {
                    row.senderName?.takeIf { it.isNotBlank() } ?: row.sender?.let(::numberOfJid) ?: title
                }

                else -> {
                    title
                }
            }
        val preview = row.text?.takeIf { it.isNotBlank() } ?: waPreview(row.toBody(), row.toMeta(), words)
        WaStarRow(row.chat, row.id, row.timestampMs, author, preview, title)
    }
}

private const val LEGACY_FILE = "wa-stars.json"
private const val GROUP_SERVER = "@g.us"
private const val PAGE = 200
private const val MAX_STARS = 1_000
