// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code.
package dev.rovsti.catalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import coil3.ImageLoader
import dev.rovsti.design.media.producedImageLoader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The seven generated photos of the catalog: model `photo:<n>`, 1-based. Aspect ratios differ so letterboxing shows. */
internal const val CatalogPhotoCount = 7

internal fun catalogPhotoModel(number: Int): String = "photo:$number"

private val Aspects = floatArrayOf(4f / 3f, 3f / 4f, 1f, 16f / 9f, 3f / 2f, 2f / 3f, 1f)
private val Hues = floatArrayOf(210f, 20f, 140f, 280f, 50f, 340f, 175f)

/** Aspect (width / height) of generated photo [number]. */
internal fun catalogPhotoAspect(number: Int): Float = Aspects[(number - 1).mod(Aspects.size)]

/**
 * A deterministic picture for `photo:<n>`: a two-colour gradient, a grid, a sun and the number, sized so its longer side
 * is the longer side asked for (at most 1600 px) and its aspect is the photo's own. No decoding, no files.
 */
internal fun catalogPhoto(
    data: Any,
    widthPx: Int,
    heightPx: Int,
): Bitmap? {
    val number = data.toString().removePrefix("photo:").toIntOrNull() ?: return null
    val aspect = catalogPhotoAspect(number)
    val long = min(max(max(widthPx, heightPx), 64), 1600)
    val w = if (aspect >= 1f) long else (long * aspect).roundToInt()
    val h = if (aspect >= 1f) (long / aspect).roundToInt() else long
    val bitmap = Bitmap.createBitmap(max(w, 1), max(h, 1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val hue = Hues[(number - 1).mod(Hues.size)]
    val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f))
    val bottom = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.85f, 0.45f))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader =
        android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, android.graphics.Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    paint.color = Color.argb(60, 255, 255, 255)
    paint.strokeWidth = max(w, h) / 200f
    val step = max(w, h) / 8f
    var x = step
    while (x < w) {
        canvas.drawLine(x, 0f, x, h.toFloat(), paint)
        x += step
    }
    var y = step
    while (y < h) {
        canvas.drawLine(0f, y, w.toFloat(), y, paint)
        y += step
    }
    paint.color = Color.argb(200, 255, 244, 200)
    canvas.drawCircle(w * 0.72f, h * 0.28f, min(w, h) * 0.12f, paint)
    paint.color = Color.WHITE
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = min(w, h) * 0.4f
    canvas.drawText(number.toString(), w / 2f, h / 2f + paint.textSize * 0.35f, paint)
    return bitmap
}

/** The loader of the real catalog app: the generated photos, no files and no network. */
internal fun catalogImageLoader(context: Context): ImageLoader = producedImageLoader(context, producer = ::catalogPhoto)
