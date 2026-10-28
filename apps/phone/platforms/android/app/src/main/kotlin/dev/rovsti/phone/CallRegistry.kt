package dev.rovsti.phone

import android.app.Notification
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.util.Log
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import dev.rovsti.bridge.AudioInfo
import dev.rovsti.bridge.CallInfo
import dev.rovsti.bridge.CallsApi
import dev.rovsti.bridge.ContactsQuery
import dev.rovsti.bridge.Focus
import dev.rovsti.bridge.ProviderJson
import dev.rovsti.bridge.RovstiBridge
import dev.rovsti.bridge.SnapshotData
import dev.rovsti.bridge.SnapshotJson
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Owns all call state (AD-4). Fed only by [RovstiInCallService] callbacks, which
 * run on the main Looper. Every mutation and snapshot build happens there, in
 * one synchronous step; no lock is held across a binder call. Other threads
 * (Rust commands) only read the volatile snapshot and post to the main Looper.
 *
 * Audio uses `CallEndpoint` only (minSdk 34).
 */
object CallRegistry : CallsApi {
    /** The activity that shows calls; told when a live call appears or the last one goes (AD-1). */
    interface Surface {
        fun onLiveCallChanged(live: Boolean)

        /** Rust drew the first frame of the Incoming Call screen (story 5.6). */
        fun onIncomingFrame(callId: Int) {}

        /** The ringing call changed (answered, declined, gone, or a new one): `null` when none rings. */
        fun onIncomingChanged(call: IncomingCall?) {}
    }

    private const val ANSWER_REPLACING_TIMEOUT_MS = 2_000L
    private const val DTMF_SAFETY_MS = 4_000L

    private class Entry(val id: Int, val call: Call, val callback: Call.Callback) {
        var displayName: String? = null
        var contactId: Long? = null
    }

    private class Pending(val targetId: Int, val replacedId: Int)

    private val main = Handler(Looper.getMainLooper())
    private val lookupExecutor: Executor by lazy { Executors.newSingleThreadExecutor() }

    // Read from any thread.
    @Volatile private var json: String = SnapshotJson.toJson(SnapshotJson.empty(0, 0, true))
    @Volatile private var knownIds: Set<Int> = emptySet()
    @Volatile private var ringingIds: Set<Int> = emptySet()

    /** The ringing call as the notification shows it; null when nothing rings. */
    @Volatile var incomingNow: IncomingCall? = null
        private set

    // Main-thread state.
    private var app: Application? = null
    private var service: RovstiInCallService? = null
    private val entries = LinkedHashMap<Int, Entry>()
    private var nextId = 1
    private var seq = 0L
    private var muted = false
    private var currentRoute: String? = null
    private var endpoints: List<CallEndpoint> = emptyList()
    private var foregroundId: Int? = null
    private var pending: Pending? = null
    private var keyguardReceiverRegistered = false
    private var ongoingKey: String? = null
    private var foregroundStarted = false
    private var incomingKey: String? = null
    private var incomingPerfId = -1
    private var liveCall = false
    private var proximity: ProximityController? = null

    /** Set by `MainActivity` while it exists; main thread only. */
    var surface: Surface? = null

    private val timeout = Runnable { completePending() }
    private val dtmfSafetyStop = Runnable { stopDtmfNow() }
    private var dtmfCall: Call? = null

