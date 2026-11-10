package dev.rovsti.phone.data

import dev.rovsti.core.model.phone.BlockRefusal
import dev.rovsti.core.model.phone.BlockedNumber
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.phone.HostRust
import dev.rovsti.platform.calllog.FakeBlocklistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The blocklist with the real `blocklist.check` and `blocklist.lines`. */
class BlockRepoTest {
    private val host by lazy { HostRust("rovsti-block") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    private fun repo(fake: FakeBlocklistRepository) = BlockRepo(fake, NativePhoneOps(host.core), scope, clock = { T0 })

    @Test fun theListHasOneLinePerRowAndAnExport() =
        runBlocking {
            val fake = FakeBlocklistRepository(listOf(BlockedNumber(1, "+905551112233", "+905551112233")))
            val block = repo(fake)
            eventually("list") { block.list.value.ready }
            val list = block.list.value
            assertEquals(1, list.rows.size)
            assertEquals(1, list.lines.size)
            assertTrue(list.lines[0].title.isNotBlank())
            assertTrue(list.csv.contains("+905551112233"))
            assertTrue(list.csvFileName.endsWith(".csv"))
        }

    @Test fun blockingAddsARowAndUndoRemovesIt() =
        runBlocking {
            val block = repo(FakeBlocklistRepository())
            eventually("list") { block.list.value.ready }
            val done = block.block("+905551112233") as BlockResult.Done
            eventually("row") { block.list.value.rows.size == 1 }
            assertTrue(block.undo(done.undo))
            eventually("row gone") {
                block.list.value.rows
                    .isEmpty()
            }
        }

    @Test fun anEmergencyNumberIsRefusedByTheCore() =
        runBlocking {
            val block = repo(FakeBlocklistRepository())
            assertEquals(BlockResult.Refused(BlockRefusal.Emergency), block.block("112"))
        }

    @Test fun aNumberTwiceIsAlreadyBlocked() =
        runBlocking {
            val block = repo(FakeBlocklistRepository())
            block.block("+905551112233")
            eventually("row") { block.list.value.rows.size == 1 }
            assertEquals(BlockResult.AlreadyBlocked, block.block("+90 555 111 22 33"))
        }

    @Test fun unblockingKeepsASnapshotForUndo() =
        runBlocking {
            val fake = FakeBlocklistRepository(listOf(BlockedNumber(1, "+905551112233", "+905551112233")))
            val block = repo(fake)
            eventually("list") { block.list.value.rows.size == 1 }
            val done = block.unblock(listOf("+905551112233")) as BlockResult.Done
            eventually("row gone") {
                block.list.value.rows
                    .isEmpty()
            }
            assertEquals(listOf("+905551112233"), done.undo.block)
            assertTrue(block.undo(done.undo))
            eventually("row back") { block.list.value.rows.size == 1 }
        }

    @Test fun withoutTheDialerRoleTheListCannotBeWritten() =
        runBlocking {
            val block = repo(FakeBlocklistRepository(allowed = false))
            assertEquals(BlockResult.Unavailable, block.block("+905551112233"))
            assertTrue(!block.canBlock())
        }
}
