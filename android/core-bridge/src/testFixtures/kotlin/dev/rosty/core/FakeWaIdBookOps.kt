package dev.rosty.core

import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.wa.WaIdBookAccount
import dev.rosty.core.model.wa.WaIdBookAccountRequest
import dev.rosty.core.model.wa.WaIdBookCatchUpRequest
import dev.rosty.core.model.wa.WaIdBookCaughtUp
import dev.rosty.core.model.wa.WaIdBookLearnRequest
import dev.rosty.core.model.wa.WaIdBookLearned
import dev.rosty.core.model.wa.WaIdBookPair
import dev.rosty.core.model.wa.WaIdBookSnapshot
import dev.rosty.core.model.wa.WaIdBookSnapshotRequest
import dev.rosty.core.model.wa.WaIdBookWipeRequest
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.ops.WaIdBookOps

/**
 * [WaIdBookOps] in memory, for JVM tests: one table per instance id, with the rules a screen test can see. A LID
 * maps to one number and the latest pair wins (the real table ranks sources; `rosty_app_core::phone::wa_idbook`
 * tests that), another own number or account epoch empties the table, the cursor moves with a page that names one.
 */
class FakeWaIdBookOps : WaIdBookOps {
    private class Table {
        val pairs = LinkedHashMap<String, String>()
        var cursor: String? = null
        var ownPn: String? = null
        var ownLid: String? = null
        var epoch: Int? = null

        fun clear() {
            pairs.clear()
            cursor = null
            ownLid = null
        }
    }

    private val lock = Any()
    private val tables = HashMap<String, Table>()

    /** The ops called, in order (`wa_idbook.learn`, ...). */
    val calls = mutableListOf<String>()

    /** Fails every call with this error while set. */
    @Volatile var failure: RustError? = null

    /** Seeds a pair of [instance] as if a catch-up had learned it. */
    fun put(
        instance: String,
        lid: String,
        pn: String,
    ) {
        synchronized(lock) { table(instance).pairs[lid] = pn }
    }

    private fun table(instance: String) = tables.getOrPut(instance) { Table() }

    private fun <T> answer(
        op: String,
        body: () -> T,
    ): Outcome<T> =
        synchronized(lock) {
            calls += op
            failure?.let { Outcome.Err(it) } ?: Outcome.Ok(body())
        }

    /** Stores the pair of [a] and [b] when one is a LID and the other a number; true when the table changed. */
    private fun Table.learn(
        a: String,
        b: String,
    ): Boolean {
        val (lid, pn) =
            when {
                WaIds.isLid(a) && WaIds.isPn(b) -> a to b
                WaIds.isPn(a) && WaIds.isLid(b) -> b to a
                else -> return false
            }
        val lidBare = WaIds.bare(lid) ?: return false
        val pnBare = WaIds.bare(pn) ?: return false
        return pairs.put(lidBare, pnBare) != pnBare
    }

    override suspend fun snapshot(req: WaIdBookSnapshotRequest): Outcome<WaIdBookSnapshot> =
        answer("wa_idbook.snapshot") {
            val table = table(req.instanceId)
            WaIdBookSnapshot(
                since = table.cursor,
                ownPn = table.ownPn,
                ownLid = table.ownLid,
                pairs = table.pairs.map { (lid, pn) -> WaIdBookPair(lid, pn) }.sortedBy { it.lid },
            )
        }

    override suspend fun catchUp(req: WaIdBookCatchUpRequest): Outcome<WaIdBookCaughtUp> =
        answer("wa_idbook.catch_up") {
            val table = table(req.instanceId)
            var changed = false
            for (item in req.items) changed = table.learn(item.lid, item.pn) || changed
            val next = req.next
            if (next != null && next != table.cursor) {
                table.cursor = next
                changed = true
            }
            WaIdBookCaughtUp(pairs = req.items.size, since = table.cursor, more = next != null, changed = changed)
        }

    override suspend fun learn(req: WaIdBookLearnRequest): Outcome<WaIdBookLearned> =
        answer("wa_idbook.learn") {
            val table = table(req.instanceId)
            var changed = false
            for (pair in req.pairs) changed = table.learn(pair.id, pair.alt) || changed
            for (change in req.numberChanges) {
                val old = WaIds.bare(change.oldPn)
                val new = WaIds.bare(change.newPn) ?: continue
                for (lid in table.pairs.filterValues { it == old }.keys) {
                    table.pairs[lid] = new
                    changed = true
                }
                for (lid in listOfNotNull(change.oldLid, change.newLid)) changed = table.learn(lid, new) || changed
            }
            WaIdBookLearned(changed)
        }

    override suspend fun account(req: WaIdBookAccountRequest): Outcome<WaIdBookAccount> =
        answer("wa_idbook.account") {
            val table = table(req.instanceId)
            val pn = WaIds.bare(req.ownPn)?.takeIf { WaIds.isPn(it) }
            val otherNumber = table.ownPn != null && pn != null && table.ownPn != pn
            val otherEpoch = table.epoch != null && req.accountEpoch != null && table.epoch != req.accountEpoch
            val reset = req.changed || otherNumber || otherEpoch
            if (reset) table.clear()
            if (req.changed) table.epoch = null
            if (pn != null) table.ownPn = pn
            WaIds.bare(req.ownLid)?.takeIf { WaIds.isLid(it) }?.let { table.ownLid = it }
            req.accountEpoch?.let { table.epoch = it }
            WaIdBookAccount(reset)
        }

    override suspend fun wipe(req: WaIdBookWipeRequest): Outcome<Empty> =
        answer("wa_idbook.wipe") {
            tables.clear()
            Empty
        }
}
