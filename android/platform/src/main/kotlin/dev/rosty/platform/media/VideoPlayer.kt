package dev.rosty.platform.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

/** Where a video is in its playback. */
data class VideoState(
    /** The file is open and its length known. */
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val durationMs: Int = 0,
    val muted: Boolean = false,
    /** Played to its end (a looping video never ends). */
    val ended: Boolean = false,
    /** The file could not be played. */
    val failed: Boolean = false,
)

/**
 * One video on one surface. Every call is safe in every state (a call the player cannot take now is dropped) and
 * nothing throws; [release] ends it for good.
 */
interface VideoPlayer {
    val state: StateFlow<VideoState>

    /** Where the playback is now, in milliseconds (read it per frame; it is not part of [state]). */
    fun positionMs(): Int

    /** The surface to draw on; null while there is none (the view went away). */
    fun setSurface(surface: Surface?)

    fun play()

    fun pause()

    fun seekTo(ms: Int)

    fun setMuted(muted: Boolean)

    fun release()
}

/** Opens a [VideoPlayer] on a file; tests pass a fake. */
fun interface VideoPlayers {
    /**
     * A player for [file] that starts at [startMs]; a [loop] plays over and over, [autoplay] starts as soon as it is
     * ready. A [muted] video does not take the audio focus.
     */
    fun open(
        file: File,
        loop: Boolean,
        muted: Boolean,
        startMs: Int,
        autoplay: Boolean,
    ): VideoPlayer
}

/** The platform player ([MediaPlayer]); [context] gives the audio manager. */
class MediaVideoPlayers(
    private val context: Context,
) : VideoPlayers {
    override fun open(
        file: File,
        loop: Boolean,
        muted: Boolean,
        startMs: Int,
        autoplay: Boolean,
    ): VideoPlayer = MediaVideoPlayer(context.applicationContext, file, loop, muted, startMs, autoplay)
}

/**
 * [VideoPlayer] on [MediaPlayer]. Sound takes the audio focus for as long as it plays (transient: music pauses and
 * comes back); losing the focus (a call, another player) pauses the video. A muted or looping-muted video (a GIF)
 * never asks for the focus.
 */
class MediaVideoPlayer(
    context: Context,
    file: File,
    loop: Boolean,
    muted: Boolean,
    private val startMs: Int,
    private val autoplay: Boolean,
) : VideoPlayer {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    private val focus =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }.build()
    private val mutable = MutableStateFlow(VideoState(muted = muted))
    override val state: StateFlow<VideoState> = mutable.asStateFlow()
    private var holdsFocus = false
    private var released = false
    private val player: MediaPlayer? =
        try {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                isLooping = loop
                if (muted) setVolume(0f, 0f)
                setOnPreparedListener { onPrepared(it) }
                setOnCompletionListener {
                    dropFocus()
                    mutable.update { s -> s.copy(playing = false, ended = true) }
                }
                setOnErrorListener { _, _, _ ->
                    dropFocus()
                    mutable.update { s -> s.copy(playing = false, failed = true) }
                    true
                }
                setDataSource(file.absolutePath)
                prepareAsync()
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    init {
        if (player == null) mutable.update { it.copy(failed = true) }
    }

    private fun onPrepared(p: MediaPlayer) {
        if (released) return
        mutable.update { it.copy(prepared = true, durationMs = p.duration.coerceAtLeast(0)) }
        if (startMs > 0) safely { p.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST) }
        if (autoplay) play()
    }

    override fun positionMs(): Int =
        if (state.value.prepared) safely { player?.currentPosition } ?: 0 else startMs.coerceAtLeast(0)

    override fun setSurface(surface: Surface?) {
        if (!released) safely { player?.setSurface(surface) }
    }

    override fun play() {
        val p = player ?: return
        val now = state.value
        if (released || !now.prepared || now.playing) return
        if (!now.muted && !takeFocus()) return
        safely {
            if (now.ended) p.seekTo(0)
            p.start()
        }
        mutable.update { it.copy(playing = true, ended = false) }
    }

    override fun pause() {
        val p = player ?: return
        if (released || !state.value.playing) return
        safely { p.pause() }
        dropFocus()
        mutable.update { it.copy(playing = false) }
    }

    override fun seekTo(ms: Int) {
        val p = player ?: return
        if (released || !state.value.prepared) return
        safely { p.seekTo(ms.toLong().coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST) }
        mutable.update { it.copy(ended = false) }
    }

    override fun setMuted(muted: Boolean) {
        val p = player ?: return
        if (released) return
        val volume = if (muted) 0f else 1f
        safely { p.setVolume(volume, volume) }
        if (state.value.playing) {
            // Sound now plays: it takes the focus; silence lets it go.
            if (muted) {
                dropFocus()
            } else if (!takeFocus()) {
                pause()
            }
        }
        mutable.update { it.copy(muted = muted) }
    }

    override fun release() {
        if (released) return
        released = true
        dropFocus()
        safely { player?.release() }
        mutable.update { it.copy(playing = false) }
    }

    private fun takeFocus(): Boolean {
        if (holdsFocus) return true
        val granted = audio?.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        holdsFocus = granted
        return granted || audio == null
    }

    private fun dropFocus() {
        if (!holdsFocus) return
        holdsFocus = false
        audio?.abandonAudioFocusRequest(focus)
    }

    private inline fun <T> safely(block: () -> T): T? =
        try {
            block()
        } catch (_: IllegalStateException) {
            null
        }
}
