package dev.rovsti.design.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's icons (ORIGINAL): hand-written vectors on a 24 x 24 grid, 2 unit round strokes, drawn in black and tinted by
 * [RovstiIcon]. No icon library is a dependency. Filled glyphs (`ellipsis`, `sms`, `whatsApp`) use fills instead.
 */
object RovstiIcons {
    val ChevronRight: ImageVector by lazy {
        stroked("ChevronRight") {
            moveTo(9f, 5f)
            lineTo(16f, 12f)
            lineTo(9f, 19f)
        }
    }

    val Back: ImageVector by lazy {
        stroked("Back") {
            moveTo(15f, 5f)
            lineTo(8f, 12f)
            lineTo(15f, 19f)
        }
    }

    val Plus: ImageVector by lazy {
        stroked("Plus") {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
    }

    val Xmark: ImageVector by lazy {
        stroked("Xmark") {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    val Checkmark: ImageVector by lazy {
        stroked("Checkmark") {
            moveTo(5f, 12.5f)
            lineTo(10f, 17.5f)
            lineTo(19f, 7f)
        }
    }

    val Search: ImageVector by lazy {
        stroked("Search") {
            ring(10.5f, 10.5f, 6f)
            moveTo(15f, 15f)
            lineTo(20f, 20f)
        }
    }

    val Ellipsis: ImageVector by lazy {
        filled("Ellipsis") {
            disc(5f, 12f, 1.7f)
            disc(12f, 12f, 1.7f)
            disc(19f, 12f, 1.7f)
        }
    }

    val Trash: ImageVector by lazy {
        stroked("Trash") {
            moveTo(4f, 7f)
            lineTo(20f, 7f)
            moveTo(9f, 7f)
            lineTo(9f, 4.5f)
            lineTo(15f, 4.5f)
            lineTo(15f, 7f)
            moveTo(6f, 7f)
            lineTo(7f, 19f)
            quadTo(7.1f, 20.5f, 8.6f, 20.5f)
            lineTo(15.4f, 20.5f)
            quadTo(16.9f, 20.5f, 17f, 19f)
            lineTo(18f, 7f)
            moveTo(10f, 11f)
            lineTo(10f, 16.5f)
            moveTo(14f, 11f)
            lineTo(14f, 16.5f)
        }
    }

    val Phone: ImageVector by lazy {
        stroked("Phone") {
            moveTo(5f, 3.5f)
            horizontalLineTo(9f)
            lineTo(10.6f, 8f)
            lineTo(8.6f, 9.6f)
            curveTo(9.6f, 11.9f, 11.6f, 13.9f, 14.4f, 15.4f)
            lineTo(16f, 13.4f)
            lineTo(20.5f, 15f)
            verticalLineTo(19f)
            arcTo(1.5f, 1.5f, 0f, false, true, 19f, 20.5f)
            curveTo(10.7f, 20.5f, 3.5f, 13.3f, 3.5f, 5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 5f, 3.5f)
            close()
        }
    }

    val Message: ImageVector by lazy { stroked("Message") { bubble() } }

    val Person: ImageVector by lazy {
        stroked("Person") {
            ring(12f, 8f, 3.6f)
            moveTo(5f, 20f)
            curveTo(5f, 15.8f, 8f, 14.2f, 12f, 14.2f)
            curveTo(16f, 14.2f, 19f, 15.8f, 19f, 20f)
        }
    }

    /** Two people, the front one whole and the back one partly hidden: the group glyph. */
    val People: ImageVector by lazy {
        stroked("People") {
            ring(9f, 8.5f, 3f)
            moveTo(3f, 19.5f)
            curveTo(3f, 16.2f, 5.5f, 14.7f, 9f, 14.7f)
            curveTo(12.5f, 14.7f, 15f, 16.2f, 15f, 19.5f)
            moveTo(15.2f, 5.7f)
            arcTo(2.7f, 2.7f, 0f, true, true, 15.2f, 11.2f)
            moveTo(17.5f, 14.8f)
            curveTo(19.6f, 15.2f, 21f, 16.4f, 21f, 18.8f)
        }
    }

    val Map: ImageVector by lazy {
        stroked("Map") {
            moveTo(3f, 6.5f)
            lineTo(9f, 4f)
            lineTo(15f, 6.5f)
            lineTo(21f, 4f)
            lineTo(21f, 17.5f)
            lineTo(15f, 20f)
            lineTo(9f, 17.5f)
            lineTo(3f, 20f)
            close()
            moveTo(9f, 4f)
            lineTo(9f, 17.5f)
            moveTo(15f, 6.5f)
            lineTo(15f, 20f)
        }
    }

    /** Eight teeth computed on a circle, with the hub. */
    val Gear: ImageVector by lazy {
        stroked("Gear") {
            val teeth = 8
            val steps = teeth * 4
            for (i in 0 until steps) {
                val outer = (i / 2) % 2 == 0
                val angle = (i - 0.5f) * (2f * Math.PI.toFloat() / steps)
                val r = if (outer) 9f else 7f
                val x = 12f + r * cos(angle)
                val y = 12f + r * sin(angle)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            ring(12f, 12f, 3f)
        }
    }

    /** Filled speech bubble: the SMS glyph. */
    val Sms: ImageVector by lazy { filled("Sms") { bubble() } }

    /** Filled round bubble with a tail and a handset cut out of it: the WhatsApp glyph. */
    val WhatsApp: ImageVector by lazy {
        filled("WhatsApp", PathFillType.EvenOdd) {
            // One contour for circle plus tail, one for the handset: even-odd leaves the handset as a hole.
            moveTo(12f, 2f)
            arcTo(10f, 10f, 0f, true, true, 7.6f, 20.9f)
            lineTo(2.5f, 22f)
            lineTo(3.9f, 17.1f)
            arcTo(10f, 10f, 0f, false, true, 12f, 2f)
            close()
            moveTo(8.2f, 7f)
            lineTo(10.2f, 7f)
            lineTo(11f, 9.4f)
            lineTo(10f, 10.4f)
            curveTo(10.7f, 11.8f, 12.2f, 13.3f, 13.6f, 14f)
            lineTo(14.6f, 13f)
            lineTo(17f, 13.8f)
            lineTo(17f, 15.8f)
            curveTo(17f, 16.5f, 16.4f, 17f, 15.8f, 17f)
            curveTo(11.4f, 16.7f, 7.3f, 12.6f, 7f, 8.2f)
            curveTo(7f, 7.6f, 7.5f, 7f, 8.2f, 7f)
            close()
        }
    }

    private fun PathBuilder.bubble() {
        moveTo(6f, 4f)
        horizontalLineToRelative(12f)
        arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
        verticalLineToRelative(7f)
        arcToRelative(3f, 3f, 0f, false, true, -3f, 3f)
        horizontalLineToRelative(-6f)
        lineToRelative(-5f, 4f)
        verticalLineToRelative(-4f)
        horizontalLineTo(6f)
        arcToRelative(3f, 3f, 0f, false, true, -3f, -3f)
        verticalLineTo(7f)
        arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
        close()
    }

    private fun PathBuilder.ring(
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    private fun PathBuilder.disc(
        cx: Float,
        cy: Float,
        r: Float,
    ) = ring(cx, cy, r)

    private fun stroked(
        name: String,
        block: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            ).build()

    private fun filled(
        name: String,
        fillType: PathFillType = PathFillType.NonZero,
        block: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black), pathFillType = fillType, pathBuilder = block)
            .build()
}
