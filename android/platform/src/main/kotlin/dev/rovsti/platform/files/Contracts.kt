package dev.rovsti.platform.files

// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/Documents.kt (E3.8a): the intents, mimes and extras of
// exportIntent/importIntent/photoPickIntent/startPhotoCapture are unchanged; the activity statics and request codes
// became ActivityResultContracts.

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * "Save as": the input is the suggested file name. The answer is [FileResult.Ok] with the chosen [FileResult.Ok.uri];
 * write to it with [FilesRepository.writeExport].
 */
class ExportDocument(
    private val mime: String,
) : ActivityResultContract<String, FileResult>() {
    override fun createIntent(
        context: Context,
        input: String,
    ): Intent = Documents.exportIntent(input, mime)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): FileResult = pickedUri(resultCode, intent)
}

/**
 * The document picker for one [kind], up to [maxBytes] (non-positive: the kind's default). The answer is
 * [FileResult.Ok] with the picked [FileResult.Ok.uri]; [FilesRepository.readUri] reads it off the main thread and
 * answers [FileResult.TooLarge] for a stream above [cap].
 */
class ImportDocument(
    private val maxBytes: Int = 0,
    val kind: ImportKind = ImportKind.Json,
) : ActivityResultContract<Unit, FileResult>() {
    /** The byte cap the read applies. */
    val cap: Int get() = kind.cap(maxBytes)

    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent = Documents.importIntent(kind)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): FileResult = pickedUri(resultCode, intent)
}

/** The system Photo Picker, images only; needs no permission (no media read right). Read the uri with the photo cap. */
class PickPhoto : ActivityResultContract<Unit, FileResult>() {
    private val inner = ActivityResultContracts.PickVisualMedia()

    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent = inner.createIntent(context, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): FileResult = pickedUri(resultCode, intent)
}

/**
 * Opens the camera app to take one photo into `cache/share/camera-<ms>.jpg` through `RovstiShareProvider`. No `CAMERA`
 * permission is declared (it would make the intent need it). The answer is [FileResult.Ok] with `path` and `mime`
 * `image/jpeg`.
 */
class CapturePhoto(
    private val now: () -> Long = System::currentTimeMillis,
) : ActivityResultContract<Unit, FileResult>() {
    private var pending: File? = null

    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent {
        val file = File(MediaShare.dir(context), Documents.cameraName(now()))
        file.delete()
        if (!file.createNewFile()) throw java.io.IOException("cannot create ${file.name}")
        val uri = MediaShare.uriFor(context, file.name)
        pending = file
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri("", uri) }
    }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): FileResult {
        val file = pending
        pending = null
        if (resultCode == Activity.RESULT_OK && file != null && file.length() > 0) {
            return FileResult.Ok(name = file.name, path = file.absolutePath, mime = "image/jpeg")
        }
        file?.delete()
        return FileResult.Cancelled
    }
}

private fun pickedUri(
    resultCode: Int,
    intent: Intent?,
): FileResult {
    val uri: Uri? = intent?.data
    return if (resultCode != Activity.RESULT_OK || uri == null) FileResult.Cancelled else FileResult.Ok(uri = uri)
}

/** The display name of a picked document; null when it has none. */
internal fun displayName(
    context: Context,
    uri: Uri,
): String? =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    }
