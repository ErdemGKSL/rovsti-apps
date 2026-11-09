package dev.rovsti.contacts.intake

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.overlay.ButtonMenuEntry
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.overlay.sheetScrollConnection
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Test tags of the mapping sheet. */
object MappingTags {
    const val Sheet = "csv-mapping-sheet"
    const val List = "csv-mapping-list"
    const val Count = "csv-mapping-count"
    const val Continue = "csv-mapping-continue"
    const val Cancel = "csv-mapping-cancel"

    fun column(index: Int) = "csv-mapping-column-$index"
}

/** The words of a role on a row: the chosen or guessed one, or what the file says for a column that is left alone. */
internal fun CsvColumn.roleLabel(): String = shown?.label ?: "Automatic"

/**
 * The sheet "Match the Columns" in the large detent: one row per column of the file (its header and the first value),
 * the role at the row's end. Tapping a row opens a menu of the five roles over the sheet, anchored to the role label;
 * the rows stay where they are while it is open. The line under the title follows the Rust reader (the mapping is read
 * again after each change). Closing the sheet any way except Continue is Cancel while the page is still in the mapping.
 */
fun OverlayController.presentMappingSheet(
    state: State<IntakeUiState>,
    onAction: (IntakeAction) -> Unit,
): SheetEntry {
    val entry =
        SheetEntry(
            title = "Match the Columns",
            detents = persistentListOf(Detent.Large),
            onDismissed = { if (state.value.phase == IntakePhase.Mapping) onAction(IntakeAction.Cancel) },
        ) { sheet ->
            MappingSheetContent(state.value, onAction, sheet)
        }
    present(entry)
    return entry
}

@Composable
internal fun ColumnScope.MappingSheetContent(
    state: IntakeUiState,
    onAction: (IntakeAction) -> Unit,
    sheet: SheetState?,
) {
    val mapping = state.mapping ?: return
    val overlays = LocalOverlay.current
    Column(Modifier.weight(1f).fillMaxWidth().testTag(MappingTags.Sheet)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = IntakeMetrics.HeaderTop),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RovstiText(
                "Match the Columns",
                style = RovstiTheme.type.title3,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RovstiText(
                "Choose what each column holds. A column marked Automatic is read as its name says.",
                Modifier.padding(top = Spacing.xs),
                style = RovstiTheme.type.footnote,
                color = RovstiTheme.colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
            RovstiText(
                mapping.countLine,
                Modifier.padding(top = Spacing.xs, bottom = Spacing.s).testTag(MappingTags.Count),
                style = RovstiTheme.type.footnote,
                color = if (mapping.canContinue) RovstiTheme.colors.accent else RovstiTheme.colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        val scroll = if (sheet != null) Modifier.nestedScroll(sheetScrollConnection(sheet)) else Modifier
        LazyColumn(scroll.weight(1f).fillMaxWidth().testTag(MappingTags.List)) {
            items(mapping.columns, key = { it.index }, contentType = { "column" }) { column ->
                Column {
                    ColumnRow(column, overlays, onAction)
                    RowSeparator()
                }
            }
        }
        MappingButtons(mapping.canContinue, onAction)
    }
}

@Composable
private fun ColumnRow(
    column: CsvColumn,
    overlays: OverlayController,
    onAction: (IntakeAction) -> Unit,
) {
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val items =
        remember(column.index, column.shown, onAction) {
            CsvRole.entries
                .map { role ->
                    MenuItem.Action(role.label, checked = column.shown == role) {
                        onAction(IntakeAction.SetRole(column.index, role))
                    }
                }.toImmutableList<MenuItem>()
        }
    ListRow(
        title = column.header.ifBlank { "Column ${column.index + 1}" },
        secondary = column.sample.ifBlank { null },
        trailing = {
            Row(
                Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                RovstiText(
                    column.roleLabel(),
                    Modifier.widthIn(max = MappingMetrics.RoleMaxWidth),
                    style = RovstiTheme.type.body,
                    color = RovstiTheme.colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RovstiIcon(
                    RovstiIcons.ChevronRight,
                    null,
                    tint = RovstiTheme.colors.accent,
                    size = MappingMetrics.Chevron,
                    modifier = Modifier.rotate(CHEVRON_DOWN),
                )
            }
        },
        onClick = { overlays.present(ButtonMenuEntry(anchor, items)) },
        modifier = Modifier.testTag(MappingTags.column(column.index)),
    )
}

private const val CHEVRON_DOWN = 90f

@Composable
private fun MappingButtons(
    canContinue: Boolean,
    onAction: (IntakeAction) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = Spacing.s, bottom = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        CapsuleButton(
            "Continue",
            onClick = { onAction(IntakeAction.ConfirmMapping) },
            modifier = Modifier.fillMaxWidth().testTag(MappingTags.Continue),
            style = CapsuleStyle.Filled,
            enabled = canContinue,
            fillWidth = true,
        )
        RovstiText(
            "Cancel",
            Modifier
                .fillMaxWidth()
                .heightIn(min = IntakeMetrics.BarButtonHeight)
                .clickable(role = Role.Button) { onAction(IntakeAction.Cancel) }
                .padding(vertical = Spacing.m)
                .testTag(MappingTags.Cancel),
            style = RovstiTheme.type.body,
            color = RovstiTheme.colors.accent,
            textAlign = TextAlign.Center,
        )
    }
}
