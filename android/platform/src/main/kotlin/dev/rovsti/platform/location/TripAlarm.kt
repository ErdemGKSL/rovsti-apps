package dev.rovsti.platform.location

// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/TripAlarm.kt

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import dev.rovsti.bridge.AlarmActivity
import dev.rovsti.bridge.TripAlarmReceiver
import dev.rovsti.bridge.TripService
import dev.rovsti.core.log.RovstiLog
import dev.rovsti.platform.Headless
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.R
import dev.rovsti.platform.notify.NotificationIcon
import dev.rovsti.platform.notify.NotificationKind
import dev.rovsti.platform.notify.PlatformChannels
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors

/** Snooze length when Rust gives no answer (library missing), and the bounds of any snooze. */
internal const val TRIP_DEFAULT_SNOOZE_MIN = 5
internal const val TRIP_MAX_SNOOZE_MIN = 15

/** The alarm stops by itself after this long (the plan's `ring_timeout_ms` wins); the notification stays. */
internal const val TRIP_RING_CAP_MS = 10 * 60 * 1000L

/** Pure: the time [minutes] (clamped to 1..15) after [nowMs]. */
internal fun snoozeAt(
    nowMs: Long,
    minutes: Int,
): Long = nowMs + minutes.coerceIn(1, TRIP_MAX_SNOOZE_MIN) * 60_000L

/**
 * Pure: when to re-ring after Rust's answer to the `snooze` action (`{"ring_at_ms": n}`). No answer at
 * all falls back to the default snooze; an answer without a positive `ring_at_ms` means Rust refuses
 * another snooze, and the caller dismisses (null).
 */
internal fun snoozeAt(
    answer: String?,
    nowMs: Long,
): Long? {
    if (answer.isNullOrBlank()) return snoozeAt(nowMs, TRIP_DEFAULT_SNOOZE_MIN)
    return try {
        JSONObject(answer).optLong("ring_at_ms", 0L).takeIf { it > nowMs }
    } catch (_: JSONException) {
        snoozeAt(nowMs, TRIP_DEFAULT_SNOOZE_MIN)
    }
}

/** Pure: the `trip_alarm` event payload; [atMs] only for `snoozed` and `timed_out`. */
internal fun tripAlarmEventJson(
    event: String,
    atMs: Long? = null,
): String {
    val o = JSONObject().put("event", event)
    if ((event == "snoozed" || event == "timed_out") && atMs != null) o.put("at_ms", atMs)
    return o.toString()
}

/**
 * Pure: when to re-ring after Rust's answer to the `timeout` action. Same shape as a snooze answer: no
 * answer means the default snooze, `{"silenced":true}` (Rust's re-ring cap) means do not ring again (null).
 */
internal fun timeoutRingAt(
    answer: String?,
    nowMs: Long,
): Long? = snoozeAt(answer, nowMs)

/** Pure: when the process-death guard re-rings: the ring timeout plus the default snooze after [nowMs]. */
internal fun guardRingAt(
    nowMs: Long,
    ringTimeoutMs: Long,
): Long = snoozeAt(nowMs + ringTimeoutMs, TRIP_DEFAULT_SNOOZE_MIN)

/** Pure: the `trip_status` payload when the full-screen intent is not allowed (the UI shows a settings row). */
internal fun fullScreenErrorJson(): String = JSONObject().put("state", "error").put("error", "full_screen").toString()

