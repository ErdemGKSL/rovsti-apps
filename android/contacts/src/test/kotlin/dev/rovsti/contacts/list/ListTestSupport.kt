package dev.rovsti.contacts.list

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
import dev.rovsti.contacts.ContactsRoots
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.ContactsTabs
import dev.rovsti.contacts.chromeFor
import dev.rovsti.contacts.contactsTabItems
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.haptics.RovstiHaptics
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.shell.RovstiShell

/** Holds the page state the way the view model would and records what the screen asks for. */
internal class ListHarness(
    initial: ContactsListUiState,
    /** Maps a typed query to the state to show (the view model's job); null keeps the state. */
    private val onQuery: ((String) -> ContactsListUiState)? = null,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<ContactsListAction>()
    lateinit var listState: LazyListState
    lateinit var searchState: TextFieldState
    val overlays = OverlayController()

    fun on(action: ContactsListAction) {
        actions += action
        if (action is ContactsListAction.QueryChanged) onQuery?.let { ui = it(action.text) }
    }
}

/** The list inside the real shell: backdrop, tab bar, overlay host. Other tabs are empty. */
@Composable
internal fun HostedList(
    harness: ListHarness,
    haptics: RovstiHaptics? = null,
) {
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots) }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    harness.listState = rememberLazyListState()
    harness.searchState = rememberTextFieldState()
    val content: @Composable () -> Unit = {
        RovstiShell(
            navigator = navigator,
            tabs = tabs,
            chromeFor = ::chromeFor,
            overlays = harness.overlays,
            modifier = Modifier.fillMaxSize(),
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
        CompositionLocalProvider(LocalRovstiHaptics provides haptics) { content() }
    } else {
        content()
    }
}
