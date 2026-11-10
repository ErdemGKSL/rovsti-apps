package dev.rovsti.contacts.trash

import androidx.compose.runtime.Immutable
import dev.rovsti.design.text.UiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** One deleted contact. [detail] reads "Deleted 3 days ago · 27 days left"; [name] is never empty. */
@Immutable
data class TrashRow(
    val key: Long,
    val name: UiText,
    val detail: UiText,
    val colorKey: String,
)

enum class TrashPhase { Loading, Ready }

@Immutable
data class TrashUiState(
    val phase: TrashPhase = TrashPhase.Loading,
    val rows: ImmutableList<TrashRow> = persistentListOf(),
    /** How long an entry stays, for the footer line. */
    val retentionDays: Int = 30,
) {
    val isEmpty: Boolean get() = phase == TrashPhase.Ready && rows.isEmpty()
}

sealed interface TrashAction {
    /** Reads the Trash again (the page came back to the front). */
    data object Refresh : TrashAction

    data class Restore(
        val key: Long,
    ) : TrashAction

    /** Swipe or menu "Delete Now": asks first. */
    data class AskDelete(
        val key: Long,
    ) : TrashAction

    data object AskEmpty : TrashAction

    data class ConfirmDelete(
        val keys: List<Long>,
    ) : TrashAction

    data object ConfirmEmpty : TrashAction
}

sealed interface TrashEffect {
    /** A confirmation: [title] and [body] are the Rust core's words, [confirmLabel] runs [then]. */
    data class Ask(
        val title: UiText,
        val body: UiText,
        val confirmLabel: UiText,
        val then: TrashAction,
    ) : TrashEffect

    data class Toast(
        val text: UiText,
    ) : TrashEffect
}
