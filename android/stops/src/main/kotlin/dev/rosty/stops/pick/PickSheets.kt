package dev.rosty.stops.pick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.core.model.stops.GtfsLineChoice
import dev.rosty.core.model.stops.Slot
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.SectionHeader
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.SheetState
import dev.rosty.design.overlay.sheetScrollConnection
import dev.rosty.design.text.SearchField
import dev.rosty.design.text.text
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.R
import dev.rosty.stops.home.OnSheet
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** Test tags of the pick sheets. */
object PickTags {
    const val Sheet = "pick-sheet"
    const val Field = "pick-field"
    const val Paste = "pick-paste"
    const val List = "pick-list"
    const val Confirm = "pick-confirm"
    const val UseA = "pick-use-a"
    const val UseB = "pick-use-b"
    const val Cancel = "pick-cancel"
    const val StopSheet = "pick-stop-sheet"
    const val CopyAs = "pick-copy-as"

    fun row(key: String) = "pick-row-$key"

    fun stopRow(key: String) = "pick-stop-$key"

    fun line(pattern: Int) = "pick-line-$pattern"
}

/**
 * The pick sheet (E7.8): its title names the end it fills; a search field that also takes a pasted link, coordinates
 * or a Plus Code (S23), a Paste button, then the rows of [PickSheetViewModel]. [field] carries pasted text into the
 * field ([PickEffect.FieldText]).
 */
fun OverlayController.presentPickSheet(
    viewModel: PickSheetViewModel,
    title: String,
    field: PickField,
    clipboard: () -> String?,
    onDismissed: () -> Unit = {},
): SheetEntry =
    SheetEntry(
        title = title,
        detents = persistentListOf(Detent.Medium, Detent.Large),
        onDismissed = onDismissed,
    ) { sheet ->
        OnSheet { PickBody(viewModel, field, clipboard, sheet) }
    }.also { present(it) }

/** A hand-over of text into the open pick sheet's field (a paste that is not a place). */
class PickField {
    internal val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun set(text: String) {
        pending.value = text
    }
}

@Composable
private fun ColumnScope.PickBody(
    viewModel: PickSheetViewModel,
    field: PickField,
    clipboard: () -> String?,
    sheet: SheetState,
) {
    val state by viewModel.state.collectAsState()
    val text = rememberTextFieldState()
    LaunchedEffect(text) {
        snapshotFlow { text.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .collect { viewModel.onAction(PickAction.Query(it)) }
    }
    val handed by field.pending.collectAsState()
    LaunchedEffect(handed) {
        handed?.let {
            text.edit { replace(0, length, it) }
            field.pending.value = null
        }
    }
    Column(Modifier.fillMaxWidth().weight(1f).testTag(PickTags.Sheet)) {
        RostyText(
            state.title,
            Modifier.padding(horizontal = Spacing.gutter),
            style = RostyTheme.type.title2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Large text: the field takes the whole width (its hint would be cut to two words) and Paste goes under it.
        val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
        val paste: @Composable (Modifier) -> Unit = { modifier ->
            CapsuleButton(
                stringResource(R.string.pick_paste),
                { viewModel.onAction(PickAction.Paste(clipboard())) },
                modifier.testTag(PickTags.Paste),
            )
        }
        if (stacked) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                horizontalAlignment = Alignment.End,
            ) {
                SearchField(
                    text,
                    Modifier.fillMaxWidth().testTag(PickTags.Field),
                    placeholder = stringResource(R.string.pick_field),
                )
                paste(Modifier)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                SearchField(
                    text,
                    Modifier.weight(1f).testTag(PickTags.Field),
                    placeholder = stringResource(R.string.pick_field),
                )
                paste(Modifier)
            }
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .nestedScroll(sheetScrollConnection(sheet))
                .testTag(PickTags.List),
            contentPadding = PaddingValues(bottom = Spacing.l),
        ) {
            items(
                state.rows,
                key = { it.key },
            ) { row -> PickRowView(row) { viewModel.onAction(PickAction.Choose(row)) } }
        }
    }
}

