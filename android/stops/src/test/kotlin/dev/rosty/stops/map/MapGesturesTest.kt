package dev.rosty.stops.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * E7.2a: pan, pinch, fling, double tap, and the seam the picking story (E7.4, AD-ST9) fills: single taps as
 * coordinates, a drag claimed at once near a pin, cancelled into a pinch by a second finger. The manual clock is the
 * only source of time (`mainClock.autoAdvance = false`, set by [ShotTest]).
 */
class MapGesturesTest : ShotTest() {
    private val camera = MapCamera(41.0082, 28.9784, 4.0)
    private val policy = FakePolicy()

    /** The trivial policy of the brief: records taps, and claims drags that start near [pin]. */
    private class FakePolicy : MapGesturePolicy {
        var pin: Offset? = null
        var grabPx = 0f
        val taps = ArrayList<List<Double>>()
        var userMoves = 0
        var claim: Claim? = null
        val claims = ArrayList<Claim>()

        override fun onTap(
            x: Float,
            y: Float,
            lat: Double,
            lon: Double,
        ) {
            taps += listOf(x.toDouble(), y.toDouble(), lat, lon)
        }

        override fun claimDrag(
            x: Float,
            y: Float,
        ): MapDragClaim? {
            val at = pin ?: return null
            if ((Offset(x, y) - at).getDistance() > grabPx) return null
            return Claim().also {
                claim = it
                claims += it
            }
        }

        override fun onUserMove() {
            userMoves++
        }
    }

    private class Claim : MapDragClaim {
        val drags = ArrayList<Offset>()
        var drop: Offset? = null
        var dropVelocity = Offset.Zero
        var tapped = false
        var cancelled = false

        override fun onDrag(
            x: Float,
            y: Float,
        ) {
            drags += Offset(x, y)
        }

        override fun onDrop(
            x: Float,
            y: Float,
            velocityX: Float,
            velocityY: Float,
        ) {
            drop = Offset(x, y)
            dropVelocity = Offset(velocityX, velocityY)
        }

        override fun onTap() {
            tapped = true
        }

        override fun onCancel() {
            cancelled = true
        }

        val ended: Int get() = listOf(drop != null, tapped, cancelled).count { it }
    }

