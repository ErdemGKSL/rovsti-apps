package dev.rovsti.design.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.theme.RovstiTheme

/** Diameter of the select circle. */
val SelectCheckSize = 24.dp

/**
 * The leading circle of a row in select mode (ORIGINAL): an empty ring that fills with the accent colour and shows a
 * checkmark when [checked]. The fill grows from the centre and the mark fades in on the `select` spring, so a quick
 * double tap reverses smoothly instead of restarting. Decorative: the row carries the selected state.
 */
@Composable
fun SelectCheck(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RovstiTheme.colors
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = RovstiMotion.select(0.001f),
        label = "select-check",
    )
    Box(modifier.size(SelectCheckSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(SelectCheckSize)) {
            val ring = 1.5.dp.toPx()
            val radius = (size.minDimension - ring) / 2f
            drawCircle(
                color = colors.labelTertiary,
                radius = radius,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(ring),
            )
            if (progress > 0f) {
                drawCircle(
                    color = colors.accent,
                    radius = (size.minDimension / 2f) * progress.coerceIn(0f, 1.1f),
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
        }
        if (progress > 0.02f) {
            RovstiIcon(
                RovstiIcons.Checkmark,
                null,
                Modifier.alpha(progress.coerceIn(0f, 1f)),
                tint = colors.onAccent,
                size = 14.dp,
            )
        }
    }
}
