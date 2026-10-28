package dev.rovsti.bridge

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * The one sound the app plays itself (AD-29): the DTMF tone of a pressed dial-pad key, on
 * `STREAM_DTMF`, when the effective Keypad tones value is on. Never for UI clicks. The generator is
 * created lazily and released after a quiet period.
 */
object KeypadTone {
    /** Digit `0`-`9`, `10` = `*`, `11` = `#` to a `ToneGenerator` tone; -1 for anything else. */
    fun toneFor(digit: Int): Int = when (digit) {
        in 0..9 -> ToneGenerator.TONE_DTMF_0 + digit
        10 -> ToneGenerator.TONE_DTMF_S
        11 -> ToneGenerator.TONE_DTMF_P
        else -> -1
    }

    private var generator: ToneGenerator? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val release = Runnable { synchronized(this) { generator?.release(); generator = null } }

    @Synchronized
    fun play(digit: Int): Boolean {
        if (digit < 0) {
            generator?.stopTone()
            return true
        }
        val tone = toneFor(digit)
        if (tone < 0) return false
        return try {
            val g = generator ?: ToneGenerator(AudioManager.STREAM_DTMF, 80).also { generator = it }
            g.startTone(tone, 150)
            main.removeCallbacks(release)
            main.postDelayed(release, 4_000)
            true
        } catch (_: RuntimeException) {
            generator = null
            false
        }
    }
}
