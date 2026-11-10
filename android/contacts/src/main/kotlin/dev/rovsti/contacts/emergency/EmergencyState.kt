package dev.rovsti.contacts.emergency

import androidx.compose.runtime.Immutable
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.design.text.UiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** What the page shows while the book is not ready. */
enum class EmergencyPhase { Loading, NoPermission, Ready }

/**
 * The Emergency Contacts page (C43) as one value. [people] are in the order the user saved (the secondary line is the
 * number in the display format); [editing] shows a drag handle on each row instead of the Call button.
 */
@Immutable
data class EmergencyUiState(
    val phase: EmergencyPhase = EmergencyPhase.Loading,
    val people: ImmutableList<PersonItem> = persistentListOf(),
    val max: Int = 5,
    val editing: Boolean = false,
    /** The text "Share List" hands to the share sheet. */
    val shareText: String = "",
    /** Everybody with a number who is not on the list yet: the "+" picker. */
    val pickables: ImmutableList<PersonItem> = persistentListOf(),
) {
    val canAdd: Boolean get() = people.size < max

    /** The header buttons: Edit needs two people to reorder; while editing only Done shows. */
    val header: List<EmergencyHeaderAction>
        get() =
            when {
                editing -> listOf(EmergencyHeaderAction.Done)
                people.size >= 2 && canAdd -> listOf(EmergencyHeaderAction.Edit, EmergencyHeaderAction.Add)
                people.size >= 2 -> listOf(EmergencyHeaderAction.Edit)
                canAdd -> listOf(EmergencyHeaderAction.Add)
                else -> emptyList()
            }
}

enum class EmergencyHeaderAction { Add, Edit, Done }

/** What the page asks of the model. */
sealed interface EmergencyAction {
    data class Open(
        val id: Long,
    ) : EmergencyAction

    /** The Call button of a row. */
    data class Call(
        val id: Long,
    ) : EmergencyAction

    data object ToggleEdit : EmergencyAction

    /** A drop in edit mode: the row that was at [from] now sits at [to]. */
    data class Move(
        val from: Int,
        val to: Int,
    ) : EmergencyAction

    /** "Move Earlier" / "Move Later" of the row menu and of the screen reader. */
    data class MoveStep(
        val index: Int,
        val up: Boolean,
    ) : EmergencyAction

    data class Remove(
        val id: Long,
    ) : EmergencyAction

    data class AddAll(
        val ids: Set<Long>,
    ) : EmergencyAction

    data object Share : EmergencyAction
}

/** One-shot results. */
sealed interface EmergencyEffect {
    data class OpenCard(
        val id: Long,
    ) : EmergencyEffect

    /** Opens the dialer with [number] typed in. */
    data class Dial(
        val number: String,
    ) : EmergencyEffect

    data class Share(
        val text: String,
    ) : EmergencyEffect

    data class Toast(
        val text: UiText,
    ) : EmergencyEffect
}
