// Rosty Project (C) 2026, GPL-3.0-or-later. Original code.
package dev.rosty.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * A head-and-shoulders portrait drawn in code, for tests that need a picture which reads as a photo of a person (no
 * binary assets). The person (skin, hair, shirt, wall pattern) is a pure function of the text of [data], so each
 * contact gets its own face and the same contact always gets the same one.
 *
 * Unlike [gradientImage], the picture has structure: a patterned wall with bokeh lights, shoulders, neck, ears, hair,
 * eyes, brows, nose and mouth, and a vignette. A screenshot of it cannot be mistaken for an aura or a tinted fill.
 */
fun portraitImage(
    data: Any,
    widthPx: Int,
    heightPx: Int,
): Bitmap {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val seed = data.toString().hashCode()

    fun pick(
        shift: Int,
        n: Int,
    ) = ((seed ushr shift) and 0xFFFF).mod(n)

    val skin = SKINS[pick(0, SKINS.size)]
    val hair = HAIRS[pick(3, HAIRS.size)]
    val shirt = SHIRTS[pick(5, SHIRTS.size)]
    val wall = WALLS[pick(7, WALLS.size)]
    val style = pick(11, 4)
    val pattern = pick(13, 3)

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val fw = w.toFloat()
    val fh = h.toFloat()
    val unit = minOf(fw, fh)
    val cx = fw * 0.5f

    // Wall: a vertical gradient with a pattern so it reads as a room, not a fill.
    p.shader = LinearGradient(0f, 0f, 0f, fh, wall.first, wall.second, Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, fw, fh, p)
    p.shader = null
    when (pattern) {
        0 -> {
            // Wooden panels: vertical boards with thin seams.
            p.color = Color.argb(46, 0, 0, 0)
            p.strokeWidth = maxOf(1f, unit * 0.012f)
            var x = 0f
            while (x < fw) {
                c.drawLine(x, 0f, x, fh, p)
                x += unit * 0.2f
            }
        }

        1 -> {
            // Bokeh: soft out-of-focus lights.
            for (i in 0 until 9) {
                val bx = fw * (((seed ushr (i * 3)) and 0xFF) / 255f)
                val by = fh * (0.05f + 0.5f * (((seed ushr (i * 2 + 1)) and 0xFF) / 255f))
                val r = unit * (0.05f + 0.07f * ((i * 37 % 10) / 10f))
                p.color = Color.argb(70, 255, 245, 210)
                c.drawCircle(bx, by, r, p)
            }
        }

        else -> {
            // A window with a cross frame on one side.
            val win = RectF(fw * 0.04f, fh * 0.08f, fw * 0.36f, fh * 0.46f)
            p.color = Color.argb(150, 235, 245, 255)
            c.drawRect(win, p)
            p.color = Color.argb(200, 250, 250, 250)
            p.strokeWidth = maxOf(2f, unit * 0.02f)
            c.drawLine(win.centerX(), win.top, win.centerX(), win.bottom, p)
            c.drawLine(win.left, win.centerY(), win.right, win.centerY(), p)
        }
    }

    val headR = unit * 0.2f
    val headCy = fh * 0.42f
    val shoulderTop = fh * 0.7f

    // Shoulders and shirt.
    p.color = shirt
    val body = Path()
    body.moveTo(fw * -0.02f, fh)
    body.cubicTo(
        fw * 0.02f,
        shoulderTop + fh * 0.02f,
        fw * 0.22f,
        shoulderTop - fh * 0.03f,
        cx - headR * 0.9f,
        shoulderTop - fh * 0.02f,
    )
    body.lineTo(cx + headR * 0.9f, shoulderTop - fh * 0.02f)
    body.cubicTo(fw * 0.78f, shoulderTop - fh * 0.03f, fw * 0.98f, shoulderTop + fh * 0.02f, fw * 1.02f, fh)
    body.close()
    c.drawPath(body, p)

    // Neck and collar.
    p.color = shade(skin, 0.86f)
    c.drawRect(cx - headR * 0.38f, headCy + headR * 0.5f, cx + headR * 0.38f, shoulderTop, p)
    p.color = skin
    val v = Path()
    v.moveTo(cx - headR * 0.55f, shoulderTop - fh * 0.02f)
    v.lineTo(cx, shoulderTop + headR * 0.55f)
    v.lineTo(cx + headR * 0.55f, shoulderTop - fh * 0.02f)
    v.close()
    c.drawPath(v, p)

    // Hair behind the head (long styles), ears.
    p.color = hair
    if (style >= 2) {
        c.drawRoundRect(
            RectF(cx - headR * 1.2f, headCy - headR * 1.0f, cx + headR * 1.2f, headCy + headR * 1.9f),
            headR * 0.9f,
            headR * 0.9f,
            p,
        )
    }
    p.color = shade(skin, 0.92f)
    c.drawOval(RectF(cx - headR * 1.12f, headCy - headR * 0.2f, cx - headR * 0.85f, headCy + headR * 0.3f), p)
    c.drawOval(RectF(cx + headR * 0.85f, headCy - headR * 0.2f, cx + headR * 1.12f, headCy + headR * 0.3f), p)

    // Head, lit from the upper left.
    p.shader =
        RadialGradient(
            cx - headR * 0.35f,
            headCy - headR * 0.4f,
            headR * 1.6f,
            skin,
            shade(skin, 0.8f),
            Shader.TileMode.CLAMP,
        )
    c.drawOval(RectF(cx - headR * 0.92f, headCy - headR * 1.08f, cx + headR * 0.92f, headCy + headR * 1.08f), p)
    p.shader = null

    // Hair on top: a cap with a side parting (short) or a fringe (long).
    p.color = hair
    val cap = Path()
    cap.moveTo(cx - headR * 0.95f, headCy - headR * 0.1f)
    cap.cubicTo(
        cx - headR * 1.15f,
        headCy - headR * 1.5f,
        cx + headR * 1.15f,
        headCy - headR * 1.5f,
        cx + headR * 0.95f,
        headCy - headR * 0.1f,
    )
    cap.cubicTo(
        cx + headR * 0.7f,
        headCy - headR * 0.75f,
        cx + headR * 0.1f,
        headCy - headR * 0.85f,
        cx - headR * 0.3f,
        headCy - headR * 0.6f,
    )
    cap.cubicTo(
        cx - headR * 0.6f,
        headCy - headR * 0.5f,
        cx - headR * 0.85f,
        headCy - headR * 0.35f,
        cx - headR * 0.95f,
        headCy - headR * 0.1f,
    )
    cap.close()
    c.drawPath(cap, p)

    // Face: brows, eyes, nose, mouth.
    val eyeY = headCy - headR * 0.05f
    val eyeDx = headR * 0.38f
    p.color = Color.WHITE
    c.drawOval(
        RectF(cx - eyeDx - headR * 0.17f, eyeY - headR * 0.09f, cx - eyeDx + headR * 0.17f, eyeY + headR * 0.09f),
        p,
    )
    c.drawOval(
        RectF(cx + eyeDx - headR * 0.17f, eyeY - headR * 0.09f, cx + eyeDx + headR * 0.17f, eyeY + headR * 0.09f),
        p,
    )
    p.color = Color.rgb(44, 30, 24)
    c.drawCircle(cx - eyeDx, eyeY, headR * 0.075f, p)
    c.drawCircle(cx + eyeDx, eyeY, headR * 0.075f, p)
    p.color = hair
    p.style = Paint.Style.STROKE
    p.strokeWidth = maxOf(1.5f, headR * 0.07f)
    p.strokeCap = Paint.Cap.ROUND
    c.drawLine(cx - eyeDx - headR * 0.18f, eyeY - headR * 0.24f, cx - eyeDx + headR * 0.16f, eyeY - headR * 0.28f, p)
    c.drawLine(cx + eyeDx - headR * 0.16f, eyeY - headR * 0.28f, cx + eyeDx + headR * 0.18f, eyeY - headR * 0.24f, p)
    p.color = shade(skin, 0.78f)
    p.strokeWidth = maxOf(1.5f, headR * 0.055f)
    val nose = Path()
    nose.moveTo(cx, eyeY + headR * 0.05f)
    nose.quadTo(cx - headR * 0.1f, eyeY + headR * 0.38f, cx + headR * 0.05f, eyeY + headR * 0.4f)
    c.drawPath(nose, p)
    p.color = Color.rgb(160, 70, 70)
    p.strokeWidth = maxOf(2f, headR * 0.075f)
    val mouth = Path()
    mouth.moveTo(cx - headR * 0.3f, headCy + headR * 0.55f)
    mouth.quadTo(cx, headCy + headR * 0.78f, cx + headR * 0.3f, headCy + headR * 0.55f)
    c.drawPath(mouth, p)
    p.style = Paint.Style.FILL

    // Vignette so the edges are darker, like a camera frame.
    p.shader =
        RadialGradient(
            cx,
            fh * 0.45f,
            maxOf(fw, fh) * 0.75f,
            Color.TRANSPARENT,
            Color.argb(90, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
    c.drawRect(0f, 0f, fw, fh, p)
    return bmp
}

private fun shade(
    color: Int,
    f: Float,
) = Color.rgb(
    (Color.red(color) * f).toInt(),
    (Color.green(color) * f).toInt(),
    (Color.blue(color) * f).toInt(),
)

private val SKINS =
    intArrayOf(
        Color.rgb(246, 214, 186),
        Color.rgb(232, 184, 146),
        Color.rgb(198, 142, 104),
        Color.rgb(150, 100, 70),
        Color.rgb(104, 68, 50),
    )
private val HAIRS =
    intArrayOf(
        Color.rgb(36, 26, 22),
        Color.rgb(92, 56, 32),
        Color.rgb(176, 128, 66),
        Color.rgb(150, 150, 154),
        Color.rgb(120, 40, 30),
    )
private val SHIRTS =
    intArrayOf(
        Color.rgb(46, 84, 150),
        Color.rgb(172, 52, 60),
        Color.rgb(48, 112, 84),
        Color.rgb(236, 228, 208),
        Color.rgb(62, 58, 74),
        Color.rgb(222, 142, 40),
    )
private val WALLS =
    arrayOf(
        Color.rgb(196, 168, 128) to Color.rgb(120, 92, 66),
        Color.rgb(168, 190, 206) to Color.rgb(84, 108, 130),
        Color.rgb(206, 190, 200) to Color.rgb(120, 98, 116),
        Color.rgb(176, 198, 168) to Color.rgb(92, 120, 86),
    )
