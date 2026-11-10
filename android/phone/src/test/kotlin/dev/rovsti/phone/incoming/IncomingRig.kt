package dev.rovsti.phone.incoming

import dev.rovsti.core.model.chat.CallReminder
import dev.rovsti.core.model.chat.CallReminderAnswer
import dev.rovsti.core.model.chat.ReminderSlot
import dev.rovsti.core.model.chat.ReminderSlotOption
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.WireCall
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.NumberDisplay
import dev.rovsti.phone.data.Sims
import dev.rovsti.platform.notify.ReminderItem
import dev.rovsti.platform.notify.RemindersRepository
import dev.rovsti.platform.telephony.CallsApi
import dev.rovsti.platform.telephony.RejectPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

internal fun ringing(
    id: Int = 1,
    name: String? = "Ayse Yilmaz",
    number: String = "+905551112233",
    account: String? = null,
    contactId: Long? = 7,
) = WireCall(
    id = id,
    state = WireCallState.Ringing,
    numberRaw = number,
    displayName = name,
    contactId = contactId,
    isIncoming = true,
    accountKey = account,
)

internal fun active(
    id: Int = 2,
    canHold: Boolean = true,
    state: WireCallState = WireCallState.Active,
) = WireCall(id = id, state = state, numberRaw = "+905559990000", displayName = "Mert Kaya", canHold = canHold)

/** A snapshot with one ringing call. */
internal fun ringingSnapshot(
    call: WireCall = ringing(),
    locked: Boolean = false,
    silenced: Boolean = false,
) = CallSnapshot(
    calls = listOf(call),
    foregroundCallId = call.id,
    keyguardLocked = locked,
    ringerSilenced = silenced,
)

/** A live call and a second caller waiting; [held] adds a held call, which relabels Hold & Answer. */
internal fun waitingSnapshot(
    held: Boolean = false,
    canHold: Boolean = true,
) = CallSnapshot(
    calls =
        listOfNotNull(
            active(2, canHold),
            ringing(3, "Deniz Aksoy", "+905553334455", contactId = 9),
            if (held) active(4, state = WireCallState.Holding) else null,
        ),
    foregroundCallId = 2,
    waitingCallId = 3,
    backgroundCallId = if (held) 4 else null,
)

internal class FakeSource(
    first: CallSnapshot = CallSnapshot(),
) : CallSource {
    private val flow = MutableStateFlow(first)
    private val wa = MutableStateFlow<Map<Int, WaCallMeta>>(emptyMap())
    override val snapshot: StateFlow<CallSnapshot> = flow.asStateFlow()
    override val whatsapp: StateFlow<Map<Int, WaCallMeta>> = wa.asStateFlow()

    fun set(snapshot: CallSnapshot) {
        flow.value = snapshot
    }

    /** Marks calls of the snapshot as WhatsApp calls (what the merged source would say). */
    fun setWhatsApp(meta: Map<Int, WaCallMeta>) {
        wa.value = meta
    }
}

/** Remembers every `CallsApi` command the screen sends. */
internal class RecordingCalls : CallsApi {
    val log = mutableListOf<String>()
    var accept = true
    var rejectCode = RejectPolicy.SENT

    override fun snapshot() = "{}"

    override fun answer(callId: Int) = record("answer $callId")

    override fun reject(callId: Int) = record("reject $callId")

    override fun disconnect(callId: Int) = record("disconnect $callId")

    override fun hold(
        callId: Int,
        on: Boolean,
    ) = record("hold $callId $on")

    override fun mute(on: Boolean) = record("mute $on")

    override fun route(name: String) = record("route $name")

    override fun dtmf(digit: Char) = record("dtmf $digit")

    override fun dtmfStop() = record("dtmfStop")

    override fun answerReplacing(callId: Int) = record("answerReplacing $callId")

    override fun answerHolding(callId: Int) = record("answerHolding $callId")

    override fun answerEndingHeld(callId: Int) = record("answerEndingHeld $callId")

