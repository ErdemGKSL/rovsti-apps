package dev.rosty.messages.wa

import dev.rosty.core.getOrNull
import dev.rosty.core.ops.NativeWaSyncOps
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.messages.MessagesContainer
import dev.rosty.wa.WaLinkStore
import kotlinx.coroutines.flow.combine

/**
 * The real [WaOffline] of the app: the store of the instance in `whatsapp-link.json` (read again whenever the link
 * state changes, so it also opens offline, where the link state is an error), filled through [remote] and woken by the
 * WorkManager outbox job.
 */
fun realWaOffline(
    container: MessagesContainer,
    remote: WaRemote,
): WaOffline {
    val links = WaLinkStore(container.waOps)
    return WaOffline(
        scope = container.scope,
        linkChanges = combine(container.wa.linkState, container.wa.settled) { state, settled -> state to settled },
        link = {
            links.read().getOrNull()?.let { link ->
                WaStoreLink(link.instanceId, socketKey = "${link.gatewayUrl}#${link.key.hashCode()}")
            }
        },
        open = { instance -> WaSyncStore.open(instance, ops = NativeWaSyncOps(container.rust)) },
        remote = remote,
        now = System::currentTimeMillis,
        wake = WaOutboxWork(container.context),
    )
}
