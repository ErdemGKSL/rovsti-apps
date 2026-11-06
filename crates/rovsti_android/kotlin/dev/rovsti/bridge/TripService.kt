package dev.rovsti.bridge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.IBinder
import android.os.Looper
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Shortest and longest interval the service asks the location provider for. */
internal const val TRIP_MIN_INTERVAL_MS = 1_000L
internal const val TRIP_MAX_INTERVAL_MS = 300_000L

/** Grace after the expected interval before the watchdog asks for a fix itself: interval / 4 within these bounds. */
internal const val TRIP_WATCHDOG_MIN_GRACE_MS = 5_000L
internal const val TRIP_WATCHDOG_MAX_GRACE_MS = 30_000L

/** Pure: [nextFixMs] limited to what a provider accepts. */
internal fun tripIntervalMs(nextFixMs: Long): Long = nextFixMs.coerceIn(TRIP_MIN_INTERVAL_MS, TRIP_MAX_INTERVAL_MS)

/** Pure: GPS when [highAccuracy], else the fused provider if the device has one, else network. */
internal fun tripProvider(highAccuracy: Boolean, hasFused: Boolean): String = when {
    highAccuracy -> LocationManager.GPS_PROVIDER
    hasFused -> LocationManager.FUSED_PROVIDER
    else -> LocationManager.NETWORK_PROVIDER
}

/** Pure: how long after the last fix the watchdog fires; mirrors `rovsti_geo::poll::retry_after`. */
internal fun tripWatchdogMs(intervalMs: Long): Long {
    val interval = tripIntervalMs(intervalMs)
    return interval + (interval / 4).coerceIn(5_000L, 30_000L)
}

/**
 * Pure: true when the system or the watchdog started the service, not the user. Such a start (a null
 * intent after a process death, a redelivery, a retry or the watchdog) must prefer the saved spec and
 * must never clear it.
 */
internal fun tripStartIsBackground(action: String?, flags: Int, hasSpecExtra: Boolean): Boolean =
    action == TripService.ACTION_WATCHDOG ||
        (flags and Service.START_FLAG_REDELIVERY) != 0 ||
        (flags and Service.START_FLAG_RETRY) != 0 ||
        !hasSpecExtra

/** Pure: the status Rust's UI maps to the "Android closed Stops" message. */
internal fun tripRestartErrorJson(): String = """{"state":"error","error":"restart"}"""

/** `AlarmManager.canScheduleExactAlarms`, which only exists (and is only needed) from Android 12. */
internal fun AlarmManager.exactAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()

/** What Rust asked for when the service was started or updated (`{dir,title,text,next_fix_ms,high_accuracy}`). */
internal data class TripSpec(
    val dir: String,
    val title: String,
    val text: String,
    val nextFixMs: Long,
    val highAccuracy: Boolean,
    /** Raw JSON of Rust's `AlarmPlan`, kept verbatim so the alarm can ring after a process restart. */
    val alarm: String? = null,
) {
    fun toJson(): String {
        val o = JSONObject().put("dir", dir).put("title", title).put("text", text)
            .put("next_fix_ms", nextFixMs).put("high_accuracy", highAccuracy)
        if (alarm != null) {
            try {
                o.put("alarm", JSONObject(alarm))
            } catch (_: JSONException) {
            }
        }
        return o.toString()
    }

    companion object {
        /** Pure: the spec in [json], or null when it is malformed or has no `dir`. */
        fun parse(json: String?): TripSpec? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val dir = o.optString("dir")
                if (dir.isEmpty()) return null
                TripSpec(
                    dir, o.optString("title", "Trip"), o.optString("text"),
                    o.optLong("next_fix_ms", 10_000L), o.optBoolean("high_accuracy", false),
                    o.optJSONObject("alarm")?.toString(),
                )
            } catch (_: JSONException) {
                null
            }
        }
    }
}

