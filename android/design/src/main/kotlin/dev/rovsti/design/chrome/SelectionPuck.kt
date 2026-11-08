package dev.rovsti.design.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import dev.rovsti.design.motion.Fling
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.motion.bounceless
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The physics of the selection under a tab bar or segmented control (ORIGINAL; the spring constants are glisse's, see
 * [RovstiMotion]). Three quantities, each its own [Animatable] so a new target keeps value and velocity:
 *
 * - [position], in slots (1.0 = one tab to the right);
 * - [lift], 0 at rest and 1 while held (the puck grows into a lens);
 * - [velocity], in slots per second, used only to squash and stretch the puck.
 *
 * While a finger is down it owns [position] (`snapTo`, started undispatched so it lands in the same frame). On release
 * the target is the slot under the *projected* rest point, and the settle spring starts with the release velocity.
 * The lift deflates only once the puck has arrived (or after 400 ms), so the lens does not shrink on its way.
 *
 * [reduceMotion] makes the springs critically damped and turns squash off.
 */
@Stable
class SelectionPuck(
    initialIndex: Int,
    var count: Int,
    private val scope: CoroutineScope,
    private val reduceMotion: () -> Boolean = { false },
) {
    val position = Animatable(initialIndex.toFloat())
    val lift = Animatable(0f)
    val velocity = Animatable(0f)

    var dragging: Boolean = false
        private set

    private var deflate: Job? = null

    private fun clampSlot(v: Float) = v.coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat())

    /** Squash/stretch now: speed in slots per second over 40, at most [TabBarSpec.MaxSquash], zero under reduce motion. */
    fun squash(): Float =
        if (reduceMotion()) {
            0f
        } else {
            (
                abs(
                    velocity.value,
                ) / SlotsPerSecondForMaxSquash
            ).coerceAtMost(TabBarSpec.MaxSquash)
        }

    /** A finger went down on slot [anchor]: lift now and slide under it, both with the drag tuning. */
    fun press(anchor: Int) {
        dragging = true
        deflate?.cancel()
        scope.launch { lift.animateTo(1f, RovstiMotion.LiftDrag.spec(UNIT).bounceless(reduceMotion())) }
        val target = anchor.toFloat().coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat())
        if (abs(position.targetValue - target) > ARRIVED) {
            scope.launch { position.animateTo(target, RovstiMotion.SelectDrag.spec(UNIT).bounceless(reduceMotion())) }
        }
    }

    /** The finger moved: the puck sits at [slots] (clamped) and [velocitySlotsPerSecond] feeds the squash. */
    fun drag(
        slots: Float,
        velocitySlotsPerSecond: Float,
    ) {
        val next = clampSlot(slots)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            position.snapTo(next)
            velocity.snapTo(velocitySlotsPerSecond)
        }
    }

    /** The slot a release at the current position with [velocitySlotsPerSecond] lands on (projected rest point). */
    fun targetFor(velocitySlotsPerSecond: Float): Int =
        slotAt(Fling.restingPoint(position.value, velocitySlotsPerSecond), count)

    /**
     * The finger lifted after a drag. Settles on [targetFor] with the release velocity and returns the slot.
     */
    fun release(velocitySlotsPerSecond: Float): Int {
        val target = targetFor(velocitySlotsPerSecond)
        dragging = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) { velocity.snapTo(velocitySlotsPerSecond) }
        scope.launch { velocity.animateTo(0f, RovstiMotion.squashDecay(SQUASH_UNIT)) }
        settle(target, velocitySlotsPerSecond)
        return target
    }

    /** A tap, a programmatic selection or a rejected change: go to [index] and deflate on arrival. */
    fun select(index: Int) {
        dragging = false
        settle(index.coerceIn(0, (count - 1).coerceAtLeast(0)), initialVelocity = null)
    }

    /** The touch was handed to the page: drop the lift and go back to where the puck was meant to be. */
    fun cancel(rest: Int) = select(rest)

    private fun settle(
        target: Int,
        initialVelocity: Float?,
    ) {
        val spec = RovstiMotion.SelectRest.spec(UNIT).bounceless(reduceMotion())
        scope.launch {
            if (initialVelocity != null) {
                position.animateTo(target.toFloat(), spec, initialVelocity = initialVelocity)
            } else {
                position.animateTo(target.toFloat(), spec)
            }
        }
        deflate?.cancel()
        deflate =
            scope.launch {
                withTimeoutOrNull(DEFLATE_TIMEOUT_MS) {
                    snapshotFlow { abs(position.value - target) < ARRIVED }.first { it }
                }
                lift.animateTo(0f, RovstiMotion.LiftRest.spec(UNIT).bounceless(reduceMotion()))
            }
    }

    private companion object {
        const val UNIT = 0.001f
        const val SQUASH_UNIT = 0.01f
        const val ARRIVED = 0.025f
        const val DEFLATE_TIMEOUT_MS = 400L
        const val SlotsPerSecondForMaxSquash = 40f
    }
}
