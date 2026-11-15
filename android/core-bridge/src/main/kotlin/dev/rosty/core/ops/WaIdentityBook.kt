package dev.rosty.core.ops

import dev.rosty.core.Outcome
import dev.rosty.core.map
import dev.rosty.core.model.wa.CallSummary
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.WaIdBookAccountRequest
import dev.rosty.core.model.wa.WaIdBookCatchUpRequest
import dev.rosty.core.model.wa.WaIdBookCaughtUp
import dev.rosty.core.model.wa.WaIdBookLearnPair
import dev.rosty.core.model.wa.WaIdBookLearnRequest
import dev.rosty.core.model.wa.WaIdBookNumberChange
import dev.rosty.core.model.wa.WaIdBookSnapshotRequest
import dev.rosty.core.model.wa.WaIdBookWipeRequest
import dev.rosty.core.model.wa.WaIdKind
import dev.rosty.core.model.wa.WaIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The identity table of an app that keeps no WhatsApp messages (Phone, WA-R2): which phone number is behind a LID.
 * The table is Rust's ([WaIdBookOps], `wa-identity.json`); this class is its thin facade. It keeps the pairs of the
 * open instance in memory ([numbers]), because the callers that turn a call's peer into a number are synchronous
 * (row mappers, a notification title), and reads them again after every call that changed a row.
 *
 * Nothing here talks to the gateway: the app fetches `wa.identity_mappings` pages and hands them to [catchUp], and
 * hands the events of its stream to [onEvent].
 */
