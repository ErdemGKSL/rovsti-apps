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
import android.util.Log
import dev.rovsti.core.ops.CallAudio
import java.util.concurrent.locks.LockSupport

/**
 * Where the frames of a call go: the `:core-bridge` WA handle's `push` (microphone, to the gateway) and `pull`
 * (speaker, from the gateway). [CallAudio] is the real one ([asFrames]); tests pass a fake.
 */
interface CallFrames {
    /** One microphone frame of exactly [WaCallAudio.FRAME_BYTES] bytes. */
    fun push(pcm: ByteArray)

    /**
     * Fills [out] (at least [WaCallAudio.FRAME_BYTES]) with the next peer frame and answers its length: 640 bytes
     * (20 ms) from a gateway that sends 20 ms frames, [WaCallAudio.FRAME_BYTES] (60 ms) from an older one; see
     * [WaCallAudio.playable]. 0 when none came (the real one waits about 20 ms for a frame first), -1 when the call
     * ended.
     */
    fun pull(out: ByteArray): Int
}

/** The `wa.call_audio_open` handle as a [CallFrames]: `audioPush` and `audioPull` underneath. */
fun CallAudio.asFrames(): CallFrames =
    object : CallFrames {
        override fun push(pcm: ByteArray) = this@asFrames.push(pcm)

        override fun pull(out: ByteArray): Int = this@asFrames.pull(out)
    }

/**
 * Watches the microphone for digital silence: Android hands an app exact zeros when it captures while it counts as in
 * the background (no visible activity and no microphone foreground service started while it was in the foreground) or
 * when audio policy silences the capture. A real microphone never gives exact zeros for long, even in a quiet room.
 *
 * [frame] answers true once per run, when the run of all-zero frames reaches [limitFrames]; a frame with any sound
 * re-arms it. Pure, so a JVM test drives it.
 */
class SilenceWatch(
    private val limitFrames: Int = LIMIT_FRAMES,
) {
    private var run = 0

    /** True exactly when this frame makes the run of all-zero frames [limitFrames] long. */
    fun frame(pcm: ByteArray): Boolean {
        if (pcm.any { it != ZERO }) {
            run = 0
            return false
        }
        run++
        return run == limitFrames
    }

    companion object {
        /** About two seconds of 60 ms frames. */
        const val LIMIT_FRAMES = 34
        private const val ZERO: Byte = 0
    }
}

/**
 * Decides when the speaker thread throws a peer frame away instead of playing it, so no delay stands in the
 * [AudioTrack]. The track is written with blocking writes: whatever arrives in a burst (the network stalled and then
 * delivered everything at once) stays in its buffer, and so does the surplus of a gateway clock that runs a little
 * faster than the phone's, because the track plays exactly as fast as frames arrive from then on. Each such
 * millisecond is a millisecond the peer is heard late, for the rest of the call.
 *
 * [drop] is asked before every write with the track's fill ([WaCallAudio.fillMs]). The least fill of a window of
 * [windowMs] of audio is what was never needed: jitter did not use it. When that is more than [targetMs] by at least
 * one frame, the surplus is dropped (whole frames, the oldest audio not yet written) and the fill is back at the
 * target. A fill that dips below the target within the window (real jitter) leaves everything alone. A fill of -1
 * (unknown) never drops. Pure, so a JVM test drives it.
 */
class PlayoutTrim(
    private val targetMs: Int = WaCallAudio.TARGET_FILL_MS,
    private val windowMs: Int = WINDOW_MS,
) {
    private var least = Int.MAX_VALUE
    private var seenMs = 0
    private var debtMs = 0

    /** True: do not play this frame of [frameMs]. [fillMs] is what the track holds right now, -1 when unknown. */
    fun drop(
        fillMs: Int,
        frameMs: Int,
    ): Boolean {
        if (fillMs < 0 || frameMs <= 0) {
            restart()
            debtMs = 0
            return false
        }
        if (debtMs >= frameMs) {
            debtMs -= frameMs
            return true
        }
        debtMs = 0
        least = minOf(least, fillMs)
        seenMs += frameMs
        if (seenMs < windowMs) return false
        val surplus = least - targetMs
        restart()
        if (surplus < frameMs) return false
        debtMs = surplus - frameMs
        return true
    }

    private fun restart() {
        least = Int.MAX_VALUE
        seenMs = 0
    }

    companion object {
        /** How much audio one window of [PlayoutTrim] looks at. */
        const val WINDOW_MS = 2_000
    }
}

