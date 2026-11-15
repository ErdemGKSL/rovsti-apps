package dev.rosty.messages.notify

import dev.rosty.core.model.messages.MessagesPrefs

/**
 * What the user allowed, as the notifier needs it (GPUI `notifications::Switches` plus the mutes). A cold process has
 * not read `messages-prefs.json` and uses the defaults (everything on), like the GPUI app's Kotlin receivers did; once
 * the app has loaded the preferences the announcer keeps [current] in step.
 */
data class NotifyPolicy(
    /** The master switch of message notifications. */
    val enabled: Boolean = true,
    /** Offer "Copy code" on a passcode message. */
    val otpCopy: Boolean = true,
    /** The muted conversation tags. */
    val muted: Set<String> = emptySet(),
    /** When a timed mute ends (epoch ms); a muted tag without an entry stays muted. */
    val muteUntil: Map<String, Long> = emptyMap(),
) {
    fun isMuted(
        tag: String,
        nowMs: Long,
    ): Boolean = tag in muted && (muteUntil[tag]?.let { it > nowMs } ?: true)

    companion object {
        @Volatile var current: NotifyPolicy = NotifyPolicy()

        fun of(prefs: MessagesPrefs): NotifyPolicy =
            NotifyPolicy(prefs.notifications, prefs.otpCopy, prefs.muted.toSet(), prefs.muteUntil)
    }
}
