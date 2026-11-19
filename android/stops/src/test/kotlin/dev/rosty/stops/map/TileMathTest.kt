package dev.rosty.stops.map

import dev.rosty.map.TileRasterizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E7.2a: tile ids, covers and the stand-in rule, against the Rust semantics (`slippy.rs`, `geometry.rs`). */
class TileMathTest {
    private fun camera(
        lat: Double,
        lon: Double,
        metresPerDp: Double,
        width: Int = 390,
        height: Int = 780,
        density: Float = 1f,
    ) = MapCamera(lat, lon, metresPerDp).apply { setViewport(width, height, density) }

    // ---- ids ----

    @Test fun keysPackAndUnpack() {
        for ((z, x, y) in listOf(
            Triple(0, 0, 0),
            Triple(14, 9520, 6130),
            Triple(20, 1_048_575, 3),
            Triple(24, 16_777_215, 16_777_215),
        )) {
            val key = TileKey.of(z, x, y)
            assertEquals(z, TileKey.z(key))
            assertEquals(x, TileKey.x(key))
            assertEquals(y, TileKey.y(key))
            assertTrue(key >= 0)
        }
        assertEquals("14/9520/6130", TileKey.describe(TileKey.of(14, 9520, 6130)))
    }

    @Test fun checkedRefusesTilesOffTheMap() {
        // `TileId::new`.
        assertEquals(TileKey.of(3, 7, 7), TileKey.checked(3, 7, 7))
        assertEquals(TileKey.NONE, TileKey.checked(3, 8, 0))
        assertEquals(TileKey.NONE, TileKey.checked(3, 0, 8))
        assertEquals(TileKey.NONE, TileKey.checked(3, -1, 0))
        assertEquals(TileKey.NONE, TileKey.checked(25, 0, 0))
        assertEquals(TileKey.of(0, 0, 0), TileKey.checked(0, 0, 0))
    }

    @Test fun knownTiles() {
        // `slippy.rs::known_tiles`.
        assertEquals(TileKey.of(14, 9510, 6142), TileKey.containing(41.0082, 28.9784, 14))
        assertEquals(TileKey.of(0, 0, 0), TileKey.containing(41.0082, 28.9784, 0))
        assertEquals(TileKey.of(1, 1, 0), TileKey.containing(0.0001, 0.0001, 1))
    }

    @Test fun containingClampsLatitudeAndWrapsLongitude() {
        // `slippy.rs::clamps_latitude_and_wraps_longitude`.
        assertEquals(0, TileKey.y(TileKey.containing(89.0, 10.0, 5)))
        assertEquals(31, TileKey.y(TileKey.containing(-89.0, 10.0, 5)))
        assertEquals(TileKey.containing(10.0, -170.0, 5), TileKey.containing(10.0, 190.0, 5))
        assertEquals(0, TileKey.x(TileKey.containing(10.0, 180.0, 5)))
        assertEquals(31, TileKey.x(TileKey.containing(10.0, 179.999, 5)))
    }

    @Test fun columnsWrapModuloTwoToTheZoom() {
        assertEquals(TileKey.of(3, 7, 2), TileKey.wrapped(3, -1, 2))
        assertEquals(TileKey.of(3, 0, 2), TileKey.wrapped(3, 8, 2))
        assertEquals(TileKey.of(3, 5, 2), TileKey.wrapped(3, 5 + 8 * 1000L, 2))
        assertEquals(TileKey.of(0, 0, 0), TileKey.wrapped(0, -7, 0))
    }

    @Test fun ancestors() {
        val t = TileKey.of(14, 9520, 6130)
        assertEquals(t, TileKey.ancestor(t, 0))
        assertEquals(TileKey.of(13, 4760, 3065), TileKey.ancestor(t, 1))
        assertEquals(TileKey.of(0, 0, 0), TileKey.ancestor(t, 14))
        assertEquals(TileKey.NONE, TileKey.ancestor(t, 15))
        assertEquals(TileKey.NONE, TileKey.ancestor(t, -1))
    }

    @Test fun fracRoundTrip() {
        // `slippy.rs::frac_round_trip`.
        for (z in intArrayOf(0, 5, 14, 20)) {
            val fx = MapMath.tileFracX(28.9784, z)
            val fy = MapMath.tileFracY(41.0082, z)
            assertEquals(41.0082, MapMath.fracToLat(z, fy), 1e-9)
            assertEquals(28.9784, MapMath.fracToLon(z, fx), 1e-9)
        }
    }

