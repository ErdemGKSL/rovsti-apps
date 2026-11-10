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

    val ArrowDown: ImageVector by lazy {
        stroked("ArrowDown") {
            moveTo(12f, 5f)
            lineTo(12f, 18.5f)
            moveTo(6f, 13f)
            lineTo(12f, 19f)
            lineTo(18f, 13f)
        }
    }

    /** Three bars: the drag handle of a row that can be reordered. */
    val Reorder: ImageVector by lazy {
        stroked("Reorder") {
            moveTo(5f, 8f)
            lineTo(19f, 8f)
            moveTo(5f, 12f)
            lineTo(19f, 12f)
            moveTo(5f, 16f)
            lineTo(19f, 16f)
        }
    }

    /** A calendar page with a plus: Add to Calendar. */
    val CalendarPlus: ImageVector by lazy {
        stroked("CalendarPlus") {
            moveTo(6.5f, 4.5f)
            lineTo(17.5f, 4.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 20f, 7f)
            lineTo(20f, 17.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 17.5f, 20f)
            lineTo(6.5f, 20f)
            arcTo(2.5f, 2.5f, 0f, false, true, 4f, 17.5f)
            lineTo(4f, 7f)
            arcTo(2.5f, 2.5f, 0f, false, true, 6.5f, 4.5f)
            close()
            moveTo(4f, 9.5f)
            lineTo(20f, 9.5f)
            moveTo(8f, 2.5f)
            lineTo(8f, 6.5f)
            moveTo(16f, 2.5f)
            lineTo(16f, 6.5f)
            moveTo(12f, 12.5f)
            lineTo(12f, 17f)
            moveTo(9.75f, 14.75f)
            lineTo(14.25f, 14.75f)
        }
    }

    /** A person with a plus: Add Members. */
    val PersonAdd: ImageVector by lazy {
        stroked("PersonAdd") {
            ring(9.5f, 8f, 3.5f)
            moveTo(3f, 20f)
            arcTo(6.5f, 6.5f, 0f, false, true, 13f, 15.5f)
            moveTo(18f, 9f)
            lineTo(18f, 15f)
            moveTo(15f, 12f)
            lineTo(21f, 12f)
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

    val ArrowUp: ImageVector by lazy {
        stroked("ArrowUp") {
            moveTo(12f, 19f)
            lineTo(12f, 5.5f)
            moveTo(6f, 11f)
            lineTo(12f, 5f)
            lineTo(18f, 11f)
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

    /** A box with an arrow leaving its top: share. */
    val Share: ImageVector by lazy {
        stroked("Share") {
            moveTo(12f, 15f)
            lineTo(12f, 3.5f)
            moveTo(8f, 7.5f)
            lineTo(12f, 3.5f)
            lineTo(16f, 7.5f)
            moveTo(8f, 10f)
            lineTo(6.5f, 10f)
            quadTo(5f, 10f, 5f, 11.5f)
            lineTo(5f, 18.5f)
            quadTo(5f, 20f, 6.5f, 20f)
            lineTo(17.5f, 20f)
            quadTo(19f, 20f, 19f, 18.5f)
            lineTo(19f, 11.5f)
            quadTo(19f, 10f, 17.5f, 10f)
            lineTo(16f, 10f)
        }
    }

    /** An arrow into a tray: save to the device. */
    val Download: ImageVector by lazy {
        stroked("Download") {
            moveTo(12f, 4f)
            lineTo(12f, 15f)
            moveTo(7.5f, 10.5f)
            lineTo(12f, 15f)
            lineTo(16.5f, 10.5f)
            moveTo(5f, 15.5f)
            lineTo(5f, 18f)
            quadTo(5f, 19.5f, 6.5f, 19.5f)
            lineTo(17.5f, 19.5f)
            quadTo(19f, 19.5f, 19f, 18f)
            lineTo(19f, 15.5f)
        }
    }

    /** An arrow curving right: forward. */
    val Forward: ImageVector by lazy {
        stroked("Forward") {
            moveTo(20f, 11f)
            lineTo(14f, 5f)
            moveTo(20f, 11f)
            lineTo(14f, 17f)
            moveTo(20f, 11f)
            lineTo(10f, 11f)
            quadTo(4.5f, 11f, 4.5f, 17f)
            lineTo(4.5f, 19f)
        }
    }

    /** A pencil: edit. */
    val Pencil: ImageVector by lazy {
        stroked("Pencil") {
            moveTo(5f, 19f)
            lineTo(5.8f, 15.2f)
            lineTo(15.6f, 5.4f)
            quadTo(16.5f, 4.5f, 17.4f, 5.4f)
            lineTo(18.6f, 6.6f)
            quadTo(19.5f, 7.5f, 18.6f, 8.4f)
            lineTo(8.8f, 18.2f)
            close()
            moveTo(14f, 7f)
            lineTo(17f, 10f)
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

    /** A bell with a slash: "Silence" on the Incoming Call screen. */
    val BellSlash: ImageVector by lazy {
        stroked("BellSlash") {
            moveTo(6f, 10f)
            curveTo(6f, 6.7f, 8.7f, 4.5f, 12f, 4.5f)
            curveTo(15.3f, 4.5f, 18f, 6.7f, 18f, 10f)
            verticalLineTo(14f)
            lineTo(19.5f, 17f)
            horizontalLineTo(4.5f)
            lineTo(6f, 14f)
            close()
            moveTo(10f, 19.5f)
            curveTo(10.4f, 20.4f, 11.1f, 21f, 12f, 21f)
            curveTo(12.9f, 21f, 13.6f, 20.4f, 14f, 19.5f)
            moveTo(4f, 4f)
            lineTo(20f, 20f)
        }
    }

    /** A bell: "Remind me" on the Incoming Call screen. */
    val Bell: ImageVector by lazy {
        stroked("Bell") {
            moveTo(6f, 10f)
            curveTo(6f, 6.7f, 8.7f, 4.5f, 12f, 4.5f)
            curveTo(15.3f, 4.5f, 18f, 6.7f, 18f, 10f)
            verticalLineTo(14f)
            lineTo(19.5f, 17f)
            horizontalLineTo(4.5f)
            lineTo(6f, 14f)
            close()
            moveTo(10f, 19.5f)
            curveTo(10.4f, 20.4f, 11.1f, 21f, 12f, 21f)
            curveTo(12.9f, 21f, 13.6f, 20.4f, 14f, 19.5f)
        }
    }

    /** The delete key of the dial pad: a tag pointing left with a cross in it. */
    val Backspace: ImageVector by lazy {
        stroked("Backspace") {
            moveTo(21f, 6f)
            horizontalLineTo(9f)
            lineTo(3f, 12f)
            lineTo(9f, 18f)
            horizontalLineTo(21f)
            close()
            moveTo(12.5f, 9.5f)
            lineTo(17.5f, 14.5f)
            moveTo(17.5f, 9.5f)
            lineTo(12.5f, 14.5f)
        }
    }

    /** Voicemail: the two reels of a tape. */
    val Voicemail: ImageVector by lazy {
        stroked("Voicemail") {
            ring(7f, 12f, 3.5f)
            ring(17f, 12f, 3.5f)
            moveTo(7f, 15.5f)
            horizontalLineTo(17f)
        }
    }

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

    /** A five-point star computed on two radii: the Favorites glyph. */
    val Star: ImageVector by lazy {
        stroked("Star") {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 9f else 4.1f
                val angle = (-90f + i * 36f) * (Math.PI.toFloat() / 180f)
                val x = 12f + r * cos(angle)
                val y = 12.6f + r * sin(angle)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    /** The Star filled: a favorite contact (the outline is [Star]). */
    val StarFilled: ImageVector by lazy {
        filled("StarFilled") {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 9.5f else 4.4f
                val angle = (-90f + i * 36f) * (Math.PI.toFloat() / 180f)
                val x = 12f + r * cos(angle)
                val y = 12.6f + r * sin(angle)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    /** An envelope: the Email glyph. */
    val Mail: ImageVector by lazy {
        stroked("Mail") {
            moveTo(5f, 5.5f)
            lineTo(19f, 5.5f)
            arcTo(2f, 2f, 0f, false, true, 21f, 7.5f)
            lineTo(21f, 16.5f)
            arcTo(2f, 2f, 0f, false, true, 19f, 18.5f)
            lineTo(5f, 18.5f)
            arcTo(2f, 2f, 0f, false, true, 3f, 16.5f)
            lineTo(3f, 7.5f)
            arcTo(2f, 2f, 0f, false, true, 5f, 5.5f)
            close()
            moveTo(3.6f, 7.2f)
            lineTo(12f, 13f)
            lineTo(20.4f, 7.2f)
        }
    }

    /** Two overlapping sheets: Copy. */
    val Copy: ImageVector by lazy {
        stroked("Copy") {
            moveTo(9f, 8f)
            lineTo(18f, 8f)
            arcTo(1.5f, 1.5f, 0f, false, true, 19.5f, 9.5f)
            lineTo(19.5f, 19f)
            arcTo(1.5f, 1.5f, 0f, false, true, 18f, 20.5f)
            lineTo(9f, 20.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 7.5f, 19f)
            lineTo(7.5f, 9.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 9f, 8f)
            close()
            moveTo(4.5f, 15f)
            lineTo(4.5f, 5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 6f, 3.5f)
            lineTo(14f, 3.5f)
        }
    }

    /** A rounded square with a plus: Add to Home Screen. */
    val AddToHome: ImageVector by lazy {
        stroked("AddToHome") {
            moveTo(5f, 4.5f)
            lineTo(19f, 4.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 20.5f, 6f)
            lineTo(20.5f, 18f)
            arcTo(1.5f, 1.5f, 0f, false, true, 19f, 19.5f)
            lineTo(5f, 19.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 3.5f, 18f)
            lineTo(3.5f, 6f)
            arcTo(1.5f, 1.5f, 0f, false, true, 5f, 4.5f)
            close()
            moveTo(12f, 8.5f)
            lineTo(12f, 15.5f)
            moveTo(8.5f, 12f)
            lineTo(15.5f, 12f)
        }
    }

    /** Three finder squares: a QR code. */
    val QrCode: ImageVector by lazy {
        stroked("QrCode") {
            moveTo(4f, 4f)
            lineTo(10f, 4f)
            lineTo(10f, 10f)
            lineTo(4f, 10f)
            close()
            moveTo(14f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 10f)
            lineTo(14f, 10f)
            close()
            moveTo(4f, 14f)
            lineTo(10f, 14f)
            lineTo(10f, 20f)
            lineTo(4f, 20f)
            close()
            moveTo(14f, 14f)
            lineTo(14f, 14.5f)
            moveTo(17f, 14f)
            lineTo(20f, 14f)
            moveTo(14f, 17.5f)
            lineTo(17.5f, 17.5f)
            lineTo(17.5f, 20f)
            moveTo(20f, 17f)
            lineTo(20f, 20f)
        }
    }

    /** A globe: a website. */
    val Globe: ImageVector by lazy {
        stroked("Globe") {
            ring(12f, 12f, 8.5f)
            moveTo(3.5f, 12f)
            lineTo(20.5f, 12f)
            moveTo(12f, 3.5f)
            curveTo(8.5f, 7.5f, 8.5f, 16.5f, 12f, 20.5f)
            moveTo(12f, 3.5f)
            curveTo(15.5f, 7.5f, 15.5f, 16.5f, 12f, 20.5f)
        }
    }

    /** A folder with its tab on the left: the Groups glyph. */
    val Folder: ImageVector by lazy {
        stroked("Folder") {
            moveTo(3f, 7.5f)
            curveTo(3f, 6.4f, 3.9f, 5.5f, 5f, 5.5f)
            lineTo(9.2f, 5.5f)
            lineTo(11.5f, 8f)
            lineTo(19f, 8f)
            curveTo(20.1f, 8f, 21f, 8.9f, 21f, 10f)
            lineTo(21f, 17.5f)
            curveTo(21f, 18.6f, 20.1f, 19.5f, 19f, 19.5f)
            lineTo(5f, 19.5f)
            curveTo(3.9f, 19.5f, 3f, 18.6f, 3f, 17.5f)
            close()
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

    /** A circle with its right half filled with hatching strokes: the light/dark glyph of Appearance. */
    val Appearance: ImageVector by lazy {
        stroked("Appearance") {
            ring(12f, 12f, 8.5f)
            moveTo(12f, 3.5f)
            lineTo(12f, 20.5f)
            moveTo(15f, 7f)
            lineTo(15f, 17f)
            moveTo(18f, 9.5f)
            lineTo(18f, 14.5f)
        }
    }

    /** A handset outline with a vibration arc on each side: the haptics glyph. */
    val Haptics: ImageVector by lazy {
        stroked("Haptics") {
            moveTo(9.5f, 4f)
            horizontalLineTo(14.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 16f, 5.5f)
            verticalLineTo(18.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 14.5f, 20f)
            horizontalLineTo(9.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 8f, 18.5f)
            verticalLineTo(5.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 9.5f, 4f)
            close()
            moveTo(4.5f, 9f)
            lineTo(4.5f, 15f)
            moveTo(19.5f, 9f)
            lineTo(19.5f, 15f)
        }
    }

    /** A circle with a lower-case i: the information glyph. */
    val Info: ImageVector by lazy {
        stroked("Info") {
            ring(12f, 12f, 9f)
            moveTo(12f, 11f)
            lineTo(12f, 16.5f)
            moveTo(12f, 7.6f)
            lineTo(12f, 7.7f)
        }
    }

    /** A page with a folded corner and two text lines: the document glyph. */
    val Document: ImageVector by lazy {
        stroked("Document") {
            moveTo(6.5f, 3.5f)
            horizontalLineTo(13.5f)
            lineTo(18.5f, 8.5f)
            verticalLineTo(19.5f)
            arcTo(1f, 1f, 0f, false, true, 17.5f, 20.5f)
            horizontalLineTo(6.5f)
            arcTo(1f, 1f, 0f, false, true, 5.5f, 19.5f)
            verticalLineTo(4.5f)
            arcTo(1f, 1f, 0f, false, true, 6.5f, 3.5f)
            close()
            moveTo(13f, 3.5f)
            lineTo(13f, 9f)
            lineTo(18.5f, 9f)
            moveTo(8.5f, 13f)
            lineTo(15.5f, 13f)
            moveTo(8.5f, 16.5f)
            lineTo(13f, 16.5f)
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

    /** A picture frame with a hill and a sun: choose a photo. */
    val Photo: ImageVector by lazy {
        stroked("Photo") {
            moveTo(6.5f, 4.5f)
            lineTo(17.5f, 4.5f)
            quadTo(20f, 4.5f, 20f, 7f)
            lineTo(20f, 17f)
            quadTo(20f, 19.5f, 17.5f, 19.5f)
            lineTo(6.5f, 19.5f)
            quadTo(4f, 19.5f, 4f, 17f)
            lineTo(4f, 7f)
            quadTo(4f, 4.5f, 6.5f, 4.5f)
            close()
            moveTo(4.5f, 16.5f)
            lineTo(9f, 12f)
            lineTo(13f, 16f)
            lineTo(15.5f, 13.5f)
            lineTo(19.5f, 17.5f)
            ring(15.5f, 9f, 1.4f)
        }
    }

    /** A camera body with a lens: take a photo. */
    val Camera: ImageVector by lazy {
        stroked("Camera") {
            moveTo(5f, 8f)
            lineTo(8f, 8f)
            lineTo(9.5f, 5.5f)
            lineTo(14.5f, 5.5f)
            lineTo(16f, 8f)
            lineTo(19f, 8f)
            quadTo(21f, 8f, 21f, 10f)
            lineTo(21f, 17f)
            quadTo(21f, 19f, 19f, 19f)
            lineTo(5f, 19f)
            quadTo(3f, 19f, 3f, 17f)
            lineTo(3f, 10f)
            quadTo(3f, 8f, 5f, 8f)
            close()
            ring(12f, 13f, 3.4f)
        }
    }

    /** An arrow bending round a quarter circle: rotate a quarter turn clockwise. */
    val Rotate: ImageVector by lazy {
        stroked("Rotate") {
            moveTo(19.5f, 12f)
            arcToRelative(7.5f, 7.5f, 0f, true, true, -2.2f, -5.3f)
            moveTo(17.5f, 3.5f)
            lineTo(17.3f, 6.7f)
            lineTo(20.5f, 7f)
        }
    }

    /** A clock face: "Recently Added" and other time rows. */
    val Clock: ImageVector by lazy {
        stroked("Clock") {
            ring(12f, 12f, 8.5f)
            moveTo(12f, 7.5f)
            verticalLineTo(12f)
            lineTo(15f, 14f)
        }
    }

    /** A microphone: Mute on the In-Call screen. */
    val Mic: ImageVector by lazy {
        stroked("Mic") {
            moveTo(9.5f, 6f)
            curveTo(9.5f, 4.6f, 10.6f, 3.5f, 12f, 3.5f)
            curveTo(13.4f, 3.5f, 14.5f, 4.6f, 14.5f, 6f)
            verticalLineTo(11f)
            curveTo(14.5f, 12.4f, 13.4f, 13.5f, 12f, 13.5f)
            curveTo(10.6f, 13.5f, 9.5f, 12.4f, 9.5f, 11f)
            close()
            moveTo(6.5f, 10.5f)
            curveTo(6.5f, 13.5f, 8.8f, 16f, 12f, 16f)
            curveTo(15.2f, 16f, 17.5f, 13.5f, 17.5f, 10.5f)
            moveTo(12f, 16f)
            verticalLineTo(20f)
        }
    }

    /** A microphone with a stroke through it: the call is muted. */
    val MicSlash: ImageVector by lazy {
        stroked("MicSlash") {
            moveTo(9.5f, 6f)
            curveTo(9.5f, 4.6f, 10.6f, 3.5f, 12f, 3.5f)
            curveTo(13.4f, 3.5f, 14.5f, 4.6f, 14.5f, 6f)
            verticalLineTo(11f)
            curveTo(14.5f, 12.4f, 13.4f, 13.5f, 12f, 13.5f)
            curveTo(10.6f, 13.5f, 9.5f, 12.4f, 9.5f, 11f)
            close()
            moveTo(6.5f, 10.5f)
            curveTo(6.5f, 13.5f, 8.8f, 16f, 12f, 16f)
            curveTo(15.2f, 16f, 17.5f, 13.5f, 17.5f, 10.5f)
            moveTo(12f, 16f)
            verticalLineTo(20f)
            moveTo(4f, 4f)
            lineTo(20f, 20f)
        }
    }

    /** A loudspeaker with sound waves: the speaker route. */
    val Speaker: ImageVector by lazy {
        stroked("Speaker") {
            moveTo(4f, 9.5f)
            horizontalLineTo(7.5f)
            lineTo(12f, 5.5f)
            verticalLineTo(18.5f)
            lineTo(7.5f, 14.5f)
            horizontalLineTo(4f)
            close()
            moveTo(15.5f, 9f)
            curveTo(16.5f, 10f, 16.5f, 14f, 15.5f, 15f)
            moveTo(18f, 6.5f)
            curveTo(20.5f, 9.5f, 20.5f, 14.5f, 18f, 17.5f)
        }
    }

    /** Two vertical bars: Hold. */
    val Pause: ImageVector by lazy {
        stroked("Pause") {
            moveTo(9f, 6f)
            verticalLineTo(18f)
            moveTo(15f, 6f)
            verticalLineTo(18f)
        }
    }

    /** The Bluetooth rune: a Bluetooth route. */
    val Bluetooth: ImageVector by lazy {
        stroked("Bluetooth") {
            moveTo(7f, 8f)
            lineTo(17f, 16f)
            lineTo(12f, 20f)
            verticalLineTo(4f)
            lineTo(17f, 8f)
            lineTo(7f, 16f)
        }
    }

    /** Headphones: a wired headset route. */
    val Headphones: ImageVector by lazy {
        stroked("Headphones") {
            moveTo(4.5f, 15f)
            verticalLineTo(12f)
            curveTo(4.5f, 7.9f, 7.9f, 4.5f, 12f, 4.5f)
            curveTo(16.1f, 4.5f, 19.5f, 7.9f, 19.5f, 12f)
            verticalLineTo(15f)
            moveTo(4.5f, 14f)
            horizontalLineTo(7.5f)
            verticalLineTo(19f)
            horizontalLineTo(5.5f)
            curveTo(4.9f, 19f, 4.5f, 18.6f, 4.5f, 18f)
            close()
            moveTo(19.5f, 14f)
            horizontalLineTo(16.5f)
            verticalLineTo(19f)
            horizontalLineTo(18.5f)
            curveTo(19.1f, 19f, 19.5f, 18.6f, 19.5f, 18f)
            close()
        }
    }

    /** Nine dots in a 3 x 3 grid: the Keypad tab (Phone). */
    val Keypad: ImageVector by lazy {
        filled("Keypad") {
            for (row in 0..2) for (col in 0..2) disc(5.5f + col * 6.5f, 5.5f + row * 6.5f, 1.8f)
        }
    }

    /** A shield: the Setup button and the "not your phone app" line. */
    val Shield: ImageVector by lazy {
        stroked("Shield") {
            moveTo(12f, 3f)
            lineTo(19.5f, 5.8f)
            verticalLineTo(11.5f)
            curveTo(19.5f, 16f, 16.4f, 19.4f, 12f, 21f)
            curveTo(7.6f, 19.4f, 4.5f, 16f, 4.5f, 11.5f)
            verticalLineTo(5.8f)
            close()
        }
    }

    /** A shield with a tick: setup is complete. */
    val ShieldCheck: ImageVector by lazy {
        stroked("ShieldCheck") {
            moveTo(12f, 3f)
            lineTo(19.5f, 5.8f)
            verticalLineTo(11.5f)
            curveTo(19.5f, 16f, 16.4f, 19.4f, 12f, 21f)
            curveTo(7.6f, 19.4f, 4.5f, 16f, 4.5f, 11.5f)
            verticalLineTo(5.8f)
            close()
            moveTo(8.8f, 12.2f)
            lineTo(11.2f, 14.6f)
            lineTo(15.4f, 9.8f)
        }
    }

    /** An empty circle: a checklist row that is not done. */
    val Circle: ImageVector by lazy {
        stroked("Circle") { ring(12f, 12f, 8.5f) }
    }

    /** A circle with a tick: a checklist row that is done. */
    val CircleCheck: ImageVector by lazy {
        stroked("CircleCheck") {
            ring(12f, 12f, 8.5f)
            moveTo(8.3f, 12.3f)
            lineTo(10.9f, 14.9f)
            lineTo(15.8f, 9.4f)
        }
    }

    /** A medical cross in a rounded square: the emergency list. */
    val MedicalCross: ImageVector by lazy {
        stroked("MedicalCross") {
            moveTo(12f, 6.5f)
            verticalLineTo(17.5f)
            moveTo(6.5f, 12f)
            horizontalLineTo(17.5f)
            moveTo(7f, 3.5f)
            horizontalLineTo(17f)
            arcTo(3.5f, 3.5f, 0f, false, true, 20.5f, 7f)
            verticalLineTo(17f)
            arcTo(3.5f, 3.5f, 0f, false, true, 17f, 20.5f)
            horizontalLineTo(7f)
            arcTo(3.5f, 3.5f, 0f, false, true, 3.5f, 17f)
            verticalLineTo(7f)
            arcTo(3.5f, 3.5f, 0f, false, true, 7f, 3.5f)
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
