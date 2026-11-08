// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code.
package dev.rovsti.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import coil3.size.Dimension
import coil3.size.Size
import coil3.transition.Transition
import dev.rovsti.design.media.producedImageLoader
import java.util.concurrent.CopyOnWriteArrayList

/** One image request the fake loader served: what was asked for, at which pixel size, with or without crossfade. */
data class RecordedImage(
    val data: Any?,
    val widthPx: Int,
    val heightPx: Int,
    val crossfade: Boolean,
)

/**
 * The image loader of every screenshot and UI test: it reads no file and uses no decoder, [producer] draws the bitmap
 * (a deterministic coloured gradient by default), and [requests] records each request with its size, so a test can
 * assert that a 44 dp avatar asked for 44 dp x density pixels.
 */
class FakeImageLoader(
    context: Context,
    producer: (data: Any, widthPx: Int, heightPx: Int) -> Bitmap? = ::gradientImage,
) {
    val requests = CopyOnWriteArrayList<RecordedImage>()

    val loader: ImageLoader =
        producedImageLoader(
            context,
            onRequest = { request: ImageRequest, size: Size ->
                requests +=
                    RecordedImage(
                        data = request.data,
                        widthPx = (size.width as? Dimension.Pixels)?.px ?: -1,
                        heightPx = (size.height as? Dimension.Pixels)?.px ?: -1,
                        crossfade = request.transitionFactory !== Transition.Factory.NONE,
                    )
            },
            producer = producer,
        )
}

/** A vertical gradient whose hue comes from the text of the request data; the size is the size asked for. */
fun gradientImage(
    data: Any,
    widthPx: Int,
    heightPx: Int,
): Bitmap {
    val hue = (data.toString().hashCode().mod(360)).toFloat()
    val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val paint = Paint()
    paint.shader =
        android.graphics.LinearGradient(
            0f,
            0f,
            0f,
            bitmap.height.toFloat(),
            Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.95f)),
            Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.8f, 0.5f)),
            android.graphics.Shader.TileMode.CLAMP,
        )
    Canvas(bitmap).drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
    return bitmap
}
