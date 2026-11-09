package dev.rovsti.contacts.card

import androidx.compose.runtime.Immutable
import dev.rovsti.core.model.contacts.Confirm
import dev.rovsti.platform.contacts.ContactCallOptions

/** What the Block row of the "Calls" section does. */
@Immutable
sealed interface BlockRow {
    /** The contact has no number to block: the row is not shown. */
    data object Hidden : BlockRow

    /** The app may use the system blocklist; [blocked] when every number of the contact is on it. */
    data class Available(
        val blocked: Boolean,
    ) : BlockRow

    /** This app is not the default dialer: the row explains it and opens the role settings ([CallOptionsEffect.OpenRoleSettings]). */
    data object NeedsRole : BlockRow
}

/** The card's "Calls" section: ringtone, voicemail and blocking (E4.13). */
@Immutable
data class CallOptionsUiState(
    /** False until the provider has been read; the section is drawn from then on, and never changes height. */
    val loaded: Boolean = false,
    /** "Default", "Silent" or the tone's title (words of the Rust op). */
    val ringtone: String = "Default",
    val voicemail: Boolean = false,
    val block: BlockRow = BlockRow.Hidden,
)

sealed interface CallOptionsAction {
    /** The Ringtone row. */
    data object ChooseRingtone : CallOptionsAction

    /** The answer of the ringtone picker. */
    data class RingtonePicked(
        val pick: ContactCallOptions.Pick,
    ) : CallOptionsAction

    /** The switch: turning it on asks first. */
    data class SetVoicemail(
        val on: Boolean,
    ) : CallOptionsAction

    /** "Yes" of the voicemail question. */
    data object ConfirmVoicemail : CallOptionsAction

    /** The Block / Unblock row. */
    data object ToggleBlock : CallOptionsAction

    /** "Block" of the block question. */
    data object ConfirmBlock : CallOptionsAction
}

sealed interface CallOptionsEffect {
    /** Start the ringtone picker on [current] (null: default, "": silent). */
    data class PickRingtone(
        val current: String?,
    ) : CallOptionsEffect

    data class AskVoicemail(
        val confirm: Confirm,
    ) : CallOptionsEffect

    data class AskBlock(
        val title: String,
        val message: String,
        val actionLabel: String,
    ) : CallOptionsEffect

    /** Open the system screen where the default phone app is chosen. */
    data object OpenRoleSettings : CallOptionsEffect

    data class Toast(
        val text: String,
    ) : CallOptionsEffect
}
