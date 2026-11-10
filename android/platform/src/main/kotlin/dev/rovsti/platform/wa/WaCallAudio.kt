package dev.rovsti.platform.wa

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import dev.rovsti.core.ops.CallAudio
import java.util.concurrent.locks.LockSupport

/**
 * Where the frames of a call go: the `:core-bridge` WA handle's `push` (microphone, to the gateway) and `pull`
 * (speaker, from the gateway). [CallAudio] is the real one ([asFrames]); tests pass a fake.
 */
interface CallFrames {
    /** One microphone frame of exactly [WaCallAudio.FRAME_BYTES] bytes. */
    fun push(pcm: ByteArray)

    /** Fills [out] with the next peer frame: [WaCallAudio.FRAME_BYTES], 0 when none is ready, -1 when the call ended. */
    fun pull(out: ByteArray): Int
}

/** The `wa.call_audio_open` handle as a [CallFrames]: `audioPush` and `audioPull` underneath. */
fun CallAudio.asFrames(): CallFrames =
    object : CallFrames {
        override fun push(pcm: ByteArray) = this@asFrames.push(pcm)

        override fun pull(out: ByteArray): Int = this@asFrames.pull(out)
    }

/**
 * Microphone and speaker of a WhatsApp call (AD-WA5). The gateway runs the call; the phone only captures and plays
 * 16 kHz mono PCM16 in 960-sample (60 ms) frames.
 *
 * Two daemon threads move the frames: `wa-mic` reads [AudioRecord] and calls [CallFrames.push] (so `CallAudio.push`,
 * `audioPush`), `wa-speaker` calls [CallFrames.pull] (`audioPull`) and writes the [AudioTrack]. Nothing crosses JNI as
 * base64 any more, and neither thread is the main thread. Needs `RECORD_AUDIO`.
 *
 * `docs/kotlin-ui/wa-call-audio.md` traces every hop and format. Callers open the audio only once the call is
 * connected (`WaCalls`: phase Active), because [start] switches the whole phone to `MODE_IN_COMMUNICATION` and takes
 * audio focus.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/WaCallAudio.kt
 */
object WaCallAudio {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 960
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    /** How long the speaker thread waits before asking again when no peer frame is ready. */
    const val IDLE_POLL_MS = 10L

    /** Where the voice of the peer comes out. */
    enum class Route { Earpiece, Speaker, Bluetooth }

    /** Pure: the attributes of the playback track: a voice call, so the system routes and ducks it like one. */
    fun trackAttributes(): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    /** Pure: PCM16 at [SAMPLE_RATE] with the given channel mask (mono in and out). */
    fun format(channelMask: Int): AudioFormat =
        AudioFormat
            .Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(channelMask)
            .build()

    /** Pure: the microphone source with the platform echo canceller and noise suppression of a voice call. */
    const val RECORD_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION

    private val lock = Any()
    private var focus: AudioFocusRequest? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var reader: Thread? = null
    private var player: Thread? = null
    private var previousMode = AudioManager.MODE_NORMAL

    /** Pure: the speaker buffer size in bytes for a minimum of [minBytes]: at least four frames. */
    fun bufferBytes(minBytes: Int): Int = maxOf(minBytes, FRAME_BYTES * 4)

    @SuppressLint("MissingPermission") // checked just above
    fun start(
        context: Context,
        frames: CallFrames,
    ): Boolean =
        synchronized(lock) {
            if (record != null) return true
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val audio = context.getSystemService(AudioManager::class.java) ?: return false
            try {
                val recordMin =
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                val trackMin =
                    AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                if (recordMin <= 0 || trackMin <= 0) return false
                val rec =
                    AudioRecord
                        .Builder()
                        .setAudioSource(RECORD_SOURCE)
                        .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                        .setBufferSizeInBytes(bufferBytes(recordMin))
                        .build()
                val out =
                    AudioTrack
                        .Builder()
                        .setAudioAttributes(trackAttributes())
                        .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                        .setBufferSizeInBytes(bufferBytes(trackMin))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                if (rec.state != AudioRecord.STATE_INITIALIZED || out.state != AudioTrack.STATE_INITIALIZED) {
                    rec.release()
                    out.release()
                    return false
                }
                previousMode = audio.mode
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                requestFocus(audio)
                preferHeadset(audio)
                rec.startRecording()
                out.play()
                record = rec
                track = out
                reader =
                    Thread({ pumpMic(rec, frames) }, "wa-mic").apply {
                        isDaemon = true
                        start()
                    }
                player =
                    Thread({ pumpSpeaker(out, frames) }, "wa-speaker").apply {
                        isDaemon = true
                        start()
                    }
                true
            } catch (_: RuntimeException) {
                stopLocked(context)
                false
            }
        }

