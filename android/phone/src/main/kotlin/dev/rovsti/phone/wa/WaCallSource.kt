package dev.rovsti.phone.wa

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.AudioRoute
import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.CallUpdate
import dev.rovsti.core.model.phone.WaArbitrateRequest
import dev.rovsti.core.model.phone.WireCall
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.phone.incoming.CallSource
import dev.rovsti.phone.incoming.WaCallMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** Who owns the call screen when a WhatsApp call meets a cellular one (the core's `Arbitration`). */
enum class WaDecision { ShowWa, WaWaiting, RejectWaBusy, Telecom }

/** Asks the core which call owns the screen (AD-WA5): a seam, so the source's tests need no native library. */
fun interface WaArbiter {
    suspend fun decide(
        telecomLive: Boolean,
        telecomRinging: Boolean,
        wa: CallUpdate?,
    ): WaDecision
}

/** [WaArbiter] over `phone.wa_arbitrate`. A core failure keeps the cellular screen: a cellular call is never hidden. */
class CoreWaArbiter(
    private val ops: PhoneOps,
) : WaArbiter {
    override suspend fun decide(
        telecomLive: Boolean,
        telecomRinging: Boolean,
        wa: CallUpdate?,
    ): WaDecision =
        when ((ops.waArbitrate(WaArbitrateRequest(telecomLive, telecomRinging, wa)) as? Outcome.Ok)?.value?.decision) {
            "show_wa" -> WaDecision.ShowWa
            "wa_waiting" -> WaDecision.WaWaiting
            "reject_wa_busy" -> WaDecision.RejectWaBusy
            else -> WaDecision.Telecom
        }
}

/** Ids of WhatsApp calls inside a [CallSnapshot]: Telecom hands out small ints, these start far above them. */
class WaIdBook {
    private val ids = HashMap<String, Int>()
    private val calls = HashMap<Int, String>()

    @Synchronized
    fun idOf(callId: String): Int =
        ids.getOrPut(callId) {
            (BASE + ids.size).also { calls[it] = callId }
        }

    @Synchronized
    fun callIdOf(id: Int): String? = calls[id]

    companion object {
        const val BASE = 0x4000_0000

        fun isWa(id: Int): Boolean = id >= BASE
    }
}

/**
 * The calls the screens read: the registry's snapshot (cellular) with the WhatsApp call folded in (AD-WA5). The WhatsApp
 * call becomes one more [WireCall] with an id from [WaIdBook], so Incoming, In-Call, the waiting card and the mini pill
 * run unchanged; [whatsapp] says which ids are WhatsApp. The core's arbitration decides who owns the screen:
 *
 * - `ShowWa`: the WhatsApp call is the foreground call (a ringing cellular call beside a running WhatsApp call becomes
 *   the waiting call);
 * - `WaWaiting`: a WhatsApp offer during a cellular call is the waiting call (End & Answer, Decline);
 * - `RejectWaBusy`: a cellular call rings, so the WhatsApp offer is declined, once per call id ([onBusy]);
 * - `Telecom`: no live WhatsApp call, the registry's snapshot as it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaMergedCallSource(
    private val base: CallSource,
    waCalls: StateFlow<List<CallSummary>>,
    muted: StateFlow<Set<String>>,
    speaker: StateFlow<Boolean>,
    private val arbiter: WaArbiter,
    val ids: WaIdBook,
    private val onBusy: (String) -> Unit,
    scope: CoroutineScope,
    private val clock: () -> Long,
) : CallSource {
    private val meta = MutableStateFlow<Map<Int, WaCallMeta>>(emptyMap())
    private val activeAt = HashMap<String, Long>()
    private val declined = HashSet<String>()

    override val whatsapp: StateFlow<Map<Int, WaCallMeta>> = meta.asStateFlow()

    override val snapshot: StateFlow<CallSnapshot> =
        combine(base.snapshot, waCalls, muted, speaker) { snap, list, mute, loud -> Inputs(snap, list, mute, loud) }
            .mapLatest { merge(it) }
            .stateIn(scope, SharingStarted.Eagerly, base.snapshot.value)

    private class Inputs(
        val snapshot: CallSnapshot,
        val calls: List<CallSummary>,
        val muted: Set<String>,
        val speaker: Boolean,
    )

    /** The WhatsApp call id that is the foreground call right now, if any. */
    fun foregroundWaCallId(): String? =
        snapshot.value.foregroundCallId
            ?.takeIf(WaIdBook::isWa)
            ?.let(ids::callIdOf)

    /** The WhatsApp call that is live (not ringing for us), if any. */
    fun liveWaCallId(): String? =
        snapshot.value.calls
            .firstOrNull { WaIdBook.isWa(it.id) && it.state != WireCallState.Ringing }
            ?.let { ids.callIdOf(it.id) }

    private suspend fun merge(input: Inputs): CallSnapshot {
        val snap = input.snapshot
        val wa = pick(input.calls)
        val telecomTop = snap.calls.filter { it.parentId == null }
        val live = telecomTop.any { it.state in LIVE }
        val ringing = telecomTop.firstOrNull { it.state == WireCallState.Ringing }
        val decision = arbiter.decide(live, ringing != null, wa?.toUpdate())
        if (decision == WaDecision.RejectWaBusy && wa != null && declined.add(wa.callId)) onBusy(wa.callId)
        if (wa == null || decision == WaDecision.Telecom || decision == WaDecision.RejectWaBusy) {
            meta.value = emptyMap()
            return snap
        }
        val id = ids.idOf(wa.callId)
        val call = wireOf(id, wa)
        val info = WaCallMeta(wa.callId, wa.peer, wa.toUpdate(), input.speaker, wa.callId in input.muted)
        meta.value = mapOf(id to info)
        val calls = snap.calls + call
        return if (decision == WaDecision.WaWaiting) {
            snap.copy(calls = calls, waitingCallId = id)
        } else {
            snap.copy(
                calls = calls,
                foregroundCallId = id,
                waitingCallId = snap.waitingCallId ?: ringing?.id,
                audio =
                    snap.audio.copy(
                        muted = info.muted,
                        route = if (input.speaker) AudioRoute.Speaker else AudioRoute.Earpiece,
                        routes = listOf(AudioRoute.Earpiece, AudioRoute.Speaker),
                        endpoints = emptyList(),
                    ),
            )
        }
    }

    /** The WhatsApp call the screens show: a ringing incoming call first, else the newest live one. */
    private fun pick(list: List<CallSummary>): CallSummary? {
        val live = list.filter { it.phase !is CallPhase.Ended }
        return live.firstOrNull { it.direction == CallDirection.In && it.phase == CallPhase.Ringing }
            ?: live.maxByOrNull { it.startedMs }
    }

    private fun wireOf(
        id: Int,
        call: CallSummary,
    ): WireCall {
        val incoming = call.direction == CallDirection.In
        val ringingIn = incoming && call.phase == CallPhase.Ringing
        val state =
            when {
                ringingIn -> WireCallState.Ringing
                call.phase == CallPhase.Active -> WireCallState.Active
                incoming -> WireCallState.Active
                else -> WireCallState.Dialing
            }
        val connected = call.phase == CallPhase.Active
        val since = if (connected) activeAt.getOrPut(call.callId, clock) else 0L
        val number = dialNumber(call.peer)
        return WireCall(
            id = id,
            state = state,
            numberRaw = number.orEmpty(),
            normalized = number.orEmpty(),
            displayName = call.peerName?.trim()?.takeIf { it.isNotEmpty() },
            connectTimeMs = since,
            startedMs = call.startedMs,
            canHold = false,
            isIncoming = incoming,
        )
    }

    private fun CallSummary.toUpdate() = CallUpdate(callId, peer, peerName, direction, phase, isVideo)

    private companion object {
        val LIVE = setOf(WireCallState.Dialing, WireCallState.Active, WireCallState.Holding)
    }
}

/** `+<digits>` for a `<digits>[:device]@s.whatsapp.net` JID, null for anything else (`@lid`, groups). */
internal fun dialNumber(peer: String): String? =
    dev.rovsti.phone.data.RecentsRepository
        .dialNumber(peer)
