package dev.rovsti.platform.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.time.Duration

/** One device position: the `{lat, lon, accuracy_m, time_ms}` payload the idle map always used. */
@Serializable
data class Fix(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
    @SerialName("time_ms") val timeMs: Long,
) {
    /** The wire JSON (key order is part of the contract). */
    fun toJson(): String =
        JSONObject()
            .put("lat", lat)
            .put("lon", lon)
            .put("accuracy_m", accuracyM)
            .put("time_ms", timeMs)
            .toString()
}

/**
 * Rovsti Stops: the device location while no trip runs (the idle map). The trip service owns the updates during a
 * trip; this only reads the last known fix, asks for one new fix, or streams live fixes while the map is open.
 * It replaces the location events the GPUI bridge emitted: results come back as values and a [Flow].
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/StopsLocation.kt
 */
interface LocationRepository {
    /** The newest `getLastKnownLocation` over the providers; null without permission or a fix. */
    fun lastKnown(): Fix?

    /**
     * One new fix, or null after [timeout], without permission or a provider. Cancelling the caller cancels the
     * request.
     */
    suspend fun fresh(timeout: Duration): Fix?

    /**
     * Live fixes every 2 s / 5 m on the main looper while collected; the provider listener is removed when the
     * collector goes away. Completes at once without permission or an enabled provider.
     */
    fun updates(): Flow<Fix>
}

class AndroidLocationRepository(
    private val context: Context,
) : LocationRepository {
    private val manager: LocationManager? get() = context.getSystemService(LocationManager::class.java)

    override fun lastKnown(): Fix? {
        if (!allowed()) return null
        val lm = manager ?: return null
        var best: Location? = null
        for (provider in PROVIDERS_LAST) {
            // The fused provider exists from API 31.
            if (provider == FUSED && Build.VERSION.SDK_INT < 31) continue
            val fix =
                try {
                    lm.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            if (fix != null && (best == null || fix.time > best.time)) best = fix
        }
        return best?.toFix()
    }

    override suspend fun fresh(timeout: Duration): Fix? {
        if (!allowed()) return null
        val lm = manager ?: return null
        val provider = enabledProvider(lm) ?: return null
        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { cont -> request(lm, provider, cont) }
        }
    }

    private fun request(
        lm: LocationManager,
        provider: String,
        cont: CancellableContinuation<Fix?>,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { fix ->
                    if (cont.isActive) cont.resume(fix?.toFix())
                }
            } else {
                val listener = LocationListener { fix -> if (cont.isActive) cont.resume(fix.toFix()) }
                cont.invokeOnCancellation { removeQuietly(lm, listener) }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            if (cont.isActive) cont.resume(null)
        } catch (_: IllegalArgumentException) {
            if (cont.isActive) cont.resume(null)
        }
    }

    override fun updates(): Flow<Fix> =
        callbackFlow {
            val lm = manager
            val provider = if (allowed() && lm != null) enabledProvider(lm) else null
            if (lm == null || provider == null) {
                close()
                return@callbackFlow
            }
            val listener = LocationListener { fix -> trySend(fix.toFix()) }
            try {
                lm.requestLocationUpdates(
                    provider,
                    WATCH_INTERVAL_MS,
                    WATCH_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
            } catch (_: SecurityException) {
                close()
            } catch (_: IllegalArgumentException) {
                close()
            }
            awaitClose { removeQuietly(lm, listener) }
        }

    private fun removeQuietly(
        lm: LocationManager,
        listener: LocationListener,
    ) {
        try {
            lm.removeUpdates(listener)
        } catch (_: SecurityException) {
        }
    }

    private fun enabledProvider(lm: LocationManager): String? =
        listOf(FUSED, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it != FUSED || Build.VERSION.SDK_INT >= 31 }
            .firstOrNull { name ->
                try {
                    lm.isProviderEnabled(name)
                } catch (_: IllegalArgumentException) {
                    false
                }
            }

    private fun allowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun Location.toFix() = Fix(latitude, longitude, accuracy.toDouble(), time)

    private companion object {
        const val FUSED = "fused"
        val PROVIDERS_LAST =
            listOf(
                FUSED,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
        const val WATCH_INTERVAL_MS = 2_000L
        const val WATCH_DISTANCE_M = 5f
    }
}
