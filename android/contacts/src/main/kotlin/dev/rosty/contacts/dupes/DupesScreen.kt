package dev.rosty.contacts.dupes

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.contacts.R
import dev.rosty.contacts.orNoName
import dev.rosty.design.chrome.LargeTitleScaffold
import dev.rosty.design.chrome.LocalContentInsets
import dev.rosty.design.chrome.NavigationButton
import dev.rosty.design.chrome.ToolbarCapsule
import dev.rosty.design.chrome.ToolbarTextButton
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.Avatar
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.ListRowChevron
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.overlay.AlertAction
import dev.rosty.design.overlay.AlertEntry
import dev.rosty.design.overlay.AlertRole
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.SheetState
import dev.rosty.design.overlay.sheetScrollConnection
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.UiText
import dev.rosty.design.text.resolve
import dev.rosty.design.text.text
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.people.ui.ContactAvatar
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
        title = stringResource(R.string.contacts_duplicates),
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
                            stringResource(R.string.contacts_merge_all),
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
                        icon = RostyIcons.People,
                        title = stringResource(R.string.contacts_no_duplicates),
                        message = stringResource(R.string.contacts_no_duplicates_message),
                    )
                }
            }
        } else if (state.phase == DupesPhase.Ready) {
            item(key = "summary", contentType = "summary") {
                RostyText(
                    state.summary.text(),
                    Modifier
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
                        .testTag(DupesTags.Summary),
                    style = RostyTheme.type.footnote,
                    color = RostyTheme.colors.labelSecondary,
                )
            }
            state.rows.forEachIndexed { index, row ->
                item(key = row.key, contentType = "dupe") {
                    Column {
                        if (index > 0) RowSeparator(inset = TextStartWithLeading)
                        ListRow(
                            title = row.name.orNoName(),
                            secondary = row.secondary.text(),
                            leading = {
                                ContactAvatar(
                                    row.name.orNoName(),
                                    row.face,
                                    size = Sizes.avatar,
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
    val context = LocalContext.current
    val holder = rememberUpdatedState(state)
    val actions = rememberUpdatedState(onAction)
    var entry by remember { mutableStateOf<SheetEntry?>(null) }
    val open = state.review != null
    LaunchedEffect(open) {
        if (open && entry == null) {
            entry = overlays.presentMergeReview(context, { holder.value }) { actions.value(it) }
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
    context: Context,
    state: () -> DupesUiState,
    onAction: (DupesAction) -> Unit,
): SheetEntry {
    val entry =
        SheetEntry(
            title = context.getString(R.string.contacts_merge),
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
    val colors = RostyTheme.colors
    Column(Modifier.weight(1f).fillMaxWidth().testTag(DupesTags.Sheet)) {
        val scroll = if (sheet != null) Modifier.nestedScroll(sheetScrollConnection(sheet)) else Modifier
        LazyColumn(scroll.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            item(key = "head", contentType = "head") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = DupesMetrics.HeaderTop),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ContactAvatar(
                        review.name.orNoName(),
                        review.face,
                        size = DupesMetrics.Avatar,
                        colorKey = review.colorKey,
                    )
                    RostyText(
                        review.name.orNoName(),
                        Modifier.padding(top = Spacing.m),
                        style = RostyTheme.type.title2,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    review.subtitle?.let {
                        RostyText(
                            it,
                            style = RostyTheme.type.subheadline,
                            color = colors.labelSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Where the fields come from: ONE line of plain text, not a pill per source.
                    RostyText(
                        review.sources.text(),
                        Modifier.padding(top = Spacing.xs).testTag(DupesTags.Sources),
                        style = RostyTheme.type.footnote,
                        color = colors.labelSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            items(review.sections, key = { it.title.toString() }, contentType = { "section" }) { section ->
                InsetGroupedSection(header = section.title?.text()) {
                    for (line in section.lines) {
                        row {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.gutter, vertical = DupesMetrics.LineVertical),
                                verticalArrangement = Arrangement.spacedBy(DupesMetrics.LineGap),
                            ) {
                                RostyText(
                                    line.label.text(),
                                    style = RostyTheme.type.footnote,
                                    color = colors.labelSecondary,
                                    maxLines = 1,
                                )
                                RostyText(line.value, style = RostyTheme.type.body)
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
                if (busy) stringResource(R.string.contacts_merging) else review.mergeLabel.text(),
                onClick = { onAction(DupesAction.MergeOpen) },
                modifier = Modifier.fillMaxWidth().testTag(DupesTags.Merge),
                style = CapsuleStyle.Filled,
                enabled = !busy,
                fillWidth = true,
            )
            // Plain accent text, as in an iOS sheet (a glass capsule at the very bottom of a sheet smears).
            RostyText(
                stringResource(R.string.contacts_cancel),
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DupesMetrics.BarButtonHeight)
                    .clickable(enabled = !busy, role = Role.Button) { onAction(DupesAction.CloseReview) }
                    .padding(vertical = Spacing.m)
                    .testTag(DupesTags.Cancel),
                style = RostyTheme.type.body,
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
    val context = LocalContext.current
    val strContactsCancel = stringResource(R.string.contacts_cancel)
    val latest = rememberUpdatedState(onAction)
    LaunchedEffect(effects, overlays) {
        effects.collect { effect ->
            when (effect) {
                is DupesEffect.Ask -> {
                    overlays.present(
                        AlertEntry(
                            heading = effect.title.resolve(context),
                            message = effect.body.resolve(context),
                            actions =
                                persistentListOf(
                                    AlertAction(strContactsCancel, AlertRole.Cancel),
                                    AlertAction(
                                        effect.confirmLabel.resolve(context),
                                        AlertRole.Default,
                                    ) { latest.value(effect.then) },
                                ),
                        ),
                    )
                }

                is DupesEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }

                is DupesEffect.Undo -> {
                    overlays.showUndo(
                        effect.message.resolve(context),
                        onUndo = { latest.value(DupesAction.Undo(effect.token)) },
                    )
                }
            }
        }
    }
}
