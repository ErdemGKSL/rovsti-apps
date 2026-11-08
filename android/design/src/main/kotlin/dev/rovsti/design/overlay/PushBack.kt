package dev.rovsti.design.overlay

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The page behind a modal sheet steps back (ORIGINAL; SKILL overlays 5.3): it scales to 0.91 about its top centre,
 * moves down by the status bar, takes 32 dp corners and dims by 0.22. [progress] is read in the layer and draw
 * phases only, so a dragged sheet drives it without recomposition. The black behind the shrunk page is the parent's
 * to draw ([OverlayHost] does it). Menus and popovers never push the page back.
 */
fun Modifier.sheetPushBack(
    progress: () -> Float,
    statusBarPx: Float,
    cornerPx: Float,
): Modifier =
    this
        .graphicsLayer {
            val p = progress().coerceIn(0f, 1f)
            val s = 1f - (1f - SheetMetrics.PushBackScale) * p
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(0.5f, 0f)
            translationY = statusBarPx * p
            if (p > 0f) {
                shape = RoundedCornerShape(cornerPx * p)
                clip = true
            }
        }.drawWithContent {
            drawContent()
            val p = progress().coerceIn(0f, 1f)
            if (p > 0f) {
                drawRect(Color.Black.copy(alpha = SheetMetrics.PushBackDim * p), Offset.Zero, size)
            }
        }
