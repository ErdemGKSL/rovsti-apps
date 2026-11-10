package dev.rovsti.contacts.editor

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.SavedStateHandle
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.photo.ContactPhotoModel
import dev.rovsti.contacts.photo.PhotoEffect
import dev.rovsti.contacts.photo.PhotoMenuActions
import dev.rovsti.contacts.photo.showPhotoCrop
import dev.rovsti.contacts.trash.ContactDeletion
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.text.UiText
import dev.rovsti.design.text.resolve
import dev.rovsti.platform.files.CapturePhoto
import dev.rovsti.platform.files.PickPhoto
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the editor tells the page that opened it. */
fun interface EditorOutcome {
    /** The contact was saved as [id]; [created] is true for a new contact. */
    fun saved(
        id: Long,
        created: Boolean,
    )
}

/**
 * What the editor needs besides its model: the photo of the contact ([photo], with the stored photo [photoUri] and the
 * WhatsApp [waJid] it may take a picture from) and the Delete Contact flow ([deletion], for contact [id]).
 */
class EditorExtras(
    val id: Long?,
    val photo: ContactPhotoModel,
    val photoUri: StateFlow<String?>,
    val waJid: StateFlow<String?>,
    val deletion: ContactDeletion?,
)

/** The extras of contact [id] (null: a new contact) over [container]. */
fun contactEditorExtras(
    container: ContactsContainer,
    id: Long?,
): EditorExtras {
    val photo =
        ContactPhotoModel(
            contactId = id,
            repo = container.contacts,
            ops = container.ops,
            files = container.files,
            waPhotos = container.waPhotos,
            cacheDir = container.context.cacheDir,
            scope = container.scope,
        )
    val photoUri: StateFlow<String?> =
        if (id == null) {
            MutableStateFlow(null)
        } else {
            container.book.book
                .map { book -> (book as? Book.Ready)?.contacts?.firstOrNull { it.id == id }?.photoUri }
                .stateIn(container.scope, SharingStarted.Eagerly, null)
        }
    val waJid: StateFlow<String?> =
        if (id == null) {
            MutableStateFlow(null)
        } else {
            container.waMarks.marks
                .map { it[id] }
                .stateIn(container.scope, SharingStarted.Eagerly, null)
        }
    return EditorExtras(id, photo, photoUri, waJid, container.deletion)
}

/** The model of one open of the editor over [container]; [saved] keeps the draft across a process death. */
fun contactEditorModel(
    container: ContactsContainer,
    id: Long?,
    seed: DraftSeed?,
    saved: SavedStateHandle = SavedStateHandle(),
): ContactEditorViewModel =
    ContactEditorViewModel(
        id = id,
        seed = seed,
        saved = saved,
        repo = container.contacts,
        ops = container.ops,
        store = container.store,
        scope = container.scope,
    )

/**
 * Opens the editor as a page sheet: a modal [SheetEntry] on the Large detent over the page that asked (which steps
 * back), with Cancel / title / Done. A swipe down, Back or a tap outside while there are unsaved changes is refused (the
 * sheet rubber-bands back) and asks "Discard Changes" / "Keep Editing" instead.
 */
fun presentContactEditor(
    context: Context,
    model: ContactEditorViewModel,
    overlays: OverlayController,
    undoScope: CoroutineScope,
    outcome: EditorOutcome = EditorOutcome { _, _ -> },
    onClosed: () -> Unit = {},
    extras: EditorExtras? = null,
): SheetEntry {
    val entry =
        SheetEntry(
            title = context.getString(R.string.contacts_contact_editor),
            detents = persistentListOf(Detent.Large),
            initialDetent = Detent.Large,
            onDismissed = onClosed,
            canDismiss = { !model.state.value.dirty },
            onDismissBlocked = { model.onAction(EditorAction.RequestClose) },
        ) { sheet ->
            ContactEditorSheet(model, sheet, undoScope, outcome, extras)
        }
    overlays.present(entry)
    return entry
}

