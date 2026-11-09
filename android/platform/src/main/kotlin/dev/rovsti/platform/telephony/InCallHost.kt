package dev.rovsti.platform.telephony

import android.app.Notification
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import java.util.concurrent.Executor

/**
 * What [CallRegistry] needs from the app's `InCallService`. The service implements this (or wraps itself in an
 * adapter) and hands it over with [CallRegistry.onServiceBound]; the registry never names a service class, so
 * `:platform` has no `dev.rovsti.<app>` reference (E3.5). Every method is called on the main Looper.
 */
interface InCallHost {
    /** `InCallService.setMuted`. */
    fun setMuted(on: Boolean)

    /** `InCallService.setAudioRoute` with a `CallAudioState.ROUTE_*` mask (the legacy path). */
    fun setAudioRoute(mask: Int)

    /** `InCallService.requestCallEndpointChange` (API 34). */
    fun requestEndpoint(
        target: CallEndpoint,
        executor: Executor,
        receiver: OutcomeReceiver<Void, CallEndpointException>,
    )

    /** `InCallService.getCallAudioState`: only read on the legacy audio path. */
    fun legacyAudioState(): CallAudioState?

    /** `Service.startForeground(id, notification, FOREGROUND_SERVICE_TYPE_PHONE_CALL)`. */
    fun startForeground(
        id: Int,
        notification: Notification,
        type: Int,
    )

    /** `Service.stopForeground(STOP_FOREGROUND_REMOVE)`. */
    fun stopForegroundRemove()
}
