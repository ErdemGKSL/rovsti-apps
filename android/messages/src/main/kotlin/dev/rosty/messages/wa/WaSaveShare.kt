package dev.rosty.messages.wa

import android.content.Intent
import android.net.Uri
import dev.rosty.core.getOrNull
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.platform.files.GalleryTarget
import dev.rosty.platform.files.GalleryWriter
import dev.rosty.platform.files.ShareIntents
import java.io.File

/** How a Save of a WhatsApp file ended. */
sealed interface WaSaved {
    /** A copy was written to [target] and recorded on the message. */
    data class Saved(
        val uri: String,
        val target: GalleryTarget,
    ) : WaSaved

    /** The message was saved before and the copy is still there: nothing was written. */
    data class Already(
        val uri: String,
    ) : WaSaved

    data object Failed : WaSaved

    /** A view-once message is never saved. */
    data object NotAllowed : WaSaved
}

/**
 * Save of a WhatsApp file (E6-WA R3): the file is copied once through MediaStore ([writer]: Pictures/Rosty,
 * Movies/Rosty, Download/Rosty) and the copy's URI is recorded on the message (`wa_sync.media_keep` `saved_uri`), which
 * keeps the cached file out of eviction and makes a second Save a no-op. A copy the user deleted in the gallery since
 * is written again.
 */
class WaMediaSaver(
    /** The store that is open now; null when WhatsApp is not linked. */
    private val store: () -> WaSyncStore?,
    private val writer: GalleryWriter,
) {
    /** The copy of message [id] that is still on the phone; null when it was never saved or the copy is gone. */
    suspend fun savedUri(
        chat: String,
        id: String,
    ): String? {
        val uri =
            store()
                ?.media(chat, id)
                ?.getOrNull()
                ?.savedUri
                ?.takeIf { it.isNotEmpty() } ?: return null
        return uri.takeIf { writer.exists(it) }
    }

    suspend fun save(
        chat: String,
        id: String,
        file: File,
        name: String,
        mime: String,
        viewOnce: Boolean = false,
    ): WaSaved {
        if (viewOnce) return WaSaved.NotAllowed
        savedUri(chat, id)?.let { return WaSaved.Already(it) }
        val type = mime.ifEmpty { "application/octet-stream" }
        val uri = writer.write(file.absolutePath, name, type) ?: return WaSaved.Failed
        // The copy is on the phone even when the store cannot record it now; the next Save then writes another.
        store()?.keepMedia(chat, id, savedUri = uri)
        return WaSaved.Saved(uri, GalleryTarget.of(type))
    }
}

/**
 * The system share sheet for a WhatsApp file staged at [uri] (the app's `RostyShareProvider`): the stream, its type,
 * its name and the read grant, so the receiving app can open it.
 */
fun waShareIntent(
    uri: Uri,
    mime: String,
    name: String,
): Intent = ShareIntents.file(uri, mime, name)