    private fun show(enabled: Boolean = true) {
        composeFrame {
            val scope = rememberCoroutineScope()
            val store =
                remember { MapTileStore(FakeTileSource.empty(), scope, rasterDispatcher = Dispatchers.Unconfined) }
            StopsMap(
                camera = camera,
                tiles = store,
                dark = false,
                modifier = Modifier.fillMaxSize().testTag(TAG),
                gesturePolicy = policy,
                gesturesEnabled = enabled,
            )
        }
        assertTrue("the map laid its size into the camera", camera.widthPx > 1000 && camera.heightPx > 2000)
        assertEquals(3f, camera.density, 0f)
    }

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag(TAG).performTouchInput(block)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun frames(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private var slop = 0f

    /** Puts a finger down and moves it just beyond the slop; the pan is anchored where it ends. */
    private fun TouchInjectionScope.startDrag(at: Offset): Offset {
        slop = viewConfiguration.touchSlop
        down(at)
        advanceEventTime(16)
        val anchored = at + Offset(slop + 2f, 0f)
        moveTo(anchored)
        advanceEventTime(16)
        return anchored
    }

    // ---- pan and fling ----

    @Test fun aDragMovesTheMapWithTheFinger() {
        show()
        var anchored = Offset.Zero
        touch { anchored = startDrag(START) }
        val lat = camera.latAt(anchored.y)
        val lon = camera.lonAt(anchored.x)
        assertTrue(camera.moving.value)
        assertEquals(1, policy.userMoves)
        touch {
            moveBy(Offset(100f, 60f))
            advanceEventTime(16)
            moveBy(Offset(-30f, 45f))
        }
        // What was under the finger is still under it.
        assertEquals(anchored.x + 70f, camera.screenX(lon), 0.6f)
        assertEquals(anchored.y + 105f, camera.screenY(lat), 0.6f)
        // A pause before lifting: no fling, the map rests at once.
        touch {
            advanceEventTime(600)
            up()
        }
        assertFalse(camera.animating)
        assertFalse(camera.moving.value)
        assertTrue("a pan is not a tap", policy.taps.isEmpty())
        frames(400)
        assertTrue(policy.taps.isEmpty())
    }

    @Test fun movingLessThanTheSlopDoesNotMoveTheMap() {
        show()
        val generation = camera.generation
        touch {
            down(START)
            advanceEventTime(16)
            moveBy(Offset(viewConfiguration.touchSlop / 3, 0f))
            advanceEventTime(16)
            up()
        }
        assertEquals(generation, camera.generation)
        assertFalse(camera.moving.value)
    }

    @Test fun aFlickCoastsAndComesToRest() {
        show()
        val lon0 = camera.centreLon
        touch {
            startDrag(START)
            repeat(8) {
                moveBy(Offset(45f, 0f))
                advanceEventTime(8)
            }
            up()
        }
        val lonAtRelease = camera.centreLon
        assertTrue("dragging right moves the centre west", lonAtRelease < lon0)
        assertTrue(camera.animating)
        assertTrue(camera.moving.value)
        frames(300)
        assertTrue("the map coasts on", camera.centreLon < lonAtRelease)
        frames(6_000)
        assertFalse(camera.animating)
        assertFalse(camera.moving.value)
        val rest = camera.centreLon
        frames(500)
        assertEquals(rest, camera.centreLon, 0.0)
    }

    @Test fun aTouchStopsTheFlingAndIsNotATap() {
        show()
        touch {
            startDrag(START)
            repeat(8) {
                moveBy(Offset(45f, 0f))
                advanceEventTime(8)
            }
            up()
        }
        frames(100)
        assertTrue(camera.animating)
        touch { down(START) }
        assertFalse(camera.animating)
        val held = camera.centreLon
        touch { up() }
        frames(600)
        assertEquals(held, camera.centreLon, 0.0)
        assertTrue(policy.taps.isEmpty())
        assertFalse(camera.moving.value)
    }

    // ---- pinch ----

    @Test fun twoFingersZoomAboutTheirMidpoint() {
        show()
        val mid = Offset(600f, 1300f)
        val lat = camera.latAt(mid.y)
        val lon = camera.lonAt(mid.x)
        val scale = camera.metresPerDp
        touch {
            down(0, Offset(400f, 1300f))
            advanceEventTime(16)
            down(1, Offset(800f, 1300f))
            advanceEventTime(16)
            // Spread from 400 px to 800 px apart in steps.
            for (i in 1..10) {
                updatePointerTo(0, Offset(400f - 20f * i, 1300f))
                updatePointerTo(1, Offset(800f + 20f * i, 1300f))
                move()
                advanceEventTime(16)
            }
        }
        assertEquals(scale / 2, camera.metresPerDp, scale * 0.005)
        assertEquals(mid.x, camera.screenX(lon), 1f)
        assertEquals(mid.y, camera.screenY(lat), 1f)
        assertTrue(camera.zooming)
        assertTrue(camera.moving.value)
        touch {
            up(0)
            up(1)
        }
        assertFalse(camera.zooming)
        assertFalse("lifting from a pinch does not fling", camera.animating)
        assertFalse(camera.moving.value)
        frames(400)
        assertTrue("a pinch is not a tap", policy.taps.isEmpty())
    }

    @Test fun twoFingersMovingTogetherPanWithoutZooming() {
        show()
        val zoom = camera.zoom
        val lon = camera.lonAt(600f)
        touch {
            down(0, Offset(400f, 1300f))
            down(1, Offset(800f, 1300f))
            advanceEventTime(16)
            for (i in 1..5) {
                updatePointerTo(0, Offset(400f + 30f * i, 1300f))
                updatePointerTo(1, Offset(800f + 30f * i, 1300f))
                move()
                advanceEventTime(16)
            }
            up(0)
            up(1)
        }
        assertEquals(zoom, camera.zoom, 1e-9)
        assertEquals(750f, camera.screenX(lon), 1f)
    }

    @Test fun aSecondFingerJoinsADragAndTheFingerLeftPansOn() {
        show()
        val zoom = camera.zoom
        var anchored = Offset.Zero
        touch {
            anchored = startDrag(Offset(400f, 1300f))
            moveBy(Offset(50f, 0f))
            advanceEventTime(16)
        }
        assertFalse(camera.zooming)
        val first = anchored + Offset(50f, 0f)
        val second = first + Offset(400f, 0f)
        touch {
            down(1, second)
            advanceEventTime(16)
            updatePointerTo(1, second + Offset(200f, 0f))
            move()
            advanceEventTime(16)
        }
        assertTrue(camera.zooming)
        assertEquals("the span went from 400 to 600", zoom + MapMath.log2(1.5), camera.zoom, 0.01)
        // The second finger lifts: the first one pans on, without a jump and without zooming.
        touch { up(1) }
        assertFalse(camera.zooming)
        assertTrue(camera.moving.value)
        val lon = camera.lonAt(first.x)
        val zoomed = camera.zoom
        touch { moveBy(0, Offset(-80f, 0f)) }
        assertEquals(first.x - 80f, camera.screenX(lon), 1f)
        assertEquals(zoomed, camera.zoom, 1e-9)
        touch {
            advanceEventTime(700)
            up(0)
        }
        assertFalse("animating=${camera.animating} zooming=${camera.zooming}", camera.moving.value)
    }

    @Test fun fingersCloserThanTheMinimumDoNotZoom() {
        show()
        val zoom = camera.zoom
        touch {
            down(0, Offset(600f, 1300f))
            down(1, Offset(610f, 1300f))
            advanceEventTime(16)
            updatePointerTo(1, Offset(625f, 1300f))
            move()
            up(0)
            up(1)
        }
        assertEquals(zoom, camera.zoom, 1e-9)
    }

    // ---- taps ----

    @Test fun aSingleTapReachesThePolicyAfterTheDoubleTapWindowAndNeverMovesTheView() {
        show()
        val generation = camera.generation
        val lat = camera.latAt(START.y)
        val lon = camera.lonAt(START.x)
        touch {
            down(START)
            advanceEventTime(40)
            up()
        }
        frames(MAP_DOUBLE_TAP_MS - 60)
        assertTrue("still waiting for a second tap", policy.taps.isEmpty())
        frames(100)
        assertEquals(1, policy.taps.size)
        val tap = policy.taps.single()
        assertEquals(START.x.toDouble(), tap[0], 0.01)
        assertEquals(START.y.toDouble(), tap[1], 0.01)
        assertEquals(lat, tap[2], 1e-9)
        assertEquals(lon, tap[3], 1e-9)
        assertEquals("a map tap never moves the view", generation, camera.generation)
        assertFalse(camera.moving.value)
        assertEquals(0, policy.userMoves)
    }

    @Test fun aDoubleTapZoomsInAboutTheTapAndIsNoSingleTap() {
        show()
        val zoom = camera.zoom
        val second = START + Offset(6f, -4f)
        val lat = camera.latAt(second.y)
        val lon = camera.lonAt(second.x)
        touch {
            down(START)
            advanceEventTime(30)
            up()
            advanceEventTime(120)
            down(second)
            advanceEventTime(30)
            up()
        }
        assertTrue(camera.animating)
        assertTrue(camera.zooming)
        frames(600)
        assertFalse(camera.animating)
        assertEquals(zoom + 1.0, camera.zoom, 1e-6)
        // What was under the second tap is still under it.
        assertEquals(second.x, camera.screenX(lon), 0.6f)
        assertEquals(second.y, camera.screenY(lat), 0.6f)
        assertTrue(policy.taps.isEmpty())
        assertFalse(camera.moving.value)
    }

    @Test fun twoTapsFarApartAreTwoSingleTaps() {
        show()
        touch {
            down(START)
            up()
            advanceEventTime(100)
            down(START + Offset(400f, 400f))
            up()
        }
        // The first tap acts when the second one shows it was no double tap.
        assertEquals(1, policy.taps.size)
        frames(400)
        assertEquals(2, policy.taps.size)
        assertEquals(START.x.toDouble(), policy.taps[0][0], 0.01)
        assertEquals((START.x + 400f).toDouble(), policy.taps[1][0], 0.01)
        assertFalse(camera.zooming)
    }

    @Test fun twoTapsTooFarApartInTimeAreTwoSingleTaps() {
        show()
        touch {
            down(START)
            up()
        }
        frames(MAP_DOUBLE_TAP_MS + 100)
        touch {
            down(START)
            up()
        }
        frames(MAP_DOUBLE_TAP_MS + 100)
        assertEquals(2, policy.taps.size)
        assertFalse(camera.zooming)
    }

    @Test fun aTapFollowedByADragActsOnWhatItTouched() {
        show()
        val lat = camera.latAt(START.y)
        touch {
            down(START)
            up()
            advanceEventTime(80)
            startDrag(START)
            moveBy(Offset(0f, 300f))
        }
        // The map moved, but the tap carries the coordinate it touched.
        assertEquals(1, policy.taps.size)
        assertEquals(lat, policy.taps.single()[2], 1e-9)
        assertTrue(abs(camera.latAt(START.y) - lat) > 1e-4)
        touch {
            advanceEventTime(600)
            up()
        }
    }

    // ---- the picking seam ----

    private fun pinAt(at: Offset) {
        policy.pin = at
        policy.grabPx = MapGesturePolicy.PIN_GRAB_DP * 3f
    }

    @Test fun aDragThatStartsNearAPinIsClaimedAtOnceAndTheMapStaysStill() {
        show()
        pinAt(Offset(600f, 1300f))
        val generation = camera.generation
        val start = Offset(650f, 1340f)
        touch {
            down(start)
            advanceEventTime(16)
        }
        // Claimed on touch down: no long press.
        assertNotNull(policy.claim)
        val claim = policy.claim!!
        var moved = Offset.Zero
        touch {
            moved = start + Offset(viewConfiguration.touchSlop + 5f, 0f)
            moveTo(moved)
            advanceEventTime(16)
            moveBy(Offset(120f, -200f))
            advanceEventTime(16)
        }
        assertEquals(listOf(moved, moved + Offset(120f, -200f)), claim.drags)
        assertEquals("the map does not pan under a grabbed pin", generation, camera.generation)
        assertFalse(camera.moving.value)
        touch {
            moveBy(Offset(60f, 0f))
            advanceEventTime(8)
            moveBy(Offset(60f, 0f))
            advanceEventTime(8)
            up()
        }
        assertEquals(moved + Offset(240f, -200f), claim.drop)
        assertTrue("a flick has its velocity: ${claim.dropVelocity}", claim.dropVelocity.x > 500f)
        assertEquals(1, claim.ended)
        assertEquals(generation, camera.generation)
        assertFalse("a dropped pin does not fling the map", camera.animating)
        assertEquals(0, policy.userMoves)
        frames(400)
        assertTrue(policy.taps.isEmpty())
    }

    @Test fun aTapOnAPinIsThePinsTapNotAMapTap() {
        show()
        pinAt(Offset(600f, 1300f))
        touch {
            down(Offset(610f, 1290f))
            advanceEventTime(40)
            up()
        }
        val claim = policy.claim!!
        assertTrue(claim.tapped)
        assertEquals(1, claim.ended)
        assertTrue(claim.drags.isEmpty())
        frames(400)
        assertTrue(policy.taps.isEmpty())
    }

    @Test fun aSecondFingerCancelsTheGrabIntoAPinch() {
        show()
        pinAt(Offset(600f, 1300f))
        val zoom = camera.zoom
        touch {
            down(0, Offset(600f, 1300f))
            advanceEventTime(16)
            moveTo(0, Offset(600f + viewConfiguration.touchSlop + 20f, 1300f))
            advanceEventTime(16)
        }
        val claim = policy.claim!!
        assertEquals(1, claim.drags.size)
        assertFalse(claim.cancelled)
        touch {
            down(1, Offset(1000f, 1300f))
            advanceEventTime(16)
        }
        assertTrue("the pin goes back", claim.cancelled)
        assertEquals(1, claim.ended)
        touch {
            updatePointerTo(1, Offset(1150f, 1300f))
            move()
            advanceEventTime(16)
        }
        assertTrue("and the two fingers pinch", camera.zoom > zoom + 0.1)
        assertTrue(camera.zooming)
        val drags = claim.drags.size
        touch {
            up(1)
            advanceEventTime(16)
            moveBy(0, Offset(40f, 0f))
            advanceEventTime(600)
            up(0)
        }
        // The claim is over: later moves pan the map and nothing more reaches it.
        assertEquals(drags, claim.drags.size)
        assertNull(claim.drop)
        assertEquals(1, claim.ended)
        assertEquals(1, policy.claims.size)
    }

    @Test fun aDragAwayFromThePinPansTheMap() {
        show()
        pinAt(Offset(600f, 1300f))
        val generation = camera.generation
        touch {
            startDrag(Offset(600f, 1300f + MapGesturePolicy.PIN_GRAB_DP * 3f + 30f))
            moveBy(Offset(0f, 100f))
            advanceEventTime(600)
            up()
        }
        assertNull(policy.claim)
        assertTrue(camera.generation > generation)
    }

    @Test fun aSecondFingerOnAnUnmovedGrabAlsoCancelsIt() {
        show()
        pinAt(Offset(600f, 1300f))
        touch {
            down(0, Offset(600f, 1300f))
            down(1, Offset(900f, 1300f))
            up(1)
            up(0)
        }
        val claim = policy.claim!!
        assertTrue(claim.cancelled)
        assertFalse(claim.tapped)
        assertEquals(1, claim.ended)
    }

    // ---- off ----

    @Test fun aMapWithGesturesOffIsAStillPicture() {
        show(enabled = false)
        val generation = camera.generation
        touch {
            down(START)
            moveBy(Offset(300f, 300f))
            up()
        }
        frames(500)
        assertEquals(generation, camera.generation)
        assertTrue(policy.taps.isEmpty())
    }

    private companion object {
        const val TAG = "map"
        val START = Offset(600f, 1300f)
    }
}
