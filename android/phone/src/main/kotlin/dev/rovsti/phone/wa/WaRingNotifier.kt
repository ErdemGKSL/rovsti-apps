package dev.rovsti.phone.wa

import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.platform.wa.WaIncoming
import dev.rovsti.platform.wa.WaIncomingNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Posts the ringing notification for a WhatsApp call the gateway's live event brought (AD-WA6). Before, only the FCM
 * push posted it, so a build or an install without Firebase rang with no notification and no full-screen intent. Each
 * call is offered once per process; `WaIncoming.show` itself skips a ring that is already over and opens the call
 * screen only for the first post.
 */
class WaRingNotifier(
    private val calls: StateFlow<List<CallSummary>>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val show: (callId: String, title: String) -> Unit,
) {
    private val offered = HashSet<String>()

    fun start() {
        scope.launch { calls.collect(::offer) }
    }

    /** Offers every call in [list] that rings for us now and was not offered yet. */
    fun offer(list: List<CallSummary>) {
        val now = clock()
        for (call in list) {
            if (!ringsNow(call, now) || !offered.add(call.callId)) continue
            show(call.callId, titleOf(call))
        }
    }

    companion object {
        /** Pure: an incoming call in `Ringing` that is younger than the gateway's ring timeout. */
        fun ringsNow(
            call: CallSummary,
            nowMs: Long,
        ): Boolean =
            call.direction == CallDirection.In &&
                call.phase == CallPhase.Ringing &&
                nowMs - call.startedMs < WaIncoming.RING_TIMEOUT_MS

        /** Pure: the contact or push name, else the `+` number, else the generic title. */
        fun titleOf(call: CallSummary): String =
            call.peerName?.trim()?.takeIf { it.isNotEmpty() }
                ?: dialNumber(call.peer)
                ?: WaIncomingNotice.FALLBACK_TITLE
    }
}
