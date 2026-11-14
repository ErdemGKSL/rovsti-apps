package dev.rovsti.contacts.favorites

import androidx.compose.runtime.Immutable
import dev.rovsti.contacts.people.AvatarFace
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.core.model.contacts.EventKind
import dev.rovsti.design.text.UiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** What the tab shows while the book is not ready. */
enum class FavoritesPhase { Loading, NoPermission, Ready }

/**
 * One birthday or anniversary. [sectionLine] is the second line on the Favorites tab ("In 4 days . Turns 36"),
 * [pageLine] the one on the See All page (the date is added). [canWish] is whether the contact has a number to send to.
 */
@Immutable
data class UpcomingItem(
    val key: String,
    val contactId: Long,
    val name: String,
    val kind: EventKind,
    val photoUri: String?,
    val sectionLine: String,
    val pageLine: String,
    val canWish: Boolean,
    val numbers: List<String> = emptyList(),
) {
    val face: AvatarFace get() = AvatarFace(photoUri, numbers)
}

/** A titled block of the See All page ("This week", "This month", "Later"). */
@Immutable
data class UpcomingGroupUi(
    val title: String,
    val items: ImmutableList<UpcomingItem>,
)

/** The whole Favorites tab as one value. */
@Immutable
data class FavoritesUiState(
    val phase: FavoritesPhase = FavoritesPhase.Loading,
    /** The favorites in the user's order. */
    val favorites: ImmutableList<PersonItem> = persistentListOf(),
    /** Edit mode: the rows show a drag handle and nothing else happens on a tap. */
    val editing: Boolean = false,
    /** Most called, not yet favorites. Empty without the call log. */
    val frequent: ImmutableList<PersonItem> = persistentListOf(),
    /** The call log is not readable: the tab shows its permission row under the Frequently Contacted title. */
    val frequentGate: Boolean = false,
    /** The next few dates, for the Upcoming section. */
    val upcoming: ImmutableList<UpcomingItem> = persistentListOf(),
    /** How many dates the See All page lists. */
    val upcomingTotal: Int = 0,
    /** The See All page, grouped. */
    val upcomingGroups: ImmutableList<UpcomingGroupUi> = persistentListOf(),
    /** Everybody who is not a favorite yet: the "+" picker. */
    val pickables: ImmutableList<PersonItem> = persistentListOf(),
) {
    /** The header buttons (`quick::favorites_header_actions`). */
    val header: List<FavoritesHeaderAction> get() = favoritesHeader(favorites.size, editing)

    val isEmpty: Boolean get() = favorites.isEmpty() && frequent.isEmpty() && upcoming.isEmpty() && !frequentGate
}

/** A button of the Favorites header. */
enum class FavoritesHeaderAction { Add, Edit, Done }

/**
 * The Favorites header buttons: Edit needs two favorites to reorder; while editing only Done shows (the rule of the
 * GPUI app's `quick::favorites_header_actions`, which is not reachable over FFI).
 */
fun favoritesHeader(
    count: Int,
    editing: Boolean,
): List<FavoritesHeaderAction> =
    when {
        editing -> listOf(FavoritesHeaderAction.Done)
        count >= 2 -> listOf(FavoritesHeaderAction.Edit, FavoritesHeaderAction.Add)
        else -> listOf(FavoritesHeaderAction.Add)
    }

/** What the screens ask of the model. */
sealed interface FavoritesAction {
    data class Open(
        val id: Long,
    ) : FavoritesAction

    data object ToggleEdit : FavoritesAction

    /** A drop in edit mode: the row that was at [from] now sits at [to]. */
    data class Move(
        val from: Int,
        val to: Int,
    ) : FavoritesAction

    /** "Move earlier" / "Move later" of the row menu. */
    data class MoveStep(
        val index: Int,
        val up: Boolean,
    ) : FavoritesAction

    data class Remove(
        val id: Long,
    ) : FavoritesAction

    /** The star of a Frequently Contacted row. */
    data class Star(
        val id: Long,
    ) : FavoritesAction

    data class AddAll(
        val ids: Set<Long>,
    ) : FavoritesAction

    /** [onPage]: asked on the See All page, so the answer goes to that page's [FavoritesViewModel.pageEffects]. */
    data class Wish(
        val contactId: Long,
        val kind: EventKind,
        val name: String,
        val onPage: Boolean = false,
    ) : FavoritesAction

    data object SaveCalendar : FavoritesAction
}

/** One-shot results. */
sealed interface FavoritesEffect {
    data class OpenCard(
        val id: Long,
    ) : FavoritesEffect

    data class Sms(
        val number: String,
        val body: String,
    ) : FavoritesEffect

    /** The .ics text is ready: the page asks where to save it. */
    data class SaveCalendar(
        val fileName: String,
        val text: String,
        val count: Int,
        val notice: String,
    ) : FavoritesEffect

    data class Toast(
        val text: UiText,
    ) : FavoritesEffect
}
