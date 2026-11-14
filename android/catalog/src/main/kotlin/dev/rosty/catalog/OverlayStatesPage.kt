package dev.rosty.catalog

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
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.Avatar
import dev.rosty.design.media.ViewerAction
import dev.rosty.design.media.showPhotoViewer
import dev.rosty.design.overlay.ActionSheetAction
import dev.rosty.design.overlay.ActionSheetEntry
import dev.rosty.design.overlay.AlertAction
import dev.rosty.design.overlay.AlertEntry
import dev.rosty.design.overlay.AlertRole
import dev.rosty.design.overlay.ContextMenuStyle
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.confirmDestructive
import dev.rosty.design.overlay.longPressMenu
import dev.rosty.design.overlay.menuTrigger
import dev.rosty.design.overlay.popoverTrigger
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * E1.22: every overlay in every state from one page, driven by buttons so nobody needs to time a gesture. Anchored
 * overlays (menu, popover) open from their own trigger; the pair of triggers at the bottom shows the "above" placement.
 */
@Composable
internal fun OverlayStatesPage() {
    val controller = LocalOverlay.current
    val colors = RostyTheme.colors
    val menuItems =
        remember {
            persistentListOf<MenuItem>(
                MenuItem.Action("Select", RostyIcons.Checkmark, onClick = {}),
                MenuItem.Action("Sort by", onClick = {}),
                MenuItem.Separator,
                MenuItem.Action("Delete all", RostyIcons.Trash, destructive = true, onClick = {}),
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
                RostyText(
                    title,
                    style = RostyTheme.type.title3,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                )
                RostyText(
                    "Drag the grabber, or fling.",
                    style = RostyTheme.type.subheadline,
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
                RostyText(
                    "On top of the first.",
                    style = RostyTheme.type.body,
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
                ) { RostyIcon(RostyIcons.Ellipsis, contentDescription = null) }
                RostyText(
                    "Opens on touch-down, grows from the button",
                    style = RostyTheme.type.footnote,
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
            ) { RostyText("Date form", style = RostyTheme.type.body, color = colors.accent, maxLines = 1) }
            Box(
                Modifier
                    .heightIn(min = Sizes.button)
                    .background(colors.fillSecondary, CircleShape)
                    .popoverTrigger(controller, label = "Long list") { LongPopoverContent() }
                    .padding(horizontal = Spacing.l)
                    .testTag("states-popover-tall"),
                contentAlignment = Alignment.Center,
            ) { RostyText("Tall, scrolls", style = RostyTheme.type.body, color = colors.accent, maxLines = 1) }
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
                RostyText("Ayla Demir", style = RostyTheme.type.callout, maxLines = 1)
                RostyText(
                    "Hold to grow into a card",
                    style = RostyTheme.type.caption1,
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
                        actions = persistentListOf(ViewerAction("share", RostyIcons.Share, "Share") {}),
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
            ) { RostyIcon(RostyIcons.Ellipsis, contentDescription = null) }
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
        RostyText(
            "Ayla Demir",
            style = RostyTheme.type.title2,
            maxLines = 1,
            modifier = Modifier.padding(top = Spacing.m),
        )
        RostyText(
            "+90 532 111 22 33",
            style = RostyTheme.type.subheadline,
            color = RostyTheme.colors.labelSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    RostyText(
        title,
        style = RostyTheme.type.footnote,
        color = RostyTheme.colors.labelSecondary,
        modifier = Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.m, bottom = Spacing.xs),
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) { content() }
}
