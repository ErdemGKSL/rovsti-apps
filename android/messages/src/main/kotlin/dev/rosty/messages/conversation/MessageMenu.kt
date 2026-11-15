package dev.rosty.messages.conversation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosContextMenuEntry
import dev.rosty.design.ios.component.IosMenuItem
import dev.rosty.design.list.ListRow
import dev.rosty.design.overlay.ContextMenuTarget
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayEntry
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.contextMenu
import dev.rosty.design.overlay.rememberPressRamp
import dev.rosty.design.overlay.toSections
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.theme.MessagesMetrics
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Long-press menu of a bubble (E6.5, AC1): the bubble lifts and the glass panel opens next to it, below or above by
 * placement (`IosContextMenuEntry`), with a row of [reactions] at the top of the panel when the chat takes reactions.
 * The list does not scroll or move under it: the lifted copy stands in at the bubble's window rect.
 */
@Composable
fun Modifier.bubbleMenu(
    controller: OverlayController,
    items: ImmutableList<MenuItem>,
    reactions: List<String>,
    onReact: (String) -> Unit,
    onTap: () -> Unit,
    preview: @Composable () -> Unit,
): Modifier {
    val target = remember { ContextMenuTarget() }
    val press = rememberPressRamp()
    val live = remember { arrayOfNulls<OverlayEntry>(1) }
    val latest = rememberUpdatedState(Triple(items, reactions, preview))
    val react = rememberUpdatedState(onReact)
    return contextMenu(
        anchor = target.anchor,
        press = press,
        onTap = onTap,
        onOpen = { rect ->
            val current = live[0]
            if (current == null || !current.state.targetState) {
                val (menu, emoji, lifted) = latest.value
                val entry =
                    IosContextMenuEntry(
                        anchor = rect,
                        sections = menu.toSections(),
                        quickActions =
                            emoji.map { e ->
                                IosMenuItem(title = "", icon = { _ ->
                                    RostyText(e, style = RostyTheme.type.title3)
                                }, onClick = {
                                    react.value(e)
                                })
                            },
                        lifted = {
                            Box(
                                Modifier
                                    .testTag(ConversationTags.Lifted)
                                    .clip(RoundedCornerShape(MessagesMetrics.BubbleCorner))
                                    .clearAndSetSemantics { },
                            ) { lifted() }
                        },
                        target = target,
                        openHaptic = true,
                        panelTag = ConversationTags.BubblePanel,
                        debugTag = "bubble.panel",
                    )
                live[0] = entry
                controller.present(entry)
            }
        },
        onPreviewDrag = {},
        onPreviewRelease = {},
        actions = items,
        cover = { target.cover.floatValue },
    )
}

/** The tap pills under a bubble (detection, OTP code). */
@Composable
internal fun PillsRow(
    pills: List<PillUi>,
    outgoing: Boolean,
    onPill: (PillUi) -> Unit,
) {
    if (pills.isEmpty()) return
    Row(
        Modifier
            .padding(top = Spacing.xs)
            .horizontalScroll(rememberScrollState())
            .testTag(ConversationTags.Pills),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, if (outgoing) Alignment.End else Alignment.Start),
    ) {
        pills.forEach { pill ->
            CapsuleButton(pill.label, onClick = { onPill(pill) }, icon = pill.kind.icon())
        }
    }
}

private fun PillKind.icon() =
    when (this) {
        PillKind.Call -> RostyIcons.Phone
        PillKind.Message -> RostyIcons.Message
        PillKind.CopyNumber, PillKind.CopyEmail, PillKind.CopyCode -> RostyIcons.Copy
        PillKind.Email -> RostyIcons.Mail
        PillKind.Calendar -> RostyIcons.CalendarPlus
        PillKind.Map -> RostyIcons.Map
        PillKind.Link -> RostyIcons.Globe
    }

/** The check circle of a bubble in select mode (M20). */
@Composable
internal fun SelectCircle(checked: Boolean) {
    val colors = RostyTheme.colors
    Box(
        Modifier
            .padding(end = Spacing.s)
            .size(MessagesMetrics.SelectCircle)
            .clip(CircleShape)
            .background(if (checked) colors.accent else colors.fillSecondary)
            .semantics { selected = checked },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) RostyIcon(RostyIcons.Checkmark, null, tint = colors.onAccent, size = MessagesMetrics.TickGlyph)
    }
}

/** What the select bar does with the chosen messages. */
enum class SelectAction { Copy, Forward, Delete, Cancel }

/** The words of select mode. */
@Immutable
class SelectLabels(
    val count: (Int) -> String,
    val copy: String,
    val forward: String,
    val delete: String,
    val cancel: String,
)

/** The bar that stands in for the composer in select mode (M20): the count, Copy, Forward, Delete, Cancel. */
@Composable
internal fun SelectBar(
    count: Int,
    labels: SelectLabels,
    onAction: (SelectAction) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
            .testTag(ConversationTags.SelectBar),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            RostyText(
                labels.count(count),
                modifier = Modifier.weight(1f),
                style = RostyTheme.type.footnote,
                color = RostyTheme.colors.labelSecondary,
            )
            RostyText(
                labels.cancel,
                modifier =
                    Modifier
                        .clickable { onAction(SelectAction.Cancel) }
                        .padding(Spacing.s)
                        .semantics { contentDescription = labels.cancel },
                color = RostyTheme.colors.accent,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            CapsuleButton(
                labels.copy,
                onClick = { onAction(SelectAction.Copy) },
                modifier = Modifier.weight(1f),
                icon = RostyIcons.Copy,
                fillWidth = true,
            )
            CapsuleButton(
                labels.forward,
                onClick = { onAction(SelectAction.Forward) },
                modifier = Modifier.weight(1f),
                icon = RostyIcons.Forward,
                fillWidth = true,
            )
            CapsuleButton(
                labels.delete,
                onClick = { onAction(SelectAction.Delete) },
                modifier = Modifier.weight(1f),
                icon = RostyIcons.Trash,
                tint = RostyTheme.colors.destructive,
                fillWidth = true,
            )
        }
    }
}

/** M16: the whole text of a message in a sheet, selectable by hand. */
fun OverlayController.presentSelectText(
    c: Context,
    text: String,
) {
    present(
        SheetEntry(
            title = c.getString(R.string.menu_select_text),
            detents = persistentListOf(Detent.Medium, Detent.Large),
        ) { _ ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.gutter)) {
                SelectionContainer { RostyText(text, modifier = Modifier.testTag(ConversationTags.SelectText)) }
            }
        },
    )
}

/** M14: when a message was sent and where it stands. */
fun OverlayController.presentInfo(
    c: Context,
    rows: List<Pair<String, String>>,
) {
    present(
        SheetEntry(title = c.getString(R.string.menu_info), detents = persistentListOf(Detent.Medium)) { _ ->
            Column(Modifier.fillMaxWidth().testTag(ConversationTags.Info)) {
                rows.forEach { (label, value) -> ListRow(title = label, meta = value) }
            }
        },
    )
}
