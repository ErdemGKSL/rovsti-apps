package dev.rovsti.contacts.me

import androidx.compose.runtime.Immutable
import dev.rovsti.contacts.R
import dev.rovsti.contacts.list.MyCardItem
import dev.rovsti.design.text.UiText
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * My card and the emergency list as the Contacts list and the card need them: the header row (`contacts.me_state`
 * title and detail; [MyCardItem.contactId] null means no card is set yet), who is on the emergency list, and the one
 * line of the "Emergency Contacts" row.
 */
@Immutable
data class MeUiState(
    val myCard: MyCardItem? = null,
    val emergencyIds: ImmutableSet<Long> = persistentSetOf(),
    val max: Int = 5,
) {
    /** The detail line of the "Emergency Contacts" row. */
    val emergencyDetail: UiText
        get() =
            if (emergencyIds.isEmpty()) {
                UiText.Res(
                    R.string.contacts_not_set_up,
                )
            } else {
                UiText.Res(R.string.contacts_emergency_detail, listOf(emergencyIds.size, max))
            }
}

/** What the screens ask of the model. */
sealed interface MeAction {
    /** "This Is Me" / the chooser's pick. */
    data class SetMyCard(
        val id: Long,
    ) : MeAction

    /** "Not Me". */
    data class ClearMyCard(
        val id: Long,
    ) : MeAction

    data class ToggleEmergency(
        val id: Long,
    ) : MeAction
}

/** One-shot results. */
sealed interface MeEffect {
    data class Toast(
        val text: UiText,
    ) : MeEffect
}
