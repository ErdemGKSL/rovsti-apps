package dev.rovsti.contacts.groups

import androidx.compose.runtime.Immutable
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.design.text.UiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class GroupsPhase { Loading, NoPermission, Ready }

/** One row of the Groups tab: [subtitle] is "3 contacts" (and the account when there are several). */
@Immutable
data class GroupItem(
    val id: Long,
    val title: String,
    val subtitle: UiText,
)

@Immutable
data class GroupsUiState(
    val phase: GroupsPhase = GroupsPhase.Loading,
    val groups: ImmutableList<GroupItem> = persistentListOf(),
)

/** What the screens ask of the Groups tab model. */
sealed interface GroupsAction {
    data class Open(
        val id: Long,
    ) : GroupsAction

    /** The name alert's Save of "New Group". */
    data class Create(
        val name: String,
    ) : GroupsAction
}

sealed interface GroupsEffect {
    /** "+" was tapped, or a rejected name comes back for another try. */
    data class AskName(
        val initial: String,
        val error: String?,
    ) : GroupsEffect

    data class OpenGroup(
        val id: Long,
    ) : GroupsEffect

    data class Toast(
        val text: UiText,
    ) : GroupsEffect
}

/** The group page as one value. [members] are the people of the group that are in the address book, by name. */
@Immutable
data class GroupPageUi(
    val id: Long,
    val title: String,
    val subtitle: UiText,
    /** "Contacts added here sync with ..." when the phone has more than one account; null otherwise. */
    val joinHint: String?,
    val members: ImmutableList<PersonItem>,
    val canMessage: Boolean,
    val canEmail: Boolean,
    /** Everybody in the address book, for Add Members. */
    val everyone: ImmutableList<PersonItem>,
) {
    val isEmpty: Boolean get() = members.isEmpty()
    val memberIds: Set<Long> get() = members.mapTo(HashSet()) { it.id }
}

/** [Gone] means the group was deleted (from here or elsewhere): the page closes. */
sealed interface GroupPageState {
    data object Loading : GroupPageState

    data object Gone : GroupPageState

    data class Ready(
        val page: GroupPageUi,
    ) : GroupPageState
}

sealed interface GroupPageAction {
    data class OpenMember(
        val id: Long,
    ) : GroupPageAction

    data class Rename(
        val name: String,
    ) : GroupPageAction

    /** Add Members' Add: the ticked set after the picker. */
    data class SetMembers(
        val chosen: Set<Long>,
    ) : GroupPageAction

    data class RemoveMember(
        val id: Long,
    ) : GroupPageAction

    data object MessageGroup : GroupPageAction

    data object EmailGroup : GroupPageAction

    data object ExportGroup : GroupPageAction

    /** Delete Group's confirmation button (the question was asked by the screen). */
    data object ConfirmDelete : GroupPageAction
}

sealed interface GroupPageEffect {
    data class OpenCard(
        val id: Long,
    ) : GroupPageEffect

    data class AskName(
        val initial: String,
        val error: String?,
    ) : GroupPageEffect

    /** [address] is the joined recipients for the composer (`;` for SMS, `,` for e-mail). */
    data class Sms(
        val address: String,
    ) : GroupPageEffect

    data class Email(
        val address: String,
    ) : GroupPageEffect

    data class SaveVcf(
        val fileName: String,
        val text: String,
        val count: Int,
    ) : GroupPageEffect

    data class Toast(
        val text: UiText,
    ) : GroupPageEffect

    /** The group is gone: pop the page. */
    data object Close : GroupPageEffect
}
