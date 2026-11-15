package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.map
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.ChatAction
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.core.model.wa.ChatState
import dev.rosty.core.model.wa.EditMessage
import dev.rosty.core.model.wa.MarkRead
import dev.rosty.core.model.wa.MediaOptions
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

    /** "typing…" to the other side; best effort. */
    suspend fun typing(
        chat: String,
        state: TypingState,
    ): Outcome<Unit>

    suspend fun thumbnail(messageId: String): Outcome<BytesAnswer>

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
                        .sendText(SendText(chat, action.text, action.quotedId))
                        .map { WaSyncOutcome.Sent(it.messageId, it.timestampMs) }
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
                                options =
                                    MediaOptions(action.quotedId, action.fileName, action.seconds, action.voiceNote),
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

    override suspend fun typing(
        chat: String,
        state: TypingState,
    ): Outcome<Unit> = call { it.chatState(ChatState(chat, state)).map { } }

    override suspend fun thumbnail(messageId: String): Outcome<BytesAnswer> = call { it.thumbnail(messageId) }

    override suspend fun media(messageId: String): Outcome<BytesAnswer> = call { it.media(messageId) }

    override suspend fun statuses(): Outcome<StatusList> = call { it.statuses() }

    override suspend fun statusMedia(statusId: String): Outcome<BytesAnswer> = call { it.statusMedia(statusId) }

    override suspend fun statusThumbnail(statusId: String): Outcome<BytesAnswer> = call { it.statusThumbnail(statusId) }

    override suspend fun markStatusViewed(ids: List<String>): Outcome<Unit> = call { it.markStatusViewed(ids).map { } }

    companion object {
        /** The `instance` label of the sync's own event socket (echoed in each `wa.event`). */
        fun syncLabel(instance: String): String = "$instance#sync"
    }
}
