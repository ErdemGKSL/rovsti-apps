package dev.rosty.messages.settings.replies

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showUndo
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.messages.settings.findComponentActivity
import kotlinx.collections.immutable.persistentListOf

/** The Quick replies page's view model, kept by the activity. */
@Composable
fun rememberQuickRepliesViewModel(container: MessagesContainer): QuickRepliesViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory = viewModelFactory { initializer { QuickRepliesViewModel(container.store, container.chat) } }
        ViewModelProvider(owner, factory)[QuickRepliesViewModel::class.java]
    }
}

/** Presents the add / edit sheet of reply [index] (null: a new one). */
internal fun presentReplyEditor(
    overlays: OverlayController,
    res: Resources,
    ui: QuickRepliesUi,
    index: Int?,
    onAction: (QuickRepliesAction) -> Unit,
) {
    val initial = index?.let { ui.replies.getOrNull(it) }.orEmpty()
    val title = res.getString(if (index == null) R.string.quick_replies_new else R.string.quick_replies_edit)
    overlays.present(
        SheetEntry(title = title, detents = persistentListOf(Detent.Large)) { sheet ->
            ReplyEditContent(
                initial = initial,
                maxChars = ui.maxChars,
                onSave = { text, answer ->
                    val done: (ReplyProblem?) -> Unit = { problem ->
                        answer(problem)
                        if (problem == null) sheet.dismiss()
                    }
                    onAction(
                        if (index == null) {
                            QuickRepliesAction.Add(text, done)
                        } else {
                            QuickRepliesAction.Edit(index, text, done)
                        },
                    )
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

/** Settings, Quick replies: the page over its view model, the edit sheet and the Undo pill of a delete. */
@Composable
fun QuickRepliesEntry(
    container: MessagesContainer,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val model = rememberQuickRepliesViewModel(container)
    val ui by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val res = LocalResources.current
    val latest = rememberUpdatedState(ui)
    LaunchedEffect(model) {
        model.effect.collect { effect ->
            when (effect) {
                is QuickRepliesEffect.Deleted -> {
                    overlays.showUndo(
                        res.getString(R.string.quick_replies_deleted),
                        { model.onAction(QuickRepliesAction.Restore(effect.index, effect.text)) },
                        res.getString(R.string.inbox_undo),
                    )
                }
            }
        }
    }
    QuickRepliesPage(
        ui = ui,
        onAction = model::onAction,
        onEdit = { index -> presentReplyEditor(overlays, res, latest.value, index, model::onAction) },
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}
