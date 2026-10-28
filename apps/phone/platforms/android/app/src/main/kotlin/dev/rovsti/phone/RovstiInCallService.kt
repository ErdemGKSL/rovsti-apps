package dev.rovsti.phone

import android.content.Intent
import android.os.IBinder
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.InCallService

/** Forwards Telecom callbacks to [CallRegistry]; holds no state of its own (AD-4). */
class RovstiInCallService : InCallService() {
    override fun onBind(intent: Intent?): IBinder? {
        CallRegistry.onServiceBound(this)
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        CallRegistry.onServiceUnbound()
        return super.onUnbind(intent)
    }

    override fun onCallAdded(call: Call) = CallRegistry.onCallAdded(call)

    override fun onCallRemoved(call: Call) = CallRegistry.onCallRemoved(call)

    override fun onMuteStateChanged(isMuted: Boolean) = CallRegistry.onMuteChanged(isMuted)

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) = CallRegistry.onEndpointChanged(callEndpoint)

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) =
        CallRegistry.onEndpointsChanged(availableEndpoints)
}
