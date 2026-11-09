package dev.rovsti.contacts.groups

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.R
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.contacts.people.presentContactPicker
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.SwipeAction
import dev.rovsti.design.list.SwipeRow
import dev.rovsti.design.list.SwipeRowGroup
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.ButtonMenuEntry
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the group page. */
object GroupPageTags {
    const val Screen = "group-page"
    const val Empty = "group-empty"
    const val Subtitle = "group-subtitle"
    const val JoinHint = "group-join-hint"

    fun member(id: Long) = "group-member-$id"
}

/** The labels of the group menu, resolved once per composition. */
private class MenuText(
    val rename: String,
    val add: String,
    val message: String,
    val email: String,
    val export: String,
    val delete: String,
)

/**
 * A group's page (E4.7), stateless: the title, how many people are in it, then the members by name; a member swipes to
 * Remove. The "..." button of the header opens the group menu over the page (Rename, Add Members, Message Group, Email
 * Group, Export Group, Delete Group). An empty group says so and offers Add Members.
 */
@Composable
fun GroupPageScreen(
    page: GroupPageUi,
    onAction: (GroupPageAction) -> Unit,
    onRename: () -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    overlays: OverlayController? = LocalOverlayOrNull.current,
) {
    val text =
        MenuText(
            rename = stringResource(R.string.group_rename),
            add = stringResource(R.string.group_add_members),
            message = stringResource(R.string.group_message),
            email = stringResource(R.string.group_email),
            export = stringResource(R.string.group_export),
            delete = stringResource(R.string.group_delete),
        )
    val addConfirm = stringResource(R.string.group_add_confirm)
    val delete = rememberDeleteAsk(page, overlays, onAction)
    val addMembers: () -> Unit = {
        overlays?.presentContactPicker(text.add, addConfirm, page.everyone, page.memberIds) { chosen ->
            onAction(GroupPageAction.SetMembers(chosen))
        }
    }
    val onOverflow: (Rect, ImmutableList<dev.rovsti.design.chrome.ToolbarAction>) -> Unit = { anchor, _ ->
        overlays?.present(ButtonMenuEntry(anchor, menuItems(page, text, onRename, addMembers, onAction, delete)))
    }
    SwipeRowGroup {
        LargeTitleScaffold(
            title = page.title,
            state = listState,
            actions = persistentListOf(),
            navigation = NavigationButton(onClick = onBack),
            onOverflow = onOverflow,
            modifier = modifier.testTag(GroupPageTags.Screen),
        ) {
            if (page.isEmpty) {
                item(key = "empty", contentType = "empty") {
                    val bottom = LocalContentInsets.current.calculateBottomPadding()
                    Box(
                        Modifier
                            .fillParentMaxHeight()
                            .fillMaxWidth()
                            .padding(bottom = bottom)
                            .testTag(GroupPageTags.Empty),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            icon = RovstiIcons.People,
                            title = "Empty Group",
                            message = "Add people with Add Members.",
                            actionLabel = text.add,
                            onAction = addMembers,
                        )
                    }
                }
            } else {
                item(key = "subtitle", contentType = "subtitle") {
                    Column(Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.s)) {
                        RovstiText(
                            page.subtitle,
                            Modifier.testTag(GroupPageTags.Subtitle),
                            style = RovstiTheme.type.footnote,
                            color = RovstiTheme.colors.labelSecondary,
                        )
                        page.joinHint?.let {
                            RovstiText(
                                it,
                                Modifier.padding(top = Spacing.xs).testTag(GroupPageTags.JoinHint),
                                style = RovstiTheme.type.footnote,
                                color = RovstiTheme.colors.labelSecondary,
                            )
                        }
                    }
                }
                page.members.forEachIndexed { index, person ->
                    item(key = person.id, contentType = "member") {
                        MemberRow(person, separated = index > 0, onAction = onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    person: PersonItem,
    separated: Boolean,
    onAction: (GroupPageAction) -> Unit,
) {
    val colors = RovstiTheme.colors
    val removeLabel = stringResource(R.string.group_remove_member)
    val swipe =
        remember(person.id, colors.destructive, removeLabel, onAction) {
            persistentListOf(
                SwipeAction(
                    removeLabel,
                    colors.destructive,
                    onClick = { onAction(GroupPageAction.RemoveMember(person.id)) },
                ),
            )
        }
    Column {
        if (separated) RowSeparator(inset = TextStartWithLeading)
        SwipeRow(trailing = swipe, rowKey = person.id, modifier = Modifier.testTag(GroupPageTags.member(person.id))) {
            ListRow(
                title = person.name,
                secondary = person.secondary,
                leading = {
                    Avatar(person.name, size = Sizes.avatar, photoModel = person.photoUri, colorKey = person.colorKey)
                },
                onClick = { onAction(GroupPageAction.OpenMember(person.id)) },
            )
        }
    }
}

private fun menuItems(
    page: GroupPageUi,
    text: MenuText,
    onRename: () -> Unit,
    addMembers: () -> Unit,
    onAction: (GroupPageAction) -> Unit,
    delete: () -> Unit,
): ImmutableList<MenuItem> {
    val items = ArrayList<MenuItem>()
    items += MenuItem.Action(text.rename, RovstiIcons.Pencil, onClick = onRename)
    items += MenuItem.Action(text.add, RovstiIcons.PersonAdd, onClick = addMembers)
    if (page.canMessage) {
        items +=
            MenuItem.Action(text.message, RovstiIcons.Message) { onAction(GroupPageAction.MessageGroup) }
    }
    if (page.canEmail) items += MenuItem.Action(text.email, RovstiIcons.Mail) { onAction(GroupPageAction.EmailGroup) }
    if (!page.isEmpty) {
        items +=
            MenuItem.Action(text.export, RovstiIcons.Download) { onAction(GroupPageAction.ExportGroup) }
    }
    items += MenuItem.Separator
    items += MenuItem.Action(text.delete, RovstiIcons.Trash, destructive = true, onClick = delete)
    return persistentListOf<MenuItem>().addAll(items)
}

/** "Delete Group": the question first (the people stay), the deletion only on its button. */
@Composable
private fun rememberDeleteAsk(
    page: GroupPageUi,
    overlays: OverlayController?,
    onAction: (GroupPageAction) -> Unit,
): () -> Unit {
    val cancel = stringResource(R.string.picker_cancel)
    val delete = stringResource(R.string.group_delete)
    val heading = stringResource(R.string.group_delete_heading, page.title)
    val message = stringResource(R.string.group_delete_message)
    return {
        overlays?.present(
            AlertEntry(
                heading = heading,
                message = message,
                actions =
                    persistentListOf(
                        AlertAction(cancel, AlertRole.Cancel),
                        AlertAction(delete, AlertRole.Destructive) { onAction(GroupPageAction.ConfirmDelete) },
                    ),
            ),
        )
    }
}
