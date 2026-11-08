package dev.rovsti.design.motion

import kotlin.math.abs

/** Rubber-band resistance for drags past a bound. Pure, no Compose. */
object Rubber {
    const val Constant = 0.55f

    /**
     * Displayed offset for a raw pull: `x * D / (D + c * |x|)`. Odd, monotonic, slope 1 at 0, asymptote `D / c`.
     * The first pixels follow the finger 1:1 so a touch feels attached.
     */
    fun band(
        raw: Float,
        dimension: Float,
        c: Float = Constant,
    ): Float {
        if (dimension <= 0f || raw == 0f) return 0f
        return raw * dimension / (dimension + c * abs(raw))
    }
}
