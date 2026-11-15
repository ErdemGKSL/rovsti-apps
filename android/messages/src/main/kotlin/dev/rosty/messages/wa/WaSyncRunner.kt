package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.GroupInfo
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncFetch
import dev.rosty.core.model.wa.WaSyncRekeyed
import dev.rosty.core.ops.WaSyncStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What asking for older history of a chat came to. */
enum class OlderResult {
    /** A page was fetched and stored: read the thread again. */
    Stored,

    /** The gateway has nothing older than what is stored. */
    ReachedStart,

    /** The gateway could not be asked (offline); what is stored stays readable. */
    Offline,
}

/**
 * Fills one open [WaSyncStore] from the gateway (E6.4s). The store decides what is owed (`plan`, the pages a chat list
 * names, the next page of a gap); this class makes the calls it asks for and hands the answers back:
 *
 * - [catchUp]: the gateway's status first, every time (its process id, the linked account, the history progress: a
 *   restarted gateway is a gap, another account empties the store), then the LID and number pairs the gateway
 *   learned (so what follows lands on merged chats), then `plan`, the chat list when it is owed and every open gap
 *   page by page until `next` is null;
 * - [refresh]: the pairs, then the chat list again (after a `resync`, or when events were dropped on this side);
 * - [apply]: stream events, oldest first, in batches, `hello` and `account_changed` included;
 * - [older]: the page before the oldest stored message of a chat (the thread reached the end of what is stored);
 * - [observe]: group and profile answers fetched elsewhere in the app, which name people by both ids.
 *
 * [onChange] hears the chats whose rows changed (null: all of them). [onRekeyed] hears every key change the store
 * reports (two chats of one person became one), from whichever call caused it, before [onChange] is told: what is
 * kept per chat outside the store follows it ([WaRekeys]). [onAccountChanged] hears that the store emptied itself for
 * another account, with the media files to delete; it is the only reason anything kept for the link is dropped.
 * [onHistory] is told when the stored history progress may have moved, [onMedia] which media messages arrived.
 * Network fills run one at a time.
 */
