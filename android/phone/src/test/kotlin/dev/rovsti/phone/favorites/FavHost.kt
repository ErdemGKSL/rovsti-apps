package dev.rovsti.phone.favorites

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.haptics.RovstiHaptics
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.transition.Tab
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.phone.ContactsTab
import dev.rovsti.phone.FavoritesTab
import dev.rovsti.phone.PhoneRoots
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.PhoneTabs
import dev.rovsti.phone.R
import dev.rovsti.phone.RootLinks
import dev.rovsti.phone.chromeFor
import dev.rovsti.phone.contacts.PeopleAction
import dev.rovsti.phone.contacts.PeopleEmpty
import dev.rovsti.phone.contacts.PeoplePhase
import dev.rovsti.phone.contacts.PeopleUi
import dev.rovsti.phone.contacts.PersonRowUi
import dev.rovsti.phone.contacts.PhoneContactsContent
import dev.rovsti.phone.contacts.sectionIndexOf
import dev.rovsti.phone.phoneTabItems
import dev.rovsti.phone.setup.SetupUiState
import dev.rovsti.shell.RovstiShell
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Holds the Favorites state like the view model would (a drop reorders it) and records what the screen asks for. */
internal class FavHarness(
    initial: FavoritesUi,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<FavoritesAction>()
    val overlays = OverlayController()
    val infos = mutableListOf<FavPerson>()
    var added = 0
    var settings = 0
    lateinit var listState: LazyListState

    fun on(action: FavoritesAction) {
        actions += action
        when (action) {
            FavoritesAction.ToggleEdit -> {
                ui =
                    ui.copy(editing = !ui.editing, frequent = if (ui.editing) FavUi.frequent else persistentListOf())
            }

            is FavoritesAction.Move -> {
                val list = ui.favorites.toMutableList()
                list.add(action.to, list.removeAt(action.from))
                ui = ui.copy(favorites = list.toImmutableList())
            }

            is FavoritesAction.MoveStep -> {
                val list = ui.favorites.toMutableList()
                val to = if (action.up) action.index - 1 else action.index + 1
                list.add(to, list.removeAt(action.index))
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
        label: String? = "mobile",
    ) = FavPerson("fav-$id", id, name, label, "+90555" + id.toString().padStart(7, '1'))

    val favorites =
        listOf(
            person(1, "Ayse Yilmaz"),
            person(3, "Elif Kaya", "work"),
            person(5, "Ozgur Sahin"),
            person(11, "Ada Lovelace", "home"),
            person(22, "Mert Koc"),
        )

    val frequent =
        listOf(
            person(2, "Burak Demir").copy(key = "freq-2"),
            person(7, "Ahmet Celik", "work").copy(key = "freq-7"),
        ).toImmutableList()

    fun state(
        editing: Boolean = false,
        favorites: List<FavPerson> = this.favorites,
        frequent: List<FavPerson> = this.frequent,
    ) = FavoritesUi(
        phase = FavoritesPhase.Ready,
        favorites = favorites.toImmutableList(),
        editing = editing,
        frequent = (if (editing) emptyList() else frequent).toImmutableList(),
        pickables =
            listOf(person(4, "Deniz Aksoy"), person(6, "Can Demir"), person(8, "Naz Polat")).toImmutableList(),
    )
}

/** The Favorites tab inside the real shell: backdrop, tab bar, overlay host. */
@Composable
internal fun HostedFavorites(
    harness: FavHarness,
    haptics: RovstiHaptics? = null,
    setup: SetupUiState = SetupUiState(),
) {
    val navigator = remember { TabNavigator(PhoneTabs, PhoneRoots).also { it.select(FavoritesTab) } }
    val tabs =
        phoneTabItems(
            stringResource(R.string.tab_favorites),
            stringResource(R.string.tab_recents),
            stringResource(R.string.tab_contacts),
            stringResource(R.string.tab_keypad),
            missed = 0,
        )
    harness.listState = rememberLazyListState()
    val links = remember(harness) { RootLinks(openSettings = { harness.settings++ }, openSetup = {}) }
    val content: @Composable () -> Unit = {
        RovstiShell(
            navigator = navigator,
            tabs = tabs,
            chromeFor = { route -> chromeFor(route) },
            overlays = harness.overlays,
            modifier = Modifier.fillMaxSize(),
        ) {
            entry<PhoneRoute.Favorites> {
                FavoritesContent(
                    ui = harness.ui,
                    onAction = harness::on,
                    onInfo = { harness.infos += it },
                    onAdd = { harness.added++ },
                    links = links,
                    setup = setup,
                    listState = harness.listState,
                )
            }
            entry<PhoneRoute.Recents> {}
            entry<PhoneRoute.Contacts> {}
            entry<PhoneRoute.Keypad> {}
        }
    }
    if (haptics != null) {
        CompositionLocalProvider(LocalRovstiHaptics provides haptics) { content() }
    } else {
        content()
    }
}

/** Holds the Contacts tab state and records what the screen asks for. */
internal class PeopleHarness(
    initial: PeopleUi,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<PeopleAction>()
    val opened = mutableListOf<Long>()
    var created = 0

    /** Presents the sheet a tapped person opens, as the app does (F24); null records the tap only. */
    var onOpenSheet: ((Long, () -> Unit) -> Unit)? = null
    val overlays = OverlayController()
    lateinit var listState: LazyListState
    lateinit var searchState: TextFieldState

    fun on(action: PeopleAction) {
        actions += action
    }
}

internal object PeopleUiSample {
    private val names =
        listOf(
            "Ada Lovelace",
            "Aylin Bulut",
            "Ayse Yilmaz",
            "Baris Cetin",
            "Burak Ozturk",
            "Can Demir",
            "Cem Yildiz",
            "Defne Kurt",
            "Deniz Aksoy",
            "Elif Sahin",
            "Emre Toprak",
            "Fatma Gunes",
            "Gizem Aslan",
            "Hakan Eren",
            "Irem Kaplan",
            "Jale Tas",
            "Kerem Aydin",
            "Leyla Uysal",
            "Mert Kaya",
            "Murat Dogan",
            "Naz Polat",
            "Nehir Acar",
            "Ozan Tekin",
            "Selin Arslan",
            "Zeynep Celik",
        )

    fun rows(
        list: List<String> = names,
        sections: Boolean = true,
    ): List<PersonRowUi> {
        var last = ""
        return list.mapIndexed { i, name ->
            val letter = name.first().toString()
            val header = sections && letter != last
            last = letter
            PersonRowUi(
                id = (i + 1).toLong(),
                name = name,
                subtitle = "mobile  +90 555 ${100 + i} 22 33",
                photoUri = null,
                starred = name in setOf("Ayse Yilmaz", "Deniz Aksoy", "Zeynep Celik"),
                letter = letter,
                header = header,
            )
        }
    }

    fun ui(
        rows: List<PersonRowUi> = rows(),
        query: String = "",
        empty: PeopleEmpty = PeopleEmpty.None,
    ): PeopleUi {
        val (index, items) = sectionIndexOf(rows)
        return PeopleUi(
            phase = PeoplePhase.Ready,
            rows = rows.toImmutableList(),
            letters = rows.filter { it.header }.map { it.letter.first() }.toImmutableList(),
            showRail = query.isBlank() && rows.size > 20,
            sectionIndex = index,
            scrubItems = items,
            query = query,
            empty = empty,
        )
    }
}

/** The Contacts tab inside the real shell. */
@Composable
internal fun HostedContacts(
    harness: PeopleHarness,
    initialQuery: String = "",
    setup: SetupUiState = SetupUiState(),
) {
    val navigator = remember { TabNavigator(PhoneTabs, PhoneRoots).also { it.select(ContactsTab as Tab) } }
    val tabs =
        phoneTabItems(
            stringResource(R.string.tab_favorites),
            stringResource(R.string.tab_recents),
            stringResource(R.string.tab_contacts),
            stringResource(R.string.tab_keypad),
            missed = 0,
        )
    harness.listState = rememberLazyListState()
    harness.searchState = rememberTextFieldState(initialQuery)
    val links = remember(harness) { RootLinks(openSettings = {}, openSetup = {}) }
    RovstiShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = { route -> chromeFor(route) },
        overlays = harness.overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<PhoneRoute.Favorites> {}
        entry<PhoneRoute.Recents> {}
        entry<PhoneRoute.Contacts> {
            PhoneContactsContent(
                ui = harness.ui,
                searchState = harness.searchState,
                listState = harness.listState,
                onAction = harness::on,
                onOpen = { id, dismissed ->
                    harness.opened += id
                    harness.onOpenSheet?.invoke(id, dismissed)
                },
                onNew = { harness.created++ },
                links = links,
                setup = setup,
            )
        }
        entry<PhoneRoute.Keypad> {}
    }
}
