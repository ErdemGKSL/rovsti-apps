package dev.rovsti.platform.location

// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/AlarmSound.kt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import dev.rovsti.platform.PlatformPrefs
import org.json.JSONException
import org.json.JSONObject

/**
 * The alarm policy; the defaults are identical to Rust's `AlarmPlan::default()` so the alarm rings the
 * same even when Rust never answered (the cargo "Kotlin contract" test keeps them in sync).
 */
internal data class AlarmPlan(
    val volumePct: Int = 80,
    val rampMs: Long = 4_000L,
    val ringTimeoutMs: Long = 600_000L,
    val soundOrder: List<String> =
        listOf("user_alarm", "default_alarm", "default_ringtone", "default_notification", "tone"),
    val vibratePatternMs: LongArray = longArrayOf(0, 700, 500, 700, 1500),
    /** The sound the user chose in Stops settings; null uses the system alarm. */
    val userSoundUri: String? = null,
) {
    companion object {
        /** Missing or broken JSON, and missing fields, keep the defaults. */
        fun parse(json: String?): AlarmPlan {
            val d = AlarmPlan()
            if (json.isNullOrBlank()) return d
            return try {
                val o = JSONObject(json)
                val order = o.optJSONArray("sound_order")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                val pattern = o.optJSONArray("vibrate_pattern_ms")?.let { a -> LongArray(a.length()) { a.optLong(it) } }
                AlarmPlan(
                    volumePct = o.optInt("volume_pct", d.volumePct).coerceIn(1, 100),
                    rampMs = o.optLong("ramp_ms", d.rampMs).coerceIn(0L, 5_000L),
                    ringTimeoutMs = o.optLong("ring_timeout_ms", d.ringTimeoutMs).coerceAtLeast(10_000L),
                    soundOrder = order?.takeIf { it.isNotEmpty() } ?: d.soundOrder,
                    vibratePatternMs = pattern?.takeIf { it.size >= 2 } ?: d.vibratePatternMs,
                    userSoundUri = o.optString("user_sound_uri", "").takeIf { it.isNotBlank() },
                )
            } catch (_: JSONException) {
                d
            }
        }
    }
}

/** Pure mirror of Rust `volume_target`: never below the user's level, at least [pct] of [max], within min..max. */
internal fun alarmVolumeTarget(
    current: Int,
    min: Int,
    max: Int,
    pct: Int = 80,
): Int {
    if (max <= 0) return max
    val floor = (max * pct + 99) / 100
    return maxOf(current, floor).coerceIn(minOf(min, max), max)
}

/** Pure mirror of Rust `ramp_volume`: linear from half of [target] (>= [min], >= 1) to [target] over [rampMs]. */
internal fun alarmRampVolume(
    target: Int,
    min: Int,
    elapsedMs: Long,
    rampMs: Long,
): Int {
    val start = ((target + 1) / 2).coerceAtLeast(min).coerceAtLeast(1).coerceAtMost(maxOf(target, 1))
    if (elapsedMs >= rampMs || target <= start) return target
    val frac = elapsedMs.toDouble() / rampMs.toDouble()
    return Math.round(start + (target - start) * frac).toInt().coerceIn(start, target)
}

/** Pure: the sound sources to try, in order, never empty and always ending in the tone. */
internal fun alarmSoundOrder(plan: AlarmPlan): List<String> {
    val known = plan.soundOrder.filter { it in AlarmPlan().soundOrder }.distinct()
    return if (known.lastOrNull() == "tone") known else known.filter { it != "tone" } + "tone"
}

/**
 * The audible half of the trip alarm, as the stock alarm clock does it: one `USAGE_ALARM` attribute set
 * for sound, focus and vibration (so Android plays on the speaker and the headset alike, and Do Not
 * Disturb lets it through), exclusive transient audio focus (music pauses), the alarm stream raised
 * to at least 80 % and restored afterwards, and a fallback chain that always ends in a tone.
 * Not thread-safe; [TripAlarm] calls it under its lock.
 */
