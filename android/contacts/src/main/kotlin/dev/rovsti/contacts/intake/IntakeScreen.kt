package dev.rovsti.contacts.intake

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.overlay.sheetScrollConnection
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the import page and its sheet. */
object IntakeTags {
    const val Page = "contacts-intake-page"
    const val Status = "intake-status"
    const val Sheet = "intake-sheet"
    const val List = "intake-list"
    const val Add = "intake-add"
    const val AddToExisting = "intake-add-existing"
    const val Cancel = "intake-cancel"
    const val Card = "intake-card"
    const val CardBack = "intake-card-back"

    fun row(index: Int) = "intake-row-$index"
}

/**
 * The page SEND, VIEW, "Import from a File" and "Paste Contact" land on (E4.10). It is a quiet large-title page with
 * one status line; the work is in the preview sheet that rises over it as soon as the cards are known (see
 * [presentIntakeSheet]). Closing the sheet by drag, scrim or back is Cancel and the page leaves with it; the page
 * itself never holds content that the sheet could push.
 */
@Composable
fun IntakeScreen(
    state: IntakeUiState,
    onAction: (IntakeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overlays = LocalOverlay.current
    val holder = rememberUpdatedState(state)
    val actions = rememberUpdatedState(onAction)
    var entry by remember { mutableStateOf<SheetEntry?>(null) }
    val sheetWanted = state.phase == IntakePhase.Preview || state.phase == IntakePhase.Importing
    LaunchedEffect(sheetWanted) {
        if (sheetWanted && entry == null) {
            entry = overlays.presentIntakeSheet(holder) { actions.value(it) }
        }
    }
    DisposableEffect(overlays) { onDispose { entry?.let(overlays::dismiss) } }
    val listState = rememberLazyListState()
    LargeTitleScaffold(
        title = "Import",
        state = listState,
        actions = persistentListOf(),
        navigation = NavigationButton(onClick = { onAction(IntakeAction.Cancel) }),
        modifier = modifier.testTag(IntakeTags.Page),
    ) {
        statusItem(state.phase)
    }
}

private fun LazyListScope.statusItem(phase: IntakePhase) {
    item(key = "status", contentType = "status") {
        val bottom = LocalContentInsets.current.calculateBottomPadding()
        Box(
            Modifier
                .fillParentMaxHeight()
                .fillMaxWidth()
                .padding(bottom = bottom)
                .testTag(IntakeTags.Status),
            contentAlignment = Alignment.Center,
        ) {
            if (phase == IntakePhase.Reading) {
                EmptyState(RovstiIcons.Download, "Reading Contacts", "Looking for people in the file.")
            } else {
                EmptyState(RovstiIcons.Download, "Import Contacts", "Choose what to add in the sheet.")
            }
        }
    }
}

/**
 * The preview sheet "Add these contacts?" in the large detent: a count line, one row per card (one avatar each, the
 * card's picture when it has one) and the buttons under the list. Closing it any way except the buttons is Cancel.
 * [state] is read inside the sheet, so it follows the model without the sheet being presented again.
 */
fun OverlayController.presentIntakeSheet(
    state: State<IntakeUiState>,
    onAction: (IntakeAction) -> Unit,
): SheetEntry {
    val entry =
        SheetEntry(
            title = "Add these contacts?",
            detents = persistentListOf(Detent.Large),
            onDismissed = { onAction(IntakeAction.Cancel) },
            canDismiss = { state.value.phase != IntakePhase.Importing },
        ) { sheet ->
            IntakeSheetContent(state.value, onAction, sheet)
        }
    present(entry)
    return entry
}

@Composable
internal fun ColumnScope.IntakeSheetContent(
    state: IntakeUiState,
    onAction: (IntakeAction) -> Unit,
    sheet: SheetState?,
) {
    val preview = state.preview
    if (preview != null) {
        CardPreviewContent(preview, onBack = { onAction(IntakeAction.CloseCard) }, sheet = sheet)
        return
    }
    Column(Modifier.weight(1f).fillMaxWidth().testTag(IntakeTags.Sheet)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = IntakeMetrics.HeaderTop),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RovstiText(
                state.title,
                style = RovstiTheme.type.title3,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RovstiText(
                state.countLine,
                Modifier.padding(top = Spacing.xs, bottom = Spacing.s),
                style = RovstiTheme.type.footnote,
                color = RovstiTheme.colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        val scroll = if (sheet != null) Modifier.nestedScroll(sheetScrollConnection(sheet)) else Modifier
        LazyColumn(scroll.weight(1f).fillMaxWidth().testTag(IntakeTags.List)) {
            items(state.rows, key = { it.index }, contentType = { "card" }) { row ->
                Column {
                    ListRow(
                        title = row.name,
                        secondary = row.secondary,
                        leading = { Avatar(row.name, size = Sizes.avatar, photo = row.photo, colorKey = row.colorKey) },
                        trailing = { ListRowChevron() },
                        onClick = { onAction(IntakeAction.OpenCard(row.index)) },
                        modifier = Modifier.testTag(IntakeTags.row(row.index)),
                    )
                    RowSeparator(inset = TextStartWithLeading)
                }
            }
        }
        ButtonBar(state, onAction)
    }
}

/** The buttons under the list: the list ends above them, so the last row is never covered. */
@Composable
private fun ButtonBar(
    state: IntakeUiState,
    onAction: (IntakeAction) -> Unit,
) {
    val importing = state.phase == IntakePhase.Importing
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = Spacing.s, bottom = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        CapsuleButton(
            if (importing) "Adding…" else state.addLabel,
            onClick = { onAction(IntakeAction.Add) },
            modifier = Modifier.fillMaxWidth().testTag(IntakeTags.Add),
            style = CapsuleStyle.Filled,
            enabled = state.canAdd,
            fillWidth = true,
        )
        if (state.canAddToExisting) {
            CapsuleButton(
                "Add to Existing Contact",
                onClick = { onAction(IntakeAction.AddToExisting) },
                modifier = Modifier.fillMaxWidth().testTag(IntakeTags.AddToExisting),
                enabled = state.canAdd,
                fillWidth = true,
            )
        }
        // Cancel is plain accent text, as in an iOS sheet: a glass capsule at the very bottom of a sheet samples the
        // sheet's own rim and draws a dark smear there.
        RovstiText(
            "Cancel",
            Modifier
                .fillMaxWidth()
                .heightIn(min = IntakeMetrics.BarButtonHeight)
                .clickable(enabled = !importing, role = Role.Button) { onAction(IntakeAction.Cancel) }
                .padding(vertical = Spacing.m)
                .testTag(IntakeTags.Cancel),
            style = RovstiTheme.type.body,
            color = if (importing) RovstiTheme.colors.labelTertiary else RovstiTheme.colors.accent,
            textAlign = TextAlign.Center,
        )
    }
}

/** The read-only card of one imported contact (C31), in place of the list; "Back" returns to the list. */
@Composable
internal fun ColumnScope.CardPreviewContent(
    preview: CardPreview,
    onBack: () -> Unit,
    sheet: SheetState?,
) {
    val colors = RovstiTheme.colors
    Column(Modifier.weight(1f).fillMaxWidth().testTag(IntakeTags.Card)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RovstiText(
                "Back",
                Modifier
                    .heightIn(min = IntakeMetrics.BarButtonHeight)
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(vertical = Spacing.m)
                    .testTag(IntakeTags.CardBack),
                style = RovstiTheme.type.body,
                color = colors.accent,
            )
        }
        val scroll = if (sheet != null) Modifier.nestedScroll(sheetScrollConnection(sheet)) else Modifier
        LazyColumn(scroll.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            item(key = "head", contentType = "head") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = IntakeCardMetrics.Top),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(
                        preview.name,
                        size = IntakeCardMetrics.Avatar,
                        photo = preview.photo,
                        colorKey = preview.colorKey,
                    )
                    RovstiText(
                        preview.name,
                        Modifier.padding(top = Spacing.m),
                        style = RovstiTheme.type.title2,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    preview.subtitle?.let {
                        RovstiText(
                            it,
                            style = RovstiTheme.type.subheadline,
                            color = colors.labelSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            items(preview.sections, key = { it.title.orEmpty() }, contentType = { "section" }) { section ->
                InsetGroupedSection(header = section.title) {
                    for (line in section.lines) {
                        row {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.gutter, vertical = IntakeCardMetrics.LineVertical),
                                verticalArrangement = Arrangement.spacedBy(IntakeCardMetrics.LineGap),
                            ) {
                                RovstiText(
                                    line.label,
                                    style = RovstiTheme.type.footnote,
                                    color = colors.labelSecondary,
                                    maxLines = 1,
                                )
                                RovstiText(line.value, style = RovstiTheme.type.body)
                            }
                        }
                    }
                }
            }
            item(key = "end", contentType = "end") { Box(Modifier.fillMaxSize().padding(bottom = Spacing.m)) }
        }
    }
}
