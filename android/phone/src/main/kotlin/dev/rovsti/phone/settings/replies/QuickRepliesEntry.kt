package dev.rovsti.phone.settings.replies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.phone.R
import kotlinx.collections.immutable.persistentListOf

/** Presents the add / edit sheet of reply [index] (null: a new one) over the page. */
internal fun presentReplyEdit(
    overlays: OverlayController,
    title: String,
    ui: QuickRepliesUi,
    index: Int?,
    onAction: (QuickRepliesAction) -> Unit,
) {
    val initial = index?.let { ui.replies.getOrNull(it) }.orEmpty()
    overlays.present(
        SheetEntry(title = title, detents = persistentListOf(Detent.Large)) { sheet ->
            ReplyEditContent(
                initial = initial,
                maxChars = ui.maxChars,
                counterAt = ui.counterAt,
                onSave = { text ->
                    onAction(if (index == null) QuickRepliesAction.Add(text) else QuickRepliesAction.Edit(index, text))
                    sheet.dismiss()
                },
                onCancel = { sheet.dismiss() },
                onDelete =
                    index?.let {
                        {
                            onAction(QuickRepliesAction.Delete(it))
                            sheet.dismiss()
                        }
                    },
            )
        },
    )
}

/** The Quick replies route: the screen over its view model, the edit sheet and the Undo pills. */
@Composable
fun QuickRepliesEntry(
    model: QuickRepliesViewModel,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val ui by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val latest = rememberUpdatedState(ui)
    val deleted = stringResource(R.string.qr_deleted)
    val restored = stringResource(R.string.qr_restored)
    val rejected = stringResource(R.string.qr_rejected)
    val undo = stringResource(R.string.set_undo)
    LaunchedEffect(model) {
        model.effect.collect { effect ->
            when (effect) {
                is QuickRepliesEffect.Deleted -> {
                    overlays.showUndo(deleted, { model.onAction(QuickRepliesAction.Undo(effect.previous)) }, undo)
                }

                is QuickRepliesEffect.DefaultsRestored -> {
                    overlays.showUndo(restored, { model.onAction(QuickRepliesAction.Undo(effect.previous)) }, undo)
                }

                QuickRepliesEffect.Rejected -> {
                    overlays.showToast(rejected)
                }
            }
        }
    }
    val titleNew = stringResource(R.string.qr_sheet_new)
    val titleEdit = stringResource(R.string.qr_sheet_edit)
    QuickRepliesScreen(
        ui = ui,
        onAction = model::onAction,
        onEdit = { index ->
            presentReplyEdit(overlays, if (index == null) titleNew else titleEdit, latest.value, index, model::onAction)
        },
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}
