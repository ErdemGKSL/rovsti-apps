package dev.rosty.stops.history

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.core.model.stops.HistoryRow
import dev.rosty.core.model.stops.Outcome
import dev.rosty.core.model.stops.StatsAnswer
import dev.rosty.design.controls.ChipModel
import dev.rosty.design.controls.ChipRow
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.SectionHeader
import dev.rosty.design.overlay.ButtonMenuEntry
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.SheetState
import dev.rosty.design.overlay.menuTrigger
import dev.rosty.design.overlay.sheetScrollConnection
import dev.rosty.design.text.SearchField
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.R
import dev.rosty.stops.home.OnSheet
import dev.rosty.stops.home.StopsIcons
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged

object HistoryTags {
    const val Sheet = "sheet-history"
    const val List = "history-list"
    const val Search = "history-search"
    const val Filters = "history-filters"
    const val Stats = "history-stats"
    const val More = "history-more"
    const val Empty = "history-empty"

    fun row(index: Int) = "history-row-$index"
}

/** What the sheet's menu does beyond the history itself: S32 backup and restore. */
interface HistorySheetActions {
    fun backUp()

    fun restore()
}

/**
 * The History sheet (AD-ST8): the statistics, then the trips with search and the outcome filter (S15); a trip opens
 * its menu (Start again, Show on map, Save as stop, Share, Copy, Delete). The header's "..." exports the trips on
 * screen (S16), backs up or restores everything (S32), deletes old trips (S36) or clears the history, each with Undo.
 */
fun OverlayController.presentHistorySheet(
    title: String,
    viewModel: HistoryViewModel,
    actions: HistorySheetActions,
    onDismissed: () -> Unit = {},
): SheetEntry =
    SheetEntry(
        title = title,
        detents = persistentListOf(Detent.Medium, Detent.Large),
        onDismissed = onDismissed,
    ) { sheet ->
        OnSheet { HistoryList(viewModel, actions, this@presentHistorySheet, sheet) }
    }.also { present(it) }

@Composable
private fun ColumnScope.HistoryList(
    viewModel: HistoryViewModel,
    actions: HistorySheetActions,
    overlays: OverlayController,
    sheet: SheetState,
) {
    val state by viewModel.state.collectAsState()
    val onAction = viewModel::onAction
    LaunchedEffect(viewModel) { onAction(HistoryAction.Refresh) }
    Column(Modifier.fillMaxWidth().weight(1f).testTag(HistoryTags.Sheet)) {
        Header(state, actions, overlays, onAction)
        if (state.loading) return@Column
        if (state.total == 0) {
            Box(Modifier.fillMaxSize().testTag(HistoryTags.Empty), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    icon = RostyIcons.Clock,
                    title = stringResource(R.string.history_empty_title),
                    message = stringResource(R.string.history_empty_message),
                )
            }
            return@Column
        }
        Rows(state, overlays, sheet, onAction)
    }
}

@Composable
private fun Header(
    state: HistoryUiState,
    actions: HistorySheetActions,
    overlays: OverlayController,
    onAction: (HistoryAction) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RostyText(
            stringResource(R.string.sheet_history_title),
            Modifier.weight(1f),
            style = RostyTheme.type.title2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MoreButton(overlays, headerMenu(state, actions, onAction))
    }
}