/** What Rust answers to `trip_fix` (`rovsti_geo::session::FixOut`). */
internal data class TripAnswer(
    val state: String,
    val nextFixMs: Long,
    val highAccuracy: Boolean,
    val ring: Boolean,
    val title: String,
    val text: String,
    /** Rust's own retry window (`retry_ms`); 0 when absent, then [tripWatchdogMs] applies. */
    val retryMs: Long = 0L,
) {
    val ended: Boolean get() = state == "ended"

    companion object {
        /** Pure: null for a missing or malformed answer (callers keep their current settings). */
        fun parse(raw: String?): TripAnswer? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                TripAnswer(
                    o.optString("state"), o.optLong("next_fix_ms", 10_000L), o.optBoolean("high_accuracy", false),
                    o.optBoolean("ring", false), o.optString("title"), o.optString("text"),
                    o.optLong("retry_ms", 0L).coerceAtLeast(0L),
                )
            } catch (_: JSONException) {
                null
            }
        }
    }
}

/** Pure: the `trip_fix` request (`rovsti_geo::session::FixIn` plus `dir`). */
internal fun tripFixJson(dir: String, lat: Double, lon: Double, accuracyM: Double, speedMps: Double?, atMs: Long): String {
    val o = JSONObject().put("dir", dir).put("lat", lat).put("lon", lon).put("accuracy_m", accuracyM).put("at_ms", atMs)
    if (speedMps != null && speedMps >= 0.0 && !speedMps.isNaN()) o.put("speed_mps", speedMps)
    return o.toString()
}

/** The spec of the running trip survives a process restart here. */
internal object TripPrefs {
    private const val FILE = "rovsti_trip"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(context: Context, spec: TripSpec) = prefs(context).edit().putString("spec", spec.toJson()).apply()

    fun load(context: Context): TripSpec? = TripSpec.parse(prefs(context).getString("spec", null))

    fun clear(context: Context) = prefs(context).edit().remove("spec").apply()

    /** The raw alarm plan JSON of the running trip, or null (the alarm then uses its built-in defaults). */
    fun alarmPlan(context: Context): String? = load(context)?.alarm

    /** The data directory the alarm hands back to Rust, from the last started trip. */
    fun dir(context: Context): String? = load(context)?.dir
}

/**
 * The Rovsti Stops trip service (AD-ST2). A foreground service of type `location`: it owns the location
 * updates and calls Rust for every fix through `Headless.call("trip_fix", ...)`. Rust owns the decision
 * (distance, next interval, ring); this class only applies it. The host manifest declares the service
 * with `foregroundServiceType="location"`, the `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`,
 * `ACCESS_FINE_LOCATION` and `POST_NOTIFICATIONS` permissions.
 */
