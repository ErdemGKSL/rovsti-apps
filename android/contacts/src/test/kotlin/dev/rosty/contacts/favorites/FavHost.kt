package dev.rosty.contacts.favorites

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.rosty.contacts.ContactsRoots
import dev.rosty.contacts.ContactsRoute
import dev.rosty.contacts.ContactsTabs
import dev.rosty.contacts.FavoritesTab
import dev.rosty.contacts.chromeFor
import dev.rosty.contacts.contactsTabItems
import dev.rosty.contacts.en
import dev.rosty.contacts.people.PersonItem
import dev.rosty.core.model.contacts.EventKind
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.haptics.RostyHaptics
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.text.UiText
import dev.rosty.design.transition.TabNavigator
import dev.rosty.shell.RostyShell
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Holds the tab state like the view model would (a drop reorders it) and records what the screen asks for. */
internal class FavHarness(
    initial: FavoritesUiState,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<FavoritesAction>()
    val overlays = OverlayController()
    var upcomingOpened = 0
    lateinit var listState: LazyListState

    fun on(action: FavoritesAction) {
        actions += action
        when (action) {
            FavoritesAction.ToggleEdit -> {
                ui = ui.copy(editing = !ui.editing)
            }

            is FavoritesAction.Move -> {
                val list = ui.favorites.toMutableList()
                list.add(action.to, list.removeAt(action.from))
                ui = ui.copy(favorites = list.toImmutableList())
            }

            else -> {
                Unit
            }
        }
    }
}

internal object FavUi {
    private fun person(
        id: Long,
        name: String,
        secondary: String? = null,
    ) = PersonItem(id, name, secondary)

    val favorites =
        listOf(
            person(1, "Ayşe Yılmaz"),
            person(3, "Elif Kaya", "Work and 1 more"),
            person(5, "Özgür Şahin"),
            person(11, "Ada Lovelace"),
            person(22, "Mert Koç"),
        )

    val frequent =
        listOf(
            person(2, "Burak Demir", "3 calls in the last 30 days"),
            person(7, "Ahmet Çelik", "2 calls in the last 30 days"),
        )

    private fun date(
        id: Long,
        name: String,
        kind: EventKind,
        days: Long,
        date: String,
        turning: String?,
        wish: Boolean = true,
    ): UpcomingItem {
        val whenText = if (days == 1L) "Tomorrow" else "In $days days"
        return UpcomingItem(
            key = "$id-${kind.name}-$days",
            contactId = id,
            name = name,
            kind = kind,
            photoUri = null,
            sectionLine = listOfNotNull(whenText, turning).joinToString(" · "),
            pageLine = listOfNotNull(whenText, date, turning).joinToString(" · "),
            canWish = wish,
        )
    }

    val upcoming =
        listOf(
            date(2, "Burak Demir", EventKind.Birthday, 2, "18 Sep", "Turns 30"),
            date(1, "Ayşe Yılmaz", EventKind.Birthday, 17, "3 Oct", "Turns 36"),
            date(3, "Elif Kaya", EventKind.Anniversary, 65, "20 Nov", "11 years", wish = false),
        )

    val later = date(4, "Mehmet Öz", EventKind.Birthday, 99, "24 Dec", null)

    fun state(
        editing: Boolean = false,
        favorites: List<PersonItem> = this.favorites,
        frequent: List<PersonItem> = this.frequent,
        upcoming: List<UpcomingItem> = this.upcoming,
        gate: Boolean = false,
    ) = FavoritesUiState(
        phase = FavoritesPhase.Ready,
        favorites = favorites.toImmutableList(),
        editing = editing,
        frequent = (if (editing) emptyList() else frequent).toImmutableList(),
        frequentGate = gate,
        upcoming = (if (editing) emptyList() else upcoming).toImmutableList(),
        upcomingTotal = upcoming.size + 1,
        upcomingGroups =
            persistentListOf(
                UpcomingGroupUi("This week", persistentListOf(upcoming[0])),
                UpcomingGroupUi("This month", persistentListOf(upcoming[1])),
                UpcomingGroupUi("Later", persistentListOf(upcoming[2], later)),
            ),
        pickables =
            listOf(
                person(2, "Burak Demir"),
                person(4, "Mehmet Öz"),
                person(6, "Zeynep Arslan", "Acme Yazılım"),
                person(7, "Ahmet Çelik"),
                person(8, "Can Aydın"),
                person(9, "Deniz Korkmaz"),
                person(10, "İpek Güneş"),
                person(12, "Alper Tekin"),
            ).toImmutableList(),
    )
}

/** The Favorites tab inside the real shell: backdrop, tab bar, overlay host. */
@Composable
internal fun HostedFavorites(
    harness: FavHarness,
    haptics: RostyHaptics? = null,
    gate: (@Composable () -> Unit)? = null,
) {
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots).also { it.select(FavoritesTab) } }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    harness.listState = rememberLazyListState()
    val content: @Composable () -> Unit = {
        RostyShell(
            navigator = navigator,
            tabs = tabs,
            chromeFor = { chromeFor(it, UiText::en) },
            overlays = harness.overlays,
            modifier = Modifier.fillMaxSize(),
        ) {
            entry<ContactsRoute.ContactsList> {}
            entry<ContactsRoute.Favorites> {
                FavoritesScreen(
                    state = harness.ui,
                    onAction = harness::on,
                    onOpenUpcoming = { harness.upcomingOpened++ },
                    listState = harness.listState,
                    gate = gate,
                )
            }
            entry<ContactsRoute.Groups> {}
            entry<ContactsRoute.Settings> {}
        }
    }
    if (haptics != null) {
        CompositionLocalProvider(LocalRostyHaptics provides haptics) { content() }
    } else {
        content()
    }
}

/** The See All page pushed over the Favorites tab, as in the app (tab bar hidden). */
@Composable
internal fun HostedUpcoming(harness: FavHarness) {
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots).also { it.select(FavoritesTab) } }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    androidx.compose.runtime.LaunchedEffect(Unit) { navigator.push(ContactsRoute.UpcomingBirthdays) }
    RostyShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = { chromeFor(it, UiText::en) },
        overlays = harness.overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<ContactsRoute.ContactsList> {}
        entry<ContactsRoute.Favorites> {}
        entry<ContactsRoute.Groups> {}
        entry<ContactsRoute.Settings> {}
        entry<ContactsRoute.UpcomingBirthdays> {
            UpcomingScreen(
                state = harness.ui,
                onAction = harness::on,
                onBack = { harness.upcomingOpened-- },
                listState = rememberLazyListState(),
            )
        }
    }
}
