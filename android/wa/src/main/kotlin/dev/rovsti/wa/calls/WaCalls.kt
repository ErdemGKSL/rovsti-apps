package dev.rovsti.wa.calls

import android.content.Context
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.ops.CallAudio
import dev.rovsti.core.ops.WaGateway
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.wa.CallFrames
import dev.rovsti.platform.wa.WaCallAudio
import dev.rovsti.platform.wa.WaCallService
import dev.rovsti.platform.wa.asFrames
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.WaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Microphone and speaker of a running call: [WaCallAudio] on a device, a fake in tests. */
interface CallAudioPump {
    /**
     * Gets the process ready to capture before the call connects: called while the user answers or places the call, so
     * with the app in the foreground (Android 14 starts a while-in-use microphone service only then). [stop] undoes it.
     */
    fun prepare() = Unit

    /** Starts the capture and playback threads over [frames]. False when the recorder or track cannot start. */
    fun start(frames: CallFrames): Boolean

    /** Stops both threads, releases the audio devices and undoes [prepare]. False when nothing was running. */
    fun stop(): Boolean

    /** Loudspeaker or earpiece; false when the device has no such route. */
    fun setSpeaker(on: Boolean): Boolean
}

/**
 * The real pump: `WaCallAudio` with the voice-communication attributes ported in R1, under the microphone foreground
 * service `WaCallService`.
 *
 * Order matters on Android 14+: a capture that starts while the app counts as background reads exact zeros, and a
 * while-in-use microphone service may only be started while the app is in the foreground. So the service starts at
 * [prepare] (the user is answering or calling, the app is in front), again (a no-op when running) **before** the
 * recorder in [start], and stops **after** the recorder in [stop]. The four seams exist for the order test.
 */
class AndroidCallAudioPump(
    context: Context,
    private val startService: (Context) -> Boolean = WaCallService::start,
    private val stopService: (Context) -> Unit = WaCallService::stop,
    private val startAudio: (Context, CallFrames) -> Boolean = WaCallAudio::start,
    private val stopAudio: (Context) -> Boolean = WaCallAudio::stop,
) : CallAudioPump {
    private val context = context.applicationContext

    override fun prepare() {
        startService(context)
    }

    override fun start(frames: CallFrames): Boolean {
        startService(context)
        return startAudio(context, frames)
    }

    override fun stop(): Boolean {
        val was = stopAudio(context)
        stopService(context)
        return was
    }

    override fun setSpeaker(on: Boolean): Boolean = WaCallAudio.setSpeaker(context, on)
}

/** The ids of deleted calls, so a reload from the gateway does not bring them back (`wa-calls-forgotten.json` in Rust). */
interface ForgottenCalls {
    fun ids(): Set<String>

    fun add(ids: Collection<String>)

    companion object {
        /** Forgets nothing across restarts: tests and the catalog. */
        fun inMemory(): ForgottenCalls =
            object : ForgottenCalls {
                private val kept = HashSet<String>()

                @Synchronized
                override fun ids(): Set<String> = kept.toSet()

                @Synchronized
                override fun add(ids: Collection<String>) {
                    kept += ids
                }
            }
    }
}

/** What [WaCalls.place] and [WaCalls.accept] answer. */
sealed interface CallResult {
    /** The call is placed or accepted; the pump starts now (accept) or when the call becomes active (place). */
    data class Started(
        val callId: String,
    ) : CallResult

    /** `RECORD_AUDIO` is not granted: nothing was sent to the gateway and no recorder was created. */
    data object NeedsMicrophone : CallResult

    /** There is no linked gateway. */
    data object NoGateway : CallResult

    data class Failed(
        val error: RustError,
    ) : CallResult
}

/**
 * The WhatsApp calls of one app (AD-WA5). The gateway runs the call; this class drives it and moves the audio.
 *
 * - [calls] is the list from `wa.calls`, kept current by the `call` gateway events (newest first, ended calls stay
 *   until the next reload). It reloads on a `resync`.
 * - [place] and [accept] need `RECORD_AUDIO` ([microphone] is the `microphone` group of the `PermissionController`);
 *   without it they answer [CallResult.NeedsMicrophone] and nothing starts.
 * - The pump runs for one call at a time and only once the call is connected (phase `Active`, both directions): it opens `gateway.openCallAudio(id)`, hands its [asFrames] to [pump] and
 *   stops, closing the handle, on [end], [reject], when the call's phase is `Ended`, or on [close].
 */