/** Pure: the `trip_alarm` headless request. */
internal fun tripAlarmRequestJson(
    dir: String,
    action: String,
): String = JSONObject().put("dir", dir).put("action", action).toString()

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
    const val CHANNEL = PlatformChannels.TRIP_ALARM
    private const val OLD_CHANNEL = PlatformChannels.TRIP_ALARM_OLD
    const val NOTIFICATION_ID = 0x5441 // "TA"
    const val ACTION_RING = "dev.rovsti.trip.RING"
    const val ACTION_SNOOZE = "dev.rovsti.trip.SNOOZE"
    const val ACTION_DISMISS = "dev.rovsti.trip.DISMISS"
    const val ACTION_CLOSE = "dev.rovsti.trip.CLOSE_ALARM"
    const val EVENT = "trip_alarm"

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()

    // A getter: the main looper is the one of the running process (tests recreate it).
    private val main get() = Handler(Looper.getMainLooper())
    private var sound: AlarmSound? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var capTask: Runnable? = null
    private var plan = AlarmPlan()

    /** Bumped when the user answers, so a timeout answer arriving late does not re-arm a dismissed alarm. */
    @Volatile private var epoch = 0

    /** True between [ring] and the user's answer. */
    @Volatile var ringing = false
        private set

    /** A test ring does not tell Rust about its dismissal. */
    @Volatile private var testRing = false

    /**
     * Rings now. A test ring with [planOverride] (the plan JSON plus `sound` / `vibrate`) uses it for the
     * plan and the channels instead of the last trip's saved settings. Idempotent: a second call while ringing does
     * nothing.
     */
    fun ring(
        context: Context,
        test: Boolean = false,
        planOverride: String? = null,
    ) {
        val app = context.applicationContext
        var fullScreenOk = true
        synchronized(lock) {
            if (ringing) return
            ringing = true
            testRing = test
            epoch++
            cancelSnooze(app)
            val now = TripClock.nowMillis()
            val override = if (test) planOverride else null
            val ringPlan = AlarmPlan.parse(override ?: TripPrefs.alarmPlan(app))
            plan = ringPlan
            // Guard: if this process dies mid-ring, this re-rings (same PendingIntent as the snooze).
            if (!test) armSnooze(app, guardRingAt(now, ringPlan.ringTimeoutMs))
            fullScreenOk = postNotification(app)
            val (wantSound, vibrate) = alarmChannels(override ?: readPrefs(app))
            val out = AlarmSound(app, ringPlan)
            this.sound = out
            try {
                if (wantSound) out.start()
                if (vibrate) out.vibrate()
            } catch (e: RuntimeException) {
                RovstiLog.w("trip", "alarm start failed", e)
            }
            holdWakeLock(app, ringPlan.ringTimeoutMs)
            val cap = Runnable { onRingTimeout(app) }
            capTask = cap
            main.postDelayed(cap, ringPlan.ringTimeoutMs)
        }
        if (!fullScreenOk) PlatformEvents.emit(TripService.EVENT_STATUS, fullScreenErrorJson())
        PlatformEvents.emit(EVENT, tripAlarmEventJson("ringing"))
    }

    /**
     * The ring ran for the whole timeout unanswered: silence everything (the notification stays), ask Rust
     * when to ring again and arm it, falling back to the default snooze. A test ring just stops.
     */
    private fun onRingTimeout(app: Context) {
        val test = testRing
        if (test) {
            stopRinging(app)
            return
        }
        val mine: Int
        synchronized(lock) {
            if (!ringing) return
            silence(app)
            mine = epoch
        }
        app.sendBroadcast(Intent(ACTION_CLOSE).setPackage(app.packageName))
        io.execute {
            val now = TripClock.nowMillis()
            val answer = askRust(app, "timeout")
            if (epoch != mine) return@execute // dismissed or snoozed meanwhile
            val at = timeoutRingAt(answer, now)
            if (at == null) {
                cancelSnooze(app) // Rust's re-ring cap: the notification stays, nothing rings again
            } else {
                armSnooze(app, at)
                PlatformEvents.emit(EVENT, tripAlarmEventJson("timed_out", at))
            }
        }
    }

    /** Dismiss from a notification action, the activity or Rust. [done] runs after the headless call. */
    fun dismiss(
        context: Context,
        done: () -> Unit = {},
    ) {
        val app = context.applicationContext
        val test = testRing
        stopRinging(app)
        cancelSnooze(app)
        io.execute {
            try {
                if (!test) askRust(app, "dismiss")
                PlatformEvents.emit(EVENT, tripAlarmEventJson("dismissed"))
            } finally {
                done()
            }
        }
    }

    /** Snooze: Rust decides when to re-ring (it caps the number of snoozes); no more snoozes means dismiss. */
    fun snooze(
        context: Context,
        done: () -> Unit = {},
    ) {
        val app = context.applicationContext
        val test = testRing
        stopRinging(app)
        io.execute {
            try {
                val now = TripClock.nowMillis()
                val answer = if (test) null else askRust(app, "snooze")
                val at = snoozeAt(answer, now)
                if (at == null) {
                    if (!test) askRust(app, "dismiss")
                    PlatformEvents.emit(EVENT, tripAlarmEventJson("dismissed"))
                } else {
                    armSnooze(app, at)
                    PlatformEvents.emit(EVENT, tripAlarmEventJson("snoozed", at))
                }
            } finally {
                done()
            }
        }
    }

    /** The `trip_alarm` headless call for the last trip's data directory; null without a trip or without an answer. */
    private fun askRust(
        app: Context,
        action: String,
    ): String? = TripPrefs.dir(app)?.let { Headless.call(EVENT, tripAlarmRequestJson(it, action)) }

    /** Arms the re-ring at [atMs] (epoch ms). `setAlarmClock` first; exact-while-idle, then inexact, as fallbacks. */
    fun armSnooze(
        context: Context,
        atMs: Long,
    ): Boolean {
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
        epoch++
        silence(app)
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        app.sendBroadcast(Intent(ACTION_CLOSE).setPackage(app.packageName))
    }

    /** The title and text the activity and the notification show, in the phone's language. */
    fun message(context: Context): Pair<String, String> =
        context.getString(R.string.alarm_title) to context.getString(R.string.alarm_text)

    /** The Stops preferences file in the data directory of the last trip; null before the first trip. */
    private fun readPrefs(context: Context): String? =
        try {
            TripPrefs.dir(context)?.let {
                java.io
                    .File(it, "stops-prefs.json")
                    .takeIf { f -> f.isFile }
                    ?.readText()
            }
        } catch (_: java.io.IOException) {
            null
        }

    /**
     * Restores a volume a dead process left raised (the saved level survives in SharedPreferences) when
     * nothing rings. Call from service start, receivers and bridge init.
     */
    fun recover(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (!ringing) AlarmSound.restoreVolume(app)
        }
    }

    /** Releases player, tone, focus, volume, vibration and wake lock; idempotent, each step in its own `finally`. */
    private fun silence(context: Context) {
        synchronized(lock) {
            ringing = false
            val out = sound
            sound = null
            try {
                capTask?.let(main::removeCallbacks)
                capTask = null
            } finally {
                try {
                    out?.stop() // player, ToneGenerator, focus
                } finally {
                    try {
                        out?.cancelVibration()
                    } finally {
                        try {
                            AlarmSound.restoreVolume(context)
                        } finally {
                            try {
                                wakeLock?.takeIf { it.isHeld }?.release()
                            } finally {
                                wakeLock = null
                            }
                        }
                    }
                }
            }
        }
    }

    private fun holdWakeLock(
        context: Context,
        timeoutMs: Long,
    ) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        wakeLock =
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rovsti:trip-alarm").apply {
                setReferenceCounted(false)
                acquire(timeoutMs + 5_000L)
            }
    }

    /** Posts the alarm notification; false when Android 14+ refuses full-screen intents (it still posts). */
    private fun postNotification(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        manager.deleteNotificationChannel(OLD_CHANNEL)
        PlatformChannels.ensure(context, CHANNEL)
        val fullScreenOk =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()
        if (!fullScreenOk) RovstiLog.w("trip", "full-screen intent not allowed; heads-up only")
        val (title, text) = message(context)
        val screen =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val snooze =
            Notification.Action
                .Builder(
                    null,
                    context.getString(R.string.alarm_snooze),
                    receiverIntent(context, ACTION_SNOOZE),
                ).build()
        val dismiss =
            Notification.Action
                .Builder(
                    null,
                    context.getString(R.string.alarm_dismiss),
                    receiverIntent(context, ACTION_DISMISS),
                ).build()
        val notification =
            Notification
                .Builder(context, CHANNEL)
                .let { NotificationIcon.apply(it, context, NotificationKind.ARRIVAL_ALARM) }
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                .addAction(snooze)
                .addAction(dismiss)
                .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            RovstiLog.w("trip", "alarm notification refused", e)
        }
        return fullScreenOk
    }

    private fun receiverIntent(
        context: Context,
        action: String,
    ): PendingIntent {
        val intent = Intent(context, TripAlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun launchIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        return PendingIntent.getActivity(
            context,
            1,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
