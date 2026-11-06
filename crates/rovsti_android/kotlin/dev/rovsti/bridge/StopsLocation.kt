package dev.rovsti.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import org.json.JSONObject

/**
 * Rovsti Stops: the device location while no trip runs (the idle map). The trip service owns
 * updates during a trip; this only reads the last known fix or asks for a single new one.
 */
object StopsLocation {
    private val PROVIDERS_LAST = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)

    /** `{lat, lon, accuracy_m, time_ms}` payload; pure so the format is easy to check. */
    fun fixJson(lat: Double, lon: Double, accuracyM: Double, timeMs: Long): String =
        JSONObject()
            .put("lat", lat)
            .put("lon", lon)
            .put("accuracy_m", accuracyM)
            .put("time_ms", timeMs)
            .toString()

    private fun json(location: Location): String =
        fixJson(location.latitude, location.longitude, location.accuracy.toDouble(), location.time)

    private fun allowed(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The newest `getLastKnownLocation` over the providers, or "" without permission or a fix. */
    fun last(ctx: Context): String {
        if (!allowed(ctx)) return ""
        val manager = ctx.getSystemService(LocationManager::class.java) ?: return ""
        var best: Location? = null
        for (provider in PROVIDERS_LAST) {
            // The fused provider exists from API 31.
            if (provider == "fused" && Build.VERSION.SDK_INT < 31) continue
            val fix = try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (fix != null && (best == null || fix.time > best.time)) best = fix
        }
        return best?.let(::json) ?: ""
    }

    /** Asks for one fix and emits it as `stops_location`; false without permission or a provider. */
    fun once(ctx: Context): Boolean {
        if (!allowed(ctx)) return false
        val manager = ctx.getSystemService(LocationManager::class.java) ?: return false
        val provider = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it != "fused" || Build.VERSION.SDK_INT >= 31 }
            .firstOrNull { name ->
                try {
                    manager.isProviderEnabled(name)
                } catch (_: IllegalArgumentException) {
                    false
                }
            } ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(provider, CancellationSignal(), ctx.mainExecutor) { fix ->
                    if (fix != null) RovstiBridge.emit("stops_location", json(fix))
                }
            } else {
                val listener = LocationListener { fix -> RovstiBridge.emit("stops_location", json(fix)) }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
