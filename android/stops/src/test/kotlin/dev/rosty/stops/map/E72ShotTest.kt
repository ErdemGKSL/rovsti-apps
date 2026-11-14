package dev.rosty.stops.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.design.theme.RostyTheme
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * E7.2 screenshots: the real city tile (`osm-14-9520-6130.mvt`, as Rust encoded it) through the whole pipeline (fake
 * source, scheduler, rasteriser, bitmap cache, draw node) on the 411 x 891 dp xxhdpi test window, with the attribution
 * and the scale bar where the home screen will float them.
 */
class E72ShotTest : ShotTest() {
    /** Paints queued raster work only when the test says so, to hold a frame half way through loading. */
    private class StepDispatcher : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue += block
        }

        fun run(max: Int = Int.MAX_VALUE): Int {
            var n = 0
            while (n < max) {
                val next = queue.removeFirstOrNull() ?: break
                next.run()
                n++
            }
            return n
        }
    }

    private var store: MapTileStore? = null

    @Composable
    private fun MapScreen(
        camera: MapCamera,
        source: TileSource,
        raster: CoroutineDispatcher = Dispatchers.Unconfined,
        units: MapUnits = MapUnits.Metric,
        extra: @Composable BoxScope.() -> Unit = {},
    ) {
        val scope = rememberCoroutineScope()
        val tiles = remember(source) { MapTileStore(source, scope, rasterDispatcher = raster) }
        DisposableEffect(tiles) {
            store = tiles
            onDispose { tiles.close() }
        }
        Box(Modifier.fillMaxSize()) {
            StopsMap(camera, tiles, dark = RostyTheme.colors.isDark, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars,
                    ).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                MapScaleBar(camera, Modifier.align(Alignment.BottomStart), units)
                MapAttribution(Modifier.align(Alignment.BottomEnd))
                extra()
            }
        }
    }

    /**
     * Over the village in the north-east of the fixture tile (houses, a stream, streets, residential land and the
     * forest around it), close enough that the window stays inside the one tile the fake source has. The data is the
     * zoom 14 tile; a 3x screen at display zoom 15.4 paints it as bitmaps of raster zoom 17.
     */
    private fun cityCamera(zoom: Double = CITY_ZOOM) =
        MapCamera().apply {
            jumpTo(
                MapMath.fracToLat(14, MapFixtures.CITY_Y + VILLAGE_Y),
                MapMath.fracToLon(14, MapFixtures.CITY_X + VILLAGE_X),
                zoom,
            )
        }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name.png",
            roborazziOptions =
                RoborazziOptions(
                    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD),
                ),
        )
    }

    private fun settleTiles() {
        repeat(6) { advance(100) }
    }

    @Test fun colorfulAtZoom14() {
        val camera = cityCamera()
        val source = FakeTileSource.city()
        composeFrame(dark = false) { MapScreen(camera, source) }
        settleTiles()
        val tiles = store!!
        assertEquals(17, tiles.rasterZoom)
        assertEquals(0, tiles.pending)
        assertEquals(listOf(FakeTileSource.CITY), source.requests)
        assertTrue("bitmaps: ${tiles.cachedBitmaps}", tiles.cachedBitmaps in 8..24)
        capture("e7.2-map-colorful-z14-light")
    }

    @Test fun eclipseAtZoom14() {
        val camera = cityCamera()
        val source = FakeTileSource.city()
        composeFrame(dark = true) { MapScreen(camera, source) }
        settleTiles()
        assertTrue(store!!.dark)
        assertEquals(0, store!!.pending)
        capture("e7.2-map-eclipse-z14-dark")
    }

    @Test fun missingTilesShowTheRadar() {
        // Centred on the tile's north-east corner: a quarter is map, the rest was never downloaded.
        shot("e7.2-map-missing-tiles-radar") {
            val camera =
                remember {
                    MapCamera().apply {
                        jumpTo(
                            MapFixtures.cityLat +
                                (MapMath.fracToLat(14, MapFixtures.CITY_Y.toDouble()) - MapFixtures.cityLat) * 0.9,
                            MapMath.fracToLon(14, MapFixtures.CITY_X + 1.0),
                            RADAR_ZOOM,
                        )
                    }
                }
            MapScreen(camera, remember { FakeTileSource.city() })
            // The tiles arrive within the frames `shot` runs before it captures (the source and the painting are
            // immediate here).
        }
    }

    @Test fun aParentStandsInWhileItsChildrenLoad() {
        for (dark in listOf(false, true)) {
            val raster = StepDispatcher()
            val camera = cityCamera(zoom = 14.85)
            val source = FakeTileSource.city()
            composeFrame(dark = dark) { MapScreen(camera, source, raster) }
            // Paint everything of the first view (raster zoom 16).
            repeat(8) {
                advance(50)
                raster.run()
            }
            val tiles = store!!
            assertEquals(16, tiles.rasterZoom)
            assertEquals(0, tiles.pending)
            val first = tiles.cachedBitmaps
            // Zoom in one level about the centre. The sharper tiles (raster zoom 17) are queued; only six of them
            // get painted, the rest of the screen keeps the enlarged parents.
            camera.zoomAbout(2.0, camera.widthPx / 2f, camera.heightPx / 2f)
            advance(50)
            assertEquals(17, tiles.rasterZoom)
            assertTrue("children queued: ${tiles.pending}", tiles.pending > 8)
            raster.run(6)
            advance(50)
            assertEquals(first + 6, tiles.cachedBitmaps)
            capture("e7.2-map-standin-parent-${if (dark) "dark" else "light"}")
        }
    }

    @Test fun scaleBar() {
        shot("e7.2-scale-bar") {
            val camera = remember { cityCamera().apply { setView(centreLat, centreLon, 2.0) } }
            MapScreen(camera, remember { FakeTileSource.city() }) {
                // Both unit systems, the imperial one above the metric one of the screen.
                Column(Modifier.align(Alignment.BottomStart).padding(bottom = 40.dp)) {
                    MapScaleBar(camera, units = MapUnits.Imperial)
                }
            }
        }
        // 2 m per dp: the metric bar is 200 m = 100 dp, the imperial one 500 ft = 76.2 dp.
        assertEquals(100f, ScaleBars.lengthDp(ScaleBars.stepIndex(2.0, MapUnits.Metric), 2.0, MapUnits.Metric), 1e-3f)
        assertEquals(
            76.2f,
            ScaleBars.lengthDp(ScaleBars.stepIndex(2.0, MapUnits.Imperial), 2.0, MapUnits.Imperial),
            1e-3f,
        )
    }

    private companion object {
        const val CITY_ZOOM = 15.4
        const val RADAR_ZOOM = 14.82
        const val VILLAGE_X = 0.845
        const val VILLAGE_Y = 0.335
    }
}
