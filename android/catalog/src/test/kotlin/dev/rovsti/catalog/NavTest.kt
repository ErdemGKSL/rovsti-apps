package dev.rovsti.catalog

import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import dev.rovsti.design.transition.Tab
import dev.rovsti.design.transition.pageTag
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation scaffold under a manual clock: push, pop, predictive back, tab switch, the overlay-before-stack back
 * order, reselect, and the shared avatar flight. Pages are found by `pageTag(tab, depth)`.
 */
class NavTest : ShotTest() {
    private val demo = Tab("demo")
    private val components = Tab("components")

    internal fun page(
        tab: Tab,
        depth: Int,
    ): SemanticsNodeInteraction = compose.onNodeWithTag(pageTag(tab, depth))

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    private val width get() =
        compose.activity.resources.displayMetrics.widthPixels
            .toFloat()

    internal fun openDemoAndPush(row: Int = 3) {
        composeFrame { CatalogApp() }
        compose.onNodeWithTag("tab-demo").performSemanticsAction(SemanticsActions.OnClick)
        advance(800)
        compose.onNodeWithTag("tab-demo").assertIsSelected()
        compose.onNodeWithTag("contact-row-$row").performClick()
    }

    internal fun backEvent(progress: Float) = BackEventCompat(0.05f, 0.5f, progress, BackEventCompat.EDGE_LEFT)

    @Test
    fun pushSlidesTheNewPageInOverADimmedParallaxedOne() {
        openDemoAndPush()
        advance(175)
        val incoming = bounds(page(demo, 1))
        val outgoing = bounds(page(demo, 0))
        assertTrue("incoming page is mid-slide: ${incoming.left}", incoming.left > 0f && incoming.left < width)
        assertTrue("outgoing page moved left: ${outgoing.left}", outgoing.left < 0f)
        assertTrue("outgoing moved at most 0.3 W: ${outgoing.left}", outgoing.left >= -0.3f * width - 1f)
        // The outgoing page covers the whole screen minus its offset; the incoming one starts where it ends: no gap.
        assertTrue("no blank band", incoming.left <= outgoing.right + 1f)
        advance(800)
        assertEquals(0f, bounds(page(demo, 1)).left, 1f)
        page(demo, 0).assertDoesNotExist()
    }

    @Test
    fun outgoingOffsetIsAboutThirtyPercentTimesProgress() {
        openDemoAndPush()
        advance(175)
        val incomingLeft = bounds(page(demo, 1)).left
        val progress = 1f - incomingLeft / width
        val outgoingLeft = bounds(page(demo, 0)).left
        assertEquals("parallax = -0.3 W x progress", -0.3f * width * progress, outgoingLeft, 0.02f * width)
    }

    @Test
    fun backPopsToTheList() {
        openDemoAndPush()
        advance(800)
        page(demo, 1).assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(800)
        page(demo, 1).assertDoesNotExist()
        page(demo, 0).assertIsDisplayed()
    }

