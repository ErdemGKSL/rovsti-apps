package dev.rosty.stops.trip

import dev.rosty.platform.system.permissions.PermissionState

/**
 * What a trip needs from Android before it starts (ARCH 7.5), a Kotlin twin of the GPUI `backend::Preflight`:
 * location and notifications block the start, "Allow all the time" location, full-screen alerts and an unrestricted
 * battery are recommended rows that never block (AD-SA7). Exact alarms are install-time for Stops (`USE_EXACT_ALARM`),
 * so they have no row. Null means Android could not tell.
 */
data class Preflight(
    val location: PermissionState,
    val notifications: PermissionState,
    val background: PermissionState,
    val fullScreen: Boolean?,
    val battery: Boolean?,
) {
    /** The first blocker, location before notifications (GPUI `next_step`); null when the trip may start. */
    val nextStep: PreflightStep?
        get() =
            when {
                location != PermissionState.Granted -> PreflightStep.Location
                notifications != PermissionState.Granted -> PreflightStep.Notifications
                else -> null
            }

    /** "Keep tracking in the background": only once foreground location is granted and "all the time" is missing. */
    val backgroundRow: Boolean
        get() = location == PermissionState.Granted && background != PermissionState.Granted

    /** "Let the alarm wake the screen": only when Android says full-screen alerts are off. */
    val fullScreenRow: Boolean get() = fullScreen == false

    /** The battery row: only when Android says the app is optimised. */
    val batteryRow: Boolean get() = battery == false
}

/** A blocker of [Preflight]. */
enum class PreflightStep { Location, Notifications }

/**
 * The row a running trip shows for a grant that went away (ARCH 7.5: a revoked grant keeps the trip and shows the
 * row): location revoked while the service runs, or notifications (the alarm cannot be shown); null when nothing is
 * missing.
 */
fun runningProblem(
    location: PermissionState,
    notifications: PermissionState,
): PreflightStep? =
    when {
        location != PermissionState.Granted -> PreflightStep.Location
        notifications != PermissionState.Granted -> PreflightStep.Notifications
        else -> null
    }
