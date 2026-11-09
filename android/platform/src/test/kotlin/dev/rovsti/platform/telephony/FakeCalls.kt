package dev.rovsti.platform.telephony

import android.app.Activity
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor

/** Stands in for the app's launcher activity: only its class is read (a `PendingIntent` target). */
class TestMainActivity : Activity()

/** Stands in for the app's call-action receiver. */
class TestCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) = Unit
}

/** An [InCallHost] that records what the registry asked of it. */
class FakeInCallHost : InCallHost {
    val foreground = mutableListOf<Pair<Int, Notification>>()
    var foregroundRemoved = 0
    var muted: Boolean? = null

    override fun setMuted(on: Boolean) {
        muted = on
    }

    override fun setAudioRoute(mask: Int) = Unit

    override fun requestEndpoint(
        target: CallEndpoint,
        executor: Executor,
        receiver: OutcomeReceiver<Void, CallEndpointException>,
    ) = Unit

    override fun legacyAudioState(): CallAudioState? = null

    override fun startForeground(
        id: Int,
        notification: Notification,
        type: Int,
    ) {
        foreground += id to notification
    }

    override fun stopForegroundRemove() {
        foregroundRemoved++
    }
}

/**
 * A real [Call] in a given state without Telecom: the hidden no-argument constructor plus the two private fields the
 * registry reads (`mState`, `mDetails`). Commands (`answer`, `reject`) are not wired and must not be called.
 */
fun fakeCall(
    number: String,
    state: Int = Call.STATE_RINGING,
): Call {
    val details = ReflectionHelpers.callConstructor(Call.Details::class.java)
    ReflectionHelpers.setField(details, "mHandle", Uri.fromParts("tel", number, null))
    ReflectionHelpers.setField(details, "mCallDirection", Call.Details.DIRECTION_INCOMING)
    ReflectionHelpers.setField(details, "mCreationTimeMillis", 1_000L)
    val call = ReflectionHelpers.callConstructor(Call::class.java)
    ReflectionHelpers.setField(call, "mState", state)
    ReflectionHelpers.setField(call, "mDetails", details)
    // The hidden constructor skips the field initialisers: give the lists the platform would have made.
    val children = ArrayList<Call>()
    val conferenceable = ArrayList<Call>()
    ReflectionHelpers.setField(call, "mChildrenIds", ArrayList<String>())
    ReflectionHelpers.setField(call, "mChildren", children)
    ReflectionHelpers.setField(call, "mUnmodifiableChildren", java.util.Collections.unmodifiableList(children))
    ReflectionHelpers.setField(call, "mCallbackRecords", java.util.concurrent.CopyOnWriteArrayList<Any>())
    ReflectionHelpers.setField(call, "mConferenceableCalls", conferenceable)
    ReflectionHelpers.setField(
        call,
        "mUnmodifiableConferenceableCalls",
        java.util.Collections.unmodifiableList(conferenceable),
    )
    return call
}
