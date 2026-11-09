package dev.rovsti.platform.telephony

/**
 * Declining with a text (story 18.1, AD-28). No SMS permission is ever declared: the text either goes out through
 * Telecom (`Call.reject(true, text)`, after spike S7 passes) or the system SMS app is opened with the text typed in
 * (`HANDOFF`, the shipped default). Pure, so a JVM test covers it. `FlipRule` (story 18.4) goes with the call
 * surface in E3.5.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/RejectPolicy.kt
 */
object RejectPolicy {
    const val MAX_CHARS = 160

    /** Result codes the Rust side reads (`calls::reject_with_message`). */
    const val STALE = 0
    const val SENT = 1
    const val OPENED = 2
    const val REJECTED_ONLY = 3

    enum class Action { TELECOM, HANDOFF, REJECT_ONLY }

    data class Decision(
        val code: Int,
        val action: Action?,
    )

    /**
     * @param telecomPath `Caps.REJECT_PATH == TELECOM`
     * @param ringing the call exists and still rings
     * @param respondViaText the call's `CAPABILITY_RESPOND_VIA_TEXT`
     * @param hasNumber the caller has a number to text
     * @param canCompose an app answers `ACTION_SENDTO smsto:`
     */
    fun decide(
        text: String,
        ringing: Boolean,
        telecomPath: Boolean,
        respondViaText: Boolean,
        hasNumber: Boolean,
        canCompose: Boolean,
    ): Decision {
        if (!ringing) return Decision(STALE, null)
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > MAX_CHARS) return Decision(STALE, null)
        if (telecomPath && respondViaText && hasNumber) return Decision(SENT, Action.TELECOM)
        if (hasNumber && canCompose) return Decision(OPENED, Action.HANDOFF)
        return Decision(REJECTED_ONLY, Action.REJECT_ONLY)
    }
}