    private val keyguardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = rebuild()
    }

    /** Called from `Application.onCreate`; Kotlin only, no native library. */
    fun init(application: Application) {
        app = application
        RovstiBridge.calls = this
    }

    // ---- CallsApi: any thread ------------------------------------------

    override fun snapshot(): String = json

    override fun answer(callId: Int) = command(callId) { it.call.answer(VideoProfile.STATE_AUDIO_ONLY) }

    override fun reject(callId: Int) = command(callId) { it.call.reject(false, null) }

    override fun disconnect(callId: Int) = command(callId) { it.call.disconnect() }

    override fun hold(callId: Int, on: Boolean) = command(callId) {
        if (on) it.call.hold() else it.call.unhold()
    }

    override fun mute(on: Boolean): Boolean {
        if (!hasService) return false
        main.post { service?.setMuted(on) }
        return true
    }

    override fun route(name: String): Boolean {
        if (!hasService) return false
        main.post {
            val target = endpoints.firstOrNull { SnapshotJson.routeName(it.endpointType) == name } ?: return@post
            service?.requestCallEndpointChange(target, Executor { it.run() }, object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) {}
                override fun onError(error: CallEndpointException) {}
            })
        }
        return true
    }

    /** Starts a tone; [dtmfStop] ends it. A safety timeout stops a tone whose "up" never arrives. */
    override fun dtmf(digit: Char): Boolean {
        if (digit !in "0123456789*#") return false
        val id = foregroundIdVolatile ?: return false
        return command(id) {
            main.removeCallbacks(dtmfSafetyStop)
            dtmfCall?.stopDtmfTone()
            it.call.playDtmfTone(digit)
            dtmfCall = it.call
            main.postDelayed(dtmfSafetyStop, DTMF_SAFETY_MS)
        }
    }

    /** Story 5.6: Rust reports the first frame of the Incoming Call screen. */
    override fun incomingFrame(callId: Int): Boolean {
        main.post {
            IncomingPerf.frame(callId)
            surface?.onIncomingFrame(callId)
        }
        return true
    }

    /**
     * The notification's Answer (story 5.3): answers only a call that is still ringing, so a
     * stale or repeated tap does nothing. Needs no Rust (AD-2).
     */
    fun answerRinging(callId: Int): Boolean = command(callId) {
        if (it.call.state == Call.STATE_RINGING) it.call.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    /** The notification's Decline: rejects only a call that is still ringing; no activity, no Rust. */
    fun declineRinging(callId: Int): Boolean = command(callId) {
        if (it.call.state == Call.STATE_RINGING) it.call.reject(false, null)
    }

    /** Any call is ringing right now (any thread; from the last snapshot). */
    fun isRinging(callId: Int? = null): Boolean = ringingIds.let { if (callId == null) it.isNotEmpty() else callId in it }

    override fun dtmfStop(): Boolean {
        main.post { stopDtmfNow() }
        return true
    }

    private fun stopDtmfNow() {
        main.removeCallbacks(dtmfSafetyStop)
        dtmfCall?.stopDtmfTone()
        dtmfCall = null
    }

    override fun answerReplacing(callId: Int): Boolean {
        if (callId !in knownIds) return false
        main.post { startAnswerReplacing(callId) }
        return true
    }

    /** The ongoing-call notification's End: disconnect every live call, directly (AD-2). */
    fun endAll() {
        main.post {
            // A waiting call is not part of "End": rejecting it would send it to voicemail.
            for (e in entries.values.toList()) if (e.call.state != Call.STATE_RINGING) e.call.disconnect()
        }
    }

    fun hasLiveCall(): Boolean = knownIds.isNotEmpty()

    @Volatile private var hasService = false
    @Volatile private var foregroundIdVolatile: Int? = null

    /** Validates against the last snapshot, then runs [block] on the main Looper (re-validating there). */
    private fun command(callId: Int, block: (Entry) -> Unit): Boolean {
        if (callId !in knownIds) return false
        main.post { entries[callId]?.let(block) }
        return true
    }

    // ---- Service callbacks: main thread ---------------------------------

    fun onServiceBound(s: RovstiInCallService) {
        service = s
        hasService = true
    }

    fun onServiceUnbound() {
        for (e in entries.values) e.call.unregisterCallback(e.callback)
        entries.clear()
        pending = null
        main.removeCallbacks(timeout)
        service = null
        hasService = false
        foregroundStarted = false
        ongoingKey = null
        if (incomingKey != null) {
            // The Telecom binding went away: a ringing notification would be a dead end.
            app?.getSystemService(NotificationManager::class.java)?.cancel(CallNotifications.INCOMING_ID)
            incomingKey = null
        }
        muted = false
        endpoints = emptyList()
        currentRoute = null
        rebuild()
    }

    fun onCallAdded(call: Call) {
        val id = nextId++
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                if (state == Call.STATE_SELECT_PHONE_ACCOUNT) selectAccount(call)
                rebuild()
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) = rebuild()
        }
        val entry = Entry(id, call, callback)
        entries[id] = entry
        if (call.state == Call.STATE_RINGING) IncomingPerf.onCallAdded(id, keyguardLocked())
        call.registerCallback(callback, main)
        if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT) selectAccount(call)
        resolveName(entry)
        rebuild()
    }

    fun onCallRemoved(call: Call) {
        val entry = entries.values.firstOrNull { it.call === call } ?: return
        entries.remove(entry.id)
        call.unregisterCallback(entry.callback)
        if (pending?.replacedId == entry.id) completePending()
        rebuild()
    }

    fun onMuteChanged(isMuted: Boolean) {
        muted = isMuted
        rebuild()
    }

    fun onEndpointChanged(endpoint: CallEndpoint) {
        currentRoute = SnapshotJson.routeName(endpoint.endpointType)
        rebuild()
    }

    fun onEndpointsChanged(available: List<CallEndpoint>) {
        endpoints = available
        rebuild()
    }

    /** `MainActivity.onResume`: the lock state may have changed while we were away. */
    fun refreshKeyguard() {
        main.post { rebuild() }
    }

    // ---- Internals: main thread ----------------------------------------

    private fun selectAccount(call: Call) {
        val telecom = app?.getSystemService(TelecomManager::class.java) ?: return
        try {
            val suggested = call.details.intentExtras
                ?.getParcelableArrayList(Call.EXTRA_SUGGESTED_PHONE_ACCOUNTS, android.telecom.PhoneAccountSuggestion::class.java)
                ?.firstOrNull()?.phoneAccountHandle
            val handle = telecom.getDefaultOutgoingPhoneAccount("tel")
                ?: suggested
                ?: telecom.callCapablePhoneAccounts.firstOrNull()
            if (handle != null) call.phoneAccountSelected(handle, false)
        } catch (_: SecurityException) {
        }
    }

    /** Resolves the caller name once, off the main thread, then rebuilds. */
    private fun resolveName(entry: Entry) {
        val context = app ?: return
        val number = entry.call.details.handle?.schemeSpecificPart ?: return
        lookupExecutor.execute {
            val match = ContactsQuery.lookup(context, number) ?: return@execute
            main.post {
                if (entries[entry.id] !== entry) return@post
                entry.displayName = match.displayName
                entry.contactId = match.contactId
                rebuild()
            }
        }
    }

    private fun startAnswerReplacing(targetId: Int) {
        val target = entries[targetId] ?: return
        // Prefer the call the user is on (active, then dialing) over a held one.
        val others = entries.values.filter { it.id != targetId }
        val active = others.firstOrNull { it.call.state == Call.STATE_ACTIVE }
            ?: others.firstOrNull { it.call.state == Call.STATE_DIALING }
            ?: others.firstOrNull { it.call.state == Call.STATE_HOLDING }
        if (active == null) {
            target.call.answer(VideoProfile.STATE_AUDIO_ONLY)
            return
        }
        completePending() // A previous operation never outlives a new one.
        pending = Pending(targetId, active.id)
        main.postDelayed(timeout, ANSWER_REPLACING_TIMEOUT_MS)
        active.call.disconnect()
    }

    /** Answers the pending target on the matching `onCallRemoved` or after the timeout. */
    private fun completePending() {
        val p = pending ?: return
        pending = null
        main.removeCallbacks(timeout)
        entries[p.targetId]?.call?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    private fun stateName(state: Int): String = when (state) {
        Call.STATE_RINGING -> "ringing"
        Call.STATE_ACTIVE -> "active"
        Call.STATE_HOLDING -> "holding"
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> "disconnected"
        Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_DIALING, Call.STATE_PULLING_CALL,
        Call.STATE_SELECT_PHONE_ACCOUNT -> "dialing"
        else -> "unknown"
    }

    private fun keyguardLocked(): Boolean =
        app?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true

    private fun buildCalls(): List<CallInfo> = entries.values.map { e ->
        val d = e.call.details
        val state = stateName(e.call.state)
        val raw = d.handle?.schemeSpecificPart ?: ""
        CallInfo(
            id = e.id,
            state = state,
            numberRaw = raw,
            normalized = ProviderJson.normalize(raw),
            displayName = e.displayName,
            contactId = e.contactId,
            connectTimeMs = d.connectTimeMillis.coerceAtLeast(0),
            canHold = d.can(Call.Details.CAPABILITY_HOLD),
            isIncoming = d.callDirection == Call.Details.DIRECTION_INCOMING,
            disconnectCause = if (state == "disconnected") SnapshotJson.causeName(d.disconnectCause?.code ?: 0) else null,
        )
    }

    /** Builds the snapshot, stores it, and pushes it (AD-4). Main thread only. */
    private fun rebuild() {
        val calls = buildCalls()
        val focus = Focus.of(calls)
        foregroundId = focus.foreground
        val routes = endpoints.mapNotNull { SnapshotJson.routeName(it.endpointType) }.distinct()
        val data = SnapshotData(
            seq = ++seq,
            nowMs = System.currentTimeMillis(),
            keyguardLocked = keyguardLocked(),
            audio = AudioInfo(muted, currentRoute ?: "earpiece", routes),
            foregroundCallId = focus.foreground,
            waitingCallId = focus.waiting,
            calls = calls,
        )
        val text = SnapshotJson.toJson(data)
        knownIds = calls.mapTo(HashSet()) { it.id }
        ringingIds = calls.filter { it.state == "ringing" }.mapTo(HashSet()) { it.id }
        foregroundIdVolatile = focus.foreground
        json = text
        // A pending answer-replacing clears on any snapshot where the target is gone.
        pending?.let { if (it.targetId !in knownIds) { pending = null; main.removeCallbacks(timeout) } }
        updateKeyguardReceiver(calls.isNotEmpty())
        // The activity first: its lock-screen flags must be set before the full-screen intent fires.
        syncLiveCall(calls.isNotEmpty())
        syncIncoming(IncomingCall.of(calls))
        syncOngoing(OngoingCall.of(calls, focus))
        syncProximity(calls, focus)
        RovstiBridge.emit("calls", text)
    }

    private fun syncLiveCall(live: Boolean) {
        if (live == liveCall) return
        liveCall = live
        surface?.onLiveCallChanged(live)
    }

    /**
     * Posts the incoming CallStyle notification (with the full-screen intent when allowed)
     * for a ringing call and takes it down when the call is answered, declined or gone.
     * Re-posts only when what it shows changes. Kotlin only: answerable without GPUI (AD-2).
     */
    private fun syncIncoming(call: IncomingCall?) {
        val changed = incomingNow?.key != call?.key
        incomingNow = call
        if (changed) surface?.onIncomingChanged(call)
        val context = app ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (call == null) {
            if (incomingKey != null) {
                manager.cancel(CallNotifications.INCOMING_ID)
                incomingKey = null
                IncomingPerf.clear(incomingPerfId)
            }
            return
        }
        if (call.key == incomingKey) return
        incomingPerfId = call.callId
        val first = incomingKey == null
        incomingKey = call.key
        val allowed = try {
            manager.canUseFullScreenIntent()
        } catch (e: RuntimeException) {
            false
        }
        var posted = postIncoming(manager) { CallNotifications.incoming(context, call, allowed) }
        // The platform may still refuse the CallStyle one: the plain heads-up cannot be.
        if (!posted) posted = postIncoming(manager) { CallNotifications.incomingHeadsUp(context, call) }
        if (!posted) {
            incomingKey = null
            return
        }
        if (first) IncomingPerf.mark("notify")
        // No notifications at all means no heads-up either: raising the activity directly is
        // the last resort (it may be refused on HyperOS; nothing else is left to try).
        try {
            if (first && !manager.areNotificationsEnabled()) CallSurface.raise(context)
        } catch (e: RuntimeException) {
            Log.w("Rovsti", "raising the incoming call failed", e)
        }
    }

    /** Builds and posts; a refusal is logged, never thrown, so it can never break the call itself. */
    private inline fun postIncoming(manager: NotificationManager, build: () -> Notification): Boolean = try {
        manager.notify(CallNotifications.INCOMING_ID, build())
        true
    } catch (e: RuntimeException) {
        Log.w("Rovsti", "incoming-call notification failed", e)
        false
    }

    private fun syncProximity(calls: List<CallInfo>, focus: Focus) {
        val context = app ?: return
        val controller = proximity ?: ProximityController(context).also { proximity = it }
        controller.update(
            wanted = ProximityPolicy.wanted(calls, focus, currentRoute ?: "earpiece"),
            callInProgress = ProximityPolicy.inProgress(calls),
        )
    }

    /**
     * Keeps the foreground service (type phoneCall) and its CallStyle notification in
     * line with the live call. Re-posts only when what it shows changes. Kotlin only (AD-2).
     */
    private fun syncOngoing(call: OngoingCall?) {
        val context = app ?: return
        val svc = service ?: return
        if (call == null) {
            if (foregroundStarted) {
                svc.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            ongoingKey = null
            return
        }
        if (call.key == ongoingKey) return
        ongoingKey = call.key
        try {
            val notification = CallNotifications.ongoing(context, call)
            if (foregroundStarted) {
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(CallNotifications.ONGOING_ID, notification)
            } else {
                svc.startForeground(
                    CallNotifications.ONGOING_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
                )
                foregroundStarted = true
            }
        } catch (e: RuntimeException) {
            // Foreground start can be refused (background start rules, missing
            // notification permission); the call itself is unaffected.
            Log.w("Rovsti", "ongoing-call notification failed", e)
            ongoingKey = null
        }
    }

    /** Lock-state receivers exist only while a call does (AD-3). */
    private fun updateKeyguardReceiver(hasCalls: Boolean) {
        val context = app ?: return
        if (hasCalls == keyguardReceiverRegistered) return
        keyguardReceiverRegistered = hasCalls
        if (hasCalls) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            context.registerReceiver(keyguardReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            try { context.unregisterReceiver(keyguardReceiver) } catch (_: IllegalArgumentException) {}
        }
    }
}
