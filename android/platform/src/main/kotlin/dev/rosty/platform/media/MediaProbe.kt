package dev.rosty.platform.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import dev.rosty.core.log.RostyLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * What a photo or video measures before it is sent (E6-WA R3): its size as it is shown (EXIF or track rotation
 * applied), the length of a video in whole seconds, and a small JPEG preview ([MediaRules.LONGEST_SIDE] px on its
 * longer side) that the other phone shows until it downloads the file. Each is null when it could not be read.
 */
class MediaMeasure(
    val width: Int?,
    val height: Int?,
    val seconds: Int?,
    val thumbnail: ByteArray?,
)

/** Measures a file to send. Tests use `FakeMediaProbe`. */
fun interface MediaProbe {
    /** [file] of type [mime] (`image/...` or `video/...`); null for any other type or a file that does not read. */
    suspend fun measure(
        file: File,
        mime: String,
    ): MediaMeasure?
}

/** The pure rules of [MediaProbe]: the preview size, the rotation, the length, the JPEG budget. */
object MediaRules {
    /** The longer side of the preview, in pixels (WhatsApp's own previews are about this big). */
    const val LONGEST_SIDE = 100

    /** The gateway refuses a preview over 64 KiB; one this small is well under it. */
    const val MAX_THUMBNAIL_BYTES = 16 * 1024

    /** JPEG qualities tried in order until the preview fits [MAX_THUMBNAIL_BYTES]. */
    val QUALITIES = listOf(70, 50, 30)

    private const val QUARTER_TURN = 90
    private const val HALF_TURN = 180
    private const val FULL_TURN = 360
    private const val MS_PER_SECOND = 1_000L

    /** [width] x [height] as shown after a rotation of [degrees] (a quarter turn swaps them). */
    fun shown(
        width: Int,
        height: Int,
        degrees: Int,
    ): Pair<Int, Int> {
        val turn = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN
        return if (turn == QUARTER_TURN || turn == HALF_TURN + QUARTER_TURN) height to width else width to height
    }

    /** The preview size of a [width] x [height] picture: [longest] on the longer side, aspect kept, never upscaled. */
    fun preview(
        width: Int,
        height: Int,
        longest: Int = LONGEST_SIDE,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val side = maxOf(width, height)
        if (side <= longest) return width to height
        val scale = longest.toDouble() / side
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** The largest power-of-two step that still decodes [width] x [height] at least twice the [longest] side. */
    fun sampleSize(
        width: Int,
        height: Int,
        longest: Int = LONGEST_SIDE,
    ): Int {
        var sample = 1
        val side = maxOf(width, height)
        while (side / (sample * 2) >= longest * 2) sample *= 2
        return sample
    }

    /** The length of a video of [durationMs], rounded to whole seconds; at least 1 for a video that has any. */
    fun seconds(durationMs: Long?): Int? {
        val ms = durationMs?.takeIf { it > 0 } ?: return null
        return maxOf(1, ((ms + MS_PER_SECOND / 2) / MS_PER_SECOND).toInt())
    }

    /** The first of [QUALITIES] whose JPEG ([encode]) fits [MAX_THUMBNAIL_BYTES]; null when none does. */
    fun jpeg(encode: (quality: Int) -> ByteArray?): ByteArray? =
        QUALITIES.firstNotNullOfOrNull { quality ->
            encode(quality)?.takeIf {
                it.isNotEmpty() &&
                    it.size <= MAX_THUMBNAIL_BYTES
            }
        }

    /** The rotation an EXIF orientation tag asks for, in degrees. */
    fun exifDegrees(orientation: Int): Int =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> QUARTER_TURN
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> HALF_TURN
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> HALF_TURN + QUARTER_TURN
            else -> 0
        }
}

/**
 * [MediaProbe] on the platform: `BitmapFactory` and the EXIF orientation for a photo (a GIF is measured by its first
 * frame), `MediaMetadataRetriever` for a video (its size, rotation, length and a frame near the start). Runs on [io].
 */
class AndroidMediaProbe(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MediaProbe {
    override suspend fun measure(
        file: File,
        mime: String,
    ): MediaMeasure? =
        withContext(io) {
            try {
                when {
                    mime.startsWith("image/") -> photo(file)
                    mime.startsWith("video/") -> video(file)
                    else -> null
                }
            } catch (e: IOException) {
                RostyLog.w(TAG, "cannot measure a file to send: ${e.message}")
                null
            } catch (e: RuntimeException) {
                // MediaMetadataRetriever and BitmapFactory throw unchecked exceptions for files they cannot read.
                RostyLog.w(TAG, "cannot measure a file to send: ${e.message}")
                null
            }
        }

    private fun photo(file: File): MediaMeasure? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val degrees =
            MediaRules.exifDegrees(
                ExifInterface(
                    file.path,
                ).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            )
        val (width, height) = MediaRules.shown(bounds.outWidth, bounds.outHeight, degrees)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize =
                    MediaRules.sampleSize(bounds.outWidth, bounds.outHeight)
            }
        val decoded = BitmapFactory.decodeFile(file.path, options)
        return MediaMeasure(width, height, null, decoded?.let { preview(it, degrees) })
    }

    private fun video(file: File): MediaMeasure? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)

            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()
            val rawWidth = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val rawHeight = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val degrees = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
            val shown =
                if (rawWidth != null &&
                    rawHeight != null
                ) {
                    MediaRules.shown(rawWidth, rawHeight, degrees)
                } else {
                    null
                }
            val seconds =
                MediaRules.seconds(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                )
            // The frame comes back as it is shown: the retriever applies the track's rotation.
            val frame = retriever.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            return MediaMeasure(shown?.first, shown?.second, seconds, frame?.let { preview(it, 0) })
        } finally {
            retriever.release()
        }
    }

    private fun preview(
        bitmap: Bitmap,
        degrees: Int,
    ): ByteArray? {
        val (width, height) = MediaRules.preview(bitmap.width, bitmap.height)
        if (width <= 0) return null
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val turned =
            if (degrees == 0) {
                scaled
            } else {
                Bitmap.createBitmap(scaled, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            }
        return MediaRules.jpeg { quality ->
            ByteArrayOutputStream().use { out ->
                if (turned.compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
            }
        }
    }

    private companion object {
        const val TAG = "media-probe"

        /** Half a second in: the very first frame of a phone video is often black. */
        const val FRAME_AT_US = 500_000L
    }
}
