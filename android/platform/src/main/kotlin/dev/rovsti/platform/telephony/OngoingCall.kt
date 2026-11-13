package dev.rovsti.platform.telephony

// Ported from apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/OngoingCall.kt (ARCH 7.3).

import dev.rovsti.platform.wire.CallInfo
import dev.rovsti.platform.wire.Focus

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
    /** Live top-level calls (a conference counts once); the notification says "2 calls" beyond one. */
    val callCount: Int = 1,
    /** The contact behind the call: its photo is the notification's avatar (F39). */
    val contactId: Long? = null,
) {
    /** Notifications are re-posted only when this changes. */
    val key: String get() = "$callId|$title|$connectTimeMs|$holding|$callCount|$contactId"

    companion object {
        /** [format] writes an unnamed caller's number for reading (F39). */
        fun of(
            calls: List<CallInfo>,
            focus: Focus,
            format: (String) -> String = { it },
        ): OngoingCall? {
            val call = calls.firstOrNull { it.id == focus.foreground } ?: return null
            if (call.state != "active" && call.state != "dialing" && call.state != "holding") return null
            val name = call.displayName?.trim()?.takeIf { it.isNotEmpty() }
            val title =
                name
                    ?: call.numberRaw
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { format(it).trim().ifEmpty { it } }
                    ?: "Unknown"
            val count =
                calls.count {
                    it.parentId == null &&
                        (it.state == "active" || it.state == "dialing" || it.state == "holding")
                }
            return OngoingCall(
                call.id,
                title,
                call.connectTimeMs,
                call.state == "holding",
                count.coerceAtLeast(1),
                call.contactId.takeIf { name != null },
            )
        }
    }
}
