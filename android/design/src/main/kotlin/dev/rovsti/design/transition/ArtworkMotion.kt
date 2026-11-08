/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, artwork/src/main/kotlin/com/convx/artwork/ArtworkMotion.kt
 * Changes for Rovsti: package dev.rovsti.design.transition; the duration is only the default of [Motion.artwork]
 * (the shared-bounds flight uses the 500 ms [sharedArtworkMillis]).
 */
package dev.rovsti.design.transition

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import kotlin.math.exp

/** The easing of Convx's artwork flights: a gamma(2) CDF, quick out of rest and settling very gently. */
object ArtworkMotion {
    const val DurationMillis = 350
    private const val Endpoint = 9f
    private val normalization = 1f / (1f - (1f + Endpoint) * exp(-Endpoint))

    fun progress(fraction: Float): Float {
        if (!fraction.isFinite()) return 1f
        if (fraction <= 0f) return 0f
        if (fraction >= 1f) return 1f
        val position = Endpoint * fraction
        return (1f - (1f + position) * exp(-position)) * normalization
    }
}

/** Convx `ui/utils/Motion.kt`: the artwork specs used by the push (morph routes) and the shared-bounds flight. */
object Motion {
    private val ArtworkEasing = Easing(ArtworkMotion::progress)

    /** Convx `Motion.artwork()`: 350 ms on the artwork easing. */
    fun <T> artwork(): FiniteAnimationSpec<T> = tween(ArtworkMotion.DurationMillis, easing = ArtworkEasing)

    /** List artwork expanding into a detail hero and returning to its source tile (Convx `SharedArtworkMillis`). */
    const val SharedArtworkMillis = 500

    /** Convx `Motion.sharedArtwork()`: the 500 ms flight of a large avatar between a row and its detail page. */
    fun <T> sharedArtwork(): FiniteAnimationSpec<T> = tween(SharedArtworkMillis, easing = ArtworkEasing)
}