/** The editor over its model, with the model's effects wired to the sheet and the overlays. */
@Composable
private fun ContactEditorSheet(
    model: ContactEditorViewModel,
    sheet: SheetState,
    undoScope: CoroutineScope,
    outcome: EditorOutcome,
    extras: EditorExtras?,
) {
    val context = LocalContext.current
    val strContactsChangesLost = stringResource(R.string.contacts_changes_lost)
    val strContactsDiscardChanges = stringResource(R.string.contacts_discard_changes)
    val strContactsKeepEditing = stringResource(R.string.contacts_keep_editing)
    val strContactsChangeUndone = stringResource(R.string.contacts_change_undone)
    val strContactsCantUndoChange = stringResource(R.string.contacts_cant_undo_change)
    val state by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val focus = LocalFocusManager.current
    val invalid = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    val latestOutcome = rememberUpdatedState(outcome)
    LaunchedEffect(model) {
        model.effects.collect { effect ->
            when (effect) {
                EditorEffect.Close -> {
                    focus.clearFocus()
                    sheet.dismiss()
                }

                EditorEffect.ConfirmDiscard -> {
                    focus.clearFocus()
                    overlays.present(
                        ActionSheetEntry(
                            message = strContactsChangesLost,
                            actions =
                                persistentListOf(
                                    ActionSheetAction(
                                        strContactsDiscardChanges,
                                        destructive = true,
                                    ) {
                                        model.onAction(EditorAction.Discard)
                                    },
                                ),
                            cancelLabel = strContactsKeepEditing,
                        ),
                    )
                }

                is EditorEffect.Saved -> {
                    // A picture a new contact was holding becomes its photo now that it has an id.
                    extras?.let { held -> undoScope.launch { held.photo.applyPending(effect.id) } }
                    latestOutcome.value.saved(effect.id, created = model.state.value.isNew)
                    if (effect.undoable) {
                        overlays.showUndo(effect.notice.resolve(context), onUndo = {
                            undoScope.launch {
                                val done = model.undo()
                                overlays.showToast(
                                    if (done) {
                                        strContactsChangeUndone
                                    } else {
                                        strContactsCantUndoChange
                                    },
                                )
                            }
                        })
                    } else {
                        overlays.showToast(effect.notice.resolve(context))
                    }
                }

                is EditorEffect.Invalid -> {
                    invalid.tryEmit(effect.issueKey)
                }

                is EditorEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }
            }
        }
    }
    val photo = extras?.let { rememberEditorPhoto(it, overlays) }
    val deleteId = extras?.id
    val onDelete: (() -> Unit)? =
        if (extras?.deletion != null && deleteId != null) {
            { extras.deletion.ask(overlays, deleteId) { sheet.dismiss() } }
        } else {
            null
        }
    Column(Modifier.fillMaxSize()) {
        ContactEditorScreen(state, model::onAction, invalid = invalid, photo = photo, onDelete = onDelete)
    }
}

/**
 * The editor's photo head: launchers for the picker and the camera (neither needs a permission), the menu actions, the
 * crop page while the model has a picture open, and the model's notices as toasts.
 */
@Composable
private fun rememberEditorPhoto(
    extras: EditorExtras,
    overlays: OverlayController,
): EditorPhoto {
    val context = LocalContext.current
    val photo = extras.photo
    val ui by photo.state.collectAsState()
    val stored by extras.photoUri.collectAsState()
    val jid by extras.waJid.collectAsState()
    val pick = rememberLauncherForActivityResult(remember { PickPhoto() }) { photo.picked(it) }
    val capture = rememberLauncherForActivityResult(remember { CapturePhoto() }) { photo.picked(it) }
    val latestJid = rememberUpdatedState(jid)
    val actions =
        remember(photo) {
            PhotoMenuActions(
                choose = { pick.launch(Unit) },
                take = { capture.launch(Unit) },
                whatsApp = { latestJid.value?.let(photo::useWhatsApp) },
                remove = photo::remove,
            )
        }
    LaunchedEffect(photo) {
        photo.effects.collect { effect ->
            if (effect is PhotoEffect.Toast) overlays.showToast(effect.text.resolve(context))
        }
    }
    LaunchedEffect(ui.crop) {
        ui.crop?.let { session ->
            overlays.showPhotoCrop(session, onChosen = photo::cropChosen, onCancel = photo::cropCancelled)
        }
    }
    return EditorPhoto(stored, ui.pendingPath, jid, ui.busy, actions)
}

/**
 * The destination of [ContactsRoute.ContactEditor] when it is reached as a route (a link): an empty page that presents
 * the editor sheet and leaves again once the sheet is gone. The card and the list present the sheet over themselves
 * instead ([presentContactEditor]), so they stay visible behind it.
 */
@Composable
fun ContactEditorPage(
    container: ContactsContainer,
    route: ContactsRoute.ContactEditor,
    outcome: EditorOutcome,
    onClosed: () -> Unit,
) {
    val context = LocalContext.current
    val overlays = LocalOverlay.current
    val model = remember(route) { contactEditorModel(container, route.id, route.seed) }
    LaunchedEffect(model) {
        presentContactEditor(
            context,
            model,
            overlays,
            container.scope,
            outcome,
            onClosed,
            extras = contactEditorExtras(container, route.id),
        )
    }
}
