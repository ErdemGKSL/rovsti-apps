package dev.rovsti.phone.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.RustError
import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.phone.CallType
import dev.rovsti.core.model.phone.DateOrder
import dev.rovsti.core.model.phone.ExtraCall
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.TimeFormat
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.phone.HostRust
import dev.rovsti.platform.calllog.FakeCallLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call log and the WhatsApp calls through the real `recents.*` ops. */
class RecentsRepositoryTest {
    private val host by lazy { HostRust("rovsti-recents") }
    private val counting by lazy { CountingCore(host.core) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locale = LocaleTime(0, TimeFormat(hour12 = false, dateOrder = DateOrder.DayMonthYear))

    @After fun stop() = scope.cancel()

    private val log =
        listOf(
            logEntry(1, "+905551112233", CallLogKind.Missed, ago = 5, name = "Ayse Yilmaz"),
            logEntry(2, "+905551112233", CallLogKind.Missed, ago = 20, name = "Ayse Yilmaz"),
            logEntry(3, "+905559998877", CallLogKind.Outgoing, ago = 60, name = "Mert Kaya"),
            logEntry(4, "+905553334455", CallLogKind.Incoming, ago = 300),
        )

    /** The real core, except that `recents.group` fails while [failing] is set. */
    private class FlakyCore(
        private val inner: RustCore,
    ) : RustCore by inner {
        @Volatile var failing = false

        override suspend fun call(
            op: String,
            request: JsonElement,
        ): Outcome<JsonElement> =
            if (failing && op == "recents.group") {
                Outcome.Err(RustError.Io("disk on fire"))
            } else {
                inner.call(op, request)
            }
    }

    private fun repo(
        callLog: FakeCallLogRepository,
        extras: MutableStateFlow<List<ExtraCall>> = MutableStateFlow(emptyList()),
        window: Long = 12,
        lastSeen: Long = 0,
        clock: () -> Long = { T0 + 60_000 },
    ) = RecentsRepository(
        callLog = callLog,
        ops = NativePhoneOps(counting),
        scope = scope,
        extras = extras,
        locale = { locale },
        clock = clock,
        lastSeenMs = { lastSeen },
        windowMs = window,
    ).also { it.start() }

    @Test fun theLogIsGroupedAndEveryLabelComesFromTheCore() =
        runBlocking {
            val recents = repo(FakeCallLogRepository(log))
            eventually("first model") { recents.model.value.ready }
            val m = recents.model.value
            assertEquals("two missed calls of one number are one row", 3, m.rows.size)
            assertEquals(m.rows.size, m.views.size)
            assertEquals("Ayse Yilmaz", m.views[0].title)
            assertEquals(2, m.rows[0].count)
            assertEquals(CallType.Missed, m.rows[0].callType)
            assertTrue(m.views[0].missed)
            assertEquals("Mert Kaya", m.views[1].title)
            assertEquals("a stranger shows the number", "+905553334455", m.rows[2].numberRaw)
            assertEquals("both missed calls count for the badge", 2, m.missedCount)
            assertEquals("2", m.missedLabel)
        }

    @Test fun whatsAppCallsAreMergedInByTime() =
        runBlocking {
            val wa =
                ExtraCall(
                    "+905557776655",
                    T0 - 10 * 60_000,
                    42,
                    missed = false,
                    outgoing = true,
                    label = "Selin",
                    callId = "wa-1",
                )
            val recents = repo(FakeCallLogRepository(log), MutableStateFlow(listOf(wa)))
            eventually("merged") { recents.model.value.rows.size == 4 }
            val m = recents.model.value
            val i = m.rows.indexOfFirst { it.whatsapp }
            assertEquals("between the 5 and 20 minute calls", 1, i)
            assertEquals("Selin", m.views[i].title)
            assertTrue(m.views[i].whatsapp)
        }

    @Test fun aNewCallIsADeltaAndKeepsTheRowsBelow() =
        runBlocking {
            val callLog = FakeCallLogRepository(log)
            val recents = repo(callLog)
            eventually("first model") { recents.model.value.ready }
            val before = recents.model.value.rows
            callLog.restore(listOf(logEntry(0, "+905550001111", CallLogKind.Incoming, ago = 0, name = "Deniz")))
            eventually("delta") { recents.model.value.rows.size == before.size + 1 }
            val m = recents.model.value
            assertEquals("one row arrived at the top", 1, m.delta.fresh)
            assertEquals("Deniz", m.views[0].title)
            assertEquals("the old rows follow unchanged", before, m.rows.drop(1))
        }

    @Test fun aBurstOfChangesIsOneRebuild() =
        runBlocking {
            val callLog = FakeCallLogRepository(log)
            val recents = repo(callLog, window = 300)
            eventually("first model") { recents.model.value.ready }
            val builds = counting.count("recents.build")
            repeat(5) { i -> callLog.restore(listOf(logEntry(0, "+90555000$i$i$i$i", CallLogKind.Incoming, ago = 0))) }
            eventually("rebuilt") { recents.model.value.rows.size == 8 }
            delay(600)
            assertEquals("five changes, one build", builds + 1, counting.count("recents.build"))
        }

    /** A phone left on Recents overnight said "Today" for yesterday's calls: coming back on a new day rebuilds. */
    @Test fun comingBackOnAnotherDayRebuildsTheLabelsAndTheSameDayDoesNot() =
        runBlocking {
            var now = T0 + 60_000
            val recents = repo(FakeCallLogRepository(log), window = 12, clock = { now })
            eventually("first model") { recents.model.value.ready }
            val builds = counting.count("recents.build")
            now += 60_000
            recents.refreshIfStale()
            delay(200)
            assertEquals("same day: nothing to rebuild", builds, counting.count("recents.build"))
            now += 86_400_000
            recents.refreshIfStale()
            eventually("rebuilt for the new day") { counting.count("recents.build") == builds + 1 }
            recents.refreshIfStale()
            delay(200)
            assertEquals("once per day", builds + 1, counting.count("recents.build"))
        }

    @Test fun whatsAppCallsOfThePhoneNumbersBecomeExtraCalls() {
        fun call(
            peer: String,
            phase: CallPhase = CallPhase.Ended("hangup"),
            direction: CallDirection = CallDirection.Out,
        ) = CallSummary("id-$peer", peer, "Name", direction, phase, false, 1_000_000, 1_042_000)
        val extras =
            RecentsRepository.extraCalls(
                listOf(
                    call("905551112233@s.whatsapp.net"),
                    call("905551112233:7@s.whatsapp.net", CallPhase.Ended("missed"), CallDirection.In),
                    call("12345@lid"),
                    call("group@g.us"),
                ),
            )
        assertEquals(2, extras.size)
        assertEquals("+905551112233", extras[0].numberRaw)
        assertEquals(42, extras[0].durationS)
        assertTrue(extras[0].outgoing)
        assertTrue(!extras[0].missed)
        assertTrue("an unanswered incoming call is missed", extras[1].missed)
        assertNull(RecentsRepository.dialNumber("abc@s.whatsapp.net"))
    }

    @Test fun aFailedBuildKeepsTheRowsAndSaysWhy() =
        runBlocking {
            val flaky = FlakyCore(host.core)
            val callLog = FakeCallLogRepository(log)
            val recents =
                RecentsRepository(
                    callLog,
                    NativePhoneOps(flaky),
                    scope,
                    locale = { locale },
                    clock = { T0 + 60_000 },
                    windowMs = 12,
                ).also { it.start() }
            eventually("first model") { recents.model.value.ready }
            assertNull(recents.model.value.error)
            val rows = recents.model.value.rows
            flaky.failing = true
            callLog.restore(listOf(logEntry(0, "+905550001111", CallLogKind.Incoming, ago = 0)))
            eventually("error shown") { recents.model.value.error != null }
            assertEquals(
                "disk on fire",
                recents.model.value.error
                    ?.message,
            )
            assertEquals("the old rows stay", rows, recents.model.value.rows)
            flaky.failing = false
            recents.refreshNow()
            eventually("recovered") {
                recents.model.value.error == null &&
                    recents.model.value.rows.size == rows.size + 1
            }
        }

    @Test fun theBuildRunsOffTheMainThread() =
        runBlocking {
            val threads = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val recents =
                RecentsRepository(
                    FakeCallLogRepository(log),
                    NativePhoneOps(counting),
                    scope,
                    locale = {
                        threads += Thread.currentThread().name
                        locale
                    },
                    clock = { T0 + 60_000 },
                ).also { it.start() }
            eventually("first model") { recents.model.value.ready }
            assertTrue(threads.isNotEmpty())
            assertTrue(threads.toString(), threads.none { it == "main" })
        }
}
