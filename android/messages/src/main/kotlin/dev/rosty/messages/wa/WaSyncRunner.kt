package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.core.getOrNull
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncFetch
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
 * - [catchUp]: `plan`, then the chat list when it is owed and every open gap page by page until `next` is null;
 * - [refresh]: the chat list again (after a `resync`, or when events were dropped on this side);
 * - [apply]: stream events, oldest first, in batches;
 * - [older]: the page before the oldest stored message of a chat (the thread reached the end of what is stored).
 *
 * [onChange] hears the chats whose rows changed (null: all of them). Network fills run one at a time.
 */
class WaSyncRunner(
    private val store: WaSyncStore,
    private val remote: WaRemote,
    private val now: () -> Long,
    private val onChange: (Set<String>?) -> Unit,
    private val pageLimit: Int? = null,
) {
    private val fill = Mutex()

    /** The `seq` the event socket resumes after; null on the first run or when the store cannot be read. */
    suspend fun afterSeq(): Long? = store.plan(pageLimit).getOrNull()?.afterSeq

    /** Does what the store's plan owes. False when the gateway could not be reached for part of it. */
    suspend fun catchUp(): Boolean =
        fill.withLock {
            val plan = store.plan(pageLimit).getOrNull() ?: return@withLock false
            var whole = true
            if (plan.refreshChats) {
                whole = refreshLocked(null)
            } else {
                whole = pages(plan.fetch)
            }
            whole
        }

    /**
     * Fetches the chat list and the pages it opens. [recentMs] also refetches a page of the chats active that recently
     * (edits, deletes and ticks that an event gap may have hidden).
     */
    suspend fun refresh(recentMs: Long? = null): Boolean = fill.withLock { refreshLocked(recentMs) }

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
                onChange(setOf(asked.chat))
                // A store that asks for the same page again would loop forever.
                next = stored.next?.takeIf { it != asked }
            }
        }
        return whole
    }

    /** Applies stream events (oldest first); the answer's `resync` asks for [refresh]. */
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
        if (applied.chats.isNotEmpty() || applied.reconciled.isNotEmpty()) onChange(applied.chats.toSet())
        return applied
    }

    /** Fetches the page before the oldest stored message of [chat]. */
    suspend fun older(chat: String): OlderResult =
        fill.withLock {
            val fetch = store.older(chat, pageLimit).getOrNull() ?: return@withLock OlderResult.ReachedStart
            val page = remote.page(fetch).getOrNull() ?: return@withLock OlderResult.Offline
            val stored = store.storePage(fetch, page, now()).getOrNull() ?: return@withLock OlderResult.Offline
            onChange(setOf(chat))
            if (stored.reachedStart && stored.new == 0) OlderResult.ReachedStart else OlderResult.Stored
        }

    private companion object {
        const val TAG = "wa-sync"

        /** A stop for one run; the gap stays open in the store and the next run continues it. */
        const val MAX_PAGES_PER_CHAT = 40
    }
}
