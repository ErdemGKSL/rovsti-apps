package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A picked or pasted file copied into app storage, ready for the outbox. */
data class WaStagedFile(
    val file: File,
    /** The name the other side sees. */
    val name: String,
    val mime: String,
    val size: Long,
)

/** What copying a picked file into app storage answered. */
sealed interface WaStageResult {
    data class Staged(
        val file: WaStagedFile,
    ) : WaStageResult

    /** Over [WaMedia.MAX_SEND_BYTES]. */
    data object TooLarge : WaStageResult

    data object Failed : WaStageResult
}

/**
 * The media of WhatsApp messages on the phone (E6.4b), kept through the offline store's media table:
 *
 * - **thumbnails** are stored with the message for good (`wa_sync.media_set` base64) and written once to a file under
 *   [dir] so the image loader has a stable model; a message whose thumbnail the store does not have asks the gateway;
 * - **full files** are downloaded into [dir] on a tap (pictures on their own), named to the store with their size, and
 *   kept in its LRU: [fetch] marks the file used, and after every download the store is brought under [capBytes] and
 *   the files it names are deleted. A message whose file was evicted stays readable and downloads again on a tap;
 * - **files to send** are copied into [dir] first ([outgoing]); once sent the store counts them as the cached media of
 *   the message, so the same LRU clears them.
 *
 * Nothing here polls; every call is one tap or one page load. Downloads run one at a time.
 */
