package dev.rosty.messages.wa

import dev.rosty.core.model.wa.TypingState
import dev.rosty.design.text.UiText
import dev.rosty.messages.R
import dev.rosty.messages.conversation.RecordPhase
import dev.rosty.messages.conversation.RecordUi
import dev.rosty.messages.inbox.duration
import dev.rosty.platform.audio.VoiceRecorder
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** A player that plays nothing: a recorder without a preview player. */
internal object NoVoiceEngine : VoiceEngine {
    override fun start(
        path: String,
        fromMs: Long,
        speed: Float,
        onEnd: () -> Unit,
    ): Boolean = false

    override fun pause() = Unit

    override fun resume() = Unit

    override fun seekTo(ms: Long) = Unit

    override fun setSpeed(speed: Float) = Unit

    override fun positionMs(): Long = 0

    override fun durationMs(): Long = 0

    override fun release() = Unit
}

/** A recorded voice note ready to go out: the Ogg/Opus file, its length and its [VoiceWaveform.reduce] bars. */
class VoiceTake(
    val file: File,
    val seconds: Int,
    val waveform: ByteArray,
)

/**
 * The voice note recorder of the WhatsApp composer (E6-WA R3), the state machine behind the microphone button:
 *
 * - [start] (the finger goes down on the microphone) records into [newFile]: [RecordPhase.Holding];
 * - [release] (the finger lifts) sends the take; [cancel] (slid left past the line) throws it away; [lock] (slid up
 *   past the line) keeps recording hands-free: [RecordPhase.Locked];
 * - locked, [stop] ends the recording and shows the take ([RecordPhase.Review]), where [togglePreview] plays it;
 *   [send] and [delete] work in both;
 * - [interrupt] (the app went to the background, the audio focus was lost to a call, the [MAX_MS] cap) ends the
 *   recording and keeps the take for review, so nothing is sent that the user did not mean to send.
 *
 * A take shorter than [MIN_MS] is thrown away with a short hint. While it records the other side is told
 * `recording` ([chatState]), only once it lasted [STATE_DELAY_MS] (a tap on the microphone tells nobody) and again
 * every [STATE_EVERY_MS]; `paused` follows when it ends, only if `recording` was sent. The input level is read every
 * [tickMs] for the meter and the waveform. Times come from [clock] (monotonic). Main thread.
 */
