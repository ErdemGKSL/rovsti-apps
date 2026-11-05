package dev.rovsti.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import java.util.concurrent.ArrayBlockingQueue

/**
 * Microphone and speaker of a WhatsApp call (AD-WA5). The gateway runs the call; the
 * phone only captures and plays 16 kHz mono PCM16 in 960-sample (60 ms) frames.
 *
 * Frames cross JNI as base64 strings (the seam passes only strings, ints and booleans):
 * Rust polls [readFrame] and pushes with [playFrame]. Needs `RECORD_AUDIO`.
 */
object WaCallAudio {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 960
    private const val FRAME_BYTES = FRAME_SAMPLES * 2

    /** About half a second per direction; older frames are dropped, never queued behind. */
    private const val QUEUE_FRAMES = 8

    private val mic = ArrayBlockingQueue<String>(QUEUE_FRAMES)
    private val lock = Any()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var reader: Thread? = null
    private var previousMode = AudioManager.MODE_NORMAL

    /** Pure: the speaker buffer size in bytes for a minimum of [minBytes]: at least four frames. */
    fun bufferBytes(minBytes: Int): Int = maxOf(minBytes, FRAME_BYTES * 4)

    @SuppressLint("MissingPermission") // checked just above
    fun start(context: Context): Boolean = synchronized(lock) {
        if (record != null) return true
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
            val recordMin = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val trackMin = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (recordMin <= 0 || trackMin <= 0) return false
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format.setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(bufferBytes(recordMin))
                .build()
            val out = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(format.setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
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
            mic.clear()
            rec.startRecording()
            out.play()
            record = rec
            track = out
            reader = Thread({ pump(rec) }, "wa-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (_: RuntimeException) {
            stopLocked(context)
            false
        }
    }

    fun stop(context: Context): Boolean = synchronized(lock) {
        val was = record != null
        stopLocked(context)
        was
    }

    private fun stopLocked(context: Context) {
        reader?.interrupt()
        reader = null
        try { record?.stop() } catch (_: RuntimeException) {}
        record?.release()
        record = null
        try { track?.stop() } catch (_: RuntimeException) {}
        track?.release()
        track = null
        mic.clear()
        context.getSystemService(AudioManager::class.java)?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it.clearCommunicationDevice()
            it.mode = previousMode
        }
    }

    /** Pure: the communication-device type to look for. */
    fun wantedType(speaker: Boolean): Int =
        if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

    /** Pure: the index in [types] of the built-in speaker or earpiece, or -1. */
    fun pickDevice(types: List<Int>, speaker: Boolean): Int = types.indexOf(wantedType(speaker))

    /**
     * Loudspeaker or earpiece. API 31+ picks the built-in device from `availableCommunicationDevices`;
     * older releases use `isSpeakerphoneOn`. False when the device has no such route.
     */
    fun setSpeaker(context: Context, speaker: Boolean): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audio.availableCommunicationDevices
                val index = pickDevice(devices.map { it.type }, speaker)
                if (index < 0) return false
                audio.setCommunicationDevice(devices[index])
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = speaker
                true
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    /** The next microphone frame as base64, or "" when none is ready. */
    fun readFrame(): String = mic.poll() ?: ""

    /** Plays one base64 frame. False when the speaker is stopped or the frame is malformed. */
    fun playFrame(base64: String): Boolean {
        val out = track ?: return false
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (bytes.size != FRAME_BYTES) return false
        // A blocking write would stall Rust's pump while the buffer is full; drop instead.
        return out.write(bytes, 0, bytes.size, AudioTrack.WRITE_NON_BLOCKING) == bytes.size
    }

    /** Reads PCM in whole frames and queues them; a full queue drops the oldest frame. */
    private fun pump(rec: AudioRecord) {
        val frame = ByteArray(FRAME_BYTES)
        while (!Thread.currentThread().isInterrupted) {
            var filled = 0
            while (filled < FRAME_BYTES) {
                val n = rec.read(frame, filled, FRAME_BYTES - filled)
                if (n <= 0) return
                filled += n
            }
            val text = Base64.encodeToString(frame, Base64.NO_WRAP)
            if (!mic.offer(text)) {
                mic.poll()
                mic.offer(text)
            }
        }
    }
}
