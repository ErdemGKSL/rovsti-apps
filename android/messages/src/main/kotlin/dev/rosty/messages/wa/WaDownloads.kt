package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.getOrNull
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncFetched
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaEntry
import dev.rosty.core.model.wa.WaSyncNetwork
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.platform.system.DataNetwork
import dev.rosty.platform.system.DeviceData
import dev.rosty.wa.sync.WaRequestGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64

/** A message whose full file may be wanted: the chat as the caller names it and the message id. */
data class WaMediaRef(
    val chat: String,
    val id: String,
)

/**
 * The file side of WhatsApp media (E6-WA R2): every download of a full file goes through the store's queue, and the
 * store's rules decide what is fetched without a tap. This class does the I/O the store asks for and decides nothing:
 *
 * - [opened]: once per open store. The files under the instance's directory are listed and reconciled with the
 *   store's rows (a cut-off download is queued again, a row without its file reads as evicted, `*.part` files and
 *   files nothing names are deleted), then the cache is brought under its cap;
 * - [auto]: media rows that arrived (events, pages) or came into view are offered to the store with the network the
 *   phone is on; the rules (Wi-Fi: photos, voice notes, stickers; mobile data: photos, voice notes; nothing on
 *   roaming, with Data Saver or with little room) stay in Rust;
 * - [tap]: the user asked for a file: queued whatever the network;
 * - [kick]: one worker drains what is due, taps first: the bytes go to `<name>.part` in the file's own directory,
 *   are synced and renamed, and the store is told how it ended (ready, failed, gone). After each round the cache is
 *   brought under its cap and the files the store let go of are deleted;
 * - [wipe]: another account was linked, or the link was forgotten: the instance's whole directory goes.
 *
 * The worker runs in [scope] (the app's), so a download outlives the screen that asked for it.
 */
