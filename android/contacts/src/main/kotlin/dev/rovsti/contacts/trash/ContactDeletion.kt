package dev.rovsti.contacts.trash

import android.content.Context
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.R
import dev.rovsti.contacts.UiConfirm
import dev.rovsti.contacts.toUi
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.Confirm
import dev.rovsti.core.model.contacts.SelectBarRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.text.UiText
import dev.rovsti.design.text.resolve
import dev.rovsti.design.text.text
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deleting one contact from the card or the editor (E4.5): ask, move to the Trash, delete, close the page, and offer
 * Undo on the pill for five seconds. Undo takes the copy out of the Trash and inserts it again (a new contact with the
 * same fields). [scope] outlives the page that asked, so a delete finishes and its pill works after the page is gone.
 */
class ContactDeletion(
    private val trash: ContactTrash,
    private val ops: ContactsOps,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    /** The confirmation words of the Rust core (`contacts.select_bar` `delete_confirm` for one contact). */
    suspend fun confirmText(): UiConfirm =
        (ops.selectBar(SelectBarRequest(count = 1, hasGroups = false)) as? Outcome.Ok)?.value?.deleteConfirm?.toUi()
            ?: UiConfirm(
                UiText.Res(R.string.contacts_delete_this_contact),
                UiText.Res(R.string.contacts_delete_contact_body),
            )

    /**
     * Asks "Delete this contact?" in an alert. Delete runs the delete; [onDeleted] then closes the page that showed the
     * contact and the undo pill is shown. [overlays] hosts the alert and the pill.
     */
    fun ask(
        overlays: OverlayController,
        id: Long,
        onDeleted: () -> Unit,
    ) {
        scope.launch {
            val confirm = confirmText()
            onMain {
                overlays.present(
                    AlertEntry(
                        heading = confirm.title.resolve(context),
                        message = confirm.body.resolve(context),
                        actions =
                            persistentListOf(
                                AlertAction(context.getString(R.string.contacts_cancel), AlertRole.Cancel),
                                AlertAction(
                                    context.getString(R.string.editor_delete),
                                    AlertRole.Destructive,
                                ) { delete(overlays, id, onDeleted) },
                            ),
                    ),
                )
            }
        }
    }

    /** Moves the contact to the Trash and deletes it; the pill follows. */
    fun delete(
        overlays: OverlayController,
        id: Long,
        onDeleted: () -> Unit,
    ) {
        scope.launch {
            val moved = trash.delete(id)
            onMain {
                when (moved) {
                    is MoveResult.Done -> {
                        onDeleted()
                        overlays.showUndo(
                            context.getString(R.string.contacts_deleted_name, moved.name.resolve(context)),
                            onUndo = { undo(overlays, moved.key) },
                        )
                    }

                    MoveResult.Gone -> {
                        onDeleted()
                    }

                    MoveResult.NoCopy -> {
                        overlays.showToast(context.getString(R.string.contacts_cant_trash))
                    }

                    MoveResult.Refused -> {
                        overlays.showToast(context.getString(R.string.contacts_cant_delete))
                    }
                }
            }
        }
    }

    private fun undo(
        overlays: OverlayController,
        key: Long,
    ) {
        scope.launch {
            val restored = trash.restore(listOf(key))
            onMain {
                when (restored) {
                    is RestoreResult.Done -> overlays.showToast(restored.notice)
                    RestoreResult.Missing -> overlays.showToast(context.getString(R.string.contacts_not_in_trash))
                    RestoreResult.Refused -> overlays.showToast(context.getString(R.string.contacts_cant_restore_trash))
                }
            }
        }
    }

    /** Overlays and pages are touched on the main thread only; [scope] itself runs off it. */
    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }
}
