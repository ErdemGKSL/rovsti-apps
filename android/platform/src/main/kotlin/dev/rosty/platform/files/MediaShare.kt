package dev.rosty.platform.files

// Ported from crates/rosty_android/kotlin/dev/rosty/bridge/MediaShare.kt (E3.8a). Same names, patterns and share dir;
// RostyShareProvider moved to RostyShareProvider.kt (it must keep the dev.rosty.bridge package).

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Gallery save and image share. No storage permission is used (minSdk 34). The app manifest declares
 * `dev.rosty.bridge.RostyShareProvider` (`android:authorities="${applicationId}.rostyshare"`, not exported,
 * `grantUriPermissions`).
 */
object MediaShare {
    private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,80}")
    private const val MAX_STAGED = 20

    fun authority(context: Context): String = "${context.packageName}.rostyshare"

    fun dir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

    /** The sub-folder of [dir] that holds finished share cards (X.2). */
    const val CARDS = "cards"

    /** `cache/share/cards`, created when missing. */
    fun cardsDir(context: Context): File = File(dir(context), CARDS).apply { mkdirs() }

    /** The provider URI of the card file [name] in [cardsDir]. */
    fun cardUriFor(
        context: Context,
        name: String,
    ): Uri =
        Uri
            .Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(CARDS)
            .appendPath(name)
            .build()

    /**
     * The file a provider [uri] names: `<name>` in [dir], or `cards/<name>` in [cardsDir]. Null for anything else
     * (other depth, unsafe name).
     */
    fun resolve(
        context: Context,
        uri: Uri,
    ): File? {
        val segments = uri.pathSegments
        val name = segments.lastOrNull() ?: return null
        if (!isSafeName(name)) return null
        return when {
            segments.size == 1 -> File(dir(context), name)
            segments.size == 2 && segments[0] == CARDS -> File(cardsDir(context), name)
            else -> null
        }
    }

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

    /** Pure: whether [name] may be served by `RostyShareProvider` (no separators, no traversal). */
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

    /** Inserts the image at [path] into Pictures/Rosty. The row is deleted again when the copy fails. */
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
                put(MediaStore.Images.Media.DISPLAY_NAME, safeName(name, "rosty-${now()}.png"))
                put(MediaStore.Images.Media.MIME_TYPE, mime.ifEmpty { "image/png" })
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Rosty")
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
                ?.filter { it.isFile }
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
