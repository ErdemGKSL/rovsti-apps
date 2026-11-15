package dev.rosty.stops.trip

import dev.rosty.core.RustError
import dev.rosty.design.text.UiText
import dev.rosty.platform.system.permissions.PermissionState
import dev.rosty.stops.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {
    private val granted = PermissionState.Granted
    private val never = PermissionState.NeverAsked
    private val gone = PermissionState.DeniedForGood

    private fun pre(
        location: PermissionState = granted,
        notifications: PermissionState = granted,
        background: PermissionState = granted,
        fullScreen: Boolean? = true,
        battery: Boolean? = true,
    ) = Preflight(location, notifications, background, fullScreen, battery)

    @Test fun locationComesBeforeNotifications() {
        assertNull(pre().nextStep)
        assertEquals(PreflightStep.Location, pre(location = never, notifications = never).nextStep)
        assertEquals(PreflightStep.Location, pre(location = gone).nextStep)
        assertEquals(PreflightStep.Notifications, pre(notifications = PermissionState.DeniedOnce).nextStep)
        assertEquals(PreflightStep.Location, pre(location = PermissionState.RevokedWhileRunning).nextStep)
    }

    @Test fun backgroundIsRecommendedOnlyAfterForegroundLocation() {
        assertFalse(pre(background = never).nextStep != null)
        assertTrue(pre(background = never).backgroundRow)
        assertFalse(pre(location = never, background = never).backgroundRow)
        assertFalse(pre().backgroundRow)
    }

    @Test fun specialRowsOnlyWhenKnownOff() {
        assertTrue(pre(fullScreen = false).fullScreenRow)
        assertFalse(pre(fullScreen = null).fullScreenRow)
        assertTrue(pre(battery = false).batteryRow)
        assertFalse(pre(battery = true).batteryRow)
    }

    @Test fun aRevokedGrantWhileRunningIsARow() {
        assertNull(runningProblem(granted, granted))
        assertEquals(PreflightStep.Location, runningProblem(PermissionState.RevokedWhileRunning, granted))
        assertEquals(PreflightStep.Notifications, runningProblem(granted, gone))
    }

    @Test fun statusErrorsBecomeRows() {
        assertEquals(TripProblem.Restart, TripViewModel.problemOf("restart"))
        assertEquals(TripProblem.Permission, TripViewModel.problemOf("permission"))
        assertEquals(TripProblem.Foreground, TripViewModel.problemOf("foreground"))
        assertNull(TripViewModel.problemOf("full_screen"))
    }

    @Test fun aRefusedGateIsWorded() {
        assertEquals(UiText.Res(R.string.trip_error_gate), TripViewModel.startRefusal(RustError.Invalid("short")))
        assertEquals(UiText.Res(R.string.trip_could_not_start), TripViewModel.startRefusal(RustError.Io("disk")))
    }
}
