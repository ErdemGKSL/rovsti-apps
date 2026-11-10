package dev.rovsti.contacts.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.contacts.R
import dev.rovsti.contacts.UiConfirm
import dev.rovsti.core.model.contacts.Addition
import dev.rovsti.core.model.contacts.Confirm
import dev.rovsti.core.model.contacts.QuickAction
import dev.rovsti.core.model.contacts.SelectAction
import dev.rovsti.design.text.UiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/** What the list shows while the book is not ready. */
enum class ListPhase { Loading, NoPermission, Ready }

/** Why the list has no rows. */
enum class EmptyKind { None, NoContacts, NoResults }

/**
 * One contact row. Everything the row draws is here, already decided: [name] follows the Name format, [secondary] is
 * the one line under it, [header] starts the section of [letter] (never while searching), [whatsapp] says the contact
 * has a chat on the linked instance (shown once, as the small glyph after the name).
 */
@Immutable
data class ContactItem(
    val id: Long,
    val name: String,
    val secondary: String?,
    val letter: String,
    val header: Boolean,
    val starred: Boolean,
    val whatsapp: Boolean,
    val hasNumber: Boolean,
    val photoUri: String?,
) {
    /** Which long-press menu the row gets: the quick actions only depend on these three facts. */
    val menuKey: MenuKey get() = MenuKey(hasNumber, whatsapp, starred)
}

/** The three facts `contacts.quick` answers from. */
@Immutable
data class MenuKey(
    val hasNumber: Boolean,
    val onWhatsapp: Boolean,
    val starred: Boolean,
)

/**
 * The "My card" row at the top of the list (`contacts.me_state`): [contactId] null is "Set up my card", the row that
 * asks which contact is you.
 */
@Immutable
data class MyCardItem(
    val contactId: Long?,
    val title: String,
    val detail: String,
)

/** The "Recent" row ("1 added - 2 edited") that opens the Recently Added / Edited page (E4.14). */
@Immutable
data class RecentEntry(
    val title: String,
    val detail: String,
)

/**
 * Select mode (E4.8): who is selected, the title and the bulk actions `contacts.select_bar` offers for that many
 * contacts, and whether every listed contact is selected (the toolbar then offers "Deselect All").
 */
@Immutable
data class SelectMode(
    val selected: ImmutableSet<Long> = persistentSetOf(),
    val title: UiText = UiText.Res(R.string.contacts_select_contacts),
    val actions: ImmutableList<SelectAction> = persistentListOf(),
    val allSelected: Boolean = false,
)

/** A group the selection can join, with how many selected contacts do not fit its account. */
@Immutable
data class GroupChoice(
    val id: Long,
    val title: String,
    val skipped: Int,
)

/** What "Create Contact" and "Add to a Contact" carry from a query that found nothing. */
@Immutable
data class SearchOffer(
    val create: DraftSeed? = null,
    val addition: Addition? = null,
)

/** The whole list screen as one value (ARCH 5.2). */
@Immutable
data class ContactsListUiState(
    val phase: ListPhase = ListPhase.Loading,
    val rows: ImmutableList<ContactItem> = persistentListOf(),
    /** The "In details" rows under a search, each with "Company - Acme" as its [ContactItem.secondary]. */
    val deepRows: ImmutableList<ContactItem> = persistentListOf(),
    val deepTitle: UiText = UiText.Res(R.string.contacts_in_details),
    val scrubberLetters: ImmutableList<Char> = persistentListOf(),
    val showScrubber: Boolean = false,
    /** Letter to the index of its header among the scrubbable items (headers and rows, in list order). */
    val sectionIndex: ImmutableMap<Char, Int> = persistentMapOf(),
    /** Headers plus rows: what the rail scrubs over. */
    val scrubItems: Int = 0,
    val query: String = "",
    /** The search field has focus (E4.2): with nothing typed the page shows only [viewed], like iOS. */
    val searchFocused: Boolean = false,
    val queryEcho: String = "",
    val emptyKind: EmptyKind = EmptyKind.None,
    val offer: SearchOffer = SearchOffer(),
    /** Contacts the "Add to a Contact" sheet offers. */
    val pickables: ImmutableList<ContactItem> = persistentListOf(),
    /** Recently viewed contacts; filled only while the search is focused and empty, so the main list has none. */
    val viewed: ImmutableList<ContactItem> = persistentListOf(),
    val viewedClear: UiConfirm = UiConfirm(UiText.Res(R.string.contacts_clear_recently_viewed), UiText.Literal("")),
    val myCard: MyCardItem? = null,
    val recentEntry: RecentEntry? = null,
    /** The detail of the "Emergency Contacts" row ("2 of 5", "Not set up"); null hides the row (the model has not answered). */
    val emergencyDetail: String? = null,
    /** `contacts.quick` per [MenuKey]; the row menus are built from it. */
    val quick: ImmutableMap<MenuKey, ImmutableList<QuickAction>> = persistentMapOf(),
    val selectMode: SelectMode? = null,
)