@Composable
private fun PickRowView(
    row: PickRow,
    onClick: () -> Unit,
) {
    val tag = Modifier.testTag(PickTags.row(row.key))
    when (row) {
        is PickRow.Header -> {
            SectionHeader(row.text.text(), Modifier.padding(top = Spacing.s))
        }

        is PickRow.Offer -> {
            ListRow(row.title, tag, secondary = row.details, onClick = onClick, secondaryMaxLines = 2)
        }

        is PickRow.Refusal -> {
            Footnote(row.text.text(), tag)
        }

        is PickRow.NoMatch -> {
            Footnote(row.text, tag)
        }

        PickRow.Hint -> {
            Footnote(stringResource(R.string.pick_hint), tag)
        }

        is PickRow.Stop -> {
            val lines =
                if (row.lines ==
                    0
                ) {
                    stringResource(R.string.pick_no_lines)
                } else {
                    pluralStringResource(R.plurals.pick_lines, row.lines, row.lines)
                }
            val detail = row.distance?.let { stringResource(R.string.pick_stop_detail, it, lines) } ?: lines
            ListRow(row.name, tag, secondary = detail, onClick = onClick)
        }

        is PickRow.Saved -> {
            val detail =
                row.distance?.let { stringResource(R.string.saved_gate_away, row.length, it) }
                    ?: stringResource(R.string.saved_gate, row.length)
            ListRow(row.name, tag, secondary = detail, onClick = onClick)
        }

        is PickRow.Pair -> {
            ListRow(row.title, tag, onClick = onClick)
        }

        is PickRow.Recent -> {
            ListRow(row.title, tag, onClick = onClick)
        }
    }
}

@Composable
private fun Footnote(
    text: String,
    modifier: Modifier = Modifier,
) {
    RostyText(
        text,
        modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s),
        style = RostyTheme.type.footnote,
        color = RostyTheme.colors.labelSecondary,
    )
}

/**
 * The Confirm sheet (GPUI `confirm_body`): a place from a shared link, a paste or a typed point, its title ("Use
 * Taksim Square" / "Use this point") and details, then Use as A, Use as B and Cancel.
 */
fun OverlayController.presentConfirmSheet(
    title: String,
    place: ConfirmPlace,
    onUse: (Slot) -> Unit,
): SheetEntry =
    SheetEntry(title = title, detents = persistentListOf(Detent.Medium)) { sheet ->
        OnSheet {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(PickTags.Confirm),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                RostyText(place.title, style = RostyTheme.type.title2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                RostyText(place.details, style = RostyTheme.type.subheadline, color = RostyTheme.colors.labelSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    CapsuleButton(
                        stringResource(R.string.pick_use_a),
                        {
                            sheet.dismiss()
                            onUse(Slot.A)
                        },
                        Modifier.weight(1f).testTag(PickTags.UseA),
                        style = CapsuleStyle.Filled,
                        fillWidth = true,
                    )
                    CapsuleButton(
                        stringResource(R.string.pick_use_b),
                        {
                            sheet.dismiss()
                            onUse(Slot.B)
                        },
                        Modifier.weight(1f).testTag(PickTags.UseB),
                        style = CapsuleStyle.Filled,
                        fillWidth = true,
                    )
                }
                CapsuleButton(
                    stringResource(R.string.pick_cancel),
                    { sheet.dismiss() },
                    Modifier.fillMaxWidth().testTag(PickTags.Cancel),
                    fillWidth = true,
                )
            }
        }
    }.also { present(it) }

/** What the stop sheet shows (S7), read by [StopSheetData]. */
class StopSheetModel(
    val stop: PickRow.Stop,
    /** "1.2 km from you, NE", when the position is known. */
    val fromYou: String?,
    val lines: ImmutableList<GtfsLineChoice>,
)

/** What the stop sheet's rows do. */
interface StopSheetActions {
    fun line(
        stop: PickRow.Stop,
        line: GtfsLineChoice,
    )

    fun copyName(stop: PickRow.Stop)

    fun copyCode(stop: PickRow.Stop)

    fun share(stop: PickRow.Stop)

    fun copyAs(stop: PickRow.Stop)

