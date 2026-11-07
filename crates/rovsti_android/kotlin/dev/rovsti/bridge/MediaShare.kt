package dev.rovsti.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/**
 * Gallery save and image share. No storage permission is used (minSdk 34). The host manifest declares
 * [RovstiShareProvider] (`android:authorities="${applicationId}.rovstishare"`, not exported,
 * `grantUriPermissions`).
 */
object MediaShare {
    private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,80}")
    private const val MAX_STAGED = 20

    fun authority(context: Context): String = "${context.packageName}.rovstishare"

    fun dir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

    /** Pure: whether [name] may be served by [RovstiShareProvider] (no separators, no traversal). */
    fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name) && !name.contains("..")

    /** Pure: a served-safe file name made from [display], keeping a short extension. */
    fun safeName(display: String, fallback: String): String {
        val cleaned = display.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("").replace("..", "_").trim('.').take(80)
        return if (cleaned.isNotEmpty() && isSafeName(cleaned)) cleaned else fallback
    }

    /** Pure: the MIME type served for [name], from its extension. */
    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "vcf" -> "text/x-vcard"
        "txt" -> "text/plain"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "amr" -> "audio/amr"
        "3gp" -> "video/3gpp"
        "mp4" -> "video/mp4"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /** Inserts the image at [path] into Pictures/Rovsti. The row is deleted again when the copy fails. */
    fun saveToGallery(context: Context, path: String, name: String, mime: String): Boolean {
        val source = File(path)
        if (!source.isFile) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safeName(name, "rovsti-${System.currentTimeMillis()}.png"))
            put(MediaStore.Images.Media.MIME_TYPE, mime.ifEmpty { "image/png" })
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Rovsti")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: RuntimeException) {
            null
        } ?: return false
        return try {
            val copied = resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
                true
            } == true
            if (!copied) throw java.io.IOException("no output stream")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            true
        } catch (_: java.io.IOException) {
            runCatching { resolver.delete(uri, null, null) }
            false
        } catch (_: RuntimeException) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    /** Copies [path] into `cache/share` (as [name] when given) and returns its provider URI; null on failure. */
    fun stageForShare(context: Context, path: String, name: String? = null): Uri? {
        val source = File(path)
        if (!source.isFile) return null
        return try {
            val folder = dir(context)
            folder.listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_STAGED - 1)?.forEach { it.delete() }
            val staged = safeName("${System.currentTimeMillis()}-${name ?: source.name}", "share-${System.currentTimeMillis()}.bin")
            source.copyTo(File(folder, staged), overwrite = true)
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(staged).build()
        } catch (_: java.io.IOException) {
            null
        }
    }
}

/** Serves the files in `cache/share` read-only to the share target. */
class RovstiShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") return null
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (!MediaShare.isSafeName(name)) return null
        val file = File(MediaShare.dir(ctx), name)
        if (!file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = MediaShare.mimeFor(uri.lastPathSegment.orEmpty())
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
