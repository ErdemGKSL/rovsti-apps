package dev.rosty.messages.starred

import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.chat.LocalStar
import dev.rosty.core.model.chat.LocalStarAction
import dev.rosty.core.model.chat.LocalStarsRequest
import dev.rosty.core.model.chat.StarCandidate
import dev.rosty.core.model.chat.StarredRowsRequest
import dev.rosty.core.ops.ChatOps
import dev.rosty.messages.conversation.BubbleState
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.data.MessagesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import dev.rosty.core.model.messages.LocalStar as StoredStar

/** A message as Star and Remind me take it from a bubble. */
data class MarkTarget(
    /** The conversation: `sms:<thread>` or `wa:<jid>`. */
    val tag: String,
    /** `sms:12`, `mms:7`, or the WhatsApp message id. */
    val id: String,
    val atMs: Long,
    /** Who wrote it, as the Starred list shows it ("Me", the sender, the chat's title). */
    val author: String,
    val preview: String,
    /** The chat's title (the title of a reminder's notification). */
    val title: String,
)

/**
 * The [MarkTarget] of a bubble in the conversation [tag], or null for one that cannot be starred or reminded of: an
 * SMS still without a provider id, a WhatsApp message still in the outbox or deleted.
 */
fun BubbleUi.markTarget(
    tag: String,
    title: String,
    me: String,
): MarkTarget? {
    val markable =
        if (tag.startsWith(SMS_TAG)) {
            key.startsWith(SMS_TAG) || key.startsWith(MMS_KEY)
        } else {
            !deleted && state != BubbleState.Waiting && state != BubbleState.Sending && state != BubbleState.Failed
        }
    if (!markable) return null
    val preview = text.ifBlank { media.firstOrNull()?.label.orEmpty() }
    return MarkTarget(tag, markId ?: key, atMs, if (outgoing) me else sender ?: title, preview, title)
}

/** A starred WhatsApp message found in the offline store. */
data class WaStarRow(
    val chat: String,
    val id: String,
    val atMs: Long,
    val author: String,
    val preview: String,
    /** The chat's title. */
    val title: String = "",
)

/**
 * A star or unstar of a WhatsApp message made on this phone (`wa-stars.json`). While it is [pending] (the gateway has
 * not taken it yet) and for a short while after, it overrides what the store says; a starred one stays as the Starred
 * list's own copy until the store shows the message unstarred.
 */
@Serializable
data class WaStarEntry(
    val chat: String,
    val starred: Boolean,
    val pending: Boolean,
    @SerialName("settled_ms") val settledMs: Long = 0,
    @SerialName("at_ms") val atMs: Long,
    val author: String,
    val preview: String,
    val title: String = "",
)

/** What [StarCenter] needs of the WhatsApp side; the container wires the offline store and the gateway. */
class WaStarSeams(
    /** The gateway can be reached. */
    val online: StateFlow<Boolean>,
    /** Emits when stored WhatsApp rows changed. */
    val changes: Flow<*>,
    /** The starred messages in the stored recent pages; null while WhatsApp is not linked. */
    val scan: suspend () -> List<WaStarRow>?,
    /** The gateway's star call; false when it was not taken. */
    val star: suspend (chat: String, id: String, starred: Boolean) -> Boolean,
)

/** One row of the Starred list. */
data class StarredItem(
    val id: String,
    /** `sms:<thread>` or `wa:<jid>`. */
    val tag: String,
    val author: String,
    /** One line (Rust `starred.rows`). */
    val preview: String,
    val atMs: Long,
    /** The chat's title when the star knows it (WhatsApp); an SMS star knows only its author. */
    val title: String? = null,
)

/** The stars a conversation page lays over its bubbles, by message id. */
data class StarMarks(
    val on: Set<String> = emptySet(),
    /** Unstarred here while the store still says starred. */
    val off: Set<String> = emptySet(),
)

/**
 * The stars of the app (E6.13). SMS/MMS have no provider star: theirs live in `messages-prefs.json` (`sms_stars`) and
 * every change goes through Rust's `starred.local` (M23). A WhatsApp star is the account's own (M10): it is shown at
 * once, kept in `wa-stars.json` and sent to the gateway now or when the connection is back; the store's `starred` flag
 * follows from the gateway's event. [items] is the Starred list: both kinds, newest first (`starred.rows`).
 */