    fun use(
        stop: PickRow.Stop,
        slot: Slot,
    )
}

/**
 * The sheet of a transit stop (S7, GPUI `stop_body`): its name and distance, the lines that call there ("Show a line
 * on the map": choosing one puts the stop on B with that line), then This stop: Copy name, Copy stop code, Share,
 * Copy as, Use as A, Use as B.
 */
fun OverlayController.presentStopSheet(
    model: StopSheetModel,
    actions: StopSheetActions,
): SheetEntry =
    SheetEntry(title = model.stop.name, detents = persistentListOf(Detent.Medium, Detent.Large)) { sheet ->
        OnSheet { StopBody(model, actions, sheet) }
    }.also { present(it) }

@Composable
private fun StopBody(
    model: StopSheetModel,
    actions: StopSheetActions,
    sheet: SheetState,
) {
    val stop = model.stop
    val leave: (() -> Unit) -> Unit = { action ->
        sheet.dismiss()
        action()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .nestedScroll(sheetScrollConnection(sheet))
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.l)
            .testTag(PickTags.StopSheet),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Column(Modifier.padding(horizontal = Spacing.gutter)) {
            RostyText(stop.name, style = RostyTheme.type.title2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            model.fromYou?.let {
                RostyText(it, style = RostyTheme.type.footnote, color = RostyTheme.colors.labelSecondary)
            }
        }
        if (model.lines.isNotEmpty()) {
            InsetGroupedSection(header = stringResource(R.string.pick_show_line)) {
                for (line in model.lines) {
                    row {
                        ListRow(
                            line.title,
                            Modifier.testTag(PickTags.line(line.pattern)),
                            secondary = line.earlier,
                            onClick = { leave { actions.line(stop, line) } },
                        )
                    }
                }
            }
        }
        InsetGroupedSection(header = stringResource(R.string.pick_this_stop)) {
            row { StopRow(stringResource(R.string.pick_copy_name), "copy-name") { actions.copyName(stop) } }
            row { StopRow(stringResource(R.string.pick_copy_code), "copy-code") { actions.copyCode(stop) } }
            row { StopRow(stringResource(R.string.pick_share), "share") { actions.share(stop) } }
            row { StopRow(stringResource(R.string.pick_copy_as), "copy-as") { actions.copyAs(stop) } }
            row { StopRow(stringResource(R.string.pick_use_a), "use-a") { leave { actions.use(stop, Slot.A) } } }
            row { StopRow(stringResource(R.string.pick_use_b), "use-b") { leave { actions.use(stop, Slot.B) } } }
        }
    }
}

@Composable
private fun StopRow(
    label: String,
    key: String,
    onClick: () -> Unit,
) {
    ListRow(label, Modifier.testTag(PickTags.stopRow(key)), onClick = onClick, titleColor = RostyTheme.colors.accent)
}

/** One way to write a place (S23 Copy as): its name and the text. */
class CopyFormat(
    val label: String,
    val text: String,
)

/** "Copy coordinates as" (GPUI `copy_as_body`): the formats of `stops.coord_format`; a tap copies one. */
fun OverlayController.presentCopyAsSheet(
    title: String,
    formats: ImmutableList<CopyFormat>,
    onCopy: (CopyFormat) -> Unit,
): SheetEntry =
    SheetEntry(title = title, detents = persistentListOf(Detent.Medium)) { sheet ->
        OnSheet {
            Column(
                Modifier.fillMaxWidth().testTag(PickTags.CopyAs),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                RostyText(title, Modifier.padding(horizontal = Spacing.gutter), style = RostyTheme.type.title3)
                InsetGroupedSection {
                    for (format in formats) {
                        row {
                            ListRow(
                                format.label,
                                Modifier.testTag(PickTags.stopRow("format-${format.label}")),
                                secondary = format.text,
                                onClick = {
                                    sheet.dismiss()
                                    onCopy(format)
                                },
                            )
                        }
                    }
                }
            }
        }
    }.also { present(it) }

/** From this font scale the pick field and Paste stack. */
private const val STACK_FONT_SCALE = 1.5f
