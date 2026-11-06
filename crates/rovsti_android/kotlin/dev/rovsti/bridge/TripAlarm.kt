package dev.rovsti.bridge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors

/** Snooze length when Rust gives no answer (library missing), and the bounds of any snooze. */
internal const val TRIP_DEFAULT_SNOOZE_MIN = 5
internal const val TRIP_MAX_SNOOZE_MIN = 15

/** The alarm stops by itself after this long; the notification stays. */
internal const val TRIP_RING_CAP_MS = 10 * 60 * 1000L

/** Pure: the time [minutes] (clamped to 1..15) after [nowMs]. */
internal fun snoozeAt(nowMs: Long, minutes: Int): Long = nowMs + minutes.coerceIn(1, TRIP_MAX_SNOOZE_MIN) * 60_000L

/**
 * Pure: when to re-ring after Rust's answer to the `snooze` action (`{"ring_at_ms": n}`). No answer at
 * all falls back to the default snooze; an answer without a positive `ring_at_ms` means Rust refuses
 * another snooze, and the caller dismisses (null).
 */
internal fun snoozeAt(answer: String?, nowMs: Long): Long? {
    if (answer.isNullOrBlank()) return snoozeAt(nowMs, TRIP_DEFAULT_SNOOZE_MIN)
    return try {
        JSONObject(answer).optLong("ring_at_ms", 0L).takeIf { it > nowMs }
    } catch (_: JSONException) {
        snoozeAt(nowMs, TRIP_DEFAULT_SNOOZE_MIN)
    }
}

/** Pure: the `trip_alarm` event payload; [atMs] only for `snoozed`. */
internal fun tripAlarmEventJson(event: String, atMs: Long? = null): String {
    val o = JSONObject().put("event", event)
    if (event == "snoozed" && atMs != null) o.put("at_ms", atMs)
    return o.toString()
}

/** Pure: the `trip_alarm` headless request. */
internal fun tripAlarmRequestJson(dir: String, action: String): String =
    JSONObject().put("dir", dir).put("action", action).toString()

/** Pure: which parts of the alarm the user kept on, from `stops-prefs.json` (both on when unreadable). */
internal fun alarmChannels(prefsJson: String?): Pair<Boolean, Boolean> {
    if (prefsJson.isNullOrBlank()) return true to true
    return try {
        val o = JSONObject(prefsJson)
        val sound = o.optBoolean("sound", true)
        val vibrate = o.optBoolean("vibrate", true)
        // Never silent: Stops refuses to turn both off, and a hand-edited file gets the sound back.
        if (!sound && !vibrate) true to vibrate else sound to vibrate
    } catch (_: JSONException) {
        true to true
    }
}

/**
 * The destination alarm (AD-ST3). Needs no GPUI: a `CATEGORY_ALARM` notification with a full-screen intent
 * to [AlarmActivity], an alarm-stream ringtone, a looping vibration and a wake lock capped at 10 minutes.
 * Snoozing re-rings through `AlarmManager.setAlarmClock`, which Doze lets through.
 */
object TripAlarm {
    const val CHANNEL = "trip_alarm"
    const val NOTIFICATION_ID = 0x5441 // "TA"
    const val ACTION_RING = "dev.rovsti.trip.RING"
    const val ACTION_SNOOZE = "dev.rovsti.trip.SNOOZE"
    const val ACTION_DISMISS = "dev.rovsti.trip.DISMISS"
    const val ACTION_CLOSE = "dev.rovsti.trip.CLOSE_ALARM"
    const val EVENT = "trip_alarm"
    private val VIBRATION = longArrayOf(0, 700, 500, 700, 1500)

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var capTask: Runnable? = null

    /** True between [ring] and the user's answer. */
    @Volatile var ringing = false
        private set

    /** A test ring does not tell Rust about its dismissal. */
    @Volatile private var testRing = false

    /** Rings now. Idempotent: a second call while ringing does nothing. */
    fun ring(context: Context, test: Boolean = false) {
        val app = context.applicationContext
        synchronized(lock) {
            if (ringing) return
            ringing = true
            testRing = test
            cancelSnooze(app)
            postNotification(app)
            val (sound, vibrate) = alarmChannels(readPrefs(app))
            if (sound) startSound(app)
            if (vibrate) startVibration(app)
            holdWakeLock(app)
            val cap = Runnable { silence(app) }
            capTask = cap
            main.postDelayed(cap, TRIP_RING_CAP_MS)
        }
        RovstiBridge.emit(EVENT, tripAlarmEventJson("ringing"))
    }

