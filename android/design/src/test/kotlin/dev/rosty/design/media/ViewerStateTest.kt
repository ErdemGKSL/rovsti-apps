package dev.rosty.design.media

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer's state without a screen (E6-WA R3): the page, a zoom that resets when the page changes, the chrome, and a
 * strip that grows in front while it is open (a chat's gallery paged from the store) without moving the photo on show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerStateTest {
    private val viewport = Size(1080f, 2340f)

    /** A frame every 16 ms, as fast as the test runs. */
    private class StepClock : MonotonicFrameClock {
        private var nanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            yield()
            nanos += 16_000_000L
            return onFrame(nanos)
        }
    }

    private class Strip(
        count: Int,
    ) {
        var count by mutableIntStateOf(count)
        var prepended by mutableIntStateOf(0)

        fun prepend(n: Int) =
            Snapshot.withMutableSnapshot {
                count += n
                prepended += n
            }
    }

    private fun TestScope.state(
        strip: Strip,
        initial: Int,
        pages: MutableList<Int> = mutableListOf(),
    ) = ViewerState(
        countOf = { strip.count },
        initial = initial,
        scope = CoroutineScope(coroutineContext + StepClock()),
        gapPx = 60f,
        viewport = viewport,
        onClose = {},
        onPage = { pages += it },
        shiftOf = { strip.prepended },
    )

    @Test fun aPageChangeResetsTheZoomAndTellsTheNewPage() =
        runTest {
            val pages = mutableListOf<Int>()
            val viewer = state(Strip(5), initial = 2, pages)
            viewer.pinch(2f, Offset(540f, 1170f), Offset.Zero)
            assertTrue(viewer.zoom.isZoomed)

            assertTrue(viewer.goTo(3))
            advanceUntilIdle()
            assertEquals(3, viewer.page)
            assertEquals(ZoomState.Fit, viewer.zoom)
            assertEquals(listOf(3), pages)
            // Past the last page nothing moves.
            viewer.goTo(4)
            advanceUntilIdle()
            assertFalse(viewer.goTo(9))
        }

    @Test fun aTapTogglesTheChrome() =
        runTest {
            val viewer = state(Strip(1), initial = 0)
            assertTrue(viewer.chromeOn)
            viewer.toggleChrome()
            assertFalse(viewer.chromeOn)
            viewer.toggleChrome()
            assertTrue(viewer.chromeOn)
        }

    @Test fun pagesPutInFrontKeepThePhotoOnShowAndItsSize() =
        runTest {
            val strip = Strip(1)
            val viewer = state(strip, initial = 0)
            viewer.setNatural(0, Size(4000f, 3000f))
            val fitted = viewer.fitted(0)

            strip.prepend(4)
            // The same photo, now the fifth page; its size went with it.
            assertEquals(4, viewer.page)
            assertEquals(5, viewer.count)
            assertEquals(fitted, viewer.fitted(4))
            assertTrue(viewer.hasNatural(4))
            assertFalse(viewer.hasNatural(0))

            // A step back while more pages come in front lands on the photo before it, not on an index.
            assertTrue(viewer.goTo(3))
            strip.prepend(2)
            advanceUntilIdle()
            assertEquals(5, viewer.page)
        }

    @Test fun aSizeHintShapesAPageBeforeItsPictureLoads() =
        runTest {
            val viewer =
                ViewerState(
                    countOf = { 2 },
                    initial = 0,
                    scope = CoroutineScope(coroutineContext + StepClock()),
                    gapPx = 60f,
                    viewport = viewport,
                    onClose = {},
                    onPage = {},
                    sizeHint = { if (it == 1) Size(1000f, 4000f) else null },
                )
            assertEquals(viewport, viewer.fitted(0))
            assertEquals(ZoomMath.fit(Size(1000f, 4000f), viewport), viewer.fitted(1))
        }
}
