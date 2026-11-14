package dev.rosty.stops.map

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.PickEvent
import dev.rosty.core.model.stops.SavedStop
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.Slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * E7.4: the picking glue against the real Rust state machine (`stops.pick_replay`, `pick_labels`, `snap`,
 * `crossing_*`, `coord_format`, `pick_save_offer`), driven through the same [MapGesturePolicy] calls the map makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickControllerTest {
    private val rust = MapHostRust()
    private val scope = CoroutineScope(Dispatchers.Main)

    // 4 m per dp on a 1x screen: 100 px are 400 m.
    private val camera = MapCamera(41.0, 29.0, 4.0).apply { setViewport(400, 800, 1f) }
    private val heard = ArrayList<String>()
    private val listener =
        object : PickListener {
            override fun onPinTap(slot: Slot) {
                heard += "pin $slot"
            }

            override fun onSavedStopTap(id: Long) {
                heard += "saved $id"
            }

            override fun onPositionTap() {
                heard += "position"
            }

            override fun onThirdTap() {
                heard += "third"
            }

            override fun onSnapped() {
                heard += "snapped"
            }

            override fun onError(error: RustError) {
                heard += "error"
            }
        }
    private val pick = PickController(rust.ops, scope, camera, listener)

    @After fun stop() = scope.cancel()

    private fun settle() = MapHostRust.settle(pick)

    private fun tap(
        x: Float,
        y: Float,
    ) {
        pick.onTap(x, y, camera.latAt(y), camera.lonAt(x))
        settle()
    }

    private val state get() = pick.state.value
    private val radar get() = pick.radar.state

    private fun stopsAt(vararg at: Pair<Float, Float>) =
        RadarStops(
            DoubleArray(at.size * 2) {
                if (it % 2 ==
                    0
                ) {
                    camera.latAt(at[it / 2].second)
                } else {
                    camera.lonAt(at[it / 2].first)
                }
            },
            at.indices.map { "S$it" },
            at.indices.map { "Stop $it" },
        )

    // ---- taps ----

    @Test fun theFirstTapDropsATheSecondBAndTheCircleIsThereAtOnce() {
        tap(100f, 400f)
        assertNotNull(state.ends.a)
        assertNull(state.ends.b)
        assertEquals("set_b", state.hintKind)
        assertEquals("Point A", state.labelA)
        assertEquals("Point B", state.labelB)
        assertNull(state.pairLabel)
        assertNotNull(radar.pinA)
        assertNull(radar.crossing)
        assertEquals(100f, camera.screenX(radar.pinA!!.lon), 0.01f)
        assertEquals("Point A", radar.pinA!!.caption)

        // 212.5 px east at 4 m per px: 850 m.
        tap(312.5f, 400f)
        assertNotNull(state.ends.b)
        assertEquals("ready", state.hintKind)
        assertEquals("A-B 850 m", state.pairLabel)
        assertEquals("A-B 850 m", state.hintText)
        assertTrue(state.canStart)
        val crossing = radar.crossing!!
        assertEquals(425.0, crossing.radiusM, 1.5)
        assertEquals(206.25f, camera.screenX(crossing.centreLon), 0.5f)
        assertEquals(RadarCrossing.OUTLINE_POINTS + 1, crossing.left.size / 2)
        // Left of A to B, heading east, is north: the top of the left half is above the line.
        val top = crossing.left.size / 4 * 2
        assertTrue(camera.screenY(crossing.left[top]) < 400f - 90f)
        assertTrue(camera.screenY(crossing.right[top]) > 400f + 90f)
    }

    @Test fun aThirdTapChangesNothingAndAsksForTheHint() {
        tap(100f, 400f)
        tap(300f, 400f)
        val before = state
        tap(200f, 600f)
        assertEquals(listOf("third"), heard)
        assertEquals(before.ends, state.ends)
        assertEquals(before.pairLabel, state.pairLabel)
    }

    @Test fun twoPointsTooCloseAreAPlainLineNotACircle() {
        tap(100f, 400f)
        // 2 px = 8 m, under the 20 m minimum.
        tap(102f, 400f)
        assertEquals("invalid", state.hintKind)
        assertFalse(state.canStart)
        assertNotNull(radar.pinB)
        assertNull("no circle: the painter joins the pins with a line", radar.crossing)
    }

    @Test fun aTapNearATransitStopLandsOnItAndAFarOneDoesNot() {
        pick.radar.update { it.copy(stops = stopsAt(200f to 300f, 350f to 700f)) }
        // 30 dp from the stop: within the 40 dp of `TAP_RADIUS_PX`.
        tap(230f, 300f)
        val a = state.ends.a!!
        assertEquals("S0", a.gtfsStop)
        assertEquals("Stop 0", a.label)
        assertEquals("Stop 0", state.labelA)
        assertTrue(radar.pinA!!.snapped)
        assertEquals(200f, camera.screenX(radar.pinA!!.lon), 0.01f)
        // 45 dp from the other stop: a plain point under the finger.
        tap(350f, 655f)
        val b = state.ends.b!!
        assertNull(b.gtfsStop)
        assertFalse(radar.pinB!!.snapped)
        assertEquals(655f, camera.screenY(radar.pinB!!.lat), 0.01f)
    }

    @Test fun theSnapRadiusIsInDpOnADenseScreen() {
        camera.setViewport(1200, 2400, 3f)
        pick.radar.update { it.copy(stops = stopsAt(600f to 900f)) }
        // 100 px are 33 dp: snaps. On a 1x reading of the same pixels it would not.
        tap(700f, 900f)
        assertEquals("S0", state.ends.a!!.gtfsStop)
    }

    @Test fun whatElseIsUnderTheTapWinsOverDroppingAPin() {
        pick.radar.update {
            it.copy(
                position = RadarPosition(camera.latAt(100f), camera.lonAt(100f), 10.0),
                saved =
                    RadarSaved(
                        doubleArrayOf(camera.latAt(700f), camera.lonAt(300f)),
                        listOf(dev.rosty.core.model.stops.StopColour.Red),
                        longArrayOf(42),
                    ),
            )
        }
        tap(110f, 95f)
        tap(305f, 690f)
        assertEquals(listOf("position", "saved 42"), heard)
        assertNull(state.ends.a)
        // With the markers hidden the same tap drops a pin.
        pick.radar.update { it.copy(showSaved = false) }
        tap(305f, 690f)
        assertNotNull(state.ends.a)
    }

    @Test fun whileDisabledTheMapOnlyMoves() {
        tap(100f, 400f)
        pick.enabled = false
        tap(300f, 400f)
        assertNull(state.ends.b)
        assertNull(pick.claimDrag(100f, 400f))
        pick.enabled = true
        assertNotNull(pick.claimDrag(100f, 400f))
    }

    // ---- actions ----

    @Test fun moveSwapRemoveAndClear() {
        tap(100f, 400f)
        tap(300f, 400f)
        val a = state.ends.a!!.coord
        val b = state.ends.b!!.coord

        pick.beginMove(Slot.A)
        settle()
        assertEquals(Slot.A, state.moving)
        assertEquals("moving", state.hintKind)
        // The next tap moves A instead of being a third tap.
        tap(100f, 600f)
        assertNull(state.moving)
        assertEquals(
            600f,
            camera.screenY(
                state.ends.a!!
                    .coord.lat,
            ),
            0.01f,
        )
        assertEquals(b, state.ends.b!!.coord)
        assertTrue(heard.isEmpty())
        assertTrue(
            abs(
                a.lat -
                    state.ends.a!!
                        .coord.lat,
            ) > 1e-4,
        )

        val moved = state.ends.a!!.coord
        pick.swap()
        settle()
        assertEquals(b, state.ends.a!!.coord)
        assertEquals(moved, state.ends.b!!.coord)
        assertNotNull(radar.crossing)

        pick.remove(Slot.B)
        settle()
        assertNull(state.ends.b)
        assertNull(radar.pinB)
        assertNull(radar.crossing)
        assertNull(state.pairLabel)

        pick.clear()
        settle()
        assertEquals(Ends(), state.ends)
        assertNull(radar.pinA)
        assertEquals("set_a", state.hintKind)
    }

    @Test fun cancelMoveLeavesTheEndWhereItWas() {
        tap(100f, 400f)
        pick.beginMove(Slot.A)
        settle()
        pick.cancelMove()
        settle()
        assertNull(state.moving)
        // The tap is B again.
        tap(300f, 400f)
        assertNotNull(state.ends.b)
    }

    @Test fun placeSetPairAndRestore() {
        val home = Destination("Home", Coord(41.0, 28.994), gtfsStop = "H1")
        val work = Destination("", Coord(41.0, 29.006))
        pick.setPair(home, work)
        settle()
        assertEquals("Home", state.labelA)
        assertEquals("A-B 1.0 km", state.pairLabel)
        assertTrue(radar.pinA!!.snapped)
        pick.place(Slot.B, Destination("Pier", Coord(41.002, 29.0)))
        settle()
        assertEquals("Pier", state.labelB)
        pick.restore(Ends(a = work))
        settle()
        assertEquals(work.coord, state.ends.a!!.coord)
        assertNull(state.ends.b)
    }

    // ---- dragging a pin ----

    @Test fun aPinNearTheFingerIsClaimedAndFollowsIt() {
        tap(100f, 400f)
        tap(300f, 400f)
        assertNull("too far from both pins", pick.claimDrag(200f, 500f))
        // 20 px off the centre of B: grabbed, and the pin keeps that offset instead of jumping under the finger.
        val claim = pick.claimDrag(320f, 400f)!!
        claim.onDrag(340f, 430f, 16)
        assertEquals(Slot.B, pick.radar.dragSlot)
        assertEquals(320f, camera.screenX(pick.radar.dragLon), 0.01f)
        assertEquals(430f, camera.screenY(pick.radar.dragLat), 0.01f)
        // The ends do not change until the pin is dropped.
        assertEquals(
            300f,
            camera.screenX(
                state.ends.b!!
                    .coord.lon,
            ),
            0.01f,
        )
        settle()
        // The circle follows the pin (a Rust call behind the finger).
        val live = radar.crossing!!
        assertEquals(430f, camera.screenY(live.bLat), 0.01f)

        // Dropped after a pause, away from any stop: it stays where it was let go.
        claim.onDrag(360f, 500f, 400)
        claim.onDrop(360f, 500f, 0f, 0f, 900)
        settle()
        assertNull(pick.radar.dragSlot)
        assertEquals(
            340f,
            camera.screenX(
                state.ends.b!!
                    .coord.lon,
            ),
            0.01f,
        )
        assertEquals(
            500f,
            camera.screenY(
                state.ends.b!!
                    .coord.lat,
            ),
            0.01f,
        )
        assertEquals(500f, camera.screenY(radar.crossing!!.bLat), 0.01f)
        assertEquals(
            100f,
            camera.screenX(
                state.ends.a!!
                    .coord.lon,
            ),
            0.01f,
        )
        assertTrue(heard.isEmpty())
    }

    @Test fun aFlickTowardsAStopSnapsToItAndASlowDropNearItDoesNot() {
        pick.radar.update { it.copy(stops = stopsAt(300f to 600f)) }
        tap(100f, 400f)
        tap(100f, 200f)
        // A fast drag of B that is let go 30 dp short of the stop: 1000 px/s.
        val flick = pick.claimDrag(100f, 200f)!!
        var t = 0L
        var x = 100f
        var y = 200f
        while (y < 580f) {
            t += 10
            x += 5f
            y += 10f
            flick.onDrag(x, y, t)
        }
        flick.onDrop(x, y, 500f, 1000f, t)
        settle()
        assertEquals("S0", state.ends.b!!.gtfsStop)
        assertEquals("Stop 0", state.labelB)
        assertEquals(
            300f,
            camera.screenX(
                state.ends.b!!
                    .coord.lon,
            ),
            0.01f,
        )
        assertEquals(listOf("snapped"), heard)
        assertTrue(radar.pinB!!.snapped)

        // A is carried slowly to the same distance from the stop and held before the drop: it stays under the finger.
        val slow = pick.claimDrag(100f, 400f)!!
        slow.onDrag(200f, 500f, 2_000)
        slow.onDrag(280f, 575f, 4_000)
        slow.onDrop(280f, 575f, 0f, 0f, 5_000)
        settle()
        assertNull(state.ends.a!!.gtfsStop)
        assertEquals(
            280f,
            camera.screenX(
                state.ends.a!!
                    .coord.lon,
            ),
            0.01f,
        )
        assertEquals(listOf("snapped"), heard)
    }

    @Test fun aSnappedPinNudgedALittleStaysOnItsStop() {
        pick.radar.update { it.copy(stops = stopsAt(300f to 600f)) }
        tap(300f, 600f)
        assertEquals("S0", state.ends.a!!.gtfsStop)
        // Started on the stop and travelled under 10 dp (`HOLD_PX`): still snapped, and no new haptic.
        val nudge = pick.claimDrag(300f, 600f)!!
        nudge.onDrag(304f, 603f, 500)
        nudge.onDrop(304f, 603f, 0f, 0f, 1_500)
        settle()
        assertEquals("S0", state.ends.a!!.gtfsStop)
        assertTrue(heard.isEmpty())
    }

    @Test fun aSecondFingerPutsThePinBack() {
        tap(100f, 400f)
        tap(300f, 400f)
        val settled = radar.crossing!!
        val claim = pick.claimDrag(300f, 400f)!!
        claim.onDrag(340f, 520f, 16)
        settle()
        assertEquals(Slot.B, pick.radar.dragSlot)
        claim.onCancel()
        settle()
        assertNull(pick.radar.dragSlot)
        assertEquals(
            300f,
            camera.screenX(
                state.ends.b!!
                    .coord.lon,
            ),
            0.01f,
        )
        assertEquals("the circle is the settled one again", settled.bLat, radar.crossing!!.bLat, 0.0)
        assertEquals(settled.radiusM, radar.crossing!!.radiusM, 0.0)
    }

    @Test fun aTapOnAPinAsksForItsMenu() {
        tap(100f, 400f)
        pick.claimDrag(105f, 395f)!!.onTap()
        assertEquals(listOf("pin A"), heard)
    }

    // ---- the pin menu ----

    @Test fun thePinMenuHasEveryFormatTheDistanceFromYouAndTheSaveOffer() {
        tap(100f, 400f)
        tap(300f, 400f)
        pick.radar.update { it.copy(position = RadarPosition(camera.latAt(700f), camera.lonAt(100f), 5.0)) }
        pick.savedStops = SavedStops(2, 1, emptyList())
        val model = runBlocking { pick.pinMenuModel(Slot.A) }!!
        assertEquals(Slot.A, model.slot)
        assertEquals("Point A", model.title)
        assertEquals(listOf("decimal", "dms", "ddm", "pluscode", "geouri", "osmlink"), model.formats.map { it.id })
        assertTrue(model.details, model.details.startsWith("41.00"))
        assertTrue(model.text(PinMenuModel.FORMAT_GEO)!!.startsWith("geo:41.00"))
        // 300 px south of A at 4 m per px.
        assertTrue(model.fromYou, model.fromYou!!.startsWith("1.2 km from you"))
        assertTrue(model.canSave)
        assertTrue(model.canSwap)
        assertNull(model.stopId)

        // The same gate is already saved: no offer.
        val ends = state.ends
        val stop = SavedStop(1, "Pier", Endpoint("", ends.a!!.coord), Endpoint("", ends.b!!.coord), 0)
        pick.savedStops = SavedStops(2, 2, listOf(stop))
        assertFalse(runBlocking { pick.pinMenuModel(Slot.A) }!!.canSave)
        // Without a position and without saved stops the rows are simply not there.
        pick.radar.update { it.copy(position = null) }
        pick.savedStops = null
        val bare = runBlocking { pick.pinMenuModel(Slot.B) }!!
        assertNull(bare.fromYou)
        assertFalse(bare.canSave)
        pick.remove(Slot.B)
        settle()
        assertNull(runBlocking { pick.pinMenuModel(Slot.B) })
        assertFalse(runBlocking { pick.pinMenuModel(Slot.A) }!!.canSwap)
    }

    @Test fun thePinAnchorIsThePinInTheWindow() {
        tap(100f, 400f)
        pick.mapOriginInWindow =
            androidx.compose.ui.geometry
                .Offset(10f, 50f)
        val anchor = pick.pinAnchor(Slot.A)!!
        assertEquals(110f, anchor.center.x, 0.01f)
        assertEquals(450f, anchor.center.y, 0.01f)
        assertEquals(29f, anchor.width, 0.01f)
        assertNull(pick.pinAnchor(Slot.B))
    }

    // ---- failures and the log ----

    /** Counts the events of every replay and can be told to fail. */
    private class Spy(
        private val real: PickOps,
    ) : PickOps by real {
        val replays = ArrayList<List<PickEvent>>()
        var failing = false

        override suspend fun replay(events: List<PickEvent>) =
            if (failing) {
                Outcome.Err(RustError.Panic("boom"))
            } else {
                replays += events
                real.replay(events)
            }
    }

    @Test fun aFailedCallLeavesThePickAsItWasAndIsReported() {
        val spy = Spy(rust.ops)
        val failing = PickController(spy, scope, camera, listener)
        failing.onTap(100f, 400f, camera.latAt(400f), camera.lonAt(100f))
        MapHostRust.settle(failing)
        spy.failing = true
        failing.onTap(300f, 400f, camera.latAt(400f), camera.lonAt(300f))
        MapHostRust.settle(failing)
        assertEquals(listOf("error"), heard)
        assertNull(failing.state.value.ends.b)
        assertNotNull(failing.state.value.ends.a)
        // The refused event is not replayed later.
        spy.failing = false
        failing.onTap(300f, 400f, camera.latAt(400f), camera.lonAt(300f))
        MapHostRust.settle(failing)
        assertNotNull(failing.state.value.ends.b)
        assertEquals(2, spy.replays.last().size)
    }

    @Test fun theEventLogStartsOverFromTheEndsSoItNeverGrows() {
        val spy = Spy(rust.ops)
        val counted = PickController(spy, scope, camera, listener)
        repeat(12) {
            counted.onTap(100f + it, 400f, camera.latAt(400f), camera.lonAt(100f + it))
            MapHostRust.settle(counted)
            counted.clear()
            MapHostRust.settle(counted)
        }
        assertTrue("longest replay: ${spy.replays.maxOf { it.size }}", spy.replays.maxOf { it.size } <= 2)
        // While an end is being moved the log keeps the events that say so.
        counted.onTap(100f, 400f, camera.latAt(400f), camera.lonAt(100f))
        counted.beginMove(Slot.A)
        counted.onTap(150f, 450f, camera.latAt(450f), camera.lonAt(150f))
        MapHostRust.settle(counted)
        assertNull(counted.state.value.moving)
        assertEquals(
            150f,
            camera.screenX(
                counted.state.value.ends.a!!
                    .coord.lon,
            ),
            0.01f,
        )
    }
}
