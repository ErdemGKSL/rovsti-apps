package dev.rosty.messages.wa

import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.WaSyncMediaState
import dev.rosty.core.model.wa.WaSyncMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
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
 * The media of WhatsApp messages as the screens use it (E6.4b, E6-WA R2), kept through the offline store:
 *
 * - **thumbnails** are stored with the message for good (`wa_sync.media_set` base64) and written once to a file under
 *   the instance's directory so the image loader has a stable model; a message whose thumbnail the store does not
 *   have asks the gateway;
 * - **full files** go through the store's download queue ([WaDownloads]): [want] offers the rows a thread shows to
 *   the auto-download rules, [fetch] is a tap. A message whose file was evicted, or was never downloaded, keeps its
 *   thumbnail and downloads on a tap; one the gateway no longer has says so;
 * - **files to send** are copied into the instance's directory first ([outgoing]); once sent the store counts them
 *   as the cached media of the message, so the same LRU clears them.
 *
 * Nothing here polls; every call is one tap or one page load.
 */
class WaMedia(
    private val offline: WaOffline,
    private val remote: WaRemote,
    private val downloads: WaDownloads,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dir: File get() = downloads.dir
    private val thumbs = ConcurrentHashMap<String, File>()

    /** The store the thumbnails in memory belong to: another link starts over. */
    @Volatile private var thumbsOf: Any? = null

    /** The messages whose file is being downloaded right now, or was tapped and is on its way. */
    val busy: StateFlow<Set<String>> get() = downloads.busy

    /** The thumbnail file of [row] when it is already known; [ensureThumb] finds the others. */
    fun thumb(row: WaSyncMessage): File? = thumbs[row.id]

    /** Forgets the thumbnails in memory: their files went with the account they belonged to. */
    fun forgetThumbs() {
        thumbs.clear()
    }

    /**
     * Makes the thumbnail of [row] a file: from disk, from the store, else from the gateway (stored then). True when a
     * new one appeared, so the page should be read again.
     */
    suspend fun ensureThumb(row: WaSyncMessage): Boolean {
        val session = offline.session.value ?: return false
        if (thumbsOf !== session.store) {
            thumbs.clear()
            thumbsOf = session.store
        }
        if (thumbs.containsKey(row.id)) return false
        val target = downloads.thumbFile(session.link.instanceId, row.id)
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
     * The rows a thread shows are offered to the auto-download rules (the store decides by kind and network). A file
     * the cap took, one that failed and one the gateway no longer has are not asked for again by themselves: they
     * wait for a tap.
     */
    suspend fun want(rows: List<WaSyncMessage>) {
        val fresh =
            rows
                .filter { it.mediaPath == null && (it.mediaState == null || it.mediaState == WaSyncMediaState.None) }
                .map { WaMediaRef(it.chat, it.id) }
        offline.wantMedia(fresh)
    }

    /**
     * The full file of [row], asked for by a tap: the cached one (marked used in the LRU), else queued in the store
     * and waited for. Null when it could not be downloaded now (offline, gone from the gateway); a file that only
     * failed stays queued and arrives with a later attempt.
     */
    suspend fun fetch(row: WaSyncMessage): File? {
        val session = offline.session.value ?: return null
        cached(session, row)?.let { return it }
        downloads.tapping(row.id)
        try {
            if (!downloads.tap(session.store, WaMediaRef(row.chat, row.id))) return null
            offline.kickMedia(session)?.join()
            return cached(session, row)
        } finally {
            downloads.untap(row.id)
            offline.noteChanged(setOf(row.chat))
        }
    }

    private suspend fun cached(
        session: WaSession,
        row: WaSyncMessage,
    ): File? {
        val stored = session.store.media(row.chat, row.id, usedMs = now()).getOrNull()
        return stored?.path?.let(::File)?.takeIf { file -> withContext(io) { file.isFile } }
    }

    /** True when the gateway no longer has the file of [row] (asked after a tap that brought none). */
    suspend fun gone(row: WaSyncMessage): Boolean {
        val session = offline.session.value ?: return false
        return session.store
            .media(row.chat, row.id)
            .getOrNull()
            ?.state == WaSyncMediaState.Gone
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
     * Where a file to send is copied to before it is queued: its own folder under the instance's directory, kept
     * under [name] (an outbox row carries no file name, so the bubble reads it from the path). Null when WhatsApp is
     * not linked.
     */
    fun outgoing(
        id: String,
        name: String,
    ): File? {
        val session = offline.session.value ?: return null
        val folder = downloads.outgoingDir(session.link.instanceId, id)
        folder.mkdirs()
        return File(folder, WaDownloads.safeName(name).ifEmpty { "file" })
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
        /** WhatsApp's limit for one attachment (`rosty_wa_proto::MAX_SEND_BYTES`). */
        const val MAX_SEND_BYTES = 16L * 1024 * 1024
    }
}
