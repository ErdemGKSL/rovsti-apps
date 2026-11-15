package dev.rosty.stops.map

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * E7.2a AC1: the Kotlin camera and tile maths against every `view.*` fixture of `rosty_ffi` (the answers of
 * `rosty_geo::view` and `rosty_geo::tiles::slippy`).
 *
 * Rust's viewport is a local equirectangular approximation, the camera is exact Web Mercator; over the few
 * kilometres of the fixtures they differ by well under a pixel, which is the tolerance here ([PX], [DEG], [REL]).
 * Tile lists, zooms and the follow table must match exactly.
 */
class MapCameraFixtureTest {
    private fun camera(viewport: kotlinx.serialization.json.JsonElement): MapCamera {
        val centre = viewport.obj("center")
        val camera = MapCamera()
        assertTrue(camera.setView(centre.d("lat"), centre.d("lon"), viewport.d("metres_per_px")))
        return camera
    }

    private fun assertViewport(
        name: String,
        expected: kotlinx.serialization.json.JsonElement,
        camera: MapCamera,
    ) {
        val centre = expected.obj("center")
        assertEquals("$name lat", centre.d("lat"), camera.centreLat, DEG)
        assertEquals("$name lon", centre.d("lon"), camera.centreLon, DEG)
        val mpp = expected.d("metres_per_px")
        assertEquals("$name metres per px", mpp, camera.metresPerDp, mpp * REL)
    }

    @Test fun project() {
        for (f in MapFixtures.pairs("view.project")) {
            val camera = camera(f.request.obj("viewport"))
            assertViewport(f.name, f.ok.obj("viewport"), camera)
            assertEquals(f.name, f.ok.d("display_zoom"), camera.displayZoom, ZOOM)
            assertEquals(f.name, f.ok["fetch_zoom"]!!.jsonPrimitive.int, MapMath.fetchZoom(camera.displayZoom))
            assertEquals(f.ok.d("min_metres_per_px"), MapMath.MIN_METRES_PER_DP, 0.0)
            assertEquals(f.ok.d("max_metres_per_px"), MapMath.MAX_METRES_PER_DP, 0.0)
            assertEquals(f.ok.d("tile_display_px"), MapMath.TILE_DISPLAY_DP, 0.0)
            // Pixels of the fixtures are relative to the viewport centre: a 0 x 0 viewport has its centre at 0, 0.
            val points = f.request.arr("points")
            val projected = f.ok.arr("projected")
            assertEquals(points.size, projected.size)
            for (i in points.indices) {
                val x = camera.screenX(points[i].d("lon"))
                val y = camera.screenY(points[i].d("lat"))
                assertEquals("${f.name} x$i", projected[i].at(0), x.toDouble(), PX)
                assertEquals("${f.name} y$i", projected[i].at(1), y.toDouble(), PX)
            }
            val pixels = f.request.arr("pixels")
            val unprojected = f.ok.arr("unprojected")
            assertEquals(pixels.size, unprojected.size)
            for (i in pixels.indices) {
                val lat = camera.latAt(pixels[i].at(1).toFloat())
                val lon = camera.lonAt(pixels[i].at(0).toFloat())
                assertEquals("${f.name} lat$i", unprojected[i].d("lat"), lat, DEG)
                assertEquals("${f.name} lon$i", unprojected[i].d("lon"), lon, DEG)
            }
        }
    }

    @Test fun step() {
        var kinds = HashSet<String>()
        for (f in MapFixtures.pairs("view.step")) {
            val camera = camera(f.request.obj("viewport"))
            val step = f.request.obj("step")
            val kind = step["kind"]!!.jsonPrimitive.content
            kinds += kind
            var nearest: Int? = null
            when (kind) {
                "fit" -> {
                    camera.setViewport(step.d("width").toInt(), step.d("height").toInt(), 1f)
                    val points = step.arr("points")
                    val latLon =
                        DoubleArray(points.size * 2) {
                            if (it % 2 ==
                                0
                            ) {
                                points[it / 2].d("lat")
                            } else {
                                points[it / 2].d("lon")
                            }
                        }
                    assertTrue(camera.fitPoints(latLon, marginPx = step.d("padding").toFloat()))
                }

                "zoom_about" -> {
                    // A refused factor leaves the view alone, like Rust.
                    camera.zoomAbout(
                        step.d("factor"),
                        step.arr("focal").at(0).toFloat(),
                        step.arr("focal").at(1).toFloat(),
                    )
                }

                "pan" -> {
                    assertTrue(camera.panBy(step.d("dx").toFloat(), step.d("dy").toFloat()))
                }

                "nearest" -> {
                    val points = step.arr("points")
                    val latLon =
                        DoubleArray(points.size * 2) {
                            if (it % 2 ==
                                0
                            ) {
                                points[it / 2].d("lat")
                            } else {
                                points[it / 2].d("lon")
                            }
                        }
                    val at = step.arr("at")
                    nearest =
                        camera.nearestOnScreen(
                            latLon,
                            at.at(0).toFloat(),
                            at.at(1).toFloat(),
                            step.d("max_px").toFloat(),
                        )
                }

                else -> {
                    error("${f.name}: unknown step $kind, teach the test")
                }
            }
            assertViewport(f.name, f.ok.obj("viewport"), camera)
            assertEquals(f.name, f.ok.d("display_zoom"), camera.displayZoom, ZOOM)
            val expectedNearest = f.ok["nearest"]
            if (expectedNearest == null || expectedNearest is JsonNull) {
                assertTrue(f.name, nearest == null || nearest == -1)
            } else {
                assertEquals(f.name, expectedNearest.jsonPrimitive.int, nearest)
            }
        }
        assertEquals(setOf("fit", "zoom_about", "pan", "nearest"), kinds)
    }

