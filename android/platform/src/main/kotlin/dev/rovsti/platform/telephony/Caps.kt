package dev.rovsti.platform.telephony

// Ported from apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/Caps.kt (ARCH 7.3).

/**
 * The safe defaults of every spike decision (AD-36). Flipping a spike's outcome is a one-line
 * change here; nothing else in the code base hard-codes these choices.
 */
object Caps {
    /** S4: how Decline-with-message leaves the app. `HANDOFF` = system SMS app (safe). */
    const val REJECT_PATH = "HANDOFF"

    /** S6: block through the app's own guard on the incoming path, not only the system list. */
    const val BLOCK_GUARD = true

    /** S9: the voicemail receiver ships disabled. */
    const val VOICEMAIL_RECEIVER = false

    /** S3: force the legacy `getCallAudioState()` path even when endpoints work. */
    const val LEGACY_AUDIO_FORCED = false

    /** S11: write the contact ringtone column. */
    const val CONTACT_RINGTONE = false
}
