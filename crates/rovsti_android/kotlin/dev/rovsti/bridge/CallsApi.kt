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
    /** Starts a tone; it plays until [dtmfStop] (or a safety timeout). */
    fun dtmf(digit: Char): Boolean
    fun dtmfStop(): Boolean
    fun answerReplacing(callId: Int): Boolean

    /** Rust drew the first frame of the Incoming Call screen for [callId] (ring-latency log, story 5.6). */
    fun incomingFrame(callId: Int): Boolean = false
}
