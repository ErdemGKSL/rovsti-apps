package dev.rovsti.platform.files

// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/MediaShare.kt (E3.8a). Same names, patterns and share dir;
// RovstiShareProvider moved to RovstiShareProvider.kt (it must keep the dev.rovsti.bridge package).

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Gallery save and image share. No storage permission is used (minSdk 34). The app manifest declares
 * `dev.rovsti.bridge.RovstiShareProvider` (`android:authorities="${applicationId}.rovstishare"`, not exported,
 * `grantUriPermissions`).
 */
object MediaShare {
    private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,80}")
    private const val MAX_STAGED = 20

    fun authority(context: Context): String = "${context.packageName}.rovstishare"

    fun dir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

    /** The provider URI of the file [name] in [dir]. */
    fun uriFor(
        context: Context,
        name: String,
    ): Uri =
        Uri
            .Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(name)
            .build()

    /** Pure: whether [name] may be served by `RovstiShareProvider` (no separators, no traversal). */
    fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name) && !name.contains("..")

    private val CAMERA_NAME = Regex("camera-[0-9]{1,20}\\.jpg")

    /** Pure: whether the camera app may write [name] (only `camera-<digits>.jpg`, created by the app itself). */
    fun writable(name: String): Boolean = CAMERA_NAME.matches(name)

    /** Pure: a served-safe file name made from [display], keeping a short extension. */
    fun safeName(
        display: String,
        fallback: String,
    ): String {
        val cleaned =
            display
                .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .replace("..", "_")
                .trim('.')
                .take(80)
        return if (cleaned.isNotEmpty() && isSafeName(cleaned)) cleaned else fallback
    }

    /** Pure: the MIME type served for [name], from its extension. */
    fun mimeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
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
    fun saveToGallery(
        context: Context,
        path: String,
        name: String,
        mime: String,
        now: () -> Long = System::currentTimeMillis,
    ): Boolean {
        val source = File(path)
        if (!source.isFile) return false
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, safeName(name, "rovsti-${now()}.png"))
                put(MediaStore.Images.Media.MIME_TYPE, mime.ifEmpty { "image/png" })
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Rovsti")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val uri =
            try {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } catch (_: RuntimeException) {
                null
            } ?: return false
        return try {
            val copied =
                resolver.openOutputStream(uri)?.use { out ->
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
    fun stageForShare(
        context: Context,
        path: String,
        name: String? = null,
        now: () -> Long = System::currentTimeMillis,
    ): Uri? {
        val source = File(path)
        if (!source.isFile) return null
        return try {
            val folder = dir(context)
            folder
                .listFiles()
                ?.sortedBy { it.lastModified() }
                ?.dropLast(MAX_STAGED - 1)
                ?.forEach { it.delete() }
            val staged =
                safeName("${now()}-${name ?: source.name}", "share-${now()}.bin")
            source.copyTo(File(folder, staged), overwrite = true)
            uriFor(context, staged)
        } catch (_: java.io.IOException) {
            null
        }
    }
}
