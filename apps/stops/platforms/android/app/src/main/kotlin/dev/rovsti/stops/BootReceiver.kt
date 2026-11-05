package dev.rovsti.stops

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rovsti.bridge.Headless
import org.json.JSONObject

/**
 * A reboot kills the trip service and its location updates, so a `trip.json` still there after
 * `BOOT_COMPLETED` is stale. Rust ends it as `cancelled` (it lands in the history); the user
 * starts a new trip. Nothing is restarted: the user may be nowhere near the destination.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Headless.libName = "rovsti_stops"
        val pending = goAsync()
        val request = JSONObject().put("dir", Headless.dataDir(context)).put("reason", "cancelled").toString()
        Thread({
            try {
                Headless.call("trip_end", request)
            } finally {
                pending.finish()
            }
        }, "trip-boot").start()
    }
}