/** What the screen asks of the model. */
sealed interface ContactsListAction {
    data class QueryChanged(
        val text: String,
    ) : ContactsListAction

    /** The search field gained or lost focus. */
    data class SearchFocusChanged(
        val focused: Boolean,
    ) : ContactsListAction

    data class Open(
        val id: Long,
    ) : ContactsListAction

    data class Quick(
        val id: Long,
        val action: String,
    ) : ContactsListAction

    data class EnterSelect(
        val first: Long?,
    ) : ContactsListAction

    data object NewContact : ContactsListAction

    // ---- select mode (E4.8)
    data object ExitSelect : ContactsListAction

    data class ToggleSelected(
        val id: Long,
    ) : ContactsListAction

    /** Selects every listed contact, or none when all of them are selected already. */
    data object ToggleSelectAll : ContactsListAction

    data object ShareSelected : ContactsListAction

    data object ExportSelected : ContactsListAction

    data object CopySelectedNumbers : ContactsListAction

    data object CopySelectedEmails : ContactsListAction

    data object StarSelected : ContactsListAction

    data object AskDeleteSelected : ContactsListAction

    data object DeleteSelected : ContactsListAction

    data object AskAddToGroup : ContactsListAction

    data class AddSelectedToGroup(
        val groupId: Long,
    ) : ContactsListAction

    /** Undo of the one pill after a multi-delete: [keys] are the Trash keys of the deleted contacts. */
    data class UndoDelete(
        val keys: List<Long>,
    ) : ContactsListAction

    data object CreateFromQuery : ContactsListAction

    data class AddToContact(
        val id: Long,
    ) : ContactsListAction

    data object AskClearViewed : ContactsListAction

    data object ClearViewed : ContactsListAction

    data object OpenMyCard : ContactsListAction

    /** The "Emergency Contacts" row and the "Recent" row: the page opens the route. */
    data object OpenEmergency : ContactsListAction

    data object OpenRecent : ContactsListAction
}

/** One-shot results: navigation, toasts, system intents, a confirmation. Delivered once, through a channel. */
sealed interface ContactsListEffect {
    data class OpenCard(
        val id: Long,
    ) : ContactsListEffect

    data class OpenEditor(
        val id: Long?,
        val seed: DraftSeed?,
    ) : ContactsListEffect

    data class Dial(
        val number: String,
    ) : ContactsListEffect

    data class Sms(
        val number: String,
    ) : ContactsListEffect

    data class WhatsApp(
        val jid: String,
        val name: String,
    ) : ContactsListEffect

    data class Share(
        val text: String,
    ) : ContactsListEffect

    data class Toast(
        val text: UiText,
    ) : ContactsListEffect

    data class ConfirmClearViewed(
        val confirm: UiConfirm,
    ) : ContactsListEffect

    data class ConfirmDeleteSelected(
        val confirm: Confirm,
    ) : ContactsListEffect

    /** A vCard file to save ("Export .vcf"). */
    data class SaveVcf(
        val fileName: String,
        val text: String,
        val count: Int,
    ) : ContactsListEffect

    data class ChooseGroup(
        val groups: ImmutableList<GroupChoice>,
    ) : ContactsListEffect

    /** The one pill after a multi-delete; Undo sends [ContactsListAction.UndoDelete] with [keys]. */
    data class Deleted(
        val notice: UiText,
        val keys: List<Long>,
    ) : ContactsListEffect
}