class WaCalls(
    private val repo: WaRepository,
    private val microphone: StateFlow<PermissionState>,
    private val pump: CallAudioPump,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val forgotten: ForgottenCalls = ForgottenCalls.inMemory(),
    private val ringOver: (String) -> Unit = {},
) {
    private val list = MutableStateFlow<List<CallSummary>>(emptyList())

    /** The recent calls, newest first. */
    val calls: StateFlow<List<CallSummary>> = list.asStateFlow()

    private val lock = Mutex()
    private var pumped: String? = null
    private var prepared: String? = null

    /** Calls answered here: a late `Ringing` event for one of them must not ring the screen again. */
    private val answered = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private var audio: CallAudio? = null
    private var started = false
    private val muted = MutableStateFlow<Set<String>>(emptySet())

    /** Calls whose microphone is muted. */
    val mutedCalls: StateFlow<Set<String>> = muted.asStateFlow()

    private val loudspeaker = MutableStateFlow(false)

    /** Whether the loudspeaker was asked for. */
    val speakerOn: StateFlow<Boolean> = loudspeaker.asStateFlow()

    /** Loads the calls and follows the events. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch { repo.events.collect(::onEvent) }
        scope.launch { reload() }
        // The link came back (a reconnect, a relink): the calls that rang or ended meanwhile are read again.
        scope.launch {
            repo.linkState
                .map { it is WaLinkState.Connected }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (it) reload() }
        }
    }

    /** Deletes [ids] from the list and remembers them, so the next reload keeps them away (Recents Delete). */
    fun forget(ids: Collection<String>) {
        if (ids.isEmpty()) return
        forgotten.add(ids)
        list.value = list.value.filterNot { it.callId in ids }
    }

    /** Places a call to [jid]. */
    suspend fun place(
        jid: String,
        video: Boolean = false,
    ): CallResult {
        if (microphone.value != PermissionState.Granted) return CallResult.NeedsMicrophone
        val gateway = repo.gateway() ?: return CallResult.NoGateway
        prepare(null)
        return when (val out = gateway.placeCall(jid)) {
            is Outcome.Err -> {
                unprepare(null)
                CallResult.Failed(out.error)
            }

            is Outcome.Ok -> {
                // The pump starts when the peer picks up (phase Active); `video` is only the screen's business.
                // The call shows as ringing at once, before the gateway's first `call` event (the core tracker's `on_placed`).
                val id = out.value.callId
                lock.withLock { if (prepared == PLACING) prepared = id }
                if (list.value.none { it.callId == id }) {
                    val placed =
                        CallSummary(id, jid, null, CallDirection.Out, CallPhase.Ringing, video, clock(), null)
                    list.value = listOf(placed) + list.value
                }
                CallResult.Started(id)
            }
        }
    }

    /**
     * Answers an incoming call. The ring stops at once ([ringOver], and the call leaves `Ringing` for `Connecting` here
     * instead of waiting for the gateway's event), the microphone service starts while the app is in front, and the
     * pump starts when the call is `Active`.
     */
    suspend fun accept(id: String): CallResult {
        if (microphone.value != PermissionState.Granted) return CallResult.NeedsMicrophone
        val gateway = repo.gateway() ?: return CallResult.NoGateway
        ringOver(id)
        answered += id
        prepare(id)
        setPhase(id, from = CallPhase.Ringing, to = CallPhase.Connecting)
        return when (val out = gateway.acceptCall(id)) {
            is Outcome.Err -> {
                // The gateway's own event says whether it still rings; until then the screen shows it ringing again.
                answered -= id
                setPhase(id, from = CallPhase.Connecting, to = CallPhase.Ringing)
                unprepare(id)
                CallResult.Failed(out.error)
            }

            is Outcome.Ok -> {
                // The audio opens when the media is up (phase Active), not when the answer is sent: until then the
                // gateway has no media to carry and a recorder would only fill the queue with stale frames.
                if (list.value.firstOrNull { it.callId == id }?.phase == CallPhase.Active) startPump(gateway, id)
                CallResult.Started(id)
            }
        }
    }

    suspend fun reject(id: String): Outcome<Unit> {
        ringOver(id)
        return drive(id, stop = true) { it.rejectCall(id) }
    }

    suspend fun end(id: String): Outcome<Unit> = drive(id, stop = true) { it.endCall(id) }

    suspend fun mute(
        id: String,
        on: Boolean,
    ): Outcome<Unit> {
        val out = drive(id, stop = false) { it.setMuted(id, on) }
        if (out is Outcome.Ok) muted.value = if (on) muted.value + id else muted.value - id
        return out
    }

    /** Loudspeaker (true) or earpiece. Returns false when the device has no such route. */
    fun speaker(on: Boolean): Boolean {
        val done = pump.setSpeaker(on)
        if (done) loudspeaker.value = on
        return done
    }

    /** Stops the pump of a call that is still running (the screen is gone). */
    suspend fun close() {
        lock.withLock { stopPumpLocked() }
    }

    private suspend fun drive(
        id: String,
        stop: Boolean,
        call: suspend (WaGateway) -> Outcome<*>,
    ): Outcome<Unit> {
        // Hanging up must always silence the audio, even when the gateway cannot be reached.
        if (stop) lock.withLock { if (pumped == id || prepared == id) stopPumpLocked() }
        val gateway = repo.gateway() ?: return Outcome.Err(RustError.NotFound("no linked gateway"))
        return when (val out = call(gateway)) {
            is Outcome.Err -> out
            is Outcome.Ok -> Outcome.Ok(Unit)
        }
    }

    private suspend fun startPump(
        gateway: WaGateway,
        id: String,
    ) {
        lock.withLock {
            if (pumped == id) return
            // Another call's audio goes; this call's own prepared service stays up (it was started in the foreground).
            if (pumped != null || prepared != id) stopPumpLocked()
            if (microphone.value != PermissionState.Granted) return
            val opened = (gateway.openCallAudio(id) as? Outcome.Ok)?.value ?: return
            if (pump.start(opened.asFrames())) {
                audio = opened
                pumped = id
                prepared = id
            } else {
                opened.close()
            }
        }
    }

    private fun stopPumpLocked() {
        if (pumped == null && audio == null && prepared == null) return
        pump.stop()
        audio?.close()
        audio = null
        pumped = null
        prepared = null
    }

    /** Starts the microphone service for [id] (null: a call being placed, its id not known yet). */
    private suspend fun prepare(id: String?) {
        lock.withLock {
            if (pumped != null) return
            prepared = id ?: PLACING
            pump.prepare()
        }
    }

    /** Undoes [prepare] when the call it was for did not start. */
    private suspend fun unprepare(id: String?) {
        lock.withLock { if (pumped == null && prepared == (id ?: PLACING)) stopPumpLocked() }
    }

    /** Moves [id] from phase [from] to [to] in the list; nothing when it is not there or in another phase. */
    private fun setPhase(
        id: String,
        from: CallPhase,
        to: CallPhase,
    ) {
        list.value = list.value.map { if (it.callId == id && it.phase == from) it.copy(phase = to) else it }
    }

    private suspend fun reload() {
        val gateway = repo.gateway() ?: return
        val loaded = (gateway.calls() as? Outcome.Ok)?.value ?: return
        val gone = forgotten.ids()
        list.value = loaded.filterNot { it.callId in gone }.sortedByDescending { it.startedMs }
    }

    private suspend fun onEvent(event: GatewayEvent) {
        when (event) {
            GatewayEvent.Resync -> reload()
            is GatewayEvent.Call -> onCall(event)
            else -> Unit
        }
    }

    private suspend fun onCall(event: GatewayEvent.Call) {
        val now = clock()
        val ended = event.phase is CallPhase.Ended
        val current = list.value
        val old = current.firstOrNull { it.callId == event.callId }
        // An event sent before our answer reached the gateway: the call is connecting, not ringing.
        val stale = event.phase == CallPhase.Ringing && event.callId in answered && old?.phase == CallPhase.Connecting
        val next =
            CallSummary(
                callId = event.callId,
                peer = event.peer,
                peerName = event.peerName ?: old?.peerName,
                direction = event.direction,
                phase = if (stale) CallPhase.Connecting else event.phase,
                isVideo = event.isVideo,
                startedMs = old?.startedMs ?: now,
                endedMs = if (ended) old?.endedMs ?: now else null,
            )
        list.value =
            if (old == null) listOf(next) + current else current.map { if (it.callId == next.callId) next else it }
        // Answered here or elsewhere, declined or over: nothing may ring for it any more.
        if (event.direction == CallDirection.In && next.phase != CallPhase.Ringing) ringOver(event.callId)
        if (ended) {
            answered -= event.callId
            lock.withLock { if (pumped == event.callId || prepared == event.callId) stopPumpLocked() }
            muted.value = muted.value - event.callId
        } else if (event.phase == CallPhase.Active) {
            // The media is up (outgoing: the peer picked up; incoming: our answer connected): the audio starts now.
            val gateway = repo.gateway()
            if (gateway != null && microphone.value == PermissionState.Granted) startPump(gateway, event.callId)
        }
    }

    private companion object {
        /** [prepared] while a call is being placed and the gateway has not named it yet. */
        const val PLACING = "\u0000placing"
    }
}
