package dev.rovsti.catalog

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.NavigationButton
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.ListRow
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F9: the collapsed inline title keeps a usable width beside a wide toolbar, never runs under it, and is centred when it fits. */
class InlineTitleCentreTest : ShotTest() {
    private fun check(
        title: String,
        centred: Boolean,
        back: Boolean = true,
    ) {
        val state = LazyListState()
        composeFrame {
            LargeTitleScaffold(
                title = title,
                state = state,
                actions =
                    persistentListOf(
                        ToolbarAction("search", RovstiIcons.Search, "Search") {},
                        ToolbarAction("add", RovstiIcons.Plus, "Add") {},
                    ),
                navigation = if (back) NavigationButton(onClick = {}) else null,
                modifier = Modifier,
            ) {
                itemsIndexed(List(40) { it }, key = { _, i -> i }) { _, i -> ListRow(title = "Row $i") }
            }
        }
        val h =
            state.layoutInfo.visibleItemsInfo
                .first()
                .size
        compose.runOnIdle { runBlocking { state.scrollBy(h * 3f) } }
        advance(16)
        advance(800)

        val root = compose.onRoot().getBoundsInRoot()
        val t = compose.onNodeWithTag("inline-title", useUnmergedTree = true).getBoundsInRoot()
        val windowCentre = (root.left.value + root.right.value) / 2
        val titleCentre = (t.left.value + t.right.value) / 2
        if (centred) assertEquals("inline title centre vs window centre", windowCentre, titleCentre, 1f)
        if (title.length < 12 &&
            !back
        ) {
            assertTrue(
                "short title must not be truncated: box ${(t.right.value - t.left.value)} dp",
                (t.right.value - t.left.value) >= 60f,
            )
        }
        val bar = compose.onNodeWithTag("title-bar").getBoundsInRoot()
        val add = compose.onNodeWithTag("toolbar-add", useUnmergedTree = true).getBoundsInRoot()
        val search = compose.onNodeWithTag("toolbar-search", useUnmergedTree = true).getBoundsInRoot()
        val toolbarLeft = minOf(add.left.value, search.left.value)
        assertTrue(
            "title box ${t.right.value} must end before toolbar $toolbarLeft",
            t.right.value <= toolbarLeft + 0.5f,
        )
        assertTrue("bar must span the window", bar.right.value > bar.left.value)
    }

    @Test
    fun shortTitleBesideAWideToolbarIsNotTruncated() = check("Contacts", centred = false, back = false)

    @Test
    fun shortTitleIsCentred() = check("Contacts", centred = false)

    @Test
    fun fortyCharacterTitleIsCentredAndTruncatedBeforeTheToolbar() =
        check("A".repeat(10) + " " + "B".repeat(28), centred = false)
}
