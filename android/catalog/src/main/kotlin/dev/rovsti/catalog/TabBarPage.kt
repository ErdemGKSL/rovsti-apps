package dev.rovsti.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import dev.rovsti.design.chrome.BackdropScope
import dev.rovsti.design.chrome.BottomScrim
import dev.rovsti.design.chrome.ChromeMetrics
import dev.rovsti.design.chrome.LocalChromeMetrics
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.RovstiTabBar
import dev.rovsti.design.chrome.TabItem
import dev.rovsti.design.chrome.rememberTabBarMinimizer
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

internal val CatalogTabs =
    persistentListOf(
        TabItem("keypad", "Keypad", RovstiIcons.Phone),
        TabItem("messages", "Messages", RovstiIcons.Message),
        TabItem("contacts", "Contacts", RovstiIcons.People),
        TabItem("stops", "Stops", RovstiIcons.Map),
    )

internal const val CatalogRowCount = 50

/** Names used for rows: "Contact 00" to "Contact 49". */
private fun rowName(i: Int) = "Contact ${i.toString().padStart(2, '0')}"

/**
 * The tab bar over a long list. The list is the glass source and scrolls under the bar with `LocalContentInsets` as its
 * `contentPadding`, so the last row (`row-49`) ends above the bar. Scrolling down minimises the bar, scrolling up
 * brings it back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TabBarPage(
    listState: LazyListState = rememberLazyListState(),
    imeVisible: @Composable () -> Boolean = { WindowInsets.isImeVisible },
) {
    val colors = RovstiTheme.colors
    val chrome = remember { ChromeMetrics() }
    val minimizer = rememberTabBarMinimizer()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    BackdropScope(
        modifier = Modifier.fillMaxSize().testTag("tabbar-page"),
        baseBg = colors.page,
        content = {
            CompositionLocalProvider(LocalContentInsets provides chrome.contentInsets()) {
                Box(Modifier.fillMaxSize().nestedScroll(minimizer)) {
                    LazyColumn(
                        state = listState,
                        contentPadding = LocalContentInsets.current,
                        modifier = Modifier.fillMaxSize().testTag("tabbar-list"),
                    ) {
                        item(key = "title") {
                            PageHeader(
                                "Tab bar",
                                "Scroll to minimise, drag the puck, tap to select. ${CatalogTabs[selected].label} is selected.",
                            )
                        }
                        itemsIndexed(List(CatalogRowCount) { it }, key = { _, i -> i }) { _, i ->
                            ListRow(
                                title = rowName(i),
                                secondary = "Row $i of $CatalogRowCount",
                                leading = { Avatar(rowName(i)) },
                                modifier = Modifier.testTag("row-$i"),
                            )
                        }
                    }
                }
            }
        },
        chrome = {
            CompositionLocalProvider(LocalChromeMetrics provides chrome) {
                BottomScrim(chrome, Modifier.align(Alignment.BottomCenter))
                RovstiTabBar(
                    tabs = CatalogTabs,
                    selected = selected,
                    onSelect = { selected = it },
                    onReselect = {},
                    modifier = Modifier.align(Alignment.BottomCenter).testTag("tab-bar"),
                    minimizer = minimizer,
                    imeVisible = imeVisible,
                )
            }
        },
    )
}

/**
 * The Stops-style bar: a full-window striped "map" with the bar folded into the circle of the selected tab. Tapping the
 * circle expands it; the button at the top folds it again.
 */
@Composable
internal fun FoldedTabBarPage() {
    val chrome = remember { ChromeMetrics() }
    var selected by rememberSaveable { mutableIntStateOf(3) }
    var folded by remember { mutableStateOf(true) }
    BackdropScope(
        modifier = Modifier.fillMaxSize().testTag("folded-page"),
        content = { StripedBackdrop() },
        chrome = {
            CapsuleButton(
                text = if (folded) "Expand" else "Fold",
                onClick = { folded = !folded },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Spacing.gutter),
            )
            RovstiText(
                "Stops",
                style = RovstiTheme.type.title2,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Spacing.gutter),
            )
            RovstiTabBar(
                tabs = CatalogTabs,
                selected = selected,
                onSelect = { selected = it },
                onReselect = {},
                modifier = Modifier.align(Alignment.BottomCenter).testTag("tab-bar"),
                folded = folded,
                onFoldedClick = { folded = false },
                chrome = chrome,
                imeVisible = { false },
            )
        },
    )
}
