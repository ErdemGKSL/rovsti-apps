package dev.rovsti.phone.incall

/**
 * The windows of the mini-call-pill to full-screen morph, in units of its progress (0 = pill, 1 = full screen), taken
 * from the numbers of the reference music app's mini player and full player morph (see the motion research notes): the pill's
 * content is gone by 0.3, the full content fades in from 0.1 to 0.6, and the pill owns the touches only while the
 * progress is at most 0.2. The rectangle between the two ends is a plain linear interpolation, because the spring that
 * drives the progress already shapes the motion.
 */
object CallSheetMotion {
    const val MiniGoneBy = 0.3f
    const val FullFrom = 0.1f
    const val FullTo = 0.6f
    const val MiniOwnsInputUntil = 0.2f

    /** The pill content's opacity at [progress]: 1 at the pill, ease-out cubic down to 0 at [MiniGoneBy]. */
    fun miniAlpha(progress: Float): Float {
        val t = (progress / MiniGoneBy).coerceIn(0f, 1f)
        return 1f - easeOutCubic(t)
    }

    /** The full screen's opacity at [progress]: 0 until [FullFrom], ease-in cubic up to 1 at [FullTo]. */
    fun fullAlpha(progress: Float): Float {
        val t = ((progress - FullFrom) / (FullTo - FullFrom)).coerceIn(0f, 1f)
        return t * t * t
    }

    /** Whether the pill takes touches at [progress]; mid-flight nobody does, so a tap cannot fight the spring. */
    fun miniOwnsInput(progress: Float): Boolean = progress <= MiniOwnsInputUntil

    /** Linear interpolation of one edge between the pill and the screen. */
    fun lerp(
        from: Float,
        to: Float,
        progress: Float,
    ): Float = from + (to - from) * progress

    private fun easeOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }
}
