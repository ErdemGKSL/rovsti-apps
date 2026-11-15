package dev.rosty.stops.home

import android.Manifest
import androidx.compose.runtime.rememberCoroutineScope
import dev.rosty.design.motion.MotionGate
import dev.rosty.stops.SampleStops
import dev.rosty.stops.TestStopsContainer
import dev.rosty.stops.map.FakeTileSource
import dev.rosty.stops.map.MapCamera
import dev.rosty.stops.map.MapTileStore
import dev.rosty.stops.map.PickController
import dev.rosty.stops.map.StopsPickOps
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The blue dot follows the live position only with the location permission (GPUI: foreground only). */
@Config(sdk = [35])
class LivePositionTest : ShotTest() {
    private val app = RuntimeEnvironment.getApplication()
    private val container = TestStopsContainer(app)
    private var map: HomeMap? = null

    private fun start(granted: Boolean) {
        val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (granted) shadowOf(app).grantPermissions(*permissions) else shadowOf(app).denyPermissions(*permissions)
        container.permissions.refreshNow { false }
        composeFrame {
            val scope = rememberCoroutineScope()
            val built =
                map ?: run {
                    val camera = MapCamera()
                    HomeMap(
                        camera,
                        MapTileStore(FakeTileSource.empty(), scope),
                        PickController(StopsPickOps(container.ops), scope, camera),
                        MotionGate(scope),
                    ).also { map = it }
                }
            LivePosition(built, container)
        }
        advance(500)
    }

    @Test fun theLastFixShowsWithThePermission() {
        start(granted = true)
        val position =
            map!!
                .pick.radar.state.position!!
        assertEquals(SampleStops.here.lat, position.lat, 0.0)
        assertEquals(25.0, position.accuracyM, 0.0)
    }

    @Test fun nothingShowsWithoutIt() {
        start(granted = false)
        assertNull(
            map!!
                .pick.radar.state.position,
        )
    }
}
