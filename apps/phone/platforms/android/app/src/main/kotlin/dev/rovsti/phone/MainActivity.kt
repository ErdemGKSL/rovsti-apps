package dev.rovsti.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.gpui.android.GpuiActivity
import dev.rovsti.bridge.RovstiBridge

class MainActivity : GpuiActivity(), CallRegistry.Surface {
    private var debugKill: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())
    private val clearLockScreenFlags = Runnable { setLockScreenFlags(false) }
    private var curtain: View? = null
    private var gpuiFailed = false
    private val dropCurtain = Runnable { removeCurtain() }

    override fun nativeLibraryName() = "rovsti_phone"
    override fun insetHandling() = InsetHandling.APPLICATION

    override fun onCreate(state: Bundle?) {
        IncomingPerf.mark(IncomingTimeline.ACTIVITY_CREATED)
        // A dial intent becomes a view of the same tel: URI before the host sees it (AD-12).
        intent = DialIntents.rewrite(intent)
        // Answer works before GPUI exists (AD-2); the lock-screen flags must be right before
        // the first frame (AD-1).
        CallRegistry.surface = this
        setLockScreenFlags(CallRegistry.hasLiveCall())
        handleCallIntent(intent)
        super.onCreate(state)
        // GpuiActivity loaded the library; now resolve the JNI bridge (AD-8).
        RovstiBridge.attachNative()
        registerDebugKill()
        showCurtainIfRinging()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(DialIntents.rewrite(intent))
        setLockScreenFlags(CallRegistry.hasLiveCall())
        handleCallIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        IncomingPerf.mark("activity_resumed")
        // The only `permissions_changed` emitter (AD-5); Rust re-pulls on it.
        RovstiBridge.emitPermissionsChanged()
        CallRegistry.refreshKeyguard()
    }

    override fun onStop() {
        // Nothing left to show over the lock screen: put the flags back at once.
        if (!CallRegistry.hasLiveCall()) setLockScreenFlags(false)
        super.onStop()
    }

    override fun onDestroy() {
        debugKill?.let { unregisterReceiver(it) }
        debugKill = null
        main.removeCallbacks(clearLockScreenFlags)
        main.removeCallbacks(dropCurtain)
        if (CallRegistry.surface === this) CallRegistry.surface = null
        super.onDestroy()
    }

    // ---- Calls over the lock screen (AD-1) -------------------------------------

    /**
     * `setShowWhenLocked` and `setTurnScreenOn` are dynamic, never in the manifest: they hold
     * only while a call is live. After the last call goes they stay a few seconds more so the
     * "Call ended" / "Missed call" hold is still seen, then the lock screen returns.
     */
    override fun onLiveCallChanged(live: Boolean) {
        main.removeCallbacks(clearLockScreenFlags)
        if (live) {
            setLockScreenFlags(true)
        } else {
            main.postDelayed(clearLockScreenFlags, LOCK_SCREEN_LINGER_MS)
            removeCurtain()
        }
    }

    override fun onIncomingFrame(callId: Int) = removeCurtain()

    private fun setLockScreenFlags(on: Boolean) {
        setShowWhenLocked(on)
        setTurnScreenOn(on)
    }

    /**
     * Notification actions arrive as intents. Answer carries `answer_call_id`: it is honoured
     * exactly once and a stale id does nothing (story 5.3). Body taps and the full-screen intent
     * need no handling here: raising the activity is enough, Rust shows the Incoming Call screen.
     */
    private fun handleCallIntent(intent: Intent) {
        val id = intent.getIntExtra(CallNotifications.EXTRA_ANSWER_CALL_ID, -1)
        if (id < 0) return
        val token = intent.getLongExtra(CallNotifications.EXTRA_ANSWER_TOKEN, 0L)
        intent.removeExtra(CallNotifications.EXTRA_ANSWER_CALL_ID)
        intent.removeExtra(CallNotifications.EXTRA_ANSWER_TOKEN)
        if (token != CallNotifications.answerToken) return
        if (answerOnce.accept(id, CallRegistry.isRinging(id))) CallRegistry.answerRinging(id)
    }

    // ---- Back, errors ------------------------------------------------------------

    /** Back that GPUI left alone: send the task to the background, keep the session warm (AD-1). */
    override fun onBackUnhandled() {
        // Back does nothing while a call rings: it must not look like a decline.
        if (CallRegistry.isRinging()) return
        moveTaskToBack(true)
    }

    /**
     * The GPUI view died (AD-17). The call lives in [CallRegistry] and the call notifications,
     * neither of which depends on GPUI, so only show a native message. A ringing call gets the
     * native Incoming Call view so it can still be answered here.
     */
    override fun onGpuiError(error: RuntimeException) {
        Log.e("Rovsti", "GPUI stopped; calls are unaffected", error)
        removeCurtain()
        gpuiFailed = true
        showNativeFallback()
    }

    /** The native view follows the call: Answer and Decline while it rings, then the message. */
    override fun onIncomingChanged(call: IncomingCall?) {
        if (gpuiFailed) showNativeFallback()
    }

    private fun showNativeFallback() {
        val ringing = CallRegistry.incomingNow
        if (ringing != null) {
            setContentView(incomingView(ringing))
            return
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "Rovsti Phone's screen stopped.\nYour call is not affected."
            textSize = 18f
            gravity = Gravity.CENTER
        })
        if (CallRegistry.hasLiveCall()) {
            column.addView(Button(this).apply {
                text = "End call"
                setOnClickListener { CallRegistry.endAll() }
            })
        }
        setContentView(column)
    }

    private fun incomingView(call: IncomingCall): View = NativeIncoming.build(
        this,
        call,
        locked = false,
        onAnswer = { CallRegistry.answerRinging(call.callId) },
        onDecline = { CallRegistry.declineRinging(call.callId) },
    )

    // ---- The native curtain (off by default; see NativeIncoming.CURTAIN_ENABLED) ----

    private fun showCurtainIfRinging() {
        if (!NativeIncoming.CURTAIN_ENABLED || curtain != null) return
        val call = CallRegistry.incomingNow ?: return
        val view = incomingView(call)
        curtain = view
        addContentView(view, ViewGroup.LayoutParams(-1, -1))
        // Never leave it up if GPUI cannot report a frame.
        main.postDelayed(dropCurtain, CURTAIN_SAFETY_MS)
    }

    private fun removeCurtain() {
        val view = curtain ?: return
        curtain = null
        main.removeCallbacks(dropCurtain)
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** Debug builds only: `adb shell am broadcast -a dev.rovsti.phone.DEBUG_KILL_GPUI`. */
    private fun registerDebugKill() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = failGpuiForDebug()
        }
        debugKill = receiver
        registerReceiver(receiver, IntentFilter(DEBUG_KILL_GPUI), Context.RECEIVER_EXPORTED)
    }

    private companion object {
        const val DEBUG_KILL_GPUI = "dev.rovsti.phone.DEBUG_KILL_GPUI"
        const val LOCK_SCREEN_LINGER_MS = 3_500L
        const val CURTAIN_SAFETY_MS = 8_000L

        /** Process-wide, so an intent redelivered to a new activity instance still answers once. */
        val answerOnce = AnswerOnce()
    }
}
