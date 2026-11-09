package dev.rovsti.contacts.groups

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import dev.rovsti.contacts.R
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.text.GlassTextField
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the Groups tab. */
object GroupsTags {
    const val Screen = "groups-screen"
    const val Empty = "groups-empty"
    const val NameField = "group-name-field"

    fun row(id: Long) = "group-row-$id"
}

/**
 * The Groups tab (E4.7), stateless: one card with a row per group (its name and how many people are in it), the "+"
 * button for a new group in the header, and an empty state that fills the page above the tab bar.
 */
@Composable
fun GroupsScreen(
    state: GroupsUiState,
    onAction: (GroupsAction) -> Unit,
    onNew: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val newLabel = stringResource(R.string.groups_new)
    val ready = state.phase == GroupsPhase.Ready
    val toolbar =
        remember(ready, onNew, newLabel) {
            if (ready) persistentListOf(ToolbarAction("add", RovstiIcons.Plus, newLabel, onNew)) else persistentListOf()
        }
    LargeTitleScaffold(
        title = stringResource(R.string.tab_groups),
        state = listState,
        actions = toolbar,
        modifier = modifier.testTag(GroupsTags.Screen),
    ) {
        if (ready && state.groups.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                val bottom = LocalContentInsets.current.calculateBottomPadding()
                Box(
                    Modifier
                        .fillParentMaxHeight()
                        .fillMaxWidth()
                        .padding(bottom = bottom)
                        .testTag(GroupsTags.Empty),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = RovstiIcons.Folder,
                        title = "No Groups",
                        message = "Make a group, then add people to it from the group's page.",
                        actionLabel = newLabel,
                        onAction = onNew,
                    )
                }
            }
        } else if (ready) {
            item(key = "groups", contentType = "groups") {
                InsetGroupedSection(modifier = Modifier.padding(top = Spacing.s)) {
                    for (group in state.groups) {
                        row {
                            ListRow(
                                title = group.title,
                                secondary = group.subtitle,
                                modifier = Modifier.testTag(GroupsTags.row(group.id)),
                                leading = {
                                    RovstiIcon(
                                        RovstiIcons.People,
                                        null,
                                        tint = RovstiTheme.colors.accent,
                                        size = GroupsMetrics.GroupGlyph,
                                    )
                                },
                                trailing = { ListRowChevron() },
                                onClick = { onAction(GroupsAction.Open(group.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The name alert of "New Group" and "Rename Group": a [GlassTextField] under the heading. [error] (the Rust core's
 * sentence about a rejected name) replaces the message. Save hands the typed text to [onSave]; an empty or taken name
 * comes back through the model with its error and this alert opens again.
 */
fun OverlayController.presentNameAlert(
    heading: String,
    initial: String,
    error: String?,
    saveLabel: String,
    cancelLabel: String,
    placeholder: String,
    onSave: (String) -> Unit,
) {
    val field = TextFieldState(initial, TextRange(0, initial.length))
    present(
        AlertEntry(
            heading = heading,
            message = error,
            actions =
                persistentListOf(
                    AlertAction(cancelLabel, AlertRole.Cancel),
                    AlertAction(saveLabel) { onSave(field.text.toString()) },
                ),
            body = {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                GlassTextField(
                    state = field,
                    placeholder = placeholder,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                top = Spacing.m,
                            ).focusRequester(focus)
                            .testTag(GroupsTags.NameField),
                    onSubmit = { onSave(field.text.toString()) },
                )
            },
        ),
    )
}
