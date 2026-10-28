package dev.rovsti.bridge

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibration on connect and on end (story 16.8, FR-71). A single predefined click, played only when the
 * settings mirror says so. `VIBRATE` is a normal permission (14.15). These are the vibrate options, not
 * the app's UI ticks: those are Rust's and follow the Haptics level.
 */
object Haptics {
    /** Pure: whether to vibrate for [event] (`connect` or `end`) with these settings. */
    fun wants(values: SettingsValues, event: String): Boolean = when (event) {
        "connect" -> values.vibrateConnect
        "end" -> values.vibrateEnd
        else -> false
    }

    fun click(context: Context): Boolean = try {
        val vibrator: Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        vibrator != null
    } catch (_: RuntimeException) {
        false
    }

    fun forEvent(context: Context, event: String): Boolean = wants(SettingsMirror.values, event) && click(context)
}
