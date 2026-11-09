package dev.rovsti.platform.location

// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/TripService.kt (the pure half)

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.location.LocationManager
import android.os.Build
import dev.rovsti.bridge.TripService
import dev.rovsti.platform.PlatformPrefs
import org.json.JSONException
import org.json.JSONObject

/** Shortest and longest interval the service asks the location provider for. */
internal const val TRIP_MIN_INTERVAL_MS = 1_000L
internal const val TRIP_MAX_INTERVAL_MS = 300_000L

/** Grace after the expected interval before the watchdog asks for a fix itself: interval / 4 within these bounds. */
internal const val TRIP_WATCHDOG_MIN_GRACE_MS = 5_000L
internal const val TRIP_WATCHDOG_MAX_GRACE_MS = 30_000L

/** Pure: [nextFixMs] limited to what a provider accepts. */
internal fun tripIntervalMs(nextFixMs: Long): Long = nextFixMs.coerceIn(TRIP_MIN_INTERVAL_MS, TRIP_MAX_INTERVAL_MS)

/** Pure: GPS when [highAccuracy], else the fused provider if the device has one, else network. */
internal fun tripProvider(
    highAccuracy: Boolean,
    hasFused: Boolean,
): String =
    when {
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
internal fun tripStartIsBackground(
    action: String?,
    flags: Int,
    hasSpecExtra: Boolean,
): Boolean =
    action == TripService.ACTION_WATCHDOG ||
        (flags and Service.START_FLAG_REDELIVERY) != 0 ||
        (flags and Service.START_FLAG_RETRY) != 0 ||
        !hasSpecExtra

/** Pure: the status Rust's UI maps to the "Android closed Stops" message. */
internal fun tripRestartErrorJson(): String = """{"state":"error","error":"restart"}"""

/** `AlarmManager.canScheduleExactAlarms`, which only exists (and is only needed) from Android 12. */
internal fun AlarmManager.exactAllowed(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()

/** What Rust asked for when the service was started or updated (`{dir,title,text,next_fix_ms,high_accuracy}`). */
data class TripSpec(
    val dir: String,
    val title: String,
    val text: String,
    val nextFixMs: Long,
    val highAccuracy: Boolean,
    /** Raw JSON of Rust's `AlarmPlan`, kept verbatim so the alarm can ring after a process restart. */
    val alarm: String? = null,
) {
    fun toJson(): String {
        val o =
            JSONObject()
                .put("dir", dir)
                .put("title", title)
                .put("text", text)
                .put("next_fix_ms", nextFixMs)
                .put("high_accuracy", highAccuracy)
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
                    dir,
                    o.optString("title", "Trip"),
                    o.optString("text"),
                    o.optLong("next_fix_ms", 10_000L),
                    o.optBoolean("high_accuracy", false),
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
                    o.optString("state"),
                    o.optLong("next_fix_ms", 10_000L),
                    o.optBoolean("high_accuracy", false),
                    o.optBoolean("ring", false),
                    o.optString("title"),
                    o.optString("text"),
                    o.optLong("retry_ms", 0L).coerceAtLeast(0L),
                )
            } catch (_: JSONException) {
                null
            }
        }
    }
}

/** Pure: the `trip_fix` request (`rovsti_geo::session::FixIn` plus `dir`). */
internal fun tripFixJson(
    dir: String,
    lat: Double,
    lon: Double,
    accuracyM: Double,
    speedMps: Double?,
    atMs: Long,
): String {
    val o =
        JSONObject()
            .put("dir", dir)
            .put("lat", lat)
            .put("lon", lon)
            .put("accuracy_m", accuracyM)
            .put("at_ms", atMs)
    if (speedMps != null && speedMps >= 0.0 && !speedMps.isNaN()) o.put("speed_mps", speedMps)
    return o.toString()
}

/** The spec of the running trip survives a process restart here. */
internal object TripPrefs {
    private const val FILE = PlatformPrefs.TRIP

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        spec: TripSpec,
    ) = prefs(context).edit().putString("spec", spec.toJson()).apply()

    fun load(context: Context): TripSpec? = TripSpec.parse(prefs(context).getString("spec", null))

    fun clear(context: Context) = prefs(context).edit().remove("spec").apply()

    /** The raw alarm plan JSON of the running trip, or null (the alarm then uses its built-in defaults). */
    fun alarmPlan(context: Context): String? = load(context)?.alarm

    /** The data directory the alarm hands back to Rust, from the last started trip. */
    fun dir(context: Context): String? = load(context)?.dir
}

/** How a [dev.rovsti.bridge.TripService] start ended. Nothing here throws to the caller (ARCH 7.5). */
enum class StartResult {
    Started,

    /** The spec was malformed or had no `dir`; nothing was started. */
    Invalid,

    /**
     * Android refused the foreground start or the location permission is missing: the UI shows "trip could not
     * start".
     */
    NotAllowed,

    /** Any other runtime failure of the start call. */
    Failed,
}

private fun isBackgroundStartRefusal(e: Throwable): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException

/**
 * Pure: the result for an exception thrown by `startForegroundService`. `ForegroundServiceStartNotAllowedException`
 * (Android 12+, the app is in the background) and `SecurityException` (missing permission or a foreground-service-type
 * check) are [StartResult.NotAllowed]; anything else is [StartResult.Failed].
 */
fun startFailure(e: Throwable): StartResult =
    when {
        e is SecurityException -> StartResult.NotAllowed
        isBackgroundStartRefusal(e) -> StartResult.NotAllowed
        else -> StartResult.Failed
    }
