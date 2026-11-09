package dev.rovsti.platform.files

// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/Documents.kt (handleFile/handlePhoto/handle) and
// MediaShare.kt (E3.8a).

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** File work behind the pickers. Everything runs off the main thread and nothing throws. */
interface FilesRepository {
    /**
     * Reads the picked [uri]. With [text] the answer holds the UTF-8 content (at most [maxBytes]); otherwise it holds
     * `path`, a copy in the app cache, and the mime type. Over the cap: [FileResult.TooLarge].
     */
    suspend fun readUri(
        uri: Uri,
        maxBytes: Int,
        text: Boolean = false,
    ): FileResult

    /** Writes [text] (or the file at [path]) to the document [uri] chosen by [ExportDocument]. */
    suspend fun writeExport(
        uri: Uri,
        text: String? = null,
        path: String? = null,
    ): FileResult

    /** Inserts the image at [path] into Pictures/Rovsti. */
    suspend fun saveToGallery(
        path: String,
        name: String,
        mime: String,
    ): Boolean

    /** Copies [path] into the share cache and returns its provider URI; null on failure. */
    suspend fun stageForShare(
        path: String,
        name: String? = null,
    ): Uri?
}

/** [FilesRepository] on the content resolver. */
class AndroidFilesRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
) : FilesRepository {
    override suspend fun readUri(
        uri: Uri,
        maxBytes: Int,
        text: Boolean,
    ): FileResult =
        withContext(io) {
            try {
                val name = displayName(context, uri)
                val resolver = context.contentResolver
                if (text) {
                    val stream = resolver.openInputStream(uri) ?: return@withContext FileResult.Failed("unavailable")
                    val content = stream.use { Documents.readLimited(it, maxBytes) }
                    if (content == null) {
                        FileResult.TooLarge
                    } else {
                        FileResult.Ok(name = name, text = content, mime = resolver.getType(uri), uri = uri)
                    }
                } else {
                    val target = File(context.cacheDir, "import-${now()}.bin")
                    val fits =
                        resolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { Documents.copyLimited(input, it, maxBytes) }
                        }
                    when (fits) {
                        true -> {
                            FileResult.Ok(
                                name = name,
                                path = target.absolutePath,
                                mime = resolver.getType(uri),
                                uri = uri,
                            )
                        }

                        false -> {
                            target.delete()
                            FileResult.TooLarge
                        }

                        null -> {
                            FileResult.Failed("unavailable")
                        }
                    }
                }
            } catch (_: SecurityException) {
                FileResult.Failed("permission")
            } catch (_: IOException) {
                FileResult.Failed("unknown")
            } catch (_: RuntimeException) {
                FileResult.Failed("unknown")
            }
        }

    override suspend fun writeExport(
        uri: Uri,
        text: String?,
        path: String?,
    ): FileResult =
        withContext(io) {
            try {
                val file = path?.let(::File)
                val ok =
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        when {
                            text != null -> out.write(text.toByteArray(Charsets.UTF_8))
                            file != null && file.isFile -> file.inputStream().use { it.copyTo(out) }
                            else -> return@use false
                        }
                        true
                    } == true
                if (ok) FileResult.Ok(name = displayName(context, uri), uri = uri) else FileResult.Failed("unknown")
            } catch (_: SecurityException) {
                FileResult.Failed("permission")
            } catch (_: IOException) {
                FileResult.Failed("unknown")
            }
        }

    override suspend fun saveToGallery(
        path: String,
        name: String,
        mime: String,
    ): Boolean = withContext(io) { MediaShare.saveToGallery(context, path, name, mime, now) }

    override suspend fun stageForShare(
        path: String,
        name: String?,
    ): Uri? = withContext(io) { MediaShare.stageForShare(context, path, name, now) }
}
