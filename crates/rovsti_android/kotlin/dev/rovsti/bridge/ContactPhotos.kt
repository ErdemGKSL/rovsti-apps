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

    /** Files older than this are deleted at startup. */
    const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

    /** At most this many cached photos are kept (the newest). */
    const val MAX_FILES = 400

    /** One cached file as [prune] sees it. */
    class Cached(val name: String, val modifiedMs: Long)

    /** The names to delete: everything past [maxAgeMs], then the oldest beyond [maxFiles]. */
    fun pruneVictims(files: List<Cached>, now: Long, maxAgeMs: Long = MAX_AGE_MS, maxFiles: Int = MAX_FILES): List<String> {
        val (old, young) = files.partition { now - it.modifiedMs > maxAgeMs }
        val extra = young.sortedByDescending { it.modifiedMs }.drop(maxFiles)
        return (old + extra).map { it.name }
    }

    /** Prunes the photo cache directory. Disk work: call it off the main thread. */
    fun prune(context: Context) {
        try {
            val dir = File(context.cacheDir, "contact-photos")
            val files = dir.listFiles() ?: return
            val victims = pruneVictims(files.map { Cached(it.name, it.lastModified()) }, System.currentTimeMillis()).toSet()
            for (f in files) if (f.name in victims) f.delete()
        } catch (_: Exception) {
        }
    }

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

    // ---- Writing a contact's photo --------------------------------------------

    /** Decoded images are shrunk to this long side before cropping, so a 48 MP photo cannot exhaust memory. */
    const val MAX_DECODE_SIDE = 4096

    /** A crop rectangle and the output side, in pixels of the decoded image. */
    data class Crop(val x: Int, val y: Int, val side: Int, val out: Int)

    /**
     * Pure: reads a `PhotoPlan` (`{x, y, side, out}`, in pixels of the image as [headerW] x [headerH])
     * and maps it onto a decoded image of [decodedW] x [decodedH], clamped to that image. Null when
     * the plan is malformed or leaves nothing to crop.
     */
    fun cropFor(planJson: String, headerW: Int, headerH: Int, decodedW: Int, decodedH: Int): Crop? {
        if (headerW <= 0 || headerH <= 0 || decodedW <= 0 || decodedH <= 0) return null
        val o = try {
            org.json.JSONObject(planJson)
        } catch (_: org.json.JSONException) {
            return null
        }
        val sx = decodedW.toDouble() / headerW
        val sy = decodedH.toDouble() / headerH
        val side = (o.optInt("side", 0) * minOf(sx, sy)).toInt().coerceAtMost(minOf(decodedW, decodedH))
        if (side <= 0) return null
        val x = (o.optInt("x", 0) * sx).toInt().coerceIn(0, decodedW - side)
        val y = (o.optInt("y", 0) * sy).toInt().coerceIn(0, decodedH - side)
        val out = o.optInt("out", side).coerceIn(1, 720)
        return Crop(x, y, side, out)
    }

    /** The plan's clockwise quarter turns (`turns`, default 0), reduced to 0..3. */
    fun quarterTurns(planJson: String): Int = try {
        org.json.JSONObject(planJson).optInt("turns", 0).mod(4)
    } catch (_: org.json.JSONException) {
        0
    }

    /** `{"w":..,"h":..}` of the image at [path] (EXIF rotation applied), or `{"w":0,"h":0}` when unreadable. */
    fun sizeJson(path: String): String {
        var w = 0
        var h = 0
        try {
            val source = android.graphics.ImageDecoder.createSource(File(path))
            // The listener sees the header before any pixels; a 1x1 target keeps the decode trivial.
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                w = info.size.width
                h = info.size.height
                decoder.setTargetSize(1, 1)
            }
        } catch (_: Exception) {
            w = 0
            h = 0
        }
        return org.json.JSONObject().put("w", w).put("h", h).toString()
    }

    /**
     * Crops the image at [path] by [planJson], scales it to the plan's `out`, encodes JPEG q90 and
     * writes it as the contact's display photo. The cached file is deleted either way.
     */
    fun set(context: Context, contactId: Long, path: String, planJson: String): Boolean {
        val file = File(path)
        return try {
            val rawId = ContactsEdit.primaryRawId(context, contactId) ?: return false
            var headerW = 0
            var headerH = 0
            val decoded = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file)) { decoder, info, _ ->
                headerW = info.size.width
                headerH = info.size.height
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val longSide = maxOf(headerW, headerH)
                if (longSide > MAX_DECODE_SIDE) {
                    val k = MAX_DECODE_SIDE.toDouble() / longSide
                    decoder.setTargetSize((headerW * k).toInt().coerceAtLeast(1), (headerH * k).toInt().coerceAtLeast(1))
                }
            }
            val turns = quarterTurns(planJson)
            val source = if (turns == 0) {
                decoded
            } else {
                val m = android.graphics.Matrix().apply { postRotate(90f * turns) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            }
            // PhotoPlan coordinates are in the rotated image, so the header size swaps on an odd turn.
            val rotatedW = if (turns % 2 == 1) headerH else headerW
            val rotatedH = if (turns % 2 == 1) headerW else headerH
            val crop = cropFor(planJson, rotatedW, rotatedH, source.width, source.height) ?: return false
            val square = Bitmap.createBitmap(source, crop.x, crop.y, crop.side, crop.side)
            val scaled = if (crop.side == crop.out) square else Bitmap.createScaledBitmap(square, crop.out, crop.out, true)
            val bytes = java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            val uri = android.net.Uri.withAppendedPath(
                android.content.ContentUris.withAppendedId(android.provider.ContactsContract.RawContacts.CONTENT_URI, rawId),
                android.provider.ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY,
            )
            val fd = context.contentResolver.openAssetFileDescriptor(uri, "rw") ?: return false
            fd.use { d -> d.createOutputStream().use { it.write(bytes) } }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        } finally {
            file.delete()
        }
    }

    /** Deletes the photo data row of the contact's primary raw contact. False when Android refused. */
    fun remove(context: Context, contactId: Long): Boolean = try {
        val rawId = ContactsEdit.primaryRawId(context, contactId)
        if (rawId == null) {
            false
        } else {
            context.contentResolver.delete(
                android.provider.ContactsContract.Data.CONTENT_URI,
                "${android.provider.ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${android.provider.ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(rawId.toString(), android.provider.ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            )
            true
        }
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
}
