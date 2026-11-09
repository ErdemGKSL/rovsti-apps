package dev.rovsti.platform.files

import android.net.Uri

/** The answer of a picker, a capture or a read. Replaces the one-shot `document_result` JSON. */
sealed interface FileResult {
    /**
     * A document was chosen or read. [uri] is the picked document (set by the contracts); [text] is the UTF-8 content
     * of a small text import; [path] is a copy in the app cache (large imports, photos, camera); [mime] when known.
     */
    data class Ok(
        val name: String? = null,
        val text: String? = null,
        val path: String? = null,
        val mime: String? = null,
        val uri: Uri? = null,
    ) : FileResult

    /** The document is larger than the cap. */
    data object TooLarge : FileResult

    /** The user backed out. Nothing is shown. */
    data object Cancelled : FileResult

    /** [reason] is `unavailable`, `permission` or `unknown`, the codes the GPUI build already sent. */
    data class Failed(
        val reason: String,
    ) : FileResult
}
