package dev.rovsti.design.motion

import kotlin.math.abs
import kotlin.math.sign

/** Momentum projection ("Designing Fluid Interfaces"): where a flick would come to rest. Pure, no Compose. */
object Fling {
    /** UIKit normal deceleration. */
    const val NormalRate = 0.998f

    /** UIKit fast deceleration. */
    const val FastRate = 0.99f

    /** Distance a flick travels before it stops; velocity in px/s. */
    fun projectedDistance(
        velocityPxPerSec: Float,
        decelerationRate: Float = NormalRate,
    ): Float = (velocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)

    fun restingPoint(
        position: Float,
        velocityPxPerSec: Float,
        rate: Float = NormalRate,
    ): Float = position + projectedDistance(velocityPxPerSec, rate)

    /**
     * The snap point nearest to [rest]. On an exact tie the point lying in the direction of travel wins;
     * with no velocity the lower value wins.
     */
    fun nearestSnap(
        points: FloatArray,
        rest: Float,
        velocityPxPerSec: Float,
    ): Float {
        require(points.isNotEmpty()) { "no snap points" }
        val travel = sign(velocityPxPerSec)
        var best = points[0]
        for (i in 1 until points.size) {
            val p = points[i]
            if (isBetter(p, best, rest, travel)) best = p
        }
        return best
    }

    private fun isBetter(
        candidate: Float,
        best: Float,
        rest: Float,
        travel: Float,
    ): Boolean {
        val dc = abs(candidate - rest)
        val db = abs(best - rest)
        if (dc != db) return dc < db
        if (travel != 0f) return sign(candidate - rest) == travel
        return candidate < best
    }
}
