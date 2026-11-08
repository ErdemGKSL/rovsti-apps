/*
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/constants/PreferenceKeys.kt
 * Changes for Rovsti: the DataStore keys Convx reads inside its glass code (ReduceDynamicBlurKey, TopBarBlurStyleKey,
 * MenuBackdropResolutionKey) plus a "reduce transparency" switch became one immutable settings class.
 */
package dev.rovsti.design.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** How [TopFadeBlur] blurs the strip under a top bar. Convx: constants/PreferenceKeys.kt `TopBarBlurStyle`. */
enum class TopBarBlurStyle {
    /** One shader pass sampling the app backdrop (needs API 33 and a backdrop outside the recorded subtree). */
    SinglePass,

    /** Haze progressive blur over a `hazeSource`. */
    HazeProgressive,
}

/**
 * User-level glass switches, read through [LocalGlassSettings]. Nothing else turns glass off: the only fallbacks
 * are Convx's own (`isGlassAllowed()`: API 31+ and not a low-RAM device) and [reduceTransparency].
 */
@Immutable
data class GlassSettings(
    /** Accessibility "reduce transparency": every glass surface paints its translucent fallback fill. */
    val reduceTransparency: Boolean = false,
    /** Replace live blur (Haze bands, top-bar blur) with flat colour for low-power devices. */
    val reduceDynamicBlur: Boolean = false,
    /** Capture scale of long-press menus, 0.3..1. */
    val menuBackdropResolution: Float = 1f,
    val topBarBlurStyle: TopBarBlurStyle = TopBarBlurStyle.HazeProgressive,
)

val LocalGlassSettings = staticCompositionLocalOf { GlassSettings() }
