package dev.rosty.stops.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.rosty.core.model.stops.Slot
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E7.4 AC1, with real fingers on the real map and the real Rust pick machine: a tap drops A then B; a drag near a pin
 * grabs it at once (no long press); a second finger cancels the grab into a pinch; a flick snaps to a transit stop; a
 * map tap never moves the view; the labels are Rust's. Manual clock ([ShotTest]).
 */
class PickGesturesTest : ShotTest() {
    private val rust = MapHostRust()
    private val snaps = ArrayList<String>()
    private val ops =
        object : PickOps by rust.ops {
            override suspend fun snap(request: dev.rosty.core.model.stops.SnapRequest) =
                rust.ops.snap(request).also { snaps += "$request -> $it" }
        }
    private val camera = MapCamera(41.0, 29.0, 4.0)
    private val overlays = OverlayController()
    private var pick: PickController? = null
    private var strings: PinMenuStrings? = null
    private val heard = ArrayList<String>()

    private val listener =
        object : PickListener {
            override fun onPinTap(slot: Slot) {
                heard += "pin $slot"
            }

            override fun onThirdTap() {
                heard += "third"
                overlays.showThirdTapHint(strings!!)
            }

            override fun onSnapped() {
                heard += "snapped"
            }
        }

    private fun show() {
        composeFrame {
            val scope = rememberCoroutineScope()
            val store =
                remember { MapTileStore(FakeTileSource.empty(), scope, rasterDispatcher = Dispatchers.Unconfined) }
            val controller = remember { PickController(ops, scope, camera, listener).also { pick = it } }
            strings = rememberPinMenuStrings()
            OverlayHost(overlays) {
                StopsMap(
                    camera = camera,
                    tiles = store,
                    dark = false,
                    modifier = Modifier.fillMaxSize().testTag(TAG).pickAnchorOrigin(controller),
                    gesturePolicy = controller,
                    overlay = rememberRadarOverlay(controller.radar, dark = false),
                )
            }
        }
        assertEquals(3f, camera.density, 0f)
    }

    private val controller get() = pick!!
    private val state get() = controller.state.value

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag(TAG).performTouchInput(block)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Lets the double-tap window pass and waits for Rust to answer. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(MAP_DOUBLE_TAP_MS + 50)
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            controller.idle
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun tap(at: Offset) {
        touch {
            down(at)
            advanceEventTime(40)
            up()
        }
        settle()
    }

    @Test fun tapsDropAThenBAndTheViewNeverMoves() {
        show()
        val generation = camera.generation
        tap(A)
        assertNotNull(state.ends.a)
        assertNull(state.ends.b)
        assertEquals("Point A", state.labelA)
        assertEquals(
            A.x,
            camera.screenX(
                state.ends.a!!
                    .coord.lon,
            ),
            0.6f,
        )
        assertEquals(
            A.y,
            camera.screenY(
                state.ends.a!!
                    .coord.lat,
            ),
            0.6f,
        )

        // 637.5 px east on a 3x screen at 4 m per dp: 850 m.
        tap(B)
        assertNotNull(state.ends.b)
        assertEquals("A-B 850 m", state.pairLabel)
        assertNotNull("the circle is there at once", controller.radar.state.crossing)
        assertEquals("a map tap never moves the view", generation, camera.generation)
        assertFalse(camera.moving.value)
        assertTrue(heard.isEmpty())
    }

    @Test fun aThirdTapShowsTheHintAndPlacesNothing() {
        show()
        tap(A)
        tap(B)
        val ends = state.ends
        tap(Offset(600f, 2000f))
        assertEquals(listOf("third"), heard)
        assertEquals(ends, state.ends)
        advance(400)
        compose.onAllNodesWithText("Drag a pin to adjust it, or tap Clear.").fetchSemanticsNodes().let {
            assertTrue("the hint is on screen", it.isNotEmpty())
        }
    }

