package dev.rosty.stops.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E7.2a and E7.2b AC2: the tile scheduler with a fake source, in virtual time: what is requested, when, what is
 * cancelled, how failures and the palette are handled, and that the bitmap cache stays within its limit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MapTileStoreTest {
    private val source = FakeTileSource.synthetic()

    /** A camera in the middle of the city tile, zoom 14 exactly, on a 1x screen: bitmaps are data tiles. */
    private fun camera(
        width: Int = 500,
        height: Int = 500,
        density: Float = 1f,
        zoom: Double = 14.0,
    ) = MapCamera().apply {
        setViewport(width, height, density)
        jumpTo(MapFixtures.cityLat, MapFixtures.cityLon, zoom)
    }

    private fun TestScope.store(
        from: TileSource = source,
        bitmapBudget: Long = 64L * 1024 * 1024,
    ) = MapTileStore(
        source = from,
        parentScope = backgroundScope,
        bitmapBudgetBytes = bitmapBudget,
        rasterDispatcher = StandardTestDispatcher(testScheduler),
        nowMs = { testScheduler.currentTime },
    )

    @Test fun aSettledCameraGetsItsVisibleTilesNearestFirst() =
        runTest {
            val store = store()
            val camera = camera()
            store.attach(camera)
            runCurrent()
            assertEquals(listOf(FakeTileSource.CITY), source.requests)
            assertTrue(store.hasBitmap(FakeTileSource.CITY))
            assertEquals(14, store.rasterZoom)
            assertEquals(0, store.pending)
            assertEquals(1L, store.rastered)

            // A wider view: the centre tile is already there, its neighbours are asked for, nearest first.
            camera.setViewport(1500, 500, 1f)
            runCurrent()
            assertEquals(3, store.cachedBitmaps)
            assertEquals(
                listOf("14/9520/6130", "14/9519/6130", "14/9521/6130"),
                source.requests.map(TileKey::describe),
            )
        }

    @Test fun oneDataTileIsRequestedOnceForAllItsBitmaps() =
        runTest {
            // A 2x screen: the city tile is 1024 px, four 512 px bitmaps painted from one request.
            val store = store(FakeTileSource.city().also { it.hold() })
            val held = FakeTileSource.city()
            val camera = camera(width = 1000, height = 1000, density = 2f)
            val s = MapTileStore(held, backgroundScope, rasterDispatcher = StandardTestDispatcher(testScheduler))
            s.attach(camera)
            runCurrent()
            assertEquals(listOf(FakeTileSource.CITY), held.requests)
            assertEquals(15, s.rasterZoom)
            assertEquals(4, s.cachedBitmaps)
            assertEquals(4L, s.rastered)
            for (dx in 0..1) for (dy in 0..1) assertTrue(s.hasBitmap(TileKey.of(15, 9520 * 2 + dx, 6130 * 2 + dy)))
            store.close()
        }

    @Test fun tilesThatLeftTheViewportAreCancelled() =
        runTest {
            val store = store()
            val camera = camera(width = 1500, height = 1500)
            source.hold()
            store.attach(camera)
            runCurrent()
            assertEquals(9, source.requests.size)
            assertEquals(9, source.inFlight)
            assertEquals(9, store.pending)

            // Far away before anything arrived: every old request is cancelled, the new view is asked for.
            camera.jumpTo(
                MapMath.fracToLat(14, MapFixtures.CITY_Y + 50.5),
                MapMath.fracToLon(
                    14,
                    MapFixtures.CITY_X + 50.5,
                ),
            )
            runCurrent()
            assertEquals(9, source.cancelled.size)
            assertEquals(source.requests.take(9).toSet(), source.cancelled.toSet())
            assertEquals(18, source.requests.size)
            assertEquals(9, source.inFlight)
            assertEquals(9, store.loading)

            source.release()
            runCurrent()
            assertEquals(9, store.cachedBitmaps)
            assertEquals(9, source.answered.size)
            // Nothing of the first view was painted.
            assertFalse(store.hasBitmap(FakeTileSource.CITY))
        }

    @Test fun aSmallPanKeepsTheRequestsThatAreStillWanted() =
        runTest {
            val store = store()
            val camera = camera(width = 1500, height = 500)
            source.hold()
            store.attach(camera)
            runCurrent()
            assertEquals(3, source.inFlight)
            // One tile to the east: the west tile leaves, two stay, one is new.
            camera.panBy(-512f, 0f)
            runCurrent()
            assertEquals(listOf("14/9519/6130"), source.cancelled.map(TileKey::describe))
            assertEquals(4, source.requests.size)
            assertEquals("14/9522/6130", TileKey.describe(source.requests.last()))
            assertEquals(3, source.inFlight)
        }

    @Test fun whileMovingTilesArePlannedAtMostOncePerDebounceAndAtOnceOnSettle() =
        runTest {
            val store = store()
            val camera = camera()
            store.attach(camera)
            runCurrent()
            assertEquals(1, source.requests.size)

            camera.setGesture(active = true)
            runCurrent()
            // A drag of four tiles in 40 ms: nothing is asked for yet.
            repeat(4) {
                camera.panBy(-512f, 0f)
                advanceTimeBy(10)
                runCurrent()
            }
            assertEquals(1, source.requests.size)
            // The debounce runs out while the finger is still down: one plan, for where the camera is now.
            advanceTimeBy(MapTileStore.DEBOUNCE_MS)
            runCurrent()
            assertEquals(listOf("14/9520/6130", "14/9524/6130"), source.requests.map(TileKey::describe))

            // More movement, then the finger lifts: planned at once, not a debounce later.
            camera.panBy(-512f, 0f)
            runCurrent()
            assertEquals(2, source.requests.size)
            camera.setGesture(active = false)
            runCurrent()
            assertEquals("14/9525/6130", TileKey.describe(source.requests.last()))
            assertEquals(3, source.requests.size)
        }

    @Test fun aPinchPaintsNothingNewUntilTheZoomSettles() =
        runTest {
            val store = store()
            val camera = camera()
            store.attach(camera)
            runCurrent()
            assertEquals(14, store.rasterZoom)

            camera.setGesture(active = true, pinch = true)
            camera.zoomAbout(2.2, 250f, 250f)
            advanceTimeBy(500)
            runCurrent()
            // Still the zoom 14 bitmap, enlarged by the draw node.
            assertEquals(14, store.rasterZoom)
            assertEquals(1, source.requests.size)
            assertEquals(1L, store.rastered)

            camera.setGesture(active = false)
            runCurrent()
            assertEquals(15, store.rasterZoom)
            // Zoom 15 on a 1x screen has no data of its own: the bitmaps are painted from the zoom 14 buffer, which
            // is still in the buffer cache, so the source is not asked again.
            assertEquals(1, source.requests.size)
            assertTrue(store.rastered > 1L)
            assertTrue(store.hasBitmap(TileKey.containing(camera.centreLat, camera.centreLon, 15)))
            // The old bitmap stays as a stand-in until the cache needs the room.
            assertTrue(store.hasBitmap(FakeTileSource.CITY))
        }

    @Test fun aZoomAnimationAlsoWaitsForItsEnd() =
        runTest {
            val store = store()
            val camera = camera()
            store.attach(camera)
            runCurrent()
            camera.animateZoomBy(2.0, 250f, 250f)
            repeat(5) {
                camera.advance(0.016)
                advanceTimeBy(16)
                runCurrent()
            }
            assertEquals(14, store.rasterZoom)
            while (camera.advance(0.016)) Unit
            runCurrent()
            assertEquals(15, store.rasterZoom)
        }

    @Test fun aFailedTileIsNotAskedAgainUntilTheRetryTime() =
        runTest {
            val store = store()
            val camera = camera()
            source.failing = true
            store.attach(camera)
            runCurrent()
            assertEquals(1, source.requests.size)
            assertEquals(0, store.cachedBitmaps)

            // Moving around inside the tile plans again and again, without a new request.
            repeat(5) {
                camera.panBy(1f, 1f)
                runCurrent()
            }
            assertEquals(1, source.requests.size)

            source.failing = false
            advanceTimeBy(MapTileStore.RETRY_AFTER_MS - 1000)
            runCurrent()
            assertEquals(1, source.requests.size)
            // The retry comes by itself, without a camera change.
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(2, source.requests.size)
            assertTrue(store.hasBitmap(FakeTileSource.CITY))
        }

    @Test fun retryFailedAsksAtOnce() =
        runTest {
            val store = store()
            source.failing = true
            store.attach(camera())
            runCurrent()
            source.failing = false
            store.retryFailed()
            runCurrent()
            assertEquals(2, source.requests.size)
            assertEquals(1, store.cachedBitmaps)
        }

    @Test fun aMissingTileIsAFailureAndTheOthersStillArrive() =
        runTest {
            val store = store()
            source.missing += TileKey.of(14, 9521, 6130)
            store.attach(camera(width = 1500, height = 500))
            runCurrent()
            assertEquals(3, source.requests.size)
            assertEquals(2, store.cachedBitmaps)
            assertFalse(store.hasBitmap(TileKey.of(14, 9521, 6130)))
        }

    @Test fun aSourceThatThrowsIsAFailureNotACrash() =
        runTest {
            val throwing = TileSource { _, _, _, _ -> throw IllegalStateException("boom") }
            val store = store(throwing)
            store.attach(camera())
            runCurrent()
            assertEquals(0, store.cachedBitmaps)
            assertEquals(0, store.pending)
        }

    @Test fun darkAsksForTheEclipsePaletteAndRepaints() =
        runTest {
            val city = FakeTileSource.city()
            val store = store(city)
            val camera = camera()
            store.attach(camera)
            runCurrent()
            assertEquals(listOf(false), city.requestedDark)
            val light = store.bitmaps.peek(FakeTileSource.CITY)!!.getPixel(3, 3)

            store.setDark(true)
            assertEquals(0, store.cachedBitmaps)
            runCurrent()
            assertEquals(listOf(false, true), city.requestedDark)
            assertTrue(store.dark)
            val dark = store.bitmaps.peek(FakeTileSource.CITY)!!.getPixel(3, 3)
            assertNotEquals(light, dark)
            // Setting the same palette again is free.
            store.setDark(true)
            runCurrent()
            assertEquals(2, city.requests.size)
        }

    @Test fun theBitmapCacheStaysWithinItsLimitWhilePanning() =
        runTest {
            // Room for four tiles; the view needs one or two.
            val store = store(bitmapBudget = 4 * TileRasterizer.TILE_BYTES)
            val camera = camera()
            store.attach(camera)
            runCurrent()
            repeat(30) {
                camera.panBy(-400f, 0f)
                runCurrent()
                assertTrue(store.cachedBitmapBytes <= store.bitmapLimitBytes)
            }
            assertEquals(4, store.cachedBitmaps)
            // The tile under the camera is always among them.
            assertTrue(store.hasBitmap(TileKey.containing(camera.centreLat, camera.centreLon, 14)))
            assertFalse(store.hasBitmap(FakeTileSource.CITY))
        }

    @Test fun aBudgetBelowOneScreenIsRaisedToTheScreen() =
        runTest {
            val store = store(bitmapBudget = 1)
            val camera = camera(width = 1500, height = 1500)
            store.attach(camera)
            runCurrent()
            // Nine tiles on screen: all of them stay although the budget is one byte, and nothing is asked twice.
            assertEquals(9, store.cachedBitmaps)
            assertEquals(9, source.requests.size)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(9, source.requests.size)
        }

    @Test fun detachCancelsWhatIsInFlightAndKeepsTheBitmaps() =
        runTest {
            val store = store()
            val camera = camera()
            store.attach(camera)
            runCurrent()
            source.hold()
            camera.panBy(-5000f, 0f)
            runCurrent()
            assertTrue(source.inFlight > 0)
            store.detach()
            runCurrent()
            assertEquals(0, source.inFlight)
            assertEquals(0, store.pending)
            assertTrue(store.hasBitmap(FakeTileSource.CITY))
            // A detached store does not follow the camera.
            source.release()
            val before = source.requests.size
            camera.panBy(-5000f, 0f)
            runCurrent()
            assertEquals(before, source.requests.size)
        }

    @Test fun nothingIsPlannedBeforeTheMapHasASize() =
        runTest {
            val store = store()
            val camera = MapCamera()
            store.attach(camera)
            runCurrent()
            assertEquals(0, source.requests.size)
            assertEquals(-1, store.rasterZoom)
            camera.setViewport(300, 300, 1f)
            runCurrent()
            assertTrue(source.requests.isNotEmpty())
        }

    @Test fun aSlowSourceDoesNotBlockLaterTiles() =
        runTest {
            val store = store()
            source.delayMs = 200
            store.attach(camera(width = 1500, height = 500))
            runCurrent()
            assertEquals(3, source.inFlight)
            assertEquals(0, store.cachedBitmaps)
            advanceTimeBy(201)
            runCurrent()
            assertEquals(3, store.cachedBitmaps)
        }
}
