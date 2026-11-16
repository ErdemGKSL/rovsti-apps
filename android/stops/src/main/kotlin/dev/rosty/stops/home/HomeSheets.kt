package dev.rosty.stops.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.ListRowChevron
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.SheetMetrics
import dev.rosty.design.overlay.SheetState
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.R
import dev.rosty.stops.StopsSizes
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the home's sheets. */
object HomeSheetTags {
    const val Main = "home-sheet"
    const val Title = "home-sheet-title"
    const val Subtitle = "home-sheet-subtitle"
    const val SavedRow = "home-sheet-saved"
    const val HistoryRow = "home-sheet-history"
    const val TransitRow = "home-sheet-transit"
    const val History = "sheet-history"
}

/**
 * The map sheet's detents before its content is measured; [SheetState.detentOffsets] orders them tallest first: index
 * 0 Large, 1 Medium, 2 Peek. The home replaces the peek with the measured one ([mapSheetDetents]).
 */
internal val MapSheetDetents = mapSheetDetents(StopsSizes.sheetPeek)

/** The map sheet's detents with a peek [peek] tall (the measured content, [mapSheetPeekPx]). */
internal fun mapSheetDetents(peek: Dp): ImmutableList<Detent> =
    persistentListOf(Detent.Peek(peek), Detent.Medium, Detent.Large)

/**
 * What sits above the map sheet's measured content, part of the peek height: the grabber (`:design`'s sheet metrics)
 * and the room above the title ([OnSheet]).
 */
internal val MapSheetGrabber: Dp =
    SheetMetrics.GrabberTop + SheetMetrics.GrabberHeight + SheetMetrics.GrabberBottom + StopsSizes.sheetTitleTop

/** The detent of an index from the tallest ([SheetState.detentOffsets] order); out of range is Peek. */
internal fun mapSheetDetent(fromTallest: Int): Detent =
    when (fromTallest) {
        0 -> Detent.Large
        1 -> Detent.Medium
        else -> MapSheetDetents.first()
    }

/**
 * The sheet over the map (ARCH 8.4): non-modal, so the map pans and takes taps around it at peek and medium; it cannot
 * be closed (Back drops it to peek). It is an overlay entry, presented while the home is on screen and dismissed when a
 * page is pushed over the map; [initialFromTallest] puts it back on the detent it had. [content] is read through a
 * state, so the sheet recomposes with the home.
 */
@Composable
internal fun rememberMapSheet(
    initialFromTallest: Int,
    content: @Composable ColumnScope.(SheetState) -> Unit,
): SheetEntry {
    val overlays = LocalOverlay.current
    val title = stringResource(R.string.home_sheet_title)
    val latest by rememberUpdatedState(content)
    val entry =
        remember(overlays) {
            SheetEntry(
                title = title,
                detents = MapSheetDetents,
                initialDetent = mapSheetDetent(initialFromTallest),
                modal = false,
                dismissible = false,
                compactLargeOnly = false,
            ) { sheet -> OnSheet { latest(this, sheet) } }
        }
    DisposableEffect(overlays, entry) {
        overlays.present(entry)
        onDispose { overlays.dismiss(entry) }
    }
    return entry
}

/**
 * What the map sheet shows until the trip (E7.5), the pick sheets (E7.8) and the saved list (E7.7) fill it: the title,
 * the trip in progress or how to start one (visible at peek), and the rows that open Saved Stops and History.
 */
@Composable
internal fun MapSheetContent(
    state: HomeState,
    onOpenSaved: () -> Unit,
    onOpenHistory: () -> Unit,
    subtitle: String? = null,
    title: String? = null,
    trip: @Composable () -> Unit = {},
) {
    val colors = RostyTheme.colors
    val type = RostyTheme.type
    val peek = LocalMapSheetPeek.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // After the scroll: the content's own coordinates, so the peek measure does not move with the scroll.
            .then(if (peek != null) Modifier.onGloballyPositioned(peek::root) else Modifier)
            .testTag(HomeSheetTags.Main),
    ) {
        Column(Modifier.padding(horizontal = Spacing.gutter)) {
            RostyText(
                title ?: stringResource(R.string.home_sheet_title),
                Modifier.testTag(HomeSheetTags.Title),
                style = type.title3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.textGap))
            val running = state.trip
            RostyText(
                subtitle ?: if (running != null) {
                    stringResource(R.string.home_trip_running, running.label)
                } else {
                    stringResource(R.string.home_sheet_hint)
                },
                // Peek always shows the title and this line (the hint with no plan); the plan and the trip add theirs.
                rememberPeekEnd().testTag(HomeSheetTags.Subtitle),
                style = type.subheadline,
                color = colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Peek ends a gap under its last element ([mapSheetPeekPx]), so what follows starts at the sheet's edge.
        Spacer(Modifier.height(Spacing.l))
        trip()
        Spacer(Modifier.height(Spacing.m))
        InsetGroupedSection {
            row {
                ListRow(
                    title = stringResource(R.string.home_saved_stops),
                    modifier = Modifier.testTag(HomeSheetTags.SavedRow),
                    leading = { RostyIcon(StopsIcons.Bookmark, null, tint = colors.accent) },
                    meta = pluralStringResource(R.plurals.count_stops, state.saved.size, state.saved.size),
                    trailing = { ListRowChevron() },
                    onClick = onOpenSaved,
                )
            }
            row {
                ListRow(
                    title = stringResource(R.string.home_history),
                    modifier = Modifier.testTag(HomeSheetTags.HistoryRow),
                    leading = { RostyIcon(RostyIcons.Clock, null, tint = colors.accent) },
                    meta = pluralStringResource(R.plurals.count_trips, state.historyCount, state.historyCount),
                    trailing = { ListRowChevron() },
                    onClick = onOpenHistory,
                )
            }
            if (state.transitImportedMs != null) {
                row {
                    ListRow(
                        title = stringResource(R.string.home_transit),
                        modifier = Modifier.testTag(HomeSheetTags.TransitRow),
                        leading = { RostyIcon(StopsIcons.Pin, null, tint = colors.accent) },
                        meta = stringResource(R.string.home_transit_loaded),
                    )
                }
            }
        }
    }
}

/** The modal sheets the home opens over the map sheet. */
enum class HomeSheet { Saved, History }
