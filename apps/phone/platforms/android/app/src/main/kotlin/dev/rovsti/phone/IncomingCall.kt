package dev.rovsti.phone

import dev.rovsti.bridge.CallInfo

/**
 * What the incoming-call notification shows. Pure, so a JVM test covers it.
 * The first ringing call wins; a second ringing call (a waiting call) is the
 * business of Epic 9.
 */
data class IncomingCall(
    val callId: Int,
    /** Contact name, else the number as dialled, else "Unknown". */
    val title: String,
    /** The number line under a contact's name; null when the title already is the number. */
    val subtitle: String?,
) {
    /** The notification is re-posted only when this changes (the name resolves a moment after the call arrives). */
    val key: String get() = "$callId|$title|$subtitle"

    companion object {
        fun of(calls: List<CallInfo>): IncomingCall? {
            val call = calls.firstOrNull { it.state == "ringing" } ?: return null
            val name = call.displayName?.trim()?.takeIf { it.isNotEmpty() }
            val number = call.numberRaw.trim().takeIf { it.isNotEmpty() }
            return IncomingCall(call.id, name ?: number ?: "Unknown", if (name != null) number else null)
        }

        /** True when [calls] holds a ringing call with this id. */
        fun isRinging(calls: List<CallInfo>, callId: Int): Boolean =
            calls.any { it.id == callId && it.state == "ringing" }
    }
}

/**
 * Honours the `answer_call_id` extra of the notification's Answer exactly once
 * (story 5.3). A stale id (the call ended, was declined, or the intent was
 * delivered again after a rotation) answers nothing.
 */
class AnswerOnce {
    private var last = NONE

    /** Whether [callId] should be answered now, given which calls are ringing. */
    fun accept(callId: Int, ringing: Boolean): Boolean {
        if (callId < 0 || callId == last || !ringing) return false
        last = callId
        return true
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}
