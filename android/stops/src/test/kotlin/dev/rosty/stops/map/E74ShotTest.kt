package dev.rosty.stops.map

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Slot
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * E7.4 screenshots: picking over the fixture village, each state reached through the [PickController] and the real
 * Rust pick machine before the frame is composed. The hint pill at the top stands in for the shell's (it shows the
 * line Rust wrote); the menus are the real ones in the overlay host.
 */
class E74ShotTest : ShotTest() {
    private val rust = MapHostRust()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val camera =
        MapCamera().apply {
            setViewport(WIDTH, HEIGHT, 3f)
            jumpTo(
                MapMath.fracToLat(14, MapFixtures.CITY_Y + 0.335),
                MapMath.fracToLon(14, MapFixtures.CITY_X + 0.845),
                15.4,
            )
        }
    private val pick = PickController(rust.ops, scope, camera)
    private val overlays = OverlayController()
    private var strings: PinMenuStrings? = null

    private val actions =
        object : PinMenuActions {
            override fun copy(
                label: String,
                text: String,
            ) = Unit

            override fun showQr(
                title: String,
                geoUri: String,
            ) = Unit

            override fun openInMaps(geoUri: String) = Unit

            override fun measureFrom(from: Coord) = Unit

            override fun saveAsStop() = Unit

            override fun showLines(stopId: String) = Unit

            override fun move(slot: Slot) = pick.beginMove(slot)

            override fun swap() = pick.swap()

            override fun remove(slot: Slot) = pick.remove(slot)
        }

    @After fun stop() = scope.cancel()

    private fun tap(
        x: Float,
        y: Float,
    ) {
        pick.onTap(x, y, camera.latAt(y), camera.lonAt(x))
        MapHostRust.settle(pick)
    }

    @Composable
    private fun Screen() {
        val tileScope = rememberCoroutineScope()
        val source = remember { FakeTileSource.city() }
        val tiles = remember { MapTileStore(source, tileScope, rasterDispatcher = Dispatchers.Unconfined) }
        DisposableEffect(tiles) { onDispose { tiles.close() } }
        val dark = RostyTheme.colors.isDark
        val state by pick.state.collectAsState()
        strings = rememberPinMenuStrings()
        OverlayHost(overlays) {
            Box(Modifier.fillMaxSize()) {
                StopsMap(
                    camera,
                    tiles,
                    dark,
                    Modifier.fillMaxSize().pickAnchorOrigin(pick),
                    gesturePolicy = pick,
                    overlay = rememberRadarOverlay(pick.radar, dark),
                )
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(12.dp)) {
                    RostyText(
                        state.hintText,
                        Modifier
                            .align(Alignment.TopCenter)
                            .background(RostyTheme.colors.groupedSurface, RoundedCornerShape(50))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        style = RostyTheme.type.subheadline,
                    )
                }
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

    /** Runs [after] on each theme's frame, then captures it as `name-light` and `name-dark`. */
    private fun shots(
        name: String,
        after: () -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            compose.runOnUiThread { overlays.dismissAll() }
            composeFrame(dark = dark) { Screen() }
            after()
            repeat(4) {
                advance(200)
                shadowOf(Looper.getMainLooper()).idle()
            }
            capture("$name-${if (dark) "dark" else "light"}")
        }
    }

    @Test fun pickA() {
        tap(A_X, A_Y)
        shots("e7.4-pick-a")
    }

    @Test fun pickAb() {
        tap(A_X, A_Y)
        tap(B_X, B_Y)
        shots("e7.4-pick-ab")
    }

    @Test fun pinDragging() {
        tap(A_X, A_Y)
        tap(B_X, B_Y)
        // B is in the finger, 300 px from where it was dropped: larger, no caption change, the circle follows.
        val claim = pick.claimDrag(B_X, B_Y)!!
        claim.onDrag(B_X + 60f, B_Y + 150f, 16)
        claim.onDrag(B_X + 120f, B_Y + 300f, 32)
        MapHostRust.settle(pick)
        shots("e7.4-pin-dragging")
    }

    @Test fun pinMenuOpen() {
        tap(A_X, A_Y)
        tap(B_X, B_Y)
        val here = Coord(camera.latAt(2300f), camera.lonAt(300f))
        pick.radar.update { it.copy(position = RadarPosition(here.lat, here.lon, 20.0)) }
        pick.savedStops =
            dev.rosty.core.model.stops
                .SavedStops(2, 1, emptyList())
        val model = runBlocking { pick.pinMenuModel(Slot.B) }!!
        shots("e7.4-pin-menu-open") {
            compose.runOnUiThread { overlays.presentPinMenu(pick.pinAnchor(Slot.B)!!, model, strings!!, actions) }
        }
    }

    @Test fun copyAsMenu() {
        tap(A_X, A_Y)
        tap(B_X, B_Y)
        val model = runBlocking { pick.pinMenuModel(Slot.B) }!!
        shots("e7.4-copy-as-menu") {
            compose.runOnUiThread { overlays.presentCopyAsMenu(pick.pinAnchor(Slot.B)!!, model, strings!!, actions) }
        }
    }

    @Test fun thirdTapHint() {
        tap(A_X, A_Y)
        tap(B_X, B_Y)
        shots("e7.4-third-tap-hint") {
            compose.runOnUiThread { overlays.showThirdTapHint(strings!!) }
        }
    }

    private companion object {
        const val WIDTH = 1233
        const val HEIGHT = 2673
        const val A_X = 330f
        const val A_Y = 1500f
        const val B_X = 900f
        const val B_Y = 1100f
    }
}
