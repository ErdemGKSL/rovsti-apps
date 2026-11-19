package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.map
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.ChatAction
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.core.model.wa.ChatState
import dev.rosty.core.model.wa.DeleteForMe
import dev.rosty.core.model.wa.EditMessage
import dev.rosty.core.model.wa.ForwardMessage
import dev.rosty.core.model.wa.GroupInfo
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.MarkRead
import dev.rosty.core.model.wa.MessageReceipts
import dev.rosty.core.model.wa.RevokeMessage
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.SendMediaWithBody
import dev.rosty.core.model.wa.SendReaction
import dev.rosty.core.model.wa.SendText
import dev.rosty.core.model.wa.StarMessage
import dev.rosty.core.model.wa.StatusList
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaStreamEvent
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncFetch
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.ops.WaEvents
import dev.rosty.core.ops.WaFeed
import dev.rosty.core.ops.WaGateway
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.WaRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** What the event socket of the sync reports, in order. */
sealed interface WaRemoteEvent {
    /** The socket is open: the gateway is reachable. */
    data object Connected : WaRemoteEvent

    /** The socket dropped (it reconnects on its own) or could not be opened. */
    data object Disconnected : WaRemoteEvent

    /** Events were dropped on this side: what the store missed must be fetched again. */
    data object Dropped : WaRemoteEvent

    data class Event(
        val event: WaSyncEvent,
    ) : WaRemoteEvent
}

/**
 * The network side of the WhatsApp offline store (E6.4s): everything the sync asks the gateway. The store never talks
 * to the network itself; [WaSyncRunner] and [WaOutbox] make these calls and hand the answers to it. Tests use a fake.
 */
interface WaRemote {
    /** The events of [instance] after [after] (null: from now). Ends when the gateway is closed. */
    fun events(
        instance: String,
        after: Long?,
    ): Flow<WaRemoteEvent>

    suspend fun chats(): Outcome<List<RichChat>>

    /**
     * A page of the LID and number pairs the gateway learned, after the cursor [since] (null: every pair). The
     * default is a gateway that does not list them: the store then learns from the events and the alt ids only.
     */
    suspend fun identityMappings(since: String?): Outcome<IdentityMappingsAnswer> =
        Outcome.Ok(IdentityMappingsAnswer(supported = false))

    /** The linked account as the gateway sees it: its own number and LID. */
    suspend fun status(): Outcome<InstanceStatus> = Outcome.Err(RustError.Unsupported("status"))

    /** The page a [WaSyncFetch] names, newest first. */
    suspend fun page(fetch: WaSyncFetch): Outcome<List<RichMessage>>

    /** Performs one outbox action in [chat]; a send answers the gateway's message id. */
    suspend fun perform(
        chat: String,
        action: WaSyncAction,
    ): Outcome<WaSyncOutcome.Sent>

    suspend fun chatAction(
        jid: String,
        kind: ChatActionKind,
    ): Outcome<Unit>

    /** Who got, read or played my message [messageId] of [chat], and when (E6-WA R4). */
    suspend fun messageReceipts(
        chat: String,
        messageId: String,
    ): Outcome<MessageReceipts> = Outcome.Err(RustError.Unsupported("message receipts"))

    /** A group with its members (the `@` list of the composer). */
    suspend fun group(jid: String): Outcome<GroupInfo> = Outcome.Err(RustError.Unsupported("group"))

    /** "typing…" to the other side; best effort. */
    suspend fun typing(
        chat: String,
        state: TypingState,
    ): Outcome<Unit>

    suspend fun thumbnail(messageId: String): Outcome<BytesAnswer>

    /**
     * The full file of a message. [RustError.NotFound] means the gateway no longer has it (a download settles as
     * gone); every other error may work later.
     */
    suspend fun media(messageId: String): Outcome<BytesAnswer>

    suspend fun statuses(): Outcome<StatusList>

    suspend fun statusMedia(statusId: String): Outcome<BytesAnswer>

    suspend fun statusThumbnail(statusId: String): Outcome<BytesAnswer>