@Composable
private fun headerMenu(
    state: HistoryUiState,
    actions: HistorySheetActions,
    onAction: (HistoryAction) -> Unit,
): ImmutableList<MenuItem> {
    val listed = state.rows.isNotEmpty()
    val csv = stringResource(R.string.history_export_csv)
    val gpx = stringResource(R.string.history_export_gpx)
    val backUp = stringResource(R.string.backup_export)
    val restore = stringResource(R.string.backup_restore)
    val clear = stringResource(R.string.history_clear)
    val prune =
        state.prune.map { choice ->
            pluralStringResource(R.plurals.history_prune_choice, choice.count, choice.count, choice.days) to choice
        }
    return remember(state.prune, state.total, listed, csv, onAction, actions) {
        val items = ArrayList<MenuItem>()
        items +=
            MenuItem.Action(csv, RostyIcons.Share, enabled = listed) {
                onAction(HistoryAction.Export(HistoryViewModel.FORMAT_CSV))
            }
        items +=
            MenuItem.Action(gpx, RostyIcons.Share, enabled = listed) {
                onAction(HistoryAction.Export(HistoryViewModel.FORMAT_GPX))
            }
        items += MenuItem.Separator
        items += MenuItem.Action(backUp) { actions.backUp() }
        items += MenuItem.Action(restore) { actions.restore() }
        if (state.total > 0) {
            items += MenuItem.Separator
            for ((label, choice) in prune) {
                items += MenuItem.Action(label, destructive = true) { onAction(HistoryAction.Prune(choice)) }
            }
            items += MenuItem.Action(clear, RostyIcons.Trash, destructive = true) { onAction(HistoryAction.Clear) }
        }
        items.toImmutableList()
    }
}

@Composable
private fun ColumnScope.Rows(
    state: HistoryUiState,
    overlays: OverlayController,
    sheet: SheetState,
    onAction: (HistoryAction) -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .nestedScroll(sheetScrollConnection(sheet))
            .testTag(HistoryTags.List),
        contentPadding = PaddingValues(bottom = Spacing.l),
    ) {
        item(key = "stats", contentType = "stats") { state.stats?.let { Stats(it) } }
        item(key = "controls", contentType = "controls") { Controls(state, onAction) }
        if (state.rows.isEmpty()) {
            item(key = "none", contentType = "none") {
                EmptyState(
                    icon = RostyIcons.Search,
                    title = stringResource(R.string.history_no_match_title),
                    message = stringResource(R.string.history_no_match_message),
                    modifier = Modifier.fillMaxWidth().testTag(HistoryTags.Empty),
                )
            }
        }
        itemsIndexed(state.rows, key = { _, row -> row.record.id }, contentType = { _, _ -> "trip" }) { i, row ->
            TripRow(row, i, state.rows.size, overlays, onAction)
        }
    }
}

/** The statistics card (GPUI `stats_rows`), from `stops.stats`. */
@Composable
private fun Stats(stats: StatsAnswer) {
    InsetGroupedSection(Modifier.testTag(HistoryTags.Stats), header = stringResource(R.string.history_stats)) {
        row { ListRow(stringResource(R.string.history_stat_trips), meta = stats.trips.toString()) }
        row {
            ListRow(
                stringResource(R.string.history_stat_arrived),
                meta = stringResource(R.string.history_percent, stats.arrivedPercent),
            )
        }
        row { ListRow(stringResource(R.string.history_stat_distance), meta = stats.totalText) }
        row { ListRow(stringResource(R.string.history_stat_average), meta = stats.avgText) }
        row { ListRow(stringResource(R.string.history_stat_last30), meta = stats.last30Days.toString()) }
        stats.top.forEachIndexed { i, top ->
            row {
                ListRow(
                    if (i == 0) stringResource(R.string.history_stat_top) else "",
                    meta = stringResource(R.string.history_top_count, top.label, top.count),
                    trailingShrinks = true,
                )
            }
        }
    }
}

/** "Trips", the search field and the outcome chips (S15). */
@Composable
private fun Controls(
    state: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
) {
    val field = rememberTextFieldState(state.query)
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.distinctUntilChanged().collect { onAction(HistoryAction.Query(it)) }
    }
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(Spacing.sectionGap))
        SectionHeader(stringResource(R.string.history_trips))
        SearchField(
            field,
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(HistoryTags.Search),
            placeholder = stringResource(R.string.history_search),
        )
        Spacer(Modifier.height(Spacing.s))
        val labels =
            HistoryFilter.entries.map { filter ->
                ChipModel(filter.wire, stringResource(filterLabel(filter)))
            }
        ChipRow(
            chips = labels.toImmutableList(),
            selected = state.filter.wire,
            onSelect = { id ->
                HistoryFilter.entries.firstOrNull { it.wire == id }?.let { onAction(HistoryAction.Filter(it)) }
            },
            modifier = Modifier.testTag(HistoryTags.Filters),
        )
        Spacer(Modifier.height(Spacing.s))
    }
}

