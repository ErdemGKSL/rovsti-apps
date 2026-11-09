package dev.rovsti.platform.files

// Ported from the central bridge object in crates/rovsti_android/kotlin/dev/rovsti/bridge/ (shareText, shareImage,
// shareFile, sendSmsTo, sendEmailTo; E3.8a). Each function returns the Intent; the caller starts it and catches
// ActivityNotFoundException.

import android.content.Intent
import android.net.Uri

/** The share sheet and composer intents. Files must already be staged (see [FilesRepository.stageForShare]). */
object ShareIntents {
    /** The system share sheet with [text]. */
    fun text(text: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)

    /** The share sheet for the staged image [uri]; [title] is the chooser title when not empty. */
    fun image(
        uri: Uri,
        mime: String,
        title: String,
    ): Intent = stream(uri, mime.ifEmpty { "image/*" }, title)

    /** The share sheet for the staged file [uri] named [name]. */
    fun file(
        uri: Uri,
        mime: String,
        name: String,
    ): Intent = stream(uri, mime.ifEmpty { "*/*" }, name)

    private fun stream(
        uri: Uri,
        mime: String,
        title: String,
    ): Intent {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (title.isNotEmpty()) send.putExtra(Intent.EXTRA_TITLE, title)
        return Intent.createChooser(send, title.ifEmpty { null }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The SMS composer for [number] (`smsto:`); [body] is prefilled when given. The user still presses send. */
    fun sendSmsTo(
        number: String,
        body: String? = null,
    ): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).also { intent ->
            body?.let { intent.putExtra("sms_body", it) }
        }

    /** The e-mail composer for [address] (`mailto:`). */
    fun sendEmailTo(address: String): Intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null))
}
