package dev.rovsti.catalog

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.chrome.inlineTitleAlpha
import dev.rovsti.design.chrome.largeTitleAlpha
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * E1.8 acceptance on the large-title scaffold. The inline title's alpha is the smoothed collapse fraction by
 * construction (`graphicsLayer { alpha = collapse }`), so the test reads that fraction through `onCollapseChanged`.
 */
class ScaffoldTest : ShotTest() {
    private val density get() = compose.activity.resources.displayMetrics.density

    private fun scrollBy(
        state: LazyListState,
        px: Float,
    ) {
        compose.runOnIdle { runBlocking { state.scrollBy(px) } }
        advance(600)
    }

    private fun titleItemHeight(state: LazyListState) =
        state.layoutInfo.visibleItemsInfo
            .first()
            .size

    @Test
    fun inlineAlphaIsBetweenZeroAndOneAtHalfScroll() {
        val state = LazyListState()
        var latest = -1f
        composeFrame { LargeTitlePage(listState = state, onCollapseChanged = { latest = it }) }
        advance(300)
        assertEquals(0f, latest, 1e-3f)

        // 0.8 is past the 0.65 hand-off, so the inline title is fading in while the large one is gone.
        scrollBy(state, titleItemHeight(state) * 0.8f)
        assertEquals(0.8f, latest, 0.03f)
        val inline = inlineTitleAlpha(latest)
        assertTrue("inline alpha $inline should be strictly between 0 and 1", inline > 0f && inline < 1f)
        assertEquals("never two titles", 0f, inline * largeTitleAlpha(latest), 0f)

        scrollBy(state, titleItemHeight(state) * 3f)
        assertEquals(1f, latest, 1e-2f)
    }

    private fun threeActions(onClick: (String) -> Unit): ImmutableList<ToolbarAction> =
        persistentListOf(
            ToolbarAction("search", RovstiIcons.Search, "Search") { onClick("search") },
            ToolbarAction("add", RovstiIcons.Plus, "Add") { onClick("add") },
            ToolbarAction("trash", RovstiIcons.Trash, "Delete") { onClick("trash") },
        )

    @Test
    fun aThirdActionMovesIntoTheOverflow() {
        var hidden: List<String>? = null
        var anchor = Rect.Zero
        composeFrame {
            val glass =
                dev.rovsti.design.glass
                    .rememberGlassSource()
            dev.rovsti.design.chrome.LargeTitleScaffold(
                title = "Title",
                state = LazyListState(),
                actions = threeActions {},
                glass = glass,
                onOverflow = { rect, rest ->
                    anchor = rect
                    hidden = rest.map { it.id }
                },
            ) {}
        }
        compose.onNodeWithTag("toolbar-search").assertIsDisplayed()
        compose.onNodeWithTag("toolbar-add").assertIsDisplayed()
        compose.onNodeWithTag("toolbar-trash").assertDoesNotExist()
        compose.onNodeWithTag("toolbar-overflow").performClick()
        assertEquals(listOf("trash"), hidden)
        assertTrue("overflow anchor $anchor must have an area", anchor.width > 0f && anchor.height > 0f)
        assertEquals(compose.onNodeWithTag("toolbar-overflow").bounds.width, anchor.width, 1f)
    }

    private fun firstLineCentre(title: SemanticsNodeInteraction): Float {
        val node = title.fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertNotNull(action)
        action!!.invoke(results)
        val layout = results.first()
        return node.boundsInRoot.top + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
    }

    @Test
    fun capsulesAreFortyFourDpAndLevelWithTheFirstTitleLine() {
        composeFrame { LargeTitlePage() }
        val capsules = compose.onAllNodesWithTag("toolbar-capsule").fetchSemanticsNodes()
        assertEquals("actions capsule and overflow capsule", 2, capsules.size)
        val line = firstLineCentre(compose.onNodeWithTag("large-title"))
        for (capsule in capsules) {
            val b = capsule.boundsInRoot
            assertEquals("capsule height", 44f * density, b.height, 1f)
            assertTrue(
                "capsule centre ${b.center.y} vs first title line $line (dp ${(b.center.y - line) / density})",
                abs(b.center.y - line) <= 4f * density,
            )
        }
    }

    @Test
    fun theBarHeightIsWrittenToChromeMetrics() {
        lateinit var metrics: dev.rovsti.design.chrome.ChromeMetrics
        composeFrame {
            metrics =
                androidx.compose.runtime.remember {
                    dev.rovsti.design.chrome
                        .ChromeMetrics()
                }
            androidx.compose.runtime.CompositionLocalProvider(
                dev.rovsti.design.chrome.LocalChromeMetrics provides metrics,
            ) {
                LargeTitlePage()
            }
        }
        // 28 dp simulated status inset plus the 52 dp bar.
        assertEquals(80f * density, metrics.topBarPx.toFloat(), 1.5f)
    }

    @Test
    fun theTitleIsItemZeroAndTheBackButtonPopsTheSubPage() {
        val state = LazyListState()
        composeFrame { LargeTitlePage(listState = state) }
        assertEquals(
            "title",
            state.layoutInfo.visibleItemsInfo
                .first()
                .key,
        )
        compose.onNodeWithTag("row-3").performClick()
        advance(300)
        compose.onNodeWithTag("large-title-subpage").assertIsDisplayed()
        compose.onNodeWithTag("back-button").performClick()
        advance(300)
        compose.onNodeWithTag("large-title-page").assertIsDisplayed()
    }
}