    // ---- stand-in ----

    @Test fun standInIsTheNearestLoadedAncestorUpToFourZoomsUp() {
        // `geometry.rs::stand_in_nearest_ancestor`, case by case.
        val want = TileKey.of(14, 9520, 6130)
        val p2 = TileKey.ancestor(want, 2)
        val p4 = TileKey.ancestor(want, 4)
        val p5 = TileKey.ancestor(want, 5)
        assertEquals(want, standIn(want) { it == want || it == p2 })
        assertEquals(p2, standIn(want) { it == p2 || it == p4 })
        assertEquals(p4, standIn(want) { it == p4 })
        assertEquals(TileKey.NONE, standIn(want) { it == p5 })
        assertEquals(TileKey.NONE, standIn(want) { false })
        val root = TileKey.of(0, 0, 0)
        assertEquals(root, standIn(root) { it == root })
        // Near the top of the pyramid the walk ends at zoom 0 without going negative.
        val shallow = TileKey.of(2, 3, 1)
        assertEquals(root, standIn(shallow) { it == root })
        assertEquals(TileKey.NONE, standIn(shallow) { false })
    }

    @Test fun standInMatchesTheRustFixtures() {
        for (f in MapFixtures.pairs("tiles.stand_in")) {
            fun key(e: kotlinx.serialization.json.JsonElement) =
                TileKey.of(e.d("z").toInt(), e.d("x").toInt(), e.d("y").toInt())
            val want = key(f.request.obj("want"))
            val have =
                f.request
                    .arr("have")
                    .map { key(it) }
                    .toSet()
            val got = standIn(want) { it in have }
            val expected = f.ok["tile"]
            if (expected == null || expected is kotlinx.serialization.json.JsonNull) {
                assertEquals(f.name, TileKey.NONE, got)
            } else {
                assertEquals(f.name, TileKey.describe(key(expected)), TileKey.describe(got))
            }
        }
    }

    // ---- covers ----

    @Test fun coverIsCentreFirstAndHasNoDuplicates() {
        val c = camera(41.0082, 28.9784, 4.0)
        val cover = TileCovers.cover(c, 14)
        assertEquals(TileKey.containing(c.centreLat, c.centreLon, 14), cover.tiles.first())
        assertEquals(cover.tiles.size, cover.tiles.toSet().size)
        assertEquals(cover.tiles.size, TileCovers.count(c, 14))
        // Every corner of the viewport is in a covered tile.
        for ((x, y) in listOf(0f to 0f, 389f to 0f, 0f to 779f, 389f to 779f)) {
            assertTrue(TileKey.containing(c.latAt(y), c.lonAt(x), 14) in cover)
        }
    }

    @Test fun coverWrapsAcrossTheAntimeridian() {
        val c = camera(0.0, 179.9, 2000.0, width = 800, height = 400)
        val cover = TileCovers.cover(c, 5)
        val columns = cover.tiles.map { TileKey.x(it) }.toSet()
        assertTrue("the last column and the first: $columns", 31 in columns && 0 in columns)
        assertTrue(columns.all { it in 0..31 })
        assertEquals(cover.tiles.size, cover.tiles.toSet().size)
        // The camera east of the line sees the same tiles.
        val east = camera(0.0, -179.9, 2000.0, width = 800, height = 400)
        assertEquals(
            columns,
            TileCovers
                .cover(east, 5)
                .tiles
                .map { TileKey.x(it) }
                .toSet(),
        )
    }

    @Test fun aViewWiderThanTheWorldListsEveryColumnOnce() {
        val c = camera(0.0, 0.0, 2000.0, width = 100_000, height = 300)
        val cover = TileCovers.cover(c, 3)
        val columns = cover.tiles.map { TileKey.x(it) }
        assertEquals((0..7).toSet(), columns.toSet())
        assertEquals(cover.tiles.size, cover.tiles.toSet().size)
    }

    @Test fun rowsStopAtTheEdgeOfTheProjection() {
        val c = camera(85.0, 0.0, 2000.0, width = 400, height = 4000)
        val cover = TileCovers.cover(c, 4, ring = 1)
        assertTrue(cover.tiles.all { TileKey.y(it) in 0..15 })
        assertTrue(cover.tiles.any { TileKey.y(it) == 0 })
    }

    @Test fun zoomZeroIsOneTile() {
        val c = camera(0.0, 0.0, 2000.0, width = 300, height = 300)
        val cover = TileCovers.cover(c, 0, ring = 1)
        assertEquals(listOf(TileKey.of(0, 0, 0)), cover.tiles.toList())
    }

