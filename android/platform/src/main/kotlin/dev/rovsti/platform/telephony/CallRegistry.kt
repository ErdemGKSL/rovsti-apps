@file:Suppress("ktlint:standard:if-else-wrapping", "ktlint:standard:max-line-length")

package dev.rovsti.platform.telephony

// Ported from apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/CallRegistry.kt (ARCH 7.3).

import android.annotation.SuppressLint
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.OutcomeReceiver
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import dev.rovsti.core.Envelope
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.WireCall
import dev.rovsti.design.time.RovstiClock
import dev.rovsti.design.time.SystemRovstiClock
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.calllog.BlockGuard
import dev.rovsti.platform.calllog.Blocklist
import dev.rovsti.platform.contacts.ContactsQuery
import dev.rovsti.platform.system.Haptics
import dev.rovsti.platform.system.SettingsMirror
import dev.rovsti.platform.wire.AccountRef
import dev.rovsti.platform.wire.AudioInfo
import dev.rovsti.platform.wire.CallInfo
import dev.rovsti.platform.wire.Focus
import dev.rovsti.platform.wire.ProviderJson
import dev.rovsti.platform.wire.SnapshotData
import dev.rovsti.platform.wire.SnapshotJson
import dev.rovsti.platform.wire.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Owns all call state (AD-4). Fed only by the app's `InCallService` callbacks (it calls [onCallAdded] and the like and
 * hands itself over through [onServiceBound] as an [InCallHost]), which
 * run on the main Looper. Every mutation and snapshot build happens there, in
 * one synchronous step; no lock is held across a binder call. Other threads
 * (Rust commands) only read the volatile snapshot and post to the main Looper.
 *
 * Audio uses `CallEndpoint` only (minSdk 34).
 */
@SuppressLint("MissingPermission") // the permissions are declared by the app manifests
object CallRegistry : CallsApi {
    /** The activity that shows calls; told when a live call appears or the last one goes (AD-1). */
    interface Surface {
        fun onLiveCallChanged(live: Boolean)

        /** Rust drew the first frame of the Incoming Call screen (story 5.6). */
        fun onIncomingFrame(callId: Int) {}

        /** The ringing call changed (answered, declined, gone, or a new one): `null` when none rings. */
        fun onIncomingChanged(call: IncomingCall?) {}
    }

    private const val DTMF_SAFETY_MS = 4_000L
    private const val SELECT_TIMEOUT_MS = 10_000L
    private const val AUDIO_FALLBACK_MS = 1_500L

    private class Entry(
        val id: Int,
        val call: Call,
        val callback: Call.Callback,
    ) {
        var displayName: String? = null
        var displayNameAlt: String? = null
        var contactId: Long? = null

        /** The call went Active once (the connect vibration plays once per call, story 16.8). */
        var connectedOnce = false

        /** A system account picker for this call waits for an answer from Rust (story 16.3). */
        var selecting = false

        /** Wall-clock time the call went on hold (null while it is not held). */
        var holdSince: Long? = null

        /** A reject was issued; a second one (a double tap, a stale intent) does nothing (story 18.1). */
        var rejected = false
    }

    /** What the any-thread reject path needs about a ringing call, from the last rebuild. */
    private class RingInfo(
        val number: String,
        val respondViaText: Boolean,
    )

    /**
     * The main Looper. Telecom delivers every `Call.Callback` through it and every command is posted
     * to it, so it is the one place a failure is caught: an exception escaping here would kill the
     * process, and Telecom would hand the call to the system dialer.
     */
    private val main =
        object : Handler(Looper.getMainLooper()) {
            override fun dispatchMessage(msg: Message) {
                try {
                    super.dispatchMessage(msg)
                } catch (e: RuntimeException) {
                    Log.e("Rovsti", "call registry task failed; the call goes on", e)
                }
            }
        }

