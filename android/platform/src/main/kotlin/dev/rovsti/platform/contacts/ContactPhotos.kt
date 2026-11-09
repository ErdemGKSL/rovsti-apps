package dev.rovsti.platform.contacts

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.provider.ContactsContract
import dev.rovsti.core.model.contacts.PhotoPlan
import dev.rovsti.core.model.contacts.PhotoSize
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * A contact photo as a small PNG file, and writing a cropped photo back. Rust asks for a photo URI (from
 * `ContactsContract` `PHOTO_THUMBNAIL_URI`) and gets back a file path. The photo is centre-cropped to a square and
 * never scaled up. Files live in the cache directory, which Android may clear.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/ContactPhotos.kt (the plan is a typed [PhotoPlan]).
 */
object ContactPhotos {
    /** A cached file younger than this is reused. */
    const val FRESH_MS = 10 * 60 * 1000L

    /** Files older than this are deleted at startup. */
    const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

    /** At most this many cached photos are kept (the newest). */
    const val MAX_FILES = 400

    /** Decoded images are shrunk to this long side before cropping, so a 48 MP photo cannot exhaust memory. */
    const val MAX_DECODE_SIDE = 4096

    private const val MAX_OUT = 720

    /** One cached file as [pruneVictims] sees it. */
    class Cached(
        val name: String,
        val modifiedMs: Long,
    )

    /** The names to delete: everything past [maxAgeMs], then the oldest beyond [maxFiles]. */
    fun pruneVictims(
        files: List<Cached>,
        now: Long,
        maxAgeMs: Long = MAX_AGE_MS,
        maxFiles: Int = MAX_FILES,
    ): List<String> {
        val (old, young) = files.partition { now - it.modifiedMs > maxAgeMs }
        val extra = young.sortedByDescending { it.modifiedMs }.drop(maxFiles)
        return (old + extra).map { it.name }
    }

    /** Prunes the photo cache directory at [nowMs] (epoch ms, from the app clock). Disk work: call it off the main thread. */
    fun prune(
        context: Context,
        nowMs: Long,
    ) {
        try {
            val files = File(context.cacheDir, "contact-photos").listFiles() ?: return
            val victims =
                pruneVictims(
                    files.map { Cached(it.name, it.lastModified()) },
                    nowMs,
                ).toSet()
            for (f in files) if (f.name in victims) f.delete()
        } catch (_: SecurityException) {
        }
    }

    /** `inSampleSize` (a power of two) that keeps the decoded bitmap at least [target] on its short side. */
    fun sampleSize(
        width: Int,
        height: Int,
        target: Int,
    ): Int {
        var sample = 1
        val short = minOf(width, height)
        while (target > 0 && short / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Side of the square result: [target], but never above the source. */
    fun outputSide(
        width: Int,
        height: Int,
        target: Int,
    ): Int = minOf(target, width, height).coerceAtLeast(1)

    /** A stable, filesystem-safe name for a URI. */
    fun cacheName(
        uri: String,
        size: Int,
    ): String {
        var hash = 1125899906842597L
        for (c in uri) hash = 31 * hash + c.code
        return "p${java.lang.Long.toHexString(hash)}_$size.png"
    }

    /** The PNG's absolute path, or "" when the contact has no readable photo. [nowMs] is epoch ms from the app clock. */
    fun thumbnailPath(
        context: Context,
        uri: String,
        size: Int,
        nowMs: Long,
    ): String {
        if (uri.isBlank() || size <= 0) return ""
        return try {
            val dir = File(context.cacheDir, "contact-photos").apply { mkdirs() }
            val file = File(dir, cacheName(uri, size))
            if (file.length() > 0 && nowMs - file.lastModified() < FRESH_MS) {
                return file.absolutePath
            }
            val source = Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ""
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize =
                        sampleSize(bounds.outWidth, bounds.outHeight, size)
                }
            val decoded =
                context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return ""
            val side = outputSide(decoded.width, decoded.height, size)
            val crop = minOf(decoded.width, decoded.height)
            val square =
                Bitmap.createBitmap(
                    decoded,
                    (decoded.width - crop) / 2,
                    (decoded.height - crop) / 2,
                    crop,
                    crop,
                )
            val scaled = if (crop == side) square else Bitmap.createScaledBitmap(square, side, side, true)
            val tmp = File(dir, file.name + ".tmp")
            FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) return ""
            file.absolutePath
        } catch (_: java.io.IOException) {
            ""
        } catch (_: RuntimeException) {
            ""
        }
    }

    // ---- Writing a contact's photo --------------------------------------------

    /** A crop rectangle and the output side, in pixels of the decoded image. */
    data class Crop(
        val x: Int,
        val y: Int,
        val side: Int,
        val out: Int,
    )

