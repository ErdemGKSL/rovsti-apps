package dev.rovsti.phone.wa

import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.platform.wa.WaOngoingCall
import dev.rovsti.platform.wa.WaProximity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * What a live WhatsApp call does outside its screen:
 * - the proximity lock ([WaProximity]): on while the call is `Active` on the earpiece and not muted, off on a route
 *   change, mute, the end of the call and when [scope] ends (the app goes away);
 * - the ongoing notification ([describe] tells `WaCallService` which call it shows, with the time it connected).
 *
 * [earpiece] is whether the voice goes to the earpiece (`communicationOnEarpiece`), [speaker] whether the loudspeaker
 * was asked for (it wins while the device is still switching).
 */
class WaInCallEffects(
    private val calls: StateFlow<List<CallSummary>>,
    private val muted: StateFlow<Set<String>>,
    private val speaker: StateFlow<Boolean>,
    private val earpiece: Flow<Boolean>,
    private val proximity: WaProximity,
    private val describe: (WaOngoingCall?) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    /** When each call was first seen `Active` here (the gateway's summary has no connect time). */
    private val connectedAt = HashMap<String, Long>()

    fun start() {
        scope.launch {
            try {
                combine(calls, muted, speaker, earpiece) { list, mutedIds, loud, ear ->
                    Snapshot(liveCall(list), mutedIds, loud, ear)
                }.collect(::apply)
            } finally {
                proximity.close()
            }
        }
    }

    private class Snapshot(
        val call: CallSummary?,
        val muted: Set<String>,
        val speaker: Boolean,
        val earpiece: Boolean,
    )

    private fun apply(now: Snapshot) {
        val call = now.call
        val active = call?.phase == CallPhase.Active
        proximity.update(
            active = active,
            earpiece = now.earpiece && !now.speaker,
            muted = call != null && call.callId in now.muted,
        )
        if (call == null) {
            connectedAt.clear()
            describe(null)
            return
        }
        val since = if (active) connectedAt.getOrPut(call.callId) { clock() } else 0L
        describe(WaOngoingCall(call.callId, WaRingNotifier.titleOf(call), since))
    }

    companion object {
        /** Pure: the call the phone is on: connecting or active, or an outgoing one that rings. */
        fun liveCall(list: List<CallSummary>): CallSummary? =
            list.firstOrNull { it.phase == CallPhase.Active || it.phase == CallPhase.Connecting }
                ?: list.firstOrNull { it.direction == CallDirection.Out && it.phase == CallPhase.Ringing }
    }
}
