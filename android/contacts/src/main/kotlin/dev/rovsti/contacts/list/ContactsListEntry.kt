package dev.rovsti.contacts.list

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.contacts.R
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.text.resolve
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.platform.files.ShareIntents
import dev.rovsti.platform.system.Handoff
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** The list's view model, owned by the activity (it outlives the page being pushed over and popped back to). */
@Composable
fun rememberContactsListViewModel(container: ContactsContainer): ContactsListViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    ContactsListViewModel(
                        book = container.book,
                        store = container.store,
                        ops = container.ops,
                        waMarks = container.waMarks,
                        contacts = container.contacts,
                        clipboard = container.clipboard,
                        shortcuts = container.shortcuts,
                        trash = container.trash,
                    )
                }
            }
        ViewModelProvider(owner, factory)[ContactsListViewModel::class.java]
    }
}

/** Runs the view model's effects: pushes routes, shows toasts and the confirmation, starts system intents. */
@Composable
fun ContactsListEffects(
    viewModel: ContactsListViewModel,
    navigator: TabNavigator<ContactsRoute>,
    overlays: OverlayController,
    context: Context,
    onEdit: ((id: Long?, seed: DraftSeed?) -> Unit)? = null,
    onSaveVcf: ((ContactsListEffect.SaveVcf) -> Unit)? = null,
) {
    val latestOverlays = rememberUpdatedState(overlays)
    val latestSave = rememberUpdatedState(onSaveVcf)
    val latestEdit = rememberUpdatedState(onEdit)
    LaunchedEffect(viewModel, navigator) {
        viewModel.effects.collect { effect ->
            val toast = { text: String -> latestOverlays.value.showToast(text) }
            when (effect) {
                is ContactsListEffect.OpenCard -> {
                    navigator.push(ContactsRoute.ContactCard(effect.id))
                }

                is ContactsListEffect.OpenEditor -> {
                    latestEdit.value?.invoke(effect.id, effect.seed)
                        ?: navigator.push(ContactsRoute.ContactEditor(effect.id, effect.seed))
                }

                is ContactsListEffect.Dial -> {
                    start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", effect.number, null))) { toast(it) }
                }

                is ContactsListEffect.Sms -> {
                    start(context, ShareIntents.sendSmsTo(effect.number)) { toast(it) }
                }

                is ContactsListEffect.Share -> {
                    start(context, ShareIntents.text(effect.text)) { toast(it) }
                }

                is ContactsListEffect.WhatsApp -> {
                    if (!Handoff.start(context, Handoff.ACTION_WA_CHAT, effect.jid, effect.name)) {
                        toast(context.getString(R.string.contacts_install_messages))
                    }
                }

                is ContactsListEffect.Toast -> {
                    toast(effect.text.resolve(context))
                }

                is ContactsListEffect.SaveVcf -> {
                    latestSave.value?.invoke(effect) ?: toast(context.getString(R.string.contacts_cant_save_here))
                }

                is ContactsListEffect.ConfirmDeleteSelected -> {
                    latestOverlays.value.present(
                        AlertEntry(
                            heading = effect.confirm.title,
                            message = effect.confirm.body.ifBlank { null },
                            actions =
                                persistentListOf(
                                    AlertAction(context.getString(R.string.contacts_cancel), AlertRole.Cancel),
                                    AlertAction(context.getString(R.string.editor_delete), AlertRole.Destructive) {
                                        viewModel.onAction(ContactsListAction.DeleteSelected)
                                    },
                                ),
                        ),
                    )
                }

                is ContactsListEffect.ChooseGroup -> {
                    latestOverlays.value.present(
                        ActionSheetEntry(
                            title = context.getString(R.string.contacts_add_to_group),
                            actions =
                                effect.groups
                                    .map { group ->
                                        ActionSheetAction(group.title) {
                                            viewModel.onAction(ContactsListAction.AddSelectedToGroup(group.id))
                                        }
                                    }.toImmutableList(),
                        ),
                    )
                }

                is ContactsListEffect.Deleted -> {
                    latestOverlays.value.showUndo(effect.notice.resolve(context), onUndo = {
                        viewModel.onAction(ContactsListAction.UndoDelete(effect.keys))
                    })
                }

                is ContactsListEffect.ConfirmClearViewed -> {
                    latestOverlays.value.present(
                        AlertEntry(
                            heading = effect.confirm.title.resolve(context),
                            message =
                                effect.confirm.body
                                    .resolve(context)
                                    .ifBlank { null },
                            actions =
                                persistentListOf(
                                    AlertAction(context.getString(R.string.contacts_cancel), AlertRole.Cancel),
                                    AlertAction(context.getString(R.string.contacts_clear), AlertRole.Destructive) {
                                        viewModel.onAction(ContactsListAction.ClearViewed)
                                    },
                                ),
                        ),
                    )
                }
            }
        }
    }
}

private fun start(
    context: Context,
    intent: Intent,
    onFailure: (String) -> Unit,
) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        onFailure(context.getString(R.string.contacts_no_app))
    }
}

private fun Context.findActivity(): ComponentActivity {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("No ComponentActivity in $this")
}
