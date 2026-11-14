package dev.rosty.stops.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.rosty.design.motion.MotionGate
import dev.rosty.design.motion.rememberMotionGate
import dev.rosty.testing.RecompositionCounter
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E7.2 AC1 and ARCH 10 rule 9: a pan, a pinch and a fling recompose nothing, anywhere: not [StopsMap], not the
 * screen that calls it, not the scale bar or the attribution beside it. Counted two ways: slots in the caller, and the
 * recomposer's own count of applied compositions (which also covers the inside of [StopsMap]). The slots count with a
 * `SideEffect` of their own: a counting child composable would be skipped and count nothing.
 *
 * And E7.2 AC3: chrome that follows the motion through [MapMotionEffect] hears two edges per gesture.
 */
class StopsMapRecompositionTest : ShotTest() {
    private val camera = MapCamera()
    private val counter = RecompositionCounter()
    private var overlayDraws = 0
    private var gate: MotionGate? = null
    private var chromeReadsMoving = false

    // One instance for the life of the test: a new lambda per composition would be a new modifier element.
    private val overlay: DrawScope.(MapCamera) -> Unit = { overlayDraws++ }

    @Composable
    private fun Screen() {
        SideEffect { counter.increment("screen") }
        val scope = rememberCoroutineScope()
        val source = remember { FakeTileSource.city() }
        val store = remember { MapTileStore(source, scope, rasterDispatcher = Dispatchers.Unconfined) }
        Box(Modifier.fillMaxSize()) {
            StopsMap(camera, store, dark = false, modifier = Modifier.fillMaxSize().testTag(TAG), overlay = overlay)
            Sibling()
            MapScaleBar(camera, Modifier.align(Alignment.BottomStart))
            MapAttribution(Modifier.align(Alignment.BottomEnd))
            Chrome()
        }
    }

    @Composable
    private fun Sibling() {
        SideEffect { counter.increment("sibling") }
    }

    @Composable
    private fun Chrome() {
        SideEffect { counter.increment("chrome") }
        val g = rememberMotionGate()
        gate = g
        MapMotionEffect(camera, g)
        if (chromeReadsMoving) MovingReader()
    }

    @Composable
    private fun MovingReader() {
        // Chrome that picks its glass tier in composition: recomposes on the two edges of a gesture, not per frame.
        @Suppress("UNUSED_VARIABLE")
        val moving = camera.moving.value
        SideEffect { counter.increment("moving-reader") }
    }

    private fun show() {
        camera.jumpTo(MapFixtures.cityLat, MapFixtures.cityLon, 14.8)
        composeFrame { Screen() }
        advance(200)
    }

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag(TAG).performTouchInput(block)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun applied(): Long = Recomposer.runningRecomposers.value.sumOf { it.changeCount }

    private fun pan() {
        touch {
            down(Offset(600f, 1300f))
            advanceEventTime(16)
            moveBy(Offset(viewConfiguration.touchSlop + 4f, 0f))
        }
        repeat(20) {
            touch {
                advanceEventTime(16)
                moveBy(Offset(11f, -7f))
            }
        }
        touch {
            advanceEventTime(500)
            up()
        }
    }

    private fun pinch() {
        touch {
            down(0, Offset(400f, 1300f))
            down(1, Offset(800f, 1300f))
        }
        repeat(20) { i ->
            touch {
                advanceEventTime(16)
                updatePointerTo(0, Offset(400f - 6f * i, 1300f))
                updatePointerTo(1, Offset(800f + 6f * i, 1300f))
                move()
            }
        }
        touch {
            up(0)
            up(1)
        }
    }

    private fun flick() {
        touch {
            down(Offset(600f, 1300f))
            advanceEventTime(16)
            moveBy(Offset(viewConfiguration.touchSlop + 4f, 0f))
            repeat(6) {
                advanceEventTime(8)
                moveBy(Offset(40f, 0f))
            }
            up()
        }
        repeat(120) {
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
    }

    @Test fun aPanAPinchAndAFlingRecomposeNothing() {
        show()
        // Let the tiles of the first view arrive and the motion gate's tail pass before counting.
        advance(500)
        assertEquals(1, counter["screen"])
        counter.reset()
        val appliedBefore = applied()
        val drawsBefore = overlayDraws
        val generation = camera.generation

        pan()
        advance(300)
        // Robolectric draws only when asked: a capture shows the node redraws from the camera without a composition.
        compose.onNodeWithTag(TAG).captureToImage()
        assertTrue("the map was redrawn after the pan", overlayDraws > drawsBefore)
        val drawsAfterPan = overlayDraws
        pinch()
        compose.onNodeWithTag(TAG).captureToImage()
        assertTrue("and after the pinch", overlayDraws > drawsAfterPan)
        advance(300)
        flick()
        advance(300)

        assertTrue("the camera really moved", camera.generation > generation + 60)
        assertEquals("slots recomposed: $counter", emptySet<String>(), counter.tags)
        assertEquals("compositions applied anywhere during the gestures", 0L, applied() - appliedBefore)
        assertFalse(camera.moving.value)
    }

    @Test fun chromeHearsTwoEdgesPerGesture() {
        chromeReadsMoving = true
        show()
        advance(500)
        val g = gate!!
        assertFalse(g.frozen)
        counter.reset()

        touch {
            down(Offset(600f, 1300f))
            advanceEventTime(16)
            moveBy(Offset(viewConfiguration.touchSlop + 4f, 0f))
        }
        advance(32)
        // E7.2 AC3: glass over the map is frozen (Frost) while the map pans.
        assertTrue(g.frozen)
        repeat(30) {
            touch {
                advanceEventTime(16)
                moveBy(Offset(9f, 5f))
            }
        }
        assertTrue(g.frozen)
        assertEquals("one recomposition for the start edge", 1, counter["moving-reader"])
        touch {
            advanceEventTime(500)
            up()
        }
        // At rest again after the gate's tail: Liquid (Flat in tests).
        advance(MotionGate.DefaultTailMs + 100)
        assertFalse(g.frozen)
        assertEquals("two edges, two recompositions", 2, counter["moving-reader"])
        assertEquals(0, counter["screen"])
        assertEquals(0, counter["sibling"])
        assertEquals(0, counter["chrome"])
    }

    private companion object {
        const val TAG = "map"
    }
}
