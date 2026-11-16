package dev.rosty.messages.wa

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.WaSyncCursor
import dev.rosty.core.model.wa.WaSyncGalleryPage
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One page of `wa_sync.chat_media` (newest first); null when the store could not answer now. */
fun interface WaGallerySource {
    suspend fun page(
        before: WaSyncCursor?,
        after: WaSyncCursor?,
        limit: Int,
    ): WaSyncGalleryPage?

    companion object {
        /** The photos and videos of [chat] in the store that is open now. */
        fun of(
            offline: WaOffline,
            chat: String,
        ): WaGallerySource =
            WaGallerySource { before, after, limit ->
                offline.session.value
                    ?.store
                    ?.chatMedia(chat, limit, before, after)
                    ?.getOrNull()
            }
    }
}

/**
 * The photos and videos of one chat as the viewer pages them (E6-WA R3), oldest first, read from the store a page at a
 * time on both sides of the message it was opened on. The opened message is there at once; [start] reads a page on
 * each side of it, [ensure] reads the next one when the viewer comes near an end. Pages put in front are counted in
 * [prepended] in the same snapshot, so the viewer keeps the photo on show.
 *
 * A message the store's gallery leaves out (view once, a sticker, a send still in the outbox) is shown alone: there is
 * nothing to page to from it.
 */
@Stable
class WaGallery(
    opened: WaSyncMessage,
    source: WaGallerySource?,
    private val pageSize: Int = PAGE,
) {
    private val source: WaGallerySource? = source?.takeIf { pages(opened) }

    /** Oldest first. */
    var items: List<WaSyncMessage> by mutableStateOf(listOf(opened))
        private set

    /** How many items were put in front of the first one since it opened. */
    var prepended: Int by mutableIntStateOf(0)
        private set

    private var older: WaSyncCursor? = null
    private var newer: WaSyncCursor? = null
    private var olderDone = this.source == null
    private var newerDone = this.source == null
    private val lock = Mutex()

    /** Whether there may be more items before the first one / after the last one. */
    val hasOlder: Boolean get() = !olderDone
    val hasNewer: Boolean get() = !newerDone

    /** The index of the message [id]; -1 when it is not loaded. */
    fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }

    /** The first page on each side of the opened message. */
    suspend fun start() {
        loadOlder()
        loadNewer()
    }

    /** Reads the next page on the side [index] is near ([EDGE] items or fewer to go). */
    suspend fun ensure(index: Int) {
        var at = index
        // Items put in front move the one at [index] along.
        if (hasOlder && at < EDGE) at += loadOlder()
        if (hasNewer && at >= items.size - EDGE) loadNewer()
    }

    /** The page before the first item; answers how many were put in front. */
    suspend fun loadOlder(): Int =
        lock.withLock {
            val from = source ?: return@withLock 0
            if (olderDone) return@withLock 0
            val cursor = older ?: cursorOf(items.first())
            val page = from.page(before = cursor, after = null, limit = pageSize) ?: return@withLock 0
            val known = items.mapTo(HashSet()) { it.id }
            val fresh = page.items.filter { it.id !in known }.asReversed()
            Snapshot.withMutableSnapshot {
                items = fresh + items
                prepended += fresh.size
            }
            older = page.older
            if (page.older == null) olderDone = true
            fresh.size
        }

    /** The page after the last item; answers how many were added. */
    suspend fun loadNewer(): Int =
        lock.withLock {
            val from = source ?: return@withLock 0
            if (newerDone) return@withLock 0
            val cursor = newer ?: cursorOf(items.last())
            val page = from.page(before = null, after = cursor, limit = pageSize) ?: return@withLock 0
            val known = items.mapTo(HashSet()) { it.id }
            val fresh = page.items.filter { it.id !in known }.asReversed()
            items = items + fresh
            newer = page.newer
            if (page.newer == null) newerDone = true
            fresh.size
        }

    /** The item [row] as it is now (its file arrived): the same place, the new row. */
    fun replace(row: WaSyncMessage) {
        val at = indexOf(row.id)
        if (at < 0) return
        items = items.toMutableList().also { it[at] = row }
    }

    companion object {
        const val PAGE = 30

        /** How close to an end the viewer gets before the next page is read. */
        const val EDGE = 3

        private fun cursorOf(row: WaSyncMessage) = WaSyncCursor(row.timestampMs, row.id)

        /** Whether the store's gallery holds [row], so the viewer can page from it. */
        fun pages(row: WaSyncMessage): Boolean =
            (row.kind == WaSyncKind.Image || row.kind == WaSyncKind.Video) &&
                row.media?.viewOnce != true &&
                !row.deleted &&
                row.state !in OUTBOX
    }
}

private val OUTBOX =
    setOf(
        dev.rosty.core.model.wa.WaSyncRowState.Waiting,
        dev.rosty.core.model.wa.WaSyncRowState.Sending,
        dev.rosty.core.model.wa.WaSyncRowState.Failed,
    )
