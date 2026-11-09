package dev.rovsti.contacts.card

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.rovsti.core.model.qr.QrMatrix
import dev.rovsti.design.media.QrModules
import dev.rovsti.design.media.renderQrBitmap
import dev.rovsti.platform.files.FilesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the QR sheet shows and does (E4.9): the code of one contact's vCard, its name under it, and the two exports.
 * Both exports draw the code at 1024 px ([renderQrBitmap]), write the PNG under [fileName] in [stagingDir] and hand it to
 * the files seam: Save Image inserts it into Pictures/Rovsti, Share Image stages it for the share provider.
 */
@Immutable
class QrSheetModel(
    val name: String,
    val matrix: QrMatrix,
    val fileName: String,
    private val files: FilesRepository,
    private val stagingDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The matrix as the design system draws it. */
    val modules: QrModules = QrModules(matrix.side) { x, y -> matrix[x, y] }

    /** Saves the image to the gallery; false when the write or the insert failed. */
    suspend fun save(): Boolean {
        val path = write() ?: return false
        return files.saveToGallery(path, fileName, MIME)
    }

    /** Stages the image for sharing; null when it could not be written. */
    suspend fun stageForShare(): Uri? {
        val path = write() ?: return null
        return files.stageForShare(path, fileName)
    }

    private suspend fun write(): String? =
        withContext(io) {
            runCatching {
                val file = File(stagingDir.apply { mkdirs() }, fileName)
                file.outputStream().use { out ->
                    renderQrBitmap(modules).asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                }
                file.absolutePath
            }.getOrNull()
        }

    companion object {
        const val MIME = "image/png"
        private const val PNG_QUALITY = 100
    }
}
