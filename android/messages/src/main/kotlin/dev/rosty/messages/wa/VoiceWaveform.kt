package dev.rosty.messages.wa

import dev.rosty.core.model.wa.MediaWaveform
import dev.rosty.platform.audio.VoiceRecorder
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The waveform of a voice note recorded on the phone (E6-WA R3): the input level is read every
 * [VoiceRecording.TICK_MS] while it records ([level]), and [reduce] makes the [MediaWaveform.SAMPLES] bars WhatsApp
 * shows from those readings. Pure, so the numbers are pinned by JVM tests. It lives in Kotlin, beside the recorder
 * that samples it: `rosty_core` has no audio code it would belong with.
 */
object VoiceWaveform {
    /** Readings this far below full scale (and quieter) are silence. */
    const val FLOOR_DB = 50.0

    /**
     * The loudness of one reading, 0 to [MediaWaveform.MAX]: [amplitude] (0 to [VoiceRecorder.MAX_AMPLITUDE]) on a
     * decibel scale, [FLOOR_DB] below full scale reading as 0, so a quiet voice still moves the bars.
     */
    fun level(amplitude: Int): Int {
        if (amplitude <= 0) return 0
        val db =
            20 * log10(amplitude.coerceAtMost(VoiceRecorder.MAX_AMPLITUDE) / VoiceRecorder.MAX_AMPLITUDE.toDouble())
        return ((db + FLOOR_DB) / FLOOR_DB * MediaWaveform.MAX).roundToInt().coerceIn(0, MediaWaveform.MAX)
    }

    /**
     * The [MediaWaveform.SAMPLES] bars of a take from its [levels] (each 0 to 100, oldest first): each bar is the mean
     * of the readings of its slice of the take (a take with fewer readings than bars repeats them), then every bar is
     * scaled so the loudest one is 100. A silent take is all zeros; no readings at all is all zeros too.
     */
    fun reduce(levels: List<Int>): ByteArray {
        val out = IntArray(MediaWaveform.SAMPLES)
        val n = levels.size
        if (n > 0) {
            for (i in out.indices) {
                val from = i * n / MediaWaveform.SAMPLES
                val until = maxOf(from + 1, (i + 1) * n / MediaWaveform.SAMPLES)
                var sum = 0L
                for (j in from until until) sum += levels[j].coerceIn(0, MediaWaveform.MAX)
                out[i] = (sum.toDouble() / (until - from)).roundToInt()
            }
        }
        val peak = out.max()
        return ByteArray(out.size) { i ->
            val scaled = if (peak > 0) (out[i] * MediaWaveform.MAX.toDouble() / peak).roundToInt() else 0
            scaled.coerceIn(0, MediaWaveform.MAX).toByte()
        }
    }

    /** The bars of a waveform as fractions 0..1, for drawing. */
    fun bars(samples: ByteArray): List<Float> = samples.map { it.toFloat() / MediaWaveform.MAX }
}
