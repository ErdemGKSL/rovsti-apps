package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.media.ViewerAction
import dev.rovsti.design.media.showPhotoViewer
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.ContextMenuStyle
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.confirmDestructive
import dev.rovsti.design.overlay.longPressMenu
import dev.rovsti.design.overlay.menuTrigger
import dev.rovsti.design.overlay.popoverTrigger
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * E1.22: every overlay in every state from one page, driven by buttons so nobody needs to time a gesture. Anchored
 * overlays (menu, popover) open from their own trigger; the pair of triggers at the bottom shows the "above" placement.
 */
@Composable
internal fun OverlayStatesPage() {
    val controller = LocalOverlay.current
    val colors = RovstiTheme.colors
    val menuItems =
        remember {
            persistentListOf<MenuItem>(
                MenuItem.Action("Select", RovstiIcons.Checkmark, onClick = {}),
                MenuItem.Action("Sort by", onClick = {}),
                MenuItem.Separator,
                MenuItem.Action("Delete all", RovstiIcons.Trash, destructive = true, onClick = {}),
            )
        }

    fun sheet(
        title: String,
        detent: Detent,
        modal: Boolean = true,
    ) {
        controller.present(
            SheetEntry(
                title = title,
                detents = persistentListOf(Detent.Peek(), Detent.Medium, Detent.Large),
                initialDetent = detent,
                modal = modal,
            ) { _ ->
                RovstiText(
                    title,
                    style = RovstiTheme.type.title3,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                )
                RovstiText(
                    "Drag the grabber, or fling.",
                    style = RovstiTheme.type.subheadline,
                    color = colors.labelSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                )
            },
        )
    }

    fun stacked() {
        sheet("First sheet", Detent.Large)
        controller.present(
            SheetEntry(title = "Second sheet", detents = persistentListOf(Detent.Medium)) { _ ->
                RovstiText(
                    "On top of the first.",
                    style = RovstiTheme.type.body,
                    modifier = Modifier.padding(Spacing.gutter),
                )
            },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly())) {
        PageHeader("Overlay states", "Each overlay in each state, started by a button.")
        Section("Menu") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(Sizes.button)
                        .background(colors.fillSecondary, CircleShape)
                        .menuTrigger(controller, menuItems)
                        .testTag("states-menu-below"),
                    contentAlignment = Alignment.Center,
                ) { RovstiIcon(RovstiIcons.Ellipsis, contentDescription = null) }
                RovstiText(
                    "Opens on touch-down, grows from the button",
                    style = RovstiTheme.type.footnote,
                    color = colors.labelSecondary,
                )
            }
        }
        Section("Popover") {
            Box(
                Modifier
                    .heightIn(min = Sizes.button)
                    .background(colors.fillSecondary, CircleShape)
                    .popoverTrigger(controller, label = "Date") { DatePopoverContent() }
                    .padding(horizontal = Spacing.l)
                    .testTag("states-popover-below"),
                contentAlignment = Alignment.Center,
            ) { RovstiText("Date form", style = RovstiTheme.type.body, color = colors.accent, maxLines = 1) }
            Box(
                Modifier
                    .heightIn(min = Sizes.button)
                    .background(colors.fillSecondary, CircleShape)
                    .popoverTrigger(controller, label = "Long list") { LongPopoverContent() }
                    .padding(horizontal = Spacing.l)
                    .testTag("states-popover-tall"),
                contentAlignment = Alignment.Center,
            ) { RovstiText("Tall, scrolls", style = RovstiTheme.type.body, color = colors.accent, maxLines = 1) }
        }
        Section("Sheet") {
            CapsuleButton("Peek", { sheet("Peek", Detent.Peek()) }, Modifier.testTag("states-sheet-peek"))
            CapsuleButton("Medium", { sheet("Medium", Detent.Medium) }, Modifier.testTag("states-sheet-medium"))
            CapsuleButton("Large", { sheet("Large", Detent.Large) }, Modifier.testTag("states-sheet-large"))
            CapsuleButton("Stacked", ::stacked, Modifier.testTag("states-sheet-stacked"))
            CapsuleButton("Non-modal", {
                sheet("Non-modal", Detent.Peek(), modal = false)
            }, Modifier.testTag("states-sheet-nonmodal"))
        }
        Section("Alert, action sheet, confirm") {
            CapsuleButton(
                "Alert",
                {
                    controller.present(
                        AlertEntry(
                            heading = "Remove from favourites?",
                            message = "Ayla Demir will no longer appear at the top.",
                            actions =
                                persistentListOf(
                                    AlertAction("Cancel", AlertRole.Cancel) {},
                                    AlertAction("Remove", AlertRole.Destructive) {},
                                ),
                        ),
                    )
                },
                Modifier.testTag("states-alert"),
            )
            CapsuleButton(
                "Action sheet",
                {
                    controller.present(
                        ActionSheetEntry(
                            title = "Ayla Demir",
                            message = "Choose what to do.",
                            actions =
                                persistentListOf(
                                    ActionSheetAction("Share contact") {},
                                    ActionSheetAction("Delete contact", destructive = true) {},
                                ),
                            onCancel = {},
                        ),
                    )
                },
                Modifier.testTag("states-action-sheet"),
            )
            CapsuleButton(
                "Confirm delete",
                {
                    controller.confirmDestructive(
                        destructiveLabel = "Delete conversation",
                        title = "Delete this conversation?",
                        message = "It cannot be restored.",
                        onConfirm = {},
                        onCancel = {},
                    )
                },
                Modifier.testTag("states-confirm"),
            )
        }
        Section("Context menu") {
            Column(
                Modifier
                    .longPressMenu(
                        controller = controller,
                        items = menuItems,
                        style = ContextMenuStyle.Card(card = { StatesContactCard() }, cardHeight = 216.dp),
                        label = "Show actions for Ayla Demir",
                    ).background(colors.fillSecondary, RoundedCornerShape(20.dp))
                    .padding(vertical = Spacing.m, horizontal = Spacing.l)
                    .testTag("states-context-preview"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar("Ayla Demir", size = 48.dp)
                RovstiText("Ayla Demir", style = RovstiTheme.type.callout, maxLines = 1)
                RovstiText(
                    "Hold to grow into a card",
                    style = RovstiTheme.type.caption1,
                    color = colors.labelSecondary,
                    maxLines = 1,
                )
            }
        }
        Section("Photo viewer") {
            val photos = remember { (1..CatalogPhotoCount).map { catalogPhotoModel(it) }.toPersistentList<Any?>() }
            CapsuleButton(
                "Photo viewer",
                {
                    controller.showPhotoViewer(
                        photos = photos,
                        initialPage = 2,
                        actions = persistentListOf(ViewerAction("share", RovstiIcons.Share, "Share") {}),
                    )
                },
                Modifier.testTag("states-photo-viewer"),
            )
        }
        Section("Toast and undo") {
            CapsuleButton("Toast", { controller.showToast("Number copied") }, Modifier.testTag("states-toast"))
            CapsuleButton(
                "Undo",
                { controller.showUndo("Conversation deleted", onUndo = {}) },
                Modifier.testTag("states-undo"),
            )
        }
        Section("Menu, above placement") {
            Box(
                Modifier
                    .size(Sizes.button)
                    .background(colors.fillSecondary, CircleShape)
                    .menuTrigger(controller, menuItems)
                    .testTag("states-menu-above"),
                contentAlignment = Alignment.Center,
            ) { RovstiIcon(RovstiIcons.Ellipsis, contentDescription = null) }
        }
        Spacer(Modifier.height(Spacing.l))
    }
}

/** What the context-menu tile grows into. */
@Composable
private fun StatesContactCard() {
    Column(
        Modifier.fillMaxSize().padding(Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Avatar("Ayla Demir", size = 88.dp)
        RovstiText(
            "Ayla Demir",
            style = RovstiTheme.type.title2,
            maxLines = 1,
            modifier = Modifier.padding(top = Spacing.m),
        )
        RovstiText(
            "+90 532 111 22 33",
            style = RovstiTheme.type.subheadline,
            color = RovstiTheme.colors.labelSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    RovstiText(
        title,
        style = RovstiTheme.type.footnote,
        color = RovstiTheme.colors.labelSecondary,
        modifier = Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.m, bottom = Spacing.xs),
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) { content() }
}