    /** Runs [block], logging instead of throwing; for calls out to the activity and the snapshot build. */
    private inline fun safely(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.e("Rovsti", "$what failed; the call goes on", e)
        }
    }

    private val lookupExecutor: Executor by lazy { Executors.newSingleThreadExecutor() }

    // Read from any thread.
    @Volatile private var json: String = SnapshotJson.toJson(SnapshotJson.empty(0, 0, true))

    @Volatile private var knownIds: Set<Int> = emptySet()

    @Volatile private var ringingIds: Set<Int> = emptySet()

    @Volatile private var ringInfo: Map<Int, RingInfo> = emptyMap()

    /** Story 18.4: who silenced the ringer of the ringing call ("user", "volume", "flip"); main thread. */
    private var silencedBy: String? = null

    @Volatile private var silenceAsk: String? = null

    @Volatile private var guardHits = 0

    @Volatile private var silenceCalls = 0
    private var flip: FlipDetector? = null
    private val blockGuard by lazy {
        BlockGuard(
            lookup = lookupExecutor,
            main = { r -> main.post(r) },
            isBlocked = { number -> app?.let { Blocklist.isBlocked(Blocklist.System(it), number) } ?: false },
        )
    }

    /** The ringing call as the notification shows it; null when nothing rings. */
    @Volatile var incomingNow: IncomingCall? = null
        private set

    // Main-thread state.
    private var app: Application? = null
    private var service: InCallHost? = null
    private val entries = LinkedHashMap<Int, Entry>()
    private var nextId = 1
    private var seq = 0L
    private var muted = false
    private var canAddCall = false
    private val audio = AudioPath()
    private var firstCallSeen = false
    private var routeTargetId: String? = null
    private var routeRequestedAt = 0L

    @Volatile private var routeLatency = -1L
    private val events = CallEventRing()
    private val coalescer =
        RebuildCoalescer(
            post = { r, delay -> main.postDelayed(r, delay) },
            cancel = { r -> main.removeCallbacks(r) },
            rebuild = { safely("rebuild") { rebuildNow() } },
        )
    private val audioDeadline =
        Runnable {
            if (audio.onFallbackDeadline()) {
                record("audioFallback", -1, "none")
                rebuild()
            }
        }
    private var foregroundId: Int? = null
    private val ops = PendingMachine { SystemClock.elapsedRealtime() }
    private var opStartedAt = 0L

    // Spike S4 measurements (main thread writes, any thread reads).
    @Volatile private var facts = MultiCallFacts()
    private var holdAnswerWatch: Int? = null
    private var keyguardReceiverRegistered = false
    private var ongoingKey: String? = null
    private var foregroundStarted = false
    private var incomingKey: String? = null
    private var incomingPerfId = -1
    private var liveCall = false
    private var proximity: ProximityController? = null

    /** Set by the app's activity while it exists; main thread only. */
    var surface: Surface? = null

    /** Ring-latency marks (story 5.6). `:phone` installs its ring-latency logger here (E5); null means nobody measures. */
    var timing: IncomingTiming? = null

    /** Wall-clock source for `now_ms` and hold times (the shared seam; tests replace it). */
    @Volatile var clock: RovstiClock = SystemRovstiClock

    private val callsFlow = MutableStateFlow<List<WireCall>>(emptyList())

    /** Every live call as the last rebuild saw it, in registry order. Emits after the notification work of that rebuild. */
    val state: StateFlow<List<WireCall>> = callsFlow.asStateFlow()

    private val opDeadline = Runnable { onOpDeadline() }
    private val dtmfSafetyStop = Runnable { stopDtmfNow() }
    private var dtmfCall: Call? = null

    private val keyguardReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = rebuild()
        }

    /** Called from `Application.onCreate`; Kotlin only, no native library. */
    fun init(application: Application) {
        app = application
    }

    // ---- CallsApi: any thread ------------------------------------------

    override fun snapshot(): String = json

    override fun answer(callId: Int) = command(callId) { it.call.answer(VideoProfile.STATE_AUDIO_ONLY) }

    override fun reject(callId: Int) = command(callId) { rejectOnce(it) { c -> c.reject(false, null) } }

    /** Rejects a ringing call exactly once, whichever path asked first (story 18.1). */
    private inline fun rejectOnce(
        entry: Entry,
        send: (Call) -> Unit,
    ) {
        if (entry.rejected || entry.call.state != Call.STATE_RINGING) return
        entry.rejected = true
        send(entry.call)
    }

    override fun rejectWithMessage(
        callId: Int,
        text: String,
    ): Int {
        val info = ringInfo[callId]
        val decision =
            RejectPolicy.decide(
                text = text,
                ringing = info != null,
                telecomPath = Caps.REJECT_PATH == "TELECOM",
                respondViaText = info?.respondViaText == true,
                hasNumber = info?.number?.isNotBlank() == true,
                // The SMS app is not resolvable without a <queries> entry (check-apk allows three); try and catch.
                canCompose = true,
            )
        val action = decision.action ?: return decision.code
        val body = text.trim()
        main.post {
            val entry = entries[callId] ?: return@post
            rejectOnce(entry) { call ->
                when (action) {
                    RejectPolicy.Action.TELECOM -> {
                        try {
                            call.reject(true, body)
                        } catch (_: RuntimeException) {
                            call.reject(false, null)
                        }
                    }

                    RejectPolicy.Action.HANDOFF -> {
                        call.reject(false, null)
                        openMessages(info?.number ?: "", body)
                    }

                    RejectPolicy.Action.REJECT_ONLY -> {
                        call.reject(false, null)
                    }
                }
            }
        }
        return decision.code
    }

    override fun rejectAndCompose(callId: Int): Boolean {
        val info = ringInfo[callId] ?: return false
        main.post {
            val entry = entries[callId] ?: return@post
            rejectOnce(entry) { call ->
                call.reject(false, null)
                openMessages(info.number, null)
            }
        }
        return true
    }

    /** `ACTION_SENDTO smsto:` with the text typed in; the SMS app sends it, never this app (AD-28). */
    private fun openMessages(
        number: String,
        body: String?,
    ) {
        val context = app ?: return
        try {
            val intent =
                Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (body != null) intent.putExtra("sms_body", body)
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w("Rovsti", "no app to compose the reply", e)
        }
    }

    // ---- Silence the ringer (story 18.4, AD-29) ----------------------------

    override fun silenceRinger(): Boolean = silence("user")

    /** Silences from the app; [by] says who asked. A refusal (no permission) is false and changes nothing. */
    private fun silence(by: String): Boolean {
        if (ringingIds.isEmpty()) return false
        val manager = app?.getSystemService(TelecomManager::class.java) ?: return false
        silenceAsk = by
        return try {
            manager.silenceRinger()
            main.post {
                if (silencedBy == null && entries.values.any { it.call.state == Call.STATE_RINGING }) {
                    silencedBy = by
                    rebuild()
                }
            }
            true
        } catch (_: SecurityException) {
            silenceAsk = null
            false
        } catch (_: RuntimeException) {
            silenceAsk = null
            false
        }
    }

    /** `InCallService.onSilenceRinger()`: the volume key, the flip or our own command. */
    fun onSilenceRinger() {
        silenceCalls++
        if (silencedBy == null && entries.values.any { it.call.state == Call.STATE_RINGING }) {
            silencedBy = silenceAsk ?: "volume"
            rebuild()
        }
    }

    override fun blockGuardHits(): Int = guardHits

    override fun blockGuardOn(): Boolean = Caps.BLOCK_GUARD

    override fun silenceCallbacks(): Int = silenceCalls

    /** The flip fired: silence once, with the flip haptic. */
    private fun onFlipped() {
        if (silencedBy != null) return
        if (silence("flip")) app?.let { Haptics.click(it) }
    }

    override fun disconnect(callId: Int) = command(callId) { it.call.disconnect() }

    override fun hold(
        callId: Int,
        on: Boolean,
    ) = command(callId) {
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
            if (audio.legacy) {
                val mask = AudioPath.legacyRouteMask("${AudioPath.LEGACY_PREFIX}$name")
                if (mask != 0) service?.setAudioRoute(mask)
                return@post
            }
            val target = audio.target(name) ?: return@post
            requestEndpoint(target)
        }
        return true
    }

    /** Routes by endpoint id (AD-23); an unknown id returns false and does nothing. */
    override fun routeEndpoint(id: String): Boolean {
        if (!hasService) return false
        val legacy = id.startsWith(AudioPath.LEGACY_PREFIX)
        if (!legacy && id !in audio.knownIds) return false
        main.post {
            if (legacy) {
                val mask = AudioPath.legacyRouteMask(id)
                if (mask != 0) service?.setAudioRoute(mask)
                return@post
            }
            routeTargetId = id
            routeRequestedAt = SystemClock.elapsedRealtime()
            routeLatency = -1
            // Re-validated here: the list may have changed since the caller looked.
            val target = audio.endpoint(id)
            if (target == null) rebuild() else requestEndpoint(target)
        }
        return true
    }

    private fun requestEndpoint(target: CallEndpoint) {
        // The answer comes back on the main Looper: rebuild touches main-thread state only.
        service?.requestEndpoint(
            target,
            Executor { main.post(it) },
            object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) {}

                override fun onError(error: CallEndpointException) {
                    // The route did not change: tell the UI at once so it reverts its pending look.
                    record("routeFailed", -1, "none")
                    rebuild()
                }
            },
        )
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
            timing?.frame(callId)
            safely("onIncomingFrame") { surface?.onIncomingFrame(callId) }
        }
        return true
    }

    /**
     * The notification's Answer (story 5.3): answers only a call that is still ringing, so a
     * stale or repeated tap does nothing. Needs no Rust (AD-2).
     */
    fun answerRinging(callId: Int): Boolean =
        command(callId) {
            if (it.call.state == Call.STATE_RINGING) it.call.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

    /** The notification's Decline: rejects only a call that is still ringing; no activity, no Rust. */
    fun declineRinging(callId: Int): Boolean =
        command(callId) {
            rejectOnce(it) { c -> c.reject(false, null) }
        }

    /** Any call is ringing right now (any thread; from the last snapshot). */
    fun isRinging(callId: Int? = null): Boolean =
        ringingIds.let {
            if (callId ==
                null
            ) {
                it.isNotEmpty()
            } else {
                callId in it
            }
        }

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

    /**
     * Ends every non-ringing top-level call, directly (AD-2): the ongoing notification's End and the
     * lock-screen button. A waiting call is not part of it: rejecting it would send it to voicemail.
     */
    override fun endAll(): Boolean {
        main.post {
            for (e in entries.values.toList()) {
                if (e.call.state != Call.STATE_RINGING && e.call.parent == null) e.call.disconnect()
            }
        }
        return true
    }

    override fun multiCallFacts(): MultiCallFacts = facts

    // ---- Multi-call commands (AD-24, AD-25) -------------------------------

    /** Swaps by `swapConference()` when the foreground has the capability, else resumes the held call. */
    override fun swap(): Boolean {
        if (!hasService || knownIds.isEmpty()) return false
        main.post { startSwap() }
        return true
    }

    /** Merges by `mergeConference()` or `conference(other)`. */
    override fun merge(): Boolean {
        if (!hasService || knownIds.isEmpty()) return false
        main.post { startMerge() }
        return true
    }

    /** Splits a participant out; only with the split capability on that child. */
    override fun split(childId: Int): Boolean =
        command(childId) {
            if (it.call.details.can(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE)) it.call.splitFromConference()
        }

    /** `answer()`, offered only when the current call can be held and no held call exists. */
    override fun answerHolding(callId: Int): Boolean {
        if (callId !in knownIds) return false
        main.post { startAnswerHolding(callId) }
        return true
    }

    /** The relabelled "End held call and answer": the held call goes, then the ringing one is answered. */
    override fun answerEndingHeld(callId: Int): Boolean {
        if (callId !in knownIds) return false
        main.post { startAnswerReplacing(callId, preferHeld = true) }
        return true
    }

    fun hasLiveCall(): Boolean = knownIds.isNotEmpty()

    @Volatile private var hasService = false

    @Volatile private var foregroundIdVolatile: Int? = null

    /** Validates against the last snapshot, then runs [block] on the main Looper (re-validating there). */
    private fun command(
        callId: Int,
        block: (Entry) -> Unit,
    ): Boolean {
        if (callId !in knownIds) return false
        main.post { entries[callId]?.let(block) }
        return true
    }

    // ---- Service callbacks: main thread ---------------------------------

    fun onServiceBound(s: InCallHost) {
        service = s
        hasService = true
    }

    fun onServiceUnbound() {
        for (e in entries.values) e.call.unregisterCallback(e.callback)
        entries.clear()
        ops.clear()
        main.removeCallbacks(opDeadline)
        holdAnswerWatch = null
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
        canAddCall = false
        audio.clear()
        firstCallSeen = false
        main.removeCallbacks(audioDeadline)
        record("onUnbind", -1, "none")
        rebuild()
    }

    fun onCallAdded(call: Call) {
        val id = nextId++
        val callback =
            object : Call.Callback() {
                override fun onStateChanged(
                    call: Call,
                    state: Int,
                ) {
                    if (state == Call.STATE_SELECT_PHONE_ACCOUNT) {
                        selectAccount(call)
                    } else {
                        entries[id]?.selecting = false
                    }
                    vibrateFor(id, state)
                    entries[id]?.let {
                        it.holdSince =
                            if (state == Call.STATE_HOLDING) (it.holdSince ?: clock.nowMillis()) else null
                    }
                    record("onStateChanged", id, stateName(state))
                    observeHoldAnswer(id, state)
                    rebuild()
                }

                override fun onDetailsChanged(
                    call: Call,
                    details: Call.Details,
                ) {
                    record("onDetailsChanged", id, stateName(call.state))
                    rebuild()
                }

                // These feed focus and caps: they rebuild at once, so a merge never shows a transient scene.
                override fun onParentChanged(
                    call: Call,
                    parent: Call?,
                ) = rebuild()

                override fun onChildrenChanged(
                    call: Call,
                    children: List<Call>,
                ) = rebuild()

                override fun onConferenceableCallsChanged(
                    call: Call,
                    conferenceableCalls: List<Call>,
                ) = rebuild()
            }
        val entry = Entry(id, call, callback)
        if (call.state == Call.STATE_HOLDING) entry.holdSince = clock.nowMillis()
        entries[id] = entry
        if (call.state == Call.STATE_RINGING) {
            timing?.onCallAdded(id, keyguardLocked())
            guardRinging(entry)
        }
        call.registerCallback(callback, main)
        if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT) selectAccount(call)
        resolveName(entry)
        record("onCallAdded", id, stateName(call.state))
        if (!firstCallSeen) {
            firstCallSeen = true
            main.postDelayed(audioDeadline, AUDIO_FALLBACK_MS)
        }
        rebuild()
    }

    /**
     * `Caps.BLOCK_GUARD` (story 17.1): the lookup runs on [lookupExecutor], never the main Looper and
     * never before the notification; a hit rejects the call and takes the notification down.
     */
    private fun guardRinging(entry: Entry) {
        val number =
            entry.call.details.handle
                ?.schemeSpecificPart ?: return
        val id = entry.id
        blockGuard.onRinging(number, Caps.BLOCK_GUARD) {
            val e = entries[id] ?: return@onRinging
            if (e.call.state != Call.STATE_RINGING) return@onRinging
            record("blockGuard", id, "ringing")
            guardHits++
            rejectOnce(e) { c -> c.reject(false, null) }
            app?.getSystemService(NotificationManager::class.java)?.cancel(CallNotifications.INCOMING_ID)
        }
    }

    fun onCallRemoved(call: Call) {
        if (entries.size <= 1) {
            firstCallSeen = false
            main.removeCallbacks(audioDeadline)
        }
        val entry = entries.values.firstOrNull { it.call === call } ?: return
        entries.remove(entry.id)
        record("onCallRemoved", entry.id, "none")
        call.unregisterCallback(entry.callback)
        observeHoldAnswer(entry.id, Call.STATE_DISCONNECTED)
        rebuild()
    }

    fun onMuteChanged(isMuted: Boolean) {
        muted = isMuted
        record("onMuteStateChanged", -1, "none")
        scheduleRebuild()
    }

    fun onEndpointChanged(endpoint: CallEndpoint) {
        audio.onCurrent(endpoint)
        record("onCallEndpointChanged", -1, "none")
        scheduleRebuild()
    }

    fun onEndpointsChanged(available: List<CallEndpoint>) {
        audio.onAvailable(available, AudioPath.hearingAidNames(app))
        record("onAvailableCallEndpointsChanged", -1, "none")
        scheduleRebuild()
    }

    fun onCanAddCallChanged(can: Boolean) {
        canAddCall = can
        record("onCanAddCallChanged", -1, "none")
        scheduleRebuild()
    }

    /** The activity's `onResume`: the lock state may have changed while we were away. */
    fun refreshKeyguard() {
        main.post { rebuild() }
    }

    // ---- Internals: main thread ----------------------------------------

    /**
     * The registry safety net for `STATE_SELECT_PHONE_ACCOUNT` raised by another app (AD-26): when the
     * phone is locked, Rust has no live session, or nobody answers within [SELECT_TIMEOUT_MS], the
     * account is chosen as in v0.1; otherwise the snapshot says `selecting_account` and Rust answers
     * with `callSelectAccount`.
     */
    private fun selectAccount(call: Call) {
        val entry = entries.values.firstOrNull { it.call === call }
        if (entry == null || !PlatformEvents.uiAttached || keyguardLocked()) {
            autoSelectAccount(call)
            return
        }
        if (entry.selecting) return
        entry.selecting = true
        main.postDelayed({
            if (entries[entry.id] === entry && entry.selecting) {
                entry.selecting = false
                autoSelectAccount(call)
                rebuild()
            }
        }, SELECT_TIMEOUT_MS)
    }

    private fun autoSelectAccount(call: Call) {
        val telecom = app?.getSystemService(TelecomManager::class.java) ?: return
        try {
            val suggested =
                call.details.intentExtras
                    ?.getParcelableArrayList(
                        Call.EXTRA_SUGGESTED_PHONE_ACCOUNTS,
                        android.telecom.PhoneAccountSuggestion::class.java,
                    )?.firstOrNull()
                    ?.phoneAccountHandle
            val handle =
                telecom.getDefaultOutgoingPhoneAccount("tel")
                    ?: suggested
                    ?: telecom.callCapablePhoneAccounts.firstOrNull()
            if (handle != null) call.phoneAccountSelected(handle, false)
        } catch (_: SecurityException) {
        }
    }

    override fun selectAccount(
        callId: Int,
        key: String,
    ): Boolean {
        val context = app ?: return false
        val handle = SimAccounts.handleFor(context, key) ?: return false
        main.post {
            val entry = entries[callId] ?: return@post
            if (entry.call.state != Call.STATE_SELECT_PHONE_ACCOUNT) return@post
            entry.selecting = false
            try {
                entry.call.phoneAccountSelected(handle, false)
            } catch (_: SecurityException) {
            }
            rebuild()
        }
        return true
    }

    /** Connect and end vibration (story 16.8): once per call, only when the mirror says so. */
    private fun vibrateFor(
        id: Int,
        state: Int,
    ) {
        val context = app ?: return
        val entry = entries[id] ?: return
        if (state == Call.STATE_ACTIVE && !entry.connectedOnce) {
            entry.connectedOnce = true
            Haptics.forEvent(context, "connect")
        } else if (state == Call.STATE_DISCONNECTED && entry.connectedOnce && id == foregroundId) {
            Haptics.forEvent(context, "end")
        }
    }

    /** Resolves the caller name once, off the main thread, then rebuilds. */
    private fun resolveName(entry: Entry) {
        val context = app ?: return
        val number =
            entry.call.details.handle
                ?.schemeSpecificPart ?: return
        lookupExecutor.execute {
            val match = ContactsQuery.lookup(context, number) ?: return@execute
            main.post {
                if (entries[entry.id] !== entry) return@post
                entry.displayName = match.displayName
                entry.displayNameAlt = match.displayNameAlt
                entry.contactId = match.contactId
                rebuild()
            }
        }
    }

    private fun liveTop(): List<Entry> =
        entries.values.filter { it.call.parent == null && it.call.state in LIVE_STATES }

    private fun startAnswerReplacing(
        targetId: Int,
        preferHeld: Boolean = false,
    ) {
        val target = entries[targetId] ?: return
        val others = entries.values.filter { it.id != targetId && it.call.parent == null }
        val active =
            others.firstOrNull { it.call.state == Call.STATE_ACTIVE }
                ?: others.firstOrNull { it.call.state == Call.STATE_DIALING }
        val held = others.firstOrNull { it.call.state == Call.STATE_HOLDING }
        // Relabelled case: the held call goes and the network holds the active one.
        val victim = if (preferHeld && held != null) held else (active ?: held)
        if (victim == null) {
            target.call.answer(VideoProfile.STATE_AUDIO_ONLY)
            return
        }
        if (!ops.start(PendingOp.AnswerReplacing(targetId, victim.id))) return
        armOpDeadline()
        victim.call.disconnect()
        rebuild() // the wire says `pending: answer` at once
    }

    private fun startAnswerHolding(callId: Int) {
        val target = entries[callId] ?: return
        val current = liveTop().firstOrNull { it.id != callId }
        if (current != null) {
            val canHold = current.call.details.can(Call.Details.CAPABILITY_HOLD)
            val heldExists = liveTop().any { it.id != callId && it.call.state == Call.STATE_HOLDING }
            if (!canHold || heldExists) return
            holdAnswerWatch = current.id
        }
        target.call.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    /** S4 criterion 4: after a hold-and-answer, did the first call end up held or disconnected? */
    private fun observeHoldAnswer(
        id: Int,
        state: Int,
    ) {
        if (holdAnswerWatch != id) return
        when (state) {
            Call.STATE_HOLDING -> facts = facts.copy(holdAnswerHeldFirst = true)
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> facts = facts.copy(holdAnswerHeldFirst = false)
            else -> return
        }
        holdAnswerWatch = null
    }

    private fun startSwap() {
        val top = liveTop()
        val fg =
            top.firstOrNull { it.call.state == Call.STATE_ACTIVE }
                ?: top.firstOrNull { it.call.state == Call.STATE_DIALING }
        val held = top.firstOrNull { it.call.state == Call.STATE_HOLDING && it !== fg }
        if (fg != null && fg.call.details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE)) {
            if (held == null) {
                fg.call.swapConference()
                return
            }
            if (!ops.start(PendingOp.Swap(held.id, fg.id))) return
            opStartedAt = SystemClock.elapsedRealtime()
            armOpDeadline()
            fg.call.swapConference()
            rebuild()
            return
        }
        if (held == null) return
        if (!ops.start(PendingOp.Swap(held.id, fg?.id))) return
        opStartedAt = SystemClock.elapsedRealtime()
        armOpDeadline()
        held.call.unhold()
        rebuild()
    }

    private fun startMerge() {
        val top = liveTop()
        val fg = top.firstOrNull { it.call.state == Call.STATE_ACTIVE } ?: top.firstOrNull()
        val before = conferenceChildCount()
        fg ?: return
        val canMergeCap = fg.call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)
        var host: Entry? = null
        var other: Entry? = null
        if (!canMergeCap) {
            for (t in top) {
                val c =
                    t.call.conferenceableCalls.firstNotNullOfOrNull { c ->
                        entries.values.firstOrNull {
                            it.call ===
                                c
                        }
                    }
                if (c != null) {
                    host = t
                    other = c
                    break
                }
            }
            if (host == null || other == null) return
        }
        if (!ops.start(PendingOp.Merge(before))) return
        opStartedAt = SystemClock.elapsedRealtime()
        armOpDeadline()
        if (canMergeCap) fg.call.mergeConference() else host!!.call.conference(other!!.call)
        rebuild()
    }

    private fun conferenceChildCount(): Int =
        entries.values
            .filter {
                it.call.parent == null &&
                    it.call.details.hasProperty(
                        Call.Details.PROPERTY_CONFERENCE,
                    )
            }.maxOfOrNull { it.call.children.size } ?: 0

    private fun armOpDeadline() {
        main.removeCallbacks(opDeadline)
        ops.nextDeadlineIn()?.let { main.postDelayed(opDeadline, it) }
    }

    /** The op's deadline passed: one swap retry, else it fails (only an answer still answers). */
    private fun onOpDeadline() {
        when (val tick = ops.tick()) {
            Tick.Idle -> {}

            is Tick.Retry -> {
                entries[tick.op.former ?: -1]?.takeIf { it.call.state == Call.STATE_ACTIVE }?.call?.hold()
                armOpDeadline()
            }

            is Tick.Failed -> {
                record("opFailed", -1, "none")
                val op = tick.op
                if (op is PendingOp.AnswerReplacing) entries[op.targetId]?.call?.answer(VideoProfile.STATE_AUDIO_ONLY)
                rebuild()
            }
        }
    }

    /** Called on every rebuild: finishes the running op as Done when Telecom shows its result. */
    private fun checkOp() {
        val op = ops.current ?: return
        val done =
            when (op) {
                is PendingOp.AnswerReplacing -> {
                    entries[op.replacedId]?.call?.state.let {
                        it == null ||
                            it == Call.STATE_DISCONNECTED
                    }
                }

                is PendingOp.Swap -> {
                    val resumed = entries[op.toResume]?.call?.state == Call.STATE_ACTIVE
                    val former = op.former?.let { entries[it]?.call?.state }
                    // For a conference swap the roles exchange, so "resumed" is the held side becoming Active.
                    resumed && (op.former == null || op.former == op.toResume || former != Call.STATE_ACTIVE)
                }

                is PendingOp.Merge -> {
                    conferenceChildCount() > op.childrenBefore
                }
            }
        if (!done) return
        val finished = ops.finish(OpResult.DONE)
        main.removeCallbacks(opDeadline)
        val took = SystemClock.elapsedRealtime() - opStartedAt
        when (finished) {
            is PendingOp.AnswerReplacing -> {
                entries[finished.targetId]?.call?.answer(VideoProfile.STATE_AUDIO_ONLY)
            }

            is PendingOp.Swap -> {
                val both = liveTop().size >= 2 && liveTop().none { it.call.state == Call.STATE_ACTIVE }
                facts = facts.copy(swapLatencyMs = took, bothHeldSeen = both)
            }

            is PendingOp.Merge -> {
                facts = facts.copy(mergeLatencyMs = took, mergeProducedConference = true)
            }

            null -> {}
        }
    }

    /** The accounts a pending picker offers (key and label). */
    private fun selectableAccounts(): List<AccountRef> {
        val context = app ?: return emptyList()
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            telecom?.callCapablePhoneAccounts?.map { h ->
                AccountRef(
                    SimAccounts.keyOf(h),
                    telecom
                        .getPhoneAccount(h)
                        ?.label
                        ?.toString()
                        .orEmpty(),
                )
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private val LIVE_STATES = setOf(Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_HOLDING, Call.STATE_CONNECTING)

    private fun stateName(state: Int): String =
        when (state) {
            Call.STATE_RINGING -> "ringing"

            Call.STATE_ACTIVE -> "active"

            Call.STATE_HOLDING -> "holding"

            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> "disconnected"

            Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_DIALING, Call.STATE_PULLING_CALL,
            Call.STATE_SELECT_PHONE_ACCOUNT,
            -> "dialing"

            else -> "unknown"
        }

    private fun keyguardLocked(): Boolean = app?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true

    private fun buildCalls(): List<CallInfo> {
        val ids = java.util.IdentityHashMap<Call, Int>(entries.size * 2)
        for (e in entries.values) ids[e.call] = e.id
        return entries.values.map { e ->
            val d = e.call.details
            val state = stateName(e.call.state)
            val raw = d.handle?.schemeSpecificPart ?: ""
            val children = e.call.children.mapNotNull { ids[it] }
            CallInfo(
                id = e.id,
                state = state,
                numberRaw = raw,
                normalized = ProviderJson.normalize(raw),
                displayName = e.displayName,
                displayNameAlt = e.displayNameAlt,
                contactId = e.contactId,
                connectTimeMs = d.connectTimeMillis.coerceAtLeast(0),
                startedMs = d.creationTimeMillis.coerceAtLeast(0),
                canHold = d.can(Call.Details.CAPABILITY_HOLD),
                isIncoming = d.callDirection == Call.Details.DIRECTION_INCOMING,
                disconnectCause =
                    if (state ==
                        "disconnected"
                    ) {
                        SnapshotJson.causeName(d.disconnectCause?.code ?: 0)
                    } else {
                        null
                    },
                parentId = e.call.parent?.let { ids[it] },
                children = children,
                caps = capsOf(d),
                props = propsOf(d, raw),
                accountKey = d.accountHandle?.let { "${it.componentName.flattenToString()}#${it.id}" },
                holdSinceMs = e.holdSince,
                conferenceable = e.call.conferenceableCalls.isNotEmpty(),
            )
        }
    }

    /** The `caps` bit positions of AD-24 (docs/phone-wire.md section 5), read from `Call.Details`. */
    private fun capsOf(d: Call.Details): Int {
        var mask = 0

        fun bit(
            capability: Int,
            wire: Int,
        ) {
            if (d.can(capability)) mask = mask or wire
        }
        bit(Call.Details.CAPABILITY_HOLD, Wire.Caps.HOLD)
        bit(Call.Details.CAPABILITY_SUPPORT_HOLD, Wire.Caps.SUPPORT_HOLD)
        bit(Call.Details.CAPABILITY_MERGE_CONFERENCE, Wire.Caps.MERGE)
        bit(Call.Details.CAPABILITY_SWAP_CONFERENCE, Wire.Caps.SWAP)
        bit(Call.Details.CAPABILITY_MANAGE_CONFERENCE, Wire.Caps.MANAGE)
        bit(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE, Wire.Caps.SPLIT)
        bit(Call.Details.CAPABILITY_DISCONNECT_FROM_CONFERENCE, Wire.Caps.DISCONNECT_PARTICIPANT)
        bit(Call.Details.CAPABILITY_RESPOND_VIA_TEXT, Wire.Caps.RESPOND_VIA_TEXT)
        bit(Call.Details.CAPABILITY_MUTE, Wire.Caps.MUTE)
        bit(Call.Details.CAPABILITY_ADD_PARTICIPANT, Wire.Caps.ADD_PARTICIPANT)
        return mask
    }

    private fun propsOf(
        d: Call.Details,
        raw: String,
    ): Int {
        var mask = 0
        if (d.hasProperty(Call.Details.PROPERTY_CONFERENCE)) mask = mask or Wire.Props.CONFERENCE
        if (d.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO)) mask = mask or Wire.Props.HD
        if (d.hasProperty(Call.Details.PROPERTY_WIFI)) mask = mask or Wire.Props.WIFI
        if (d.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL) || emergencyNumber(raw)) {
            mask = mask or Wire.Props.EMERGENCY
        }
        return mask
    }

    /** The Kotlin emergency match for a dialled number (AD-24): the platform's own list, never ours. */
    private fun emergencyNumber(raw: String): Boolean =
        raw.isNotEmpty() &&
            try {
                android.telephony.PhoneNumberUtils.isEmergencyNumber(raw)
            } catch (_: RuntimeException) {
                false
            }

    /** Rebuilds at once (cancels a pending scheduled rebuild). Focus, caps and notifications use this. */
    private fun rebuild() = coalescer.now()

    /** Audio, mute and can-add changes: one rebuild within 12 ms however many callbacks arrive (AD-23). */
    private fun scheduleRebuild() = coalescer.schedule()

    /** Names are constants and ids are call ids: the ring never holds a number or a name. */
    private fun record(
        name: String,
        id: Int,
        state: String,
    ) {
        events.record(name, id, state, 0, entries.size, audio.knownIds.size)
    }

    /** The Diagnostics report's event dump (no numbers, no names). */
    override fun eventDump(): List<String> = events.dump()

    override fun routeLatencyMs(): Long = routeLatency

    /** Builds the snapshot, stores it, and pushes it (AD-4). Main thread only. */
    private fun rebuildNow() {
        // An op finishes when Telecom shows its result; an answer target that is gone ends it.
        (ops.current as? PendingOp.AnswerReplacing)?.let {
            if (entries[it.targetId] == null) {
                ops.finish(OpResult.DONE)
                main.removeCallbacks(opDeadline)
            }
        }
        checkOp()
        val calls = buildCalls()
        val ringing = calls.any { it.state == "ringing" }
        // The silenced flag lives only while a call rings.
        if (!ringing) {
            silencedBy = null
            silenceAsk = null
        }
        val selecting = entries.values.firstOrNull { it.selecting }
        // While an operation runs the previous foreground stays in front (no transient scene).
        val focus = Focus.of(calls, if (ops.current != null) foregroundId else null)
        foregroundId = focus.foreground
        val can = MultiCall.canDo(canAddCall, calls, focus)
        val (pendingNow, pendingFailed) = ops.flags()
        val audioInfo = audio.info(muted, if (audio.legacy) service?.legacyAudioState() else null)
        val data =
            SnapshotData(
                seq = ++seq,
                nowMs = clock.nowMillis(),
                keyguardLocked = keyguardLocked(),
                audio = audioInfo,
                foregroundCallId = focus.foreground,
                waitingCallId = focus.waiting,
                backgroundCallId = focus.background,
                endedCallId = focus.ended,
                canAddCall = can.addCall,
                canMerge = can.merge,
                canSwap = can.swap,
                pending = pendingNow,
                pendingFailed = pendingFailed,
                selectingAccount = selecting != null,
                accounts = if (selecting != null) selectableAccounts() else emptyList(),
                ringerSilenced = ringing && silencedBy != null,
                silencedBy = if (ringing) silencedBy else null,
                calls = calls,
            )
        val text = SnapshotJson.toJson(data)
        routeTargetId?.let { target ->
            if (audioInfo.endpoints.any { it.id == target && it.selected }) {
                routeLatency = SystemClock.elapsedRealtime() - routeRequestedAt
                routeTargetId = null
            }
        }
        // Ids and booleans only (spike logs): the burst test counts these lines.
        Log.d(
            "RovstiSpike",
            "snapshot seq=$seq calls=${calls.size} endpoints=${audioInfo.endpoints.size} legacy=${audio.legacy}",
        )
        knownIds = calls.mapTo(HashSet()) { it.id }
        ringingIds = calls.filter { it.state == "ringing" }.mapTo(HashSet()) { it.id }
        ringInfo =
            calls.filter { it.state == "ringing" }.associate {
                it.id to RingInfo(it.numberRaw, it.caps and Wire.Caps.RESPOND_VIA_TEXT != 0)
            }
        syncFlip(ringing && silencedBy == null)
        foregroundIdVolatile = focus.foreground
        json = text
        facts =
            facts.copy(
                conferenceable =
                    entries.values
                        .filter {
                            it.call.conferenceableCalls.isNotEmpty()
                        }.mapTo(HashSet()) { it.id },
            )
        updateKeyguardReceiver(calls.isNotEmpty())
        // The activity first: its lock-screen flags must be set before the full-screen intent fires.
        syncLiveCall(calls.isNotEmpty())
        syncIncoming(IncomingCall.of(calls, SettingsMirror.values.hasReplies))
        syncOngoing(OngoingCall.of(calls, focus))
        syncProximity(calls, focus)
        publish(text)
    }

    /**
     * Last step of a rebuild, after the notifications: the typed list for collectors, then the JSON event for the
     * Rust side. Nothing reaches a collector before the CallStyle notification is posted (ARCH 8.2).
     */
    private fun publish(text: String) {
        safely("decode snapshot") {
            callsFlow.value =
                Envelope.json.decodeFromString(CallSnapshot.serializer(), text).calls
        }
        PlatformEvents.emit("calls", text)
    }

    /** The flip listener exists only while a call rings, is not yet silenced and the setting is on (story 18.4, P-22). */
    private fun syncFlip(wanted: Boolean) {
        val context = app ?: return
        val on = wanted && SettingsMirror.values.flipToSilence
        val detector = flip ?: if (on) FlipDetector(context) { onFlipped() }.also { flip = it } else return
        if (on) detector.start() else detector.stop()
    }

    private fun syncLiveCall(live: Boolean) {
        if (live == liveCall) return
        liveCall = live
        safely("onLiveCallChanged") { surface?.onLiveCallChanged(live) }
    }

    /**
     * Posts the incoming CallStyle notification (with the full-screen intent when allowed)
     * for a ringing call and takes it down when the call is answered, declined or gone.
     * Re-posts only when what it shows changes. Kotlin only: answerable without GPUI (AD-2).
     */
    private fun syncIncoming(call: IncomingCall?) {
        val changed = incomingNow?.key != call?.key
        incomingNow = call
        if (changed) safely("onIncomingChanged") { surface?.onIncomingChanged(call) }
        val context = app ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (call == null) {
            if (incomingKey != null) {
                manager.cancel(CallNotifications.INCOMING_ID)
                incomingKey = null
                timing?.clear(incomingPerfId)
            }
            return
        }
        if (call.key == incomingKey) return
        incomingPerfId = call.callId
        val first = incomingKey == null
        incomingKey = call.key
        val allowed =
            try {
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
        if (first) timing?.mark("notify")
        // No notifications at all means no heads-up either: raising the activity directly is
        // the last resort (it may be refused on HyperOS; nothing else is left to try).
        try {
            if (first && !manager.areNotificationsEnabled()) CallSurface.raise(context)
        } catch (e: RuntimeException) {
            Log.w("Rovsti", "raising the incoming call failed", e)
        }
    }

    /** Builds and posts; a refusal is logged, never thrown, so it can never break the call itself. */
    private inline fun postIncoming(
        manager: NotificationManager,
        build: () -> Notification,
    ): Boolean =
        try {
            manager.notify(CallNotifications.INCOMING_ID, build())
            true
        } catch (e: RuntimeException) {
            Log.w("Rovsti", "incoming-call notification failed", e)
            false
        }

    private fun syncProximity(
        calls: List<CallInfo>,
        focus: Focus,
    ) {
        val context = app ?: return
        val controller = proximity ?: ProximityController(context).also { proximity = it }
        controller.update(
            wanted =
                SettingsMirror.values.proximity &&
                    ProximityPolicy.wanted(
                        calls,
                        focus,
                        audio.routeName() ?: "earpiece",
                    ),
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
                svc.stopForegroundRemove()
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
                context
                    .getSystemService(NotificationManager::class.java)
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
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
            context.registerReceiver(keyguardReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            try {
                context.unregisterReceiver(keyguardReceiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