class WaDownloads(
    private val scope: CoroutineScope,
    private val remote: WaRemote,
    /** The root of WhatsApp media; each instance has its own directory under it. */
    val dir: File,
    private val now: () -> Long,
    private val device: DeviceData,
    /** Asks for the wake job when a download waits for its next attempt or for a network. */
    private val wake: WaWake = WaWake { },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Since the vault (`plans/wa-vault.md` 7, 14): the files live here in RAM, never under [dir], nothing downloads
     * without a tap, and the downloads go through [requests] one at a time. Null: the files of [dir] (tests of the disk
     * cache).
     */
    val ram: WaRamFiles? = null,
    private val requests: WaRequestGate? = null,
) {
    private val worker = Mutex()
    private val gate = Any()
    private var queuedPass: Job? = null
    private val tapped = MutableStateFlow<Set<String>>(emptySet())
    private val active = MutableStateFlow<Set<String>>(emptySet())
    private val busyState = MutableStateFlow<Set<String>>(emptySet())

    /** The messages whose file was asked for by a tap and is not there yet, or is being downloaded right now. */
    val busy: StateFlow<Set<String>> = busyState.asStateFlow()

    private fun publishBusy() {
        busyState.value = tapped.value + active.value
    }

    fun root(instance: String): File = File(dir, safe(instance))

    fun thumbFile(
        instance: String,
        id: String,
    ): File = File(File(root(instance), THUMBS), "${safe(id)}.jpg")

    /** The folder a file to send is copied into before it is queued. */
    fun outgoingDir(
        instance: String,
        id: String,
    ): File = File(File(root(instance), OUT), safe(id))

    private fun network(): WaSyncNetwork =
        when (device.network()) {
            DataNetwork.Wifi -> WaSyncNetwork.Wifi
            DataNetwork.Mobile -> WaSyncNetwork.Mobile
            DataNetwork.Roaming -> WaSyncNetwork.Roaming
            DataNetwork.DataSaver -> WaSyncNetwork.DataSaver
            DataNetwork.None -> WaSyncNetwork.None
        }

    private fun freeBytes(): Long? = device.freeBytes(dir)

    // ---- open: rows and files agree again

    /**
     * Puts the store's media rows and the files of its instance in agreement and brings the cache under its cap.
     * Answers the chats whose rows changed.
     */
    suspend fun opened(store: WaSyncStore): Set<String> {
        // A store in memory starts empty, and so does the RAM: nothing to reconcile.
        if (ram != null) return evict(store)
        val root = root(store.instanceId)
        val listed = withContext(io) { listing(root) }
        val changed = HashSet<String>()
        when (val out = store.reconcileMedia(listed?.map { it.absolutePath })) {
            is Outcome.Ok -> {
                changed += out.value.missing.map { it.chat }
                delete(out.value.delete)
            }

            is Outcome.Err -> {
                RostyLog.w(TAG, "media_reconcile failed: ${out.error.message}")
            }
        }
        changed += evict(store)
        return changed
    }

    /**
     * Every file under the directories of full media and of files to send (not the thumbnails). Null: a directory
     * that is there could not be listed, which must not read as "every file is gone".
     */
    private fun listing(root: File): List<File>? {
        val files = ArrayList<File>()
        for (name in listOf(FILES, OUT)) {
            val top = File(root, name)
            if (!top.exists()) continue
            val pending = ArrayDeque(listOf(top))
            while (pending.isNotEmpty()) {
                val children = pending.removeFirst().listFiles() ?: return null
                for (child in children) if (child.isDirectory) pending += child else files += child
            }
        }
        return files
    }

    // ---- asking for files

    /**
     * Offers [rows] to the auto-download rules under the network the phone is on. True when a file is queued: [kick]
     * then fetches it. Rows that are not media, or whose file is cached, are answered by the store as "nothing to do".
     */
    suspend fun auto(
        store: WaSyncStore,
        rows: Collection<WaMediaRef>,
    ): Boolean {
        // Auto-download is off since the vault: media is fetched on a tap only.
        if (rows.isEmpty() || ram != null) return false
        val network = network()
        if (network == WaSyncNetwork.None) return false
        val free = freeBytes()
        var queued = false
        for (row in rows) {
            val asked = store.enqueueMedia(row.chat, row.id, now(), network = network, freeBytes = free).getOrNull()
            if (asked?.queued == true) queued = true
        }
        return queued
    }

    /** The user tapped the file of a message: queued whatever the network. False: it has no file to download. */
    suspend fun tap(
        store: WaSyncStore,
        ref: WaMediaRef,
    ): Boolean {
        val asked = store.enqueueMedia(ref.chat, ref.id, now(), tap = true, freeBytes = freeBytes()).getOrNull()
        return asked?.queued == true
    }

    /** [id] waits for its tapped download (the bubble shows the spinner) until [untap]. */
    fun tapping(id: String) {
        tapped.update { it + id }
        publishBusy()
    }

    fun untap(id: String) {
        tapped.update { it - id }
        publishBusy()
    }

    // ---- the worker

    /**
     * Makes the downloads that are due, in the app's scope. The answer is the pass that will see everything queued
     * before this call: join it to wait for a tapped file. [changed] hears the chats whose rows changed.
     */
    fun kick(
        store: WaSyncStore,
        changed: (Set<String>) -> Unit,
    ): Job =
        synchronized(gate) {
            queuedPass?.takeIf { it.isActive } ?: scope
                .launch {
                    worker.withLock {
                        synchronized(gate) { queuedPass = null }
                        pass(store, changed)
                    }
                }.also { queuedPass = it }
        }

    private suspend fun pass(
        store: WaSyncStore,
        changed: (Set<String>) -> Unit,
    ) {
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val network = network()
            if (network == WaSyncNetwork.None) {
                // Nothing can be fetched; the wake job runs once a network is there.
                val waiting = store.mediaStats().getOrNull()?.queued ?: 0
                if (waiting > 0) wake.at(0)
                return
            }
            val due = store.dueMedia(now(), network = network, freeBytes = freeBytes()).getOrNull() ?: return
            if (due.items.isEmpty()) {
                due.nextWakeMs?.let { wake.at((it - now()).coerceAtLeast(0)) }
                return
            }
            for (item in due.items) {
                download(store, item)
                changed(setOf(item.chat))
            }
            val gone = evict(store)
            if (gone.isNotEmpty()) changed(gone)
        }
    }

    /** One download: `.part`, sync, rename, then the store hears how it ended. */
    private suspend fun download(
        store: WaSyncStore,
        item: WaSyncMediaEntry,
    ) {
        active.update { it + item.id }
        publishBusy()
        try {
            val name =
                store
                    .message(item.chat, item.id)
                    .getOrNull()
                    ?.media
                    ?.fileName
            val target = target(store.instanceId, item, name)
            val outcome =
                when (val got = requests?.download { remote.media(item.id) } ?: remote.media(item.id)) {
                    is Outcome.Err -> {
                        RostyLog.w(TAG, "download failed: ${got.error.message}")
                        if (got.error is RustError.NotFound) {
                            WaSyncFetched.Gone(got.error.message)
                        } else {
                            WaSyncFetched.Failed(got.error.message)
                        }
                    }

                    is Outcome.Ok -> {
                        val placed = withContext(io) { place(got.value, target) }
                        if (placed != null) {
                            WaSyncFetched.Ready(placed.absolutePath, placed.length())
                        } else {
                            WaSyncFetched.Failed("the file could not be written")
                        }
                    }
                }
            when (val settled = store.settleMedia(item.chat, item.id, outcome, now())) {
                is Outcome.Ok -> {
                    settled.value.replaced?.let { delete(listOf(it)) }
                }

                is Outcome.Err -> {
                    // The row does not name the file: the next reconcile deletes it.
                    RostyLog.w(TAG, "media_settle failed: ${settled.error.message}")
                }
            }
        } finally {
            active.update { it - item.id }
            publishBusy()
        }
    }

    private fun target(
        instance: String,
        item: WaSyncMediaEntry,
        fileName: String?,
    ): File {
        val name =
            fileName
                ?.let(::safeName)
                ?.takeIf { it.isNotEmpty() }
                ?: "${item.kind.name.lowercase()}.${extensionOf(item.mime, item.kind)}"
        return File(File(File(root(instance), FILES), safe(item.id)), name)
    }

    /**
     * The answer's bytes at [target]: written (or the temp file the core downloaded moved) to `<target>.part` in the
     * same directory, synced, then renamed, so a file under its own name is always whole.
     */
    private fun place(
        answer: BytesAnswer,
        target: File,
    ): File? {
        ram?.let { memory -> return answer.bytes()?.let { memory.put(target.name, it) } }
        val part = File(target.path + PART)
        return try {
            target.parentFile?.mkdirs()
            val temp = answer.path?.let(::File)
            val data = answer.dataB64
            when {
                data != null -> {
                    FileOutputStream(part).use { out ->
                        out.write(Base64.getDecoder().decode(data))
                        out.fd.sync()
                    }
                }

                temp != null && temp.isFile -> {
                    if (!temp.renameTo(part)) {
                        temp.inputStream().use { input ->
                            FileOutputStream(part).use { out ->
                                input.copyTo(out)
                                out.fd.sync()
                            }
                        }
                        temp.delete()
                    }
                }

                else -> {
                    return null
                }
            }
            if (target.exists()) target.delete()
            if (part.renameTo(target)) target else null.also { part.delete() }
        } catch (_: IOException) {
            part.delete()
            null
        } catch (_: IllegalArgumentException) {
            part.delete()
            null
        }
    }

    // ---- the cap

    /** Brings the cache under the stored cap and deletes what the store let go of. Answers the chats it touched. */
    suspend fun evict(store: WaSyncStore): Set<String> {
        val gone =
            store
                .evictMedia(nowMs = now())
                .getOrNull()
                ?.evicted
                .orEmpty()
        if (gone.isEmpty()) return emptySet()
        delete(gone.map { it.path })
        return gone.mapTo(HashSet()) { it.chat }
    }

    /** Deletes [paths], only under [dir]: the store never names anything else, but a path is still a path. */
    private suspend fun delete(paths: List<String>) {
        if (paths.isEmpty()) return
        withContext(io) {
            for (path in paths) {
                if (ram?.owns(path) == true) {
                    ram?.release(path)
                    continue
                }
                val file = File(path)
                if (!file.startsWith(dir)) continue
                file.delete()
                file.parentFile?.takeIf { it != dir && it.list()?.isEmpty() == true }?.delete()
            }
        }
    }

    /**
     * The media of [instance] belongs to an account that is gone (another one was linked, or the link was
     * forgotten): [files] the store named and the rest of the instance's directory are deleted.
     */
    suspend fun wipe(
        instance: String,
        files: List<String>,
    ) {
        delete(files)
        ram?.clear()
        withContext(io) { root(instance).deleteRecursively() }
    }

    /** Frees every file in RAM (lock): the store that named them is closed. */
    fun dropRam() {
        ram?.clear()
    }

    companion object {
        private const val TAG = "wa-downloads"

        /** The bytes of a gateway answer: inline, or the temp file of a large one (read, then deleted at once). */
        internal fun BytesAnswer.bytes(): ByteArray? =
            try {
                dataB64?.let { Base64.getDecoder().decode(it) }
                    ?: path?.let(::File)?.takeIf { it.isFile }?.let { file ->
                        try {
                            file.readBytes()
                        } finally {
                            file.delete()
                        }
                    }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        private const val FILES = "files"
        private const val OUT = "out"
        private const val THUMBS = "thumbs"
        private const val PART = ".part"
        private const val MAX_NAME = 80
        private const val FORBIDDEN = ":*?\"|<>"

        /** A stop for one pass; what is still queued is fetched by the next one. */
        private const val MAX_ROUNDS = 64

        /** Page rows older than this are not offered to the auto-download rules: old history downloads on a tap. */
        const val AUTO_RECENT_MS = 7L * 24 * 60 * 60 * 1000

        internal fun safe(text: String): String {
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

        /** The media messages among stream [events], for [auto]. */
        fun refsOf(events: List<WaSyncEvent>): List<WaMediaRef> =
            events.mapNotNull { event ->
                (event.event as? GatewayEvent.Message)
                    ?.takeIf { it.body is MessageBody.Media }
                    ?.let { WaMediaRef(it.chat, it.id) }
            }

        /** The media messages of a fetched page that are recent enough for [auto]. */
        fun refsOf(
            page: List<RichMessage>,
            nowMs: Long,
        ): List<WaMediaRef> =
            page
                .filter { it.body is MessageBody.Media && nowMs - it.timestampMs <= AUTO_RECENT_MS }
                .map { WaMediaRef(it.chat, it.id) }

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
