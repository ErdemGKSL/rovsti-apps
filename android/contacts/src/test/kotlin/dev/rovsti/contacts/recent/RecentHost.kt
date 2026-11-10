package dev.rovsti.contacts.recent

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.rovsti.contacts.ContactsRoots
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.ContactsTabs
import dev.rovsti.contacts.chromeFor
import dev.rovsti.contacts.contactsTabItems
import dev.rovsti.contacts.en
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.text.UiText
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.shell.RovstiShell
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Plays the view model's part for the segment and records what the page asks for. */
internal class RecentHarness(
    initial: RecentUiState,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<RecentAction>()
    val overlays = OverlayController()
    var backs = 0
    lateinit var listState: LazyListState

    fun on(action: RecentAction) {
        actions += action
        if (action is RecentAction.SelectTab) ui = ui.copy(selected = action.id)
    }
}

internal object RecentUi {
    val tabs =
        persistentListOf(
            RecentTabUi("added", "Added", "Nothing New", "No contacts added in the last 30 days"),
            RecentTabUi("edited", "Edited", "Nothing Edited", "No contacts edited in the last 30 days"),
        )

    private fun person(
        id: Long,
        name: String,
        whenText: String,
    ) = PersonItem(id, name, whenText)

    val added =
        listOf(
            person(41, "Selma Aydoğan", "Today"),
            person(42, "Kerem Bulut", "Yesterday"),
            person(43, "Defne Çınar", "3 days ago"),
            person(44, "Orhan Tunç", "12 Sep"),
            person(45, "Aylin Sönmez", "4 Sep"),
        )

    val edited =
        listOf(
            person(2, "Burak Demir", "Today"),
            person(3, "Elif Kaya", "2 days ago"),
            person(8, "Can Aydın", "7 Sep"),
        )

    /** [many] gives both lists enough rows to scroll. */
    fun state(
        selected: String = "added",
        added: List<PersonItem> = this.added,
        edited: List<PersonItem> = this.edited,
        many: Boolean = false,
    ): RecentUiState {
        val a = if (many) (1L..30L).map { person(100 + it, "Added Person $it", "Today") } else added
        val e = if (many) (1L..30L).map { person(200 + it, "Edited Person $it", "Today") } else edited
        return RecentUiState(
            phase = RecentPhase.Ready,
            tabs = tabs,
            selected = selected,
            added = a.toImmutableList(),
            edited = e.toImmutableList(),
        )
    }
}

/** The page pushed over the Contacts tab, as in the app (tab bar hidden on this route). */
@Composable
internal fun HostedRecent(harness: RecentHarness) {
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots).also { it.push(ContactsRoute.Recent) } }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    harness.listState = rememberLazyListState()
    RovstiShell(
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
        entry<ContactsRoute.Recent> {
            RecentScreen(
                state = harness.ui,
                onAction = harness::on,
                onBack = { harness.backs++ },
                listState = harness.listState,
            )
        }
    }
}
