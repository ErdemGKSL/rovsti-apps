package dev.rovsti.design.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import dev.rovsti.design.motion.rememberReduceMotion

/** Length of the theme melt in milliseconds. */
const val THEME_MELT_MS = 360

private val MeltEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * The theme melt (E5.14, ORIGINAL): when the system (or the preference) flips between light and dark, the new theme is
 * applied at once and a sheet of the OLD page colour is drawn over the whole window, fading out over [THEME_MELT_MS].
 * It is one draw-phase rectangle (no layout, no recomposition per frame, no touch handling), so the glass and the lists
 * under it are not disturbed. Under reduce motion the theme just switches. The first composition never melts.
 */
@Composable
fun ThemeMelt(
    dark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // The melt sits above the app's own provider, so it reads the system switch (animations off) directly.
    val reduce by rememberReduceMotion()
    val progress = remember { Animatable(0f) }
    val from = remember { arrayOf<Color>(Color.Transparent) }
    val last = remember { booleanArrayOf(dark) }
    LaunchedEffect(dark) {
        if (last[0] == dark) return@LaunchedEffect
        from[0] = (if (last[0]) DarkColors else LightColors).page
        last[0] = dark
        if (reduce) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        progress.snapTo(1f)
        progress.animateTo(0f, tween(THEME_MELT_MS, easing = MeltEasing))
    }
    Box(
        modifier.drawWithContent {
            drawContent()
            val p = progress.value
            if (p > 0f) drawRect(from[0], alpha = p)
        },
    ) {
        content()
    }
}
