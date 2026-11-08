package dev.rovsti.design.icons

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.theme.RovstiTheme

/**
 * Draws an [icon] tinted [tint]. With a [contentDescription] it is announced; with `null` it is decorative (the usual
 * case inside a row or button, whose own node carries the label).
 */
@Composable
fun RovstiIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = RovstiTheme.colors.labelPrimary,
    size: Dp = DefaultIconSize,
) {
    val painter = rememberVectorPainter(icon)
    val described =
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    androidx.compose.foundation.layout.Box(
        modifier
            .size(size)
            .then(described)
            .paint(painter, colorFilter = ColorFilter.tint(tint), contentScale = ContentScale.Fit),
    )
}

val DefaultIconSize: Dp = 24.dp
