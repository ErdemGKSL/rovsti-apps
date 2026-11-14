package dev.rovsti.phone

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.design.time.SystemRovstiClock
import dev.rovsti.design.transition.Tab
import dev.rovsti.people.ui.LocalAvatarResolver
import dev.rovsti.phone.data.People
import dev.rovsti.phone.people.LocalContactPhotos
import dev.rovsti.phone.people.rememberContactPhotos
import dev.rovsti.platform.identity.PhoneNumbers
import dev.rovsti.platform.telephony.AnswerOnce
import dev.rovsti.platform.telephony.CallNotifications
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.platform.telephony.IncomingCall
import dev.rovsti.platform.wa.WaIncoming
import dev.rovsti.shell.NavSpec
import dev.rovsti.shell.RovstiShellActivity
import dev.rovsti.shell.shellContainer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * The one activity of Rovsti Phone (FQCN kept, singleTop). Edge to edge, back order, refresh rate and permission refresh
 * come from [RovstiShellActivity]; this class adds what only a phone app needs:
 *
 * - **Calls over the lock screen (AD-1).** `setShowWhenLocked` and `setTurnScreenOn` are dynamic, never in the manifest:
 *   they hold only while `CallRegistry` reports a live call or `WaCalls` a live WhatsApp call (or a WhatsApp ring's
 *   full-screen intent is waiting for its call, see [LockScreenGate]), and they stay a few seconds after the last call
 *   goes so the "Call ended" hold is still seen.
 * - **Notification intents.** Answer is honoured exactly once, before any UI exists (AD-2, story 5.3); Message opens
 *   Incoming; a WhatsApp Decline sends the task straight back. The WhatsApp notification's Answer and Decline are
 *   broadcasts (`WaCallActionReceiver`); the activity still takes them from intents of older notifications.
 * - **Dial links.** DIAL and VIEW of `tel:` put the number on the Keypad (`dial.fromTelUri` decodes it).
 * - **Back** at a tab root sends the task to the background and keeps the session warm, and does nothing while a call
 *   rings (it must not look like a decline).
 * - **The native fallback.** If the Compose UI cannot start while a call rings, [NativeIncoming] shows Answer and Decline.
 */
