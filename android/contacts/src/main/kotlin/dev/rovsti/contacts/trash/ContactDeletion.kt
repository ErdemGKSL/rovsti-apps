package dev.rovsti.contacts.trash

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
) {
    /** The confirmation words of the Rust core (`contacts.select_bar` `delete_confirm` for one contact). */
    suspend fun confirmText(): Confirm =
        (ops.selectBar(SelectBarRequest(count = 1, hasGroups = false)) as? Outcome.Ok)?.value?.deleteConfirm
            ?: Confirm("Delete this contact?", "It is removed from the phone and from its accounts.")

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
                        heading = confirm.title,
                        message = confirm.body,
                        actions =
                            persistentListOf(
                                AlertAction("Cancel", AlertRole.Cancel),
                                AlertAction("Delete", AlertRole.Destructive) { delete(overlays, id, onDeleted) },
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
                        overlays.showUndo("Deleted ${moved.name}", onUndo = { undo(overlays, moved.key) })
                    }

                    MoveResult.Gone -> {
                        onDeleted()
                    }

                    MoveResult.NoCopy -> {
                        overlays.showToast("Couldn't move it to the Trash, so it was not deleted")
                    }

                    MoveResult.Refused -> {
                        overlays.showToast("Couldn't delete this contact")
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
                    RestoreResult.Missing -> overlays.showToast("It is not in the Trash any more")
                    RestoreResult.Refused -> overlays.showToast("Couldn't bring it back, it is in the Trash")
                }
            }
        }
    }

    /** Overlays and pages are touched on the main thread only; [scope] itself runs off it. */
    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }
}
