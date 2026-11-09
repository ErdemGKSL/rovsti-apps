package dev.rovsti.contacts.intake

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** A picture as 8-bit gray values, row-major: the input of `qr.decode`. */
class Luma(
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
)

/** Reads a picked picture as [Luma]. Null when the picture cannot be read. */
fun interface LumaReader {
    suspend fun read(uri: String): Luma?
}

/**
 * [LumaReader] on the content resolver. The decode and the conversion run on [worker] (`Dispatchers.Default`), never on
 * the main thread. The picture is read at a power-of-two sample size so that neither side is above [MAX_SIDE]: the
 * decoder of `qr.decode` takes at most 1600 x 1600 pixels.
 */
class AndroidLumaReader(
    private val context: Context,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : LumaReader {
    override suspend fun read(uri: String): Luma? =
        withContext(worker) {
            try {
                val target = Uri.parse(uri)
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(target)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize =
                            sampleFor(bounds.outWidth, bounds.outHeight)
                    }
                val bitmap =
                    resolver.openInputStream(target)?.use { BitmapFactory.decodeStream(it, null, options) }
                        ?: return@withContext null
                try {
                    val w = bitmap.width
                    val h = bitmap.height
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    Luma(w, h, lumaOf(pixels))
                } finally {
                    bitmap.recycle()
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }

    companion object {
        const val MAX_SIDE = 1600

        /** The power-of-two sample size that brings both sides to at most [MAX_SIDE]. */
        fun sampleFor(
            width: Int,
            height: Int,
        ): Int {
            var sample = 1
            while (width / sample > MAX_SIDE || height / sample > MAX_SIDE) sample *= 2
            return sample
        }

        /**
         * Gray values of ARGB [pixels] (Rec. 601 weights). A transparent pixel is read over white, as a viewer shows a
         * PNG, so a QR code saved with a clear background still has light modules between the dark ones.
         */
        fun lumaOf(pixels: IntArray): ByteArray =
            ByteArray(pixels.size) { i ->
                val p = pixels[i]
                val a = p ushr ALPHA_SHIFT
                val r = (p shr RED_SHIFT) and CHANNEL
                val g = (p shr GREEN_SHIFT) and CHANNEL
                val b = p and CHANNEL
                val gray = (r * RED_WEIGHT + g * GREEN_WEIGHT + b * BLUE_WEIGHT) / WEIGHT_SUM
                ((gray * a + CHANNEL * (CHANNEL - a)) / CHANNEL).toByte()
            }

        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val CHANNEL = 255
        private const val RED_WEIGHT = 299
        private const val GREEN_WEIGHT = 587
        private const val BLUE_WEIGHT = 114
        private const val WEIGHT_SUM = 1000
    }
}