    override fun incomingFrame(callId: Int) = record("incomingFrame $callId")

    override fun silenceRinger() = record("silence")

    override fun endAll() = record("endAll")

    override fun rejectAndCompose(callId: Int) = record("compose $callId")

    override fun rejectWithMessage(
        callId: Int,
        text: String,
    ): Int {
        log += "reply $callId $text"
        return rejectCode
    }

    private fun record(line: String): Boolean {
        log += line
        return accept
    }
}

internal class FakeBackend(
    var replies: ReplyChoices? = ReplyChoices(listOf("Can't talk now.", "I'll call you back."), 160),
    var slots: List<ReminderSlotOption> =
        listOf(
            ReminderSlotOption(ReminderSlot.In15Min, "In 15 minutes", 900_000),
            ReminderSlotOption(ReminderSlot.In1Hour, "In 1 hour", 3_600_000),
        ),
    var made: CallReminderAnswer? = answer(),
) : IncomingBackend {
    var savedAsked: List<String>? = null
    var reminderFor: Triple<String, String?, ReminderSlot>? = null

    override suspend fun replyChoices(saved: List<String>): ReplyChoices? {
        savedAsked = saved
        return replies
    }

    override suspend fun reminderSlots(
        nowMs: Long,
        tzOffsetS: Int,
    ) = slots

    override suspend fun reminder(
        number: String,
        name: String?,
        slot: ReminderSlot,
        nowMs: Long,
        tzOffsetS: Int,
    ): CallReminderAnswer? {
        reminderFor = Triple(number, name, slot)
        return made
    }

    companion object {
        fun answer() =
            CallReminderAnswer(
                reminder = CallReminder(5, "+905551112233", "Ayse Yilmaz", 900_000, 0, false),
                armJson = """{"id":5}""",
                title = "Call back Ayse Yilmaz",
                body = "+90 555 111 22 33",
                setText = "Reminder at 18:05",
                atClock = "18:05",
            )
    }
}

internal class FakeReminders(
    var accept: Boolean = true,
) : RemindersRepository<ReminderItem> {
    val armed = mutableListOf<String>()

    override fun arm(json: String): Boolean {
        if (accept) armed += json
        return accept
    }

    override fun disarm(id: Long) = true

    override fun armed(): Flow<List<ReminderItem>> = emptyFlow()

    override fun rearmAll() = true
}

/** A model over fakes, with its effects collected into [effects] as they happen (Unconfined, so no waiting). */
internal class IncomingHarness(
    first: CallSnapshot = ringingSnapshot(),
    sims: Sims = Sims(),
    keepInitial: Boolean = false,
    display: NumberDisplay = NumberDisplay.AsIs,
) {
    val source = FakeSource(first)
    val calls = RecordingCalls()
    val backend = FakeBackend()
    val reminders = FakeReminders()
    val effects = mutableListOf<IncomingEffect>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    val model =
        IncomingViewModel(
            source = source,
            calls = calls,
            backend = backend,
            reminders = reminders,
            display = display,
            sims = MutableStateFlow(sims),
            savedReplies = { listOf("saved") },
            unknownLabel = "Unknown",
            scope = scope,
            clock = { NOW },
            tzOffsetS = { 0 },
        ).also { m -> scope.launch { m.effect.collect { effects += it } } }

    init {
        // What the first snapshot said (Leave, for a call-less start) is only wanted by the tests that ask for it.
        if (!keepInitial) effects.clear()
    }

    val ui get() = model.state.value

    companion object {
        const val NOW = 1_700_000_000_000L
    }
}

/** The Rust core's Display form with home "90", learned for every number in [snapshot] (so a built state is formatted). */
internal fun hostDisplay(snapshot: CallSnapshot): NumberDisplay {
    val display = NumberDisplay(NativeSharedOps(HostRust("rovsti-incoming-shot").core)) { "90" }
    kotlinx.coroutines.runBlocking { display.warm(snapshot.calls.map { it.numberRaw }) }
    return display
}
