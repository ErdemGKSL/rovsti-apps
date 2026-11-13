package dev.rovsti.platform.telephony

// Ported from apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/IncomingCall.kt (ARCH 7.3).

import dev.rovsti.platform.wire.CallInfo

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
    /** The heads-up offers "Message" (quick replies exist and the caller has a number; story 18.7). */
    val canMessage: Boolean = false,
    /** The contact behind the caller: its photo is the notification's avatar (F39). */
    val contactId: Long? = null,
) {
    /** The notification is re-posted only when this changes (the name resolves a moment after the call arrives). */
    val key: String get() = "$callId|$title|$subtitle|$canMessage|$contactId"

    companion object {
        /**
         * [format] writes a number for reading (F39): an unnamed caller is `+90 532 123 45 67`, not the dialled digits.
         * The name is the registry's, resolved by the app's caller-ID (the same one Recents uses).
         */
        fun of(
            calls: List<CallInfo>,
            hasReplies: Boolean = false,
            format: (String) -> String = { it },
        ): IncomingCall? {
            val call = calls.firstOrNull { it.state == "ringing" } ?: return null
            val name = call.displayName?.trim()?.takeIf { it.isNotEmpty() }
            val number = call.numberRaw.trim().takeIf { it.isNotEmpty() }
            val text = number?.let { format(it).trim().ifEmpty { it } }
            return IncomingCall(
                call.id,
                name ?: text ?: "Unknown",
                if (name != null) text else null,
                hasReplies && number != null,
                call.contactId.takeIf { name != null },
            )
        }

        /** True when [calls] holds a ringing call with this id. */
        fun isRinging(
            calls: List<CallInfo>,
            callId: Int,
        ): Boolean = calls.any { it.id == callId && it.state == "ringing" }
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
    fun accept(
        callId: Int,
        ringing: Boolean,
    ): Boolean {
        if (callId < 0 || callId == last || !ringing) return false
        last = callId
        return true
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}
