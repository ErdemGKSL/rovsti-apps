package dev.rovsti.phone.incall

import androidx.compose.runtime.Immutable
import dev.rovsti.core.model.phone.AudioRoute
import dev.rovsti.core.model.phone.RowKind
import dev.rovsti.phone.incoming.SimChip
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Where the single call is in its life. [Ended] is the two-second hold after it is gone. */
enum class CallPhase { Dialing, Active, Holding, Ended }

/** The call the In-Call screen shows. Names and numbers only; every sentence is drawn from [InCallStrings]. */
@Immutable
data class InCallCall(
    val callId: Int,
    /** The name, else the number, else the "Unknown" word. */
    val title: String,
    /** The number under a contact's name; null when the title already is the number. */
    val subtitle: String?,
    /** The raw number the note, the copy and the block act on; empty for a private caller. */
    val number: String,
    /** Colours the avatar and the aura; the contact id, else the title. */
    val avatarKey: String,
    val contactId: Long?,
    val phase: CallPhase,
    /** Wall-clock ms at which the call connected; 0 until it does. */
    val connectTimeMs: Long,
    /** When the call was placed or rang: the key (with the number) under which its note is stored. */
    val startedMs: Long,
    val sim: SimChip?,
    val incoming: Boolean,
)

/** One line of the audio route list, already filtered for the grants the app holds. */
@Immutable
data class RouteRowUi(
    val id: String,
    val kind: RowKind,
    val label: String,
    val subtitle: String,
    val selected: Boolean,
    val unavailable: Boolean,
)

/** The in-call keypad (DTMF). [digits] is what was sent; [pasteLabel] is non-null while the clipboard fits as tones. */
@Immutable
data class ToneKeypad(
    val open: Boolean = false,
    val digits: String = "",
    val pasteLabel: String? = null,
    /** The tones remembered for this number (U51), offered as "Send ..." when the keypad opens. */
    val remembered: String? = null,
    /** A paste (or a remembered sequence) is being played; the button then reads Stop. */
    val sending: Boolean = false,
)

/** The summary shown for two seconds after the call is gone. */
@Immutable
data class EndedUi(
    val callId: Int,
    val connected: Boolean,
    val talkS: Long,
    /** The duration as the core writes it ("4:32"); empty when the call never connected. */
    val elapsed: String,
)

@Immutable
data class InCallUi(
    val call: InCallCall? = null,
    val muted: Boolean = false,
    val canHold: Boolean = false,
    val route: AudioRoute = AudioRoute.Earpiece,
    val routeRows: ImmutableList<RouteRowUi> = persistentListOf(),
    /** The Audio button opens the list (3+ routes); otherwise a tap toggles speaker and phone. */
    val routeOpensList: Boolean = false,
    val keypad: ToneKeypad = ToneKeypad(),
    /** The call already has a saved note (the Note button then reads "Note"). */
    val hasNote: Boolean = false,
    val ended: EndedUi? = null,
    /** The keyguard is up: the screen shows over the lock screen. */
    val locked: Boolean = false,
) {
    val live: Boolean get() = call != null && call.phase != CallPhase.Ended
    val held: Boolean get() = call?.phase == CallPhase.Holding
}

sealed interface InCallAction {
    data object ToggleMute : InCallAction

    data object ToggleHold : InCallAction

    data object ToggleKeypad : InCallAction

    /** The Audio button: toggles earpiece and speaker when there is nothing to choose from. */
    data object ToggleRoute : InCallAction

    data class PickRoute(
        val id: String,
    ) : InCallAction

    data object End : InCallAction

    // The tone keypad.
    data class Digit(
        val key: Char,
    ) : InCallAction

    data object DigitUp : InCallAction

    data object CopyDigits : InCallAction

    data object PasteTones : InCallAction

    data object StopTones : InCallAction

    data object SendRemembered : InCallAction

    // Note, number, timer.
    data object OpenNote : InCallAction

    data class SaveNote(
        val text: String,
    ) : InCallAction

    data object CopyNumber : InCallAction

    data class CopyTimer(
        val text: String,
    ) : InCallAction

    data class ShareSummary(
        val text: String,
    ) : InCallAction
}

enum class InCallNotice {
    NumberCopied,
    NoNumber,
    TimerCopied,
    TonesCopied,
    TonesSent,
    NoteSaved,
    NoteFailed,
    RouteFailed,
    CommandFailed,
}

/** What the post-call pill offers. */
enum class PostCallKind { Block, AddNote }

sealed interface InCallEffect {
    data class Notice(
        val notice: InCallNotice,
    ) : InCallEffect

    /** The note sheet for the call: [text] is the saved note, empty when there is none. */
    data class ShowNote(
        val number: String,
        val startedMs: Long,
        val title: String,
        val text: String,
    ) : InCallEffect

    data class Share(
        val text: String,
    ) : InCallEffect

    /** The pill after a call: Block for a short call from an unknown number, Add note after a connected one. */
    data class PostCall(
        val kind: PostCallKind,
        val number: String,
        val title: String,
        val startedMs: Long,
        val talkS: Long,
        val elapsed: String,
    ) : InCallEffect

    /** The ended summary has been shown; the screen closes. */
    data object Leave : InCallEffect

    data object Confirm : InCallEffect

    data object Reject : InCallEffect
}
