package dev.rosty.stops.map

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * E7.2b: what the draw node shows for cached, stand-in and missing tiles, that neighbours leave no seam, and
 * E7.13 AC1: drawing 16 cached bitmaps allocates nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TileLayerPainterTest {
    private fun TestScope.store() =
        MapTileStore(
            FakeTileSource.empty(),
            backgroundScope,
            bitmapBudgetBytes =
                512L * 1024 * 1024,
        )

    private fun solid(argb: Int): Bitmap = TileRasterizer.newBitmap().apply { eraseColor(argb) }

    /** A bitmap whose four quarters have their own colours: NW, NE, SW, SE. */
    private fun quartered(colours: IntArray): Bitmap {
        val b = TileRasterizer.newBitmap()
        val c = Canvas(b)
        val p = android.graphics.Paint()
        for (i in 0 until 4) {
            p.color = colours[i]
            val l = (i and 1) * 256f
            val t = (i shr 1) * 256f
            c.drawRect(l, t, l + 256f, t + 256f, p)
        }
        return b
    }

    private fun MapTileStore.put(
        key: Long,
        bitmap: Bitmap,
    ) = bitmaps.put(key, bitmap, TileRasterizer.TILE_BYTES)

    /** A camera whose top-left corner is exactly the corner of raster tile [x], [y] at [zoom], on a 1x screen. */
    private fun cameraAt(
        zoom: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        displayZoom: Double = zoom.toDouble(),
    ): MapCamera {
        val camera = MapCamera()
        camera.setViewport(width, height, 1f)
        val size = 512.0 * MapMath.pow2(displayZoom - zoom)
        camera.jumpTo(
            MapMath.fracToLat(zoom, y + height / 2.0 / size),
            MapMath.fracToLon(zoom, x + width / 2.0 / size),
            displayZoom,
        )
        return camera
    }

    private fun draw(
        painter: TileLayerPainter,
        camera: MapCamera,
    ): Bitmap {
        val out = Bitmap.createBitmap(camera.widthPx, camera.heightPx, Bitmap.Config.ARGB_8888)
        painter.setRadarColors(PLATE, GRID)
        painter.draw(Canvas(out), camera)
        return out
    }

    @Test fun cachedTilesAreDrawnWhereTheCameraPutsThem() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            store.put(TileKey.of(14, 9520, 6130), solid(RED))
            store.put(TileKey.of(14, 9521, 6130), solid(BLUE))
            store.put(TileKey.of(14, 9520, 6131), solid(GREEN))
            val out = draw(painter, cameraAt(14, 9520, 6130, 1024, 1024))
            assertEquals(RED, out.getPixel(256, 256))
            assertEquals(BLUE, out.getPixel(768, 256))
            assertEquals(GREEN, out.getPixel(256, 768))
            // The fourth tile is not cached: the radar plate.
            assertEquals(PLATE, out.getPixel(768, 768))
            assertEquals(3, painter.lastExact)
            assertTrue(painter.lastMissing >= 1)
            // Edge to edge: the last red pixel is next to the first blue one.
            assertEquals(RED, out.getPixel(511, 100))
            assertEquals(BLUE, out.getPixel(512, 100))
        }

    @Test fun neighboursLeaveNoSeamAtAFractionalZoomAndOffset() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            for (dx in -1..4) {
                for (dy in -1..4) {
                    store.put(
                        TileKey.of(14, 9520 + dx, 6130 + dy),
                        solid(
                            if ((dx + dy) % 2 ==
                                0
                            ) {
                                RED
                            } else {
                                BLUE
                            },
                        ),
                    )
                }
            }
            for (displayZoom in doubleArrayOf(13.63, 14.0, 14.21, 14.49)) {
                val camera = cameraAt(14, 9520, 6130, 900, 700, displayZoom)
                camera.panBy(-37.3f, -11.7f)
                val out = draw(painter, camera)
                // Every pixel belongs to a tile: no plate shows through between or beside them.
                for (y in 0 until 700) {
                    for (x in 0 until 900) {
                        val c = out.getPixel(x, y)
                        if (c != RED &&
                            c != BLUE
                        ) {
                            throw AssertionError(
                                "plate or blend at $x,$y at zoom $displayZoom: ${Integer.toHexString(c)}",
                            )
                        }
                    }
                }
                assertEquals(0, painter.lastMissing)
            }
        }

    @Test fun aMissingTileShowsItsNearestCachedAncestorEnlarged() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            // The parent of 15/19040..19041/12260..12261 with one colour per quarter.
            store.put(TileKey.of(14, 9520, 6130), quartered(intArrayOf(RED, BLUE, GREEN, BLACK)))
            val out = draw(painter, cameraAt(15, 19040, 12260, 1024, 1024))
            // Each child shows its quarter of the parent, twice as large.
            assertEquals(RED, out.getPixel(256, 256))
            assertEquals(BLUE, out.getPixel(768, 256))
            assertEquals(GREEN, out.getPixel(256, 768))
            assertEquals(BLACK, out.getPixel(768, 768))
            assertEquals(0, painter.lastExact)
            assertEquals(0, painter.lastMissing)
            assertTrue(painter.lastStandIn >= 4)

            // A child that is cached is drawn itself, the others keep the stand-in.
            store.put(TileKey.of(15, 19041, 12260), solid(WHITE))
            val mixed = draw(painter, cameraAt(15, 19040, 12260, 1024, 1024))
            assertEquals(WHITE, mixed.getPixel(768, 256))
            assertEquals(RED, mixed.getPixel(256, 256))
        }

    @Test fun theStandInWalkStopsFourZoomsUpLikeRust() =
        runTest {
            // `stand_in`: itself, then ancestors up to 4 levels up; the 5th is not used.
            for (levels in 1..5) {
                val store = store()
                val painter = TileLayerPainter(store)
                val want = TileKey.of(16, 9520 * 4, 6130 * 4)
                store.put(TileKey.ancestor(want, levels), solid(RED))
                val out = draw(painter, cameraAt(16, 9520 * 4, 6130 * 4, 512, 512))
                val expected = if (levels <= STAND_IN_LEVELS) RED else PLATE
                assertEquals("ancestor $levels zooms up", expected, out.getPixel(256, 256))
                assertEquals(standIn(want) { store.hasBitmap(it) } != TileKey.NONE, expected == RED)
            }
        }

    @Test fun theNearestAncestorWinsOverAFartherOne() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            val want = TileKey.of(16, 9520 * 4, 6130 * 4)
            store.put(TileKey.ancestor(want, 3), solid(BLUE))
            store.put(TileKey.ancestor(want, 1), solid(RED))
            val out = draw(painter, cameraAt(16, 9520 * 4, 6130 * 4, 512, 512))
            assertEquals(RED, out.getPixel(256, 256))
        }

    @Test fun aStandInShowsTheRightPartOfADeepAncestor() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            // Two zooms up, the wanted tile is the cell (3, 1) of a 4 x 4 grid: inside the parent's NE quarter.
            store.put(TileKey.of(14, 9520, 6130), quartered(intArrayOf(RED, BLUE, GREEN, BLACK)))
            val out = draw(painter, cameraAt(16, 9520 * 4 + 3, 6130 * 4 + 1, 512, 512))
            assertEquals(BLUE, out.getPixel(256, 256))
            // And cell (1, 2) is in the SW quarter.
            val sw = draw(painter, cameraAt(16, 9520 * 4 + 1, 6130 * 4 + 2, 512, 512))
            assertEquals(GREEN, sw.getPixel(256, 256))
        }

    @Test fun aMissingTileShowsItsCachedChildrenReduced() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            // After a zoom-out: the tiles one zoom down are cached, three of the four.
            store.put(TileKey.of(15, 19040, 12260), solid(RED))
            store.put(TileKey.of(15, 19041, 12260), solid(BLUE))
            store.put(TileKey.of(15, 19040, 12261), solid(GREEN))
            val out = draw(painter, cameraAt(14, 9520, 6130, 512, 512))
            assertEquals(RED, out.getPixel(128, 128))
            assertEquals(BLUE, out.getPixel(384, 128))
            assertEquals(GREEN, out.getPixel(128, 384))
            assertEquals(PLATE, out.getPixel(384, 384))
            // Grandchildren fill the gap when they are there.
            for (dx in 0..1) for (dy in 0..1) store.put(TileKey.of(16, 19041 * 2 + dx, 12261 * 2 + dy), solid(BLACK))
            val filled = draw(painter, cameraAt(14, 9520, 6130, 512, 512))
            assertEquals(BLACK, filled.getPixel(384, 384))
            assertEquals(BLACK, filled.getPixel(500, 500))
        }

    @Test fun whereNothingIsLoadedTheRadarPlateAndGridShow() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            val out = draw(painter, cameraAt(14, 9520, 6130, 1024, 1024))
            assertEquals(PLATE, out.getPixel(256, 256))
            assertEquals(PLATE, out.getPixel(700, 900))
            // The hairline grid runs along the tile edges.
            assertTrue(out.getPixel(512, 256) != PLATE || out.getPixel(511, 256) != PLATE)
            assertEquals(0, painter.lastExact + painter.lastStandIn)
            assertTrue(painter.lastMissing >= 4)
        }

    @Test fun tilesWrapAcrossTheAntimeridian() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            val camera = MapCamera()
            camera.setViewport(600, 300, 1f)
            // Centred on the antimeridian at the equator: the left half is the last column, the right half the first.
            camera.jumpTo(MapMath.fracToLat(6, 32.5), 180.0, 6.0)
            store.put(TileKey.of(6, 63, 32), solid(RED))
            store.put(TileKey.of(6, 0, 32), solid(BLUE))
            val out = draw(painter, camera)
            assertEquals(RED, out.getPixel(150, 150))
            assertEquals(BLUE, out.getPixel(450, 150))
            assertEquals(RED, out.getPixel(299, 150))
            assertEquals(BLUE, out.getPixel(300, 150))
        }

    @Test fun rowsBeyondTheProjectionAreJustPlate() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            val camera = MapCamera()
            camera.setViewport(400, 4000, 1f)
            camera.setView(85.0, 0.0, 2000.0)
            val zoom = MapTileStore.rasterZoomFor(camera.pixelZoom)
            for (x in 0 until (1 shl zoom)) {
                for (y in 0 until (1 shl zoom)) {
                    store.put(
                        TileKey.of(zoom, x, y),
                        solid(RED),
                    )
                }
            }
            val out = draw(painter, camera)
            val top = camera.screenY(MapMath.MAX_MERCATOR_LAT).toInt()
            assertTrue(top > 10)
            assertEquals(PLATE, out.getPixel(200, top - 5))
            assertEquals(RED, out.getPixel(200, top + 5))
        }

    @Test fun aZeroSizeOrAbsurdViewportDrawsNothingAndDoesNotThrow() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            val empty = MapCamera()
            painter.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)), empty)
            assertEquals(0, painter.lastExact + painter.lastStandIn + painter.lastMissing)
            val huge = MapCamera()
            huge.setViewport(60_000, 60_000, 1f)
            painter.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)), huge)
            assertEquals(0, painter.lastExact)
        }

    @Test fun drawingMarksTilesAsUsedSoTheVisibleOnesSurviveEviction() =
        runTest {
            val store =
                MapTileStore(
                    FakeTileSource.empty(),
                    backgroundScope,
                    bitmapBudgetBytes =
                        3 * TileRasterizer.TILE_BYTES,
                )
            val painter = TileLayerPainter(store)
            val visible = TileKey.of(14, 9520, 6130)
            store.put(visible, solid(RED))
            store.put(TileKey.of(14, 1, 1), solid(BLUE))
            store.put(TileKey.of(14, 2, 2), solid(BLUE))
            draw(painter, cameraAt(14, 9520, 6130, 500, 500))
            // A new tile needs room: the oldest unused one goes, not the one that was just drawn.
            store.put(TileKey.of(14, 3, 3), solid(GREEN))
            assertTrue(store.hasBitmap(visible))
            assertTrue(!store.hasBitmap(TileKey.of(14, 1, 1)))
        }

    /**
     * E7.13 AC1: a frame of 16 cached tiles allocates nothing on the Java heap. Measured with the JVM's per-thread
     * allocation counter over many frames while the camera pans and zooms a little, so the transform maths, the cache
     * lookups and the `drawBitmap` calls are all in the measured path.
     */
    @Test fun drawingSixteenCachedBitmapsAllocatesNothing() =
        runTest {
            val store = store()
            val painter = TileLayerPainter(store)
            for (dx in -2..6) {
                for (dy in -2..6) {
                    store.put(
                        TileKey.of(14, 9520 + dx, 6130 + dy),
                        solid(
                            if ((dx + dy) % 2 ==
                                0
                            ) {
                                RED
                            } else {
                                BLUE
                            },
                        ),
                    )
                }
            }
            // 4 x 4 tiles of 512 px fill the canvas exactly.
            val camera = cameraAt(14, 9520, 6130, 2048, 2048)
            val canvas = Canvas(Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888))
            painter.setRadarColors(PLATE, GRID)
            painter.draw(canvas, camera)
            assertEquals(16, painter.lastExact)

            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val me = Thread.currentThread().id

            fun frames(n: Int) {
                for (i in 0 until n) {
                    // Sub-pixel motion that keeps the same 16 to 25 tiles in view.
                    camera.panBy(if (i % 2 == 0) 0.4f else -0.4f, if (i % 3 == 0) 0.3f else -0.15f)
                    camera.zoomAbout(if (i % 2 == 0) 1.0003 else 1 / 1.0003, 1000f, 1000f)
                    painter.draw(canvas, camera)
                }
            }
            // Warm up: class loading, JIT and the first use of every code path allocate; steady state must not.
            frames(3000)
            var best = Long.MAX_VALUE
            repeat(5) {
                val before = threads.getThreadAllocatedBytes(me)
                frames(FRAMES)
                best = minOf(best, threads.getThreadAllocatedBytes(me) - before)
            }
            assertTrue(painter.lastExact >= 16)
            assertEquals(0, painter.lastMissing)
            println("map draw allocation: $best bytes over $FRAMES frames of ${painter.lastExact} tiles")
            java.io.File("build").mkdirs()
            java.io
                .File(
                    "build/map-draw-allocation.txt",
                ).writeText("$best bytes over $FRAMES frames of ${painter.lastExact} tiles\n")
            assertEquals("bytes allocated over $FRAMES frames", 0L, best)
        }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val PLATE = 0xFFEEEEEE.toInt()
        const val GRID = 0xFF888888.toInt()
        const val FRAMES = 500
    }
}
