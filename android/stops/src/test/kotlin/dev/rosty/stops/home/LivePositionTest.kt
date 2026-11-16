package dev.rosty.stops.home

import android.Manifest
import androidx.compose.runtime.rememberCoroutineScope
import dev.rosty.design.motion.MotionGate
import dev.rosty.platform.location.Fix
import dev.rosty.platform.location.TripStatus
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
import java.io.File

/** The blue dot follows the live position only with the location permission (GPUI: foreground only). */
@Config(sdk = [35])
class LivePositionTest : ShotTest() {
    private val app = RuntimeEnvironment.getApplication()
    private val container = TestStopsContainer(app)
    private var map: HomeMap? = null

    private fun start(
        granted: Boolean,
        tripRunning: Boolean = false,
        following: Boolean = false,
    ) {
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
            LivePosition(built, container, tripRunning, following)
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

    @Test fun duringATripTheDotFollowsTheTripsFixesNotItsOwnRequest() {
        start(granted = true, tripRunning = true)
        // The map's own updates are not collected while a trip runs: a live fix does not move the dot.
        container.fakeLocation.push(Fix(1.0, 2.0, 5.0, 1L))
        advance(100)
        assertEquals(
            SampleStops.here.lat,
            map!!
                .pick.radar.state.position!!
                .lat,
            0.0,
        )
        // The trip's fix does.
        val answer = File(System.getProperty("rosty.ffi.fixtures"), "headless.trip_fix.answer.json").readText()
        container.fakeTrips.push(TripStatus.parse(answer))
        advance(100)
        val position =
            map!!
                .pick.radar.state.position!!
        assertEquals(41.0, position.lat, 0.0)
        assertEquals(28.9, position.lon, 0.0)
        assertEquals(10.0, position.accuracyM, 0.0)
    }

    @Test fun followingDuringATripAsksForItsOwnUpdates() {
        start(granted = true, tripRunning = true, following = true)
        container.fakeLocation.push(Fix(1.0, 2.0, 5.0, 1L))
        advance(100)
        assertEquals(
            1.0,
            map!!
                .pick.radar.state.position!!
                .lat,
            0.0,
        )
    }

    @Test fun nothingShowsWithoutIt() {
        start(granted = false)
        assertNull(
            map!!
                .pick.radar.state.position,
        )
    }
}
