package dev.rosty.stops

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rosty.core.log.RostyLog
import dev.rosty.platform.Headless
import dev.rosty.shell.VaultBoot
import dev.rosty.shell.shellContainer
import org.json.JSONObject

/**
 * A reboot kills the trip service and its location updates, so a `trip.json` still there after `BOOT_COMPLETED` is
 * stale. Rust ends it as `cancelled` (it lands in the history); the user starts a new trip. Nothing is restarted: the
 * user may be nowhere near the destination. Ported from the GPUI app's `BootReceiver` (same headless `trip_end`).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread({
            try {
                // The container starts Rust.init with the app's data directory; Headless waits for it.
                app.shellContainer().rust
                Headless.call(KIND, endRequest(Headless.dataDir(app)))
            } catch (e: RuntimeException) {
                RostyLog.e("trip", "ending the stale trip after a reboot failed", e)
            }
            try {
                // The WhatsApp vault's daily unlock ends with the reboot (`plans/wa-vault.md` 13.2).
                VaultBoot.dropDailyUnlock(app)
            } catch (e: RuntimeException) {
                RostyLog.e("trip", "dropping the WhatsApp daily unlock failed", e)
            } finally {
                // Null only when the receiver is called directly (tests), outside a broadcast.
                pending?.finish()
            }
        }, "trip-boot").start()
    }

    companion object {
        const val KIND = "trip_end"

        /** The headless request (pure): end the trip in [dir] as cancelled. */
        fun endRequest(dir: String): String = JSONObject().put("dir", dir).put("reason", "cancelled").toString()
    }
}
