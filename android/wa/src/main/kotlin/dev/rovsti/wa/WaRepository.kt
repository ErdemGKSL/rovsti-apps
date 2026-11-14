package dev.rovsti.wa

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.RichChat
import dev.rovsti.core.model.wa.RichMessage
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.ops.WaGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The WhatsApp state of one app (ARCH 7.4): link state, the gateway, its events and the lists derived from them.
 * One per app container. View models depend on this interface; tests use `FakeWaRepository`.
 */
interface WaRepository {
    /** Where the link stands; [WaLinkState.None] until [start] has read the stored link. */
    val linkState: StateFlow<WaLinkState>

    /**
     * False until [start] has read the stored link and, when there is one, the gateway's first status arrived or
     * failed. Until then [linkState] `None` does not mean "not linked": a cold process (the sync worker) waits for it.
     */
    val settled: StateFlow<Boolean>

    /**
     * The gateway events of this app's instance, in order. Lists should not be derived from this flow (events can be
     * dropped under load): use [chats] and [messages], which reload on a gap or a `resync`.
     */
    val events: SharedFlow<GatewayEvent>

    /** Reads the stored link, opens the gateway and the event stream, and listens for `wa_push`. Idempotent. */
    fun start()

    /** The open gateway, opened lazily from the stored link; null while there is no link or it cannot be opened. */
    suspend fun gateway(): WaGateway?

    /** The gateway has news (an FCM push): skip the reconnect backoff of the event stream. */
    suspend fun wake(): Outcome<Unit>

    /** The chats, newest first. Emits at once, then again after a `resync`, an event gap or a change to a chat. */
    fun chats(): Flow<List<RichChat>>

    /** The newest page of [jid], newest first; reloads like [chats]. */
    fun messages(jid: String): Flow<List<RichMessage>>

    /** Stores [link] (from the settings screen's `Save` effect) and reconnects with it. */
    suspend fun relinkTo(link: StoredLink): Outcome<Unit>

    /** Closes the gateway and the event stream and removes the stored link. */
    suspend fun forget(): Outcome<Unit>
}
