package dev.rovsti.phone.wa

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.CanPlaceRequest
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.platform.telephony.CallsApi
import dev.rovsti.platform.telephony.RejectPolicy
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.calls.CallResult
import dev.rovsti.wa.calls.WaCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Something about a WhatsApp call the user must be told: the screens turn it into a toast. */
sealed interface WaNotice {
    /** `RECORD_AUDIO` is missing: the app asks for it and retries ([WaCallLauncher.retry]). */
    data object NeedsMicrophone : WaNotice

    /** The gateway refused or could not be reached. */
    data object Failed : WaNotice

    /** The core's sentence for a call that cannot start (not connected, busy, no number). */
    data class Refused(
        val text: String,
    ) : WaNotice
}

/** Where [WaNotice]s are said; one per container. */
class WaNotices {
    private val bus =
        MutableSharedFlow<WaNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<WaNotice> = bus.asSharedFlow()

    fun say(notice: WaNotice) {
        bus.tryEmit(notice)
    }
}

/**
 * `CallsApi` for the call screens when WhatsApp calls exist: a command on a WhatsApp call (an id from [WaIdBook]) goes to
 * [WaCalls], everything else to the registry. The commands are suspend on the WhatsApp side, so they run on [scope] and
 * answer true at once (the call's own events show what happened, a failure is a [WaNotice]).
 *
 * The two arbitration answers of the waiting card:
 * - "End & Answer" on a waiting WhatsApp call ends the cellular calls, then answers it;
 * - "End & Answer" on a waiting cellular call ends the WhatsApp call, then answers the cellular one.
 * Hold & Answer never applies to WhatsApp (the gateway carries one audio stream), so it answers false there.
 */
class WaCallsRouter(
    private val registry: CallsApi,
    private val waCalls: WaCalls,
    private val source: WaMergedCallSource,
    private val notices: WaNotices,
    private val scope: CoroutineScope,
) : CallsApi by registry {
    private fun waId(id: Int): String? = if (WaIdBook.isWa(id)) source.ids.callIdOf(id) else null

    override fun answer(callId: Int): Boolean {
        val wa = waId(callId) ?: return registry.answer(callId)
        scope.launch { report(waCalls.accept(wa)) }
        return true
    }

    override fun reject(callId: Int): Boolean {
        val wa = waId(callId) ?: return registry.reject(callId)
        scope.launch { waCalls.reject(wa) }
        return true
    }

    override fun disconnect(callId: Int): Boolean {
        val wa = waId(callId) ?: return registry.disconnect(callId)
        scope.launch { waCalls.end(wa) }
        return true
    }

    override fun mute(on: Boolean): Boolean {
        val wa = source.foregroundWaCallId() ?: return registry.mute(on)
        scope.launch { waCalls.mute(wa, on) }
        return true
    }

    override fun route(name: String): Boolean {
        if (source.foregroundWaCallId() == null) return registry.route(name)
        return waCalls.speaker(name == SPEAKER)
    }

    override fun routeEndpoint(id: String): Boolean = source.foregroundWaCallId() == null && registry.routeEndpoint(id)

    override fun hold(
        callId: Int,
        on: Boolean,
    ): Boolean = waId(callId) == null && registry.hold(callId, on)

    override fun dtmf(digit: Char): Boolean = source.foregroundWaCallId() == null && registry.dtmf(digit)

    override fun dtmfStop(): Boolean = source.foregroundWaCallId() == null && registry.dtmfStop()

    override fun answerReplacing(callId: Int): Boolean {
        val wa = waId(callId)
        if (wa != null) {
            // The cellular calls end first; the WhatsApp offer is answered once they are gone.
            registry.endAll()
            scope.launch { report(waCalls.accept(wa)) }
            return true
        }
        val live = source.liveWaCallId() ?: return registry.answerReplacing(callId)
        scope.launch { waCalls.end(live) }
        return registry.answer(callId)
    }

    override fun answerHolding(callId: Int): Boolean =
        waId(callId) == null && source.liveWaCallId() == null && registry.answerHolding(callId)

    override fun answerEndingHeld(callId: Int): Boolean =
        waId(callId) == null && source.liveWaCallId() == null && registry.answerEndingHeld(callId)

    override fun rejectWithMessage(
        callId: Int,
        text: String,
    ): Int {
        if (waId(callId) == null) return registry.rejectWithMessage(callId, text)
        return if (reject(callId)) RejectPolicy.REJECTED_ONLY else RejectPolicy.STALE
    }

    override fun rejectAndCompose(callId: Int): Boolean =
        if (waId(callId) ==
            null
        ) {
            registry.rejectAndCompose(callId)
        } else {
            reject(callId)
        }

    override fun silenceRinger(): Boolean = registry.silenceRinger()

    override fun incomingFrame(callId: Int): Boolean = waId(callId) == null && registry.incomingFrame(callId)

    private fun report(result: CallResult) {
        when (result) {
            is CallResult.Started -> Unit
            CallResult.NeedsMicrophone -> notices.say(WaNotice.NeedsMicrophone)
            CallResult.NoGateway, is CallResult.Failed -> notices.say(WaNotice.Failed)
        }
    }

    private companion object {
        const val SPEAKER = "speaker"
    }
}

/**
 * Places WhatsApp calls (Recents row menu, the person sheets, the keypad's call menu, a sibling app's hand-off). The core
 * checks the rules ([PhoneOps.canPlace]: linked, not busy, a number); [WaCalls.place] needs the microphone and says so
 * with [WaNotice.NeedsMicrophone], after which the app asks for it and calls [retry].
 */
class WaCallLauncher(
    private val waCalls: WaCalls,
    link: StateFlow<WaLinkState>,
    private val ops: PhoneOps,
    private val busy: () -> Boolean,
    private val e164: suspend (String) -> String?,
    private val notices: WaNotices,
    private val scope: CoroutineScope,
) {
    /** WhatsApp calls can be placed: the instance is linked. The menus enable their WhatsApp row from this. */
    val available: StateFlow<Boolean> =
        link.map { it is WaLinkState.Connected }.stateIn(
            scope,
            SharingStarted.Eagerly,
            link.value is WaLinkState.Connected,
        )

    private var waiting: String? = null

    fun place(number: String) {
        scope.launch { run(number) }
    }

    /** The microphone was granted: places the call that asked for it. */
    fun retry() {
        val number = waiting ?: return
        waiting = null
        place(number)
    }

    private suspend fun run(number: String) {
        val full = e164(number)?.takeIf { it.isNotBlank() } ?: number
        val check = ops.canPlace(CanPlaceRequest(available.value, busy(), full))
        if (check is Outcome.Err) {
            notices.say(WaNotice.Refused(check.error.message.orEmpty()))
            return
        }
        when (val result = waCalls.place(jidOf(full))) {
            is CallResult.Started -> {
                waiting = null
            }

            CallResult.NeedsMicrophone -> {
                waiting = number
                notices.say(WaNotice.NeedsMicrophone)
            }

            CallResult.NoGateway, is CallResult.Failed -> {
                notices.say(WaNotice.Failed)
            }
        }
    }

    companion object {
        /** The JID of a phone number: its digits at the WhatsApp server. */
        fun jidOf(number: String): String = number.filter { it in '0'..'9' } + "@s.whatsapp.net"
    }
}
