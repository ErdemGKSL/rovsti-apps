package dev.rovsti.contacts.cleanup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.rovsti.contacts.R
import dev.rovsti.contacts.orNoName
import dev.rovsti.contacts.people.ContactAvatar
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.chrome.ToolbarButton
import dev.rovsti.design.chrome.ToolbarCapsule
import dev.rovsti.design.chrome.ToolbarTextButton
import dev.rovsti.design.controls.ChipRow
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.controls.SelectCheck
import dev.rovsti.design.controls.SelectCheckSize
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.text.UiText
import dev.rovsti.design.text.resolve
import dev.rovsti.design.text.text
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow

/** Test tags of the Clean up page. */
object CleanupTags {
    const val Screen = "cleanup-screen"
    const val Empty = "cleanup-empty"
    const val Chips = "cleanup-chips"
    const val Delete = "cleanup-delete"
    const val SelectAll = "cleanup-select-all"
    const val Cancel = "cleanup-cancel"

    fun row(id: Long) = "cleanup-row-$id"
}

private val SelectSlotWidth = Spacing.gutter + SelectCheckSize

/**
 * The Clean up page (E4.11, C24, C54), stateless. The filters are ONE row of chips that scrolls sideways and never
 * wraps, each with its count. Under it the contacts of the filter, one avatar each. "Select" turns the rows into
 * check circles; then the title bar offers Select All, Cancel and Delete (a question first, then the Trash and ONE
 * undo pill). An empty filter is an [EmptyState] that fills the page above the bars.
 */
@Composable
fun CleanupScreen(
    state: CleanupUiState,
    onAction: (CleanupAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val strContactsSelect = stringResource(R.string.contacts_select)
    val strContactsCleanUp = stringResource(R.string.contacts_clean_up)
    val strContactsAllClear = stringResource(R.string.contacts_all_clear)
    val select = state.select
    BackHandler(enabled = select != null) { onAction(CleanupAction.ExitSelect) }
    val toolbar =
        remember(state.rows.isNotEmpty(), select == null, onAction) {
            if (select != null || state.rows.isEmpty()) {
                persistentListOf()
            } else {
                persistentListOf(
                    ToolbarAction("select", RovstiIcons.Checkmark, strContactsSelect) {
                        onAction(CleanupAction.EnterSelect(null))
                    },
                )
            }
        }
    LargeTitleScaffold(
        title = select?.title?.text() ?: strContactsCleanUp,
        state = listState,
        actions = toolbar,
        navigation = NavigationButton(onClick = onBack),
        modifier = modifier.testTag(CleanupTags.Screen),
        actionsContent = select?.let { { SelectButtons(it, onAction) } },
    ) {
        if (state.phase == CleanupPhase.Ready) {
            item(key = "chips", contentType = "chips") {
                ChipRow(
                    chips = state.chips,
                    selected = state.filter,
                    onSelect = { onAction(CleanupAction.SelectFilter(it)) },
                    modifier = Modifier.testTag(CleanupTags.Chips),
                )
            }
        }
        if (state.isEmpty) {
            item(key = "empty", contentType = "empty") {
                val bottom = LocalContentInsets.current.calculateBottomPadding()
                Box(
                    Modifier
                        .fillParentMaxHeight()
                        .fillMaxWidth()
                        .padding(bottom = bottom)
                        .testTag(CleanupTags.Empty),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = RovstiIcons.Checkmark,
                        title = strContactsAllClear,
                        message = state.emptyText,
                    )
                }
            }
        } else {
            state.rows.forEachIndexed { index, row ->
                item(key = row.id, contentType = "contact") {
                    CleanupRowItem(
                        row,
                        separated = index > 0,
                        checked = select?.let { row.id in it.selected },
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanupRowItem(
    row: CleanupRow,
    separated: Boolean,
    checked: Boolean?,
    onAction: (CleanupAction) -> Unit,
) {
    val slot by animateDpAsState(
        targetValue = if (checked != null) SelectSlotWidth else CleanupMetrics.None,
        animationSpec = RovstiMotion.select(Dp.VisibilityThreshold),
        label = "select-slot",
    )
    val toggle = remember(row.id, onAction) { { onAction(CleanupAction.Toggle(row.id)) } }
    val enter = remember(row.id, onAction) { { onAction(CleanupAction.EnterSelect(row.id)) } }
    Column(
        Modifier.testTag(CleanupTags.row(row.id)).semantics {
            if (checked != null) selected = checked
        },
    ) {
        if (separated) RowSeparator(inset = TextStartWithLeading + slot)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (slot > CleanupMetrics.None) {
                Box(Modifier.width(slot).clipToBounds(), contentAlignment = Alignment.CenterStart) {
                    SelectCheck(
                        checked == true,
                        Modifier.padding(start = Spacing.gutter).requiredWidth(SelectCheckSize),
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                ListRow(
                    title = row.name.orNoName(),
                    secondary = row.secondary,
                    leading = {
                        ContactAvatar(
                            row.name.orNoName(),
                            row.face,
                            size = Sizes.avatar,
                            colorKey = row.colorKey,
                        )
                    },
                    onClick = if (checked != null) toggle else enter,
                )
            }
        }
    }
}

/** Select All / Deselect All, Cancel and Delete, in the title bar (they replace Select). */
@Composable
private fun SelectButtons(
    select: CleanupSelect,
    onAction: (CleanupAction) -> Unit,
) {
    // ONE capsule: the title bar stacks what it is given, so two capsules would overlap.
    ToolbarCapsule {
        ToolbarButton(
            RovstiIcons.Trash,
            stringResource(R.string.editor_delete),
            { onAction(CleanupAction.AskDelete) },
            Modifier.testTag(CleanupTags.Delete),
            enabled = select.canDelete,
        )
        ToolbarTextButton(
            if (select.allSelected) {
                stringResource(
                    R.string.contacts_deselect_all,
                )
            } else {
                stringResource(R.string.contacts_select_all)
            },
            { onAction(CleanupAction.ToggleAll) },
            Modifier.testTag(CleanupTags.SelectAll),
        )
        ToolbarTextButton(
            stringResource(R.string.contacts_cancel),
            { onAction(CleanupAction.ExitSelect) },
            Modifier.testTag(CleanupTags.Cancel),
            bold = true,
        )
    }
}

/** Runs the model's effects: the delete question becomes an alert, a notice a toast, a delete ONE undo pill. */
@Composable
fun CleanupEffects(
    effects: Flow<CleanupEffect>,
    overlays: OverlayController,
    onAction: (CleanupAction) -> Unit,
) {
    val context = LocalContext.current
    val strContactsCancel = stringResource(R.string.contacts_cancel)
    val strEditorDelete = stringResource(R.string.editor_delete)
    val latest = rememberUpdatedState(onAction)
    LaunchedEffect(effects, overlays) {
        effects.collect { effect ->
            when (effect) {
                is CleanupEffect.Ask -> {
                    overlays.present(
                        AlertEntry(
                            heading = effect.confirm.title,
                            message = effect.confirm.body.ifBlank { null },
                            actions =
                                persistentListOf(
                                    AlertAction(strContactsCancel, AlertRole.Cancel),
                                    AlertAction(strEditorDelete, AlertRole.Destructive) {
                                        latest.value(CleanupAction.ConfirmDelete)
                                    },
                                ),
                        ),
                    )
                }

                is CleanupEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }

                is CleanupEffect.Deleted -> {
                    overlays.showUndo(
                        effect.notice.resolve(context),
                        onUndo = { latest.value(CleanupAction.Undo(effect.keys)) },
                    )
                }
            }
        }
    }
}
