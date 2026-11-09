package dev.rovsti.contacts.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.SavedStateHandle
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** What the editor tells the page that opened it. */
fun interface EditorOutcome {
    /** The contact was saved as [id]; [created] is true for a new contact. */
    fun saved(
        id: Long,
        created: Boolean,
    )
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
    model: ContactEditorViewModel,
    overlays: OverlayController,
    undoScope: CoroutineScope,
    outcome: EditorOutcome = EditorOutcome { _, _ -> },
    onClosed: () -> Unit = {},
): SheetEntry {
    val entry =
        SheetEntry(
            title = "Contact editor",
            detents = persistentListOf(Detent.Large),
            initialDetent = Detent.Large,
            onDismissed = onClosed,
            canDismiss = { !model.state.value.dirty },
            onDismissBlocked = { model.onAction(EditorAction.RequestClose) },
        ) { sheet ->
            ContactEditorSheet(model, sheet, undoScope, outcome)
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
) {
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
                            message = "Your changes will be lost.",
                            actions =
                                persistentListOf(
                                    ActionSheetAction("Discard Changes", destructive = true) {
                                        model.onAction(EditorAction.Discard)
                                    },
                                ),
                            cancelLabel = "Keep Editing",
                        ),
                    )
                }

                is EditorEffect.Saved -> {
                    latestOutcome.value.saved(effect.id, created = model.state.value.isNew)
                    if (effect.undoable) {
                        overlays.showUndo(effect.notice, onUndo = {
                            undoScope.launch {
                                val done = model.undo()
                                overlays.showToast(if (done) "Change undone" else "Can't undo this change")
                            }
                        })
                    } else {
                        overlays.showToast(effect.notice)
                    }
                }

                is EditorEffect.Invalid -> {
                    invalid.tryEmit(effect.issueKey)
                }

                is EditorEffect.Toast -> {
                    overlays.showToast(effect.text)
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ContactEditorScreen(state, model::onAction, invalid = invalid)
    }
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
    val overlays = LocalOverlay.current
    val model = remember(route) { contactEditorModel(container, route.id, route.seed) }
    LaunchedEffect(model) {
        presentContactEditor(model, overlays, container.scope, outcome, onClosed)
    }
}
