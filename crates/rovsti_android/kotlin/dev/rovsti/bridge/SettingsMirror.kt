package dev.rovsti.bridge

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Kotlin's copy of the settings the parts that run without the screen need (AD-30): proximity,
 * vibration, keypad tones, flip, the notification's Message action. Rust is the only writer of
 * truth and pushes [SettingsValues] on attach and after every change (`settingsSync`). Before the
 * first push the defaults equal v0.1 behaviour.
 */
data class SettingsValues(
    val proximity: Boolean = true,
    val flipToSilence: Boolean = false,
    val vibrateConnect: Boolean = false,
    val vibrateEnd: Boolean = false,
    val hasReplies: Boolean = false,
    /** null follows the system dial-pad tone setting. */
    val keypadTones: Boolean? = null,
    val haptics: String = "normal",
    val nameFormat: String = "first_last",
)

object SettingsMirror {
    private const val FILE = "rovsti_phone_settings"

    @Volatile var values = SettingsValues()
        private set

    /** The `SettingsSync` JSON of `rovsti_core::wire`; unknown or missing fields keep the v0.1 default. */
    fun parse(json: String): SettingsValues? = try {
        val o = JSONObject(json)
        SettingsValues(
            proximity = o.optBoolean("proximity", true),
            flipToSilence = o.optBoolean("flip_to_silence", false),
            vibrateConnect = o.optBoolean("vibrate_connect", false),
            vibrateEnd = o.optBoolean("vibrate_end", false),
            hasReplies = o.optBoolean("has_replies", false),
            keypadTones = if (o.has("keypad_tones") && !o.isNull("keypad_tones")) o.getBoolean("keypad_tones") else null,
            haptics = o.optString("haptics", "normal").ifEmpty { "normal" },
            nameFormat = o.optString("name_format", "first_last").ifEmpty { "first_last" },
        )
    } catch (_: JSONException) {
        null
    }

    /** The keypad tones the dial pad plays: the setting, else the system's. */
    fun effectiveKeypadTones(v: SettingsValues, systemTones: Boolean): Boolean = v.keypadTones ?: systemTones

    /** Stores a push. False when it could not be read or written. */
    fun apply(context: Context, json: String): Boolean {
        val parsed = parse(json) ?: return false
        values = parsed
        return try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("sync", json)
                .apply()
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Reads the last stored push so a process restart matches before Rust attaches. */
    fun restore(context: Context) {
        val stored = try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("sync", null)
        } catch (_: RuntimeException) {
            null
        } ?: return
        parse(stored)?.let { values = it }
    }

    /** `DTMF_TONE_WHEN_DIALING` of the system (default on when unreadable). */
    fun systemKeypadTones(context: Context): Boolean = try {
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.DTMF_TONE_WHEN_DIALING, 1) != 0
    } catch (_: RuntimeException) {
        true
    }

    /** Whether a flip sensor exists: the gravity sensor, or accelerometer as the fallback. */
    fun flipSupported(context: Context): Boolean = try {
        val sensors = context.getSystemService(android.hardware.SensorManager::class.java)
        sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null
    } catch (_: RuntimeException) {
        false
    }

    /** `{flip_supported, system_keypad_tones}` for `systemFacts`. */
    fun factsJson(flip: Boolean, systemTones: Boolean): String =
        JSONObject().put("flip_supported", flip).put("system_keypad_tones", systemTones).toString()
}
