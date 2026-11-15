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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import dev.rosty.design.overlay.SheetState
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.R
import dev.rosty.stops.StopsSizes
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the home's sheets. */
object HomeSheetTags {
    const val Main = "home-sheet"
    const val Title = "home-sheet-title"
    const val SavedRow = "home-sheet-saved"
    const val HistoryRow = "home-sheet-history"
    const val TransitRow = "home-sheet-transit"
    const val Saved = "sheet-saved"
    const val History = "sheet-history"
    const val Pick = "sheet-pick"
}

/** The map sheet's detents, tallest first: index 0 Large, 1 Medium, 2 Peek ([SheetState.detentOffsets] order). */
internal val MapSheetDetents = persistentListOf(Detent.Peek(StopsSizes.sheetPeek), Detent.Medium, Detent.Large)

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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(HomeSheetTags.Main)) {
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
                style = type.subheadline,
                color = colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The rows start just below what the peek detent shows (grabber, title, one line), so peek ends on a clean edge.
        Spacer(Modifier.height(StopsSizes.peekRowsGap))
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

/** Which placeholder sheet to open over the map. */
enum class HomeSheet { Saved, History, Pick }

/**
 * Presents one of the modal sheets that later stories fill (Saved: E7.7, History: E7.11, Pick: E7.8) over the map
 * sheet; [onDismissed] runs when it has left. [shared] is the text of a link handed to the app (shown by Pick).
 */
internal fun OverlayController.presentHomeSheet(
    which: HomeSheet,
    title: String,
    shared: String? = null,
    onDismissed: () -> Unit = {},
): SheetEntry =
    SheetEntry(
        title = title,
        detents = persistentListOf(Detent.Medium, Detent.Large),
        onDismissed = onDismissed,
    ) { _ -> PlaceholderSheet(which, title, shared) }.also { present(it) }

@Composable
private fun PlaceholderSheet(
    which: HomeSheet,
    title: String,
    shared: String?,
) {
    val tag =
        when (which) {
            HomeSheet.Saved -> HomeSheetTags.Saved
            HomeSheet.History -> HomeSheetTags.History
            HomeSheet.Pick -> HomeSheetTags.Pick
        }
    val message =
        when (which) {
            HomeSheet.Saved -> {
                stringResource(R.string.sheet_saved_message)
            }

            HomeSheet.History -> {
                stringResource(R.string.sheet_history_message)
            }

            HomeSheet.Pick -> {
                shared?.let { stringResource(R.string.sheet_pick_shared, it) }
                    ?: stringResource(R.string.sheet_pick_message)
            }
        }
    val icon =
        when (which) {
            HomeSheet.Saved -> StopsIcons.Bookmark
            HomeSheet.History -> RostyIcons.Clock
            HomeSheet.Pick -> RostyIcons.Search
        }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(tag)) {
        RostyText(title, style = RostyTheme.type.title2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Spacing.xl))
        EmptyState(
            icon = icon,
            title = stringResource(R.string.sheet_coming),
            message = message,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
