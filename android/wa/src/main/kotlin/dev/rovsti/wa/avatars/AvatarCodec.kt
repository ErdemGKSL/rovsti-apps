package dev.rovsti.wa.avatars

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Turns the bytes the gateway sent into the bytes kept on disk: checked to be a picture and no larger than a slot.
 * Tests pass a fake; [BitmapAvatarCodec] is the real one.
 */
fun interface AvatarCodec {
    /** The picture to store for [bytes] (at most about [maxPx] on its short side), or null when it is not an image. */
    fun downscale(
        bytes: ByteArray,
        maxPx: Int,
    ): ByteArray?
}

/** Decodes with `BitmapFactory.Options.inSampleSize` so a big profile photo never exists at full size in memory. */
object BitmapAvatarCodec : AvatarCodec {
    private const val JPEG_QUALITY = 85

    override fun downscale(
        bytes: ByteArray,
        maxPx: Int,
    ): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            if (sample == 1) {
                bytes
            } else {
                ByteArrayOutputStream()
                    .also {
                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            JPEG_QUALITY,
                            it,
                        )
                    }.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * The largest power of two (what the decoder rounds `inSampleSize` to) that keeps the short side of a [width] x
 * [height] picture at [maxPx] or more; 1 for a picture that is already small.
 */
fun sampleSize(
    width: Int,
    height: Int,
    maxPx: Int,
): Int {
    val short = minOf(width, height)
    var sample = 1
    while (maxPx > 0 && short / (sample * 2) >= maxPx) sample *= 2
    return sample
}
