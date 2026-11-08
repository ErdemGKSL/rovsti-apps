/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/AppBackdropState.kt
 * Changes for Rovsti: package dev.rovsti.design.backdrop; pureBlack/AppTheme became the baseBg parameter, the floating tab bar scroll
 * connection became the extraFrozen parameter; navigation direction is passed as lastNavWasPop.
 */
package dev.rovsti.design.backdrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import dev.rovsti.design.backdrop.backdrops.LayerBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberBackdropFreeze
import dev.rovsti.design.backdrop.backdrops.rememberLayerBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberNavTransitionFreeze
import kotlinx.coroutines.delay

/** State shared by the app backdrop and its freeze controls. */
@Stable
class AppBackdropState(
    val baseBg: Color,
    val appBackdrop: LayerBackdrop,
    val frozenProvider: () -> Boolean,
    val motionFrozen: MutableState<Boolean>,
    val scrollConnection: NestedScrollConnection,
)

/**
 * @param baseBg colour drawn under the recorded content (Convx: surfaceContainer, or black in pure-black mode).
 * @param extraFrozen additional freeze source, e.g. the floating tab bar's `isScrolling()` in Convx.
 */
@Composable
fun rememberAppBackdropState(
    baseBg: Color,
    currentRoute: String?,
    extraFrozen: () -> Boolean = { false },
    lastNavWasPop: () -> Boolean = { false },
): AppBackdropState {
    // Keep this draw lambda stable; changing it makes the backdrop record again.
    val appBackdrop =
        rememberLayerBackdrop(
            onDraw =
                remember(baseBg) {
                    val bg = baseBg
                    {
                        drawRect(bg)
                        drawContent()
                    }
                },
        )

    // Keep blur capture still during scrolls and route transitions.
    val backdropFreeze = rememberBackdropFreeze()
    val navTransitionFreeze = rememberNavTransitionFreeze(currentRoute, lastNavWasPop)
    val backdropFrozenProvider =
        remember(backdropFreeze, navTransitionFreeze, extraFrozen) {
            {
                backdropFreeze.frozen() ||
                    navTransitionFreeze.frozen() ||
                    extraFrozen()
            }
        }

    // Drawing reads the provider directly; composition needs observable state.
    val appMotionFrozen = remember { mutableStateOf(false) }
    val backdropFreezeConnection =
        remember(backdropFreeze, backdropFrozenProvider, appMotionFrozen) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Pause motion once per gesture, before the next draw.
                    if (!appMotionFrozen.value) appMotionFrozen.value = true
                    return backdropFreeze.connection.onPreScroll(available, source)
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    val result = backdropFreeze.connection.onPostFling(consumed, available)
                    appMotionFrozen.value = backdropFrozenProvider()
                    return result
                }
            }
        }
    LaunchedEffect(backdropFrozenProvider) {
        while (true) {
            val isFrozen = backdropFrozenProvider()
            if (appMotionFrozen.value != isFrozen) appMotionFrozen.value = isFrozen
            delay(120L)
        }
    }

    return remember(baseBg, appBackdrop, backdropFrozenProvider, appMotionFrozen, backdropFreezeConnection) {
        AppBackdropState(baseBg, appBackdrop, backdropFrozenProvider, appMotionFrozen, backdropFreezeConnection)
    }
}