class VoiceRecording(
    private val scope: CoroutineScope,
    private val recorder: VoiceRecorder,
    /** Plays the take in review; its own player, not the one of the thread's notes. */
    private val preview: VoiceEngine,
    /** Where the next take is written; null when it cannot be (WhatsApp is not linked). */
    private val newFile: () -> File?,
    /** Deletes a take that will not be sent. */
    private val discard: suspend (File) -> Unit,
    private val clock: () -> Long,
    /** Tells the other side what this side does (`recording`, `paused`); best effort. */
    private val chatState: suspend (TypingState) -> Unit,
    /** A short line for the user: too short, the microphone could not be opened. */
    private val hint: (UiText) -> Unit,
    /** The take to send. */
    private val onTake: suspend (VoiceTake) -> Unit,
    private val tickMs: Long = TICK_MS,
) {
    private val ui = MutableStateFlow<RecordUi?>(null)

    /** What the composer shows; null while nothing is recorded. */
    val state: StateFlow<RecordUi?> = ui.asStateFlow()

    private var phase: RecordPhase? = null
    private var file: File? = null
    private var startedMs = 0L
    private var lengthMs = 0L
    private val levels = ArrayList<Int>()
    private var ticker: Job? = null
    private var announcer: Job? = null
    private var announced = false
    private var previewStarted = false
    private var playing = false
    private var bars: List<Float> = emptyList()

    /** Something is recorded or waits for review: the composer shows the recorder. */
    val active: Boolean get() = phase != null

    /** The finger went down on the microphone. False when nothing records (no file, the microphone is busy). */
    fun start(): Boolean {
        if (phase != null) return false
        val target = newFile()
        // A call, the cap or a failing microphone all end the same way: the take waits for the user.
        if (target == null || !recorder.start(target, MAX_MS) { _ -> scope.launch { interrupt() } }) {
            target?.let { scope.launch { discard(it) } }
            hint(UiText.Res(R.string.wa_record_failed))
            return false
        }
        file = target
        startedMs = clock()
        lengthMs = 0
        levels.clear()
        phase = RecordPhase.Holding
        ticker =
            scope.launch {
                while (isActive) {
                    delay(tickMs)
                    levels += VoiceWaveform.level(recorder.amplitude())
                    if (clock() - startedMs >= MAX_MS) {
                        interrupt()
                        break
                    }
                    publish()
                }
            }
        announcer =
            scope.launch {
                delay(STATE_DELAY_MS)
                while (isActive) {
                    announced = true
                    chatState(TypingState.Recording)
                    delay(STATE_EVERY_MS)
                }
            }
        publish()
        return true
    }

    /** The finger lifted while holding: the take goes out (or is thrown away when it is too short). */
    fun release() {
        if (phase != RecordPhase.Holding) return
        finishInto(send = true)
    }

    /** Slid left past the cancel line (or Delete): the take is thrown away without a word. */
    fun cancel() {
        if (phase == null) return
        if (phase != RecordPhase.Review) endRecording()
        drop(tooShort = false)
    }

    /** Slid up past the lock line: recording goes on without the finger. */
    fun lock() {
        if (phase != RecordPhase.Holding) return
        phase = RecordPhase.Locked
        publish()
    }

    /** Stop while locked: the take is shown for review. */
    fun stop() {
        if (phase != RecordPhase.Locked) return
        finishInto(send = false)
    }

    /** Send while locked or in review. */
    fun send() {
        when (phase) {
            RecordPhase.Locked -> {
                finishInto(send = true)
            }

            RecordPhase.Review -> {
                preview.release()
                deliver()
            }

            else -> {
                Unit
            }
        }
    }

    /** Delete while locked or in review. */
    fun delete() = cancel()

    /** Play or pause the take in review. */
    fun togglePreview() {
        if (phase != RecordPhase.Review) return
        val take = file ?: return
        when {
            playing -> {
                preview.pause()
                playing = false
            }

            previewStarted -> {
                preview.resume()
                playing = true
            }

            else -> {
                previewStarted = preview.start(take.absolutePath, 0, 1f) { scope.launch { previewEnded() } }
                playing = previewStarted
            }
        }
        if (playing) followPreview() else ticker?.cancel()
        publish()
    }

    /**
     * The app went to the background or the page was left: a recording ends and waits for review, a preview pauses.
     */
    fun interrupt() {
        when (phase) {
            RecordPhase.Holding, RecordPhase.Locked -> {
                finishInto(send = false)
            }

            RecordPhase.Review -> {
                if (playing) togglePreview()
            }

            null -> {
                Unit
            }
        }
    }

    /** The thread is gone for good: whatever was recorded is thrown away. */
    fun dispose() {
        cancel()
    }

    /** Ends the recording; a take long enough is sent ([send]) or reviewed, a shorter one thrown away. */
    private fun finishInto(send: Boolean) {
        val playable = endRecording()
        if (!playable || lengthMs < MIN_MS) {
            drop(tooShort = true)
            return
        }
        if (send) {
            deliver()
        } else {
            phase = RecordPhase.Review
            bars = VoiceWaveform.bars(VoiceWaveform.reduce(levels))
            previewStarted = false
            playing = false
            publish()
        }
    }

    /** Stops the microphone and the announcements; true when the file holds a recording. */
    private fun endRecording(): Boolean {
        ticker?.cancel()
        lengthMs = clock() - startedMs
        val playable = recorder.stop()
        announcer?.cancel()
        if (announced) {
            announced = false
            scope.launch { chatState(TypingState.Paused) }
        }
        return playable
    }

    private fun deliver() {
        val take = file ?: return reset()
        val seconds = ((lengthMs + HALF_SECOND_MS) / SECOND_MS).toInt().coerceAtLeast(1)
        val waveform = VoiceWaveform.reduce(levels)
        file = null
        reset()
        scope.launch { onTake(VoiceTake(take, seconds, waveform)) }
    }

    private fun drop(tooShort: Boolean) {
        val take = file
        file = null
        reset()
        take?.let { scope.launch { discard(it) } }
        if (tooShort) hint(UiText.Res(R.string.wa_record_hold))
    }

    private fun reset() {
        ticker?.cancel()
        preview.release()
        previewStarted = false
        playing = false
        phase = null
        bars = emptyList()
        ui.value = null
    }

    private fun followPreview() {
        ticker?.cancel()
        ticker =
            scope.launch {
                while (isActive && playing) {
                    delay(tickMs)
                    publish()
                }
            }
    }

    private fun previewEnded() {
        if (phase != RecordPhase.Review) return
        ticker?.cancel()
        playing = false
        // Played to its end: the next tap plays it from the start.
        preview.release()
        previewStarted = false
        publish()
    }

    private fun publish() {
        val now = phase ?: return
        val length = if (now == RecordPhase.Review) lengthMs else clock() - startedMs
        ui.value =
            RecordUi(
                phase = now,
                elapsed = duration((length / SECOND_MS).toInt()),
                level = (levels.lastOrNull() ?: 0) / LEVEL_MAX,
                bars = if (now == RecordPhase.Review) bars.toImmutableList() else persistentListOf(),
                playing = playing,
                progress =
                    if (previewStarted && length > 0) {
                        (preview.positionMs().toFloat() / length).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
            )
    }

    companion object {
        /** A take shorter than this is thrown away ("Hold to record"). */
        const val MIN_MS = 1_000L

        /** The longest voice note: 15 minutes (about 2.7 MB at 24 kbit/s, far below WhatsApp's 16 MiB). */
        const val MAX_MS = 15 * 60_000L

        /** The level meter and the waveform read the microphone this often. */
        const val TICK_MS = 100L

        /** `recording` is told to the other side once the recording lasted this long. */
        const val STATE_DELAY_MS = 500L

        /** ...and again this often while it goes on (WhatsApp lets the line fade on its own). */
        const val STATE_EVERY_MS = 5_000L

        /** The type of a WhatsApp voice note. */
        const val MIME = "audio/ogg; codecs=opus"

        private const val SECOND_MS = 1_000L
        private const val HALF_SECOND_MS = 500L
        private const val LEVEL_MAX = 100f
    }
}
