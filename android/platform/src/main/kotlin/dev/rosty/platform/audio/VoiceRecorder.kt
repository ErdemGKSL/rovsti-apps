package dev.rosty.platform.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import dev.rosty.core.log.RostyLog
import java.io.File
import java.io.IOException

/** Why the system ended a recording before the app asked it to. */
enum class RecorderStop {
    /** Another app took the audio focus: an incoming call, a voice assistant, a call app. */
    FocusLost,

    /** The recording reached the length it was started with. */
    MaxLength,

    /** The recorder failed (the microphone went away, the file could not be written). */
    Error,
}

/**
 * The microphone of a voice note (E6-WA R3): one recording at a time into a file, with the loudness of the input read
 * while it runs. Called on the main thread. Tests use `FakeVoiceRecorder`.
 */
interface VoiceRecorder {
    /**
     * Starts recording into [file] (Opus in Ogg, mono). [onInterrupted] is called on the main thread when the system
     * ends the recording on its own; the file then holds what was recorded up to that moment, and [stop] still has to
     * be called. False when the microphone cannot be opened (no permission, busy, no such encoder).
     */
    fun start(
        file: File,
        maxMs: Long,
        onInterrupted: (RecorderStop) -> Unit,
    ): Boolean

    /** The loudest input since the previous call, 0 to [MAX_AMPLITUDE]; 0 while nothing records. */
    fun amplitude(): Int

    /** Ends the recording and lets the microphone go. True when the file holds a playable recording. */
    fun stop(): Boolean

    companion object {
        /** What `MediaRecorder.getMaxAmplitude` answers at most (16-bit PCM). */
        const val MAX_AMPLITUDE = 32_767
    }
}

/**
 * [VoiceRecorder] on Android's [MediaRecorder]: Opus in an Ogg container (API 29+; the app's minimum is 34), mono,
 * [SAMPLE_RATE] Hz at [BIT_RATE] bit/s, the format WhatsApp's own voice notes have. While it records it holds a
 * transient exclusive audio focus, so a call or another app that takes the focus ends the recording
 * ([RecorderStop.FocusLost]).
 */
class AndroidVoiceRecorder(
    private val context: Context,
) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var focus: AudioFocusRequest? = null
    private val audio get() = context.getSystemService(AudioManager::class.java)

    // The permission is checked right above; lint does not follow the early return.
    @SuppressLint("MissingPermission")
    override fun start(
        file: File,
        maxMs: Long,
        onInterrupted: (RecorderStop) -> Unit,
    ): Boolean {
        stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val next = MediaRecorder(context)
        return try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            next.setAudioChannels(1)
            next.setAudioSamplingRate(SAMPLE_RATE)
            next.setAudioEncodingBitRate(BIT_RATE)
            next.setMaxDuration(maxMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            next.setOutputFile(file)
            next.setOnInfoListener { _, what, _ ->
                if (what ==
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                ) {
                    onInterrupted(RecorderStop.MaxLength)
                }
            }
            next.setOnErrorListener { _, _, _ -> onInterrupted(RecorderStop.Error) }
            next.prepare()
            next.start()
            recorder = next
            takeFocus(onInterrupted)
            true
        } catch (e: IOException) {
            RostyLog.w(TAG, "the microphone could not be opened", e)
            next.release()
            false
        } catch (e: IllegalStateException) {
            RostyLog.w(TAG, "the recorder refused to start", e)
            next.release()
            false
        } catch (e: RuntimeException) {
            // MediaRecorder.start throws a bare RuntimeException when the microphone is busy.
            RostyLog.w(TAG, "the recorder failed to start", e)
            next.release()
            false
        }
    }

    private fun takeFocus(onInterrupted: (RecorderStop) -> Unit) {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        onInterrupted(RecorderStop.FocusLost)
                    }
                }.build()
        focus = request
        audio?.requestAudioFocus(request)
    }

    override fun amplitude(): Int =
        try {
            recorder?.maxAmplitude ?: 0
        } catch (_: IllegalStateException) {
            0
        }

    override fun stop(): Boolean {
        val current = recorder ?: return false
        recorder = null
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
        return try {
            current.stop()
            true
        } catch (_: RuntimeException) {
            // Stopped before any audio was written: the file is not playable.
            false
        } finally {
            current.release()
        }
    }

    companion object {
        /** Opus' wide band: speech sounds as on WhatsApp, a minute is about 180 KB. */
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 24_000
        private const val TAG = "voice-recorder"
    }
}
