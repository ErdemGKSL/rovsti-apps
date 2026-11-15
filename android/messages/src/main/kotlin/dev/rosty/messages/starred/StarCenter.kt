package dev.rosty.messages.starred

import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.LocalStar
import dev.rosty.core.model.chat.LocalStarAction
import dev.rosty.core.model.chat.LocalStarsRequest
import dev.rosty.core.model.chat.StarCandidate
import dev.rosty.core.model.chat.StarredRowsRequest
import dev.rosty.core.ops.ChatOps
import dev.rosty.messages.conversation.BubbleState
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.data.MessagesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

/** A starred WhatsApp message of the offline store. */
data class WaStarRow(
    val chat: String,
    val id: String,
    val atMs: Long,
    val author: String,
    val preview: String,
    /** The chat's title. */
    val title: String = "",
)

/** What [StarCenter] needs of the WhatsApp side; the container wires the offline store and its outbox. */
class WaStarSeams(
    /** Emits when stored WhatsApp rows changed. */
    val changes: Flow<*>,
    /** The starred messages of every chat (`wa_sync.starred`), newest first; null while WhatsApp is not linked. */
    val starred: suspend () -> List<WaStarRow>?,
    /**
     * Queues a star or unstar in the store's outbox (`wa_sync.outbox_enqueue`): the stored flag changes at once, the
     * gateway is told now or when the connection is back. False when it was not queued (WhatsApp is not linked).
     */
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
    val off: Set<String> = emptySet(),
)

/**
 * The stars of the app (E6.13). SMS/MMS have no provider star: theirs live in `messages-prefs.json` (`sms_stars`) and
 * every change goes through Rust's `starred.local` (M23). A WhatsApp star is the account's own (M10): it is an action
 * of the offline store's outbox, so the store shows it at once, sends it now or when the connection is back (also from
 * the outbox job while the app is closed) and takes it back when the gateway refuses it. [items] is the Starred list:
 * both kinds, newest first (`starred.rows`); the WhatsApp ones are the store's starred messages of every chat.
 */
class StarCenter(
    private val chat: ChatOps,
    private val store: MessagesStore,
    private val wa: WaStarSeams,
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val stored = MutableStateFlow<List<WaStarRow>>(emptyList())
    private val list = MutableStateFlow<List<StarredItem>>(emptyList())

    /** The Starred list, newest first. */
    val items: StateFlow<List<StarredItem>> = list.asStateFlow()

    /** Follows the stars from now on. Idempotent. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            store.load()
            rescan()
            launch { wa.changes.debounce(SCAN_DEBOUNCE_MS).collect { rescan() } }
            combine(
                store.prefs.map { it?.smsStars.orEmpty() }.distinctUntilChanged(),
                stored,
            ) { sms, rows ->
                candidates(sms, rows)
            }.collectLatest { found -> list.value = sorted(found) }
        }
    }

    /**
     * The stars to lay over the bubbles of the conversation [tag]. A WhatsApp bubble already carries the store's flag
     * (a star in the outbox included), so nothing is laid over it.
     */
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
            flowOf(StarMarks())
        }

    /**
     * Stars or unstars [target]. False when it could not be kept (the prefs file is unreadable, Rust refused, WhatsApp
     * is not linked).
     */
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
        if (!wa.star(target.tag.removePrefix(WA_TAG), target.id, starred)) return false
        // The store has the flag already: the list follows now, not after the debounce.
        rescan()
        return true
    }

    /** Reads the store's starred messages again (the Starred page opened, a star was queued, rows changed). */
    suspend fun rescan() {
        stored.value = wa.starred().orEmpty()
    }

    private fun candidates(
        sms: Map<String, StoredStar>,
        rows: List<WaStarRow>,
    ): Map<String, StarredItem> {
        val out = LinkedHashMap<String, StarredItem>()
        for ((id, s) in sms) out[id] = StarredItem(id, SMS_TAG + s.threadId, s.author, s.preview, s.atMs)
        for (r in rows) out[r.id] = StarredItem(r.id, WA_TAG + r.chat, r.author, r.preview, r.atMs, r.title)
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

    private companion object {
        const val SCAN_DEBOUNCE_MS = 1_500L
    }
}

internal const val SMS_TAG = "sms:"
internal const val WA_TAG = "wa:"
private const val MMS_KEY = "mms:"
