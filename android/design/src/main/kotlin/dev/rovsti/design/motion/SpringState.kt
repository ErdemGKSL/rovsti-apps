// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.motion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Closed-form damped spring (mass 1), ported from glisse `src/internal/spring.ts:21-64`.
 *
 * Exact for any step size up to [MaxStep], so the result does not depend on the frame rate.
 * UI code animates with `Animatable` and [GlisseSpring.spec]; this class steps a spring by hand
 * (tests, custom nodes driven from `withFrameNanos`).
 */
class SpringState(
    var value: Float,
    var target: Float = value,
    var velocity: Float = 0f,
) {
    /** Retargets without touching value or velocity, which is what makes a spring interruptible. */
    fun set(newTarget: Float) {
        target = newTarget
    }

    /** Advances by [dt0] seconds (clamped to 0..[MaxStep]). Returns true once settled, with value snapped to target. */
    fun advance(
        stiffness: Float,
        damping: Float,
        dt0: Float,
    ): Boolean {
        val dt = dt0.coerceIn(0f, MaxStep)
        val x = value - target
        val v = velocity
        val half = damping / 2f
        val discriminant = half * half - stiffness
        if (abs(discriminant) < 1e-4f) {
            val decay = exp(-half * dt)
            val b = v + half * x
            value = target + decay * (x + b * dt)
            velocity = decay * (v - half * b * dt)
        } else if (discriminant > 0f) {
            val root = sqrt(discriminant)
            val r1 = -half + root
            val r2 = -half - root
            val a = (v - r2 * x) / (r1 - r2)
            val b = x - a
            val e1 = exp(r1 * dt)
            val e2 = exp(r2 * dt)
            value = target + a * e1 + b * e2
            velocity = a * r1 * e1 + b * r2 * e2
        } else {
            val omega = sqrt(-discriminant)
            val decay = exp(-half * dt)
            val c = cos(omega * dt)
            val s = sin(omega * dt)
            value = target + decay * (x * c + ((v + half * x) / omega) * s)
            velocity = decay * (v * c - ((half * v + stiffness * x) / omega) * s)
        }
        if (abs(value - target) < SettlePosition && abs(velocity) < SettleVelocity) {
            value = target
            velocity = 0f
            return true
        }
        return false
    }

    fun advance(
        spring: GlisseSpring,
        dt: Float,
    ): Boolean = advance(spring.stiffness, spring.damping, dt)

    companion object {
        /** Longest step the solver takes in one call, seconds. */
        const val MaxStep = 0.1f
        const val SettlePosition = 1e-4f
        const val SettleVelocity = 1e-3f
    }
}
