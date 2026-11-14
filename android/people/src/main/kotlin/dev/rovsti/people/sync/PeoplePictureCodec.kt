package dev.rovsti.people.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.rovsti.wa.avatars.AvatarCodec
import dev.rovsti.wa.avatars.sampleSize
import java.io.ByteArrayOutputStream

/**
 * The codec of the people cache (S12): the full WhatsApp picture (usually 640 px) scaled so its short side is exactly
 * `maxPx`, as a JPEG at quality 85. The decoder first samples by a power of two (a big picture never exists at full size
 * in memory), then a filtered scale gives the exact size; a picture already small enough is kept as it came.
 */
object PeoplePictureCodec : AvatarCodec {
    private const val JPEG_QUALITY = 85

    override fun downscale(
        bytes: ByteArray,
        maxPx: Int,
    ): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (scaledSize(bounds.outWidth, bounds.outHeight, maxPx) == null) return bytes
        val options =
            BitmapFactory.Options().apply {
                inSampleSize =
                    sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            }
        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val target = scaledSize(sampled.width, sampled.height, maxPx)
        val scaled = target?.let { (w, h) -> Bitmap.createScaledBitmap(sampled, w, h, true) } ?: sampled
        return try {
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
        } finally {
            if (scaled !== sampled) scaled.recycle()
            sampled.recycle()
        }
    }
}

/**
 * The size a [width] x [height] picture is scaled to so its short side is [maxPx], keeping the aspect; null when the
 * short side is already [maxPx] or less (nothing to do).
 */
fun scaledSize(
    width: Int,
    height: Int,
    maxPx: Int,
): Pair<Int, Int>? {
    val short = minOf(width, height)
    if (maxPx <= 0 || short <= maxPx) return null
    val ratio = maxPx.toDouble() / short
    val w = if (width == short) maxPx else Math.round(width * ratio).toInt().coerceAtLeast(maxPx)
    val h = if (height == short) maxPx else Math.round(height * ratio).toInt().coerceAtLeast(maxPx)
    return w to h
}