    /** Dismiss from a notification action, the activity or Rust. [done] runs after the headless call. */
    fun dismiss(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext
        val test = testRing
        stopRinging(app)
        cancelSnooze(app)
        io.execute {
            try {
                if (!test) TripPrefs.dir(app)?.let { Headless.call(EVENT, tripAlarmRequestJson(it, "dismiss")) }
                RovstiBridge.emit(EVENT, tripAlarmEventJson("dismissed"))
            } finally {
                done()
            }
        }
    }

    /** Snooze: Rust decides when to re-ring (it caps the number of snoozes); no more snoozes means dismiss. */
    fun snooze(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext
        val test = testRing
        stopRinging(app)
        io.execute {
            try {
                val now = System.currentTimeMillis()
                val answer = if (test) null else TripPrefs.dir(app)?.let { Headless.call(EVENT, tripAlarmRequestJson(it, "snooze")) }
                val at = snoozeAt(answer, now)
                if (at == null) {
                    if (!test) TripPrefs.dir(app)?.let { Headless.call(EVENT, tripAlarmRequestJson(it, "dismiss")) }
                    RovstiBridge.emit(EVENT, tripAlarmEventJson("dismissed"))
                } else {
                    armSnooze(app, at)
                    RovstiBridge.emit(EVENT, tripAlarmEventJson("snoozed", at))
                }
            } finally {
                done()
            }
        }
    }

    /** Arms the re-ring at [atMs] (epoch ms). `setAlarmClock` first; exact-while-idle, then inexact, as fallbacks. */
    fun armSnooze(context: Context, atMs: Long): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        val ring = receiverIntent(context, ACTION_RING)
        return try {
            if (alarms.exactAllowed()) {
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, launchIntent(context)), ring)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, ring)
            }
            true
        } catch (_: SecurityException) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, ring)
                true
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    fun cancelSnooze(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(receiverIntent(context, ACTION_RING))
    }

    /** Stops sound, vibration, wake lock and the notification; tells [AlarmActivity] to close. */
    fun stopRinging(context: Context) {
        val app = context.applicationContext
        silence(app)
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        app.sendBroadcast(Intent(ACTION_CLOSE).setPackage(app.packageName))
    }

    /** The title and text the activity shows. */
    fun message(): Pair<String, String> = "Wake up" to "You crossed the line"

    /** The Stops preferences file in the data directory of the last trip; null before the first trip. */
    private fun readPrefs(context: Context): String? = try {
        TripPrefs.dir(context)?.let { java.io.File(it, "stops-prefs.json").takeIf { f -> f.isFile }?.readText() }
    } catch (_: java.io.IOException) {
        null
    }

    private fun silence(context: Context) {
        synchronized(lock) {
            ringing = false
            capTask?.let(main::removeCallbacks)
            capTask = null
            try {
                player?.stop()
            } catch (_: IllegalStateException) {
            }
            player?.release()
            player = null
            context.getSystemService(VibratorManager::class.java)?.cancel()
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    private fun startSound(context: Context) {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: java.io.IOException) {
            android.util.Log.w("Rovsti", "alarm sound failed", e)
            player?.release()
            player = null
        } catch (e: RuntimeException) {
            android.util.Log.w("Rovsti", "alarm sound failed", e)
            player?.release()
            player = null
        }
    }

    private fun startVibration(context: Context) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(VIBRATION, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun holdWakeLock(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rovsti:trip-alarm").apply {
            setReferenceCounted(false)
            acquire(TRIP_RING_CAP_MS)
        }
    }

    private fun postNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Destination alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when you are about to arrive"
                setSound(null, null) // the sound is played on the alarm stream, not by the notification
                enableVibration(false)
            },
        )
        val (title, text) = message()
        val screen = PendingIntent.getActivity(
            context, 0, Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(Notification.Action.Builder(null, "Snooze", receiverIntent(context, ACTION_SNOOZE)).build())
            .addAction(Notification.Action.Builder(null, "Dismiss", receiverIntent(context, ACTION_DISMISS)).build())
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            android.util.Log.w("Rovsti", "alarm notification refused", e)
        }
    }

    private fun receiverIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, TripAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun launchIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1, context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Notification actions and the snooze alarm; declare it (not exported) in the host manifest. */
class TripAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val done = { pending.finish() }
        when (intent.action) {
            TripAlarm.ACTION_RING -> {
                TripAlarm.ring(context)
                done()
            }
            TripAlarm.ACTION_SNOOZE -> TripAlarm.snooze(context, done)
            TripAlarm.ACTION_DISMISS -> TripAlarm.dismiss(context, done)
            else -> done()
        }
    }
}
