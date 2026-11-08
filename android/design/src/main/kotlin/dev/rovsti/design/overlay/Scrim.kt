package dev.rovsti.design.overlay

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import dev.rovsti.design.theme.RovstiTheme

/** Dim strength at full progress: a light page needs less to separate an overlay than a dark one. */
object ScrimAlpha {
    const val Light = 0.20f
    const val Dark = 0.40f
}

/**
 * The dimming layer behind a modal overlay (ORIGINAL).
 *
 * It fills its parent, eats every pointer event so nothing underneath scrolls or taps while the overlay is up, and
 * reports a tap (a press that ends within touch slop) through [onTap]. [progress] is read in the draw phase only, so
 * the fade-in never recomposes.
 */
@Composable
fun OverlayScrim(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    lightAlpha: Float = ScrimAlpha.Light,
    darkAlpha: Float = ScrimAlpha.Dark,
    onTap: (() -> Unit)? = null,
) {
    val peak = if (RovstiTheme.colors.isDark) darkAlpha else lightAlpha
    val slop = LocalViewConfiguration.current.touchSlop
    Box(
        modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = (peak * progress()).coerceIn(0f, 1f))) }
            .pointerInput(onTap) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var moved = false
                    var total = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        event.changes.forEach {
                            total += it.positionChange().getDistance()
                            it.consume()
                        }
                        if (total > slop) moved = true
                        if (event.changes.none { it.pressed }) break
                    }
                    if (!moved) onTap?.invoke()
                }
            }.testTag("overlay-scrim"),
    )
}
