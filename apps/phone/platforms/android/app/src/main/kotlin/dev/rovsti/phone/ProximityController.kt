package dev.rovsti.phone

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dev.rovsti.bridge.CallInfo
import dev.rovsti.bridge.Focus

/** When the screen should go dark at the ear (story 5.7). Pure, so a JVM test covers it. */
object ProximityPolicy {
    /**
     * Wanted for the foreground call while it is Active or Dialing on the earpiece route.
     * Speaker, Bluetooth and wired headsets keep the screen on; so do Holding, Ringing
     * and ended calls.
     */
    fun wanted(calls: List<CallInfo>, focus: Focus, route: String): Boolean {
        if (route != "earpiece") return false
        val state = calls.firstOrNull { it.id == focus.foreground }?.state ?: return false
        return state == "active" || state == "dialing"
    }

    /** Whether any call is still in progress; decides how the lock lets go (see [ProximityController.update]). */
    fun inProgress(calls: List<CallInfo>): Boolean =
        calls.any { it.state == "active" || it.state == "dialing" || it.state == "holding" }
}

/**
 * Holds `PROXIMITY_SCREEN_OFF_WAKE_LOCK` while [ProximityPolicy] wants it, so
 * the screen blanks against the cheek and wakes when the phone moves away.
 * Kotlin only, driven by [CallRegistry] on the main Looper; the lock is released
 * on every path that stops wanting it (route change, hold, end, service unbind).
 *
 * The release flag is a HyperOS unknown: when the call is over, the screen waits for
 * the phone to leave the ear (`RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY`) so a hang-up does
 * not light the screen against the cheek; on a route change it wakes at once. Check
 * with `adb shell dumpsys power | grep -i proximity` (see docs/spikes/s2-incoming.md).
 */
class ProximityController(context: Context) {
    private val lock: PowerManager.WakeLock? = run {
        val power = context.getSystemService(PowerManager::class.java)
        if (power != null && power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Rovsti:proximity").apply {
                setReferenceCounted(false)
            }
        } else {
            Log.w("Rovsti", "no proximity wake lock on this device")
            null
        }
    }

    /** Whether the lock is held; exposed for logs and tests of callers. */
    val held: Boolean get() = lock?.isHeld == true

    /** Brings the lock in line with [wanted]; [callInProgress] picks how it is let go. */
    fun update(wanted: Boolean, callInProgress: Boolean) {
        val wl = lock ?: return
        try {
            if (wanted && !wl.isHeld) {
                // A safety timeout so a lost release can never leave the screen dark for good.
                wl.acquire(MAX_HOLD_MS)
            } else if (!wanted && wl.isHeld) {
                wl.release(if (callInProgress) 0 else PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        } catch (e: RuntimeException) {
            Log.w("Rovsti", "proximity lock failed", e)
        }
    }

    private companion object {
        const val MAX_HOLD_MS = 6L * 60 * 60 * 1000
    }
}
