package dev.rovsti.phone

import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.platform.wa.WaIncoming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When the activity may show over the lock screen and turn the screen on (AD-1): only while a call is live, and for
 * [lingerMs] after the last one so the "Call ended" summary is still seen. The flags are dynamic, never in the manifest.
 * Time and the flags are seams, so a JVM test drives the policy without an activity.
 *
 * Three things hold the flags on:
 * - a live cellular call (`CallRegistry`, [onLiveCallChanged]);
 * - a live WhatsApp call ([onWaCalls], from `WaCalls.calls`: WhatsApp calls are not in the registry);
 * - the full-screen intent of a ringing WhatsApp call ([holdForWaIntent]). On a cold start the activity exists before
 *   the WhatsApp call list has the call, so the intent holds the flags until the list has had the call (it then takes
 *   over), the list says that call ended, or [waHoldMs] passed. The hold never outlives a call that never shows up.
 */
class LockScreenGate(
    private val setFlags: (Boolean) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val lingerMs: Long = LINGER_MS,
    private val isKeyguardLocked: () -> Boolean = { false },
    private val waHoldMs: Long = WaIncoming.RING_TIMEOUT_MS,
) {
    private val lockedState = MutableStateFlow(isKeyguardLocked())

    /** The keyguard is up (`KeyguardManager.isKeyguardLocked`): the Incoming screen shows slide-to-answer. */
    val locked: StateFlow<Boolean> = lockedState.asStateFlow()

    private var telecomLive = false
    private var waLive = false
    private var heldWaCall: String? = null

    /** Whether the flags are wanted right now (before the linger). */
    val wanted: Boolean get() = telecomLive || waLive || heldWaCall != null

    /** Reads the keyguard again (resume, a new intent, a ring starting with the screen off). */
    fun refreshLocked() {
        lockedState.value = isKeyguardLocked()
    }

    private val clear = Runnable { setFlags(false) }

    private val dropHold =
        Runnable {
            heldWaCall = null
            apply()
        }

    /** The registry's live-call flag changed. */
    fun onLiveCallChanged(live: Boolean) {
        telecomLive = live
        apply()
    }

    /**
     * The WhatsApp call list changed: [live] says whether a call there holds the screen ([holdsScreen]), [ended] are
     * the ids of the calls there that are over. A live call takes over from the intent hold; an ended held call drops it.
     */
    fun onWaCalls(
        live: Boolean,
        ended: Set<String>,
    ) {
        waLive = live
        val held = heldWaCall
        if (held != null && (live || held in ended)) {
            removeCallbacks(dropHold)
            heldWaCall = null
        }
        apply()
    }

    /** A full-screen intent (or a tap) for the ringing WhatsApp call [callId] opened the activity. */
    fun holdForWaIntent(callId: String) {
        removeCallbacks(dropHold)
        heldWaCall = callId
        postDelayed(dropHold, waHoldMs)
        apply()
    }

    private fun apply() {
        removeCallbacks(clear)
        refreshLocked()
        if (wanted) setFlags(true) else postDelayed(clear, lingerMs)
    }

    /** Drops the pending clear and hold (the activity is going away). */
    fun cancel() {
        removeCallbacks(clear)
        removeCallbacks(dropHold)
    }

    companion object {
        const val LINGER_MS = 3_500L

        /** A ring older than this is a stale entry (the gateway gives up after [WaIncoming.RING_TIMEOUT_MS]). */
        private const val STALE_RING_MS = WaIncoming.RING_TIMEOUT_MS + 15_000L

        /**
         * Pure: whether a WhatsApp call keeps the app over the lock screen: it is ringing (in or out, not stale),
         * connecting or active.
         */
        fun holdsScreen(
            call: CallSummary,
            nowMs: Long,
        ): Boolean =
            when (call.phase) {
                is CallPhase.Ended -> false
                CallPhase.Ringing -> nowMs - call.startedMs < STALE_RING_MS
                CallPhase.Connecting, CallPhase.Active -> true
            }
    }
}
