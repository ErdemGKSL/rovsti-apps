package dev.rovsti.design.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

class SpringStateTest {
    /** Reference: classic RK4 on x'' = -k x - c x' with a 1 kHz step, in double precision. */
    private fun rk4(
        stiffness: Double,
        damping: Double,
        x0: Double,
        v0: Double,
        seconds: Double,
        dt: Double = 0.001,
    ): Pair<Double, Double> {
        var x = x0
        var v = v0

        fun acc(
            px: Double,
            pv: Double,
        ) = -stiffness * px - damping * pv
        var t = 0.0
        while (t < seconds - 1e-12) {
            val k1x = v
            val k1v = acc(x, v)
            val k2x = v + 0.5 * dt * k1v
            val k2v = acc(x + 0.5 * dt * k1x, v + 0.5 * dt * k1v)
            val k3x = v + 0.5 * dt * k2v
            val k3v = acc(x + 0.5 * dt * k2x, v + 0.5 * dt * k2v)
            val k4x = v + dt * k3v
            val k4v = acc(x + dt * k3x, v + dt * k3v)
            x += dt / 6 * (k1x + 2 * k2x + 2 * k3x + k4x)
            v += dt / 6 * (k1v + 2 * k2v + 2 * k3v + k4v)
            t += dt
        }
        return x to v
    }

    private fun assertMatchesRk4(
        stiffness: Float,
        damping: Float,
        v0: Float = 0f,
    ) {
        val s = SpringState(value = 0f, target = 1f, velocity = v0)
        var t = 0.0
        // Step 1 ms like the reference so each sampled instant matches exactly.
        repeat(400) {
            s.advance(stiffness, damping, 0.001f)
            t += 0.001
            val (x, v) = rk4(stiffness.toDouble(), damping.toDouble(), -1.0, v0.toDouble(), t)
            assertEquals("value at t=$t", 1.0 + x, s.value.toDouble(), 1e-3)
            // Velocity tolerance scales with the spring's speed; it is looser only because velocity is large.
            assertEquals("velocity at t=$t", v, s.velocity.toDouble(), 1e-2)
        }
    }

    @Test
    fun underDampedMatchesRk4() = assertMatchesRk4(250f, 11.8f)

    @Test
    fun criticallyDampedMatchesRk4() = assertMatchesRk4(400f, 40f)

    @Test
    fun overDampedMatchesRk4() = assertMatchesRk4(1000f, 80f)

    @Test
    fun initialVelocityMatchesRk4() {
        assertMatchesRk4(250f, 11.8f, v0 = 4f)
        assertMatchesRk4(1000f, 80f, v0 = -3f)
    }

    @Test
    fun largeStepsMatchRk4Too() {
        // Frame-rate independence: one 16 ms step lands where 1 ms steps land.
        val s = SpringState(0f, 1f)
        s.advance(250f, 11.8f, 0.016f)
        val (x, _) = rk4(250.0, 11.8, -1.0, 0.0, 0.016)
        assertEquals(1.0 + x, s.value.toDouble(), 1e-4)
    }

    @Test
    fun oneStepEqualsTwoHalfSteps() {
        for ((k, c) in listOf(250f to 11.8f, 400f to 40f, 1000f to 80f)) {
            val one = SpringState(0f, 1f, 2f)
            val two = SpringState(0f, 1f, 2f)
            one.advance(k, c, 0.016f)
            two.advance(k, c, 0.008f)
            two.advance(k, c, 0.008f)
            assertEquals("value k=$k", one.value, two.value, 1e-5f)
            assertEquals("velocity k=$k", one.velocity, two.velocity, 1e-3f)
        }
    }

    @Test
    fun retargetKeepsValueAndVelocity() {
        val s = SpringState(0f, 1f)
        repeat(5) { s.advance(RovstiMotion.GlassPressUp, 0.016f) }
        val value = s.value
        val velocity = s.velocity
        assertTrue(velocity > 0f)
        s.set(0f)
        assertEquals(value, s.value)
        assertEquals(velocity, s.velocity)
        assertEquals(0f, s.target)
        // One frame later the motion is continuous: no jump larger than a frame of travel.
        s.advance(RovstiMotion.GlassPressUp, 0.016f)
        val dt = 0.016f
        val bound = abs(velocity) * dt + 0.5f * RovstiMotion.GlassPressUp.stiffness * abs(value) * dt * dt
        assertTrue("jump ${abs(s.value - value)} <= $bound", abs(s.value - value) <= bound * 1.05f)
    }

    @Test
    fun glassPressUpOvershootsAboutTwentyEightPercentOnce() {
        val s = SpringState(0f, 1f)
        var peak = 0f
        var crossingsOfTarget = 0
        var wasAbove = false
        var steps = 0
        while (steps < 2000) {
            val settled = s.advance(RovstiMotion.GlassPressUp, 0.004f)
            peak = max(peak, s.value)
            val above = s.value > 1f
            if (above != wasAbove) crossingsOfTarget++
            wasAbove = above
            steps++
            if (settled) break
        }
        val zeta = RovstiMotion.GlassPressUp.dampingRatio.toDouble()
        val theory = exp(-zeta * Math.PI / sqrt(1 - zeta * zeta))
        assertEquals("theory", 0.28, theory, 0.01)
        assertEquals("measured overshoot", 0.28, (peak - 1f).toDouble(), 0.03)
        assertTrue("settles within 2000 steps", steps < 2000)
        assertTrue("goes above target once, then comes back and eventually settles on it", crossingsOfTarget >= 2)
    }

    @Test
    fun settlesExactlyOnTheTarget() {
        for (spring in listOf(RovstiMotion.GlassPressUp, RovstiMotion.SelectRest, RovstiMotion.Toggle)) {
            val s = SpringState(0f, 1f, 5f)
            var settled = false
            var steps = 0
            while (!settled && steps < 5000) {
                settled = s.advance(spring, 0.008f)
                steps++
            }
            assertTrue("$spring settles", settled)
            assertEquals(1f, s.value)
            assertEquals(0f, s.velocity)
        }
    }

    @Test
    fun timeStepIsClampedToOneTenthSecond() {
        val a = SpringState(0f, 1f)
        val b = SpringState(0f, 1f)
        a.advance(250f, 11.8f, 5f)
        b.advance(250f, 11.8f, 0.1f)
        assertEquals(b.value, a.value)
        assertEquals(b.velocity, a.velocity)
        val c = SpringState(0f, 1f)
        c.advance(250f, 11.8f, -1f)
        assertEquals(0f, c.value)
    }

    @Test
    fun toggleNeverOvershoots() {
        val s = SpringState(0f, 1f)
        var peak = 0f
        repeat(2000) {
            s.advance(RovstiMotion.Toggle, 0.004f)
            peak = max(peak, s.value)
        }
        assertFalse("peak $peak", peak > 1f)
    }
}
