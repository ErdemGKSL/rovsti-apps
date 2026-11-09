package dev.rovsti.platform.calllog

import dev.rovsti.core.Envelope
import dev.rovsti.core.model.phone.BlockReason
import dev.rovsti.core.model.phone.BlockedNumber
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/** The Blocklist cases of the GPUI build's Epic17Test (17.1, 17.2), typed instead of JSON envelopes. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class BlocklistTest {
    private class Fake(
        var can: Boolean = true,
        var rows: List<Blocklist.Row> = emptyList(),
        var denied: Boolean = false,
    ) : Blocklist.Provider {
        val inserted = ArrayList<String>()

        override fun canBlock() = can

        override fun rows(): List<Blocklist.Row> = if (denied) throw SecurityException() else rows

        override fun insert(raw: String): Boolean = if (denied) throw SecurityException() else inserted.add(raw)

        override fun unblock(raw: String): Int = if (denied) throw SecurityException() else 1

        override fun isBlocked(raw: String): Boolean =
            if (denied) throw SecurityException() else rows.any { it.numberRaw == raw }
    }

    @Serializable
    private data class Numbers(
        val numbers: List<BlockedNumber>,
    )

    @Test fun rowsMapToBlockedNumbersAndMatchTheFixture() {
        val p =
            Fake(
                rows =
                    listOf(
                        Blocklist.Row(1, "+90 555 111 22 33", "+905551112233"),
                        Blocklist.Row(2, "0850 000 00 00", ""),
                    ),
            )
        val (result, numbers) = Blocklist.numbers(p)
        assertEquals(Blocklist.Result.OK, result)
        val root =
            Envelope.json.parseToJsonElement(
                File(System.getProperty("rovsti.wire.fixtures"), "blocklist.json").readText(),
            )
        val data = (root as JsonObject).getValue("data").toString()
        // The second row has no E.164: the normalisation fills it, as in the fixture.
        assertEquals(Envelope.json.decodeFromString(Numbers.serializer(), data).numbers, numbers)
    }

    @Test fun aSecurityExceptionIsUnavailableWhenBlockingIsOffAndPermissionOtherwise() {
        val unavailable = Fake(can = false, denied = true)
        assertEquals(Blocklist.Result.UNAVAILABLE, Blocklist.numbers(unavailable).first)
        val permission = Fake(can = true, denied = true)
        assertEquals(Blocklist.Result.PERMISSION, Blocklist.numbers(permission).first)
        assertEquals(Blocklist.Result.UNAVAILABLE, Blocklist.block(unavailable, "0555") { false })
        assertEquals(Blocklist.Result.PERMISSION, Blocklist.unblock(permission, "0555"))
        assertFalse(Blocklist.isBlocked(permission, "0555"))
    }

    @Test fun emergencyNumbersAndBlanksAreRefusedBeforeTheProvider() {
        val p = Fake()
        assertEquals(Blocklist.Result.REFUSED, Blocklist.block(p, "112") { it == "112" })
        assertEquals(Blocklist.Result.REFUSED, Blocklist.block(p, "  ") { false })
        assertEquals(Blocklist.Result.REFUSED, Blocklist.unblock(p, ""))
        assertTrue(p.inserted.isEmpty())
        assertEquals(Blocklist.Result.OK, Blocklist.block(p, " 0555 ") { false })
        assertEquals(listOf("0555"), p.inserted)
    }

    @Test fun blockReasonsMapToUserSystemUnknown() {
        assertEquals(BlockReason.User, Blocklist.reasonOf(3))
        for (code in listOf(1, 2, 4, 5, 6, 7)) assertEquals(BlockReason.System, Blocklist.reasonOf(code))
        assertEquals(BlockReason.Unknown, Blocklist.reasonOf(0))
        assertEquals(BlockReason.Unknown, Blocklist.reasonOf(99))
        for (reason in BlockReason.entries) assertEquals(reason, Blocklist.reasonOf(Blocklist.reasonCode(reason)))
    }

    @Test fun theGuardNeverDelaysTheNotificationAndHitsLandOnMain() {
        val queue = ArrayDeque<Runnable>()
        val lookup = Executor { queue.addLast(it) }
        val mainQueue = ArrayList<Runnable>()
        var asked = 0
        val guard =
            BlockGuard(lookup, { mainQueue.add(it) }, {
                asked++
                true
            })
        var blocked = 0
        guard.onRinging("0555", true) { blocked++ }
        // A slow provider: nothing ran yet, onRinging already returned.
        assertEquals(0, asked)
        assertEquals(0, blocked)
        queue.removeFirst().run()
        assertEquals(1, asked)
        assertEquals(0, blocked)
        mainQueue.single().run()
        assertEquals(1, blocked)
    }

    @Test fun theGuardDoesNothingWhenOffOrWithoutANumberOrOnAMiss() {
        val queue = ArrayDeque<Runnable>()
        val guard = BlockGuard({ queue.addLast(it) }, { it.run() }, { false })
        var blocked = 0
        guard.onRinging("0555", false) { blocked++ }
        guard.onRinging("", true) { blocked++ }
        assertTrue(queue.isEmpty())
        guard.onRinging("0555", true) { blocked++ }
        queue.removeFirst().run()
        assertEquals(0, blocked)
        val boom = BlockGuard({ it.run() }, { it.run() }, { throw IllegalStateException() })
        boom.onRinging("0555", true) { blocked++ }
        assertEquals(0, blocked)
    }

    @Test fun theRepositoryKeepsTheRefusalsAndReadsThroughTheProvider() =
        runTest {
            val provider = Fake(rows = listOf(Blocklist.Row(7, "0555", "")))
            val repository =
                AndroidBlocklistRepository(
                    context = org.robolectric.RuntimeEnvironment.getApplication(),
                    io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                    provider = provider,
                    isEmergency = { it == "112" },
                )
            assertEquals(Blocklist.Result.REFUSED, repository.block("112"))
            assertEquals(Blocklist.Result.OK, repository.block("0556"))
            assertTrue(repository.canBlock())
            assertTrue(repository.isBlocked("0555"))
            assertFalse(repository.isBlocked("0999"))
        }

    @Test fun theFakeRepositoryRefusesLikeTheRealOne() =
        runTest {
            val fake = FakeBlocklistRepository()
            assertEquals(Blocklist.Result.REFUSED, fake.block("112"))
            assertEquals(Blocklist.Result.OK, fake.block("0555 111"))
            assertEquals(Blocklist.Result.OK, fake.block("0555111"))
            assertTrue(fake.isBlocked("0555 111"))
            fake.allowed = false
            assertEquals(Blocklist.Result.UNAVAILABLE, fake.unblock("0555111"))
        }
}
