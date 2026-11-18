package dev.rosty.phone.wa

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.rosty.core.log.RostyLog
import dev.rosty.phone.NativeIncoming
import dev.rosty.platform.wa.WaAnswerTiming
import dev.rosty.platform.wa.WaIncoming
import dev.rosty.platform.R as PlatformR

/**
 * A WhatsApp call ringing while Phone's vault is locked (round 7b; `plans/wa-vault.md` 15). The ring's full-screen
 * intent, its tap and its Answer open this over the lock screen (`setShowWhenLocked`, `setTurnScreenOn`, dynamic as in
 * `MainActivity`). It shows only the caller's title the ring notice shows (a device contact, else what the sealed push
 * said, else "WhatsApp call") with Answer and Decline: no app page, no message, nothing from the vault.
 *
 * - **Answer** stops the ring and asks Android to dismiss the keyguard (`requestDismissKeyguard`: the person's
 *   fingerprint, face or PIN on the lock screen; the vault's Keystore keys need an unlocked phone). Then the call is
 *   handed to `MainActivity` as an Answer from the notification: the app opens its vault inline (an unlocked sibling with
 *   no prompt, else the biometric prompt over the page) and answers once it is open, if the caller still waits.
 *   [WaAnswerTiming] logs each step in milliseconds since the tap.
 * - **Decline** stops the ring here only (no gateway request while locked; the caller hears it ring until the
 *   gateway's 60 s timeout, as before).
 * - The page closes when the ring is over (the call ended, was answered elsewhere, timed out).
 */
class WaLockedCallActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var callId: String? = null
    private var answering = false

    private val overListener: (String) -> Unit = { id ->
        if (id == callId && !answering) main.post { finish() }
    }

    private val timeout = Runnable { if (!answering) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (!take(intent, fresh = savedInstanceState == null)) {
            finish()
            return
        }
        WaIncoming.addOverListener(overListener)
        main.postDelayed(timeout, WaIncoming.RING_TIMEOUT_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!take(intent, fresh = true)) finish()
    }

    override fun onDestroy() {
        WaIncoming.removeOverListener(overListener)
        main.removeCallbacks(timeout)
        super.onDestroy()
    }

    /** Shows the call of [intent]; false when it names none or its ring is already over. */
    private fun take(
        intent: Intent?,
        fresh: Boolean,
    ): Boolean {
        val id = intent?.getStringExtra(WaIncoming.EXTRA_CALL_ID)?.takeIf { it.isNotEmpty() } ?: return false
        if (WaIncoming.isOver(id) && !answering) return false
        callId = id
        val title = intent.getStringExtra(WaIncoming.EXTRA_TITLE) ?: getString(PlatformR.string.wa_call_title)
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        setContentView(
            NativeIncoming.build(
                this,
                title = title,
                subtitle = getString(PlatformR.string.wa_call_title).takeIf { it != title },
                locked = locked,
                onAnswer = ::answer,
                onDecline = ::decline,
            ),
        )
        // Answer tapped on the notice itself: go on at once (once; a recreation does not answer again).
        val answerTapped = intent.getStringExtra(WaIncoming.EXTRA_ACTION) == WaIncoming.ANSWER
        intent.removeExtra(WaIncoming.EXTRA_ACTION)
        if (answerTapped && fresh) answer()
        return true
    }

    private fun answer() {
        val id = callId ?: return
        if (answering) return
        answering = true
        WaAnswerTiming.start(id)
        // The ring stops now; the over listener ignores it while answering.
        WaIncoming.cancel(this, id)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            handOver(id)
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = handOver(id)

                override fun onDismissCancelled() = notUnlocked(id, "screen unlock cancelled")

                override fun onDismissError() = notUnlocked(id, "screen unlock failed")
            },
        )
    }

    /** The screen is unlocked: `MainActivity` opens the vault and answers (an Answer from the notification). */
    private fun handOver(id: String) {
        WaAnswerTiming.mark(id, "screen unlocked")
        try {
            startActivity(WaIncoming.answerIntent(this, id))
        } catch (e: RuntimeException) {
            RostyLog.w(TAG, "WA call: could not hand the answer over", e)
            WaAnswerTiming.finish(id, "hand-over failed")
        }
        finish()
    }

    /** The person backed out of the screen unlock: the page stays, Answer can be tapped again while the caller waits. */
    private fun notUnlocked(
        id: String,
        step: String,
    ) {
        WaAnswerTiming.finish(id, step)
        answering = false
    }

    private fun decline() {
        callId?.let { WaIncoming.cancel(this, it) }
        finish()
    }

    private companion object {
        const val TAG = "phone"
    }
}
