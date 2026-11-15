package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.model.chat.SearchCandidate
import dev.rosty.core.model.messages.InboxThread
import dev.rosty.core.model.messages.Message
import dev.rosty.core.model.messages.MessageDirection
import dev.rosty.core.model.messages.PhoneNumber
import dev.rosty.core.model.messages.ThreadId
import dev.rosty.core.model.wa.ChatAction
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.WaIds
import dev.rosty.messages.inbox.InboxWords
import dev.rosty.messages.inbox.waPreview
import dev.rosty.messages.names.PersonNames
import dev.rosty.wa.WaRepository
import kotlinx.coroutines.flow.Flow

/**
 * The WhatsApp chats the inbox shows and the chat actions it takes. One seam, so the inbox does not care whether the
 * chats come from the gateway or from the offline store (E6.4s: the store is the only source of the UI, the network
 * fills it). Tests use a fake.
 */
interface WaChats {
    /** The chats, newest first; empty while WhatsApp is not linked. */
    val chats: Flow<List<RichChat>>

    /** Archive, pin, mute, unmute, mark read/unread; false when it could not be done (not linked, gateway error). */
    suspend fun act(
        jid: String,
        kind: ChatActionKind,
    ): Boolean

    /**
     * The stored messages of the chats [jids] (the recent ones `global_search.prepare` picked) as search candidates,
     * chat key `wa:<jid>` and message id the WhatsApp id; null while WhatsApp is not linked.
     */
    suspend fun searchCandidates(jids: List<String>): List<SearchCandidate>? = null
}

/** [WaChats] straight over the gateway (`:wa`): the live list and `chat_action`. */
class GatewayWaChats(
    private val repo: WaRepository,
) : WaChats {
    override val chats: Flow<List<RichChat>> get() = repo.chats()

    override suspend fun act(
        jid: String,
        kind: ChatActionKind,
    ): Boolean {
        val gateway = repo.gateway() ?: return false
        return gateway.chatAction(ChatAction(jid, kind)) is Outcome.Ok
    }
}

/** The status broadcast and newsletter "chats" are not conversations. */
fun RichChat.isConversation(): Boolean = WaIds.isConversation(jid)

/** A chat as `inbox.merge` takes it (GPUI `inbox::chat_threads`): the newest message in the unified model. */
fun RichChat.toInboxThread(words: InboxWords): InboxThread? {
    val last = last ?: return null
    if (!isConversation()) return null
    val id = ThreadId.WhatsApp(jid)
    val body = waPreview(last.body, lastMeta ?: MessageMeta(), words)
    return InboxThread(
        id = id,
        last =
            Message(
                thread = id,
                direction = if (last.fromMe) MessageDirection.Outgoing else MessageDirection.Incoming,
                sender = if (last.fromMe) null else WaIds.number(last.sender ?: jid)?.let(::PhoneNumber),
                body = body,
                timestampMs = last.timestampMs,
                read = unread == 0,
            ),
        unread = unread,
        // WhatsApp's side of the rule; the merge puts a contact before it and the inbox asks for the rest.
        title = PersonNames.storedTitle(this),
        isGroup = isGroup,
    )
}