class TripService : Service() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var locations: LocationManager
    private lateinit var alarms: AlarmManager
    @Volatile private var spec: TripSpec? = null
    private var foreground = false
    private var requested: Pair<Long, String>? = null
    private val listener = LocationListener { location -> worker.execute { onFix(location) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LocationManager::class.java)
        alarms = getSystemService(AlarmManager::class.java)
        running = true
        instance = this
        TripAlarm.recover(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(emitStopped = true)
            return START_NOT_STICKY
        }
        // A restart by the system or the watchdog carries no spec: use the one saved at the last start.
        val extra = TripSpec.parse(intent?.getStringExtra(EXTRA_SPEC))
        val background = tripStartIsBackground(intent?.action, flags, intent?.getStringExtra(EXTRA_SPEC) != null)
        val incoming = if (background) spec ?: TripPrefs.load(this) ?: extra else extra ?: spec ?: TripPrefs.load(this)
        if (incoming == null) {
            // startForegroundService promises a startForeground call; keep it, then leave.
            try {
                if (!foreground) promote(TripSpec("", "Trip", "", TRIP_MAX_INTERVAL_MS, false))
            } catch (_: RuntimeException) {
            }
            shutdown(emitStopped = false)
            return START_NOT_STICKY
        }
        spec = incoming
        TripPrefs.save(this, incoming)
        try {
            if (!foreground) {
                promote(incoming)
            } else {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(incoming.title, incoming.text))
            }
        } catch (e: RuntimeException) {
            // Not allowed to start a foreground service now, or no location permission.
            android.util.Log.w("Rovsti", "trip service could not go foreground", e)
            if (background) {
                // Keep the saved trip: only the user's Stop or Rust's `ended` clears it.
                emitStatus(tripRestartErrorJson())
                postRestartNotice()
                removeUpdates()
                cancelWatchdog()
                stopSelf()
                return START_NOT_STICKY
            }
            emitStatus(JSONObject().put("state", "error").put("error", "foreground").toString())
            shutdown(emitStopped = false)
            return START_NOT_STICKY
        }
        if (!requestUpdates(incoming.nextFixMs, incoming.highAccuracy)) {
            emitStatus(JSONObject().put("state", "error").put("error", "permission").toString())
            shutdown(emitStopped = false)
            return START_NOT_STICKY
        }
        armWatchdog(tripWatchdogMs(incoming.nextFixMs))
        if (intent?.action == ACTION_WATCHDOG) worker.execute { askForFix(incoming) }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        running = false
        foreground = false
        if (instance === this) instance = null
        removeUpdates()
        cancelWatchdog()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun promote(spec: TripSpec) {
        val notification = notification(spec.title, spec.text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID + 1)
    }

    /** Tells the user tracking stopped because Android refused a background start; one tap reopens Stops. */
    private fun postRestartNotice() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel("trip_problem", "Trip paused", NotificationManager.IMPORTANCE_HIGH))
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val open = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID + 1,
            Notification.Builder(this, "trip_problem")
                .setSmallIcon(NotificationIcon.small(this))
                .setContentTitle("Stops paused your trip")
                .setContentText("Tap to resume tracking.")
                .setCategory(Notification.CATEGORY_ERROR)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** (Re)registers for updates; false when location permission is missing. Cheap when nothing changed. */
    @Synchronized
    private fun requestUpdates(nextFixMs: Long, highAccuracy: Boolean): Boolean {
        val interval = tripIntervalMs(nextFixMs)
        val provider = tripProvider(highAccuracy, locations.hasProvider(LocationManager.FUSED_PROVIDER))
        if (requested == Pair(interval, provider)) return true
        return try {
            locations.removeUpdates(listener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request = LocationRequest.Builder(interval)
                    .setQuality(if (highAccuracy) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                    .setMinUpdateIntervalMillis(interval / 2)
                    .build()
                locations.requestLocationUpdates(provider, request, worker, listener)
            } else {
                @Suppress("DEPRECATION")
                locations.requestLocationUpdates(provider, interval, 0f, listener, Looper.getMainLooper())
            }
            requested = Pair(interval, provider)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            // The provider disappeared (location switched off): the watchdog keeps trying.
            requested = null
            armWatchdog(tripWatchdogMs(TRIP_MIN_INTERVAL_MS * 10))
            true
        }
    }

    @Synchronized
    private fun removeUpdates() {
        try {
            locations.removeUpdates(listener)
        } catch (_: RuntimeException) {
        }
        requested = null
    }

    /** Runs on [worker]: sends one fix to Rust and applies the answer. */
    private fun onFix(location: Location) {
        val current = spec ?: return
        val speed = if (location.hasSpeed()) location.speed.toDouble() else null
        val request = tripFixJson(
            current.dir, location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy.toDouble() else 1_000.0, speed, location.time,
        )
        val raw = Headless.call("trip_fix", request)
        val answer = TripAnswer.parse(raw)
        if (raw != null) emitStatus(raw)
        if (answer == null) {
            armWatchdog(tripWatchdogMs(current.nextFixMs))
            return
        }
        // Ring first: an ended trip stops the service right after, and the alarm must outlive it.
        if (answer.ring) TripAlarm.ring(applicationContext)
        if (answer.ended) {
            shutdown(emitStopped = false)
            return
        }
        val updated = current.copy(
            title = answer.title.ifEmpty { current.title }, text = answer.text,
            nextFixMs = answer.nextFixMs, highAccuracy = answer.highAccuracy,
        )
        spec = updated
        TripPrefs.save(this, updated)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(updated.title, updated.text))
        requestUpdates(answer.nextFixMs, answer.highAccuracy)
        armWatchdog(answer.retryMs.takeIf { it > 0 } ?: tripWatchdogMs(answer.nextFixMs))
    }

    /**
     * The watchdog fired: no fix arrived in time, so ask for one now. A null location tries the other
     * provider once, then reports `searching`; the watchdog stays armed, so this is never silent.
     */
    private fun askForFix(current: TripSpec) {
        val hasFused = locations.hasProvider(LocationManager.FUSED_PROVIDER)
        val first = tripProvider(current.highAccuracy, hasFused)
        val second = tripProvider(!current.highAccuracy, hasFused)
        try {
            locations.getCurrentLocation(first, null, worker) { location ->
                if (location != null) {
                    onFix(location)
                } else {
                    askOtherProvider(second, current)
                }
            }
        } catch (_: SecurityException) {
            emitStatus(JSONObject().put("state", "error").put("error", "permission").toString())
        } catch (_: IllegalArgumentException) {
            // The provider is gone (location switched off): keep retrying.
            armWatchdog(tripWatchdogMs(TRIP_MIN_INTERVAL_MS * 10))
        } catch (_: RuntimeException) {
            armWatchdog(tripWatchdogMs(TRIP_MIN_INTERVAL_MS * 10))
        }
    }

    private fun askOtherProvider(provider: String, current: TripSpec) {
        try {
            locations.getCurrentLocation(provider, null, worker) { location ->
                if (location != null) {
                    onFix(location)
                } else {
                    emitStatus(JSONObject().put("state", "searching").toString())
                    armWatchdog(tripWatchdogMs(current.nextFixMs))
                }
            }
        } catch (_: RuntimeException) {
            emitStatus(JSONObject().put("state", "searching").toString())
            armWatchdog(tripWatchdogMs(TRIP_MIN_INTERVAL_MS * 10))
        }
    }

    private fun shutdown(emitStopped: Boolean) {
        if (emitStopped) emitStatus(JSONObject().put("state", "stopped").toString())
        removeUpdates()
        cancelWatchdog()
        TripPrefs.clear(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun emitStatus(json: String) = RovstiBridge.emit(EVENT_STATUS, json)

    private fun watchdogIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 0, Intent(this, TripWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Arms the watchdog [delayMs] from now (already includes the grace, see [tripWatchdogMs]). */
    private fun armWatchdog(delayMs: Long) {
        val at = System.currentTimeMillis() + delayMs
        try {
            if (alarms.exactAllowed()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogIntent())
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogIntent())
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogIntent())
        }
    }

    private fun cancelWatchdog() = alarms.cancel(watchdogIntent())

    private fun notification(title: String, text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Trip in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Stops follows your trip"
                setShowBadge(false)
            },
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val open = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TripService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(NotificationIcon.small(this))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val CHANNEL = "trip"
        const val NOTIFICATION_ID = 0x5452 // "TR"
        const val ACTION_STOP = "dev.rovsti.trip.STOP"
        const val ACTION_WATCHDOG = "dev.rovsti.trip.WATCHDOG"
        const val EXTRA_SPEC = "spec"
        const val EVENT_STATUS = "trip_status"

        @Volatile var running = false
            private set

        @Volatile private var instance: TripService? = null

        /**
         * The watchdog alarm fired. With a live service, re-arm and ask for a fix in place (no foreground
         * start needed); otherwise start the service so it restores the saved trip.
         */
        fun onWatchdog(context: Context) {
            val live = instance
            val current = live?.spec
            if (live != null && current != null) {
                live.armWatchdog(tripWatchdogMs(current.nextFixMs))
                try {
                    live.worker.execute { live.askForFix(current) }
                    return
                } catch (_: RejectedExecutionException) {
                    // The service is shutting down: fall through and start it again.
                }
            }
            try {
                context.startForegroundService(Intent(context, TripService::class.java).setAction(ACTION_WATCHDOG))
            } catch (e: RuntimeException) {
                android.util.Log.w("Rovsti", "trip watchdog could not wake the service", e)
            }
        }

        /** Starts the service with [specJson]; false when it is malformed or Android refuses. */
        fun start(context: Context, specJson: String): Boolean {
            if (TripSpec.parse(specJson) == null) return false
            return try {
                context.startForegroundService(Intent(context, TripService::class.java).putExtra(EXTRA_SPEC, specJson))
                true
            } catch (_: RuntimeException) {
                false
            }
        }

        /** Stops the service if it is running. */
        fun stop(context: Context): Boolean {
            val wasRunning = running
            context.stopService(Intent(context, TripService::class.java))
            return wasRunning
        }
    }
}

/** Alarm target of the watchdog: restarts the service (allowed for an exact alarm) so it asks for a fix. */
class TripWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TripService.ACTION_WATCHDOG) return
        TripService.onWatchdog(context)
    }
}