private fun filterLabel(filter: HistoryFilter): Int =
    when (filter) {
        HistoryFilter.All -> R.string.history_filter_all
        HistoryFilter.Arrived -> R.string.history_filter_arrived
        HistoryFilter.Cancelled -> R.string.history_filter_cancelled
    }

/** One trip: its title, when and how it went; a tap opens its menu at the row. */
@Composable
private fun TripRow(
    row: HistoryRow,
    position: Int,
    count: Int,
    overlays: OverlayController,
    onAction: (HistoryAction) -> Unit,
) {
    val context = LocalContext.current
    val bounds = remember { arrayOf(Rect.Zero) }
    val menu = rowMenu(row, onAction)
    val top = if (position == 0) CARD_CORNER else NO_CORNER
    val bottom = if (position == count - 1) CARD_CORNER else NO_CORNER
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    val ended =
        DateUtils.formatDateTime(
            context,
            row.record.endedMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
        )
    Column(
        Modifier
            .padding(horizontal = Spacing.gutter)
            .clip(shape)
            .background(RostyTheme.colors.groupedSurface)
            .onGloballyPositioned { bounds[0] = it.boundsInWindow() },
    ) {
        if (position > 0) RowSeparator()
        ListRow(
            title = row.title,
            modifier = Modifier.testTag(HistoryTags.row(row.index)),
            secondary = stringResource(R.string.history_row_detail, ended, row.summaryLine),
            // Large text: the pair and its line wrap rather than lose their ends to the outcome.
            titleMaxLines = 2,
            secondaryMaxLines = 3,
            meta =
                stringResource(
                    if (row.record.outcome == Outcome.Arrived) {
                        R.string.history_filter_arrived
                    } else {
                        R.string.history_filter_cancelled
                    },
                ),
            onClick = { overlays.present(ButtonMenuEntry(bounds[0], menu)) },
        )
    }
}

@Composable
private fun rowMenu(
    row: HistoryRow,
    onAction: (HistoryAction) -> Unit,
): ImmutableList<MenuItem> {
    val again = stringResource(R.string.history_start_again)
    val show = stringResource(R.string.history_show_on_map)
    val save = stringResource(R.string.pick_menu_save)
    val share = stringResource(R.string.pick_share)
    val copy = stringResource(R.string.history_copy)
    val delete = stringResource(R.string.history_delete)
    val ends = row.record.a != null && row.record.b != null
    return remember(row, ends, onAction) {
        val items = ArrayList<MenuItem>()
        if (ends) {
            items += MenuItem.Action(again, StopsIcons.Navigation) { onAction(HistoryAction.StartAgain(row)) }
            items += MenuItem.Action(show, RostyIcons.Map) { onAction(HistoryAction.ShowOnMap(row)) }
            items += MenuItem.Action(save, StopsIcons.Bookmark) { onAction(HistoryAction.SaveAsStop(row)) }
            items += MenuItem.Separator
        }
        items += MenuItem.Action(share, RostyIcons.Share) { onAction(HistoryAction.Share(row)) }
        items += MenuItem.Action(copy, RostyIcons.Copy) { onAction(HistoryAction.Copy(row)) }
        items += MenuItem.Separator
        items += MenuItem.Action(delete, RostyIcons.Trash, destructive = true) { onAction(HistoryAction.Delete(row)) }
        items.toImmutableList()
    }
}

/** A 48 dp "..." target that opens [items] on touch down (the Saved sheet's). */
@Composable
private fun MoreButton(
    overlays: OverlayController,
    items: ImmutableList<MenuItem>,
) {
    val label = stringResource(R.string.saved_more)
    Box(
        Modifier
            .size(Sizes.touch)
            .semantics { contentDescription = label }
            .menuTrigger(overlays, items, label)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    waitForUpOrCancellation()?.consume()
                }
            }.testTag(HistoryTags.More),
        contentAlignment = Alignment.Center,
    ) {
        RostyIcon(RostyIcons.Ellipsis, null, tint = RostyTheme.colors.labelSecondary, size = MORE_GLYPH)
    }
}
