package dev.rosty.phone.wa

import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.CallSummary
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.ops.WaIdentityBook
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.WaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Keeps Phone's own identity table ([WaIdentityBook], `wa-identity.json`) current (WA-R2). Phone shows no WhatsApp
 * messages and does not open the offline store of Messages; this table is what lets a call whose peer arrives as a
 * LID, with no number next to it, still get a number and a name.
 *
 * When it works:
 * - the link settles or changes: the table of the link's instance is opened (no link: the table is removed);
 * - the instance is connected: the account of the status is noted (another own number or account epoch empties the
 *   table) and the gateway's pairs are fetched from the stored cursor, page by page;
 * - the event socket opens again (`hello`) or reports a gap (`resync`): the same catch-up, from the cursor;
 * - `identity_mapped`, `identity_number_changed`, `account_changed` and a state with another account arrive: the
 *   table takes them in, and an emptied table is filled again from the start;
 * - the call list changes: every `peer` / `peer_alt` pair it names is learned.
 *
 * [instanceId] is the instance of the stored link (null without one, an error when the link file cannot be read);
 * [fetch] is one page of `wa.identity_mappings` (null while there is no gateway).
 */
class WaIdentitySync(
    private val wa: WaRepository,
    private val book: WaIdentityBook,
    private val instanceId: suspend () -> Outcome<String?>,
    private val fetch: suspend (since: String?) -> Outcome<IdentityMappingsAnswer>?,
    private val calls: Flow<List<CallSummary>>,
    private val scope: CoroutineScope,
    /** Waits for what [book] needs before its first call (the Rust core of a cold process). */
    private val ready: suspend () -> Unit = {},
) {
    private val catchUps = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    /** Starts following the link, the events and the calls. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            ready()
            combine(wa.linkState, wa.settled) { state, settled -> state to settled }.collectLatest { (state, settled) ->
                if (settled) onLink(state)
            }
        }
        scope.launch {
            ready()
            wa.events.collect { event ->
                val emptied = book.onEvent(event)
                if (emptied || event is GatewayEvent.Hello || event is GatewayEvent.Resync) catchUps.trySend(Unit)
            }
        }
        scope.launch {
            ready()
            calls.collect { book.learnCalls(it) }
        }
        scope.launch {
            for (request in catchUps) catchUp()
        }
    }

    /**
     * Opens the table of the stored link now, when it is not open yet: for a caller that cannot wait for [start]'s
     * collectors (a push that wakes a cold process). Fetches nothing.
     */
    suspend fun ensureOpen() {
        ready()
        (instanceId() as? Outcome.Ok)?.value?.let { book.open(it) }
    }

    private suspend fun onLink(state: WaLinkState) {
        val id =
            when (val read = instanceId()) {
                // The link file cannot be read: keep what is open, the next change asks again.
                is Outcome.Err -> return

                is Outcome.Ok -> read.value
            }
        if (id == null) {
            // No link (never linked, or removed in this app): the table of the old link goes with it.
            if (state is WaLinkState.None) book.wipe() else book.open(null)
            return
        }
        book.open(id)
        if (state is WaLinkState.Connected) {
            book.noteAccount(state.status)
            catchUps.trySend(Unit)
        }
    }

    /** Fetches the gateway's pairs from the stored cursor until it has none left. A failure stops; the next one resumes. */
    private suspend fun catchUp() {
        repeat(MAX_PAGES) {
            val page = (fetch(book.since()) as? Outcome.Ok)?.value ?: return
            val done = (book.catchUp(page) as? Outcome.Ok)?.value ?: return
            if (!done.more) return
        }
    }

    private companion object {
        /** A bound on one catch-up; the cursor is kept, so the next one goes on. */
        const val MAX_PAGES = 200
    }
}

/**
 * The WhatsApp calls with a LID peer's number filled in from [book] (`peerAlt`), and a LID peer nobody maps named
 * [unknown] ("WhatsApp user") instead of nothing or its digits. Everything downstream reads the result as it reads
 * the gateway's own list: `RecentsRepository.dialNumber`, `CallSummary.dialPeer`, caller id, the call screens.
 */
fun resolvedWaCalls(
    calls: StateFlow<List<CallSummary>>,
    book: WaIdentityBook,
    unknown: () -> String,
    scope: CoroutineScope,
): StateFlow<List<CallSummary>> =
    combine(calls, book.numbers) { list, _ -> resolveWaCalls(list, book, unknown()) }
        .stateIn(scope, SharingStarted.Eagerly, resolveWaCalls(calls.value, book, unknown()))

/** Pure: [calls] with [WaIdentityBook.fill] applied, and [unknown] as the name of a LID peer without a number or a name. */
internal fun resolveWaCalls(
    calls: List<CallSummary>,
    book: WaIdentityBook,
    unknown: String,
): List<CallSummary> =
    calls.map { call ->
        val filled = book.fill(call)
        if (!WaIds.isLid(filled.peer) || WaIds.number(filled.peer, filled.peerAlt) != null) {
            filled
        } else if (isNoName(filled.peerName, filled.peer)) {
            filled.copy(peerName = unknown)
        } else {
            filled
        }
    }

/** Whether [name] names nobody: empty, or only the digits of the LID [peer] (with or without a `+`). */
private fun isNoName(
    name: String?,
    peer: String,
): Boolean {
    val text = name?.trim().orEmpty()
    if (text.isEmpty()) return true
    val user = WaIds.user(peer) ?: return false
    return text.removePrefix("+") == user || text == WaIds.bare(peer)
}
