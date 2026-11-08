/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/AppNavHost.kt
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/AppRootContent.kt
 * Changes for Rovsti: Navigation 3 `NavDisplay` takes the four Navigation Compose lambdas as `transitionSpec`,
 * `popTransitionSpec` and `predictivePopTransitionSpec` (one lambda for initial and target scene instead of one per
 * direction), so the enter/exit pairs of AppNavHost.kt:55-98 are combined with `togetherWith`; `isMorphRoute(route)` is a
 * predicate over the page's key (a page that grows out of a tile of the page below); a tab switch is the 220/160 ms
 * crossfade of AppRootContent.kt:990. Predictive back uses the pop spec, as Navigation Compose does.
 */
package dev.rovsti.design.transition

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.scene.Scene
import dev.rovsti.design.motion.RovstiMotion

/** Metadata key under which `RovstiNavHost` stores the [StackKey] of every `NavEntry`. */
const val StackKeyMetadata = "rovsti.stackKey"

/**
 * The three `NavDisplay` transition specs for pages keyed by [StackKey].
 *
 * @param isMorphRoute true for a page that grows out of a tile of the page below (Convx `isMorphRoute`): the page below
 *   holds still and dims instead of sliding away.
 */
class NavTransitions<R : Any>(
    private val isMorphRoute: (R) -> Boolean = { false },
) {
    // NavEntry.key is private in 1.2.1; the host stores the key in the entry metadata (see [StackKeyMetadata]).
    @Suppress("UNCHECKED_CAST")
    private fun stackKey(scene: Scene<StackKey<R>>): StackKey<R>? = scene.metadata[StackKeyMetadata] as? StackKey<R>

    private fun route(scene: Scene<StackKey<R>>): R? = stackKey(scene)?.route

    private fun tab(scene: Scene<StackKey<R>>): Tab? = stackKey(scene)?.tab

    private fun AnimatedContentTransitionScope<Scene<StackKey<R>>>.switchesTab(): Boolean =
        tab(initialState) != tab(targetState)

    private fun AnimatedContentTransitionScope<Scene<StackKey<R>>>.morphTarget(): Boolean =
        route(targetState)?.let(isMorphRoute) == true

    private fun AnimatedContentTransitionScope<Scene<StackKey<R>>>.morphInitial(): Boolean =
        route(initialState)?.let(isMorphRoute) == true

    /** Convx `AppRootContent.kt:990`: tabs cross-fade, nothing slides. */
    private fun tabSwitch(): ContentTransform = fadeIn(tween(220)) togetherWith fadeOut(tween(160))

    /** Convx `AppNavHost.kt` enterTransition + exitTransition. */
    val transitionSpec: AnimatedContentTransitionScope<Scene<StackKey<R>>>.() -> ContentTransform = {
        if (switchesTab()) {
            tabSwitch()
        } else {
            // The incoming screen slides a full width while the outgoing screen moves a third of that distance and dims.
            val enter = slideInHorizontally(if (morphTarget()) Motion.artwork() else RovstiMotion.push()) { it }
            val exit: ExitTransition =
                // A destination that grows out of one of this screen's tiles must not slide it away underneath: the
                // tile is the thing the next screen is coming out of, so this one holds still and dims.
                if (morphTarget()) {
                    fadeOut(Motion.artwork(), targetAlpha = 0.7f)
                } else {
                    slideOutHorizontally(RovstiMotion.push()) {
                        RovstiMotion.parallaxOffset(it)
                    } + fadeOut(RovstiMotion.push(), targetAlpha = RovstiMotion.PushDimAlpha)
                }
            enter togetherWith exit
        }
    }

    /** Convx `AppNavHost.kt` popEnterTransition + popExitTransition. */
    val popTransitionSpec: AnimatedContentTransitionScope<Scene<StackKey<R>>>.() -> ContentTransform = {
        if (switchesTab()) {
            tabSwitch()
        } else {
            // The screen underneath eases back from its parallax offset while the leaving one slides off, so back mirrors
            // the push. The incoming screen stays opaque to avoid a transparent overlap.
            // Deviation: Convx passes `parallaxOffset(-it)`, which is +0.3 W (the page starts to the right of its resting
            // place and leaves a blank band on the left while the leaving page slides off). The push moves the page to
            // -0.3 W, so the mirror is `parallaxOffset(it)`: it eases back from -0.3 W to 0.
            val enter: EnterTransition =
                if (morphInitial()) {
                    EnterTransition.None
                } else {
                    slideInHorizontally(RovstiMotion.push()) { RovstiMotion.parallaxOffset(it) }
                }
            val exit: ExitTransition =
                if (morphInitial()) {
                    fadeOut(Motion.artwork())
                } else {
                    slideOutHorizontally(RovstiMotion.push()) { it } + fadeOut(RovstiMotion.push())
                }
            enter togetherWith exit
        }
    }

    /** Predictive back scrubs the pop transition (the swipe edge does not change it). */
    val predictivePopTransitionSpec: AnimatedContentTransitionScope<Scene<StackKey<R>>>.(Int) -> ContentTransform = {
        popTransitionSpec(this)
    }
}
