package dev.rosty.messages.wa.status

import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.StatusAuthor
import dev.rosty.core.model.wa.StatusItem
import dev.rosty.core.model.wa.StatusList
import dev.rosty.messages.wa.WaDownloads
import dev.rosty.messages.wa.WaOffline
import dev.rosty.messages.wa.WaRamFiles
import dev.rosty.messages.wa.WaRemote
import dev.rosty.wa.sync.WaRequestGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WhatsApp Status updates of the linked account (GPUI `StatusFeed`): the gateway's list, fetched when the gateway
 * becomes reachable and after each `status_changed` event, shared by the Status row and the viewer. An update the user
 * saw shows as seen at once and its author gets a read receipt; a reload that races the receipt keeps it seen.
 *
 * Status updates are not part of the offline store (they live for a day): offline, the last list fetched in this
 * process stays, with the pictures already downloaded into [dir].
 */
class StatusFeed(
    private val scope: CoroutineScope,
    private val offline: WaOffline,
    private val remote: WaRemote,
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Since the vault: the pictures live here in RAM and never under [dir] (`plans/wa-vault.md` 13.1). */
    private val ram: WaRamFiles? = null,
    /**
     * The first list waits this long after the gateway is reachable: the cold start keeps to its four requests
     * (`plans/wa-vault.md` 14) and Status comes with the steady budget.
     */
    private val firstDelayMs: Long = 0,
    /** Every Status request goes through it; null: unlimited (tests of other things). */
    private val gate: WaRequestGate? = null,
) {
    /** The pictures in [ram] by file key. */
    private val held = java.util.concurrent.ConcurrentHashMap<String, File>()
    private val state = MutableStateFlow(StatusList())
    val list: StateFlow<StatusList> = state.asStateFlow()

    private val reloads =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val started = AtomicBoolean(false)
    private val seen = HashSet<String>()
    private val queue = ArrayList<String>()
    private val sending = Mutex()

    /** Follows the link, the connection and `status_changed`. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            offline.session.collectLatest { session ->
                if (session == null) {
                    clear()
                    return@collectLatest
                }
                launch {
                    var first = true
                    offline.online.collect {
                        if (!it) return@collect
                        if (first) delay(firstDelayMs)
                        first = false
                        reloads.tryEmit(Unit)
                    }
                }
                launch { offline.live.filterIsInstance<GatewayEvent.StatusChanged>().collect { reloads.tryEmit(Unit) } }
                reloads.collect { reload() }
            }
        }
    }

    private fun clear() {
        state.value = StatusList()
        ram?.let { memory -> held.values.forEach { memory.release(it.path) } }
        held.clear()
        synchronized(seen) {
            seen.clear()
            queue.clear()
        }
    }

    /** Fetches the list again; what was seen here stays seen. */
    suspend fun reload() {
        val fetched = keyed(request { remote.statuses() }.getOrNull() ?: return)
        val known = synchronized(seen) { seen.toSet() }
        state.value = fetched.markViewed(known)
        flush()
    }

    /**
     * The list with every author under the key the store knows the person by: an author the gateway names by a LID
     * is the person of the number's chat, and one person named by both ids is one entry.
     */
    private suspend fun keyed(list: StatusList): StatusList {
        val byKey = LinkedHashMap<String, StatusAuthor>()
        for (author in list.authors) {
            val key = offline.keyOf(author.author)
            val items = author.items.map { if (it.author == key) it else it.copy(author = key) }
            val known = byKey[key]
            byKey[key] =
                if (known == null) {
                    author.copy(author = key, items = items)
                } else {
                    known.copy(
                        name = known.name ?: author.name,
                        items = (known.items + items).sortedBy { it.timestampMs },
                    )
                }
        }
        return list.copy(authors = byKey.values.toList())
    }

    /** The user saw [id]: seen at once, and a read receipt goes to its author. */
    fun viewed(id: String) {
        synchronized(seen) {
            if (!seen.add(id)) return
            queue += id
        }
        state.update { it.markViewed(listOf(id)) }
        scope.launch { flush() }
    }

    private suspend fun flush() {
        sending.withLock {
            val ids = synchronized(seen) { queue.toList() }
            if (ids.isEmpty()) return
            if (request {
                    remote.markStatusViewed(
                        ids,
                    )
                } is Outcome.Ok
            ) {
                synchronized(seen) { queue.removeAll(ids.toSet()) }
            }
        }
    }

    /**
     * The picture of [item] as a file: its preview ([thumbnail]) or the photo itself. Downloaded once into the cache;
     * null when it cannot be fetched (offline, or the update has none).
     */
    suspend fun picture(
        item: StatusItem,
        thumbnail: Boolean,
    ): File? {
        val name = (if (thumbnail) "t-" else "m-") + item.id.filter { it.isLetterOrDigit() }
        val file = File(dir, name)
        if (ram != null) {
            held[name]?.let { return it }
        } else if (withContext(io) { file.length() > 0 }) {
            return file
        }
        if (thumbnail && item.media?.thumbnail != true) return null
        val answer =
            request { if (thumbnail) remote.statusThumbnail(item.id) else remote.statusMedia(item.id) }.getOrNull()
                ?: return null
        if (ram != null) {
            val bytes = withContext(io) { with(WaDownloads) { answer.bytes() } } ?: return null
            return ram.put(name, bytes)?.also { held[name] = it }
        }
        return withContext(io) { answer.writeTo(file) }
    }

    /** Frees the pictures in RAM and forgets the list (lock). */
    fun drop() {
        clear()
    }

    private suspend fun <T> request(block: suspend () -> Outcome<T>): Outcome<T> = gate?.call(block) ?: block()
}

/** Writes the bytes of a gateway answer (inline base64, or the temp file of a large one) to [file]. */
internal fun BytesAnswer.writeTo(file: File): File? =
    try {
        file.parentFile?.mkdirs()
        val inline = dataB64
        val temp = path
        when {
            inline != null -> file.writeBytes(Base64.getDecoder().decode(inline))
            temp != null -> File(temp).copyTo(file, overwrite = true)
            else -> return null
        }
        file
    } catch (_: java.io.IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