    /**
     * Pure: maps [plan] (in pixels of an image of [headerW] x [headerH]) onto a decoded image of [decodedW] x
     * [decodedH], clamped to that image. Null when the plan leaves nothing to crop.
     */
    fun cropFor(
        plan: PhotoPlan,
        headerW: Int,
        headerH: Int,
        decodedW: Int,
        decodedH: Int,
    ): Crop? {
        if (headerW <= 0 || headerH <= 0 || decodedW <= 0 || decodedH <= 0) return null
        val sx = decodedW.toDouble() / headerW
        val sy = decodedH.toDouble() / headerH
        val side = (plan.side * minOf(sx, sy)).toInt().coerceAtMost(minOf(decodedW, decodedH))
        if (side <= 0) return null
        val x = (plan.x * sx).toInt().coerceIn(0, decodedW - side)
        val y = (plan.y * sy).toInt().coerceIn(0, decodedH - side)
        return Crop(x, y, side, plan.out.coerceIn(1, MAX_OUT))
    }

    /** The plan's clockwise quarter turns reduced to 0..3. */
    fun quarterTurns(plan: PhotoPlan): Int = plan.turns.mod(4)

    /** The pixel size of the image at [path] (EXIF rotation applied), or 0 x 0 when unreadable. */
    fun size(path: String): PhotoSize {
        var w = 0
        var h = 0
        try {
            // The listener sees the header before any pixels; a 1x1 target keeps the decode trivial.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                w = info.size.width
                h = info.size.height
                decoder.setTargetSize(1, 1)
            }
        } catch (_: java.io.IOException) {
            w = 0
            h = 0
        } catch (_: RuntimeException) {
            w = 0
            h = 0
        }
        return PhotoSize(w, h)
    }

    /**
     * Crops the image at [path] by [plan], scales it to the plan's `out`, encodes JPEG q90 and writes it as the
     * contact's display photo. The cached file is deleted either way.
     */
    fun set(
        context: Context,
        contactId: Long,
        path: String,
        plan: PhotoPlan,
    ): ContactResult<Unit> {
        val file = File(path)
        return guarded {
            val rawId =
                ContactsEdit.primaryRawId(context, contactId)
                    ?: return@guarded ContactResult.failed(ContactResult.Reason.NotFound)
            var headerW = 0
            var headerH = 0
            val decoded =
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    headerW = info.size.width
                    headerH = info.size.height
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longSide = maxOf(headerW, headerH)
                    if (longSide > MAX_DECODE_SIDE) {
                        val k = MAX_DECODE_SIDE.toDouble() / longSide
                        decoder.setTargetSize(
                            (headerW * k).toInt().coerceAtLeast(1),
                            (headerH * k).toInt().coerceAtLeast(1),
                        )
                    }
                }
            val turns = quarterTurns(plan)
            val source =
                if (turns == 0) {
                    decoded
                } else {
                    val m = Matrix().apply { postRotate(90f * turns) }
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                }
            // PhotoPlan coordinates are in the rotated image, so the header size swaps on an odd turn.
            val rotatedW = if (turns % 2 == 1) headerH else headerW
            val rotatedH = if (turns % 2 == 1) headerW else headerH
            val crop =
                cropFor(plan, rotatedW, rotatedH, source.width, source.height)
                    ?: return@guarded ContactResult.failed(ContactResult.Reason.Refused)
            val square = Bitmap.createBitmap(source, crop.x, crop.y, crop.side, crop.side)
            val scaled =
                if (crop.side ==
                    crop.out
                ) {
                    square
                } else {
                    Bitmap.createScaledBitmap(square, crop.out, crop.out, true)
                }
            val bytes =
                ByteArrayOutputStream()
                    .also {
                        scaled.compress(
                            Bitmap.CompressFormat.JPEG,
                            90,
                            it,
                        )
                    }.toByteArray()
            val uri =
                Uri.withAppendedPath(
                    ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                    ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY,
                )
            val fd =
                context.contentResolver.openAssetFileDescriptor(uri, "rw")
                    ?: return@guarded ContactResult.failed(ContactResult.Reason.Refused)
            fd.use { d -> d.createOutputStream().use { it.write(bytes) } }
            ContactResult.Done
        }.also { file.delete() }
    }

    /** Deletes the photo data row of the contact's primary raw contact. */
    fun remove(
        context: Context,
        contactId: Long,
    ): ContactResult<Unit> =
        guarded {
            val rawId =
                ContactsEdit.primaryRawId(context, contactId)
                    ?: return@guarded ContactResult.failed(ContactResult.Reason.NotFound)
            context.contentResolver.delete(
                ContactsContract.Data.CONTENT_URI,
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            )
            ContactResult.Done
        }
}
