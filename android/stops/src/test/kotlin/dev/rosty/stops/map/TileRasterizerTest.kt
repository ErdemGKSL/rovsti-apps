package dev.rosty.stops.map

import android.graphics.Bitmap
import android.graphics.Color
import dev.rosty.core.model.stops.TileBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * E7.2b: the rasteriser against the rules of `docs/kotlin-ui/tile-buffer.md`, pixel by pixel on small made-up tiles,
 * then on the real city tile; and the E7.13 AC1 benchmark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TileRasterizerTest {
    private val rasterizer = TileRasterizer()

    private fun tile(
        vararg classes: TileBuffer.TileClass,
        z: Int = 14,
        x: Int = 100,
        y: Int = 200,
    ) = PreparedTile(TileBuffer(0, z, x, y, 4096, BACKGROUND, classes.toList()))

    private fun polygon(
        rgba: Int,
        vararg rings: FloatArray,
    ) = TileBuffer.TileClass(20, false, rgba, 0f, TileBuffer.Kind.Polygon, rings.toList())

    private fun line(
        rgba: Int,
        width14: Float,
        casing: Boolean,
        vararg parts: FloatArray,
    ) = TileBuffer.TileClass(33, casing, rgba, width14, TileBuffer.Kind.Line, parts.toList())

    /** A square ring, clockwise on screen (positive area, an exterior) or reversed (a hole). */
    private fun square(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        hole: Boolean = false,
    ): FloatArray {
        val ring = floatArrayOf(l, t, r, t, r, b, l, b)
        check(TileBuffer.ringArea2(ring) > 0)
        return if (hole) floatArrayOf(l, t, l, b, r, b, r, t) else ring
    }

    private fun render(
        tile: PreparedTile,
        zoom: Int = tile.buffer.z,
        x: Int = tile.buffer.x shl (zoom - tile.buffer.z),
        y: Int = tile.buffer.y shl (zoom - tile.buffer.z),
        displayZoom: Double = zoom.toDouble(),
        density: Float = 1f,
    ): Bitmap = TileRasterizer.newBitmap().also { rasterizer.render(tile, zoom, x, y, displayZoom, density, it) }

    /** Tile units to the pixel in a full-tile bitmap (4096 units over 512 px). */
    private fun Bitmap.at(
        ux: Int,
        uy: Int,
    ): Int = getPixel(ux / 8, uy / 8)

    // ---- polygons ----

    @Test fun anEmptyTileIsItsBackground() {
        val b = render(tile())
        assertEquals(BACKGROUND_ARGB, b.getPixel(0, 0))
        assertEquals(BACKGROUND_ARGB, b.getPixel(511, 511))
        assertEquals(BACKGROUND_ARGB, b.getPixel(256, 256))
    }

    @Test fun aHoleIsCutOutOfItsPolygon() {
        val hole = square(1600f, 1600f, 2400f, 2400f, hole = true)
        val b = render(tile(polygon(RED, square(800f, 800f, 3200f, 3200f), hole)))
        assertEquals("the ring is filled", RED_ARGB, b.at(1200, 2000))
        assertEquals("the hole shows the background", BACKGROUND_ARGB, b.at(2000, 2000))
        assertEquals("outside is background", BACKGROUND_ARGB, b.at(400, 400))
    }

    @Test fun overlappingPolygonsOfOneClassDoNotCancelEachOtherOut() {
        // Two exteriors that overlap, as tiles do in their 1/16 margin. One even-odd path would leave the overlap
        // empty.
        val b = render(tile(polygon(RED, square(800f, 800f, 2400f, 2400f), square(1600f, 1600f, 3200f, 3200f))))
        assertEquals(RED_ARGB, b.at(1200, 1200))
        assertEquals("the overlap is filled", RED_ARGB, b.at(2000, 2000))
        assertEquals(RED_ARGB, b.at(2800, 2800))
    }

    @Test fun aHoleBelongsToThePolygonBeforeItOnly() {
        // The hole of the first polygon lies where the second polygon is: the second one is still drawn whole.
        val b =
            render(
                tile(
                    polygon(
                        RED,
                        square(400f, 400f, 2000f, 2000f),
                        square(800f, 800f, 1600f, 1600f, hole = true),
                        square(1000f, 1000f, 3600f, 3600f),
                    ),
                ),
            )
        assertEquals("inside the hole, outside the second polygon", BACKGROUND_ARGB, b.at(900, 900))
        assertEquals("inside the hole and the second polygon", RED_ARGB, b.at(1300, 1300))
        assertEquals(RED_ARGB, b.at(3000, 3000))
    }

    @Test fun halfTransparentFillsBlendOncePerPolygon() {
        val half = 0x00FF0080 // green, alpha 128
        val b = render(tile(polygon(half, square(800f, 800f, 2400f, 2400f), square(1600f, 1600f, 3200f, 3200f))))
        val once = b.at(1200, 1200)
        val twice = b.at(2000, 2000)
        // Blended over the pale background: neither the background nor solid green.
        assertNotEquals(BACKGROUND_ARGB, once)
        assertTrue("half green: ${Integer.toHexString(once)}", Color.red(once) in 100..150 && Color.green(once) > 240)
        // Each polygon is filled on its own, so the overlap gets the fill twice (and never zero times).
        assertTrue("the overlap is greener: ${Integer.toHexString(twice)}", Color.red(twice) < Color.red(once) - 30)
    }

    @Test fun recordsPaintInOrder() {
        val below = polygon(RED, square(800f, 800f, 3200f, 3200f))
        val b = render(tile(below, polygon(BLUE, square(1600f, 1600f, 2400f, 2400f))))
        assertEquals(BLUE_ARGB, b.at(2000, 2000))
        assertEquals(RED_ARGB, b.at(1200, 1200))
    }

    @Test fun degenerateRingsAreSkipped() {
        val twoPoints = floatArrayOf(10f, 10f, 500f, 500f)
        val b = render(tile(polygon(RED, twoPoints, floatArrayOf(), square(800f, 800f, 1600f, 1600f))))
        assertEquals(RED_ARGB, b.at(1200, 1200))
        assertEquals(BACKGROUND_ARGB, b.at(256, 256))
    }

    // ---- lines ----

    private fun widthAcross(
        b: Bitmap,
        column: Int,
        argb: Int,
    ): Int = (0 until 512).count { b.getPixel(column, it) == argb }

    @Test fun aCasedLineIsItsCasingWithTheLineOnTop() {
        val road = floatArrayOf(0f, 2048f, 4096f, 2048f)
        // The writer's order: casing first (width14 + 1.5), then the line, same parts.
        val b = render(tile(line(BLUE, 9.5f, true, road), line(RED, 8f, false, road)))
        assertEquals(RED_ARGB, b.getPixel(256, 256))
        assertEquals(8, widthAcross(b, 256, RED_ARGB))
        // 0.75 px of casing on each side: the pixel rows next to the line are mostly blue.
        val above = b.getPixel(256, 251)
        assertTrue(Integer.toHexString(above), Color.blue(above) > 200 && Color.red(above) < 240)
        assertEquals(BACKGROUND_ARGB, b.getPixel(256, 245))
    }

    @Test fun lineWidthFollowsTheDisplayZoomAndTheDensity() {
        val road = floatArrayOf(0f, 2048f, 4096f, 2048f)
        val t = tile(line(RED, 4f, false, road))
        assertEquals(4, widthAcross(render(t, displayZoom = 14.0), 256, RED_ARGB))
        assertEquals(8, widthAcross(render(t, displayZoom = 15.0), 256, RED_ARGB))
        assertEquals(16, widthAcross(render(t, displayZoom = 18.0), 256, RED_ARGB))
        assertEquals(2, widthAcross(render(t, displayZoom = 13.0), 256, RED_ARGB))
        assertEquals(12, widthAcross(render(t, displayZoom = 14.0, density = 3f), 256, RED_ARGB))
    }

    @Test fun strokesHaveRoundCapsAndJoins() {
        // A 40 px wide stroke that ends in the middle of the tile: its end is a half disc, not a flat cut.
        val t = tile(line(RED, 40f, false, floatArrayOf(800f, 2048f, 2048f, 2048f)))
        val b = render(t)
        // Beyond the end point by half the width minus a bit, on the axis: inside the cap.
        assertEquals(RED_ARGB, b.getPixel(256 + 17, 256))
        // The same distance out but in the corner of the square a butt-less square cap would fill: outside the disc.
        assertEquals(BACKGROUND_ARGB, b.getPixel(256 + 17, 256 + 17))
        // A right-angle corner: the outside of the join is rounded.
        val corner = render(tile(line(RED, 40f, false, floatArrayOf(800f, 2048f, 2048f, 2048f, 2048f, 3200f))))
        assertEquals(RED_ARGB, corner.getPixel(256 + 10, 256 - 10))
        assertEquals(BACKGROUND_ARGB, corner.getPixel(256 + 18, 256 - 18))
    }

    // ---- raster tiles under a data tile ----

    @Test fun aBitmapOneZoomDownShowsItsQuarterOfTheDataTile() {
        // One square per quadrant, each its own colour.
        val t =
            tile(
                polygon(RED, square(512f, 512f, 1536f, 1536f)),
                polygon(BLUE, square(2560f, 512f, 3584f, 1536f)),
                polygon(GREEN, square(512f, 2560f, 1536f, 3584f)),
                polygon(BLACK, square(2560f, 2560f, 3584f, 3584f)),
            )
        val x = t.buffer.x * 2
        val y = t.buffer.y * 2
        val expected =
            mapOf((0 to 0) to RED_ARGB, (1 to 0) to BLUE_ARGB, (0 to 1) to GREEN_ARGB, (1 to 1) to BLACK_ARGB)
        for ((cell, colour) in expected) {
            val b = render(t, zoom = 15, x = x + cell.first, y = y + cell.second)
            assertEquals("quadrant $cell", colour, b.getPixel(256, 256))
            // The square is 1024 units = a quarter of the 2048 units the bitmap shows: 256 px, from 128 to 384.
            assertEquals(colour, b.getPixel(130, 130))
            assertEquals(BACKGROUND_ARGB, b.getPixel(120, 120))
            assertEquals(BACKGROUND_ARGB, b.getPixel(390, 390))
        }
    }

    @Test fun neighbouringBitmapsMeetWithoutASeam() {
        // A polygon and a road across the middle of the data tile: the last column of the left bitmap and the first
        // column of the right one show the same thing.
        val t =
            tile(
                polygon(GREEN, square(1000f, 600f, 3000f, 1400f)),
                line(RED, 6f, false, floatArrayOf(0f, 1000f, 4096f, 1040f)),
            )
        val x = t.buffer.x * 2
        val y = t.buffer.y * 2
        val left = render(t, zoom = 15, x = x, y = y)
        val right = render(t, zoom = 15, x = x + 1, y = y)
        var differing = 0
        for (row in 0 until 512) {
            if (left.getPixel(511, row) != right.getPixel(0, row)) differing++
        }
        // Only anti-aliased edge rows of the slightly sloped road may differ between the two columns.
        assertTrue("$differing rows differ", differing <= 4)
        assertEquals(GREEN_ARGB, left.getPixel(511, 200))
        assertEquals(GREEN_ARGB, right.getPixel(0, 200))
        assertEquals(RED_ARGB, left.getPixel(511, 255))
    }

    @Test fun aDeepBitmapShowsASixtyFourthOfTheSide() {
        val t = tile(polygon(RED, square(0f, 0f, 32f, 32f)))
        val levels = TileRasterizer.MAX_LEVELS_BELOW_DATA
        val b = render(t, zoom = 14 + levels, x = t.buffer.x shl levels, y = t.buffer.y shl levels)
        // 64 units across the bitmap: the 32 unit square fills its top-left quarter.
        assertEquals(RED_ARGB, b.getPixel(100, 100))
        assertEquals(BACKGROUND_ARGB, b.getPixel(300, 300))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBitmapOutsideItsDataTileIsRefused() {
        val t = tile()
        render(t, zoom = 15, x = t.buffer.x * 2 + 2, y = t.buffer.y * 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBitmapAboveItsDataTileIsRefused() {
        val t = tile()
        render(t, zoom = 13, x = t.buffer.x / 2, y = t.buffer.y / 2)
    }

    // ---- the real tile ----

    @Test fun theCityTilePaintsInBothPalettes() {
        val light = render(PreparedTile(MapFixtures.cityTile(dark = false)))
        val dark = render(PreparedTile(MapFixtures.cityTile(dark = true)))

        fun colours(b: Bitmap): Map<Int, Int> {
            val counts = HashMap<Int, Int>()
            for (y in 0 until 512 step 2) for (x in 0 until 512 step 2) counts.merge(b.getPixel(x, y), 1, Int::plus)
            return counts
        }
        val lightColours = colours(light)
        val darkColours = colours(dark)
        // A map, not a flat plate: many colours, and no single one covers nearly everything.
        assertTrue("light has ${lightColours.size} colours", lightColours.size > 40)
        assertTrue(lightColours.values.max() < 65536 * 9 / 10)

        // Eclipse is darker on average (`style.rs::eclipse_is_darker`).
        fun sum(c: Int) = (Color.red(c) + Color.green(c) + Color.blue(c)).toLong()

        fun luma(m: Map<Int, Int>) = m.entries.sumOf { (c, n) -> sum(c) * n } / m.values.sum()
        assertTrue(luma(darkColours) < luma(lightColours) - 150)
        // Fully opaque: tiles are drawn over each other without blending.
        assertTrue(lightColours.keys.all { Color.alpha(it) == 255 })
    }

    @Test fun cachedPathsMatchTheBufferVertexCount() {
        val buffer = MapFixtures.cityTile()
        val prepared = PreparedTile(buffer)
        assertEquals(buffer.classes.size, prepared.records.size)
        // A cased line shares its paths with its casing instead of building them twice.
        val records = prepared.records
        val shared = records.indices.count { it > 0 && records[it].paths === records[it - 1].paths }
        assertTrue("shared records: $shared", shared > 0)
        assertTrue(prepared.sizeBytes > buffer.vertexCount * 8L)
    }

    // ---- E7.13 AC1 ---------------------------------------------------------------------------------------------

    /**
     * Rasterising the dense city tile (the whole tile into one 512 px bitmap, the heaviest case: every path is
     * replayed) takes under 25 ms on the host, median after warm-up. The numbers go to `build/map-benchmark.txt`
     * for the write-up. Preparing the paths (once per downloaded tile) is timed apart.
     */
    @Test fun rasterisingTheDenseCityTileTakesUnder25Ms() {
        val buffer = MapFixtures.cityTile()
        val prepareNs = LongArray(15)
        var prepared = PreparedTile(buffer)
        for (i in prepareNs.indices) {
            val t0 = System.nanoTime()
            prepared = PreparedTile(buffer)
            prepareNs[i] = System.nanoTime() - t0
        }
        val bitmap = TileRasterizer.newBitmap()
        repeat(WARM_UP) { rasterizer.render(prepared, 14, buffer.x, buffer.y, 14.0, 1f, bitmap) }
        val fullNs = LongArray(RUNS)
        for (i in fullNs.indices) {
            val t0 = System.nanoTime()
            rasterizer.render(prepared, 14, buffer.x, buffer.y, 14.0, 1f, bitmap)
            fullNs[i] = System.nanoTime() - t0
        }
        // What a 3x phone paints: one of the 16 bitmaps under the data tile, at the display zoom it is made for.
        val subNs = LongArray(RUNS)
        for (i in subNs.indices) {
            val t0 = System.nanoTime()
            rasterizer.render(prepared, 16, buffer.x * 4 + 1 + i % 2, buffer.y * 4 + 1 + (i / 2) % 2, 14.4, 3f, bitmap)
            subNs[i] = System.nanoTime() - t0
        }

        fun line(
            what: String,
            median: Double,
            max: Double,
        ) = "$what: median %.2f ms, max %.2f ms".format(median, max)

        fun LongArray.medianMs() = sorted()[size / 2] / 1e6

        fun LongArray.maxMs() = max() / 1e6
        val paths = prepared.records.sumOf { it.paths.size }
        val report =
            buildString {
                appendLine("city tile 14/9520/6130: ${buffer.vertexCount} vertices, ${buffer.classes.size} records")
                appendLine("$paths paths")
                appendLine(line("prepare paths (once per tile)", prepareNs.medianMs(), prepareNs.maxMs()))
                appendLine(line("rasterise whole tile into 512 px", fullNs.medianMs(), fullNs.maxMs()))
                appendLine(line("rasterise 1/16 of it into 512 px (3x screen)", subNs.medianMs(), subNs.maxMs()))
            }
        println(report)
        File("build").mkdirs()
        File("build/map-benchmark.txt").writeText(report)
        assertTrue("median ${fullNs.medianMs()} ms", fullNs.medianMs() < BUDGET_MS)
        assertTrue("sub-tile median ${subNs.medianMs()} ms", subNs.medianMs() < BUDGET_MS)
    }

    /**
     * A made-up downtown tile, far denser than the fixture (which is a village in a forest): [blocks] x [blocks] city
     * blocks, each with four houses (filled, with outlines like the writer makes them) on residential land, and a
     * cased street grid with a vertex at every crossing.
     */
    private fun denseTile(blocks: Int): TileBuffer {
        val step = 4096f / blocks
        val land = ArrayList<FloatArray>()
        val houses = ArrayList<FloatArray>()
        val outlines = ArrayList<FloatArray>()
        for (bx in 0 until blocks) {
            for (by in 0 until blocks) {
                val l = bx * step
                val t = by * step
                land += square(l + step * 0.08f, t + step * 0.08f, l + step * 0.92f, t + step * 0.92f)
                for (h in 0 until 4) {
                    val hl = l + step * (0.14f + 0.40f * (h % 2))
                    val ht = t + step * (0.14f + 0.40f * (h / 2))
                    // An L-shaped house: six corners.
                    val w = step * 0.30f
                    val m = w * 0.5f
                    val ring =
                        floatArrayOf(hl, ht, hl + w, ht, hl + w, ht + m, hl + m, ht + m, hl + m, ht + w, hl, ht + w)
                    check(TileBuffer.ringArea2(ring) > 0)
                    houses += ring
                    outlines += ring + floatArrayOf(hl, ht)
                }
            }
        }
        val streets = ArrayList<FloatArray>()
        for (i in 0..blocks) {
            streets += FloatArray(2 * (blocks + 1)) { if (it % 2 == 0) i * step else (it / 2) * step }
            streets += FloatArray(2 * (blocks + 1)) { if (it % 2 == 0) (it / 2) * step else i * step }
        }
        val classes =
            listOf(
                TileBuffer.TileClass(5, false, 0xE8E4E0FF.toInt(), 0f, TileBuffer.Kind.Polygon, land),
                TileBuffer.TileClass(20, false, 0xD9D0C9FF.toInt(), 0f, TileBuffer.Kind.Polygon, houses),
                TileBuffer.TileClass(20, true, 0xBFB5ABFF.toInt(), 1f, TileBuffer.Kind.Line, outlines),
                TileBuffer.TileClass(33, true, 0xCFCDCAFF.toInt(), 5.5f, TileBuffer.Kind.Line, streets),
                TileBuffer.TileClass(33, false, 0xFFFFFFFF.toInt(), 4f, TileBuffer.Kind.Line, streets),
            )
        return TileBuffer(0, 14, 100, 200, 4096, BACKGROUND, classes)
    }

    /**
     * Not the AC's tile, but the honest question behind it: what does a downtown tile cost? Recorded for the write-up
     * (`build/map-benchmark-dense.txt`); the 30 000 vertex case is held to the same 25 ms.
     */
    @Test fun rasterisingMadeUpDowntownTiles() {
        val report = StringBuilder()
        var worst = 0.0
        for (blocks in intArrayOf(16, 32, 48)) {
            val buffer = denseTile(blocks)
            val t0 = System.nanoTime()
            val prepared = PreparedTile(buffer)
            val prepareMs = (System.nanoTime() - t0) / 1e6
            val bitmap = TileRasterizer.newBitmap()
            repeat(WARM_UP) { rasterizer.render(prepared, 14, buffer.x, buffer.y, 14.0, 1f, bitmap) }
            val full = LongArray(RUNS)
            for (i in full.indices) {
                val s = System.nanoTime()
                rasterizer.render(prepared, 14, buffer.x, buffer.y, 14.0, 1f, bitmap)
                full[i] = System.nanoTime() - s
            }
            val sub = LongArray(RUNS)
            for (i in sub.indices) {
                val s = System.nanoTime()
                val sx = buffer.x * 4 + 1 + i % 2
                val sy = buffer.y * 4 + 1 + (i / 2) % 2
                rasterizer.render(prepared, 16, sx, sy, 14.4, 3f, bitmap)
                sub[i] = System.nanoTime() - s
            }
            val fullMs = full.sorted()[RUNS / 2] / 1e6
            val subMs = sub.sorted()[RUNS / 2] / 1e6
            // The 30 000 vertex tile is held to the budget; the 69 000 vertex one (above the GPUI map's 60 000 cap) is
            // recorded only, so a busy build machine does not fail the gate on a tile no server sends.
            if (blocks <= ASSERTED_BLOCKS) worst = maxOf(worst, fullMs, subMs)
            val paths = prepared.records.sumOf { it.paths.size }
            report.appendLine(
                "made-up downtown, $blocks x $blocks blocks: ${buffer.vertexCount} vertices, $paths paths; " +
                    "prepare %.1f ms; whole tile median %.2f ms; ".format(prepareMs, fullMs) +
                    "1/16 at 3x median %.2f ms".format(subMs),
            )
        }
        println(report)
        File("build").mkdirs()
        File("build/map-benchmark-dense.txt").writeText(report.toString())
        assertTrue("worst median $worst ms\n$report", worst < BUDGET_MS)
    }

    private companion object {
        const val BACKGROUND = 0xF8F4F0FF.toInt()
        const val BACKGROUND_ARGB = 0xFFF8F4F0.toInt()
        const val RED = 0xFF0000FF.toInt()
        const val RED_ARGB = 0xFFFF0000.toInt()
        const val BLUE = 0x0000FFFF
        const val BLUE_ARGB = 0xFF0000FF.toInt()
        const val GREEN = 0x00FF00FF
        const val GREEN_ARGB = 0xFF00FF00.toInt()
        const val BLACK = 0x000000FF
        const val BLACK_ARGB = 0xFF000000.toInt()
        const val WARM_UP = 20
        const val RUNS = 31
        const val BUDGET_MS = 25.0
        const val ASSERTED_BLOCKS = 32
    }
}
