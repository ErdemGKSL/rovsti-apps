package dev.rovsti.contacts.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.core.model.contacts.SelectAction
import dev.rovsti.design.chrome.ToolbarButton
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.menuTrigger
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the select toolbar. */
object SelectTags {
    const val Toolbar = "select-toolbar"
    const val Share = "select-share"
    const val AddToGroup = "select-add-to-group"
    const val Star = "select-star"
    const val Delete = "select-delete"
    const val More = "select-more"
    const val Cancel = "select-cancel"
    const val SelectAll = "select-all"
}

/**
 * The bulk toolbar of select mode (E4.8): Share, Add to Group, Star, Delete and a "..." menu (Export .vcf, Copy
 * Numbers, Copy Emails). It is the content of the tab bar's accessory slot, so it carries the bar's glass and sits in
 * the room beside the folded circle; [modifier] and [isInline] come from that slot. A button whose action
 * `contacts.select_bar` does not offer (nothing selected, no groups) is dimmed and does nothing.
 */
@Composable
fun SelectToolbar(
    select: SelectMode,
    onAction: (ContactsListAction) -> Unit,
    overlays: OverlayController,
    modifier: Modifier = Modifier,
    isInline: Boolean = true,
) {
    fun on(action: SelectAction) = action in select.actions
    val more =
        remember(onAction) {
            persistentListOf<MenuItem>(
                MenuItem.Action("Export .vcf", RovstiIcons.Download, onClick = {
                    onAction(ContactsListAction.ExportSelected)
                }),
                MenuItem.Separator,
                MenuItem.Action("Copy Numbers", RovstiIcons.Phone, onClick = {
                    onAction(ContactsListAction.CopySelectedNumbers)
                }),
                MenuItem.Action("Copy Emails", RovstiIcons.Mail, onClick = {
                    onAction(ContactsListAction.CopySelectedEmails)
                }),
            )
        }
    Row(
        modifier
            .height(
                if (isInline) SelectMetrics.ToolbarInline else SelectMetrics.ToolbarExpanded,
            ).padding(horizontal = SelectMetrics.ToolbarSide)
            .testTag(SelectTags.Toolbar),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(
            RovstiIcons.Share,
            "Share",
            { onAction(ContactsListAction.ShareSelected) },
            Modifier.testTag(SelectTags.Share),
            enabled = on(SelectAction.Share),
        )
        ToolbarButton(
            RovstiIcons.Folder,
            "Add to Group",
            { onAction(ContactsListAction.AskAddToGroup) },
            Modifier.testTag(SelectTags.AddToGroup),
            enabled = on(SelectAction.AddToGroup),
        )
        ToolbarButton(
            RovstiIcons.Star,
            "Star",
            { onAction(ContactsListAction.StarSelected) },
            Modifier.testTag(SelectTags.Star),
            enabled = on(SelectAction.Star),
        )
        ToolbarButton(
            RovstiIcons.Trash,
            "Delete",
            { onAction(ContactsListAction.AskDeleteSelected) },
            Modifier.testTag(SelectTags.Delete),
            enabled = on(SelectAction.Delete),
        )
        val canMore = on(SelectAction.SaveToFile)
        Box(
            Modifier
                .size(width = Sizes.button, height = Sizes.touch)
                .alpha(if (canMore) 1f else 0.38f)
                .testTag(SelectTags.More)
                .then(if (canMore) Modifier.menuTrigger(overlays, more, "More") else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(RovstiIcons.Ellipsis, null, tint = RovstiTheme.glass.foreground, size = SelectMetrics.MoreIcon)
        }
    }
}