    @Test fun aDragNearAPinGrabsItAtOnceAndThePinFollowsTheFinger() {
        show()
        tap(A)
        tap(B)
        val generation = camera.generation
        // 60 px (20 dp) from B: inside the 32 dp of PIN_GRAB_PX. No waiting before the move.
        val start = B + Offset(60f, 0f)
        touch {
            down(start)
            advanceEventTime(8)
            moveBy(Offset(viewConfiguration.touchSlop + 10f, 0f))
            advanceEventTime(8)
            moveBy(Offset(100f, 240f))
        }
        assertEquals(Slot.B, controller.radar.dragSlot)
        // The pin keeps its offset to the finger: it moved by what the finger moved.
        val moved =
            compose
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .layoutInfo.viewConfiguration.touchSlop + 10f + 100f
        assertEquals(B.x + moved, camera.screenX(controller.radar.dragLon), 0.6f)
        assertEquals(B.y + 240f, camera.screenY(controller.radar.dragLat), 0.6f)
        assertEquals("the map stays still under a grabbed pin", generation, camera.generation)
        assertFalse(camera.moving.value)
        touch {
            advanceEventTime(700)
            up()
        }
        settle()
        assertNull(controller.radar.dragSlot)
        assertEquals(
            B.x + moved,
            camera.screenX(
                state.ends.b!!
                    .coord.lon,
            ),
            0.6f,
        )
        assertEquals(
            B.y + 240f,
            camera.screenY(
                state.ends.b!!
                    .coord.lat,
            ),
            0.6f,
        )
        assertEquals(generation, camera.generation)
        assertFalse(camera.animating)
    }

    @Test fun aSecondFingerCancelsTheGrabIntoAPinch() {
        show()
        tap(A)
        tap(B)
        val b = state.ends.b!!.coord
        val zoom = camera.zoom
        touch {
            down(0, B)
            advanceEventTime(8)
            moveBy(0, Offset(viewConfiguration.touchSlop + 30f, 60f))
            advanceEventTime(8)
        }
        assertEquals(Slot.B, controller.radar.dragSlot)
        touch {
            down(1, B + Offset(-300f, 500f))
            advanceEventTime(16)
        }
        assertNull("the pin went back", controller.radar.dragSlot)
        touch {
            updatePointerTo(1, B + Offset(-450f, 800f))
            move()
            advanceEventTime(16)
        }
        assertTrue("and the fingers pinch", camera.zoom > zoom + 0.1)
        touch {
            up(1)
            advanceEventTime(700)
            up(0)
        }
        settle()
        assertEquals(b, state.ends.b!!.coord)
        assertNull(controller.radar.dragSlot)
    }

    @Test fun aFlickedPinSnapsToTheTransitStopItFliesAt() {
        show()
        val stop = Offset(700f, 2000f)
        controller.radar.update {
            it.copy(
                stops =
                    RadarStops(
                        doubleArrayOf(camera.latAt(stop.y), camera.lonAt(stop.x)),
                        listOf("K1"),
                        listOf("Kadıköy"),
                    ),
            )
        }
        tap(A)
        tap(B)
        // B is thrown down and left at well over 900 dp/s (the injected moves are 26 ms apart) and released 30 dp
        // short of the stop.
        touch {
            down(B)
            advanceEventTime(10)
            val end = stop + Offset(40f, -80f)
            val steps = 6
            for (i in 1..steps) {
                moveTo(B + (end - B) * (i.toFloat() / steps))
                advanceEventTime(10)
            }
            up()
        }
        settle()
        assertEquals(snaps.toString(), listOf("snapped"), heard)
        assertEquals("K1", state.ends.b!!.gtfsStop)
        assertEquals("Kadıköy", state.labelB)
        assertEquals(
            stop.x,
            camera.screenX(
                state.ends.b!!
                    .coord.lon,
            ),
            0.6f,
        )
        assertTrue(
            controller.radar.state.pinB!!
                .snapped,
        )
    }

    @Test fun aTapOnAPinOpensItsMenuAndIsNoMapTap() {
        show()
        tap(A)
        tap(B)
        val ends = state.ends
        touch {
            down(A + Offset(20f, -15f))
            advanceEventTime(40)
            up()
        }
        settle()
        assertEquals(listOf("pin A"), heard)
        assertEquals(ends, state.ends)
        // The anchor of the menu is the pin, in window pixels.
        val anchor = controller.pinAnchor(Slot.A)!!
        assertEquals(A.x, anchor.center.x, 1f)
        assertEquals(A.y, anchor.center.y, 1f)
    }

    @Test fun moveThenATapMovesThatEnd() {
        show()
        tap(A)
        tap(B)
        compose.runOnUiThread { controller.beginMove(Slot.A) }
        settle()
        assertEquals(Slot.A, state.moving)
        tap(Offset(300f, 2100f))
        assertNull(state.moving)
        assertEquals(
            2100f,
            camera.screenY(
                state.ends.a!!
                    .coord.lat,
            ),
            0.6f,
        )
        assertTrue(heard.isEmpty())
    }

    private companion object {
        const val TAG = "map"
        val A = Offset(200f, 1300f)
        val B = Offset(837.5f, 1300f)
    }
}
