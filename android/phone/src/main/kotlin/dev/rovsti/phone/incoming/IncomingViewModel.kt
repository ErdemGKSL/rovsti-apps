package dev.rovsti.phone.incoming

import dev.rovsti.core.model.chat.ReminderSlot
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.WireCall
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.phone.data.Sims
import dev.rovsti.platform.notify.ReminderItem
import dev.rovsti.platform.notify.RemindersRepository
import dev.rovsti.platform.telephony.CallsApi
import dev.rovsti.platform.telephony.RejectPolicy
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * The decisions of the Incoming screen and of the waiting-call card (E5.6). It maps the call snapshot to what is drawn
 * ([state]) and each tap to exactly one `CallsApi` call; the Rust core only supplies words and time slots
 * ([IncomingBackend]). Answer is honoured once per call (a double tap, or a tap after the notification's Answer, does
 * nothing), and a call that is gone makes every command answer "stale" instead of acting on another call.
 */
class IncomingViewModel(
    private val source: CallSource,
    private val calls: CallsApi,
    private val backend: IncomingBackend,
    private val reminders: RemindersRepository<ReminderItem>,
    sims: StateFlow<Sims>,
    private val savedReplies: () -> List<String>,
    private val unknownLabel: String,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val tzOffsetS: () -> Int = { TimeZone.getDefault().getOffset(clock()) / MS_PER_S },
) {
    private val effects = Channel<IncomingEffect>(Channel.BUFFERED)

    /** One-shot results: sheets to show, notices, haptics, leaving the screen. */
    val effect = effects.receiveAsFlow()

    val state: StateFlow<IncomingUi> =
        combine(source.snapshot, sims) { snapshot, sims -> build(snapshot, sims) }
            .stateIn(scope, SharingStarted.Eagerly, build(source.snapshot.value, sims.value))

    private val answered = HashSet<Int>()
    private val framed = HashSet<Int>()
    private var lastRinging: Int? = null
    private var seen = false

    init {
        scope.launch { source.snapshot.collect(::observe) }
    }

    fun onAction(action: IncomingAction) {
        when (action) {
            IncomingAction.Answer -> {
                ringingId()?.let { answer(it) } ?: stale()
            }

            IncomingAction.Decline -> {
                ringingId()?.let { decline(it) } ?: stale()
            }

            IncomingAction.OpenReplies -> {
                ringingId()?.let { openReplies(it) } ?: stale()
            }

            is IncomingAction.Reply -> {
                reply(action.callId, action.text)
            }

            is IncomingAction.ReplyCustom -> {
                compose(action.callId)
            }

            IncomingAction.OpenReminders -> {
                openReminders()
            }

            is IncomingAction.RemindAt -> {
                remind(action.slot)
            }

            IncomingAction.Silence -> {
                calls.silenceRinger()
            }

            is IncomingAction.FirstFrame -> {
                if (framed.add(action.callId)) calls.incomingFrame(action.callId)
            }

            IncomingAction.EndAndAnswer -> {
                waitingId()?.let { act(calls.answerReplacing(it)) } ?: stale()
            }

            IncomingAction.HoldAndAnswer -> {
                holdAndAnswer()
            }

            IncomingAction.DeclineWaiting -> {
                waitingId()?.let { decline(it) } ?: stale()
            }

            IncomingAction.OpenWaitingReplies -> {
                waitingId()?.let { openReplies(it) } ?: stale()
            }
        }
    }

    // ---- The snapshot -------------------------------------------------------------------------------------------

    private fun build(
        snapshot: CallSnapshot,
        sims: Sims,
    ): IncomingUi {
        val ringing = ringingOf(snapshot)
        val waiting = waitingOf(snapshot)
        val chip =
            ringing?.accountKey?.let { key ->
                if (sims.visible) {
                    sims.chips.firstOrNull { it.key == key }?.let {
                        SimChip(
                            it.chip,
                            it.colorArgb,
                        )
                    }
                } else {
                    null
                }
            }
        val relabelled = snapshot.backgroundCallId != null
        val live = snapshot.calls.firstOrNull { it.id == snapshot.foregroundCallId }
        return IncomingUi(
            caller = ringing?.let { caller(it, chip) },
            waiting =
                waiting?.let {
                    WaitingUi(
                        callId = it.id,
                        title = titleOf(it),
                        subtitle = subtitleOf(it),
                        avatarKey = keyOf(it),
                        relabelled = relabelled,
                        canHold = relabelled || live?.canHold == true,
                        canMessage = it.numberRaw.isNotBlank(),
                    )
                },
            locked = snapshot.keyguardLocked,
            silenced = snapshot.ringerSilenced,
            canMessage = ringing?.numberRaw?.isNotBlank() == true,
        )
    }

    private fun caller(
        call: WireCall,
        sim: SimChip?,
    ) = IncomingCaller(call.id, titleOf(call), subtitleOf(call), keyOf(call), call.contactId, sim)

    private fun titleOf(call: WireCall): String =
        call.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: call.numberRaw.trim().takeIf { it.isNotEmpty() }
            ?: unknownLabel

    private fun subtitleOf(call: WireCall): String? =
        call.numberRaw.trim().takeIf { it.isNotEmpty() && !call.displayName.isNullOrBlank() }

    private fun keyOf(call: WireCall): String = call.contactId?.toString() ?: titleOf(call)

    private fun ringingOf(snapshot: CallSnapshot): WireCall? =
        snapshot.calls.firstOrNull {
            it.state == WireCallState.Ringing && it.parentId == null && it.id != snapshot.waitingCallId
        }

    private fun waitingOf(snapshot: CallSnapshot): WireCall? =
        snapshot.waitingCallId?.let { id ->
            snapshot.calls.firstOrNull {
                it.id == id &&
                    it.state == WireCallState.Ringing
            }
        }

    /** Tells the screen when the ringing call is gone: answered (open In-Call) or ended (close). */
    private fun observe(snapshot: CallSnapshot) {
        val ringing = ringingOf(snapshot)?.id
        val previous = lastRinging
        val first = !seen
        seen = true
        lastRinging = ringing
        if (ringing != null) return
        if (previous == null && !first) return
        val gone = previous?.let { id -> snapshot.calls.firstOrNull { it.id == id } }
        val live =
            if (previous == null) {
                snapshot.calls.any { it.state == WireCallState.Active || it.state == WireCallState.Holding }
            } else {
                gone?.state in LIVE
            }
        effects.trySend(IncomingEffect.Leave(toCall = live))
    }

    private fun ringingId(): Int? = state.value.caller?.callId

    private fun waitingId(): Int? = state.value.waiting?.callId

    // ---- Commands -----------------------------------------------------------------------------------------------

    private fun answer(id: Int) {
        if (!answered.add(id)) return
        if (calls.answer(id)) effects.trySend(IncomingEffect.Confirm) else stale()
    }

    private fun decline(id: Int) {
        if (calls.reject(id)) effects.trySend(IncomingEffect.Reject) else stale()
    }

    private fun act(done: Boolean) {
        if (done) effects.trySend(IncomingEffect.Confirm) else stale()
    }

    private fun holdAndAnswer() {
        val waiting = state.value.waiting ?: return stale()
        if (!waiting.canHold) {
            effects.trySend(IncomingEffect.Reject)
            return
        }
        act(if (waiting.relabelled) calls.answerEndingHeld(waiting.callId) else calls.answerHolding(waiting.callId))
    }

    private fun openReplies(id: Int) {
        scope.launch {
            val replies = backend.replyChoices(savedReplies())
            effects.trySend(
                IncomingEffect.ShowReplies(
                    id,
                    replies?.choices.orEmpty().toImmutableList(),
                    replies?.maxChars ?: RejectPolicy.MAX_CHARS,
                ),
            )
        }
    }

    private fun reply(
        id: Int,
        text: String,
    ) {
        when (calls.rejectWithMessage(id, text)) {
            RejectPolicy.SENT, RejectPolicy.OPENED -> effects.trySend(IncomingEffect.Reject)
            RejectPolicy.REJECTED_ONLY -> effects.trySend(IncomingEffect.Notice(IncomingNotice.DeclinedOnly))
            else -> stale()
        }
    }

    private fun compose(id: Int) {
        if (calls.rejectAndCompose(id)) effects.trySend(IncomingEffect.Reject) else stale()
    }

    private fun openReminders() {
        if (ringingId() == null) return stale()
        scope.launch {
            val slots = backend.reminderSlots(clock(), tzOffsetS())
            effects.trySend(
                if (slots.isEmpty()) {
                    IncomingEffect.Notice(IncomingNotice.ReminderFailed)
                } else {
                    IncomingEffect.ShowReminders(slots.toImmutableList())
                },
            )
        }
    }

    /** "Remind me": the reminder is armed first, then the call is declined, so a failed reminder keeps ringing. */
    private fun remind(slot: ReminderSlot) {
        val caller = state.value.caller ?: return stale()
        val number =
            source.snapshot.value.calls
                .firstOrNull { it.id == caller.callId }
                ?.numberRaw
                .orEmpty()
        scope.launch {
            val name = caller.subtitle?.let { caller.title }
            val made = backend.reminder(number.ifBlank { caller.title }, name, slot, clock(), tzOffsetS())
            if (made != null && reminders.arm(made.armJson)) {
                calls.reject(caller.callId)
                effects.trySend(IncomingEffect.Notice(IncomingNotice.ReminderSet, made.setText))
                effects.trySend(IncomingEffect.Reject)
            } else {
                effects.trySend(IncomingEffect.Notice(IncomingNotice.ReminderFailed))
            }
        }
    }

    private fun stale() {
        effects.trySend(IncomingEffect.Notice(IncomingNotice.Stale))
    }

    private companion object {
        const val MS_PER_S = 1000
        val LIVE = setOf(WireCallState.Active, WireCallState.Holding, WireCallState.Dialing)
    }
}
