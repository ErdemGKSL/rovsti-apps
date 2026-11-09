package dev.rovsti.contacts.dupes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import dev.rovsti.design.chrome.ToolbarCapsule
import dev.rovsti.design.chrome.ToolbarTextButton
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
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.overlay.sheetScrollConnection
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow

/** Test tags of the duplicates page and its review sheet. */
object DupesTags {
    const val Screen = "dupes-screen"
    const val Empty = "dupes-empty"
    const val Summary = "dupes-summary"
    const val MergeAll = "dupes-merge-all"
    const val Sheet = "dupes-sheet"
    const val Sources = "dupes-sources"
    const val Merge = "dupes-merge"
    const val Cancel = "dupes-cancel"

    fun row(key: Long) = "dupes-row-$key"
}

/**
 * The duplicates page (E4.11, C8), stateless. One row per group: ONE avatar (the keeper's), the keeper's name and one
 * line "and 2 more · Same number" - never an overlapping stack, never a pill. "Merge All" in the title bar asks first.
 * A tap opens the review sheet ([presentMergeReview]). Nothing here is ever covered by the bars: the list pads for them
 * and an empty page fills the room above.
 */
@Composable
fun DupesScreen(
    state: DupesUiState,
    onAction: (DupesAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    overlays: OverlayController = LocalOverlay.current,
) {
    ReviewSheetHost(state, onAction, overlays)
    LargeTitleScaffold(
        title = "Duplicates",
        state = listState,
        actions = persistentListOf(),
        navigation = NavigationButton(onClick = onBack),
        modifier = modifier.testTag(DupesTags.Screen),
        actionsContent =
            if (state.rows.isEmpty()) {
                null
            } else {
                {
                    ToolbarCapsule {
                        ToolbarTextButton(
                            "Merge All",
                            { onAction(DupesAction.AskMergeAll) },
                            Modifier.testTag(DupesTags.MergeAll),
                            bold = true,
                        )
                    }
                }
            },
    ) {
        if (state.isEmpty) {
            item(key = "empty", contentType = "empty") {
                val bottom = LocalContentInsets.current.calculateBottomPadding()
                Box(
                    Modifier
                        .fillParentMaxHeight()
                        .fillMaxWidth()
                        .padding(bottom = bottom)
                        .testTag(DupesTags.Empty),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = RovstiIcons.People,
                        title = "No Duplicates",
                        message = "Contacts that share a number, an email or a name show up here.",
                    )
                }
            }
        } else if (state.phase == DupesPhase.Ready) {
            item(key = "summary", contentType = "summary") {
                RovstiText(
                    state.summary,
                    Modifier
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
                        .testTag(DupesTags.Summary),
                    style = RovstiTheme.type.footnote,
                    color = RovstiTheme.colors.labelSecondary,
                )
            }
            state.rows.forEachIndexed { index, row ->
                item(key = row.key, contentType = "dupe") {
                    Column {
                        if (index > 0) RowSeparator(inset = TextStartWithLeading)
                        ListRow(
                            title = row.name,
                            secondary = row.secondary,
                            leading = {
                                Avatar(
                                    row.name,
                                    size = Sizes.avatar,
                                    photoModel = row.photoUri,
                                    colorKey = row.colorKey,
                                )
                            },
                            trailing = { ListRowChevron() },
                            onClick = { onAction(DupesAction.Open(row.key)) },
                            modifier = Modifier.testTag(DupesTags.row(row.key)),
                        )
                    }
                }
            }
        }
    }
}

/** Presents the review sheet while the state holds a review, and takes it away when the review goes. */
@Composable
private fun ReviewSheetHost(
    state: DupesUiState,
    onAction: (DupesAction) -> Unit,
    overlays: OverlayController,
) {
    val holder = rememberUpdatedState(state)
    val actions = rememberUpdatedState(onAction)
    var entry by remember { mutableStateOf<SheetEntry?>(null) }
    val open = state.review != null
    LaunchedEffect(open) {
        if (open && entry == null) {
            entry = overlays.presentMergeReview({ holder.value }) { actions.value(it) }
        } else if (!open) {
            entry?.let(overlays::dismiss)
            entry = null
        }
    }
    DisposableEffect(overlays) { onDispose { entry?.let(overlays::dismiss) } }
}

