package dev.rovsti.phone

import android.content.Intent
import android.os.IBinder
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.util.Log
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.platform.telephony.InCallHost
import java.util.concurrent.Executor

/**
 * Forwards Telecom callbacks to [CallRegistry]; holds no state of its own (AD-4). It is the [InCallHost] the registry
 * drives (mute, route, foreground), so `:platform` never names this class.
 *
 * No callback may throw: an exception here kills the process, and Telecom then hands the call to the system dialer. A
 * failure is logged and the call goes on.
 */
class RovstiInCallService :
    InCallService(),
    InCallHost {
    override fun onBind(intent: Intent?): IBinder? {
        guard("onBind") { CallRegistry.onServiceBound(this) }
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        guard("onUnbind") { CallRegistry.onServiceUnbound() }
        return super.onUnbind(intent)
    }

    override fun onCallAdded(call: Call) = guard("onCallAdded") { CallRegistry.onCallAdded(call) }

    override fun onCallRemoved(call: Call) = guard("onCallRemoved") { CallRegistry.onCallRemoved(call) }

    override fun onSilenceRinger() = guard("onSilenceRinger") { CallRegistry.onSilenceRinger() }

    override fun onCanAddCallChanged(canAddCall: Boolean) =
        guard("onCanAddCallChanged") { CallRegistry.onCanAddCallChanged(canAddCall) }

    override fun onMuteStateChanged(isMuted: Boolean) =
        guard("onMuteStateChanged") { CallRegistry.onMuteChanged(isMuted) }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) =
        guard("onCallEndpointChanged") { CallRegistry.onEndpointChanged(callEndpoint) }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) =
        guard("onAvailableCallEndpointsChanged") { CallRegistry.onEndpointsChanged(availableEndpoints) }

    // ---- InCallHost: what CallRegistry asks of the service ------------------------------------------------------

    // setMuted, setAudioRoute and startForeground are final members of InCallService / Service with the very signatures
    // of the InCallHost methods, so they implement the interface as inherited.

    override fun requestEndpoint(
        target: CallEndpoint,
        executor: Executor,
        receiver: OutcomeReceiver<Void, CallEndpointException>,
    ) = requestCallEndpointChange(target, executor, receiver)

    @Suppress("DEPRECATION")
    override fun legacyAudioState(): CallAudioState? = callAudioState

    override fun stopForegroundRemove() = stopForeground(STOP_FOREGROUND_REMOVE)

    private inline fun guard(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.e("Rovsti", "$name failed; the call goes on", e)
        }
    }
}