class StarCenter(
    private val chat: ChatOps,
    private val store: MessagesStore,
    private val wa: WaStarSeams,
    private val file: File?,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val graceMs: Long = GRACE_MS,
) {
    private val disk = Mutex()
    private val started = AtomicBoolean(false)
    private var loaded = false
    private val entries = MutableStateFlow<Map<String, WaStarEntry>>(emptyMap())
    private val scanned = MutableStateFlow<List<WaStarRow>>(emptyList())
    private val list = MutableStateFlow<List<StarredItem>>(emptyList())

    /** The Starred list, newest first. */
    val items: StateFlow<List<StarredItem>> = list.asStateFlow()

    /** Follows the stars from now on. Idempotent. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            store.load()
            load()
            rescan()
            launch { wa.online.collect { if (it) flush() } }
            launch { wa.changes.debounce(SCAN_DEBOUNCE_MS).collect { rescan() } }
            combine(
                store.prefs.map { it?.smsStars.orEmpty() }.distinctUntilChanged(),
                entries,
                scanned,
            ) { sms, own, rows ->
                candidates(sms, own, rows)
            }.collectLatest { found -> list.value = sorted(found) }
        }
    }

    /** The stars to lay over the bubbles of the conversation [tag]. */
    fun marks(tag: String): Flow<StarMarks> =
        if (tag.startsWith(SMS_TAG)) {
            val thread = tag.removePrefix(SMS_TAG)
            store.prefs
                .map { prefs ->
                    StarMarks(
                        prefs
                            ?.smsStars
                            .orEmpty()
                            .filterValues { it.threadId == thread }
                            .keys,
                    )
                }.distinctUntilChanged()
        } else {
            val jid = tag.removePrefix(WA_TAG)
            entries
                .map { all ->
                    val mine = all.filterValues { it.chat == jid && overrides(it) }
                    StarMarks(mine.filterValues { it.starred }.keys, mine.filterValues { !it.starred }.keys)
                }.distinctUntilChanged()
        }

    /** A pending change, or one the gateway took so recently that the store may not know yet. */
    private fun overrides(entry: WaStarEntry): Boolean = entry.pending || now() - entry.settledMs < graceMs

    /** Stars or unstars [target]. False when it could not be kept (the prefs file is unreadable, Rust refused). */
    suspend fun set(
        target: MarkTarget,
        starred: Boolean,
    ): Boolean = if (target.tag.startsWith(SMS_TAG)) setSms(target, starred) else setWa(target, starred)

    private suspend fun setSms(
        target: MarkTarget,
        starred: Boolean,
    ): Boolean {
        if (store.load() is Outcome.Err) return false
        val thread = target.tag.removePrefix(SMS_TAG)
        val current =
            store.prefs.value
                ?.smsStars
                .orEmpty()
                .mapValues { (_, s) -> LocalStar(s.threadId, s.atMs, s.author, s.preview) }
        val request =
            LocalStarsRequest(
                stars = current,
                action = if (starred) LocalStarAction.Set else LocalStarAction.Unset,
                id = target.id,
                star = LocalStar(thread, target.atMs, target.author, target.preview).takeIf { starred },
                threadId = thread,
            )
        val answer = chat.localStars(request).getOrNull() ?: return false
        val next = answer.stars.mapValues { (_, s) -> StoredStar(s.threadId, s.atMs, s.author, s.preview) }
        store.updatePrefs { it.copy(smsStars = next) }
        return answer.starred == starred
    }

    private suspend fun setWa(
        target: MarkTarget,
        starred: Boolean,
    ): Boolean {
        load()
        val jid = target.tag.removePrefix(WA_TAG)
        put(
            target.id,
            WaStarEntry(
                jid,
                starred,
                pending = true,
                atMs = target.atMs,
                author = target.author,
                preview = target.preview,
                title = target.title,
            ),
        )
        if (wa.online.value) scope.launch { flush() }
        return true
    }

    /**
     * A conversation page saw these WhatsApp messages of [jid] with the store's flag: an entry the store contradicts
     * after the grace time was changed elsewhere (another phone) and goes; a settled unstar is done and goes too.
     */
    suspend fun seen(
        jid: String,
        stored: Map<String, Boolean>,
    ) {
        val stale =
            entries.value.filter { (id, e) ->
                e.chat == jid && !overrides(e) && (!e.starred || stored[id] == false)
            }
        if (stale.isNotEmpty()) change { it - stale.keys }
    }

    /** Reads the store's starred rows again (the Starred page opened). */
    suspend fun rescan() {
        wa.scan()?.let { scanned.value = it }
    }

    /** Sends every pending change; what the gateway does not take stays pending. */
    private suspend fun flush() {
        for ((id, entry) in entries.value.filterValues { it.pending }) {
            if (!wa.star(entry.chat, id, entry.starred)) continue
            change { all ->
                // Changed again meanwhile: the newer wish is still to be sent.
                if (all[id] == entry) all + (id to entry.copy(pending = false, settledMs = now())) else all
            }
        }
    }

    private fun candidates(
        sms: Map<String, StoredStar>,
        own: Map<String, WaStarEntry>,
        rows: List<WaStarRow>,
    ): Map<String, StarredItem> {
        val out = LinkedHashMap<String, StarredItem>()
        for ((id, s) in sms) out[id] = StarredItem(id, SMS_TAG + s.threadId, s.author, s.preview, s.atMs)
        for (r in rows) out[r.id] = StarredItem(r.id, WA_TAG + r.chat, r.author, r.preview, r.atMs, r.title)
        for ((id, e) in own) {
            when {
                e.starred -> out.getOrPut(id) { StarredItem(id, WA_TAG + e.chat, e.author, e.preview, e.atMs, e.title) }
                overrides(e) -> out.remove(id)
            }
        }
        return out
    }

    /** Rust's order and one-line previews (`starred.rows`); without an answer the newest still comes first. */
    private suspend fun sorted(found: Map<String, StarredItem>): List<StarredItem> {
        if (found.isEmpty()) return emptyList()
        val request =
            StarredRowsRequest(found.values.map { StarCandidate(it.id, it.author, it.preview, it.atMs, true) })
        val rows = chat.starredRows(request).getOrNull()?.rows
        return rows?.mapNotNull { row -> found[row.id]?.copy(preview = row.preview) }
            ?: found.values.sortedByDescending { it.atMs }
    }

    private suspend fun put(
        id: String,
        entry: WaStarEntry,
    ) = change { all ->
        val next = all + (id to entry)
        // The oldest settled copies go first when the file grows past its cap.
        if (next.size <= MAX_ENTRIES) {
            next
        } else {
            next -
                next.entries
                    .filter { !it.value.pending }
                    .sortedBy { it.value.atMs }
                    .take(
                        next.size - MAX_ENTRIES,
                    ).map { it.key }
        }
    }

    private suspend fun change(edit: (Map<String, WaStarEntry>) -> Map<String, WaStarEntry>) {
        disk.withLock {
            val next = edit(entries.value)
            if (next == entries.value) return
            entries.value = next
            val text = Json.encodeToString(FILE_FORMAT, next)
            withContext(io) {
                try {
                    file?.parentFile?.mkdirs()
                    file?.writeText(text)
                } catch (e: IOException) {
                    RostyLog.w(TAG, "wa-stars.json was not written: ${e.message}")
                }
            }
        }
    }

    private suspend fun load() {
        disk.withLock {
            if (loaded) return
            loaded = true
            entries.value = withContext(io) { read() }
        }
    }

    private fun read(): Map<String, WaStarEntry> =
        try {
            file?.takeIf { it.isFile }?.readText()?.let { LENIENT.decodeFromString(FILE_FORMAT, it) } ?: emptyMap()
        } catch (_: IOException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }

    companion object {
        const val FILE = "wa-stars.json"
        private const val TAG = "stars"

        /** How long a change the gateway took overrides the store (its event is on the way). */
        const val GRACE_MS = 15_000L
        private const val SCAN_DEBOUNCE_MS = 1_500L
        private const val MAX_ENTRIES = 1_000
        private val FILE_FORMAT = MapSerializer(String.serializer(), WaStarEntry.serializer())
        private val LENIENT = Json { ignoreUnknownKeys = true }
    }
}

internal const val SMS_TAG = "sms:"
internal const val WA_TAG = "wa:"
private const val MMS_KEY = "mms:"
