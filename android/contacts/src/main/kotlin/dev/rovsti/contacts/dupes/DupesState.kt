package dev.rovsti.contacts.dupes

import androidx.compose.runtime.Immutable
import dev.rovsti.contacts.intake.PreviewSection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * One group of contacts that look like the same person. ONE avatar (the keeper's) and one secondary line: "and 2 more"
 * and why they were grouped. [key] is the keeper's id (the group's first id), which stays stable while the others change.
 */
@Immutable
data class DupeRow(
    val key: Long,
    val name: String,
    val secondary: String,
    val photoUri: String?,
) {
    val colorKey: String get() = key.toString()
}

/**
 * The merged card of one group, as `contacts.merge_plan` writes it. [sources] is ONE line of plain text naming the
 * contacts that go into it (never pills); [mergeLabel] is the main button.
 */
@Immutable
data class MergeReview(
    val key: Long,
    val name: String,
    val subtitle: String?,
    val photoUri: String?,
    val sources: String,
    val sections: ImmutableList<PreviewSection>,
    val mergeLabel: String,
) {
    val colorKey: String get() = key.toString()
}

enum class DupesPhase { Loading, Ready }

@Immutable
data class DupesUiState(
    val phase: DupesPhase = DupesPhase.Loading,
    /** The line above the list from the Rust core ("2 possible duplicates"). */
    val summary: String = "",
    val rows: ImmutableList<DupeRow> = persistentListOf(),
    val review: MergeReview? = null,
    /** A merge is being written; its buttons wait. */
    val busy: Boolean = false,
) {
    val isEmpty: Boolean get() = phase == DupesPhase.Ready && rows.isEmpty()
}

sealed interface DupesAction {
    /** A row was tapped: show the merged preview. */
    data class Open(
        val key: Long,
    ) : DupesAction

    data object CloseReview : DupesAction

    /** "Merge" in the review sheet: merges the group that is open. */
    data object MergeOpen : DupesAction

    /** The toolbar's "Merge All": asks first. */
    data object AskMergeAll : DupesAction

    data object ConfirmMergeAll : DupesAction

    /** The one Undo of the last merge; [token] says which merge. */
    data class Undo(
        val token: Long,
    ) : DupesAction
}

sealed interface DupesEffect {
    data class Ask(
        val title: String,
        val body: String,
        val confirmLabel: String,
        val then: DupesAction,
    ) : DupesEffect

    data class Toast(
        val text: String,
    ) : DupesEffect

    /** ONE undo pill for a whole merge; Undo sends [DupesAction.Undo] with [token]. */
    data class Undo(
        val message: String,
        val token: Long,
    ) : DupesEffect
}