class WaSyncRunner(
    private val store: WaSyncStore,
    private val remote: WaRemote,
    private val now: () -> Long,
    private val onChange: (Set<String>?) -> Unit,
    private val pageLimit: Int? = null,
    private val onRekeyed: suspend (List<WaSyncRekeyed>) -> Unit = {},
    private val onAccountChanged: suspend (deleteFiles: List<String>) -> Unit = {},
    private val onHistory: suspend () -> Unit = {},
    private val onMedia: suspend (List<WaMediaRef>) -> Unit = {},
) {
    private val fill = Mutex()

    /** The `seq` the event socket resumes after; null on the first run or when the store cannot be read. */
    suspend fun afterSeq(): Long? = store.plan(pageLimit).getOrNull()?.afterSeq

    /** Does what the store's plan owes. False when the gateway could not be reached for part of it. */
    suspend fun catchUp(): Boolean =
        fill.withLock {
            // The status first: a gap or another account must be known before anything is stored. Then identity:
            // the chat list and the pages that follow are stored under the merged keys.
            val stale = statusLocked()
            val merged = identityLocked()
            val plan = store.plan(pageLimit).getOrNull() ?: return@withLock false
            if (plan.refreshChats || merged || stale) refreshLocked(null) else pages(plan.fetch)
        }

    /**
     * Fetches the chat list and the pages it opens. [recentMs] also refetches a page of the chats active that recently
     * (edits, deletes and ticks that an event gap may have hidden).
     */
    suspend fun refresh(recentMs: Long? = null): Boolean =
        fill.withLock {
            identityLocked()
            refreshLocked(recentMs)
        }

    /**
     * Catches up on the gateway's pairs from the stored cursor. An old gateway that lists none is asked once per open
     * store. True when a merge moved rows: the chat list is refreshed.
     */
    private suspend fun identityLocked(): Boolean {
        val caught = store.identityCatchUp { since -> remote.identityMappings(since) }.getOrNull() ?: return false
        caught.error?.let { RostyLog.w(TAG, "identity catch-up stopped: ${it.message}") }
        followed(caught.rekeyed)
        return caught.resync
    }

    /**
     * Hands the gateway's whole status to the store: the account's own number and LID (its own messages are "mine"
     * under either), the id of the gateway process, the account epoch and the history progress. True when the chat
     * list must be fetched again (a gap, a merge, another account).
     */
    private suspend fun statusLocked(): Boolean {
        val status = remote.status().getOrNull() ?: return false
        val applied = store.observe(now(), status = status).getOrNull() ?: return false
        if (applied.accountChanged) onAccountChanged(applied.deleteFiles)
        followed(applied.rekeyed)
        onHistory()
        return applied.resync || applied.accountChanged
    }

    private suspend fun followed(steps: List<WaSyncRekeyed>) {
        if (steps.isEmpty()) return
        onRekeyed(steps)
        // The rows of two keys moved: every reader reads again.
        onChange(null)
    }

    private suspend fun refreshLocked(recentMs: Long?): Boolean {
        val chats = remote.chats().getOrNull() ?: return false
        val stored =
            when (val out = store.storeChats(chats, now(), pageLimit, recentMs)) {
                is Outcome.Ok -> {
                    out.value
                }

                is Outcome.Err -> {
                    RostyLog.w(TAG, "store_chats failed: ${out.error.message}")
                    return false
                }
            }
        followed(stored.rekeyed)
        onChange(null)
        return pages(stored.fetch)
    }

    /** Fetches [first] and, per chat, the pages that follow until the store says the gap is closed. */
    private suspend fun pages(first: List<WaSyncFetch>): Boolean {
        var whole = true
        for (start in first) {
            var next: WaSyncFetch? = start
            var fetched = 0
            while (next != null && fetched < MAX_PAGES_PER_CHAT) {
                val asked = next
                val page = remote.page(asked).getOrNull()
                if (page == null) {
                    whole = false
                    break
                }
                val stored = store.storePage(asked, page, now()).getOrNull()
                if (stored == null) {
                    whole = false
                    break
                }
                fetched++
                followed(stored.rekeyed)
                onChange(setOf(asked.chat))
                onMedia(WaDownloads.refsOf(page, now()))
                // A store that asks for the same page again would loop forever.
                next = stored.next?.takeIf { it != asked }
            }
        }
        return whole
    }

    /**
     * Applies stream events (oldest first), every one of the socket as it came. The answer's `resync` or
     * `accountChanged` asks for [refresh].
     */
    suspend fun apply(events: List<WaSyncEvent>): WaSyncApplied? {
        if (events.isEmpty()) return null
        val applied =
            when (val out = store.applyEvents(events)) {
                is Outcome.Ok -> {
                    out.value
                }

                is Outcome.Err -> {
                    RostyLog.w(TAG, "apply_events failed: ${out.error.message}")
                    return null
                }
            }
        if (applied.accountChanged) onAccountChanged(applied.deleteFiles)
        followed(applied.rekeyed)
        if (applied.accountChanged) {
            onChange(null)
        } else if (applied.chats.isNotEmpty() || applied.reconciled.isNotEmpty()) {
            onChange(applied.chats.toSet())
        }
        if (applied.accountChanged || events.any { it.event.movesHistory() }) onHistory()
        onMedia(WaDownloads.refsOf(events))
        return applied
    }

    /** Fetches the page before the oldest stored message of [chat]. */
    suspend fun older(chat: String): OlderResult =
        fill.withLock {
            val fetch = store.older(chat, pageLimit).getOrNull() ?: return@withLock OlderResult.ReachedStart
            val page = remote.page(fetch).getOrNull() ?: return@withLock OlderResult.Offline
            val stored = store.storePage(fetch, page, now()).getOrNull() ?: return@withLock OlderResult.Offline
            followed(stored.rekeyed)
            onChange(setOf(chat))
            if (stored.reachedStart && stored.new == 0) OlderResult.ReachedStart else OlderResult.Stored
        }

    /**
     * Hands in what the gateway answered outside the sync ([groups] of `wa.group(s)`, [profiles] of `wa.profiles`):
     * members and profiles name a person by LID and by number. A merge it causes is followed and the chat list is
     * fetched again.
     */
    suspend fun observe(
        groups: List<GroupInfo> = emptyList(),
        profiles: List<WaProfile> = emptyList(),
    ) {
        if (groups.isEmpty() && profiles.isEmpty()) return
        val applied = store.observe(now(), groups = groups, profiles = profiles).getOrNull() ?: return
        followed(applied.rekeyed)
        if (applied.resync) refresh()
    }

    private fun GatewayEvent.movesHistory(): Boolean = this is GatewayEvent.State || this is GatewayEvent.HistorySynced

    private companion object {
        const val TAG = "wa-sync"

        /** A stop for one run; the gap stays open in the store and the next run continues it. */
        const val MAX_PAGES_PER_CHAT = 40
    }
}
