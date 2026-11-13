package dev.rovsti.phone.settings.replies

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.list.ReorderList
import dev.rovsti.design.list.SwipeAction
import dev.rovsti.design.list.SwipeRow
import dev.rovsti.design.list.rememberReorderList
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.press.rowPress
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.R
import dev.rovsti.phone.settings.ActionRow
import dev.rovsti.phone.settings.GroupFooter
import dev.rovsti.phone.settings.GroupedCell
import dev.rovsti.shell.settings.SettingsLazyPage
import kotlinx.collections.immutable.persistentListOf
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem

/** Test tags of the Quick replies page. */
object QuickRepliesTags {
    const val Page = "quick-replies-page"
    const val Add = "quick-replies-add"
    const val Restore = "quick-replies-restore"
    const val Count = "quick-replies-count"

    fun row(index: Int) = "quick-reply-row-$index"

    fun handle(index: Int) = "quick-reply-handle-$index"
}

/**
 * Quick replies (E5.9b): one grouped card of the replies, each with a drag handle on its end; a tap edits, a swipe
 * deletes (with Undo), then Add reply and Restore defaults. Stateless: [onEdit] gets the reply to edit or null for a new
 * one; the drop, the delete and the restore leave as [QuickRepliesAction]s.
 */
@Composable
fun QuickRepliesScreen(
    ui: QuickRepliesUi,
    onAction: (QuickRepliesAction) -> Unit,
    onEdit: (index: Int?) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val reorder =
        rememberReorderList(
            lazyListState = listState,
            shown = ui.replies,
            keyOf = { it },
            scrollThresholdPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(),
            onDropped = { from, to -> onAction(QuickRepliesAction.Move(from, to)) },
        )
    SettingsLazyPage(
        title = stringResource(R.string.qr_title),
        modifier = modifier.testTag(QuickRepliesTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        listState = listState,
    ) {
        repliesItems(ui, reorder, onAction, onEdit)
    }
}

private fun LazyListScope.repliesItems(
    ui: QuickRepliesUi,
    reorder: ReorderList<String>,
    onAction: (QuickRepliesAction) -> Unit,
    onEdit: (Int?) -> Unit,
) {
    val count = reorder.items.size
    itemsIndexed(reorder.items, key = { _, text -> text }, contentType = { _, _ -> "reply" }) { index, text ->
        ReorderableItem(state = reorder.state, key = text) { dragging ->
            ReplyRow(text, index, count, dragging, ui.replies.size > 1, this, onAction, onEdit)
        }
    }
    item(key = "count", contentType = "footer") {
        if (ui.loaded) {
            GroupFooter(
                stringResource(R.string.qr_count, ui.replies.size, ui.max),
                Modifier.testTag(QuickRepliesTags.Count),
            )
        }
    }
    item(key = "add", contentType = "action") {
        if (ui.canAdd && ui.loaded) {
            GroupedCell(0, 1, Modifier.padding(top = Spacing.s)) {
                ActionRow(stringResource(R.string.qr_add), { onEdit(null) }, QuickRepliesTags.Add)
            }
        }
    }
    item(key = "restore", contentType = "action") {
        if (ui.custom) {
            GroupedCell(0, 1, Modifier.padding(top = Spacing.l)) {
                ActionRow(
                    stringResource(R.string.qr_restore),
                    { onAction(QuickRepliesAction.RestoreDefaults) },
                    QuickRepliesTags.Restore,
                )
            }
        }
    }
    item(key = "footer", contentType = "footer") {
        GroupFooter(stringResource(R.string.qr_footer), Modifier.padding(top = Spacing.s))
    }
}

@Composable
private fun ReplyRow(
    text: String,
    index: Int,
    count: Int,
    dragging: Boolean,
    canDelete: Boolean,
    scope: ReorderableCollectionItemScope,
    onAction: (QuickRepliesAction) -> Unit,
    onEdit: (Int?) -> Unit,
) {
    val lift by animateFloatAsState(if (dragging) LIFT else 1f, RovstiMotion.select(1f), label = "lift")
    val haptics = LocalRovstiHaptics.current
    val delete = stringResource(R.string.set_delete)
    val earlier = stringResource(R.string.qr_move_earlier)
    val later = stringResource(R.string.qr_move_later)
    val steps =
        remember(index, count, earlier, later) {
            buildList {
                if (index > 0) {
                    add(
                        CustomAccessibilityAction(earlier) {
                            onAction(QuickRepliesAction.Move(index, index - 1))
                            true
                        },
                    )
                }
                if (index < count - 1) {
                    add(
                        CustomAccessibilityAction(later) {
                            onAction(QuickRepliesAction.Move(index, index + 1))
                            true
                        },
                    )
                }
            }
        }
    val swipe =
        if (canDelete) {
            persistentListOf(
                SwipeAction(delete, RovstiTheme.colors.destructive) { onAction(QuickRepliesAction.Delete(index)) },
            )
        } else {
            persistentListOf()
        }
    val source = remember { MutableInteractionSource() }
    GroupedCell(
        index,
        count,
        Modifier.graphicsLayer {
            scaleX = lift
            scaleY = lift
        },
    ) {
        SwipeRow(trailing = swipe, rowKey = text) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag(QuickRepliesTags.row(index))
                    .semantics { customActions = steps }
                    .rowPress(source)
                    .padding(start = Spacing.gutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(vertical = Spacing.m)
                        .clickable(interactionSource = source, indication = null, role = Role.Button) { onEdit(index) },
                ) {
                    IosText(text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Handle(index, scope) { haptics.tick() }
            }
        }
    }
}

@Composable
private fun Handle(
    index: Int,
    scope: ReorderableCollectionItemScope,
    onStart: () -> Unit,
) {
    val label = stringResource(R.string.qr_reorder)
    with(scope) {
        Box(
            Modifier
                .size(Sizes.touch)
                .semantics { contentDescription = label }
                .draggableHandle(onDragStarted = { onStart() })
                .testTag(QuickRepliesTags.handle(index)),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(RovstiIcons.Reorder, null, tint = RovstiTheme.colors.labelSecondary, size = Spacing.xl)
        }
    }
}

private const val LIFT = 1.03f
