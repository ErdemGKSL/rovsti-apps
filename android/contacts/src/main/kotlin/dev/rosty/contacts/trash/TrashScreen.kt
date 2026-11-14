package dev.rosty.contacts.trash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.rosty.contacts.R
import dev.rosty.design.chrome.LargeTitleScaffold
import dev.rosty.design.chrome.LocalContentInsets
import dev.rosty.design.chrome.NavigationButton
import dev.rosty.design.chrome.ToolbarAction
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.Avatar
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.SwipeAction
import dev.rosty.design.list.SwipeRow
import dev.rosty.design.list.SwipeRowGroup
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.overlay.AlertAction
import dev.rosty.design.overlay.AlertEntry
import dev.rosty.design.overlay.AlertRole
import dev.rosty.design.overlay.ContextMenuStyle
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.longPressMenu
import dev.rosty.design.overlay.showToast
import dev.rosty.design.text.resolve
import dev.rosty.design.text.text
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow

/** Test tags of the Trash page. */
object TrashTags {
    const val Screen = "trash-screen"
    const val Empty = "trash-empty"
    const val Footer = "trash-footer"

    fun row(key: Long) = "trash-row-$key"
}

/**
 * The Trash (E4.5, C20), stateless: deleted contacts newest first, each with how long ago it went and how long is left.
 * Swipe a row, or long-press it, for Restore and Delete Now (the menu floats over the page; nothing moves). The toolbar
 * button empties the Trash after a question. An empty Trash is an [EmptyState] that fills the page above the bar.
 */
@Composable
fun TrashScreen(
    state: TrashUiState,
    onAction: (TrashAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    overlays: OverlayController? = LocalOverlayOrNull.current,
) {
    val emptyTrashLabel = stringResource(R.string.contacts_empty_trash)
    val toolbar =
        remember(state.rows.isNotEmpty(), onAction) {
            if (state.rows.isEmpty()) {
                persistentListOf()
            } else {
                persistentListOf(
                    ToolbarAction("empty-trash", RostyIcons.Trash, emptyTrashLabel) {
                        onAction(TrashAction.AskEmpty)
                    },
                )
            }
        }
    LargeTitleScaffold(
        title = stringResource(R.string.settings_trash),
        state = listState,
        actions = toolbar,
        navigation = NavigationButton(onClick = onBack),
        modifier = modifier.testTag(TrashTags.Screen),
    ) {
        if (state.isEmpty) {
            item(key = "empty", contentType = "empty") {
                val bottom = LocalContentInsets.current.calculateBottomPadding()
                Box(
                    Modifier
                        .fillParentMaxHeight()
                        .fillMaxWidth()
                        .padding(bottom = bottom)
                        .testTag(TrashTags.Empty),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = RostyIcons.Trash,
                        title = stringResource(R.string.contacts_trash_empty),
                        message =
                            pluralStringResource(
                                R.plurals.contacts_trash_retention_empty,
                                state.retentionDays,
                                state.retentionDays,
                            ),
                    )
                }
            }
        } else {
            state.rows.forEachIndexed { index, row ->
                item(key = row.key, contentType = "trash") {
                    TrashRowItem(row, separated = index > 0, overlays = overlays, onAction = onAction)
                }
            }
            if (state.rows.isNotEmpty()) {
                item(key = "footer", contentType = "footer") {
                    RostyText(
                        pluralStringResource(
                            R.plurals.contacts_trash_retention_footer,
                            state.retentionDays,
                            state.retentionDays,
                        ),
                        Modifier
                            .padding(horizontal = Spacing.gutter, vertical = Spacing.m)
                            .testTag(TrashTags.Footer),
                        style = RostyTheme.type.footnote,
                        color = RostyTheme.colors.labelSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashRowItem(
    row: TrashRow,
    separated: Boolean,
    overlays: OverlayController?,
    onAction: (TrashAction) -> Unit,
) {
    val restoreLabel = stringResource(R.string.contacts_restore)
    val deleteNowLabel = stringResource(R.string.contacts_delete_now)
    val colors = RostyTheme.colors
    val restore = remember(row.key, onAction) { { onAction(TrashAction.Restore(row.key)) } }
    val delete = remember(row.key, onAction) { { onAction(TrashAction.AskDelete(row.key)) } }
    val swipe =
        remember(row.key, colors.accent, colors.destructive, onAction) {
            persistentListOf(
                SwipeAction(restoreLabel, colors.accent, onClick = restore),
                SwipeAction(deleteNowLabel, colors.destructive, onClick = delete),
            )
        }
    val menu =
        remember(row.key, onAction) {
            persistentListOf<MenuItem>(
                MenuItem.Action(restoreLabel, RostyIcons.Forward, onClick = restore),
                MenuItem.Action(
                    deleteNowLabel,
                    RostyIcons.Trash,
                    destructive = true,
                    onClick = delete,
                ),
            )
        }
    androidx.compose.foundation.layout.Column {
        if (separated) RowSeparator(inset = TextStartWithLeading)
        SwipeRow(trailing = swipe, rowKey = row.key, modifier = Modifier.testTag(TrashTags.row(row.key))) {
            if (overlays != null) {
                Box(
                    Modifier.longPressMenu(
                        controller = overlays,
                        items = menu,
                        style = ContextMenuStyle.Lifted(preview = { RowBody(row) }),
                        onTap = null,
                    ),
                ) { RowBody(row) }
            } else {
                RowBody(row)
            }
        }
    }
}

@Composable
private fun RowBody(row: TrashRow) {
    ListRow(
        title = row.name.text(),
        secondary = row.detail.text(),
        leading = { Avatar(row.name.text(), size = Sizes.avatar, colorKey = row.colorKey) },
    )
}

/** Runs the model's effects: a question becomes an alert, a notice a toast. */
@Composable
fun TrashEffects(
    effects: Flow<TrashEffect>,
    overlays: OverlayController,
    onAction: (TrashAction) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val latestAction = rememberUpdatedState(onAction)
    LaunchedEffect(effects, overlays) {
        effects.collect { effect ->
            when (effect) {
                is TrashEffect.Ask -> {
                    overlays.present(
                        AlertEntry(
                            heading = effect.title.resolve(context),
                            message = effect.body.resolve(context),
                            actions =
                                persistentListOf(
                                    AlertAction(resources.getString(R.string.contacts_cancel), AlertRole.Cancel),
                                    AlertAction(effect.confirmLabel.resolve(context), AlertRole.Destructive) {
                                        latestAction.value(effect.then)
                                    },
                                ),
                        ),
                    )
                }

                is TrashEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }
            }
        }
    }
}
