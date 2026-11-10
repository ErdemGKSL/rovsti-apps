package dev.rovsti.wa.calls

import android.content.Context
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
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
import dev.rovsti.wa.WaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Microphone and speaker of a running call: [WaCallAudio] on a device, a fake in tests. */
interface CallAudioPump {
    /** Starts the capture and playback threads over [frames]. False when the recorder or track cannot start. */
    fun start(frames: CallFrames): Boolean

    /** Stops both threads and releases the audio devices. False when nothing was running. */
    fun stop(): Boolean

    /** Loudspeaker or earpiece; false when the device has no such route. */
    fun setSpeaker(on: Boolean): Boolean
}

/** The real pump: `WaCallAudio` with the voice-communication attributes ported in R1. */
class AndroidCallAudioPump(
    context: Context,
) : CallAudioPump {
    private val context = context.applicationContext

    override fun start(frames: CallFrames): Boolean {
        if (!WaCallAudio.start(context, frames)) return false
        // After the audio runs: the foreground service keeps the microphone usable once the app is in the background.
        WaCallService.start(context)
        return true
    }

    override fun stop(): Boolean {
        WaCallService.stop(context)
        return WaCallAudio.stop(context)
    }

    override fun setSpeaker(on: Boolean): Boolean = WaCallAudio.setSpeaker(context, on)
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
) {
    private val list = MutableStateFlow<List<CallSummary>>(emptyList())

    /** The recent calls, newest first. */
    val calls: StateFlow<List<CallSummary>> = list.asStateFlow()

    private val lock = Mutex()
    private var pumped: String? = null
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
    }

    /** Places a call to [jid]. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun place(
        jid: String,
        video: Boolean = false,
    ): CallResult {
        if (microphone.value != PermissionState.Granted) return CallResult.NeedsMicrophone
        val gateway = repo.gateway() ?: return CallResult.NoGateway
        return when (val out = gateway.placeCall(jid)) {
            is Outcome.Err -> {
                CallResult.Failed(out.error)
            }

            is Outcome.Ok -> {
                // The pump starts when the peer picks up (phase Active); `video` is only the screen's business.
                CallResult.Started(out.value.callId)
            }
        }
    }

    /** Answers an incoming call and starts the pump. */
    suspend fun accept(id: String): CallResult {
        if (microphone.value != PermissionState.Granted) return CallResult.NeedsMicrophone
        val gateway = repo.gateway() ?: return CallResult.NoGateway
        return when (val out = gateway.acceptCall(id)) {
            is Outcome.Err -> {
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

    suspend fun reject(id: String): Outcome<Unit> = drive(id, stop = true) { it.rejectCall(id) }

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
        if (stop) lock.withLock { if (pumped == id) stopPumpLocked() }
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
            stopPumpLocked()
            if (microphone.value != PermissionState.Granted) return
            val opened = (gateway.openCallAudio(id) as? Outcome.Ok)?.value ?: return
            if (pump.start(opened.asFrames())) {
                audio = opened
                pumped = id
            } else {
                opened.close()
            }
        }
    }

    private fun stopPumpLocked() {
        if (pumped == null && audio == null) return
        pump.stop()
        audio?.close()
        audio = null
        pumped = null
    }

    private suspend fun reload() {
        val gateway = repo.gateway() ?: return
        val loaded = (gateway.calls() as? Outcome.Ok)?.value ?: return
        list.value = loaded.sortedByDescending { it.startedMs }
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
        val next =
            CallSummary(
                callId = event.callId,
                peer = event.peer,
                peerName = event.peerName ?: old?.peerName,
                direction = event.direction,
                phase = event.phase,
                isVideo = event.isVideo,
                startedMs = old?.startedMs ?: now,
                endedMs = if (ended) old?.endedMs ?: now else null,
            )
        list.value =
            if (old == null) listOf(next) + current else current.map { if (it.callId == next.callId) next else it }
        if (ended) {
            lock.withLock { if (pumped == event.callId) stopPumpLocked() }
            muted.value = muted.value - event.callId
        } else if (event.phase == CallPhase.Active) {
            // The media is up (outgoing: the peer picked up; incoming: our answer connected): the audio starts now.
            val gateway = repo.gateway()
            if (gateway != null && microphone.value == PermissionState.Granted) startPump(gateway, event.callId)
        }
    }
}
