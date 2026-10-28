package dev.rovsti.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Story 10.5: a contact photo as a small PNG file. Rust asks for a photo URI
 * (from `ContactsContract` `PHOTO_THUMBNAIL_URI`) and gets back a file path,
 * so the bridge keeps moving only strings and ints (AD-5). The photo is
 * centre-cropped to a square and never scaled up; Rust decodes the file off
 * the UI thread. Files live in the cache directory, which Android may clear.
 */
object ContactPhotos {
    /** A cached file younger than this is reused. */
    const val FRESH_MS = 10 * 60 * 1000L

    /** `inSampleSize` (a power of two) that keeps the decoded bitmap at least [target] on its short side. */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        val short = minOf(width, height)
        while (target > 0 && short / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Side of the square result: [target], but never above the source. */
    fun outputSide(width: Int, height: Int, target: Int): Int = minOf(target, width, height).coerceAtLeast(1)

    /** A stable, filesystem-safe name for a URI. */
    fun cacheName(uri: String, size: Int): String {
        var hash = 1125899906842597L
        for (c in uri) hash = 31 * hash + c.code
        return "p${java.lang.Long.toHexString(hash)}_$size.png"
    }

    /** The PNG's absolute path, or "" when the contact has no readable photo. */
    fun thumbnailPath(context: Context, uri: String, size: Int): String {
        if (uri.isBlank() || size <= 0) return ""
        return try {
            val dir = File(context.cacheDir, "contact-photos").apply { mkdirs() }
            val file = File(dir, cacheName(uri, size))
            if (file.length() > 0 && System.currentTimeMillis() - file.lastModified() < FRESH_MS) {
                return file.absolutePath
            }
            val source = Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ""
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size)
            }
            val decoded = context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return ""
            val side = outputSide(decoded.width, decoded.height, size)
            val crop = minOf(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(decoded, (decoded.width - crop) / 2, (decoded.height - crop) / 2, crop, crop)
            val scaled = if (crop == side) square else Bitmap.createScaledBitmap(square, side, side, true)
            val tmp = File(dir, file.name + ".tmp")
            FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) return ""
            file.absolutePath
        } catch (_: Exception) {
            ""
        }
    }
}
