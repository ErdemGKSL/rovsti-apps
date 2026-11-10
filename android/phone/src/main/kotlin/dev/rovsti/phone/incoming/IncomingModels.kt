package dev.rovsti.phone.incoming

import androidx.compose.runtime.Immutable
import dev.rovsti.core.model.chat.ReminderSlot
import dev.rovsti.core.model.chat.ReminderSlotOption
import kotlinx.collections.immutable.ImmutableList

/** The SIM line of the ringing call, only drawn on a phone with two or more SIMs. */
@Immutable
data class SimChip(
    val label: String,
    val colorArgb: Int,
)

/** Who is calling on the Incoming screen. [title] is the name, else the number, else the "Unknown" word. */
@Immutable
data class IncomingCaller(
    val callId: Int,
    val title: String,
    /** The number under a contact's name; null when the title already is the number. */
    val subtitle: String?,
    /** Colours the avatar and the aura; the contact id, else the title. */
    val avatarKey: String,
    val contactId: Long?,
    val sim: SimChip?,
    /** A WhatsApp call (AD-WA5): the screen says "WhatsApp audio" and shows the profile picture of [photoJid]. */
    val whatsapp: Boolean = false,
    val photoJid: String? = null,
)

/** A second caller while a call is live: the card with four ways to handle it. */
@Immutable
data class WaitingUi(
    val callId: Int,
    val title: String,
    val subtitle: String?,
    val avatarKey: String,
    /** A call is already held: "Hold & Answer" becomes "End held call & Answer" (the relabelled button). */
    val relabelled: Boolean,
    /** "Hold & Answer" works: the live call can be held and no call is held yet. */
    val canHold: Boolean,
    val canMessage: Boolean,
    /** The waiting call is a WhatsApp call: End & Answer and Decline only. */
    val whatsapp: Boolean = false,
)

@Immutable
data class IncomingUi(
    val caller: IncomingCaller? = null,
    val waiting: WaitingUi? = null,
    /** The keyguard is up: the screen shows over the lock screen. */
    val locked: Boolean = false,
    /** The ringer was silenced (volume key or the Silence button): the screen rests. */
    val silenced: Boolean = false,
    val canMessage: Boolean = false,
)

/** What the user can do on the Incoming screen and the waiting-call card. */
sealed interface IncomingAction {
    data object Answer : IncomingAction

    data object Decline : IncomingAction

    /** The Message circle: asks for the reply choices of the ringing call. */
    data object OpenReplies : IncomingAction

    data class Reply(
        val callId: Int,
        val text: String,
    ) : IncomingAction

    /** "Custom...": declines and opens the SMS app on the caller with an empty body. */
    data class ReplyCustom(
        val callId: Int,
    ) : IncomingAction

    /** The Remind me circle: asks for the time slots. */
    data object OpenReminders : IncomingAction

    data class RemindAt(
        val slot: ReminderSlot,
    ) : IncomingAction

    data object Silence : IncomingAction

    /** The first frame of the screen was drawn (the ring-latency log, story 5.6). */
    data class FirstFrame(
        val callId: Int,
    ) : IncomingAction

    // The waiting-call card.
    data object EndAndAnswer : IncomingAction

    data object HoldAndAnswer : IncomingAction

    data object DeclineWaiting : IncomingAction

    data object OpenWaitingReplies : IncomingAction
}

/** Things the screen does once, after a decision. */
sealed interface IncomingEffect {
    /** The choices of the "Decline with message" sheet, for [callId]. */
    data class ShowReplies(
        val callId: Int,
        val choices: ImmutableList<String>,
        val maxChars: Int,
    ) : IncomingEffect

    data class ShowReminders(
        val slots: ImmutableList<ReminderSlotOption>,
    ) : IncomingEffect

    data class Notice(
        val notice: IncomingNotice,
        val text: String? = null,
    ) : IncomingEffect

    /** The ringing call is gone: [toCall] when it was answered (open In-Call), else the screen closes. */
    data class Leave(
        val toCall: Boolean,
    ) : IncomingEffect

    data object Confirm : IncomingEffect

    data object Reject : IncomingEffect
}

enum class IncomingNotice { Stale, DeclinedOnly, ReminderSet, ReminderFailed }
