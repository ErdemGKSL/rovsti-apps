package dev.rosty.messages.wa

import android.media.MediaPlayer
import android.media.PlaybackParams
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.SpeedOption
import dev.rosty.core.model.chat.SpeedsRequest
import dev.rosty.core.model.chat.VoicePlayerState
import dev.rosty.core.model.chat.VoiceSpeed
import dev.rosty.core.model.chat.VoiceToggleKind
import dev.rosty.core.model.chat.VoiceToggleRequest
import dev.rosty.core.model.chat.VoiceViewRequest
import dev.rosty.core.ops.ChatOps
import dev.rosty.messages.conversation.VoiceUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/** The platform player under the voice notes: one file at a time. Called on the main thread. */
interface VoiceEngine {
    /** Loads [path] and plays it from [fromMs] at [speed]; false when it cannot be played. */
    fun start(
        path: String,
        fromMs: Long,
        speed: Float,
        onEnd: () -> Unit,
    ): Boolean

    fun pause()

    fun resume()

    fun seekTo(ms: Long)

    fun setSpeed(speed: Float)

    fun positionMs(): Long

    fun durationMs(): Long

    /** Stops and lets the file go. */
    fun release()
}

/** The decisions Rust makes for the player (`voice_player.*`), narrowed so tests can script them. */
interface VoiceRules {
    /** What a tap on the play button of [key] does while the player is in [state]. */
    suspend fun toggle(
        state: VoicePlayerState,
        key: String,
    ): Pair<VoiceToggleKind, Long?>?

    /** Progress and label of a position; with [scrub] (0..1) also where to seek. */
    suspend fun view(
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        scrub: Double? = null,
    ): Triple<Float, String, Long?>?

    /** The speed [current] plays at and the one a tap on the pill changes to. */
    suspend fun speeds(current: VoiceSpeed): Pair<SpeedOption, SpeedOption>?
}

/** [VoiceRules] on the Rust core. */
class ChatVoiceRules(
    private val chat: ChatOps,
) : VoiceRules {
    override suspend fun toggle(
        state: VoicePlayerState,
        key: String,
    ): Pair<VoiceToggleKind, Long?>? =
        chat.voiceToggle(VoiceToggleRequest(state, key)).getOrNull()?.let {
            it.plan to
                it.fromMs
        }

    override suspend fun view(
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        scrub: Double?,
    ): Triple<Float, String, Long?>? =
        chat
            .voiceView(VoiceViewRequest(positionMs, durationMs, playing, scrub))
            .getOrNull()
            ?.let { Triple(it.progress.toFloat(), it.timeLabel, it.seekMs) }

    override suspend fun speeds(current: VoiceSpeed): Pair<SpeedOption, SpeedOption>? =
        chat.voiceSpeeds(SpeedsRequest(current)).getOrNull()?.let { answer ->
            (answer.speeds.firstOrNull { it.speed == current } ?: return null) to answer.next
        }
}

/**
 * The voice note player of the app (M17): one note at a time, play/pause, scrubbing and the 1×/1.5×/2× speed that is
 * remembered (`voice_speed` of `messages-prefs.json`). Every decision is Rust's ([VoiceRules]); this class only drives
 * the [engine] and publishes what the playing note's row shows. While a note plays its position is read every
 * [tickMs] for the progress bar; nothing runs while nothing plays.
 */
