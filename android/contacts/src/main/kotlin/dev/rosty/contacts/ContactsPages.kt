package dev.rosty.contacts

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.contacts.R
import dev.rosty.contacts.editor.EditorOutcome
import dev.rosty.contacts.editor.contactEditorExtras
import dev.rosty.contacts.editor.contactEditorModel
import dev.rosty.contacts.editor.presentContactEditor
import dev.rosty.contacts.intake.rememberImportActions
import dev.rosty.contacts.list.ContactsListAction
import dev.rosty.contacts.list.ContactsListEffect
import dev.rosty.contacts.list.ContactsListEffects
import dev.rosty.contacts.list.ContactsListScreen
import dev.rosty.contacts.list.rememberContactsListViewModel
import dev.rosty.contacts.me.MeAction
import dev.rosty.contacts.me.MeEffect
import dev.rosty.contacts.me.rememberMeViewModel
import dev.rosty.contacts.people.presentContactChooser
import dev.rosty.design.chrome.LargeTitleScaffold
import dev.rosty.design.chrome.LocalContentInsets
import dev.rosty.design.chrome.NavigationButton
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.showToast
import dev.rosty.design.text.resolve
import dev.rosty.design.text.text
import dev.rosty.design.transition.Tab
import dev.rosty.design.transition.TabNavigator
import dev.rosty.platform.files.ExportDocument
import dev.rosty.platform.files.FileResult
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.system.permissions.PermissionState
import dev.rosty.shell.permissions.PermissionGate
import dev.rosty.shell.permissions.PermissionRow
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

/** Test tags of the Contacts pages. */
object ContactsTags {
    const val ListPage = "contacts-list-page"
    const val EmptyBook = "contacts-empty-book"
    const val PermissionSection = "contacts-permission"
    const val SettingsPage = "contacts-settings-page"
    const val ImportFile = "contacts-settings-import-file"
    const val PasteContact = "contacts-settings-paste-contact"
    const val ScanQr = "contacts-settings-scan-qr"
    const val ImportCsv = "contacts-settings-import-csv"
    const val ExportCsv = "contacts-settings-export-csv"
    const val FindDuplicates = "contacts-settings-find-duplicates"
    const val CleanUp = "contacts-settings-clean-up"
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
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var pendingVcf by remember { mutableStateOf<ContactsListEffect.SaveVcf?>(null) }
    val saveVcf =
        rememberLauncherForActivityResult(ExportDocument("text/x-vcard")) { result ->
            val file = pendingVcf ?: return@rememberLauncherForActivityResult
            pendingVcf = null
            val uri = (result as? FileResult.Ok)?.uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val written = container.files.writeExport(uri, text = file.text)
                overlays.showToast(
                    if (written is FileResult.Ok) {
                        resources.getString(R.string.contacts_saved_file, file.fileName)
                    } else {
                        resources.getString(R.string.contacts_cant_save_file)
                    },
                )
            }
        }
    ContactsListEffects(
        viewModel,
        navigator,
        overlays,
        context,
        onEdit = { id, seed ->
            presentContactEditor(
                context,
                contactEditorModel(container, id, seed),
                overlays,
                container.scope,
                outcome =
                    EditorOutcome { saved, created ->
                        if (created) navigator.push(ContactsRoute.ContactCard(saved))
                    },
                extras = contactEditorExtras(container, id),
            )
        },
        onSaveVcf = {
            pendingVcf = it
            saveVcf.launch(it.fileName)
        },
    )
    val import = rememberImportActions(container, navigator, overlays)
    // My card and the emergency row come from the Me model (E4.14); a book without contacts has nothing to point at.
    val me = rememberMeViewModel(container)
    val meState by me.state.collectAsState()
    LaunchedEffect(me) { me.effects.collect { if (it is MeEffect.Toast) overlays.showToast(it.text.resolve(context)) } }
    val shown =
        remember(state, meState) {
            if (state.rows.isEmpty()) {
                state
            } else {
                state.copy(
                    myCard = meState.myCard,
                    emergencyDetail = meState.myCard?.let { meState.emergencyDetail.resolve(context) },
                )
            }
        }
    val onAction: (ContactsListAction) -> Unit = { action ->
        when (action) {
            ContactsListAction.OpenMyCard -> {
                val mine = meState.myCard?.contactId
                if (mine != null) {
                    navigator.push(ContactsRoute.ContactCard(mine))
                } else {
                    scope.launch {
                        overlays.presentContactChooser(
                            resources.getString(R.string.contacts_set_my_card),
                            me.choices(),
                        ) { id ->
                            me.onAction(MeAction.SetMyCard(id))
                        }
                    }
                }
            }

            ContactsListAction.OpenEmergency -> {
                navigator.push(ContactsRoute.Emergency)
            }

            ContactsListAction.OpenRecent -> {
                navigator.push(ContactsRoute.Recent)
            }

            else -> {
                viewModel.onAction(action)
            }
        }
    }
    ContactsListScreen(
        state = shown,
        onAction = onAction,
        listState = listState,
        searchState = searchState,
        onPasteContact = import.pasteContact,
        onScanQr = import.scanQr,
        gate = {
            InsetGroupedSection(modifier = Modifier.testTag(ContactsTags.PermissionSection)) {
                row { PermissionRow(PermissionGroup.CONTACTS) }
            }
        },
    )
}
