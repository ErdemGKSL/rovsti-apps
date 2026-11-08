/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/utils/SharedArtwork.kt
 * Changes for Rovsti: the music-specific source registry (artwork URLs, owners, library origins) is left out; a shared
 * element is keyed by an id the caller chooses (`avatar-<contact id>`) and joins the flight on both ends by key alone.
 * `LocalSharedTransitionScope` / `LocalNavAnimatedVisibilityScope` became [LocalSharedScopes].
 */
@file:OptIn(ExperimentalSharedTransitionApi::class)

package dev.rovsti.design.transition

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The scopes a shared element needs: the [SharedTransitionScope] around the `NavDisplay` and the entry's
 * [AnimatedVisibilityScope] (Navigation 3 provides the entry's `AnimatedContentScope` as `LocalNavAnimatedContentScope`).
 */
@Immutable
class SharedScopes(
    val transition: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
)

/** Null outside [RovstiNavHost] (previews, tests of a single page): [rovstiSharedBounds] is then a no-op. */
val LocalSharedScopes = compositionLocalOf<SharedScopes?> { null }

/** Z index of a flying element in the shared-transition overlay: above both pages so neither clips it (Convx 1f). */
private const val FlightZIndex = 1f

/** Titles fly above artwork (Convx `sharedTitleElement`: 2f). */
private const val TitleZIndex = 2f

/**
 * Marks this node as one end of a shared flight. Put the same [key] on the node of the source page (a row's avatar) and
 * of the destination page (the detail header); while the page transition runs the node is drawn in the overlay above
 * both pages, so neither page clips it, and its bounds follow the 500 ms [Motion.sharedArtwork] spec.
 *
 * Place it before `size`, `clip` and `shadow`: the bounds are those of the node it is applied to.
 */
@Composable
fun Modifier.rovstiSharedBounds(
    key: Any,
    title: Boolean = false,
): Modifier {
    val scopes = LocalSharedScopes.current ?: return this
    return with(scopes.transition) {
        this@rovstiSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = scopes.visibility,
            boundsTransform = SharedBoundsTransform,
            renderInOverlayDuringTransition = true,
            zIndexInOverlay = if (title) TitleZIndex else FlightZIndex,
        )
    }
}

private val SharedBoundsTransform = BoundsTransform { _, _ -> Motion.sharedArtwork() }

/**
 * True while a page transition or a shared flight runs (Convx `artworkTransitionRunning`), so heavy work (image
 * decoding, list measuring) can wait for the end of the motion. False outside [RovstiNavHost].
 */
@Composable
fun isTransitionActive(): Boolean {
    val scopes = LocalSharedScopes.current ?: return false
    val transition = scopes.visibility.transition
    return transition.currentState != transition.targetState || scopes.transition.isTransitionActive
}
