package dev.rovsti.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.up
import dev.rovsti.design.media.PhotoViewerTags
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.20: open, zoom, pan, dismiss, page, chrome and Back of the photo viewer, on the manual clock. */
@OptIn(ExperimentalTestApi::class)
class PhotoViewerTest : ShotTest() {
    private fun open(cell: Int = 3) {
        openPhotosPage()
        compose.onNodeWithTag("photo-cell-$cell").performClick()
        advance(800)
    }

    /** The exit flight (500 ms) plus the frames the host needs to drop the finished entry. */
    private fun closeFlight() = repeat(5) { advance(500) }

    private fun root() = compose.onNodeWithTag(PhotoViewerTags.Root)

    @Test
    fun opensFromTheGridWithItsPaneTitleAndPosition() {
        open(3)
        assertTrue(viewerOpen())
        assertEquals("Photo 3 of 7", viewerNode().descriptionOrNull())
        assertEquals(2, viewerPage())
        assertEquals(1f, viewerDim(), 0.01f)
        assertEquals(1f, viewerZoom(), 0f)
        val pane =
            compose
                .onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher
                        .expectValue(SemanticsProperties.PaneTitle, "Photo"),
                ).fetchSemanticsNodes()
        assertEquals(1, pane.size)
    }

    @Test
    fun doubleTapZoomsToTwoAndAHalfAndBack() {
        open()
        root().performTouchInput { doubleClick(Offset(centerX + 100f, centerY - 60f)) }
        advance(800)
        assertEquals(2.5f, viewerZoom(), 0.02f)
        root().performTouchInput { doubleClick(center) }
        advance(800)
        assertEquals(1f, viewerZoom(), 0.02f)
    }

    @Test
    fun verticalSwipeWhileZoomedPansInsteadOfDismissing() {
        open()
        root().performTouchInput { doubleClick(center) }
        advance(800)
        root().performTouchInput { swipe(Offset(centerX, centerY), Offset(centerX, centerY + height * 0.4f), 300) }
        advance(800)
        assertTrue("still open", viewerOpen())
        assertEquals(1f, viewerDim(), 0.01f)
        assertEquals(2.5f, viewerZoom(), 0.05f)
        assertEquals(2, viewerPage())
    }

    @Test
    fun dragDownDimsTheBackgroundAndAThirdOfTheHeightDismisses() {
        open()
        root().performTouchInput {
            down(center)
            moveBy(Offset(0f, height * 0.04f), 60)
            moveBy(Offset(0f, height * 0.04f), 60)
        }
        advance(50)
        val dim = viewerDim()
        assertTrue("dim falls with the drag: $dim", dim < 0.9f && dim > 0.2f)
        root().performTouchInput { moveBy(Offset(0f, height * 0.2f), 60) }
        advance(50)
        root().performTouchInput {
            advanceEventTime(400)
            up()
        }
        closeFlight()
        assertFalse("closed", viewerOpen())
    }

    @Test
    fun aShortDragReturnsAndStaysOpen() {
        open()
        root().performTouchInput {
            down(center)
            moveBy(Offset(0f, height * 0.05f), 60)
            moveBy(Offset(0f, height * 0.03f), 60)
        }
        advance(50)
        assertTrue(viewerDim() < 1f)
        // The finger rests before it lifts: no fling velocity, so the release is decided by the distance alone.
        root().performTouchInput {
            advanceEventTime(400)
            up()
        }
        advance(1000)
        assertTrue(viewerOpen())
        assertEquals(1f, viewerDim(), 0.01f)
    }

    @Test
    fun horizontalFlingPagesToTheNextPhoto() {
        open(3)
        root().performTouchInput {
            swipe(Offset(width * 0.8f, centerY), Offset(width * 0.2f, centerY), 120)
        }
        advance(1000)
        assertEquals(3, viewerPage())
        assertEquals("Photo 4 of 7", viewerNode().descriptionOrNull())
    }

    @Test
    fun theFirstPageRubberBandsAndStaysPut() {
        open(1)
        root().performTouchInput {
            swipe(Offset(width * 0.2f, centerY), Offset(width * 0.8f, centerY), 120)
        }
        advance(1000)
        assertEquals(0, viewerPage())
    }

    @Test
    fun aTapHidesTheChromeAndAnotherShowsIt() {
        open()
        assertTrue(viewerChrome())
        root().performTouchInput {
            down(center)
            up()
        }
        advance(600)
        assertFalse(viewerChrome())
        root().performTouchInput {
            down(center)
            up()
        }
        advance(600)
        assertTrue(viewerChrome())
    }

    @Test
    fun backClosesTheViewer() {
        open()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        closeFlight()
        assertFalse(viewerOpen())
    }

    @Test
    fun toolbarActionsReceiveThePageOnShow() {
        open(3)
        compose.onNodeWithTag(PhotoViewerTags.action("share")).performClick()
        advance(100)
        compose.onNodeWithTag(PhotoViewerTags.action("remove")).performClick()
        advance(100)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        closeFlight()
        val log =
            compose
                .onNodeWithTag("photos-log")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString { it.text }
        assertEquals("Remove photo 3", log)
    }

    @Test
    fun closeButtonClosesAndTheTitleReadsThePosition() {
        open(5)
        compose.onNodeWithTag("back-button").performClick()
        closeFlight()
        assertFalse(viewerOpen())
    }
}
