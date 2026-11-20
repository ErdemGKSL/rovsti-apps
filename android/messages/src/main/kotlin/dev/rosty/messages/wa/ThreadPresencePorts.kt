package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.HeaderPresence
import dev.rosty.core.model.wa.PresenceViewRequest
import dev.rosty.wa.presence.WaPresencePorts

/**
 * The presence ports of the thread (E6-WA R5): the gateway calls through the sync's [WaRemote] (only while connected;
 * offline they fail quietly and the line stays empty) and Rust's line through the [WaActionRules].
 */
class ThreadPresencePorts(
    private val remote: WaRemote,
    private val rules: WaActionRules,
) : WaPresencePorts {
    override suspend fun subscribe(jid: String): Outcome<Unit> = remote.subscribePresence(jid)

    override suspend fun unsubscribe(jid: String): Outcome<Unit> = remote.unsubscribePresence(jid)

    override suspend fun available(available: Boolean): Outcome<Unit> = remote.setAvailable(available)

    override suspend fun view(req: PresenceViewRequest): Outcome<HeaderPresence> = rules.presenceView(req)
}
