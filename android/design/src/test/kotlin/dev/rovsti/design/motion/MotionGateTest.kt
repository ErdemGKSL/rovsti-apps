package dev.rovsti.design.motion

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MotionGateTest {
    private fun TestScope.gate() = MotionGate(this)

    @Test
    fun writesFrozenOnEdgesOnly() =
        runTest(StandardTestDispatcher()) {
            val g = gate()
            repeat(200) { g.scrollConnection.onPreScroll(Offset(0f, 5f), NestedScrollSource.UserInput) }
            assertTrue(g.frozen)
            assertEquals(1, g.frozenWrites)
            g.scrollConnection.onPostFling(Velocity.Zero, Velocity.Zero)
            repeat(10) { g.transition(false) }
            advanceTimeBy(119)
            runCurrent()
            assertTrue("still frozen inside the tail", g.frozen)
            advanceTimeBy(2)
            runCurrent()
            assertFalse(g.frozen)
            assertEquals("one write down, one up", 2, g.frozenWrites)
        }

    @Test
    fun tailIs120Ms() =
        runTest(StandardTestDispatcher()) {
            val g = gate()
            g.gesture(true)
            g.gesture(false)
            advanceTimeBy(119)
            runCurrent()
            assertTrue(g.frozen)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(g.frozen)
        }

    @Test
    fun newMotionInsideTheTailCancelsItWithoutAWrite() =
        runTest(StandardTestDispatcher()) {
            val g = gate()
            g.gesture(true)
            g.gesture(false)
            advanceTimeBy(100)
            g.transition(true)
            assertEquals("no thaw, no second freeze", 1, g.frozenWrites)
            advanceTimeBy(1000)
            runCurrent()
            assertTrue(g.frozen)
            g.transition(false)
            advanceTimeBy(121)
            runCurrent()
            assertFalse(g.frozen)
            assertEquals(2, g.frozenWrites)
        }

    @Test
    fun scrollAndGestureOverlapStaysFrozenUntilBothStop() =
        runTest(StandardTestDispatcher()) {
            val g = gate()
            g.scrollConnection.onPreScroll(Offset(0f, 1f), NestedScrollSource.UserInput)
            g.gesture(true)
            g.scrollConnection.onPostFling(Velocity.Zero, Velocity.Zero)
            advanceTimeBy(500)
            runCurrent()
            assertTrue("gesture still active", g.frozen)
            g.gesture(false)
            advanceTimeBy(121)
            runCurrent()
            assertFalse(g.frozen)
            assertEquals(2, g.frozenWrites)
        }

    @Test
    fun scrollConnectionConsumesNothing() =
        runTest(StandardTestDispatcher()) {
            val g = gate()
            assertEquals(Offset.Zero, g.scrollConnection.onPreScroll(Offset(3f, 4f), NestedScrollSource.UserInput))
            assertEquals(Velocity.Zero, g.scrollConnection.onPostFling(Velocity(1f, 1f), Velocity(2f, 2f)))
        }
}

class ReduceMotionSpecTest {
    @Test
    fun gatedSnapsOnlyWhenReduced() {
        val s = spring<Float>()
        assertTrue(s.gated(true) is SnapSpec<*>)
        assertTrue(s.gated(false) === s)
    }

    @Test
    fun bouncelessCriticallyDampsUnderDampedSpringsAtTheSameStiffness() {
        val bouncy = RovstiMotion.GlassPressUp.spec(0.001f)
        val flat = bouncy.bounceless(true)
        assertEquals(1f, flat.dampingRatio)
        assertEquals(bouncy.stiffness, flat.stiffness)
        assertEquals(0.001f, flat.visibilityThreshold)
        assertTrue(bouncy.bounceless(false) === bouncy)
        val toggle = RovstiMotion.Toggle.spec(0.001f)
        assertTrue("over-damped springs stay as they are", toggle.bounceless(true) === toggle)
    }
}