    @Test fun aZeroSizeViewportStillCoversTheCentre() {
        val c = camera(41.0082, 28.9784, 4.0, width = 0, height = 0)
        val cover = TileCovers.cover(c, 14)
        assertEquals(listOf(TileKey.of(14, 9510, 6142)), cover.tiles.toList())
    }

    @Test fun marginAddsTiles() {
        val c = camera(41.0082, 28.9784, 4.0)
        val plain = TileCovers.count(c, 14)
        assertTrue(TileCovers.count(c, 14, marginPx = 600f) > plain)
        assertTrue(TileCovers.count(c, 14, ring = 1) > plain)
        assertFalse(TileCovers.cover(c, 14).tiles.isEmpty())
    }

    @Test fun forViewOverBudgetAtZoomZeroIsEmpty() {
        val c = camera(0.0, 0.0, 2000.0)
        assertEquals(0, TileCovers.forView(c, 0).tiles.size)
        assertEquals(0, TileCovers.forView(c, 0).zoom)
    }

    // ---- data tiles under raster tiles ----

    @Test fun rasterZoomIsTheNearestIntegerAndBounded() {
        assertEquals(14, MapTileStore.rasterZoomFor(14.49))
        assertEquals(15, MapTileStore.rasterZoomFor(14.5))
        assertEquals(0, MapTileStore.rasterZoomFor(-2.0))
        assertEquals(0, MapTileStore.rasterZoomFor(Double.NaN))
        assertEquals(TileRasterizer.MAX_RASTER_ZOOM, MapTileStore.rasterZoomFor(40.0))
    }

    @Test fun onA1xScreenTheDataTileIsTheRasterTileUpToZoom14() {
        val t = TileKey.of(14, 9520, 6130)
        assertEquals(t, MapTileStore.dataTileFor(t, 1f))
        assertEquals(TileKey.of(5, 16, 16), MapTileStore.dataTileFor(TileKey.of(5, 16, 16), 1f))
        // Closer than 14 there is no data: the zoom 14 tile is enlarged.
        assertEquals(t, MapTileStore.dataTileFor(TileKey.of(16, 9520 * 4 + 3, 6130 * 4 + 1), 1f))
    }

    @Test fun aDenseScreenPaintsSeveralBitmapsFromOneDataTile() {
        val t = TileKey.of(14, 9520, 6130)
        // 2x: one zoom more of bitmaps for the same ground; 3x rounds to two.
        assertEquals(t, MapTileStore.dataTileFor(TileKey.of(15, 9520 * 2 + 1, 6130 * 2), 2f))
        assertEquals(t, MapTileStore.dataTileFor(TileKey.of(16, 9520 * 4 + 3, 6130 * 4 + 3), 3f))
        assertEquals(TileKey.of(13, 4760, 3065), MapTileStore.dataTileFor(TileKey.of(15, 9520 * 2, 6130 * 2), 3f))
        // The deepest bitmap zoom still has a data tile no more than six zooms up.
        val deep = TileKey.of(TileRasterizer.MAX_RASTER_ZOOM, 9520 shl 6, 6130 shl 6)
        assertEquals(t, MapTileStore.dataTileFor(deep, 3.5f))
        assertEquals(TileKey.of(0, 0, 0), MapTileStore.dataTileFor(TileKey.of(1, 1, 1), 3f))
    }

    @Test fun lineWidthFollowsTheDocRule() {
        // `style.rs::line_width_scales_and_clamps`: w14 * 2^clamp(dz - 14, -3, 2), at least 0.5.
        assertEquals(4f, TileRasterizer.lineWidthDp(4f, 14.0), 1e-6f)
        assertEquals(8f, TileRasterizer.lineWidthDp(4f, 15.0), 1e-6f)
        assertEquals(16f, TileRasterizer.lineWidthDp(4f, 19.0), 1e-6f)
        assertEquals(0.5f, TileRasterizer.lineWidthDp(4f, 11.0), 1e-6f)
        assertEquals(0.5f, TileRasterizer.lineWidthDp(4f, 3.0), 1e-6f)
        assertEquals(0.5f, TileRasterizer.lineWidthDp(0.2f, 14.0), 1e-6f)
        assertEquals(12f, TileRasterizer.lineWidthPx(4f, 14.0, 3f), 1e-6f)
    }
}
