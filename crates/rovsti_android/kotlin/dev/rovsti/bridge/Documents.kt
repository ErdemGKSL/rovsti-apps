package dev.rovsti.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.InputStream

/**
 * Storage Access Framework hand-offs for the backup (story 16.6, AD-30). No storage permission is
 * used. `exportDocument` and `importDocument` start a system picker and return at once; the answer is
 * the one-shot `document_result` event (through the [Mailbox]). `MainActivity.onActivityResult` forwards
 * to [handle]. One operation is pending at a time.
 */
object Documents {
    const val REQUEST_EXPORT = 0x4558 // "EX"
    const val REQUEST_IMPORT = 0x494D // "IM"
    const val REQUEST_EXPORT_CONTACTS = 0x4543 // "EC"
    const val REQUEST_IMPORT_CONTACTS = 0x4943 // "IC"

    /** At most 256 KB is read from an import (a backup is a few KB). */
    const val MAX_BYTES = 256 * 1024

    /** A vCard file with a whole address book in it is larger than a backup. */
    const val MAX_CONTACTS_BYTES = 8 * 1024 * 1024

    private var pendingJson: String? = null

    fun exportIntent(name: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, name)

    fun importIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))

    fun exportContactsIntent(name: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/x-vcard")
            .putExtra(Intent.EXTRA_TITLE, name)

    fun importContactsIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/x-vcard", "text/vcard", "text/directory", "text/plain", "application/octet-stream"),
            )

    fun start(activity: Activity, export: Boolean, name: String, json: String): Boolean =
        launch(activity, export, json, if (export) exportIntent(name) else importIntent(), if (export) REQUEST_EXPORT else REQUEST_IMPORT)

    /** The vCard picker: [text] is the whole `.vcf` for an export. */
    fun startContacts(activity: Activity, export: Boolean, name: String, text: String): Boolean =
        launch(
            activity, export, text,
            if (export) exportContactsIntent(name) else importContactsIntent(),
            if (export) REQUEST_EXPORT_CONTACTS else REQUEST_IMPORT_CONTACTS,
        )

    private fun launch(activity: Activity, export: Boolean, payload: String, intent: Intent, code: Int): Boolean {
        if (export) pendingJson = payload
        return try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, code)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            pendingJson = null
            false
        }
    }

    /** Reads at most [max] bytes as UTF-8; null when the stream is longer (not a backup). */
    fun readLimited(stream: InputStream, max: Int = MAX_BYTES): String? {
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

    /** `rovsti_core::wire::DocumentResult`. */
    fun resultJson(op: String, ok: Boolean, name: String? = null, text: String? = null, error: String? = null): String {
        val o = JSONObject().put("op", op).put("ok", ok)
        name?.let { o.put("name", it) }
        text?.let { o.put("text", it) }
        error?.let { o.put("error", it) }
        return o.toString()
    }

    /** The display name of a picked document. */
    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Completes a picker. File work runs on [worker], never the main thread; [emit] gets the
     * `document_result`. Returns true when the request code was ours.
     */
    fun handle(
        context: Context,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        worker: java.util.concurrent.Executor,
        emit: (String) -> Unit,
    ): Boolean {
        val export = requestCode == REQUEST_EXPORT || requestCode == REQUEST_EXPORT_CONTACTS
        val contacts = requestCode == REQUEST_EXPORT_CONTACTS || requestCode == REQUEST_IMPORT_CONTACTS
        if (!export && requestCode != REQUEST_IMPORT && requestCode != REQUEST_IMPORT_CONTACTS) return false
        val op = when {
            contacts -> if (export) "export_contacts" else "import_contacts"
            else -> if (export) "export_backup" else "import_backup"
        }
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            pendingJson = null
            // A cancelled picker answers `ok:false` with no error: Rust shows nothing.
            emit(resultJson(op, false))
            return true
        }
        val json = pendingJson
        pendingJson = null
        worker.execute {
            emit(
                try {
                    val name = displayName(context, uri)
                    if (export) {
                        val ok = json != null && context.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                            true
                        } == true
                        resultJson(op, ok, name, error = if (ok) null else "unknown")
                    } else {
                        val text = context.contentResolver.openInputStream(uri)?.use {
                            readLimited(it, if (contacts) MAX_CONTACTS_BYTES else MAX_BYTES)
                        }
                        if (text == null) resultJson(op, false, name, error = "unavailable") else resultJson(op, true, name, text)
                    }
                } catch (_: SecurityException) {
                    resultJson(op, false, error = "permission")
                } catch (_: java.io.IOException) {
                    resultJson(op, false, error = "unknown")
                },
            )
        }
        return true
    }
}
