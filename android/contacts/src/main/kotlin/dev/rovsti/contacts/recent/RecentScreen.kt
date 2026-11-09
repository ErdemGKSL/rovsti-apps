package dev.rovsti.contacts.recent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.rovsti.contacts.favorites.PersonRow
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.controls.SegmentedControl
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Test tags of the Recently Added / Edited page. */
object RecentTags {
    const val Screen = "recent-screen"
    const val Segments = "recent-segments"
    const val Empty = "recent-empty"
    const val Footer = "recent-footer"

    fun row(id: Long) = "recent-row-$id"
}

/** Where each segment's list was scrolled to when the user left it: item index and pixel offset. */
internal class SegmentScroll {
    private val saved = HashMap<String, Pair<Int, Int>>()

    fun save(
        id: String,
        state: LazyListState,
    ) {
        saved[id] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
    }

    suspend fun restore(
        id: String,
        state: LazyListState,
    ) {
        val (index, offset) = saved[id] ?: (0 to 0)
        state.scrollToItem(index, offset)
    }
}

/**
 * The Recently Added / Edited page (E4.14), stateless: a segmented control (the Convx-ported [SegmentedControl]) over
 * the list of the shown segment. The control is the first item of the one list, so the pill springs between segments
 * instead of being rebuilt; each segment keeps its own scroll position while the other is shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentScreen(
    state: RecentUiState,
    onAction: (RecentAction) -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scroll = remember { SegmentScroll() }
    val pageColor = RovstiTheme.colors.page
    LaunchedEffect(state.selected) { scroll.restore(state.selected, listState) }
    val labels = remember(state.tabs) { state.tabs.map { it.label }.toImmutableList() }
    LargeTitleScaffold(
        title = "Recent",
        state = listState,
        actions = persistentListOf(),
        navigation = NavigationButton(onClick = onBack),
        modifier = modifier.testTag(RecentTags.Screen),
    ) {
        if (state.phase == RecentPhase.Ready && state.tabs.isNotEmpty()) {
            // Sticky: the control stays under the bar while the list scrolls, so the other segment is one tap away.
            stickyHeader(key = "segments", contentType = "segments") {
                Box(Modifier.fillMaxWidth().background(pageColor)) {
                    SegmentedControl(
                        items = labels,
                        selected = state.selectedIndex,
                        onSelect = { index ->
                            val tab = state.tabs.getOrNull(index) ?: return@SegmentedControl
                            if (tab.id != state.selected) {
                                scroll.save(state.selected, listState)
                                onAction(RecentAction.SelectTab(tab.id))
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
                                .testTag(RecentTags.Segments),
                    )
                }
            }
            val rows = state.rows
            if (rows.isEmpty()) {
                item(key = "empty-${state.selected}", contentType = "empty") {
                    val bottom = LocalContentInsets.current.calculateBottomPadding()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = RecentMetrics.EmptyHeight)
                            .padding(bottom = bottom)
                            .testTag(RecentTags.Empty),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            icon = RovstiIcons.Clock,
                            title = state.selectedTab?.emptyTitle.orEmpty(),
                            message = state.selectedTab?.emptyBody.orEmpty(),
                        )
                    }
                }
            }
            rows.forEachIndexed { index, person ->
                item(key = "${state.selected}-${person.id}", contentType = "recent") {
                    Column {
                        if (index > 0) RowSeparator(inset = TextStartWithLeading)
                        PersonRow(
                            person,
                            Modifier.testTag(RecentTags.row(person.id)),
                            onClick = { onAction(RecentAction.Open(person.id)) },
                            trailing = null,
                        )
                    }
                }
            }
            if (rows.isNotEmpty()) {
                item(key = "footer", contentType = "footer") {
                    RovstiText(
                        "Showing the last ${state.windowDays} days.",
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.gutter + Spacing.m, vertical = Spacing.m)
                            .testTag(RecentTags.Footer),
                        style = RovstiTheme.type.footnote,
                        color = RovstiTheme.colors.labelSecondary,
                    )
                }
            }
        }
    }
}