class WaMedia(
    private val offline: WaOffline,
    private val remote: WaRemote,
    private val dir: File,
    private val now: () -> Long,
    private val capBytes: () -> Long = { DEFAULT_CAP_BYTES },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val thumbs = ConcurrentHashMap<String, File>()
    private val busyState = MutableStateFlow<Set<String>>(emptySet())
    private val failedState = MutableStateFlow<Set<String>>(emptySet())
    private val downloads = Mutex()

    /** The messages whose file is being downloaded right now. */
    val busy: StateFlow<Set<String>> = busyState.asStateFlow()

    /** The messages whose last download failed (cleared by the next try). */
    val failed: StateFlow<Set<String>> = failedState.asStateFlow()

    private fun root(instance: String): File = File(dir, safe(instance))

    private fun thumbFile(
        instance: String,
        id: String,
    ): File = File(File(root(instance), "thumbs"), "${safe(id)}.jpg")

    /** The thumbnail file of [row] when it is already known; [ensureThumb] finds the others. */
    fun thumb(row: WaSyncMessage): File? = thumbs[row.id]

    /**
     * Makes the thumbnail of [row] a file: from disk, from the store, else from the gateway (stored then). True when a
     * new one appeared, so the page should be read again.
     */
    suspend fun ensureThumb(row: WaSyncMessage): Boolean {
        if (thumbs.containsKey(row.id)) return false
        val session = offline.session.value ?: return false
        val target = thumbFile(session.link.instanceId, row.id)
        if (withContext(io) { target.isFile }) {
            thumbs[row.id] = target
            return true
        }
        var b64 =
            if (row.hasThumbnail) {
                session.store
                    .media(row.chat, row.id)
                    .getOrNull()
                    ?.thumbnailB64
            } else {
                null
            }
        if (b64 == null) {
            if (row.media?.thumbnail != true || !offline.online.value) return false
            val answer = remote.thumbnail(row.id).getOrNull() ?: return false
            val bytes = withContext(io) { bytesOf(answer) } ?: return false
            b64 = Base64.getEncoder().encodeToString(bytes)
            session.store.setMedia(row.chat, row.id, now(), thumbnailB64 = b64)
        }
        val data = b64
        val written =
            withContext(io) {
                try {
                    target.parentFile?.mkdirs()
                    target.writeBytes(Base64.getDecoder().decode(data))
                    true
                } catch (_: IOException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
        if (!written) return false
        thumbs[row.id] = target
        return true
    }

    /** The downloaded file of [row] when it is still on the phone. */
    fun local(row: WaSyncMessage): File? = row.mediaPath?.let(::File)?.takeIf { it.isFile }

    /**
     * The full file of [row], opened now: the cached one (marked used in the LRU), else downloaded, stored and the
     * cache brought under its cap. Null when it could not be downloaded (offline, gone from the gateway).
     */
    suspend fun fetch(row: WaSyncMessage): File? {
        val session = offline.session.value ?: return null
        val stored = session.store.media(row.chat, row.id, usedMs = now()).getOrNull()
        val cached = stored?.path?.let(::File)
        if (cached != null && withContext(io) { cached.isFile }) return cached
        busyState.update { it + row.id }
        failedState.update { it - row.id }
        try {
            val file =
                downloads.withLock {
                    val answer =
                        when (val got = remote.media(row.id)) {
                            is Outcome.Ok -> {
                                got.value
                            }

                            is Outcome.Err -> {
                                RostyLog.w(TAG, "download failed: ${got.error.message}")
                                null
                            }
                        }
                    answer?.let { withContext(io) { place(it, target(session.link.instanceId, row)) } }
                }
            if (file == null) {
                failedState.update { it + row.id }
                return null
            }
            session.store.setMedia(row.chat, row.id, now(), path = file.absolutePath, bytes = file.length())
            evict(session)
            return file
        } finally {
            busyState.update { it - row.id }
            offline.noteChanged(setOf(row.chat))
        }
    }

    /** Brings the cache under its cap and deletes what the store let go of. */
    private suspend fun evict(session: WaSession) {
        val gone =
            session.store
                .evictMedia(capBytes())
                .getOrNull()
                ?.evicted
                .orEmpty()
        if (gone.isEmpty()) return
        withContext(io) {
            // Only files this class wrote: the store never names anything else, but a path is still a path.
            gone.map { File(it.path) }.filter { it.startsWith(dir) }.forEach { it.delete() }
        }
        offline.noteChanged(gone.mapTo(HashSet()) { it.chat })
    }

    private fun target(
        instance: String,
        row: WaSyncMessage,
    ): File {
        val name =
            row.media
                ?.fileName
                ?.let(::safeName)
                ?.takeIf { it.isNotEmpty() }
                ?: "${row.kind.name.lowercase()}.${extensionOf(row.mime, row.kind)}"
        return File(File(File(root(instance), "files"), safe(row.id)), name)
    }

    /** The answer's bytes at [target]: decoded, or the temp file the core downloaded moved there. */
    private fun place(
        answer: BytesAnswer,
        target: File,
    ): File? =
        try {
            target.parentFile?.mkdirs()
            val temp = answer.path?.let(::File)
            val data = answer.dataB64
            when {
                data != null -> {
                    target.writeBytes(Base64.getDecoder().decode(data))
                    target
                }

                temp != null && temp.isFile -> {
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    target
                }

                else -> {
                    null
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun bytesOf(answer: BytesAnswer): ByteArray? =
        try {
            answer.dataB64?.let { Base64.getDecoder().decode(it) }
                ?: answer.path?.let(::File)?.takeIf { it.isFile }?.let { file ->
                    file.readBytes().also { file.delete() }
                }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * Where a file to send is copied to before it is queued: its own folder under [dir], kept under [name] (an outbox
     * row carries no file name, so the bubble reads it from the path). Null when WhatsApp is not linked.
     */
    fun outgoing(
        id: String,
        name: String,
    ): File? {
        val session = offline.session.value ?: return null
        val folder = File(File(root(session.link.instanceId), "out"), safe(id))
        folder.mkdirs()
        return File(folder, safeName(name).ifEmpty { "file" })
    }

    /** Deletes a staged file that will not be sent after all (removed from the composer, a cancelled send). */
    suspend fun discard(path: String) {
        withContext(io) {
            val file = File(path)
            if (file.startsWith(dir)) {
                file.delete()
                file.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
            }
        }
    }

    companion object {
        private const val TAG = "wa-media"

        /** WhatsApp's limit for one attachment (`rosty_wa_proto::MAX_SEND_BYTES`). */
        const val MAX_SEND_BYTES = 16L * 1024 * 1024

        /** The cache cap until Settings has one (E6.12). */
        const val DEFAULT_CAP_BYTES = 512L * 1024 * 1024

        private const val MAX_NAME = 80

        private fun safe(text: String): String {
            val kept = text.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_NAME)
            // Ids that differ only in dropped characters still get their own file.
            return if (kept == text) kept else "$kept-${Integer.toHexString(text.hashCode())}"
        }

        /** A display name as a file name: no path parts, no control characters. */
        fun safeName(name: String): String =
            name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filter { !it.isISOControl() && it !in FORBIDDEN }
                .trim()
                .trim('.')
                .takeLast(MAX_NAME)

        private const val FORBIDDEN = ":*?\"|<>"

        /** The extension a downloaded file gets when the message names none. */
        fun extensionOf(
            mime: String?,
            kind: WaSyncKind,
        ): String =
            when (mime?.substringBefore(';')?.trim()?.lowercase()) {
                "image/jpeg" -> {
                    "jpg"
                }

                "image/png" -> {
                    "png"
                }

                "image/webp" -> {
                    "webp"
                }

                "image/gif" -> {
                    "gif"
                }

                "video/mp4" -> {
                    "mp4"
                }

                "audio/ogg" -> {
                    "ogg"
                }

                "audio/mpeg" -> {
                    "mp3"
                }

                "audio/mp4", "audio/aac" -> {
                    "m4a"
                }

                "application/pdf" -> {
                    "pdf"
                }

                else -> {
                    when (kind) {
                        WaSyncKind.Image -> "jpg"
                        WaSyncKind.Sticker -> "webp"
                        WaSyncKind.Video -> "mp4"
                        WaSyncKind.Audio -> "ogg"
                        else -> "bin"
                    }
                }
            }
    }
}
