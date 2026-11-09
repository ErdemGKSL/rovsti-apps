package dev.rovsti.contacts.cleanup

import androidx.compose.runtime.Immutable
import dev.rovsti.core.model.contacts.Confirm
import dev.rovsti.design.controls.ChipModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/** One contact under the current filter. [name] is never empty; [secondary] is the first thing the contact does have. */
@Immutable
data class CleanupRow(
    val id: Long,
    val name: String,
    val secondary: String?,
    val photoUri: String?,
) {
    val colorKey: String get() = id.toString()
}

/** Select mode of the page: who is picked and what the toolbar may do. */
@Immutable
data class CleanupSelect(
    val selected: ImmutableSet<Long> = persistentSetOf(),
    val title: String = "Select contacts",
    val allSelected: Boolean = false,
    val canDelete: Boolean = false,
)

enum class CleanupPhase { Loading, Ready }

@Immutable
data class CleanupUiState(
    val phase: CleanupPhase = CleanupPhase.Loading,
    /** The filters with their counts, in the order of the Rust core (`contacts.cleanup`). */
    val chips: ImmutableList<ChipModel> = persistentListOf(),
    /** The id of the chip that is on. */
    val filter: String = "",
    /** Shown when the filter has no contact left ("Every contact has a number"). */
    val emptyText: String = "",
    val rows: ImmutableList<CleanupRow> = persistentListOf(),
    val select: CleanupSelect? = null,
) {
    val isEmpty: Boolean get() = phase == CleanupPhase.Ready && rows.isEmpty()
}

sealed interface CleanupAction {
    data class SelectFilter(
        val id: String,
    ) : CleanupAction

    /** The toolbar's "Select" ([first] null) or a long press on a row. */
    data class EnterSelect(
        val first: Long? = null,
    ) : CleanupAction

    data object ExitSelect : CleanupAction

    data class Toggle(
        val id: Long,
    ) : CleanupAction

    data object ToggleAll : CleanupAction

    /** The toolbar's Delete: asks first. */
    data object AskDelete : CleanupAction

    data object ConfirmDelete : CleanupAction

    data class Undo(
        val keys: List<Long>,
    ) : CleanupAction
}

sealed interface CleanupEffect {
    data class Ask(
        val confirm: Confirm,
    ) : CleanupEffect

    data class Toast(
        val text: String,
    ) : CleanupEffect

    /** ONE pill for the whole delete; Undo sends [CleanupAction.Undo] with [keys]. */
    data class Deleted(
        val notice: String,
        val keys: List<Long>,
    ) : CleanupEffect
}
