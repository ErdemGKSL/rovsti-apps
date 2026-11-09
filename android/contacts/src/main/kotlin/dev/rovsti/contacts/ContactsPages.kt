package dev.rovsti.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.editor.EditorOutcome
import dev.rovsti.contacts.editor.contactEditorModel
import dev.rovsti.contacts.editor.presentContactEditor
import dev.rovsti.contacts.list.ContactsListEffects
import dev.rovsti.contacts.list.ContactsListScreen
import dev.rovsti.contacts.list.rememberContactsListViewModel
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.transition.Tab
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.shell.permissions.PermissionGate
import dev.rovsti.shell.permissions.PermissionRow
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the Contacts pages. */
object ContactsTags {
    const val ListPage = "contacts-list-page"
    const val EmptyBook = "contacts-empty-book"
    const val PermissionSection = "contacts-permission"
    const val ComingPage = "contacts-coming-page"
    const val SettingsPage = "contacts-settings-page"
    const val IntakePage = "contacts-intake-page"
}

/**
 * The Contacts tab root (E4.2): the [ContactsListScreen] over its view model. Nothing is asked at launch: while the book
 * may not be read the screen shows the [PermissionRow] (the gate explains first, in a sheet, and the system dialog only
 * follows "Continue"). Effects of the view model become navigation, toasts and system intents here.
 */
@Composable
fun ContactsListPage(
    container: ContactsContainer,
    navigator: TabNavigator<ContactsRoute>,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(navigator, listState) {
        navigator.scrollToTop.collect { if (it.tab == ContactsTab) listState.animateScrollToItem(0) }
    }
    PermissionGate(PermissionGroup.CONTACTS)
    val viewModel = rememberContactsListViewModel(container)
    val pending by container.pendingSearch.collectAsState()
    val state by viewModel.state.collectAsState()
    val searchState = rememberTextFieldState()
    // A relation the card could not open: its name lands in the search field (C46).
    LaunchedEffect(pending) {
        pending?.let {
            searchState.setTextAndPlaceCursorAtEnd(it)
            container.pendingSearch.value = null
        }
    }
    val overlays = LocalOverlay.current
    val context = LocalContext.current
    ContactsListEffects(viewModel, navigator, overlays, context) { id, seed ->
        presentContactEditor(
            contactEditorModel(container, id, seed),
            overlays,
            container.scope,
            outcome = EditorOutcome { saved, created -> if (created) navigator.push(ContactsRoute.ContactCard(saved)) },
        )
    }
    ContactsListScreen(
        state = state,
        onAction = viewModel::onAction,
        listState = listState,
        searchState = searchState,
        gate = {
            InsetGroupedSection(modifier = Modifier.testTag(ContactsTags.PermissionSection)) {
                row { PermissionRow(PermissionGroup.CONTACTS) }
            }
        },
    )
}

/** An empty state that fills the page below the title and ends above the tab bar, so nothing sits under the bar. */
private fun LazyListScope.emptyItem(
    key: String,
    icon: ImageVector,
    title: Int,
    message: Int,
    tag: String,
) {
    item(key = key, contentType = "empty") {
        val bottom = LocalContentInsets.current.calculateBottomPadding()
        Box(
            Modifier
                .fillParentMaxHeight()
                .fillMaxWidth()
                .padding(bottom = bottom)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(icon = icon, title = stringResource(title), message = stringResource(message))
        }
    }
}

/** Favorites and Groups roots until E4.5 and E4.7: the title over an empty page. */
@Composable
fun TabPlaceholderPage(
    title: String,
    navigator: TabNavigator<ContactsRoute>,
    tab: Tab,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(navigator, listState) {
        navigator.scrollToTop.collect { if (it.tab == tab) listState.animateScrollToItem(0) }
    }
    LargeTitleScaffold(title = title, state = listState, actions = persistentListOf()) {
        emptyItem(
            key = "empty",
            icon = if (tab == FavoritesTab) RovstiIcons.Star else RovstiIcons.Folder,
            title = R.string.coming_title,
            message = R.string.coming_message,
            tag = ContactsTags.ComingPage,
        )
    }
}

/** A pushed page whose screen is a later story: the route works (links, restore, back), the body says so. */
@Composable
fun ComingPage(
    title: String,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    LargeTitleScaffold(
        title = title,
        state = listState,
        actions = persistentListOf(),
        navigation = NavigationButton(onClick = onBack),
    ) {
        emptyItem(
            key = "empty",
            icon = RovstiIcons.Document,
            title = R.string.coming_title,
            message = R.string.coming_message,
            tag = ContactsTags.ComingPage,
        )
    }
}

/** The page SEND and VIEW of a vCard land on until the import screen (E4.10) exists. */
@Composable
fun IntakePlaceholderPage(onBack: () -> Unit) {
    val listState = rememberLazyListState()
    LargeTitleScaffold(
        title = stringResource(R.string.intake_placeholder_title),
        state = listState,
        actions = persistentListOf(),
        navigation = NavigationButton(onClick = onBack),
        modifier = Modifier.testTag(ContactsTags.IntakePage),
    ) {
        emptyItem(
            key = "empty",
            icon = RovstiIcons.Download,
            title = R.string.intake_placeholder_title,
            message = R.string.intake_placeholder_message,
            tag = ContactsTags.ComingPage,
        )
    }
}
