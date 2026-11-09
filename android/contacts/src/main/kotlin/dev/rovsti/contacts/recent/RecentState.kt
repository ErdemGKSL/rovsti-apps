package dev.rovsti.contacts.recent

import androidx.compose.runtime.Immutable
import dev.rovsti.contacts.people.PersonItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** What the page shows while the book is not ready. */
enum class RecentPhase { Loading, NoPermission, Ready }

/** A segment of the page: [id] is `added` or `edited` (`contacts.recent_lists` tabs), with the text of its empty state. */
@Immutable
data class RecentTabUi(
    val id: String,
    val label: String,
    val emptyTitle: String,
    val emptyBody: String,
)

/**
 * The Recently Added / Edited page (C15, C45) as one value. The rows' second line is the op's "when" text ("Today",
 * "Yesterday", "3 days ago", "12 May"). [selected] is the id of the segment that is shown.
 */
@Immutable
data class RecentUiState(
    val phase: RecentPhase = RecentPhase.Loading,
    val tabs: ImmutableList<RecentTabUi> = persistentListOf(),
    val selected: String = ADDED,
    val added: ImmutableList<PersonItem> = persistentListOf(),
    val edited: ImmutableList<PersonItem> = persistentListOf(),
    val windowDays: Int = 30,
) {
    /** The rows of the shown segment. */
    val rows: ImmutableList<PersonItem> get() = if (selected == EDITED) edited else added

    val selectedIndex: Int get() = tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0)

    val selectedTab: RecentTabUi? get() = tabs.firstOrNull { it.id == selected }

    companion object {
        const val ADDED = "added"
        const val EDITED = "edited"
    }
}

sealed interface RecentAction {
    data class SelectTab(
        val id: String,
    ) : RecentAction

    data class Open(
        val id: Long,
    ) : RecentAction
}

sealed interface RecentEffect {
    data class OpenCard(
        val id: Long,
    ) : RecentEffect
}
