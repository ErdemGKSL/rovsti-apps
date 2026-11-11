package dev.rovsti.phone.incall

import dev.rovsti.core.model.phone.AudioRoute
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.Endpoint
import dev.rovsti.core.model.phone.RowKind
import dev.rovsti.core.model.phone.SceneAnswer
import dev.rovsti.core.model.phone.SceneControl
import dev.rovsti.core.model.phone.SceneLayout
import dev.rovsti.core.model.phone.ToneStep
import dev.rovsti.core.model.phone.WireCall
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.NumberDisplay
import dev.rovsti.phone.data.PlaceResult
import dev.rovsti.phone.data.Sims
import dev.rovsti.phone.incoming.CallSource
import dev.rovsti.phone.incoming.SimChip
import dev.rovsti.phone.incoming.WaCallMeta
import dev.rovsti.platform.files.ClipboardRepository
import dev.rovsti.platform.telephony.CallsApi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How long each tone of a paste sounds, the silence after it, and a `,` pause. */
internal const val TONE_ON_MS = 250L
internal const val TONE_GAP_MS = 100L
internal const val TONE_PAUSE_MS = 2_000L

/** The summary of a finished call stays this long, then the screen closes. */
internal const val ENDED_DWELL_MS = 2_000L

/** A call from an unknown number shorter than this offers Block afterwards. */
internal const val SHORT_CALL_S = 60L

private const val MAX_DIGITS = 64
private const val MS_PER_S = 1000L

/**
 * The decisions of the single-call In-Call screen (E5.7a). It maps the call snapshot to what is drawn ([state]) and each
 * tap to exactly one `CallsApi` command; the Rust core only supplies words and rules ([InCallBackend]). Audio routes
 * that need `BLUETOOTH_CONNECT` are hidden while it is not granted. When the call is gone the model keeps its last
 * state as an "ended" summary for [ENDED_DWELL_MS], offers the post-call pill once, then asks the screen to close.
 */
