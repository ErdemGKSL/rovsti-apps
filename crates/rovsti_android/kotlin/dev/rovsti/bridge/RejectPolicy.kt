package dev.rovsti.bridge

/**
 * Declining with a text (story 18.1, AD-28). No SMS permission is ever declared: the text either goes
 * out through Telecom (`Call.reject(true, text)`, after spike S7 passes) or the system SMS app is
 * opened with the text typed in (`HANDOFF`, the shipped default). Pure, so a JVM test covers it.
 */
object RejectPolicy {
    const val MAX_CHARS = 160

    /** Result codes the Rust side reads (`calls::reject_with_message`). */
    const val STALE = 0
    const val SENT = 1
    const val OPENED = 2
    const val REJECTED_ONLY = 3

    enum class Action { TELECOM, HANDOFF, REJECT_ONLY }

    data class Decision(val code: Int, val action: Action?)

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

/**
 * The flip-to-silence trigger (story 18.4): z below -7.0 m/s2 on two consecutive samples, after at
 * least one sample at or above -3.0, so a phone already face down at ring start never silences.
 */
class FlipRule {
    private var armed = false
    private var down = 0

    /** Feeds one z sample; true exactly when the phone has just been turned face down. */
    fun onZ(z: Float): Boolean {
        if (z >= ARM) armed = true
        if (!armed) return false
        down = if (z < FACE_DOWN) down + 1 else 0
        if (down >= 2) {
            armed = false
            down = 0
            return true
        }
        return false
    }

    companion object {
        const val FACE_DOWN = -7.0f
        const val ARM = -3.0f
    }
}
