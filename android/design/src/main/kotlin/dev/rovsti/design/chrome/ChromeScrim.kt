/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/ChromeScrim.kt
 * Changes for Rovsti: package dev.rovsti.design.chrome; AppBarHeight became TopBarContentHeight (FrostedTopBar.kt).
 */

package dev.rovsti.design.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Hero-header scroll progress 0..1 feeding [ChromeScrim] intensity. Shared by
 * Artist/Album/Playlist screens so back/share buttons stay legible over content.
 * Returns a [State] so callers hand the holder through; the value is read only
 * in the draw phase (F1: no per-frame composition read while scrolling).
 */
@Composable
fun rememberChromeScrimProgress(
    lazyListState: LazyListState,
    thresholdPx: Float = 300f,
): State<Float> =
    remember {
        derivedStateOf {
            val scrolledPx =
                if (lazyListState.firstVisibleItemIndex == 0) {
                    lazyListState.firstVisibleItemScrollOffset.toFloat()
                } else {
                    thresholdPx
                }
            (scrolledPx / thresholdPx).coerceIn(0f, 1f)
        }
    }

/**
 * Progressive dark scrim behind hero back/share chrome, fading to dark once scrolled
 * past the art. Bottom gradient stop must stay [Color.Transparent] or a visible line
 * appears at the box's own bounds.
 *
 * Brush alphas are the full-intensity stops; [graphicsLayer] applies progress as a
 * single layer alpha so the scroll value is never read in composition.
 */
@Composable
fun ChromeScrim(
    progress: State<Float>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(TopBarContentHeight * 2.5f)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        0.35f to Color.Black.copy(alpha = 0.4f),
                        0.7f to Color.Black.copy(alpha = 0.12f),
                        1f to Color.Transparent,
                    ),
                ).graphicsLayer { alpha = progress.value },
    )
}
