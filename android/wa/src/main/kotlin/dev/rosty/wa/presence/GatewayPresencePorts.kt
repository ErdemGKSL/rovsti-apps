package dev.rosty.wa.presence

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.HeaderPresence
import dev.rosty.core.model.wa.PresenceViewRequest
import dev.rosty.core.ops.WaGateway
import dev.rosty.core.ops.WaOps

/**
 * [WaPresencePorts] over an app's gateway and the Rust op. With no gateway (not linked, the vault locked) every call
 * answers an error and the line stays empty; nothing is retried here.
 */
class GatewayPresencePorts(
    private val gateway: suspend () -> WaGateway?,
    private val ops: WaOps,
) : WaPresencePorts {
    private suspend fun <T> call(block: suspend (WaGateway) -> Outcome<T>): Outcome<Unit> {
        val open = gateway() ?: return Outcome.Err(RustError.NotFound("WhatsApp is not linked"))
        return when (val answer = block(open)) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Err -> answer
        }
    }

    override suspend fun subscribe(jid: String) = call { it.subscribePresence(jid) }

    override suspend fun unsubscribe(jid: String) = call { it.unsubscribePresence(jid) }

    override suspend fun available(available: Boolean) = call { it.setPresence(available) }

    override suspend fun view(req: PresenceViewRequest): Outcome<HeaderPresence> = ops.presenceView(req)
}
