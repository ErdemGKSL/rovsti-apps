package dev.rovsti.platform.files

// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/Documents.kt (E3.8a). The request codes, the pending-state
// statics and the JSON `document_result` are gone: the picker is an ActivityResultContract (Contracts.kt) and the
// answer is a typed FileResult. The constants and the pure helpers are kept as they were.

import android.content.Intent
import java.io.InputStream
import java.io.OutputStream

/**
 * Limits and pure helpers of the Storage Access Framework hand-offs (backup, contacts, call log, message archive,
 * attachments, GTFS). No storage permission is used.
 */
object Documents {
    /** At most 256 KB is read from an import (a backup is a few KB). */
    const val MAX_BYTES = 256 * 1024

    /** A picked contact photo larger than this (20 MiB) is refused. */
    const val MAX_PHOTO_BYTES = 20 * 1024 * 1024

    /** The cap for a file import: an encrypted message archive can be large (AD-MSG7). */
    const val MAX_FILE_BYTES = 64 * 1024 * 1024

    /** A vCard file with a whole address book in it is larger than a backup. */
    const val MAX_CONTACTS_BYTES = 8 * 1024 * 1024

    /** Pure: the file name the camera writes, `camera-<ms>.jpg` (the only name the share provider lets it write). */
    fun cameraName(nowMs: Long): String = "camera-${nowMs.coerceAtLeast(0)}.jpg"

    /** Pure: the byte cap for a file import, [requested] limited to [MAX_FILE_BYTES]; non-positive means the cap. */
    fun fileCap(requested: Int): Int = if (requested <= 0) MAX_FILE_BYTES else minOf(requested, MAX_FILE_BYTES)

    /** Copies at most [max] bytes from [input] to [out]; false (and a partial copy) when the stream is longer. */
    fun copyLimited(
        input: InputStream,
        out: OutputStream,
        max: Int,
    ): Boolean {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return true
            total += n
            if (total > max) return false
            out.write(buffer, 0, n)
        }
    }

    /** Reads at most [max] bytes as UTF-8; null when the stream is longer (not a backup). */
    fun readLimited(
        stream: InputStream,
        max: Int = MAX_BYTES,
    ): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** "Save as" intent: a new document of [mime] named [name]. */
    fun exportIntent(
        name: String,
        mime: String,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime.ifEmpty { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, name)

    /** The open-document intent for one [kind] (the mime list of today's `importIntent`/`importContactsIntent`). */
    fun importIntent(kind: ImportKind): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .also { intent -> kind.mimes?.let { intent.putExtra(Intent.EXTRA_MIME_TYPES, it) } }
}

/** What an import picks; decides the mime filter, whether the answer is text or a cache file, and the default cap. */
enum class ImportKind(
    internal val mimes: Array<String>?,
    /** True when the answer is the UTF-8 text (small documents); false when it is a copy in the app cache. */
    val text: Boolean,
) {
    Json(arrayOf("application/json", "text/plain", "application/octet-stream"), true),
    Contacts(
        arrayOf(
            "text/x-vcard",
            "text/vcard",
            "text/directory",
            "text/plain",
            "text/csv",
            "text/comma-separated-values",
            "application/octet-stream",
        ),
        true,
    ),
    Calls(arrayOf("text/csv", "text/comma-separated-values", "text/plain"), true),
    Attachment(null, false),
    Gtfs(null, false),
    ;

    /** The cap for a request of [requested] bytes (non-positive: the kind's default). */
    fun cap(requested: Int): Int =
        when {
            this == Contacts && requested <= 0 -> Documents.MAX_CONTACTS_BYTES
            text && requested <= 0 -> Documents.MAX_BYTES
            text -> requested
            else -> Documents.fileCap(requested)
        }
}
