package dev.rovsti.contacts.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import dev.rovsti.testing.gradientImage
import java.io.File

/** The path the crop screenshots "pick"; the fake image loader draws [landscape] for it. */
internal const val PICTURE_PATH = "/cache/pick-landscape.jpg"

/**
 * A recognisable landscape (sky, sun at the upper right, dark hills, a faint grid every eighth) so a crop's centre,
 * zoom and turn can be read off a screenshot. Any other request gets the default gradient.
 */
internal fun pictureProducer(
    data: Any,
    widthPx: Int,
    heightPx: Int,
): Bitmap? {
    if (data !is File || data.path != PICTURE_PATH) return gradientImage(data, widthPx, heightPx)
    return landscape(widthPx, heightPx)
}

internal fun landscape(
    widthPx: Int,
    heightPx: Int,
): Bitmap {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader =
        LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            Color.rgb(120, 190, 245),
            Color.rgb(250, 225, 190),
            Shader.TileMode.CLAMP,
        )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    paint.color = Color.rgb(255, 214, 90)
    canvas.drawCircle(w * 0.72f, h * 0.28f, h * 0.12f, paint)
    paint.color = Color.rgb(46, 92, 82)
    val hills = Path()
    hills.moveTo(0f, h.toFloat())
    hills.lineTo(0f, h * 0.72f)
    hills.lineTo(w * 0.22f, h * 0.5f)
    hills.lineTo(w * 0.45f, h * 0.78f)
    hills.lineTo(w * 0.68f, h * 0.55f)
    hills.lineTo(w.toFloat(), h * 0.8f)
    hills.lineTo(w.toFloat(), h.toFloat())
    hills.close()
    canvas.drawPath(hills, paint)
    paint.color = Color.argb(70, 255, 255, 255)
    paint.strokeWidth = (h / 300f).coerceAtLeast(1f)
    for (i in 1 until 8) {
        canvas.drawLine(w * i / 8f, 0f, w * i / 8f, h.toFloat(), paint)
        canvas.drawLine(0f, h * i / 8f, w.toFloat(), h * i / 8f, paint)
    }
    return bitmap
}