/**
 * The speaker's numbers for the log line that is written about every ten seconds of a call: the [AudioTrack] fill
 * before each write (how late the peer is heard because of the track alone) and what [PlayoutTrim] dropped. Pure.
 */
class FillLog {
    private var frames = 0
    private var known = 0
    private var sum = 0L
    private var least = Int.MAX_VALUE
    private var most = 0
    private var trimmedMs = 0
    private var frameMs = 0

    /** One peer frame of [frameMs] came while the track held [fillMs] (-1: unknown); [dropped]: it was not played. */
    fun frame(
        fillMs: Int,
        frameMs: Int,
        dropped: Boolean,
    ) {
        frames++
        this.frameMs = frameMs
        if (dropped) trimmedMs += frameMs
        if (fillMs < 0) return
        known++
        sum += fillMs
        least = minOf(least, fillMs)
        most = maxOf(most, fillMs)
    }

    /**
     * The line, and a fresh start. [underruns] is `AudioTrack.underrunCount` (the track ran dry: it grows over the
     * call), [bufferMs] the track's capacity.
     */
    fun line(
        underruns: Int,
        bufferMs: Int,
    ): String {
        val fill =
            if (known == 0) {
                "track_fill=unknown"
            } else {
                "track_fill mean=${sum / known}ms min=${least}ms max=${most}ms"
            }
        val text =
            "WA call audio: $fill trimmed=${trimmedMs}ms frames=$frames frame=${frameMs}ms " +
                "underruns=$underruns buffer=${bufferMs}ms"
        frames = 0
        known = 0
        sum = 0
        least = Int.MAX_VALUE
        most = 0
        trimmedMs = 0
        return text
    }
}

