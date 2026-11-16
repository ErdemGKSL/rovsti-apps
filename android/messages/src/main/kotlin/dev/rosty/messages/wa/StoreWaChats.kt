package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.SearchCandidate
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.core.model.wa.ChatMeta
import dev.rosty.core.model.wa.DELETED_BY_ME
import dev.rosty.core.model.wa.DeliveryStatus
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.model.wa.WaMessage
import dev.rosty.core.model.wa.WaSyncChat
import dev.rosty.core.model.wa.WaSyncCursor
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.messages.names.PersonNames
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * [WaChats] from the offline store (E6.4s): the inbox reads the chats of the linked instance from the device, online
 * or not, and reads them again whenever the sync or the outbox changed a row. Chat actions (archive, pin, mute, mark
 * read) go to the gateway; its `chat_meta` event then updates the store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreWaChats(
    private val offline: WaOffline,
    private val remote: WaRemote,
    /** Names the chats and the group senders of the search candidates. */
    private val names: PersonNames,
) : WaChats {
    override val chats: Flow<List<RichChat>> =
        offline.session.flatMapLatest { session ->
            if (session == null) {
                flowOf(emptyList())
            } else {
                offline.changes
                    .onStart { emit(null) }
                    .conflate()
                    .map {
                        session.store
                            .chats(includeArchived = true)
                            .getOrNull()
                            .orEmpty()
                            .map(WaSyncChat::toRichChat)
                    }
            }
        }

    override suspend fun searchCandidates(jids: List<String>): List<SearchCandidate>? {
        val session = offline.session.value ?: return null
        val chats =
            session.store
                .chats(includeArchived = true)
                .getOrNull()
                .orEmpty()
                .associateBy { it.jid }
        val out = ArrayList<SearchCandidate>()
        for (jid in jids) {
            val chat = chats[jid]
            val title = names.chatTitle(jid, chat)
            val group = chat?.isGroup ?: WaIds.isGroup(jid)
            val authors = HashMap<String?, String>()
            var cursor: WaSyncCursor? = null
            for (page in 0 until SEARCH_PAGES) {
                val read = session.store.thread(jid, SEARCH_PAGE, cursor).getOrNull() ?: break
                for (row in read.items) {
                    val text = row.text?.takeIf { it.isNotBlank() && !row.deleted } ?: continue
                    // A send still in the outbox has no WhatsApp id yet: its client id opens it all the same.
                    out +=
                        SearchCandidate(
                            chatKey = WaRekeys.tagOf(jid),
                            chatTitle = title,
                            messageId = row.id,
                            author =
                                when {
                                    row.fromMe -> null
                                    group -> authors.getOrPut(row.sender) { names.sender(row.sender, row.senderName) }
                                    else -> row.senderName?.takeIf { it.isNotBlank() }
                                },
                            text = text,
                            atMs = row.timestampMs,
                        )
                }
                cursor = read.next ?: break
            }
        }
        return out
    }

    override suspend fun act(
        jid: String,
        kind: ChatActionKind,
    ): Boolean {
        val session = offline.session.value ?: return false
        if (remote.chatAction(jid, kind) !is Outcome.Ok) return false
        // The `chat_meta` event brings the new state; this covers a gateway that sends none for the change.
        session.runner.refresh()
        return true
    }
}

/** Stored messages read per chat for the global search: a few pages at most. */
private const val SEARCH_PAGES = 3
private const val SEARCH_PAGE = 200

/** A stored chat in the shape the inbox merges (`RichChat`, as the gateway lists it). */
fun WaSyncChat.toRichChat(): RichChat =
    RichChat(
        jid = jid,
        name = name,
        last = last?.toWaMessage(),
        unread = unread,
        isGroup = isGroup,
        meta =
            ChatMeta(
                pictureId = pictureId,
                pushName = pushName,
                archived = archived,
                pinned = pinned,
                mutedUntilMs = mutedUntilMs,
                markedUnread = markedUnread,
            ),
        lastMeta = last?.toMeta(),
    )

/** The body the gateway would have sent for a stored row. */
fun WaSyncMessage.toBody(): MessageBody {
    val media =
        when (kind) {
            WaSyncKind.Image -> MediaKind.Image
            WaSyncKind.Video -> MediaKind.Video
            WaSyncKind.Audio -> MediaKind.Audio
            WaSyncKind.Document -> MediaKind.Document
            WaSyncKind.Sticker -> MediaKind.Sticker
            else -> null
        }
    return when {
        media != null -> MessageBody.Media(media, text, mime.orEmpty(), size ?: 0)
        kind == WaSyncKind.Unsupported && text == null -> MessageBody.Unsupported
        else -> MessageBody.Text(text.orEmpty())
    }
}

fun WaSyncMessage.toWaMessage(): WaMessage = WaMessage(id, chat, sender, fromMe, timestampMs, toBody(), reactions)

fun WaSyncMessage.toMeta(): MessageMeta =
    MessageMeta(
        quoted = quoted,
        forwarded = forwarded,
        editedMs = editedMs,
        revoked = deleted,
        status =
            when (state) {
                WaSyncRowState.Sent -> DeliveryStatus.Sent
                WaSyncRowState.Delivered -> DeliveryStatus.Delivered
                WaSyncRowState.Read -> DeliveryStatus.Read
                WaSyncRowState.Played -> DeliveryStatus.Played
                else -> null
            },
        senderName = senderName,
        media = this.media,
        location = location,
        contact = contact,
        mentions = mentions,
        starred = starred,
        deleted = deleted,
        deletedAt = deletedAt,
        deletedBy = deletedBy,
        unavailable = unavailable,
    )

/** Whether I deleted this message myself (not "deleted for everyone" by its sender). */
fun WaSyncMessage.deletedByMe(): Boolean = deleted && (deletedBy == DELETED_BY_ME || (deletedBy == null && fromMe))
