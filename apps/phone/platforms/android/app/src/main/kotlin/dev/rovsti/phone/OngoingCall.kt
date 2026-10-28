package dev.rovsti.phone

import dev.rovsti.bridge.CallInfo
import dev.rovsti.bridge.Focus

/**
 * What the ongoing-call notification shows. Pure, so a JVM test covers it.
 * Ringing calls belong to the incoming notification (Epic 5); a call that has
 * been disconnected no longer keeps the foreground service.
 */
data class OngoingCall(
    val callId: Int,
    val title: String,
    val connectTimeMs: Long,
    val holding: Boolean,
) {
    /** Notifications are re-posted only when this changes. */
    val key: String get() = "$callId|$title|$connectTimeMs|$holding"

    companion object {
        fun of(calls: List<CallInfo>, focus: Focus): OngoingCall? {
            val call = calls.firstOrNull { it.id == focus.foreground } ?: return null
            if (call.state != "active" && call.state != "dialing" && call.state != "holding") return null
            val title = call.displayName?.takeIf { it.isNotBlank() }
                ?: call.numberRaw.takeIf { it.isNotBlank() }
                ?: "Unknown"
            return OngoingCall(call.id, title, call.connectTimeMs, call.state == "holding")
        }
    }
}