    fun stop(context: Context): Boolean =
        synchronized(lock) {
            val was = record != null
            stopLocked(context)
            was
        }

    private fun stopLocked(context: Context) {
        reader?.interrupt()
        reader = null
        player?.interrupt()
        player = null
        try {
            record?.stop()
        } catch (_: RuntimeException) {
        }
        record?.release()
        record = null
        try {
            track?.stop()
        } catch (_: RuntimeException) {
        }
        track?.release()
        track = null
        context.getSystemService(AudioManager::class.java)?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            it.isSpeakerphoneOn = false
            focus?.let(it::abandonAudioFocusRequest)
            it.mode = previousMode
        }
        focus = null
    }

    /** Transient exclusive focus as a voice call: music pauses and comes back when the call ends. */
    private fun requestFocus(audio: AudioManager) {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(trackAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
    }

    /** A paired headset wins over the earpiece when the call starts (API 31+; older releases route SCO themselves). */
    private fun preferHeadset(audio: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val devices = audio.availableCommunicationDevices
        val index = pickDevice(devices.map { it.type }, Route.Bluetooth)
        if (index >= 0) audio.setCommunicationDevice(devices[index])
    }

    /** Pure: the communication-device types that serve [route], best first. */
    fun wantedTypes(route: Route): List<Int> =
        when (route) {
            Route.Speaker -> {
                listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            }

            Route.Earpiece -> {
                listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            }

            Route.Bluetooth -> {
                listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }

    /** Pure: the index in [types] of the device for [route], or -1. */
    fun pickDevice(
        types: List<Int>,
        route: Route,
    ): Int {
        for (wanted in wantedTypes(route)) {
            val at = types.indexOf(wanted)
            if (at >= 0) return at
        }
        return -1
    }

    /** Loudspeaker or earpiece; see [setRoute]. */
    fun setSpeaker(
        context: Context,
        speaker: Boolean,
    ): Boolean = setRoute(context, if (speaker) Route.Speaker else Route.Earpiece)

    /**
     * Moves the voice to [route]. API 31+ picks the device from `availableCommunicationDevices` (a Bluetooth headset
     * is a SCO or BLE device there); older releases use `isSpeakerphoneOn` and cannot choose Bluetooth. False when
     * the device has no such route.
     */
    fun setRoute(
        context: Context,
        route: Route,
    ): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audio.availableCommunicationDevices
                val index = pickDevice(devices.map { it.type }, route)
                if (index < 0) return false
                audio.setCommunicationDevice(devices[index])
            } else if (route == Route.Bluetooth) {
                false
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = route == Route.Speaker
                true
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Whether a call is being captured and played right now. */
    val running: Boolean get() = synchronized(lock) { record != null }

    /** Reads PCM in whole frames from the microphone and pushes each one to the handle. */
    private fun pumpMic(
        rec: AudioRecord,
        frames: CallFrames,
    ) {
        while (!Thread.currentThread().isInterrupted) {
            val frame = ByteArray(FRAME_BYTES)
            var filled = 0
            while (filled < FRAME_BYTES) {
                val n = rec.read(frame, filled, FRAME_BYTES - filled)
                if (n <= 0) return
                filled += n
            }
            frames.push(frame)
        }
    }

    /** Pulls peer frames and plays them; writes never block (a full buffer drops the frame). Ends with the call. */
    private fun pumpSpeaker(
        out: AudioTrack,
        frames: CallFrames,
    ) {
        val frame = ByteArray(FRAME_BYTES)
        while (!Thread.currentThread().isInterrupted) {
            val n = frames.pull(frame)
            when {
                n < 0 -> {
                    return
                }

                n == FRAME_BYTES -> {
                    out.write(frame, 0, FRAME_BYTES, AudioTrack.WRITE_NON_BLOCKING)
                }

                else -> {
                    LockSupport.parkNanos(IDLE_POLL_MS * 1_000_000L)
                }
            }
        }
    }
}