internal class AlarmSound(
    context: Context,
    private val plan: AlarmPlan,
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var ramp: Runnable? = null
    private var toneLoop: Runnable? = null
    private var active = false

    /** Index into [order] of the source that plays now. */
    private var sourceIndex = -1
    private val order = alarmSoundOrder(plan)

    fun start() {
        if (active) return
        active = true
        requestFocus()
        raiseVolume()
        playFrom(0)
    }

    /**
     * Plays the sound for [ms] milliseconds (volume as the plan says), then stops and restores the
     * user's volume. Not for use while the alarm rings; [TripAlarm] refuses a preview then.
     */
    fun preview(ms: Long) {
        start()
        main.postDelayed({ stop() }, ms.coerceIn(500L, 10_000L))
    }

    /** Idempotent; releases player, tone, focus and restores the user's volume. */
    fun stop() {
        active = false
        ramp?.let(main::removeCallbacks)
        ramp = null
        toneLoop?.let(main::removeCallbacks)
        toneLoop = null
        releasePlayer()
        try {
            tone?.stopTone()
        } catch (_: RuntimeException) {
        }
        tone?.release()
        tone = null
        restoreVolume(app)
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun vibrate() {
        val vibrator = app.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(plan.vibratePatternMs, 0)
        // A missing VIBRATE grant or a vibrator service in a bad state must not take the alarm down with it.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, ALARM_ATTRS)
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    fun cancelVibration() {
        try {
            app.getSystemService(VibratorManager::class.java)?.cancel()
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun requestFocus() {
        val manager = audio ?: return
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(ALARM_ATTRS)
                .setOnAudioFocusChangeListener { } // losing focus never silences the alarm
                .build()
        focus = request
        try {
            manager.requestAudioFocus(request)
        } catch (e: RuntimeException) {
            android.util.Log.w("Rovsti", "alarm focus failed", e)
        }
    }

    private fun raiseVolume() {
        val manager = audio ?: return
        val prefs = app.getSharedPreferences(PlatformPrefs.ALARM, Context.MODE_PRIVATE)
        val current = manager.getStreamVolume(AudioManager.STREAM_ALARM)
        // Keep the first saved level if a crashed ring left one behind.
        if (!prefs.contains(KEY_SAVED)) prefs.edit().putInt(KEY_SAVED, current).commit()
        val min =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                manager.getStreamMinVolume(AudioManager.STREAM_ALARM)
            } else {
                0
            }
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = alarmVolumeTarget(current, min, max, plan.volumePct)
        val begin = SystemClock.elapsedRealtime()
        val step =
            object : Runnable {
                override fun run() {
                    if (!active) return
                    val elapsed = SystemClock.elapsedRealtime() - begin
                    setAlarmVolume(manager, alarmRampVolume(target, min, elapsed, plan.rampMs))
                    if (elapsed < plan.rampMs) main.postDelayed(this, RAMP_STEP_MS)
                }
            }
        ramp = step
        step.run()
    }

    private fun playFrom(index: Int) {
        releasePlayer()
        var i = index
        while (active && i < order.size) {
            sourceIndex = i
            val ok =
                when (order[i]) {
                    "tone" -> startTone()
                    else -> startUri(i, uriFor(order[i]))
                }
            if (ok) return
            i++
        }
    }

    private fun uriFor(key: String): Uri? =
        try {
            when (key) {
                "user_alarm" -> {
                    plan.userSoundUri?.let(Uri::parse)
                        ?: RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
                }

                "default_alarm" -> {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                }

                "default_ringtone" -> {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                }

                "default_notification" -> {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }

                else -> {
                    null
                }
            }
        } catch (_: RuntimeException) {
            null
        }

    private fun startUri(
        index: Int,
        uri: Uri?,
    ): Boolean {
        if (uri == null) return false
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(ALARM_ATTRS)
            p.setOnErrorListener { _, what, extra ->
                android.util.Log.w("Rovsti", "alarm player error $what/$extra")
                // A failure after start moves on to the next source (posted: never release inside the callback).
                main.post { if (active && player === p && sourceIndex == index) playFrom(index + 1) }
                true
            }
            p.setDataSource(app, uri)
            p.isLooping = true
            p.prepare()
            p.start()
            player = p
            return true
        } catch (e: java.io.IOException) {
            android.util.Log.w("Rovsti", "alarm sound failed", e)
        } catch (e: RuntimeException) {
            android.util.Log.w("Rovsti", "alarm sound failed", e)
        }
        p.release()
        return false
    }

    private fun startTone(): Boolean {
        val generator =
            try {
                ToneGenerator(AudioManager.STREAM_ALARM, 100)
            } catch (e: RuntimeException) {
                android.util.Log.w("Rovsti", "alarm tone failed", e)
                return false
            }
        tone = generator
        val loop =
            object : Runnable {
                override fun run() {
                    if (!active) return
                    generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, TONE_MS)
                    main.postDelayed(this, TONE_MS + 200L)
                }
            }
        toneLoop = loop
        loop.run()
        return true
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        try {
            p.stop()
        } catch (_: IllegalStateException) {
        }
        p.release()
    }

    companion object {
        /** One attribute set for the player, the focus request, vibration and the notification channel. */
        val ALARM_ATTRS: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        private const val KEY_SAVED = "saved_volume"
        private const val RAMP_STEP_MS = 250L
        private const val TONE_MS = 1_000

        private fun setAlarmVolume(
            manager: AudioManager,
            volume: Int,
        ) {
            try {
                manager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
            } catch (e: SecurityException) {
                // e.g. a Do Not Disturb policy: ring at the user's level
                android.util.Log.w("Rovsti", "alarm volume refused", e)
            }
        }

        /** Puts the user's alarm volume back and forgets it. Safe to call when nothing was saved. */
        fun restoreVolume(context: Context) {
            val prefs = context.getSharedPreferences(PlatformPrefs.ALARM, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_SAVED)) return
            val saved = prefs.getInt(KEY_SAVED, -1)
            val manager = context.getSystemService(AudioManager::class.java)
            if (saved >= 0 && manager != null) setAlarmVolume(manager, saved)
            prefs.edit().remove(KEY_SAVED).commit()
        }
    }
}
