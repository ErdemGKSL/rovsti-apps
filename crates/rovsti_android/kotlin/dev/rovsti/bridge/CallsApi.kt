package dev.rovsti.bridge

/**
 * What `RovstiBridge` needs from an app's call owner (Rovsti Phone's
 * `CallRegistry`). Every method is non-blocking and safe to call from any
 * thread; ids that are unknown or stale return false and change nothing.
 */
interface CallsApi {
    /** The last snapshot JSON the owner built (AD-4); never queries Telecom. */
    fun snapshot(): String
    fun answer(callId: Int): Boolean
    fun reject(callId: Int): Boolean
    fun disconnect(callId: Int): Boolean
    fun hold(callId: Int, on: Boolean): Boolean
    fun mute(on: Boolean): Boolean
    fun route(name: String): Boolean

    /** Routes call audio to the endpoint with this identifier (AD-23); false for an unknown id. */
    fun routeEndpoint(id: String): Boolean = false

    /** Milliseconds from the last endpoint request to the snapshot that selected it; -1 when none yet (spike S3). */
    fun routeLatencyMs(): Long = -1

    /** The registry's recent events for the Diagnostics report (no numbers, no names). */
    fun eventDump(): List<String> = emptyList()
    /** Starts a tone; it plays until [dtmfStop] (or a safety timeout). */
    fun dtmf(digit: Char): Boolean
    fun dtmfStop(): Boolean
    fun answerReplacing(callId: Int): Boolean

    /** Swaps the active and the held call (`swapConference()` or `unhold()`); false when there is nothing to swap. */
    fun swap(): Boolean = false

    /** Merges the two calls into a conference; false when nothing can be merged. */
    fun merge(): Boolean = false

    /** Splits a conference participant into a private call; false without the split capability. */
    fun split(childId: Int): Boolean = false

    /** Answers a ringing call and lets the network hold the current one; false when the current call cannot be held. */
    fun answerHolding(callId: Int): Boolean = false

    /** Ends the held call, then answers [callId] (the relabelled "End held call and answer"). */
    fun answerEndingHeld(callId: Int): Boolean = false

    /** Ends every non-ringing top-level call. */
    fun endAll(): Boolean = false

    /** Answers a call waiting in `STATE_SELECT_PHONE_ACCOUNT` with the account [key] (story 16.3). */
    fun selectAccount(callId: Int, key: String): Boolean = false

    /** What the registry measured for the S4 probe. */
    fun multiCallFacts(): MultiCallFacts = MultiCallFacts()

    /** Rust drew the first frame of the Incoming Call screen for [callId] (ring-latency log, story 5.6). */
    fun incomingFrame(callId: Int): Boolean = false

    /**
     * Declines a ringing call with [text] (story 18.1): [RejectPolicy] codes, 0 stale, 1 sent, 2 opened
     * the SMS app, 3 rejected only. The call is rejected exactly once on every path except 0.
     */
    fun rejectWithMessage(callId: Int, text: String): Int = RejectPolicy.STALE

    /** Declines and opens the SMS app on the caller with an empty body ("Write your own..."). */
    fun rejectAndCompose(callId: Int): Boolean = false

    /** `TelecomManager.silenceRinger()` for the ringing call (story 18.4); false when refused. */
    fun silenceRinger(): Boolean = false

    /** Diagnostics: how many calls the block guard rejected since the process started (spike S6). */
    fun blockGuardHits(): Int = 0
    fun blockGuardOn(): Boolean = false

    /** Diagnostics: how many times `InCallService.onSilenceRinger` fired (spike S8). */
    fun silenceCallbacks(): Int = 0

    /**
     * Spike S7: waits up to [windowMs] for a ringing call and declines it with "test" through the Telecom
     * path, whatever `Caps.REJECT_PATH` says. Blocking; runs off the UI thread.
     */
    fun probeRejectText(windowMs: Long): Epic17Probes.Reject = Epic17Probes.Reject(false, false, 0, false)
}
