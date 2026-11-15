package dev.rosty.platform.location

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONException
import org.json.JSONObject

/** What the system alarm-sound picker answered. */
sealed interface AlarmPick {
    /** Closed without a choice: nothing changes. */
    data object Cancelled : AlarmPick

    /** The system's default alarm: the app stores "no choice". */
    data object Default : AlarmPick

    data class Chosen(
        val uri: String,
        /** The ringtone's own title; empty when the system had none. */
        val title: String,
    ) : AlarmPick
}

/**
 * The Settings side of the alarm (Stops E7.10, S12-S14): the sound picker as an activity-result intent, the short volume
 * test through [AlarmSound] (the same focus, stream and ramp as the real ring), the vibration preview and a test ring.
 * The GPUI bridge's `previewAlarmSound`, `previewVibration`, `pickAlarmSound` and `tripTestAlarm`, without the activity
 * statics. Every call is made on the main thread; none of them throws.
 */
object AlarmPreview {
    private val main get() = Handler(Looper.getMainLooper())
    private var playing: AlarmSound? = null
    private var stopTask: Runnable? = null

    /** The system alarm picker; [current] null (or blank) marks the default alarm. No silent entry. */
    fun pickerIntent(current: String?): Intent {
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, default)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                if (current.isNullOrBlank()) default else Uri.parse(current),
            )
    }

    /** The picker's answer (the same rules as [AlarmOptions.pickedJson]: the default alarm is no choice). */
    fun picked(
        context: Context,
        resultCode: Int,
        data: Intent?,
    ): AlarmPick {
        val hasExtra = data?.hasExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) == true

        @Suppress("DEPRECATION")
        val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        return pickOf(AlarmOptions.pickedJson(context, resultCode, uri, hasExtra))
    }

    /** Plays the sound of [planJson] (`stops.alarm_plan`'s plan) for [ms]; false while the trip alarm rings. */
    fun playSound(
        context: Context,
        planJson: String,
        ms: Long = TEST_MS,
    ): Boolean {
        if (TripAlarm.ringing) return false
        stopSound()
        val sound = AlarmSound(context.applicationContext, AlarmPlan.parse(planJson))
        playing = sound
        sound.start()
        val stop = Runnable { stopSound() }
        stopTask = stop
        main.postDelayed(stop, ms.coerceIn(MIN_MS, MAX_MS))
        return true
    }

    /** Stops a test sound still playing and gives the user's volume back (leaving the page calls it). */
    fun stopSound() {
        stopTask?.let(main::removeCallbacks)
        stopTask = null
        playing?.stop()
        playing = null
    }

    /** True while a test sound plays. */
    val isPlaying: Boolean get() = playing != null

    /** One run of [pattern] with alarm attributes; false without a vibrator. */
    fun vibrate(
        context: Context,
        pattern: List<Long>,
    ): Boolean = AlarmOptions.previewVibration(context, pattern.toLongArray())

    /** Rings once as a test with [testRingJson] (`stops.alarm_plan`'s `test_ring`: the plan plus `sound` / `vibrate`). */
    fun testRing(
        context: Context,
        testRingJson: String,
    ): Boolean {
        if (TripAlarm.ringing) return false
        TripAlarm.ring(context, test = true, planOverride = testRingJson)
        return true
    }

    /** The `alarm_sound_picked` JSON as a pick (pure). */
    internal fun pickOf(json: String): AlarmPick =
        try {
            val o = JSONObject(json)
            when {
                o.optBoolean("cancelled", false) -> AlarmPick.Cancelled
                o.isNull("uri") -> AlarmPick.Default
                else -> AlarmPick.Chosen(o.getString("uri"), o.optString("title", ""))
            }
        } catch (_: JSONException) {
            AlarmPick.Cancelled
        }

    /** The volume test of the GPUI app: three seconds. */
    const val TEST_MS = 3_000L
    private const val MIN_MS = 500L
    private const val MAX_MS = 10_000L
}