class WaVoiceNotes(
    private val scope: CoroutineScope,
    private val rules: VoiceRules,
    private val engine: VoiceEngine,
    private val savedSpeed: suspend () -> VoiceSpeed,
    private val saveSpeed: suspend (VoiceSpeed) -> Unit,
    private val tickMs: Long = TICK_MS,
) {
    private var player = VoicePlayerState.Idle
    private var speed: SpeedOption? = null
    private var ticker: Job? = null

    private val ui = MutableStateFlow<VoiceUi?>(null)

    /** The note in the player; null while none is loaded. */
    val state: StateFlow<VoiceUi?> = ui.asStateFlow()

    private val speedText = MutableStateFlow("")

    /** The speed pill: "1×", "1.5×", "2×". */
    val speedLabel: StateFlow<String> = speedText.asStateFlow()

    private suspend fun currentSpeed(): SpeedOption? =
        speed ?: rules.speeds(savedSpeed())?.first?.also {
            speed = it
            speedText.value = it.label
        }

    /** A tap on the play button of the note [key] whose file is [path]. False when the file cannot be played. */
    suspend fun toggle(
        key: String,
        path: String,
    ): Boolean {
        val (plan, fromMs) = rules.toggle(sync(), key) ?: return false
        when (plan) {
            VoiceToggleKind.Pause -> {
                engine.pause()
                player = player.copy(playing = false, positionMs = engine.positionMs())
                ticker?.cancel()
            }

            VoiceToggleKind.Resume -> {
                fromMs?.let(engine::seekTo)
                engine.resume()
                player = player.copy(playing = true, positionMs = fromMs ?: player.positionMs)
                tick()
            }

            VoiceToggleKind.Start -> {
                ticker?.cancel()
                engine.release()
                val factor = currentSpeed()?.factor?.toFloat() ?: 1f
                if (!engine.start(path, fromMs ?: 0, factor) { scope.launch { ended(key) } }) {
                    player = VoicePlayerState.Idle
                    ui.value = null
                    return false
                }
                player = VoicePlayerState(key, true, fromMs ?: 0, engine.durationMs(), false, null)
                tick()
            }
        }
        publish()
        return true
    }

    /** The player's state with the engine's position. */
    private fun sync(): VoicePlayerState {
        if (player.key.isNotEmpty() && !player.ended) player = player.copy(positionMs = engine.positionMs())
        return player
    }

    private fun tick() {
        ticker?.cancel()
        ticker =
            scope.launch {
                while (isActive && player.playing) {
                    delay(tickMs)
                    if (!player.playing) break
                    sync()
                    publish()
                }
            }
    }

    private suspend fun ended(key: String) {
        if (player.key != key) return
        ticker?.cancel()
        player = player.copy(playing = false, ended = true, positionMs = player.durationMs)
        // A note that played to its end reads as one that was never started.
        ui.value = null
    }

    private suspend fun publish() {
        val now = player
        if (now.key.isEmpty() || now.ended) {
            ui.value = null
            return
        }
        val (progress, label, _) = rules.view(now.positionMs, now.durationMs, now.playing) ?: return
        if (player.key == now.key && !player.ended) ui.value = VoiceUi(now.key, now.playing, progress, label)
    }

    /** Scrubbing the bar of the loaded note to [fraction] (0..1). */
    suspend fun seek(
        key: String,
        fraction: Float,
    ) {
        if (player.key != key || player.ended) return
        val target =
            rules.view(player.positionMs, player.durationMs, player.playing, fraction.toDouble())?.third ?: return
        engine.seekTo(target)
        player = player.copy(positionMs = target)
        publish()
    }

    /** A tap on the speed pill: the next of 1×, 1.5×, 2×, kept for the next note and the next run. */
    suspend fun nextSpeed() {
        val current = currentSpeed()?.speed ?: return
        val next = rules.speeds(current)?.second ?: return
        speed = next
        speedText.value = next.label
        if (player.playing) engine.setSpeed(next.factor.toFloat())
        saveSpeed(next.speed)
    }

    /** Reads the remembered speed so the pill has its label before the first tap. */
    suspend fun prepare() {
        currentSpeed()
    }

    /** Stops whatever plays (the thread was left). */
    fun stop() {
        ticker?.cancel()
        engine.release()
        player = VoicePlayerState.Idle
        ui.value = null
    }

    companion object {
        const val TICK_MS = 200L
    }
}

/** [VoiceEngine] on Android's [MediaPlayer]. A voice note is a small local file, so it is prepared in place. */
class MediaVoiceEngine : VoiceEngine {
    private var player: MediaPlayer? = null
    private var speed = 1f

    override fun start(
        path: String,
        fromMs: Long,
        speed: Float,
        onEnd: () -> Unit,
    ): Boolean {
        release()
        this.speed = speed
        val next = MediaPlayer()
        return try {
            next.setDataSource(path)
            next.prepare()
            next.setOnCompletionListener { onEnd() }
            next.setOnErrorListener { _, _, _ ->
                onEnd()
                true
            }
            if (fromMs > 0) next.seekTo(fromMs.toInt())
            player = next
            // Setting the speed of a prepared player starts it.
            next.playbackParams = PlaybackParams().setSpeed(speed)
            if (!next.isPlaying) next.start()
            true
        } catch (_: IOException) {
            next.release()
            false
        } catch (_: IllegalStateException) {
            next.release()
            false
        } catch (_: IllegalArgumentException) {
            next.release()
            false
        }
    }

    private inline fun <T> safely(
        fallback: T,
        block: (MediaPlayer) -> T,
    ): T {
        val current = player ?: return fallback
        return try {
            block(current)
        } catch (_: IllegalStateException) {
            fallback
        }
    }

    override fun pause() = safely(Unit) { if (it.isPlaying) it.pause() }

    override fun resume() =
        safely(Unit) {
            it.playbackParams = PlaybackParams().setSpeed(speed)
            if (!it.isPlaying) it.start()
        }

    override fun seekTo(ms: Long) = safely(Unit) { it.seekTo(ms.toInt()) }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        safely(Unit) { if (it.isPlaying) it.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    override fun positionMs(): Long = safely(0L) { it.currentPosition.toLong() }

    override fun durationMs(): Long = safely(0L) { it.duration.toLong().coerceAtLeast(0) }

    override fun release() {
        player?.release()
        player = null
    }
}