    @Test fun tiles() {
        for (f in MapFixtures.pairs("view.tiles")) {
            val camera = camera(f.request.obj("viewport"))
            camera.setViewport(f.request.d("width").toInt(), f.request.d("height").toInt(), 1f)
            val cover = TileCovers.forView(camera, f.request["max_tiles"]!!.jsonPrimitive.int)
            assertEquals(f.name, f.ok["zoom"]!!.jsonPrimitive.int, cover.zoom)
            assertEquals(f.name, f.ok.d("display_zoom"), camera.displayZoom, ZOOM)
            assertEquals(f.name, f.ok["count"]!!.jsonPrimitive.int, cover.tiles.size)
            val expected = f.ok.arr("tiles")
            for (i in expected.indices) {
                val t = expected[i].jsonObject
                val key =
                    TileKey.of(
                        t["z"]!!.jsonPrimitive.int,
                        t["x"]!!.jsonPrimitive.long.toInt(),
                        t["y"]!!.jsonPrimitive.long.toInt(),
                    )
                assertEquals("${f.name} tile $i", TileKey.describe(key), TileKey.describe(cover.tiles[i]))
                // The corners of a tile, `TileId::nw` and `se`.
                val z = TileKey.z(key)
                assertEquals(t.obj("nw").d("lat"), MapMath.fracToLat(z, TileKey.y(key).toDouble()), 1e-9)
                assertEquals(t.obj("nw").d("lon"), MapMath.fracToLon(z, TileKey.x(key).toDouble()), 1e-9)
                assertEquals(t.obj("se").d("lat"), MapMath.fracToLat(z, TileKey.y(key) + 1.0), 1e-9)
                assertEquals(t.obj("se").d("lon"), MapMath.fracToLon(z, TileKey.x(key) + 1.0), 1e-9)
            }
            val centre = f.ok.obj("centre_tile")
            val centreKey = TileKey.containing(camera.centreLat, camera.centreLon, cover.zoom)
            assertEquals(f.name, "${centre["z"]}/${centre["x"]}/${centre["y"]}", TileKey.describe(centreKey))
        }
    }

    @Test fun fracToCoord() {
        for (f in MapFixtures.pairs("view.frac_to_coord")) {
            val z = f.request["z"]!!.jsonPrimitive.int
            assertEquals(f.name, f.ok.obj("coord").d("lat"), MapMath.fracToLat(z, f.request.d("fy")), 1e-9)
            assertEquals(f.name, f.ok.obj("coord").d("lon"), MapMath.fracToLon(z, f.request.d("fx")), 1e-9)
            assertEquals(f.ok.d("max_lat"), MapMath.MAX_MERCATOR_LAT, 0.0)
            val coords = f.request.arr("coords")
            val fracs = f.ok.arr("fracs")
            assertEquals(coords.size, fracs.size)
            for (i in coords.indices) {
                assertEquals("${f.name} fx", fracs[i].at(0), MapMath.tileFracX(coords[i].d("lon"), z), 1e-6)
                assertEquals("${f.name} fy", fracs[i].at(1), MapMath.tileFracY(coords[i].d("lat"), z), 1e-6)
            }
        }
    }

    @Test fun follow() {
        for (f in MapFixtures.pairs("view.follow")) {
            val mode = MapFollowMode.fromWire(f.request["mode"]!!.jsonPrimitive.content)
            if (f.isError) {
                // Only an unknown mode is refused.
                assertNull(f.name, mode)
                continue
            }
            val next =
                when (val event = f.request["event"]!!.jsonPrimitive.content) {
                    "me_tap" -> mode!!.onMeTap(f.request["has_fix"]?.jsonPrimitive?.boolean ?: false)
                    "pan" -> mode!!.onPan()
                    else -> error("unknown event $event")
                }
            assertEquals(f.name, f.ok["mode"]!!.jsonPrimitive.content, next.wire)
            assertEquals(f.name, f.ok["watches"]!!.jsonPrimitive.boolean, next.watches)
        }
    }

    @Test fun theFixturesAreAllCovered() {
        // A new op under view.* must get a test here.
        val ops =
            java.io
                .File(System.getProperty("rosty.ffi.fixtures"))
                .list()!!
                .filter { it.startsWith("view.") }
                .map { it.split('.')[1] }
                .toSet()
        assertEquals(setOf("project", "step", "tiles", "frac_to_coord", "follow"), ops)
        assertTrue(abs(MapMath.EQUATOR_M - 40_075_016.686) < 1e-6)
    }

    private companion object {
        /** Screen pixels: Mercator against the equirectangular approximation. */
        const val PX = 0.25

        /** Degrees: about a metre. */
        const val DEG = 1e-5

        /** Relative scale. */
        const val REL = 1e-3

        /** `display_zoom` from the camera's centre and scale, the Rust formula. */
        const val ZOOM = 1e-4
    }
}
