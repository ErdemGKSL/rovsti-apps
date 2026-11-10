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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rovsti.contacts.R
import dev.rovsti.core.model.contacts.SelectAction
import dev.rovsti.design.chrome.ToolbarButton
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.menuTrigger
import dev.rovsti.design.text.UiText
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
    val strContactsExportVcf = stringResource(R.string.contacts_export_vcf)
    val strContactsCopyNumbers = stringResource(R.string.contacts_copy_numbers)
    val strContactsCopyEmails = stringResource(R.string.contacts_copy_emails)
    val strContactsShare = stringResource(R.string.contacts_share)
    val strContactsAddToGroup = stringResource(R.string.contacts_add_to_group)
    val strContactsStar = stringResource(R.string.contacts_star)
    val strEditorDelete = stringResource(R.string.editor_delete)

    fun on(action: SelectAction) = action in select.actions
    val more =
        remember(onAction) {
            persistentListOf<MenuItem>(
                MenuItem.Action(strContactsExportVcf, RovstiIcons.Download, onClick = {
                    onAction(ContactsListAction.ExportSelected)
                }),
                MenuItem.Separator,
                MenuItem.Action(strContactsCopyNumbers, RovstiIcons.Phone, onClick = {
                    onAction(ContactsListAction.CopySelectedNumbers)
                }),
                MenuItem.Action(strContactsCopyEmails, RovstiIcons.Mail, onClick = {
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
            strContactsShare,
            { onAction(ContactsListAction.ShareSelected) },
            Modifier.testTag(SelectTags.Share),
            enabled = on(SelectAction.Share),
        )
        ToolbarButton(
            RovstiIcons.Folder,
            strContactsAddToGroup,
            { onAction(ContactsListAction.AskAddToGroup) },
            Modifier.testTag(SelectTags.AddToGroup),
            enabled = on(SelectAction.AddToGroup),
        )
        ToolbarButton(
            RovstiIcons.Star,
            strContactsStar,
            { onAction(ContactsListAction.StarSelected) },
            Modifier.testTag(SelectTags.Star),
            enabled = on(SelectAction.Star),
        )
        ToolbarButton(
            RovstiIcons.Trash,
            strEditorDelete,
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
                .then(
                    if (canMore) {
                        Modifier.menuTrigger(
                            overlays,
                            more,
                            stringResource(R.string.contacts_more),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(RovstiIcons.Ellipsis, null, tint = RovstiTheme.glass.foreground, size = SelectMetrics.MoreIcon)
        }
    }
}