/**
 * Microphone and speaker of a WhatsApp call (AD-WA5). The gateway runs the call; the phone only captures and plays
 * 16 kHz mono PCM16: the microphone in 960-sample (60 ms) frames, the speaker in whatever the gateway sends (20 ms
 * frames, or 60 ms from an older gateway).
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

    /** The shortest peer frame: 20 ms (320 samples). Every peer frame is a multiple of it. */
    const val STEP_BYTES = 640

    /** Bytes of one millisecond of PCM16 mono at [SAMPLE_RATE]. */
    const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000

    /** What the speaker track should hold: enough for the jitter of two 20 ms frames, no more. */
    const val TARGET_FILL_MS = 40

    /** The speaker's numbers are logged this often. */
    const val LOG_EVERY_MS = 10_000L

    private const val TAG = "Rovsti"

    /**
     * How long the speaker thread sleeps when [CallFrames.pull] answers 0. The real pull waits for a frame itself
     * (about 20 ms, in Rust) and hands it over the moment it arrives, so this is only a guard against a pull that
     * answers 0 at once; it was a 10 ms poll before, which a frame waited out.
     */
    const val IDLE_POLL_MS = 1L

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

    /** Pure: the microphone buffer size in bytes for a minimum of [minBytes]: at least four frames. */
    fun bufferBytes(minBytes: Int): Int = maxOf(minBytes, FRAME_BYTES * 4)

    /**
     * Pure: the speaker track's buffer size in bytes for a minimum of [minBytes]: the platform minimum, or two 60 ms
     * frames (120 ms) when that is more. Writes block, so a full buffer is that much delay; it was four frames
     * (240 ms). Two whole frames still take the 60 ms frame of an older gateway without a wait.
     */
    fun speakerBufferBytes(minBytes: Int): Int = maxOf(minBytes, FRAME_BYTES * 2)

    /**
     * Pure: how many frames (samples) the track needs before it starts playing: [TARGET_FILL_MS], or the whole
     * buffer of [capacityFrames] when that is smaller. The platform default is the whole buffer, which held the
     * first words back until 120 ms (before: 240 ms) had arrived and kept that much as a standing delay.
     */
    fun startThresholdFrames(capacityFrames: Int): Int =
        (TARGET_FILL_MS * SAMPLE_RATE / 1000).coerceAtMost(capacityFrames).coerceAtLeast(1)

    /** Pure: whether [bytes] is a peer frame the speaker plays: 20, 40 or 60 ms. Whatever size arrives is played. */
    fun playable(bytes: Int): Boolean = bytes in STEP_BYTES..FRAME_BYTES && bytes % STEP_BYTES == 0

    /**
     * Pure: milliseconds of audio the track holds: [writtenFrames] written so far minus `playbackHeadPosition`
     * [head] (a 32-bit counter that wraps). -1 when the result is not believable (more than the buffer of
     * [capacityFrames] and one frame: some devices reset the head when the route changes), so nothing is decided
     * on it.
     */
    fun fillMs(
        writtenFrames: Long,
        head: Int,
        capacityFrames: Int,
    ): Int {
        val fill = (writtenFrames - (head.toLong() and UINT_MASK)) and UINT_MASK
        if (fill > capacityFrames + FRAME_SAMPLES) return -1
        return (fill * 1000 / SAMPLE_RATE).toInt()
    }

    private const val UINT_MASK = 0xFFFF_FFFFL

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
                        .setBufferSizeInBytes(speakerBufferBytes(trackMin))
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
                startEarly(out)
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

    /** Lets the track play once [startThresholdFrames] arrived instead of waiting for a full buffer. */
    private fun startEarly(out: AudioTrack) {
        try {
            out.setStartThresholdInFrames(startThresholdFrames(out.bufferSizeInFrames))
        } catch (error: RuntimeException) {
            Log.w(TAG, "WA call audio: the start threshold was refused: ${error.message}")
        }
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
        val silence = SilenceWatch()
        while (!Thread.currentThread().isInterrupted) {
            val frame = ByteArray(FRAME_BYTES)
            var filled = 0
            while (filled < FRAME_BYTES) {
                val n = rec.read(frame, filled, FRAME_BYTES - filled)
                if (n <= 0) return
                filled += n
            }
            if (silence.frame(frame)) {
                Log.w(
                    TAG,
                    "WA call microphone: 2 s of exact digital silence. Android silences capture for an app it counts " +
                        "as background (no visible activity, or the microphone foreground service was not started " +
                        "while the app was in the foreground), or audio policy muted the source " +
                        "(another app holds the microphone). The peer hears nothing.",
                )
            }
            frames.push(frame)
        }
    }

    /**
     * Pulls peer frames and plays them, paced by the track (blocking writes), whatever their length. Ends with the
     * call. [PlayoutTrim] keeps the track from holding more than it needs, and [FillLog] writes the numbers to the
     * log about every ten seconds.
     */
    private fun pumpSpeaker(
        out: AudioTrack,
        frames: CallFrames,
    ) {
        val frame = ByteArray(FRAME_BYTES)
        val trim = PlayoutTrim()
        val log = FillLog()
        var written = 0L
        var logged = System.nanoTime()
        try {
            val capacity = out.bufferSizeInFrames
            while (!Thread.currentThread().isInterrupted) {
                val n = frames.pull(frame)
                when {
                    n < 0 -> {
                        return
                    }

                    playable(n) -> {
                        val frameMs = n / BYTES_PER_MS
                        val fill = fillMs(written, out.playbackHeadPosition, capacity)
                        val dropped = trim.drop(fill, frameMs)
                        log.frame(fill, frameMs, dropped)
                        if (!dropped) {
                            // Blocking: the track drains in real time and paces this thread, so a burst of frames
                            // from the network waits in the client queue (which keeps only the newest) instead of
                            // being cut off at the track's buffer. `stop` releases a waiting write.
                            val wrote = out.write(frame, 0, n, AudioTrack.WRITE_BLOCKING)
                            if (wrote > 0) written += wrote / 2
                        }
                    }

                    else -> {
                        LockSupport.parkNanos(IDLE_POLL_MS * 1_000_000L)
                    }
                }
                val now = System.nanoTime()
                if (now - logged >= LOG_EVERY_MS * 1_000_000L) {
                    logged = now
                    Log.i(TAG, log.line(out.underrunCount, capacity * 1000 / SAMPLE_RATE))
                }
            }
        } catch (_: RuntimeException) {
            // `stop` released the track under this thread: the call is over.
        }
    }
}