class MainActivity :
    RovstiShellActivity<PhoneRoute>(),
    CallRegistry.Surface {
    override val container: PhoneContainer get() = shellContainer() as PhoneContainer

    override val navSpec: NavSpec<PhoneRoute> = NavSpec(PhoneTabs, PhoneRoots, PhoneRoute.serializer())

    private val main = Handler(Looper.getMainLooper())

    // Lazy: the gate reads the keyguard when built, and an activity has no context while it is being constructed
    // (getSystemService then throws and the app crashes on open).
    private val lockScreen by lazy {
        LockScreenGate(
            ::setLockScreenFlags,
            main::postDelayed,
            main::removeCallbacks,
            isKeyguardLocked = { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true },
        )
    }
    private var uiFailed = false

    /** Main-thread jobs of this activity instance (the WhatsApp call list, the keyguard). */
    private val jobs =
        MainScope() +
            CoroutineExceptionHandler { _, e -> Log.e("Rovsti", "Following the WhatsApp calls failed", e) }

    /** Unlocking or the screen going off changes the Incoming screen (slide or buttons) while no cellular call runs. */
    private val keyguardReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = lockScreen.refreshLocked()
        }

    /** The error boundary of the Incoming screen: no first frame in time shows [NativeIncoming] (AD-17). */
    private val watchdog =
        IncomingWatchdog(
            post = { r, delay -> main.postDelayed(r, delay) },
            cancel = { r -> main.removeCallbacks(r) },
            onStuck = { id ->
                Log.e("Rovsti", "The Incoming screen of call $id drew nothing; showing the native screen")
                uiFailed = true
                showNativeFallback()
            },
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        IncomingPerf.mark(IncomingTimeline.ACTIVITY_CREATED)
        // The registry tells this activity about live calls; the lock-screen flags must be right before the first frame.
        CallRegistry.surface = this
        // A fresh launch by a WhatsApp ring (its full-screen intent): the call list may not have the call yet.
        if (savedInstanceState == null) holdForWaRing(intent)
        lockScreen.onLiveCallChanged(CallRegistry.hasLiveCall())
        followWhatsAppCalls()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Back that nothing above handled: keep the session warm, and never look like a decline.
                    if (CallRegistry.isRinging()) return
                    moveTaskToBack(true)
                }
            },
        )
        try {
            super.onCreate(savedInstanceState)
        } catch (e: RuntimeException) {
            // The call lives in CallRegistry and the notifications; only the screen is lost (AD-17).
            Log.e("Rovsti", "The screen could not start; calls are unaffected", e)
            uiFailed = true
            showNativeFallback()
        }
    }

    override fun onResume() {
        super.onResume()
        IncomingPerf.mark(IncomingTimeline.ACTIVITY_RESUMED)
        CallRegistry.refreshKeyguard()
        lockScreen.refreshLocked()
        armWatchdog()
    }

    /** Watches the first frame while the Incoming screen is the page and its call rings. */
    private fun armWatchdog() {
        val ringing = CallRegistry.incomingNow ?: return
        if (!uiFailed && (navigator.current as? PhoneRoute.Incoming)?.callId == ringing.callId) {
            watchdog.arm(ringing.callId)
        }
    }

    /**
     * Power and volume while a call rings over the lock screen: the first press silences the ringer, the second declines
     * (the model decides; it is registered by the Incoming screen's host).
     */
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (event.repeatCount == 0 && keyCode in RING_KEYS && container.ringKey?.invoke() == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onIncomingFrame(callId: Int) = watchdog.frame(callId)

    override fun onStop() {
        watchdog.disarm()
        // Nothing left to show over the lock screen: put the flags back at once.
        if (!lockScreen.wanted) setLockScreenFlags(false)
        // The preferences and notes are written a moment after the last edit; the process may be killed after this.
        container.scope.launch { container.store.flush() }
        super.onStop()
    }

    override fun onDestroy() {
        jobs.cancel()
        runCatching { unregisterReceiver(keyguardReceiver) }
        lockScreen.cancel()
        watchdog.disarm()
        if (CallRegistry.surface === this) CallRegistry.surface = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        holdForWaRing(intent)
        lockScreen.onLiveCallChanged(CallRegistry.hasLiveCall())
        super.onNewIntent(intent)
        armWatchdog()
    }

    @Composable
    override fun Content() {
        // Every avatar goes through the resolver (own photo, else the WhatsApp picture, else initials; X.1d).
        val contacts by container.people.people.collectAsState()
        val photos =
            rememberContactPhotos((contacts as? People.Ready)?.contacts) { PhoneNumbers.e164(it, container.homeRegion) }
        CompositionLocalProvider(
            LocalAvatarResolver provides container.avatarResolver,
            LocalContactPhotos provides photos,
        ) {
            PhoneApp(container = container, navigator = navigator)
        }
    }

    // ---- Intents -----------------------------------------------------------------------------------------------

    override fun routeFor(extras: Bundle?): Pair<Tab, List<PhoneRoute>>? =
        phoneLinkFor(null, null, extras, CallRegistry.hasLiveCall())?.route

    override fun routeFor(intent: Intent): Pair<Tab, List<PhoneRoute>>? {
        handleWaCallIntent(intent)
        val link = phoneLinkFor(intent.action, intent.dataString, intent.extras, CallRegistry.hasLiveCall())
        if (link != null) apply(link)
        // A link is used once: a rotation or a restart must not answer, reply or open the same call again (nor hold
        // the lock-screen flags for a WhatsApp ring that is long over).
        intent.consumePhoneExtras()
        intent.removeExtra(WaIncoming.EXTRA_CALL_ID)
        intent.removeExtra(WaIncoming.EXTRA_ANSWERED)
        return link?.route
    }

    /** The side effects of [link], in the order the notification promises them: answer first, then everything else. */
    private fun apply(link: PhoneLink) {
        val id = link.answerCallId
        if (id != null && link.answerToken == CallNotifications.answerToken &&
            answerOnce.accept(id, CallRegistry.isRinging(id))
        ) {
            CallRegistry.answerRinging(id)
        }
        link.telUri?.let(container::openTelUri)
        link.handoffJid?.let { container.pendingHandoff.value = it }
    }

    /**
     * Answer and Decline on a WhatsApp call notification: [WaIncoming.takeAction] cancels the notification and hands the
     * payload to the container (`WaCallActions` acts on it once). Decline sends the task straight back so it does not open the UI,
     * like a telephony Decline.
     */
    private fun handleWaCallIntent(intent: Intent) {
        val decline = intent.getStringExtra(WaIncoming.EXTRA_ACTION) == WaIncoming.DECLINE
        val payload = WaIncoming.takeAction(this, intent) ?: return
        container.pendingWaCallAction.value = payload
        if (decline) moveTaskToBack(true)
    }

    // ---- Calls over the lock screen (AD-1) -------------------------------------------------------------------

    override fun onLiveCallChanged(live: Boolean) {
        lockScreen.onLiveCallChanged(live)
    }

    /**
     * The intent of a WhatsApp ring (not its Decline) holds the flags until the call list has the call. So does the
     * intent of an Answer taken on the lock-screen notification (`WaIncoming.openAnswered`): its ring is over, the call
     * is not live in the list yet.
     */
    private fun holdForWaRing(intent: Intent?) {
        val id = intent?.getStringExtra(WaIncoming.EXTRA_CALL_ID)?.takeIf { it.isNotEmpty() } ?: return
        if (intent.getStringExtra(WaIncoming.EXTRA_ACTION) == WaIncoming.DECLINE) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val answered = intent.getBooleanExtra(WaIncoming.EXTRA_ANSWERED, false)
        if (answered) WaIncoming.cancelAnswering(this, id)
        if (WaIncoming.isOver(id) && !answered) return
        lockScreen.holdForWaIntent(id)
    }

    /**
     * WhatsApp calls are not in `CallRegistry`: their list drives the lock-screen flags too, and the keyguard state the
     * Incoming screen reads (slide to answer only while locked).
     */
    private fun followWhatsAppCalls() {
        registerReceiver(
            keyguardReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            RECEIVER_NOT_EXPORTED,
        )
        jobs.launch { lockScreen.locked.collect { container.keyguardLocked.value = it } }
        jobs.launch {
            container.waCalls.calls
                .map { calls ->
                    val now = SystemRovstiClock.nowMillis()
                    calls.any { LockScreenGate.holdsScreen(it, now) } to
                        calls.filter { it.phase is CallPhase.Ended }.mapTo(HashSet()) { it.callId }
                }.distinctUntilChanged()
                .collect { (live, ended) -> lockScreen.onWaCalls(live, ended) }
        }
    }

    private fun setLockScreenFlags(on: Boolean) {
        setShowWhenLocked(on)
        setTurnScreenOn(on)
    }

    // ---- The native fallback (AD-17) -----------------------------------------------------------------------------

    /** The native view follows the call: Answer and Decline while it rings, then the message. */
    override fun onIncomingChanged(call: IncomingCall?) {
        if (call == null) watchdog.disarm()
        if (uiFailed) showNativeFallback()
    }

    private fun showNativeFallback() {
        val ringing = CallRegistry.incomingNow
        if (ringing != null) {
            setContentView(incomingView(ringing))
            return
        }
        val pad = (FALLBACK_PADDING_DP * resources.displayMetrics.density).toInt()
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad)
            }
        column.addView(
            TextView(this).apply {
                text = getString(R.string.fallback_stopped)
                textSize = FALLBACK_TEXT_SP
                gravity = Gravity.CENTER
            },
        )
        if (CallRegistry.hasLiveCall()) {
            column.addView(
                Button(this).apply {
                    text = getString(R.string.fallback_end_call)
                    setOnClickListener { CallRegistry.endAll() }
                },
            )
        }
        setContentView(column)
    }

    private fun incomingView(call: IncomingCall): View =
        NativeIncoming.build(
            this,
            call,
            locked = false,
            onAnswer = { CallRegistry.answerRinging(call.callId) },
            onDecline = { CallRegistry.declineRinging(call.callId) },
        )

    private companion object {
        val RING_KEYS = setOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        const val FALLBACK_PADDING_DP = 24
        const val FALLBACK_TEXT_SP = 18f

        /** Process-wide, so an intent redelivered to a new activity instance still answers once. */
        val answerOnce = AnswerOnce()
    }
}
