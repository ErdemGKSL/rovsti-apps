package dev.rosty.messages.conversation

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The Compose facts the thread scroll model stands on (plan `thread-scroll.md`, step 1), kept as regression tests for
 * the next foundation update: offsets of a reversed list, a negative offset in `requestScrollToItem`, and a request
 * made in a `SideEffect` landing in the same frame.
 */
@Config(sdk = [35])
class ThreadScrollProbeTest : ShotTest() {
    private lateinit var list: LazyListState
    private val rows = mutableStateOf((0 until COUNT).map { "r$it" })
    private val follow = mutableStateOf(false)

    /** (rows, first visible key) of every layout pass of the list. */
    private val placed = ArrayList<Pair<Int, Any?>>()

    private fun show() {
        composeFrame {
            list = rememberLazyListState()
            val keys = rows.value
            if (follow.value) SideEffect { list.requestScrollToItem(0) }
            LazyColumn(
                state = list,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 40.dp, bottom = 8.dp),
            ) {
                items(keys, key = { it }) {
                    BasicText(
                        it,
                        Modifier.fillMaxWidth().height(ROW.dp).layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                // Placed in the pass whose measure produced this layout info.
                                placed += list.layoutInfo.totalItemsCount to firstKey()
                                placeable.place(0, 0)
                            }
                        },
                    )
                }
            }
        }
        advance(100)
    }

    private fun firstKey(): Any? =
        list.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == list.firstVisibleItemIndex }
            ?.key

    @Test fun atRestTheSpotIsZeroAndAPositiveScrollMovesToOlderRows() {
        show()
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)
        assertEquals("r0", firstKey())
        val px = with(compose.density) { 30.dp.toPx() }
        compose.runOnUiThread { runBlocking { list.scrollBy(px) } }
        advance(100)
        assertEquals(0, list.firstVisibleItemIndex)
        assertTrue("the offset grows: ${list.firstVisibleItemScrollOffset}", list.firstVisibleItemScrollOffset > 0)
        compose.runOnUiThread { runBlocking { list.scrollBy(px * 4) } }
        advance(100)
        assertTrue("the index rises: ${list.firstVisibleItemIndex}", list.firstVisibleItemIndex >= 1)
    }

    @Test fun aRowAddedAtTheNewestEndIsLaidOutBelowTheViewport() {
        // The bug of the plan, section 1.2: a keyed list keeps its first visible item, also at (0, 0).
        show()
        compose.runOnUiThread { rows.value = listOf("new") + rows.value }
        advance(100)
        assertEquals("r0", firstKey())
        assertEquals(1, list.firstVisibleItemIndex)
        assertEquals(listOf<Any?>("r0"), placed.filter { it.first == COUNT + 1 }.map { it.second }.distinct())
    }

    @Test fun aSideEffectRequestLandsInTheSameFrame() {
        show()
        compose.runOnUiThread {
            follow.value = true
            rows.value = listOf("new") + rows.value
        }
        advance(100)
        // Every pass that laid the new row out had it at the bottom: no frame shows the old one first.
        val withNew = placed.filter { it.first == COUNT + 1 }
        assertTrue("a frame was placed with the new row", withNew.isNotEmpty())
        assertEquals(listOf("new"), withNew.map { it.second }.distinct())
        assertEquals(0, list.firstVisibleItemScrollOffset)
    }

    @Test fun aNegativeOffsetMovesTheRowAwayFromTheStartEdge() {
        show()
        val away = with(compose.density) { 200.dp.roundToPx() }
        compose.runOnUiThread { list.requestScrollToItem(TARGET, -away) }
        advance(100)
        val info = list.layoutInfo
        val row = info.visibleItemsInfo.first { it.key == "r$TARGET" }
        // Offsets of a reversed list are measured from the bottom (start) edge, past the content padding.
        assertEquals(away, row.offset)
        assertTrue("an older row is first visible: ${list.firstVisibleItemIndex}", list.firstVisibleItemIndex < TARGET)
        // Near the newest end the list clamps: it cannot scroll past row 0.
        compose.runOnUiThread { list.requestScrollToItem(1, -info.viewportSize.height) }
        advance(100)
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)
    }

    private companion object {
        const val COUNT = 60
        const val ROW = 56
        const val TARGET = 20
    }
}