/**
 * The review sheet "Merge" in the large detent: the merged card as `contacts.merge_plan` writes it (one avatar, the
 * name, the fields by section), ONE line saying where it comes from, and the buttons under the list. Dragging the sheet
 * away is Cancel. [state] is read inside the sheet, so it follows the model without being presented again.
 */
fun OverlayController.presentMergeReview(
    state: () -> DupesUiState,
    onAction: (DupesAction) -> Unit,
): SheetEntry {
    val entry =
        SheetEntry(
            title = "Merge",
            detents = persistentListOf(Detent.Large),
            onDismissed = { onAction(DupesAction.CloseReview) },
        ) { sheet ->
            val current = state()
            val review = current.review
            if (review != null) MergeReviewContent(review, current.busy, onAction, sheet)
        }
    present(entry)
    return entry
}

@Composable
internal fun ColumnScope.MergeReviewContent(
    review: MergeReview,
    busy: Boolean,
    onAction: (DupesAction) -> Unit,
    sheet: SheetState?,
) {
    val colors = RovstiTheme.colors
    Column(Modifier.weight(1f).fillMaxWidth().testTag(DupesTags.Sheet)) {
        val scroll = if (sheet != null) Modifier.nestedScroll(sheetScrollConnection(sheet)) else Modifier
        LazyColumn(scroll.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            item(key = "head", contentType = "head") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = DupesMetrics.HeaderTop),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(
                        review.name,
                        size = DupesMetrics.Avatar,
                        photoModel = review.photoUri,
                        colorKey = review.colorKey,
                    )
                    RovstiText(
                        review.name,
                        Modifier.padding(top = Spacing.m),
                        style = RovstiTheme.type.title2,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    review.subtitle?.let {
                        RovstiText(
                            it,
                            style = RovstiTheme.type.subheadline,
                            color = colors.labelSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Where the fields come from: ONE line of plain text, not a pill per source.
                    RovstiText(
                        review.sources,
                        Modifier.padding(top = Spacing.xs).testTag(DupesTags.Sources),
                        style = RovstiTheme.type.footnote,
                        color = colors.labelSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            items(review.sections, key = { it.title.orEmpty() }, contentType = { "section" }) { section ->
                InsetGroupedSection(header = section.title) {
                    for (line in section.lines) {
                        row {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.gutter, vertical = DupesMetrics.LineVertical),
                                verticalArrangement = Arrangement.spacedBy(DupesMetrics.LineGap),
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
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
                .padding(top = DupesMetrics.ButtonBarTop, bottom = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            CapsuleButton(
                if (busy) "Merging…" else review.mergeLabel,
                onClick = { onAction(DupesAction.MergeOpen) },
                modifier = Modifier.fillMaxWidth().testTag(DupesTags.Merge),
                style = CapsuleStyle.Filled,
                enabled = !busy,
                fillWidth = true,
            )
            // Plain accent text, as in an iOS sheet (a glass capsule at the very bottom of a sheet smears).
            RovstiText(
                "Cancel",
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DupesMetrics.BarButtonHeight)
                    .clickable(enabled = !busy, role = Role.Button) { onAction(DupesAction.CloseReview) }
                    .padding(vertical = Spacing.m)
                    .testTag(DupesTags.Cancel),
                style = RovstiTheme.type.body,
                color = if (busy) colors.labelTertiary else colors.accent,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Runs the model's effects: a question becomes an alert, a notice a toast, a merge ONE undo pill. */
@Composable
fun DupesEffects(
    effects: Flow<DupesEffect>,
    overlays: OverlayController,
    onAction: (DupesAction) -> Unit,
) {
    val latest = rememberUpdatedState(onAction)
    LaunchedEffect(effects, overlays) {
        effects.collect { effect ->
            when (effect) {
                is DupesEffect.Ask -> {
                    overlays.present(
                        AlertEntry(
                            heading = effect.title,
                            message = effect.body,
                            actions =
                                persistentListOf(
                                    AlertAction("Cancel", AlertRole.Cancel),
                                    AlertAction(effect.confirmLabel, AlertRole.Default) { latest.value(effect.then) },
                                ),
                        ),
                    )
                }

                is DupesEffect.Toast -> {
                    overlays.showToast(effect.text)
                }

                is DupesEffect.Undo -> {
                    overlays.showUndo(effect.message, onUndo = { latest.value(DupesAction.Undo(effect.token)) })
                }
            }
        }
    }
}
