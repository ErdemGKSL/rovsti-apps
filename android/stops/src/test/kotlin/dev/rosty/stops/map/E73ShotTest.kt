package dev.rosty.stops.map

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.CrossingReadRequest
import dev.rosty.core.model.stops.StopColour
import dev.rosty.design.theme.RostyTheme
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * E7.3 screenshots: every radar layer over the real fixture tile (the village of `E72ShotTest`), drawn through
 * [StopsMap]'s overlay from one [RadarState]. The circle's geometry is asked from the real Rust core.
 */
class E73ShotTest : ShotTest() {
    private val rust = MapHostRust()

    /** The test window, set before composing so the layers can be placed by pixel. */
    private val camera =
        MapCamera().apply {
            setViewport(WIDTH, HEIGHT, 3f)
            jumpTo(
                MapMath.fracToLat(14, MapFixtures.CITY_Y + 0.335),
                MapMath.fracToLon(14, MapFixtures.CITY_X + 0.845),
                15.4,
            )
        }

    private fun at(
        x: Float,
        y: Float,
    ) = Coord(camera.latAt(y), camera.lonAt(x))

    private fun points(vararg at: Pair<Float, Float>) =
        DoubleArray(at.size * 2) {
            if (it % 2 ==
                0
            ) {
                camera.latAt(at[it / 2].second)
            } else {
                camera.lonAt(at[it / 2].first)
            }
        }

    private fun crossing(
        a: Coord,
        b: Coord,
    ): RadarCrossing =
        runBlocking {
            val valid = (rust.ops.validate(a, b) as Outcome.Ok).value
            val request = CrossingReadRequest(a, b, at = a, outlinePoints = RadarCrossing.OUTLINE_POINTS)
            RadarCrossing.of(a, b, valid, (rust.ops.read(request) as Outcome.Ok).value)
        }

    @Composable
    private fun Screen(state: RadarState) {
        val scope = rememberCoroutineScope()
        val source = remember { FakeTileSource.city() }
        val tiles = remember { MapTileStore(source, scope, rasterDispatcher = Dispatchers.Unconfined) }
        DisposableEffect(tiles) { onDispose { tiles.close() } }
        val holder = remember(state) { RadarHolder().apply { set(state) } }
        val dark = RostyTheme.colors.isDark
        Box(Modifier.fillMaxSize()) {
            StopsMap(camera, tiles, dark, Modifier.fillMaxSize(), overlay = rememberRadarOverlay(holder, dark))
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars,
                    ).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                MapScaleBar(camera, Modifier.align(Alignment.BottomStart))
                MapAttribution(Modifier.align(Alignment.BottomEnd))
            }
        }
    }

    @Test fun abCircle() {
        val a = at(330f, 1500f)
        val b = at(900f, 1100f)
        val state =
            RadarState(
                pinA = RadarPin(a.lat, a.lon, caption = "Point A"),
                pinB = RadarPin(b.lat, b.lon, snapped = true, caption = "Orman Yolu"),
                crossing = crossing(a, b),
            )
        shot("e7.3-ab-circle") { Screen(state) }
    }

    @Test fun positionAndAccuracy() {
        val here = at(520f, 1250f)
        shot("e7.3-position-accuracy") { Screen(RadarState(position = RadarPosition(here.lat, here.lon, 60.0))) }
    }

    @Test fun breadcrumb() {
        // A walk down the track on the left of the tile to where the user is now.
        val trail =
            points(
                160f to 300f,
                150f to 420f,
                190f to 560f,
                230f to 700f,
                215f to 830f,
                150f to 980f,
                120f to 1100f,
                90f to 1200f,
                180f to 1320f,
                300f to 1420f,
                420f to 1500f,
                560f to 1560f,
            )
        val here = at(560f, 1560f)
        shot(
            "e7.3-breadcrumb",
        ) { Screen(RadarState(trail = trail, position = RadarPosition(here.lat, here.lon, 15.0))) }
    }

    @Test fun gtfsStopsAndLine() {
        val line = points(60f to 2000f, 280f to 2040f, 560f to 2075f, 850f to 2050f, 1180f to 2010f)
        val others = points(980f to 320f, 1010f to 620f, 420f to 900f, 700f to 1400f)
        val stops =
            RadarStops(
                line + others,
                List(9) { "S$it" },
                listOf(
                    "Orman Girişi",
                    "Dere",
                    "Köy Yolu",
                    "Çeşme",
                    "Tepe",
                    "Köy Meydanı",
                    "Okul",
                    "Değirmen",
                    "Kavşak",
                ),
            )
        val a = at(280f, 2040f)
        val state =
            RadarState(stops = stops, line = line, pinA = RadarPin(a.lat, a.lon, snapped = true, caption = "Dere"))
        shot("e7.3-gtfs-stops-line") { Screen(state) }
    }

    @Test fun savedStopsMarkers() {
        val saved =
            RadarSaved(
                points(300f to 500f, 980f to 420f, 640f to 1200f, 250f to 1900f, 950f to 2100f),
                listOf(StopColour.Orange, StopColour.Red, StopColour.Green, StopColour.Blue, StopColour.Orange),
                longArrayOf(1, 2, 3, 4, 5),
            )
        shot("e7.3-saved-stops-markers") { Screen(RadarState(saved = saved)) }
    }

    private companion object {
        const val WIDTH = 1233
        const val HEIGHT = 2673
    }
}
