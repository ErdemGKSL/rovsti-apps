package dev.rosty.messages.settings.replies

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.list.ReorderList
import dev.rosty.design.list.SwipeAction
import dev.rosty.design.list.SwipeRow
import dev.rosty.design.list.rememberReorderList
import dev.rosty.design.motion.RostyMotion
import dev.rosty.design.press.rowPress
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.settings.ActionRow
import dev.rosty.messages.settings.GroupFooter
import dev.rosty.messages.settings.GroupedCell
import dev.rosty.shell.settings.SettingsLazyPage
import kotlinx.collections.immutable.persistentListOf
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem

/** Test tags of the Quick replies page. */
object QuickRepliesTags {
    const val Page = "messages-quick-replies"
    const val Add = "messages-quick-replies-add"
    const val Count = "messages-quick-replies-count"

    fun row(index: Int) = "messages-quick-reply-$index"

    fun handle(index: Int) = "messages-quick-reply-handle-$index"
}

/**
 * Settings, Quick replies (M11, GPUI `settings_ui::quick_replies_section`): one grouped card of the replies, each with a
 * drag handle; a tap edits, a swipe deletes (with Undo), then "Add reply". The replies show as chips above both
 * composers (SMS and WhatsApp). Stateless: [onEdit] gets the reply to edit, or null for a new one.
 */
@Composable
fun QuickRepliesPage(
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
            scrollThresholdPadding = PaddingValues(),
            onDropped = { from, to -> onAction(QuickRepliesAction.Move(from, to)) },
        )
    SettingsLazyPage(
        title = stringResource(R.string.quick_replies_title),
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
    if (ui.loaded && count == 0) {
        item(key = "empty", contentType = "footer") {
            GroupFooter(stringResource(R.string.quick_replies_empty))
        }
    }
    itemsIndexed(reorder.items, key = { _, text -> text }, contentType = { _, _ -> "reply" }) { index, text ->
        ReorderableItem(state = reorder.state, key = text) { dragging ->
            ReplyRow(text, index, count, dragging, this, onAction, onEdit)
        }
    }
    item(key = "count", contentType = "footer") {
        if (ui.loaded && count > 0) {
            GroupFooter(
                stringResource(R.string.quick_replies_count, count, ui.max),
                Modifier.testTag(QuickRepliesTags.Count),
            )
        }
    }
    item(key = "add", contentType = "action") {
        if (ui.loaded) {
            GroupedCell(0, 1, Modifier.padding(top = Spacing.s)) {
                ActionRow(
                    stringResource(R.string.quick_replies_add),
                    { onEdit(null) },
                    QuickRepliesTags.Add,
                    enabled = ui.canAdd,
                )
            }
        }
    }
    item(key = "footer", contentType = "footer") {
        GroupFooter(stringResource(R.string.quick_replies_footer), Modifier.padding(top = Spacing.s))
    }
}

@Composable
private fun ReplyRow(
    text: String,
    index: Int,
    count: Int,
    dragging: Boolean,
    scope: ReorderableCollectionItemScope,
    onAction: (QuickRepliesAction) -> Unit,
    onEdit: (Int?) -> Unit,
) {
    val lift by animateFloatAsState(if (dragging) LIFT else 1f, RostyMotion.select(1f), label = "lift")
    val haptics = LocalRostyHaptics.current
    val delete = stringResource(R.string.quick_replies_delete)
    val earlier = stringResource(R.string.quick_replies_move_earlier)
    val later = stringResource(R.string.quick_replies_move_later)
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
        persistentListOf(
            SwipeAction(delete, RostyTheme.colors.destructive) { onAction(QuickRepliesAction.Delete(index)) },
        )
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
    val label = stringResource(R.string.quick_replies_reorder)
    with(scope) {
        Box(
            Modifier
                .size(Sizes.touch)
                .semantics { contentDescription = label }
                .draggableHandle(onDragStarted = { onStart() })
                .testTag(QuickRepliesTags.handle(index)),
            contentAlignment = Alignment.Center,
        ) {
            RostyIcon(RostyIcons.Reorder, null, tint = RostyTheme.colors.labelSecondary, size = Spacing.xl)
        }
    }
}

private const val LIFT = 1.03f
