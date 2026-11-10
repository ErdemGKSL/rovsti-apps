package dev.rovsti.phone

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import dev.rovsti.design.transition.Tab
import dev.rovsti.platform.telephony.AnswerOnce
import dev.rovsti.platform.telephony.CallNotifications
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.platform.telephony.IncomingCall
import dev.rovsti.platform.wa.WaIncoming
import dev.rovsti.shell.NavSpec
import dev.rovsti.shell.RovstiShellActivity
import dev.rovsti.shell.shellContainer
import kotlinx.coroutines.launch

/**
 * The one activity of Rovsti Phone (FQCN kept, singleTop). Edge to edge, back order, refresh rate and permission refresh
 * come from [RovstiShellActivity]; this class adds what only a phone app needs:
 *
 * - **Calls over the lock screen (AD-1).** `setShowWhenLocked` and `setTurnScreenOn` are dynamic, never in the manifest:
 *   they hold only while `CallRegistry` reports a live call, and they stay a few seconds after the last call goes so the
 *   "Call ended" hold is still seen.
 * - **Notification intents.** Answer is honoured exactly once, before any UI exists (AD-2, story 5.3); Message opens
 *   Incoming; a WhatsApp Decline sends the task straight back.
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
    private val lockScreen = LockScreenGate(::setLockScreenFlags, main::postDelayed, main::removeCallbacks)
    private var uiFailed = false

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
        setLockScreenFlags(CallRegistry.hasLiveCall())
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
        armWatchdog()
    }

    /** Watches the first frame while the Incoming screen is the page and its call rings. */
    private fun armWatchdog() {
        val ringing = CallRegistry.incomingNow ?: return
        if (!uiFailed && (navigator.current as? PhoneRoute.Incoming)?.callId == ringing.callId) {
            watchdog.arm(ringing.callId)
        }
    }

    override fun onIncomingFrame(callId: Int) = watchdog.frame(callId)

    override fun onStop() {
        watchdog.disarm()
        // Nothing left to show over the lock screen: put the flags back at once.
        if (!CallRegistry.hasLiveCall()) setLockScreenFlags(false)
        // The preferences and notes are written a moment after the last edit; the process may be killed after this.
        container.scope.launch { container.store.flush() }
        super.onStop()
    }

    override fun onDestroy() {
        lockScreen.cancel()
        watchdog.disarm()
        if (CallRegistry.surface === this) CallRegistry.surface = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        setLockScreenFlags(CallRegistry.hasLiveCall())
        super.onNewIntent(intent)
        armWatchdog()
    }

    @Composable
    override fun Content() {
        PhoneApp(container = container, navigator = navigator)
    }

    // ---- Intents -----------------------------------------------------------------------------------------------

    override fun routeFor(extras: Bundle?): Pair<Tab, List<PhoneRoute>>? =
        phoneLinkFor(null, null, extras, CallRegistry.hasLiveCall())?.route

    override fun routeFor(intent: Intent): Pair<Tab, List<PhoneRoute>>? {
        handleWaCallIntent(intent)
        val link = phoneLinkFor(intent.action, intent.dataString, intent.extras, CallRegistry.hasLiveCall())
        if (link != null) apply(link)
        // A link is used once: a rotation or a restart must not answer, reply or open the same call again.
        intent.consumePhoneExtras()
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
     * payload to the container (E5.8 acts on it once). Decline sends the task straight back so it does not open the UI,
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
        const val FALLBACK_PADDING_DP = 24
        const val FALLBACK_TEXT_SP = 18f

        /** Process-wide, so an intent redelivered to a new activity instance still answers once. */
        val answerOnce = AnswerOnce()
    }
}