class InCallViewModel(
    private val source: CallSource,
    private val calls: CallsApi,
    private val backend: InCallBackend,
    private val notes: InCallNotes,
    private val blocker: InCallBlocker,
    private val clipboard: ClipboardRepository,
    private val tones: ToneMemory,
    private val display: NumberDisplay,
    private val sims: StateFlow<Sims>,
    private val bluetoothGranted: StateFlow<Boolean>,
    private val unknownLabel: String,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** Places the second call of the Add call sheet; null where no call may be placed (the sheet is then not offered). */
    private val placer: CallPlacer? = null,
    /** The name a conference call goes by. */
    private val conferenceLabel: String = "Conference call",
) {
    private val effects = Channel<InCallEffect>(Channel.BUFFERED)

    /** One-shot results: sheets, notices, haptics, the post-call pill, leaving. */
    val effect = effects.receiveAsFlow()

    private val keypad = MutableStateFlow(ToneKeypad())
    private val rows = MutableStateFlow(RouteRows())
    private val ended = MutableStateFlow<Held?>(null)
    private val noted = MutableStateFlow(0)

    private class RouteRows(
        val rows: List<RouteRowUi> = emptyList(),
        val opensList: Boolean = false,
    )

    /** The call as last seen live, kept for the ended summary. */
    private class Held(
        val call: InCallCall,
        val ui: EndedUi,
    )

    private val scene = MutableStateFlow<SceneAnswer?>(null)
    private var sceneJob: Job? = null
    private val waFacts = MutableStateFlow<WaControls?>(null)
    private var waKey: Any? = null

    /** The parts of the state that are not the snapshot itself: the ended summary and the WhatsApp facts. */
    private class Extra(
        val held: Held?,
        val whatsapp: Map<Int, WaCallMeta>,
        val controls: WaControls?,
        val scene: SceneAnswer?,
    )

    private var tracked: InCallCall? = null
    private var lastEndpoints: List<Endpoint>? = null
    private var endpointsKey: List<Endpoint>? = null
    private var sending: Job? = null
    private var clip: String? = null
    private val finished = HashSet<Int>()

    val state: StateFlow<InCallUi> =
        combine(
            source.snapshot,
            this.sims,
            keypad,
            combine(rows, bluetoothGranted) { r, bt -> filtered(r, bt) },
            combine(
                combine(ended, noted, display.version) { e, _, _ -> e },
                source.whatsapp,
                waFacts,
                scene,
            ) { e, wa, facts, sc -> Extra(e, wa, facts, sc) },
        ) { snapshot, simList, pad, routes, extra -> build(snapshot, simList, pad, routes, extra) }
            .stateIn(scope, SharingStarted.Eagerly, InCallUi())

    init {
        scope.launch {
            source.snapshot.collect {
                display.warm(it.calls.map { c -> c.numberRaw })
                observe(it)
                askScene(it)
            }
        }
        // The WhatsApp facts of the tracked call can arrive after (or change without) a snapshot.
        scope.launch {
            source.whatsapp.collect { meta ->
                // A call stays a WhatsApp call after its facts are gone (the ended summary and the post-call pill ask).
                tracked = tracked?.let { t -> meta[t.callId]?.let { t.copy(whatsapp = true, photoJid = it.jid) } ?: t }
                askWaControls(tracked?.let { meta[it.callId] })
            }
        }
    }

    fun onAction(action: InCallAction) {
        when (action) {
            InCallAction.ToggleMute -> {
                command { calls.mute(!state.value.muted) }
            }

            InCallAction.ToggleHold -> {
                toggleHold()
            }

            InCallAction.ToggleKeypad -> {
                toggleKeypad()
            }

            InCallAction.ToggleRoute -> {
                toggleRoute()
            }

            is InCallAction.PickRoute -> {
                if (!calls.routeEndpoint(action.id)) notice(InCallNotice.RouteFailed)
            }

            InCallAction.End -> {
                end()
            }

            InCallAction.Swap -> {
                command { calls.swap() }
            }

            InCallAction.Merge -> {
                command { calls.merge() }
            }

            is InCallAction.Split -> {
                command { calls.split(action.id) }
            }

            is InCallAction.Drop -> {
                command { calls.disconnect(action.id) }
            }

            InCallAction.EndAll -> {
                endAll()
            }

            InCallAction.OpenAddCall -> {
                openAddCall()
            }

            InCallAction.OpenConference -> {
                effects.trySend(InCallEffect.ShowConference)
            }

            is InCallAction.Dial -> {
                dial(action.number)
            }

            is InCallAction.Dimmed -> {
                effects.trySend(InCallEffect.Say(action.text))
            }

            InCallAction.Unavailable -> {
                notice(InCallNotice.WaUnavailable)
            }

            is InCallAction.Digit -> {
                digit(action.key)
            }

            InCallAction.DigitUp -> {
                calls.dtmfStop()
            }

            InCallAction.CopyDigits -> {
                copyDigits()
            }

            InCallAction.PasteTones -> {
                pasteTones()
            }

            InCallAction.StopTones -> {
                stopTones()
            }

            InCallAction.SendRemembered -> {
                sendRemembered()
            }

            InCallAction.OpenNote -> {
                openNote()
            }

            is InCallAction.SaveNote -> {
                saveNote(action.text)
            }

            InCallAction.CopyNumber -> {
                copyNumber()
            }

            is InCallAction.CopyTimer -> {
                clipboard.copy(action.text)
                notice(InCallNotice.TimerCopied)
            }

            is InCallAction.ShareSummary -> {
                effects.trySend(InCallEffect.Share(action.text))
            }
        }
    }

    /** The timer text for [seconds]; the screen calls it once a second. */
    suspend fun elapsed(seconds: Long): String = backend.elapsed(seconds)

    /** The wall clock the screen's timer counts from. */
    fun now(): Long = clock()

    // ---- The snapshot -------------------------------------------------------------------------------------------

    private fun liveOf(snapshot: CallSnapshot): WireCall? {
        val live = snapshot.calls.filter { it.parentId == null && it.state in LIVE }
        return live.firstOrNull { it.id == snapshot.foregroundCallId } ?: live.firstOrNull()
    }

    private fun build(
        snapshot: CallSnapshot,
        sims: Sims,
        pad: ToneKeypad,
        routes: RouteRows,
        extra: Extra,
    ): InCallUi {
        val held = extra.held
        val wire = liveOf(snapshot)
        val meta = wire?.let { extra.whatsapp[it.id] }
        val call = wire?.let { callOf(it, sims, meta) } ?: held?.call
        val whatsapp = if (call?.whatsapp == true) extra.controls ?: WaControls() else null
        return InCallUi(
            call = call,
            muted = snapshot.audio.muted,
            canHold = wire?.canHold == true,
            multi = multiOf(snapshot, wire, sims, extra),
            whatsapp = whatsapp,
            route = snapshot.audio.route,
            routeRows = routes.rows.toImmutableList(),
            routeOpensList = routes.opensList,
            keypad = if (call != null) pad else ToneKeypad(),
            hasNote = call != null && !call.whatsapp && notes.current(call.number, call.startedMs).isNotEmpty(),
            ended = held?.ui,
            locked = snapshot.keyguardLocked,
        )
    }

    /** The Held card and the conference rows from the core's scene; the one-call face while it has not answered. */
    private fun multiOf(
        snapshot: CallSnapshot,
        wire: WireCall?,
        sims: Sims,
        extra: Extra,
    ): MultiCallUi {
        val answer = extra.scene
        if (answer == null || wire == null || answer.primary?.id != wire.id) return MultiCallUi()
        val heldWire = answer.held?.let { h -> snapshot.calls.firstOrNull { it.id == h.id && it.state in LIVE } }
        val conference = answer.layout == SceneLayout.Conference
        val reason = { control: SceneControl -> answer.disabled.firstOrNull { it.control == control }?.text }
        val people =
            answer.participants.map { p ->
                val child = snapshot.calls.firstOrNull { it.id == p.id }
                ParticipantUi(
                    id = p.id,
                    // The core labels an unnamed leg with its raw number; the owner sees it in Display form (F22).
                    label = display.peek(p.label.trim()).ifEmpty { p.label },
                    avatarKey =
                        child?.contactId?.toString() ?: child?.displayName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: p.label,
                    canSplit = p.canSplit,
                    canDrop = p.canDrop,
                )
            }
        return MultiCallUi(
            held =
                heldWire?.let {
                    val call = callOf(it, sims, extra.whatsapp[it.id])
                    HeldCardUi(
                        it.id,
                        if (isConference(it)) conferenceLabel else call.title,
                        call.avatarKey,
                        isConference(it),
                    )
                },
            conference = conference,
            participants = people.toImmutableList(),
            summary = answer.participantsSummary,
            addCall = ControlFact(reason(SceneControl.AddCall)),
            merge = ControlFact(reason(SceneControl.Merge)),
            swap = ControlFact(reason(SceneControl.HoldSwap)),
        )
    }

    private fun isConference(call: WireCall) = call.children.isNotEmpty()

    private fun callOf(
        wire: WireCall,
        sims: Sims,
        wa: WaCallMeta?,
    ): InCallCall {
        val title =
            if (isConference(wire)) {
                conferenceLabel
            } else {
                wire.displayName?.trim()?.takeIf { it.isNotEmpty() }
            }
                ?: display.peek(wire.numberRaw.trim()).takeIf { it.isNotEmpty() }
                ?: unknownLabel
        val chip =
            wire.accountKey?.let { key ->
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
        return InCallCall(
            callId = wire.id,
            title = title,
            subtitle =
                display
                    .peek(
                        wire.numberRaw.trim(),
                    ).takeIf { it.isNotEmpty() && !wire.displayName.isNullOrBlank() },
            number = wire.numberRaw.trim(),
            avatarKey =
                wire.contactId?.toString() ?: wire.displayName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: wire.numberRaw.trim().ifEmpty { unknownLabel },
            contactId = wire.contactId,
            phase =
                when (wire.state) {
                    WireCallState.Active -> CallPhase.Active
                    WireCallState.Holding -> CallPhase.Holding
                    else -> CallPhase.Dialing
                },
            connectTimeMs = wire.connectTimeMs,
            startedMs = wire.startedMs.takeIf { it > 0 } ?: wire.connectTimeMs,
            sim = chip,
            incoming = wire.isIncoming,
            whatsapp = wa != null,
            photoJid = wa?.jid,
        )
    }

    private fun observe(snapshot: CallSnapshot) {
        val wire = liveOf(snapshot)
        if (wire != null) {
            val previous = tracked
            val meta = source.whatsapp.value[wire.id]
            tracked = callOf(wire, sims.value, meta)
            askWaControls(meta)
            if (previous?.callId != wire.id) {
                keypad.value = ToneKeypad()
                finished.clear()
            }
            routesFor(snapshot)
            return
        }
        val gone = tracked ?: return
        tracked = null
        if (!finished.add(gone.callId)) return
        finish(gone)
    }

    /** Asks the core what the WhatsApp call can do, again only when its phase, speaker or mute changed. */
    private fun askWaControls(meta: WaCallMeta?) {
        if (meta == null) {
            waKey = null
            return
        }
        val key = Triple(meta.update.phase, meta.speaker, meta.muted)
        if (key == waKey) return
        waKey = key
        scope.launch { backend.waControls(meta.update, meta.speaker, meta.muted)?.let { waFacts.value = it } }
    }

    /** Asks the core for the scene of [snapshot]; an older answer still in flight is dropped. */
    private fun askScene(snapshot: CallSnapshot) {
        sceneJob?.cancel()
        sceneJob =
            scope.launch {
                scene.value = if (snapshot.calls.any { it.state in LIVE }) backend.scene(snapshot) else null
            }
    }

    private fun routesFor(snapshot: CallSnapshot) {
        val endpoints = snapshot.audio.endpoints
        if (endpoints == endpointsKey) return
        endpointsKey = endpoints
        val previous = lastEndpoints
        lastEndpoints = endpoints
        scope.launch {
            val answer = backend.audioRows(endpoints, previous) ?: return@launch
            rows.value =
                RouteRows(
                    rows =
                        answer.rows.map {
                            RouteRowUi(it.id, it.kind, it.label, it.subtitle, it.selected, it.unavailable || it.ghost)
                        },
                    opensList = answer.decision == dev.rovsti.core.model.phone.RouteDecision.OpensList,
                )
        }
    }

    private fun filtered(
        r: RouteRows,
        bluetooth: Boolean,
    ): RouteRows {
        if (bluetooth) return r
        val kept = r.rows.filter { it.kind != RowKind.Bluetooth }
        return RouteRows(kept, opensList = r.opensList && kept.size > 2)
    }

    // ---- The end ------------------------------------------------------------------------------------------------

    private fun finish(call: InCallCall) {
        sending?.cancel()
        sending = null
        calls.dtmfStop()
        scope.launch {
            val connected = call.connectTimeMs > 0
            val talkS = if (connected) ((clock() - call.connectTimeMs) / MS_PER_S).coerceAtLeast(0) else 0
            val elapsed = if (connected) backend.elapsed(talkS) else ""
            val summary = EndedUi(call.callId, connected, talkS, elapsed)
            ended.value = Held(call.copy(phase = CallPhase.Ended), summary)
            if (!call.whatsapp) postCall(call, summary)
            delay(ENDED_DWELL_MS)
            ended.value = null
            effects.trySend(InCallEffect.Leave)
        }
    }

    /** One pill at most: Block for a short call from a stranger, else Add note after a connected call. */
    private suspend fun postCall(
        call: InCallCall,
        summary: EndedUi,
    ) {
        if (call.number.isBlank()) return
        val stranger = call.contactId == null
        if (call.incoming && stranger && summary.talkS < SHORT_CALL_S && blocker.canBlock(call.number)) {
            effects.trySend(post(PostCallKind.Block, call, summary))
        } else if (summary.connected && notes.current(call.number, call.startedMs).isEmpty()) {
            effects.trySend(post(PostCallKind.AddNote, call, summary))
        }
    }

    private fun post(
        kind: PostCallKind,
        call: InCallCall,
        summary: EndedUi,
    ) = InCallEffect.PostCall(kind, call.number, call.title, call.startedMs, summary.talkS, summary.elapsed)

    /** Blocks [number] (the pill's Block); the answer is the Undo, or null when it did not work. */
    suspend fun block(number: String): dev.rovsti.phone.data.BlockUndo? = blocker.block(number)

    suspend fun undoBlock(undo: dev.rovsti.phone.data.BlockUndo): Boolean = blocker.undo(undo)

    // ---- Commands -----------------------------------------------------------------------------------------------

    private fun command(run: () -> Boolean) {
        if (state.value.call == null || !state.value.live) return
        if (run()) effects.trySend(InCallEffect.Confirm) else notice(InCallNotice.CommandFailed)
    }

    private fun toggleHold() {
        // With a second call the Hold slot is Swap.
        if (state.value.multi.twoCalls) return onAction(InCallAction.Swap)
        val call = state.value.call ?: return
        if (!state.value.canHold && !state.value.held) return
        command { calls.hold(call.callId, !state.value.held) }
    }

    private fun end() {
        val call = state.value.call ?: return
        if (!state.value.live) return
        if (calls.disconnect(call.callId)) effects.trySend(InCallEffect.Reject) else notice(InCallNotice.CommandFailed)
    }

    private fun endAll() {
        if (!state.value.live) return
        if (calls.endAll()) effects.trySend(InCallEffect.Reject) else notice(InCallNotice.CommandFailed)
    }

    private fun openAddCall() {
        val reason = state.value.multi.addCall.reason
        if (reason != null) return onAction(InCallAction.Dimmed(reason))
        if (state.value.live && placer != null) effects.trySend(InCallEffect.ShowAddCall)
    }

    /** Places [number] as the next call, on the SIM the live call runs on (so "ask every time" never interrupts). */
    private fun dial(number: String) {
        val placer = placer ?: return
        val account = liveOf(source.snapshot.value)?.accountKey
        scope.launch {
            when (placer.place(number, account)) {
                is PlaceResult.Placed, is PlaceResult.HandedToDialer -> effects.trySend(InCallEffect.Confirm)
                else -> notice(InCallNotice.CommandFailed)
            }
        }
    }

    private fun toggleRoute() {
        val speaker = state.value.route == AudioRoute.Speaker
        command { calls.route(if (speaker) "earpiece" else "speaker") }
    }

    private fun notice(n: InCallNotice) {
        effects.trySend(InCallEffect.Notice(n))
    }

    // ---- The tone keypad ----------------------------------------------------------------------------------------

    private fun toggleKeypad() {
        val open = !keypad.value.open
        if (!open) {
            stopTones()
            keypad.update { it.copy(open = false) }
            return
        }
        val number =
            state.value.call
                ?.number
                .orEmpty()
        keypad.update { it.copy(open = true, remembered = tones.get(number)) }
        // The clipboard is read once, when the keypad opens, never per frame.
        scope.launch {
            clip = clipboard.primaryText()
            val label = backend.pasteLabel(clip)
            keypad.update { it.copy(pasteLabel = label) }
        }
    }

    private fun digit(key: Char) {
        if (!state.value.live) return
        stopTones()
        calls.dtmf(key)
        append(key)
    }

    private fun append(key: Char) {
        keypad.update { it.copy(digits = (it.digits + key).takeLast(MAX_DIGITS)) }
    }

    private fun copyDigits() {
        val digits = keypad.value.digits
        if (digits.isEmpty()) return
        clipboard.copy(digits, sensitive = true)
        notice(InCallNotice.TonesCopied)
    }

    private fun pasteTones() {
        val text = clip ?: return
        play(text, remember = true)
    }

    private fun sendRemembered() {
        val text = keypad.value.remembered ?: return
        play(text, remember = false)
    }

    private fun play(
        text: String,
        remember: Boolean,
    ) {
        stopTones()
        val number =
            state.value.call
                ?.number
                .orEmpty()
        keypad.update { it.copy(sending = true) }
        sending =
            scope.launch {
                val plan = backend.tonePlan(text)
                for (step in plan) {
                    if (!isActive || !state.value.live) break
                    when (step) {
                        ToneStep.Pause -> {
                            delay(TONE_PAUSE_MS)
                        }

                        is ToneStep.Tone -> {
                            calls.dtmf(step.key)
                            append(step.key)
                            delay(TONE_ON_MS)
                            calls.dtmfStop()
                            delay(TONE_GAP_MS)
                        }
                    }
                }
                keypad.update { it.copy(sending = false) }
                if (isActive && plan.isNotEmpty()) {
                    if (remember) tones.put(number, text)
                    notice(InCallNotice.TonesSent)
                }
            }
    }

    private fun stopTones() {
        val job = sending ?: return
        sending = null
        job.cancel()
        calls.dtmfStop()
        keypad.update { it.copy(sending = false) }
    }

    // ---- Note, number -------------------------------------------------------------------------------------------

    private fun openNote() {
        val call = state.value.call ?: return
        if (call.number.isBlank()) return notice(InCallNotice.NoNumber)
        effects.trySend(
            InCallEffect.ShowNote(call.number, call.startedMs, call.title, notes.current(call.number, call.startedMs)),
        )
    }

    /** Saves the note for [number] at [startedMs]; also used by the post-call "Add note" sheet. */
    private fun saveNote(text: String) {
        val call = state.value.call ?: tracked ?: return
        save(call.number, call.startedMs, text)
    }

    fun save(
        number: String,
        startedMs: Long,
        text: String,
    ) {
        scope.launch {
            if (notes.save(number, startedMs, text.trim())) {
                noted.update { it + 1 }
                notice(InCallNotice.NoteSaved)
            } else {
                effects.trySend(InCallEffect.Reject)
                notice(InCallNotice.NoteFailed)
            }
        }
    }

    private fun copyNumber() {
        val number =
            state.value.call
                ?.number
                .orEmpty()
        if (number.isBlank()) return notice(InCallNotice.NoNumber)
        clipboard.copy(number)
        notice(InCallNotice.NumberCopied)
    }

    private companion object {
        val LIVE = setOf(WireCallState.Dialing, WireCallState.Active, WireCallState.Holding)
    }
}