    suspend fun markStatusViewed(ids: List<String>): Outcome<Unit>
}

/**
 * [WaRemote] over the app's [WaRepository]. The sync opens its own event socket on the repository's gateway, resumed
 * after the store's `seq` and labelled [syncLabel], so the repository's stream (which has no `seq` to resume from after
 * a cold start) and its listeners are left alone.
 */
class GatewayWaRemote(
    private val repo: WaRepository,
    private val waEvents: WaEvents = WaEvents(),
    /**
     * The lazy sync (`plans/wa-vault.md` 14): one event socket, the repository's (live, no `after=` replay), and the
     * status the repository fetched when it opened the gateway. Off: the sync opens its own socket after its `seq`.
     */
    private val shareRepository: Boolean = false,
) : WaRemote {
    private suspend fun <T> call(block: suspend (WaGateway) -> Outcome<T>): Outcome<T> {
        val gateway = repo.gateway() ?: return Outcome.Err(RustError.NotFound("WhatsApp is not linked"))
        return block(gateway)
    }

    override fun events(
        instance: String,
        after: Long?,
    ): Flow<WaRemoteEvent> =
        channelFlow {
            val gateway = repo.gateway()
            if (gateway == null) {
                send(WaRemoteEvent.Disconnected)
                return@channelFlow
            }
            if (shareRepository) {
                // The repository's socket (opened with its gateway): the sync only listens.
                val feed =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        waEvents.feed(instance).collect { send(remoteEvent(it) ?: return@collect) }
                    }
                if (repo.linkState.value is WaLinkState.Connected) send(WaRemoteEvent.Connected)
                awaitClose { feed.cancel() }
                return@channelFlow
            }
            val label = syncLabel(instance)
            // Subscribed before the socket starts (undispatched), so the first events are not missed.
            val feed =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    waEvents.feed(label).collect { send(remoteEvent(it) ?: return@collect) }
                }
            when (val stream = gateway.startEvents(label, after)) {
                is Outcome.Err -> {
                    feed.cancel()
                    send(WaRemoteEvent.Disconnected)
                }

                is Outcome.Ok -> {
                    awaitClose { stream.value.close() }
                }
            }
        }

    private fun remoteEvent(feed: WaFeed): WaRemoteEvent? =
        when (feed) {
            WaFeed.Resync -> {
                WaRemoteEvent.Dropped
            }

            is WaFeed.Undecodable -> {
                null
            }

            is WaFeed.Stream -> {
                when (val event = feed.event) {
                    WaStreamEvent.Connected -> WaRemoteEvent.Connected
                    is WaStreamEvent.Disconnected -> WaRemoteEvent.Disconnected
                    WaStreamEvent.Unauthorized -> WaRemoteEvent.Disconnected
                    is WaStreamEvent.Event -> WaRemoteEvent.Event(WaSyncEvent.of(event))
                }
            }
        }

    override suspend fun chats(): Outcome<List<RichChat>> = call { it.chatsWithMeta() }

    override suspend fun identityMappings(since: String?): Outcome<IdentityMappingsAnswer> =
        call { it.identityMappings(since) }

    override suspend fun status(): Outcome<InstanceStatus> {
        if (shareRepository) {
            // The repository asked for it when it opened the gateway: no second request.
            repo.gateway() ?: return Outcome.Err(RustError.NotFound("WhatsApp is not linked"))
            (repo.linkState.value as? WaLinkState.Connected)?.status?.let { return Outcome.Ok(it) }
        }
        return call { it.status() }
    }

    override suspend fun page(fetch: WaSyncFetch): Outcome<List<RichMessage>> =
        call { it.messagesWithMeta(fetch.chat, fetch.before, fetch.limit) }

    override suspend fun perform(
        chat: String,
        action: WaSyncAction,
    ): Outcome<WaSyncOutcome.Sent> =
        call { gateway ->
            when (action) {
                is WaSyncAction.SendText -> {
                    gateway
                        .sendText(SendText(chat, action.text, action.quotedId, action.mentions, action.linkPreview))
                        .map { WaSyncOutcome.Sent(it.messageId, it.timestampMs) }
                }

                // E6-WA R4: a real forward, marked forwarded; the gateway relays the media.
                is WaSyncAction.Forward -> {
                    gateway
                        .forward(ForwardMessage(action.sourceChat, action.messageId, chat))
                        .map { WaSyncOutcome.Sent(it.messageId, it.timestampMs) }
                }

                is WaSyncAction.DeleteForMe -> {
                    gateway.deleteForMe(DeleteForMe(chat, action.messageId)).map { WaSyncOutcome.Sent() }
                }

                is WaSyncAction.SendMedia -> {
                    gateway
                        .sendMediaWith(
                            SendMediaWithBody(
                                chat = chat,
                                kind = action.media,
                                mime = action.mime,
                                caption = action.caption,
                                path = action.file,
                                // Preview, pixel size, waveform, GIF flag and view once travel with the row.
                                options = action.options(),
                            ),
                        ).map { WaSyncOutcome.Sent(it.messageId, it.timestampMs) }
                }

                is WaSyncAction.Edit -> {
                    gateway.editMessage(EditMessage(chat, action.messageId, action.text)).map { WaSyncOutcome.Sent() }
                }

                is WaSyncAction.Revoke -> {
                    gateway
                        .revokeMessage(RevokeMessage(chat, action.messageId, action.sender))
                        .map { WaSyncOutcome.Sent() }
                }

                is WaSyncAction.React -> {
                    gateway
                        .react(SendReaction(chat, action.messageId, action.fromMe, action.sender, action.emoji))
                        .map { WaSyncOutcome.Sent() }
                }

                is WaSyncAction.MarkRead -> {
                    gateway.markRead(MarkRead(chat, action.sender, action.ids)).map { WaSyncOutcome.Sent() }
                }

                // The played receipt of a voice note (`WaSyncStore.markPlayed`).
                is WaSyncAction.MarkPlayed -> {
                    gateway
                        .markPlayed(MarkRead(chat, action.sender, listOf(action.messageId)))
                        .map { WaSyncOutcome.Sent() }
                }

                // E6.13: the account's own star (M10).
                is WaSyncAction.Star -> {
                    gateway
                        .starMessage(StarMessage(chat, action.messageId, action.starred))
                        .map { WaSyncOutcome.Sent() }
                }
            }
        }

    override suspend fun chatAction(
        jid: String,
        kind: ChatActionKind,
    ): Outcome<Unit> = call { it.chatAction(ChatAction(jid, kind)).map { } }

    override suspend fun messageReceipts(
        chat: String,
        messageId: String,
    ): Outcome<MessageReceipts> = call { it.messageReceipts(chat, messageId) }

    override suspend fun group(jid: String): Outcome<GroupInfo> = call { it.group(jid) }

    override suspend fun typing(
        chat: String,
        state: TypingState,
    ): Outcome<Unit> = call { it.chatState(ChatState(chat, state)).map { } }

    override suspend fun thumbnail(messageId: String): Outcome<BytesAnswer> = call { it.thumbnail(messageId) }

    override suspend fun media(messageId: String): Outcome<BytesAnswer> {
        // Not linked is not "the file is gone": the download stays queued.
        val gateway = repo.gateway() ?: return Outcome.Err(RustError.Network("WhatsApp is not linked"))
        return gateway.media(messageId)
    }

    override suspend fun statuses(): Outcome<StatusList> = call { it.statuses() }

    override suspend fun statusMedia(statusId: String): Outcome<BytesAnswer> = call { it.statusMedia(statusId) }

    override suspend fun statusThumbnail(statusId: String): Outcome<BytesAnswer> = call { it.statusThumbnail(statusId) }

    override suspend fun markStatusViewed(ids: List<String>): Outcome<Unit> = call { it.markStatusViewed(ids).map { } }

    companion object {
        /** The `instance` label of the sync's own event socket (echoed in each `wa.event`). */
        fun syncLabel(instance: String): String = "$instance#sync"
    }
}
