package dev.rovsti.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.glass.rememberGlassSource
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

internal const val LargeTitleRowCount = 40

private fun contactName(i: Int) = "Contact ${i.toString().padStart(2, '0')}"

/**
 * A contacts-like page: the large-title scaffold with 40 rows, two actions and an overflow button. A row opens a pushed
 * sub-page with the back button (the animated push is E1.18; here the page just swaps).
 */
@Composable
internal fun LargeTitlePage(
    listState: LazyListState = rememberLazyListState(),
    actions: ImmutableList<ToolbarAction> = rememberCatalogActions(),
    onCollapseChanged: ((Float) -> Unit)? = null,
    initialPushed: Int = -1,
) {
    val glass = rememberGlassSource()
    var pushed by rememberSaveable { mutableIntStateOf(initialPushed) }
    var overflowAnchor by remember { mutableStateOf(Rect.Zero) }
    if (pushed >= 0) {
        BackHandler { pushed = -1 }
        val subState = rememberLazyListState()
        LargeTitleScaffold(
            title = contactName(pushed),
            state = subState,
            actions = persistentListOf(),
            glass = glass,
            navigation = NavigationButton(onClick = { pushed = -1 }),
            modifier = Modifier.testTag("large-title-subpage"),
        ) {
            itemsIndexed(List(SubRows.size) { it }, key = { _, i -> "detail-$i" }) { _, i ->
                ListRow(title = SubRows[i].first, secondary = SubRows[i].second)
            }
        }
    } else {
        LargeTitleScaffold(
            title = "Contacts",
            state = listState,
            actions = actions,
            glass = glass,
            // TODO(E1.10): open the overflow menu at this anchor.
            onOverflow = { anchor, _ -> overflowAnchor = anchor },
            onCollapseChanged = onCollapseChanged,
            modifier = Modifier.testTag("large-title-page"),
        ) {
            itemsIndexed(List(LargeTitleRowCount) { it }, key = { _, i -> i }) { _, i ->
                ListRow(
                    title = contactName(i),
                    secondary = "Mobile, +49 170 555 01$i",
                    leading = { Avatar(contactName(i)) },
                    trailing = { ListRowChevron() },
                    onClick = { pushed = i },
                    modifier = Modifier.testTag("row-$i"),
                )
            }
        }
    }
}

@Composable
internal fun rememberCatalogActions(): ImmutableList<ToolbarAction> =
    remember {
        persistentListOf(
            ToolbarAction("search", RovstiIcons.Search, "Search") {},
            ToolbarAction("add", RovstiIcons.Plus, "Add contact") {},
        )
    }

private val SubRows =
    listOf(
        "Mobile" to "+49 170 555 0100",
        "Home" to "+49 30 555 0101",
        "Email" to "name@example.com",
        "Notes" to "Met at the station",
    )
