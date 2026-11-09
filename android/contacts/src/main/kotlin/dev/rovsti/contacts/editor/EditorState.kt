package dev.rovsti.contacts.editor

import androidx.compose.runtime.Immutable
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftIssueKind
import dev.rovsti.core.model.contacts.EventKind
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.contacts.RelationKind

/** The repeatable lists of the editor; each row has a stable key so a field keeps its text when a row above goes. */
enum class EditorList { Numbers, Emails, Addresses, Websites, Events, Relations }

/** The single-value text fields of the editor. */
enum class EditorText { Given, Family, PhoneticGiven, PhoneticFamily, Org, Title, Nickname, Note }

/** The label a row can carry: a number/email/address kind, an event kind or a relation kind. */
sealed interface RowLabel {
    data class Field(
        val kind: FieldKind,
    ) : RowLabel

    data class Event(
        val kind: EventKind,
    ) : RowLabel

    data class Relation(
        val kind: RelationKind,
    ) : RowLabel
}

/** The row keys of every list, parallel to the lists of the draft. */
@Immutable
data class EditorKeys(
    val numbers: List<Long> = emptyList(),
    val emails: List<Long> = emptyList(),
    val addresses: List<Long> = emptyList(),
    val websites: List<Long> = emptyList(),
    val events: List<Long> = emptyList(),
    val relations: List<Long> = emptyList(),
) {
    fun of(list: EditorList): List<Long> =
        when (list) {
            EditorList.Numbers -> numbers
            EditorList.Emails -> emails
            EditorList.Addresses -> addresses
            EditorList.Websites -> websites
            EditorList.Events -> events
            EditorList.Relations -> relations
        }

    fun with(
        list: EditorList,
        keys: List<Long>,
    ): EditorKeys =
        when (list) {
            EditorList.Numbers -> copy(numbers = keys)
            EditorList.Emails -> copy(emails = keys)
            EditorList.Addresses -> copy(addresses = keys)
            EditorList.Websites -> copy(websites = keys)
            EditorList.Events -> copy(events = keys)
            EditorList.Relations -> copy(relations = keys)
        }
}

/** An account new contacts can be saved to, with its words. */
@Immutable
data class AccountChoice(
    val key: String,
    val label: String,
)

enum class EditorPhase { Loading, Ready, Gone }

/** Keys of [EditorUiState.issues] and [EditorUiState.hints]. */
object EditorIssueKey {
    const val NAME = "name"

    fun row(
        list: EditorList,
        key: Long,
    ) = "${list.name.lowercase()}:$key"
}

/**
 * The editor page. [draft] and [keys] are the live values and change synchronously with every keystroke; [external]
 * counts the changes that did not come from typing (a restored draft, an undo) so a text field knows when to take the
 * model's text instead of its own. [issues] are the sentences of `contacts.draft_validate` for the fields the owner has
 * left or, after a Save, for all of them; [hints] are the parsed dates under the date fields.
 */
@Immutable
data class EditorUiState(
    val phase: EditorPhase = EditorPhase.Loading,
    val isNew: Boolean = true,
    val draft: ContactDraft = ContactDraft(),
    val keys: EditorKeys = EditorKeys(),
    val external: Int = 0,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val issues: Map<String, String> = emptyMap(),
    val hints: Map<String, String> = emptyMap(),
    val accounts: List<AccountChoice> = emptyList(),
    val accountLabel: String = "",
    val groups: List<Group> = emptyList(),
) {
    val canSave: Boolean get() = phase == EditorPhase.Ready && dirty && !saving

    /** The words of the Groups row: the chosen titles. */
    val groupsLabel: String
        get() {
            val chosen = groups.filter { it.id in draft.groups }.map { it.title }
            return when (chosen.size) {
                0 -> "None"
                1, 2 -> chosen.joinToString(", ")
                else -> "${chosen.size} groups"
            }
        }
}

sealed interface EditorAction {
    data class Text(
        val field: EditorText,
        val value: String,
    ) : EditorAction

    /** The value of a row: number, email, address, website, date or relation name. */
    data class RowValue(
        val list: EditorList,
        val key: Long,
        val value: String,
    ) : EditorAction

    data class Label(
        val list: EditorList,
        val key: Long,
        val label: RowLabel,
    ) : EditorAction

    data class CustomLabel(
        val list: EditorList,
        val key: Long,
        val text: String,
    ) : EditorAction

    data class AddRow(
        val list: EditorList,
    ) : EditorAction

    data class RemoveRow(
        val list: EditorList,
        val key: Long,
    ) : EditorAction

    /** Makes (or, when it already is one, clears) the row the default of its list. */
    data class ToggleDefault(
        val list: EditorList,
        val key: Long,
    ) : EditorAction

    data class ToggleGroup(
        val id: Long,
    ) : EditorAction

    data class SetAccount(
        val key: String,
    ) : EditorAction

    /** A field lost the focus: its sentence may show now. */
    data class Left(
        val issueKey: String,
    ) : EditorAction

    data object Save : EditorAction

    /** Cancel, Back or a swipe down: closes at once, or asks first when there is something to lose. */
    data object RequestClose : EditorAction

    data object Discard : EditorAction
}

sealed interface EditorEffect {
    /** The page can go. */
    data object Close : EditorEffect

    /** There are unsaved changes: ask. */
    data object ConfirmDiscard : EditorEffect

    /** Saved as [id]; [notice] is the words of the pill and [undoable] whether it carries Undo. */
    data class Saved(
        val id: Long,
        val notice: String,
        val undoable: Boolean,
    ) : EditorEffect

    /** A Save was refused for the sentence under a field; the page scrolls it into view. */
    data class Invalid(
        val issueKey: String,
    ) : EditorEffect

    data class Toast(
        val text: String,
    ) : EditorEffect
}

internal fun DraftIssueKind.listOrNull(): EditorList? =
    when (this) {
        DraftIssueKind.NoName -> null
        DraftIssueKind.BadNumber -> EditorList.Numbers
        DraftIssueKind.BadEmail -> EditorList.Emails
        DraftIssueKind.BadDate -> EditorList.Events
    }
