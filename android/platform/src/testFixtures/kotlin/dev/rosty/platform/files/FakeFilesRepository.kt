package dev.rosty.platform.files

import android.net.Uri

/** A [FilesRepository] for tests: answers are set up front and every call is recorded. */
class FakeFilesRepository(
    var readResult: FileResult = FileResult.Cancelled,
    var writeResult: FileResult = FileResult.Ok(),
    var galleryOk: Boolean = true,
    var staged: Uri? = null,
) : FilesRepository {
    val reads = mutableListOf<Triple<Uri, Int, Boolean>>()
    val writes = mutableListOf<Triple<Uri, String?, String?>>()
    val gallery = mutableListOf<Triple<String, String, String>>()
    val stagedPaths = mutableListOf<Pair<String, String?>>()

    override suspend fun readUri(
        uri: Uri,
        maxBytes: Int,
        text: Boolean,
    ): FileResult {
        reads += Triple(uri, maxBytes, text)
        return readResult
    }

    override suspend fun writeExport(
        uri: Uri,
        text: String?,
        path: String?,
    ): FileResult {
        writes += Triple(uri, text, path)
        return writeResult
    }

    override suspend fun saveToGallery(
        path: String,
        name: String,
        mime: String,
    ): Boolean {
        gallery += Triple(path, name, mime)
        return galleryOk
    }

    val stagedCards = mutableListOf<Pair<String, Boolean>>()

    override suspend fun stageCard(
        path: String,
        asStill: Boolean,
    ): StagedCard? {
        stagedCards += path to asStill
        val uri = staged ?: return null
        val format =
            when {
                asStill -> CardFormat.Jpeg
                path.endsWith(".gif") -> CardFormat.Gif
                path.endsWith(".webp") -> CardFormat.WebP
                else -> CardFormat.Png
            }
        return StagedCard(uri, format.mime, java.io.File(path), format)
    }

    override suspend fun stageForShare(
        path: String,
        name: String?,
    ): Uri? {
        stagedPaths += path to name
        return staged
    }
}
