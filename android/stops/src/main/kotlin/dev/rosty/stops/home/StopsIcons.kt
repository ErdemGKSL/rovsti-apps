package dev.rosty.stops.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import dev.rosty.stops.StopsSizes

/**
 * The glyphs Stops needs that `RostyIcons` lacks (ORIGINAL): the GPUI tab icons (navigation arrow, bookmark) and the
 * map buttons (locate, layers, map pin). Same grid and stroke as `RostyIcons`: 24 x 24, 2 unit round strokes, drawn in
 * black and tinted by `RostyIcon`.
 */
internal object StopsIcons {
    /** The Trip tab: the tilted navigation arrow. */
    val Navigation: ImageVector by lazy {
        stroked("Navigation") {
            moveTo(3f, 11f)
            lineTo(21f, 3f)
            lineTo(13f, 21f)
            lineTo(11f, 13f)
            close()
        }
    }

    /** The Saved tab. */
    val Bookmark: ImageVector by lazy {
        stroked("Bookmark") {
            moveTo(18f, 21f)
            lineTo(12f, 16.5f)
            lineTo(6f, 21f)
            lineTo(6f, 5f)
            curveTo(6f, 3.9f, 6.9f, 3f, 8f, 3f)
            lineTo(16f, 3f)
            curveTo(17.1f, 3f, 18f, 3.9f, 18f, 5f)
            close()
        }
    }

    /** Locate / follow: a ring with four ticks. */
    val Locate: ImageVector by lazy {
        stroked("Locate") {
            circle(12f, 12f, 6.5f)
            circle(12f, 12f, 1.5f)
            moveTo(12f, 2f)
            lineTo(12f, 5.5f)
            moveTo(12f, 18.5f)
            lineTo(12f, 22f)
            moveTo(2f, 12f)
            lineTo(5.5f, 12f)
            moveTo(18.5f, 12f)
            lineTo(22f, 12f)
        }
    }

    /** Map layers: two stacked sheets. */
    val Layers: ImageVector by lazy {
        stroked("Layers") {
            moveTo(12f, 3f)
            lineTo(21.5f, 8f)
            lineTo(12f, 13f)
            lineTo(2.5f, 8f)
            close()
            moveTo(2.5f, 12.5f)
            lineTo(12f, 17.5f)
            lineTo(21.5f, 12.5f)
            moveTo(2.5f, 16.5f)
            lineTo(12f, 21.5f)
            lineTo(21.5f, 16.5f)
        }
    }

    /** A map pin with its hole. */
    val Pin: ImageVector by lazy {
        stroked("Pin") {
            moveTo(12f, 22f)
            curveTo(12f, 22f, 4.5f, 15.2f, 4.5f, 9.5f)
            curveTo(4.5f, 5.4f, 7.9f, 2f, 12f, 2f)
            curveTo(16.1f, 2f, 19.5f, 5.4f, 19.5f, 9.5f)
            curveTo(19.5f, 15.2f, 12f, 22f, 12f, 22f)
            close()
            circle(12f, 9.5f, 2.8f)
        }
    }

    private fun PathBuilder.circle(
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun stroked(
        name: String,
        block: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(name, StopsSizes.glyphGrid, StopsSizes.glyphGrid, GRID, GRID)
            .path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            ).build()

    private const val GRID = 24f
}
