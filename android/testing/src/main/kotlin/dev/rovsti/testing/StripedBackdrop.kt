package dev.rovsti.testing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

private val StripeColors =
    listOf(
        Color(0xFFE5484D),
        Color(0xFFF5A524),
        Color(0xFFF7D154),
        Color(0xFF3FB950),
        Color(0xFF2BB5B0),
        Color(0xFF2F7BEA),
        Color(0xFF6E56CF),
        Color(0xFFD6409F),
    )

/**
 * A busy backdrop for glass shots: diagonal stripes in eight colours with a soft light-to-dark
 * gradient on top. Refraction, blur and rim light are only visible over something like this.
 */
@Composable
fun StripedBackdrop(modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        val stripe = 48.dp.toPx()
        val reach = size.width + size.height
        rotate(degrees = -35f, pivot = center) {
            var x = -reach
            var index = 0
            while (x < reach) {
                drawRect(
                    color = StripeColors[index % StripeColors.size],
                    topLeft = Offset(x, -reach),
                    size = Size(stripe, reach * 2),
                )
                x += stripe
                index++
            }
        }
        drawRect(
            brush =
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                ),
        )
    }
}
