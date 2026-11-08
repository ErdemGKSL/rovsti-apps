package dev.rovsti.design.overlay

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

/** Where a morphing card ended up. Plain fields written during layout and read during draw: no state, no recomposition. */
internal class CardGeometry {
    var trailing = false
    var upward = false

    /** The card's top-left corner in window pixels, its size, and its padding: what the finger handling maps with. */
    var left = 0f
    var top = 0f
    var width = 0f
    var height = 0f
    var padding = 0f
}

/** Corner radius of the growing glass: from half the trigger's short side (a capsule) to the card's own radius. */
internal fun surfaceRadius(
    from: Size,
    cardCornerPx: Float,
    t: Float,
): Float {
    val capsule = min(from.width, from.height) / 2f
    return capsule + (cardCornerPx - capsule) * t.coerceIn(0f, 1f)
}

/** A rounded rectangle at an arbitrary rect of the layer, used as the animated clip of the surface and the rows. */
internal class SurfaceShape(
    private val left: Float,
    private val top: Float,
    private val width: Float,
    private val height: Float,
    private val radius: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = max(0f, min(radius, min(width, height) / 2f))
        return Outline.Rounded(RoundRect(left, top, left + max(0f, width), top + max(0f, height), CornerRadius(r)))
    }
}
