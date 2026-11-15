package dev.rosty.messages.remind

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.rosty.core.model.chat.ReminderSlot
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.ActionSheetAction
import dev.rosty.design.overlay.ActionSheetEntry
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayEntry
import dev.rosty.design.overlay.PopoverEntry
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.remind.theme.RemindMetrics
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Test tags of the "Remind me" popover. */
object RemindTags {
    const val Root = "remind-popover"
    const val Cancel = "remind-cancel"

    fun slot(slot: ReminderSlot) = "remind-${slot.name}"
}

/** One choice: "In 1 hour" and when that is ("15:05"). */
@Immutable
data class RemindRow(
    val slot: ReminderSlot,
    val label: String,
    val clock: String,
)

/** The popover as drawn. [cancel] is the "Cancel reminder (18:05)" row of a message that has one. */
@Immutable
data class RemindUi(
    val title: String,
    val rows: ImmutableList<RemindRow>,
    val cancel: String? = null,
    val cancelSheet: String = "",
)

/**
 * The card of the "Remind me" popover (M33): the slots Rust offers, each with its time, and "Cancel reminder" when the
 * message already has one. It sits on the popover's glass, so the text is the glass foreground.
 */
@Composable
fun RemindCard(
    ui: RemindUi,
    onPick: (ReminderSlot) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = RostyTheme.glass.foreground
    Column(modifier.width(RemindMetrics.CardWidth).testTag(RemindTags.Root)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            RostyIcon(RostyIcons.Bell, null, tint = RostyTheme.colors.labelSecondary, size = RemindMetrics.RowGlyph)
            RostyText(
                ui.title,
                modifier = Modifier.weight(1f).semantics { heading() },
                style = RostyTheme.type.footnote,
                color = RostyTheme.colors.labelSecondary,
                maxLines = 2,
            )
        }
        for (row in ui.rows) {
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.touch)
                    .clickable(role = Role.Button) { onPick(row.slot) }
                    .padding(horizontal = Spacing.l, vertical = Spacing.s)
                    .testTag(RemindTags.slot(row.slot)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                RostyText(
                    row.label,
                    modifier = Modifier.weight(1f),
                    style = RostyTheme.type.body,
                    color = ink,
                    maxLines = 2,
                )
                RostyText(
                    row.clock,
                    style = RostyTheme.type.subheadline,
                    color = RostyTheme.colors.labelSecondary,
                    maxLines = 1,
                )
            }
        }
        ui.cancel?.let { label ->
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.touch)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(horizontal = Spacing.l, vertical = Spacing.s)
                    .testTag(RemindTags.Cancel),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RostyText(label, style = RostyTheme.type.body, color = RostyTheme.colors.destructive, maxLines = 2)
            }
        }
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(RemindMetrics.Hairline).background(RostyTheme.colors.separator))
}

/**
 * Opens the choices: a popover growing out of [anchor] (the bubble whose menu asked), or an action sheet when there is
 * no anchor (the menu was run by a screen reader's action).
 */
fun OverlayController.presentRemind(
    anchor: Rect?,
    ui: RemindUi,
    onPick: (ReminderSlot) -> Unit,
    onCancel: () -> Unit,
): OverlayEntry {
    if (anchor == null) {
        val actions =
            ui.rows.map { ActionSheetAction("${it.label} · ${it.clock}") { onPick(it.slot) } } +
                listOfNotNull(ui.cancel?.let { ActionSheetAction(it, destructive = true, onClick = onCancel) })
        return present(
            ActionSheetEntry(title = ui.title, actions = actions.toImmutableList(), cancelLabel = ui.cancelSheet),
        )
    }
    val holder = arrayOfNulls<OverlayEntry>(1)
    val entry =
        PopoverEntry(anchor = anchor, title = ui.title) {
            RemindCard(
                ui,
                onPick = {
                    holder[0]?.let(::dismiss)
                    onPick(it)
                },
                onCancel = {
                    holder[0]?.let(::dismiss)
                    onCancel()
                },
            )
        }
    holder[0] = entry
    return present(entry)
}
