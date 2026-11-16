package dev.rosty.platform.files

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Copies a file into the phone's shared collections (MediaStore): photos to Pictures/Rosty, videos to Movies/Rosty,
 * anything else to Download/Rosty. No storage permission is needed (minSdk 34): an app may always add to these
 * collections and read back what it added.
 */
interface GalleryWriter {
    /** Copies [path] as [name] ([mime] picks the collection) and answers the content URI of the copy; null on failure. */
    suspend fun write(
        path: String,
        name: String,
        mime: String,
    ): String?

    /** True while the copy at [uri] still exists (the user may have deleted it in the gallery since). */
    suspend fun exists(uri: String): Boolean
}

/** Where a saved file of type [mime] goes: the MediaStore collection and its folder. */
enum class GalleryTarget(
    val relativePath: String,
) {
    Pictures("Pictures/Rosty"),
    Movies("Movies/Rosty"),
    Downloads("Download/Rosty"),
    ;

    companion object {
        /** Pure: a photo (also a GIF or a sticker) is a picture, a video a movie, everything else a download. */
        fun of(mime: String): GalleryTarget =
            when {
                mime.startsWith("image/", ignoreCase = true) -> Pictures
                mime.startsWith("video/", ignoreCase = true) -> Movies
                else -> Downloads
            }

        private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
        private const val MAX_NAME = 120

        /** Pure: [name] without path separators and control characters (any script is kept); null when nothing is left. */
        fun displayName(name: String): String? =
            name
                .replace(UNSAFE, "_")
                .trim()
                .trim('.')
                .take(MAX_NAME)
                .takeIf { it.isNotEmpty() }
    }
}

/** [GalleryWriter] on the content resolver: the row is written with `IS_PENDING` set and published once complete. */
class MediaStoreGalleryWriter(
    private val context: Context,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
) : GalleryWriter {
    override suspend fun write(
        path: String,
        name: String,
        mime: String,
    ): String? = withContext(io) { insert(context.contentResolver, File(path), name, mime) }

    override suspend fun exists(uri: String): Boolean =
        withContext(io) {
            try {
                context.contentResolver
                    .query(
                        Uri.parse(uri),
                        arrayOf(MediaStore.MediaColumns._ID),
                        null,
                        null,
                        null,
                    )?.use {
                        it.moveToFirst()
                    } == true
            } catch (_: RuntimeException) {
                // Not readable now (a revoked grant, a provider that is gone): treated as gone, a second copy is fine.
                false
            }
        }

    private fun insert(
        resolver: ContentResolver,
        source: File,
        name: String,
        mime: String,
    ): String? {
        if (!source.isFile) return null
        val target = GalleryTarget.of(mime)
        val collection =
            when (target) {
                GalleryTarget.Pictures -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                GalleryTarget.Movies -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                GalleryTarget.Downloads -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, GalleryTarget.displayName(name) ?: "rosty-${now()}")
                put(MediaStore.MediaColumns.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri =
            try {
                resolver.insert(collection, values)
            } catch (_: RuntimeException) {
                null
            } ?: return null
        return try {
            val copied =
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                    true
                } == true
            if (!copied) throw IOException("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri.toString()
        } catch (_: IOException) {
            runCatching { resolver.delete(uri, null, null) }
            null
        } catch (_: RuntimeException) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