class WaIdentityBook(
    private val ops: WaIdBookOps,
    private val dir: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var instance: String? = null
    private var cursor: String? = null
    private val table = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The LID user (the part before `@lid`) to the bare phone JID of its person, for the open instance. */
    val numbers: StateFlow<Map<String, String>> = table.asStateFlow()

    /** `+<digits>` behind [jid]: the number a phone JID carries, or the number the table holds for a LID. */
    fun numberOf(jid: String?): String? = WaIds.number(jid) ?: WaIds.number(pnOf(jid))

    /**
     * The number of a person the gateway names by [jid] and, when it knows it, by [alt] (a call's `peer` and
     * `peer_alt`): the phone number among the two, else the number the table holds for a LID among them.
     */
    fun numberOf(
        jid: String?,
        alt: String?,
    ): String? = WaIds.number(jid, alt) ?: numberOf(jid) ?: numberOf(alt)

    /** The bare phone JID the table holds for the LID [jid]; null for anything else and for a LID nobody mapped. */
    fun pnOf(jid: String?): String? {
        val id = WaIds.parse(jid)?.takeIf { it.kind == WaIdKind.Lid } ?: return null
        return table.value[id.user]
    }

    /**
     * [call] with `peerAlt` set to the number the table holds, when its peer is a LID the gateway sent no number
     * for. Everything after it (`WaIds.number(peer, peerAlt)`, the dial peer, caller id) then works unchanged.
     */
    fun fill(call: CallSummary): CallSummary {
        if (!WaIds.isLid(call.peer) || WaIds.isPn(call.peerAlt)) return call
        val pn = pnOf(call.peer) ?: return call
        return call.copy(peerAlt = pn)
    }

    /**
     * Opens the table of [instanceId] (the stored link's instance) and reads its pairs; null closes it (no link).
     * Opening the instance that is already open only reads the pairs again.
     */
    suspend fun open(instanceId: String?): Outcome<Unit> =
        lock.withLock {
            instance = instanceId
            if (instanceId == null) {
                cursor = null
                table.value = emptyMap()
                Outcome.Ok(Unit)
            } else {
                reload(instanceId)
            }
        }

    /** `since` of the next `wa.identity_mappings` call; null before the first page and while nothing is open. */
    suspend fun since(): String? = lock.withLock { cursor }

    /**
     * Takes in one page of `wa.identity_mappings`. Go on with the answer's `since` while it says `more`. A gateway
     * from before the list ([IdentityMappingsAnswer.supported] false) has nothing to take in.
     */
    suspend fun catchUp(page: IdentityMappingsAnswer): Outcome<WaIdBookCaughtUp> =
        lock.withLock {
            val id = instance
            if (id == null || !page.supported) {
                return@withLock Outcome.Ok(WaIdBookCaughtUp(pairs = 0, since = cursor, more = false, changed = false))
            }
            val done = ops.catchUp(WaIdBookCatchUpRequest(dir, id, page.items, page.next))
            if (done is Outcome.Ok && done.value.changed) reload(id)
            done
        }

    /**
     * Learns the `peer` / `peer_alt` pairs of [calls] (source `call`). Pairs the table already holds are not sent,
     * so a list that is observed again and again costs nothing.
     */
    suspend fun learnCalls(calls: List<CallSummary>): Outcome<Boolean> {
        val known = table.value
        val pairs =
            calls
                .mapNotNull { call ->
                    val alt = call.peerAlt ?: return@mapNotNull null
                    val (lid, pn) =
                        when {
                            WaIds.isLid(call.peer) && WaIds.isPn(alt) -> call.peer to alt
                            WaIds.isPn(call.peer) && WaIds.isLid(alt) -> alt to call.peer
                            else -> return@mapNotNull null
                        }
                    val lidBare = WaIds.bare(lid) ?: return@mapNotNull null
                    val pnBare = WaIds.bare(pn) ?: return@mapNotNull null
                    if (known[WaIds.user(lidBare)] == pnBare) null else WaIdBookLearnPair(lidBare, pnBare)
                }.distinct()
        return if (pairs.isEmpty()) Outcome.Ok(false) else learn(pairs)
    }

    /** Learns [pairs] and [numberChanges]; true when a row changed. */
    suspend fun learn(
        pairs: List<WaIdBookLearnPair> = emptyList(),
        numberChanges: List<WaIdBookNumberChange> = emptyList(),
    ): Outcome<Boolean> =
        lock.withLock {
            val id = instance ?: return@withLock Outcome.Ok(false)
            val learned = ops.learn(WaIdBookLearnRequest(dir, id, clock(), pairs, numberChanges))
            if (learned is Outcome.Ok && learned.value.changed) reload(id)
            learned.map { it.changed }
        }

    /**
     * Notes the account the status names (own number, own LID, account epoch). True when it is another account
     * than the table was learned under: the table was emptied, catch up from the start.
     */
    suspend fun noteAccount(status: InstanceStatus): Outcome<Boolean> =
        account { id -> WaIdBookAccountRequest(dir, id, status.ownJid, status.ownLid, status.accountEpoch) }

    /**
     * Takes in one gateway event: `identity_mapped`, `identity_number_changed`, a call that names both ids, a state
     * that names the account, and `account_changed`. True when the table was emptied (another account): the caller
     * catches up from the start. Every other event is ignored.
     */
    suspend fun onEvent(event: GatewayEvent): Boolean =
        when (event) {
            is GatewayEvent.IdentityMapped -> {
                learn(pairs = listOf(WaIdBookLearnPair(event.lid, event.pn, event.source, event.learnedMs)))
                false
            }

            is GatewayEvent.IdentityNumberChanged -> {
                learn(
                    numberChanges =
                        listOf(WaIdBookNumberChange(event.oldPn, event.newPn, event.oldLid, event.newLid)),
                )
                false
            }

            is GatewayEvent.Call -> {
                event.peerAlt?.let { learn(pairs = listOf(WaIdBookLearnPair(event.peer, it))) }
                false
            }

            is GatewayEvent.AccountChanged -> {
                account { id -> WaIdBookAccountRequest(dir, id, ownPn = event.newPn, changed = true) }
                true
            }

            is GatewayEvent.State -> {
                (noteAccount(event.status()) as? Outcome.Ok)?.value == true
            }

            else -> {
                false
            }
        }

    /** Removes the table of this app (the link was removed) and closes it. */
    suspend fun wipe(): Outcome<Unit> =
        lock.withLock {
            instance = null
            cursor = null
            table.value = emptyMap()
            ops.wipe(WaIdBookWipeRequest(dir)).map { }
        }

    private suspend fun account(request: (String) -> WaIdBookAccountRequest): Outcome<Boolean> =
        lock.withLock {
            val id = instance ?: return@withLock Outcome.Ok(false)
            val noted = ops.account(request(id))
            if (noted is Outcome.Ok && noted.value.reset) reload(id)
            noted.map { it.reset }
        }

    /** Reads the pairs and the cursor of [id] again. Called under [lock]. */
    private suspend fun reload(id: String): Outcome<Unit> =
        ops.snapshot(WaIdBookSnapshotRequest(dir, id)).map { snapshot ->
            cursor = snapshot.since
            table.value =
                buildMap {
                    for (pair in snapshot.pairs) {
                        val lid = WaIds.parse(pair.lid)?.takeIf { it.kind == WaIdKind.Lid } ?: continue
                        if (WaIds.isPn(pair.pn)) put(lid.user, pair.pn)
                    }
                }
        }
}
