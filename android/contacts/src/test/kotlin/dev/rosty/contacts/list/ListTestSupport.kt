package dev.rosty.contacts.list

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
import androidx.compose.ui.platform.LocalContext
import dev.rosty.contacts.ContactsRoots
import dev.rosty.contacts.ContactsRoute
import dev.rosty.contacts.ContactsTabs
import dev.rosty.contacts.chromeFor
import dev.rosty.contacts.contactsTabItems
import dev.rosty.core.model.contacts.SelectAction
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.haptics.RostyHaptics
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.text.UiText
import dev.rosty.design.text.resolve
import dev.rosty.design.transition.TabNavigator
import dev.rosty.shell.RostyShell
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableSet

/** Holds the page state the way the view model would and records what the screen asks for. */
internal class ListHarness(
    initial: ContactsListUiState,
    /** Maps a typed query to the state to show (the view model's job); null keeps the state. */
    private val onQuery: ((String) -> ContactsListUiState)? = null,
    /** Maps the search field's focus to the state to show (the view model's job); null only records it. */
    private val onFocus: ((Boolean) -> ContactsListUiState)? = null,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<ContactsListAction>()
    lateinit var listState: LazyListState
    lateinit var searchState: TextFieldState
    val overlays = OverlayController()

    /** When true the harness plays the view model's part for select mode (enter, toggle, select all, exit). */
    var simulateSelect = false

    fun on(action: ContactsListAction) {
        actions += action
        if (action is ContactsListAction.QueryChanged) onQuery?.let { ui = it(action.text) }
        if (action is ContactsListAction.SearchFocusChanged) onFocus?.let { ui = it(action.focused) }
        if (simulateSelect) applySelect(action)
    }

    /** The state of select mode for [ids], with the words and actions the Rust core gives (`contacts.select_bar`). */
    fun selectState(ids: Set<Long>): SelectMode {
        val listed = ui.rows.map { it.id }
        return SelectMode(
            selected = ids.toImmutableSet(),
            title = UiText.Literal(if (ids.isEmpty()) "Select contacts" else "${ids.size} selected"),
            actions =
                if (ids.isEmpty()) {
                    persistentListOf()
                } else {
                    persistentListOf(
                        SelectAction.Share,
                        SelectAction.SaveToFile,
                        SelectAction.AddToGroup,
                        SelectAction.Star,
                        SelectAction.Delete,
                    )
                },
            allSelected = listed.isNotEmpty() && ids.containsAll(listed),
        )
    }

    private fun applySelect(action: ContactsListAction) {
        val now = ui.selectMode?.selected.orEmpty()
        val next: Set<Long>? =
            when (action) {
                is ContactsListAction.EnterSelect -> {
                    setOfNotNull(action.first)
                }

                ContactsListAction.ExitSelect -> {
                    null
                }

                is ContactsListAction.ToggleSelected -> {
                    if (action.id in now) now - action.id else now + action.id
                }

                ContactsListAction.ToggleSelectAll -> {
                    val all = ui.rows.map { it.id }.toSet()
                    if (now.containsAll(all)) emptySet() else all
                }

                else -> {
                    return
                }
            }
        ui = ui.copy(selectMode = next?.let(::selectState))
    }
}

/** The list inside the real shell: backdrop, tab bar, overlay host. Other tabs are empty. */
@Composable
internal fun HostedList(
    harness: ListHarness,
    haptics: RostyHaptics? = null,
) {
    val context = LocalContext.current
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots) }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    harness.listState = rememberLazyListState()
    harness.searchState = rememberTextFieldState()
    val content: @Composable () -> Unit = {
        RostyShell(
            navigator = navigator,
            tabs = tabs,
            chromeFor = { route -> chromeFor(route, harness.ui.selectMode != null) { it.resolve(context) } },
            overlays = harness.overlays,
            modifier = Modifier.fillMaxSize(),
            accessory =
                harness.ui.selectMode?.let { bar ->
                    { modifier, inline -> SelectToolbar(bar, harness::on, harness.overlays, modifier, inline) }
                },
        ) {
            entry<ContactsRoute.ContactsList> {
                ContactsListScreen(
                    state = harness.ui,
                    onAction = harness::on,
                    listState = harness.listState,
                    searchState = harness.searchState,
                )
            }
            entry<ContactsRoute.Favorites> {}
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
