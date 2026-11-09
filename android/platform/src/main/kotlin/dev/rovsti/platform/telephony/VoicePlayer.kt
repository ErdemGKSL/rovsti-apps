package dev.rovsti.platform.telephony

// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/VoicePlayer.kt (ARCH 7.3).

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import org.json.JSONObject

/**
 * Plays one voice note at a time on a `MediaPlayer` (speed through `PlaybackParams`, API 23+).
 * Holds transient audio focus while playing. Errors are caught into the `error` field of
 * [stateJson]; no function throws. The UI reaches it through the voice-note player of the UI layer.
 */
object VoicePlayer {
    private var player: MediaPlayer? = null
    private var key = ""
    private var ended = false
    private var error = ""
    private var focus: AudioFocusRequest? = null

    @Synchronized
    fun play(
        context: Context,
        key: String,
        path: String,
        fromMs: Long,
        speed: Float,
    ): Boolean {
        error = ""
        return try {
            var p = player
            if (p == null || this.key != key) {
                release(context)
                p =
                    MediaPlayer().apply {
                        setAudioAttributes(attributes())
                        setDataSource(path)
                        prepare()
                        setOnCompletionListener {
                            ended = true
                            abandonFocus(context)
                        }
                        setOnErrorListener { _, what, extra ->
                            error = "media error $what/$extra"
                            true
                        }
                    }
                player = p
                this.key = key
            }
            if (ended || fromMs > 0) p.seekTo(if (ended && fromMs <= 0) 0 else fromMs.toInt())
            ended = false
            if (!requestFocus(context)) {
                error = "audio focus refused"
                return false
            }
            applySpeed(p, speed)
            p.start()
            true
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            release(context)
            false
        }
    }

    @Synchronized
    fun pause(context: Context): Boolean =
        guard {
            val p = player ?: return@guard false
            if (p.isPlaying) p.pause()
            abandonFocus(context)
            true
        }

    @Synchronized
    fun seek(ms: Long): Boolean =
        guard {
            val p = player ?: return@guard false
            p.seekTo(ms.coerceIn(0, p.duration.toLong().coerceAtLeast(0)).toInt())
            ended = false
            true
        }

    @Synchronized
    fun setSpeed(speed: Float): Boolean =
        guard {
            val p = player ?: return@guard false
            applySpeed(p, speed)
            true
        }

    @Synchronized
    fun stop(context: Context): Boolean {
        val had = player != null
        release(context)
        return had
    }

    @Synchronized
    fun stateJson(): String {
        val p = player
        val o = JSONObject().put("key", key).put("ended", ended).put("error", error)
        try {
            o.put("playing", p?.isPlaying == true)
            o.put("position_ms", if (ended) p?.duration ?: 0 else p?.currentPosition ?: 0)
            o.put("duration_ms", p?.duration?.coerceAtLeast(0) ?: 0)
        } catch (e: Exception) {
            o.put("playing", false).put("position_ms", 0).put("duration_ms", 0)
            if (error.isEmpty()) o.put("error", e.message ?: "state")
        }
        return o.toString()
    }

    private fun applySpeed(
        p: MediaPlayer,
        speed: Float,
    ) {
        val s = if (speed.isFinite()) speed.coerceIn(0.25f, 4f) else 1f
        // Setting params on a paused player would start it; keep the paused state.
        val wasPlaying = p.isPlaying
        p.playbackParams = p.playbackParams.setSpeed(s)
        if (!wasPlaying && p.isPlaying) p.pause()
    }

    private fun attributes(): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun audio(context: Context): AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun requestFocus(context: Context): Boolean {
        val request =
            focus ?: AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes())
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        synchronized(this) { runCatching { player?.takeIf { it.isPlaying }?.pause() } }
                    }
                }.build()
                .also { focus = it }
        return audio(context).requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus(context: Context) {
        focus?.let { audio(context).abandonAudioFocusRequest(it) }
    }

    private fun release(context: Context) {
        runCatching { player?.release() }
        player = null
        key = ""
        ended = false
        abandonFocus(context)
    }

    private inline fun guard(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            false
        }
}
