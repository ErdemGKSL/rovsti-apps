package dev.rovsti.bridge

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.rovsti.platform.Headless
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.location.StartResult
import dev.rovsti.platform.location.TRIP_MAX_INTERVAL_MS
import dev.rovsti.platform.location.TRIP_MIN_INTERVAL_MS
import dev.rovsti.platform.location.TripAlarm
import dev.rovsti.platform.location.TripAnswer
import dev.rovsti.platform.location.TripClock
import dev.rovsti.platform.location.TripPrefs
import dev.rovsti.platform.location.TripSpec
import dev.rovsti.platform.location.exactAllowed
import dev.rovsti.platform.location.startFailure
import dev.rovsti.platform.location.tripFixJson
import dev.rovsti.platform.location.tripIntervalMs
import dev.rovsti.platform.location.tripProvider
import dev.rovsti.platform.location.tripRestartErrorJson
import dev.rovsti.platform.location.tripStartIsBackground
import dev.rovsti.platform.location.tripWatchdogMs
import dev.rovsti.platform.notify.NotificationIcon
import dev.rovsti.platform.notify.NotificationKind
import dev.rovsti.platform.notify.PlatformChannels
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

// The four manifest components of the trip. They live in this package, not dev.rovsti.platform.location, because the
// manifests and the PendingIntents the GPUI build armed already name `dev.rovsti.bridge.*` (ARCH 7.2); FqcnTest pins
// the names. All logic is in dev.rovsti.platform.location.
// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/{TripService,TripAlarm,AlarmActivity}.kt

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

    @Volatile private var foreground = false
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
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
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, notification(incoming.title, incoming.text))
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
        PlatformChannels.ensure(this, PlatformChannels.TRIP_PROBLEM)
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val open =
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            NOTIFICATION_ID + 1,
            Notification
                .Builder(this, PlatformChannels.TRIP_PROBLEM)
                .let { NotificationIcon.apply(it, this, NotificationKind.TRIP) }
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
    private fun requestUpdates(
        nextFixMs: Long,
        highAccuracy: Boolean,
    ): Boolean {
        val interval = tripIntervalMs(nextFixMs)
        val provider = tripProvider(highAccuracy, locations.hasProvider(LocationManager.FUSED_PROVIDER))
        if (requested == Pair(interval, provider)) return true
        return try {
            locations.removeUpdates(listener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request =
                    LocationRequest
                        .Builder(interval)
                        .setQuality(
                            if (highAccuracy) {
                                LocationRequest.QUALITY_HIGH_ACCURACY
                            } else {
                                LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                            },
                        ).setMinUpdateIntervalMillis(interval / 2)
                        .build()
                locations.requestLocationUpdates(provider, request, worker, listener)
            } else {
                @Suppress("DEPRECATION")
                val main = Looper.getMainLooper()
                locations.requestLocationUpdates(provider, interval, 0f, listener, main)
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
        val request =
            tripFixJson(
                current.dir,
                location.latitude,
                location.longitude,
                if (location.hasAccuracy()) location.accuracy.toDouble() else 1_000.0,
                speed,
                location.time,
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
        val updated =
            current.copy(
                title = answer.title.ifEmpty { current.title },
                text = answer.text,
                nextFixMs = answer.nextFixMs,
                highAccuracy = answer.highAccuracy,
            )
        spec = updated
        TripPrefs.save(this, updated)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification(updated.title, updated.text))
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

    private fun askOtherProvider(
        provider: String,
        current: TripSpec,
    ) {
        try {
            locations.getCurrentLocation(provider, null, worker) { location ->
                if (location != null) {
                    onFix(location)
                } else {
                    emitStatus(JSONObject().put("state", "searching").toString())
                    armWatchdog(tripWatchdogMs(current.nextFixMs))
                }
            }
        } catch (_: SecurityException) {
            emitStatus(JSONObject().put("state", "error").put("error", "permission").toString())
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

    private fun emitStatus(json: String) = PlatformEvents.emit(EVENT_STATUS, json)

    private fun watchdogIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, TripWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Arms the watchdog [delayMs] from now (already includes the grace, see [tripWatchdogMs]). */
    private fun armWatchdog(delayMs: Long) {
        val at = TripClock.nowMillis() + delayMs
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

    private fun notification(
        title: String,
        text: String,
    ): Notification {
        PlatformChannels.ensure(this, CHANNEL)
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val open =
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, TripService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .let { NotificationIcon.apply(it, this, NotificationKind.TRIP) }
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
        const val CHANNEL = PlatformChannels.TRIP
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
                    live.worker.execute {
                        // A provider that vanished left no registration behind: restore it before asking.
                        live.requestUpdates(current.nextFixMs, current.highAccuracy)
                        live.askForFix(current)
                    }
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
        fun start(
            context: Context,
            specJson: String,
        ): Boolean = startResult(context, specJson) == StartResult.Started

        /**
         * Starts the service with [specJson] and says why not when it did not start. Never throws: the exception
         * Android raises for a refused foreground start becomes [StartResult.NotAllowed] (see [startFailure]).
         */
        fun startResult(
            context: Context,
            specJson: String,
        ): StartResult {
            if (TripSpec.parse(specJson) == null) return StartResult.Invalid
            return try {
                context.startForegroundService(Intent(context, TripService::class.java).putExtra(EXTRA_SPEC, specJson))
                StartResult.Started
            } catch (e: RuntimeException) {
                android.util.Log.w("Rovsti", "trip service start refused", e)
                startFailure(e)
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
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != TripService.ACTION_WATCHDOG) return
        TripService.onWatchdog(context)
    }
}

/** Notification actions and the snooze alarm; declare it (not exported) in the host manifest. */
class TripAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        TripAlarm.recover(context)
        val pending = goAsync()
        val done = { pending.finish() }
        when (intent.action) {
            TripAlarm.ACTION_RING -> {
                TripAlarm.ring(context)
                done()
            }

            TripAlarm.ACTION_SNOOZE -> {
                TripAlarm.snooze(context, done)
            }

            TripAlarm.ACTION_DISMISS -> {
                TripAlarm.dismiss(context, done)
            }

            else -> {
                done()
            }
        }
    }
}

/**
 * The full-screen alarm (AD-ST3): plain Views, no GPUI, so it shows over the lock screen even when the
 * app process was just started. Back means Snooze. Declare it in the host manifest with
 * `showWhenLocked`, `turnScreenOn`, `launchMode="singleInstance"`, `excludeFromRecents` and `exported="false"`.
 */
class AlarmActivity : Activity() {
    private val closer =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!TripAlarm.ringing) {
            // A stale tap on an old notification: nothing is ringing.
            finish()
            return
        }
        val (title, text) = TripAlarm.message()
        setContentView(content(title, text))
        registerReceiver(closer, IntentFilter(TripAlarm.ACTION_CLOSE), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(closer)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    @Deprecated("Back is Snooze on this screen")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = snooze()

    private fun snooze() {
        TripAlarm.snooze(this)
        finish()
    }

    private fun dismiss() {
        TripAlarm.dismiss(this)
        finish()
    }

    private fun content(
        title: String,
        text: String,
    ): LinearLayout {
        val pad = (24 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
            addView(label(title, 34f, Color.WHITE))
            addView(label(text, 18f, Color.LTGRAY))
            addView(button("Dismiss") { dismiss() })
            addView(button("Snooze") { snooze() })
        }
    }

    private fun label(
        text: String,
        sp: Float,
        color: Int,
    ) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
    }

    private fun button(
        text: String,
        onClick: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        textSize = 20f
        layoutParams =
            LinearLayout
                .LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
        setOnClickListener { onClick() }
    }
}