    @Test
    fun predictiveBackHoldsThePopAtHalfAndCancelRestores() {
        openDemoAndPush()
        advance(800)
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        advance(40)
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(backEvent(0.5f)) }
        advance(100)
        val leaving = bounds(page(demo, 1))
        val below = bounds(page(demo, 0))
        assertTrue("detail is half way out: ${leaving.left}", leaving.left > 0.05f * width && leaving.left < width)
        assertTrue(
            "the page below eases back from its parallax offset: ${below.left}",
            below.left <= 0f && below.left > -0.3f * width,
        )
        // Nothing is popped yet: the pop happens on commit.
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        advance(800)
        assertEquals(0f, bounds(page(demo, 1)).left, 1f)
        page(demo, 1).assertIsDisplayed()
    }

    @Test
    fun committedPredictiveBackPopsTheStack() {
        openDemoAndPush()
        advance(800)
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        advance(40)
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(backEvent(0.5f)) }
        advance(40)
        compose.runOnUiThread { dispatcher.onBackPressed() }
        advance(1000)
        page(demo, 1).assertDoesNotExist()
        page(demo, 0).assertIsDisplayed()
    }

    @Test
    fun tabSwitchCrossFadesWithoutAnyHorizontalOffset() {
        composeFrame { CatalogApp() }
        page(components, 0).assertIsDisplayed()
        compose.onNodeWithTag("tab-demo").performSemanticsAction(SemanticsActions.OnClick)
        advance(100)
        assertEquals("outgoing tab does not slide", 0f, bounds(page(components, 0)).left, 1f)
        assertEquals("incoming tab does not slide", 0f, bounds(page(demo, 0)).left, 1f)
        advance(800)
        page(components, 0).assertDoesNotExist()
    }

    @Test
    fun tabsKeepTheirOwnStacks() {
        openDemoAndPush()
        advance(800)
        compose.onNodeWithTag("tab-components").performSemanticsAction(SemanticsActions.OnClick)
        advance(800)
        page(components, 0).assertIsDisplayed()
        compose.onNodeWithTag("tab-demo").performSemanticsAction(SemanticsActions.OnClick)
        advance(800)
        page(demo, 1).assertIsDisplayed()
    }

    @Test
    fun overlayClosesBeforeTheStackPops() {
        composeFrame { CatalogApp(initialPage = CatalogPage.Overlays) }
        page(components, 1).assertExists()
        compose.onNodeWithTag("present-first").performClick()
        advance(800)
        compose.onNodeWithTag("overlay-card-1").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(40)
        advance(1500)
        compose.onNodeWithTag("overlay-card-1").assertDoesNotExist()
        page(components, 1).assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(1000)
        page(components, 1).assertDoesNotExist()
        page(components, 0).assertIsDisplayed()
    }

    @Test
    fun reselectingTheTabScrollsTheListToItsTop() {
        composeFrame { CatalogApp() }
        compose.onNodeWithTag("tab-demo").performSemanticsAction(SemanticsActions.OnClick)
        advance(800)
        compose.onNodeWithTag("scaffold-list").performScrollToIndex(20)
        advance(300)
        compose.onNodeWithTag("contact-row-0").assertDoesNotExist()
        compose.onNodeWithTag("tab-demo").performSemanticsAction(SemanticsActions.OnClick)
        advance(1500)
        compose.onNodeWithTag("contact-row-0").assertIsDisplayed()
    }

    @Test
    fun reselectingADeepTabPopsToItsRoot() {
        openDemoAndPush()
        advance(800)
        compose.onNodeWithTag("tab-demo").performSemanticsAction(SemanticsActions.OnClick)
        advance(1000)
        page(demo, 1).assertDoesNotExist()
        page(demo, 0).assertIsDisplayed()
    }

    @Test
    fun sharedAvatarFliesBetweenTheRowAndTheHeader() {
        composeFrame { CatalogApp() }
        compose.onNodeWithTag("tab-demo").performSemanticsAction(SemanticsActions.OnClick)
        advance(800)
        val row = bounds(compose.onNodeWithTag(listAvatarTag(3), useUnmergedTree = true))
        compose.onNodeWithTag("contact-row-3").performClick()
        advance(250)
        val flying = bounds(compose.onNodeWithTag(detailAvatarTag(3), useUnmergedTree = true))
        advance(800)
        val header = bounds(compose.onNodeWithTag(detailAvatarTag(3), useUnmergedTree = true))
        assertTrue(
            "avatar grows: ${row.width} < ${flying.width} < ${header.width}",
            flying.width > row.width && flying.width < header.width,
        )
        assertTrue(
            "avatar sits between the row and the header vertically",
            flying.top.coerceIn(minOf(row.top, header.top) - 1f, maxOf(row.top, header.top) + 1f) == flying.top,
        )
    }
}
