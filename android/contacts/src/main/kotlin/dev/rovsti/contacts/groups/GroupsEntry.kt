package dev.rovsti.contacts.groups

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.GroupsTab
import dev.rovsti.contacts.R
import dev.rovsti.contacts.findComponentActivity
import dev.rovsti.contacts.startSafely
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.text.UiText
import dev.rovsti.design.text.resolve
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.platform.files.ExportDocument
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.ShareIntents
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.shell.permissions.PermissionGate
import kotlinx.coroutines.launch

/** The Groups view model, owned by the activity (it outlives a group page being pushed over the tab and popped back). */
@Composable
fun rememberGroupsViewModel(container: ContactsContainer): GroupsViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer { GroupsViewModel(container.book, container.contacts, container.ops, container.store) }
            }
        ViewModelProvider(owner, factory)[GroupsViewModel::class.java]
    }
}

/** The destination of the Groups tab root. */
@Composable
fun GroupsPage(
    container: ContactsContainer,
    navigator: TabNavigator<ContactsRoute>,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(navigator, listState) {
        navigator.scrollToTop.collect { if (it.tab == GroupsTab) listState.animateScrollToItem(0) }
    }
    PermissionGate(PermissionGroup.CONTACTS)
    val viewModel = rememberGroupsViewModel(container)
    val state by viewModel.state.collectAsState()
    val overlays = LocalOverlay.current
    val heading = stringResource(R.string.groups_new)
    val save = stringResource(R.string.groups_save)
    val cancel = stringResource(R.string.picker_cancel)
    val placeholder = stringResource(R.string.groups_name_placeholder)
    val latest = rememberUpdatedState(viewModel)
    LaunchedEffect(viewModel, navigator) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is GroupsEffect.AskName -> {
                    overlays.presentNameAlert(
                        heading,
                        effect.initial,
                        effect.error,
                        save,
                        cancel,
                        placeholder,
                    ) { name ->
                        latest.value.onAction(GroupsAction.Create(name))
                    }
                }

                is GroupsEffect.OpenGroup -> {
                    navigator.push(ContactsRoute.GroupPage(effect.id))
                }

                is GroupsEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }
            }
        }
    }
    GroupsScreen(
        state = state,
        onAction = viewModel::onAction,
        onNew = viewModel::askNewName,
        listState = listState,
    )
}

/** The destination of `ContactsRoute.GroupPage`. */
@Composable
fun GroupPageDestination(
    container: ContactsContainer,
    id: Long,
    navigator: TabNavigator<ContactsRoute>,
    onBack: () -> Unit,
) {
    val model =
        remember(container, id) {
            GroupPageViewModel(id, container.book, container.contacts, container.ops, container.store, container.scope)
        }
    val state by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<GroupPageEffect.SaveVcf?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ExportDocument("text/x-vcard")) { result ->
            val file = pending ?: return@rememberLauncherForActivityResult
            pending = null
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
    GroupPageEffects(model, navigator, overlays, context, onBack) { effect ->
        pending = effect
        launcher.launch(effect.fileName)
    }
    val listState = rememberLazyListState()
    val ready = state as? GroupPageState.Ready
    LaunchedEffect(state) { if (state is GroupPageState.Gone) onBack() }
    if (ready != null) {
        GroupPageScreen(
            page = ready.page,
            onAction = model::onAction,
            onRename = model::askRename,
            onBack = onBack,
            listState = listState,
            modifier = Modifier,
        )
    }
}

@Composable
private fun GroupPageEffects(
    model: GroupPageViewModel,
    navigator: TabNavigator<ContactsRoute>,
    overlays: OverlayController,
    context: Context,
    onBack: () -> Unit,
    onSave: (GroupPageEffect.SaveVcf) -> Unit,
) {
    val heading = stringResource(R.string.group_rename_heading)
    val save = stringResource(R.string.groups_save)
    val cancel = stringResource(R.string.picker_cancel)
    val placeholder = stringResource(R.string.groups_name_placeholder)
    val latestSave = rememberUpdatedState(onSave)
    LaunchedEffect(model, navigator) {
        model.effects.collect { effect ->
            val toast: (String) -> Unit = { text -> overlays.showToast(text) }
            when (effect) {
                is GroupPageEffect.OpenCard -> {
                    navigator.push(ContactsRoute.ContactCard(effect.id))
                }

                is GroupPageEffect.AskName -> {
                    overlays.presentNameAlert(
                        heading,
                        effect.initial,
                        effect.error,
                        save,
                        cancel,
                        placeholder,
                    ) { name ->
                        model.onAction(GroupPageAction.Rename(name))
                    }
                }

                is GroupPageEffect.Sms -> {
                    startSafely(context, ShareIntents.sendSmsTo(effect.address), toast)
                }

                is GroupPageEffect.Email -> {
                    startSafely(context, ShareIntents.sendEmailTo(effect.address), toast)
                }

                is GroupPageEffect.SaveVcf -> {
                    latestSave.value(effect)
                }

                is GroupPageEffect.Toast -> {
                    toast(effect.text.resolve(context))
                }

                GroupPageEffect.Close -> {
                    onBack()
                }
            }
        }
    }
}
