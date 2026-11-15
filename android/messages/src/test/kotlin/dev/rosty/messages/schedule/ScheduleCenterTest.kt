package dev.rosty.messages.schedule

import dev.rosty.core.FakeRustCore
import dev.rosty.core.model.messages.FiredSms
import dev.rosty.core.model.messages.ScheduleTarget
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.ScheduledState
import dev.rosty.core.ops.NativeMessagesOps
import dev.rosty.platform.sms.ArmMode
import dev.rosty.platform.sms.FakeScheduledSmsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** E6.10 (AD-MSG6): SMS through the queue and its alarm, WhatsApp on the gateway, fired settle. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleCenterTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val rust = FakeRustCore().also { it.scriptScheduleQueue() }
    private val alarms = FakeScheduledSmsRepository()
    private val remote = FakeWaScheduleRemote()
    private val live = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
    private val queue = MemoryQueueStore()
    private val sms = ScheduleDest.Sms("+905550000001", subId = 2)
    private val wa = ScheduleDest.WhatsApp("905550000002@s.whatsapp.net")

    private fun TestScope.center(): ScheduleCenter {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return ScheduleCenter(
            backgroundScope,
            NativeMessagesOps(rust),
            alarms,
            remote,
            live,
            queue,
            { now },
            dispatcher,
        ).also { it.start() }
    }

    @Test fun anSmsIsQueuedSavedAndArmed() =
        runTest(UnconfinedTestDispatcher()) {
            val center = center()

            val outcome = center.schedule(sms, "  See you at six ", now + hour)

            assertEquals(ScheduleOutcome.Scheduled(now + hour), outcome)
            val item =
                center.state.value.local
                    .single()
            assertEquals("See you at six", item.body)
            assertEquals(ScheduleTarget.Sms("+905550000001", 2), item.target)
            val armed = alarms.armed().single()
            assertEquals(listOf(item.id, now + hour), listOf(armed.id, armed.atMs))
            assertEquals("+905550000001", armed.to)
            assertEquals(2, armed.subId)
            assertTrue("scheduled.json holds the queue Rust answered", "See you at six" in queue.content)
        }

    @Test fun withoutExactAlarmsItMayBeLate() =
        runTest(UnconfinedTestDispatcher()) {
            alarms.mode = ArmMode.Inexact

            assertEquals(ScheduleOutcome.Scheduled(now + hour, late = true), center().schedule(sms, "hi", now + hour))
        }

    @Test fun lessThanAMinuteAheadIsTooSoonAndAnEmptyTextIsRefused() =
        runTest(UnconfinedTestDispatcher()) {
            val center = center()

            assertEquals(ScheduleOutcome.TooSoon, center.schedule(sms, "hi", now + 59_999))
            assertEquals(ScheduleOutcome.Failed, center.schedule(sms, "   ", now + hour))
            assertTrue(
                center.state.value.local
                    .isEmpty(),
            )
            assertTrue(alarms.armed().isEmpty())
        }

    @Test fun anItemTheAlarmServiceRefusesLeavesTheQueueAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val center = center()

            // The alarm layer rejects an item without a recipient.
            assertEquals(ScheduleOutcome.Failed, center.schedule(ScheduleDest.Sms("", null), "hi", now + hour))
            assertTrue(
                center.state.value.local
                    .isEmpty(),
            )
            assertEquals("[]", queue.content)
        }

    @Test fun theStoredQueueIsLoadedAtStart() =
        runTest(UnconfinedTestDispatcher()) {
            val first = center()
            first.schedule(sms, "one", now + hour)
            val again = center()

            assertEquals(
                listOf("one"),
                again.state.value.local
                    .map { it.body },
            )
        }

    @Test fun cancellingAnSmsDisarmsItsAlarm() =
        runTest(UnconfinedTestDispatcher()) {
            val center = center()
            center.schedule(sms, "one", now + hour)
            val id =
                center.state.value.local
                    .single()
                    .id

            assertTrue(center.cancel(StripKey.Local(id)))
            assertTrue(
                center.state.value.local
                    .isEmpty(),
            )
            assertTrue(alarms.armed().isEmpty())
            assertFalse("gone already", center.cancel(StripKey.Local(id)))
        }

    @Test fun aSentItemLeavesTheQueueQuietly() =
        runTest(UnconfinedTestDispatcher()) {
            val center = center()
            val notices = ArrayList<ScheduleNotice>()
            backgroundScope.launch { center.notices.collect { notices += it } }
            center.schedule(sms, "one", now + hour)

            alarms.fire(
                center.state.value.local
                    .single()
                    .id,
                ok = true,
            )

            assertTrue(
                center.state.value.local
                    .isEmpty(),
            )
            assertTrue(notices.isEmpty())
            assertEquals("[]", queue.content)
        }

    @Test fun aFailedItemLeavesTheQueueAndIsAnnounced() =
        runTest(UnconfinedTestDispatcher()) {
            val center = center()
            val notices = ArrayList<ScheduleNotice>()
            backgroundScope.launch { center.notices.collect { notices += it } }
            center.schedule(sms, "one", now + hour)

            alarms.fire(
                center.state.value.local
                    .single()
                    .id,
                ok = false,
            )

            assertTrue(
                center.state.value.local
                    .isEmpty(),
            )
            assertEquals(
                listOf<ScheduleNotice>(ScheduleNotice.SmsFailed(1, "+905550000001", "+905550000001" to "one")),
                notices,
            )
        }

    @Test fun severalFailuresAreCountedAndTheNewestReopens() =
        runTest(UnconfinedTestDispatcher()) {
            val center = center()
            val notices = ArrayList<ScheduleNotice>()
            backgroundScope.launch { center.notices.collect { notices += it } }
            center.schedule(sms, "one", now + hour)
            center.schedule(ScheduleDest.Sms("+905550000003", null), "two", now + 2 * hour)
            val ids =
                center.state.value.local
                    .map { it.id }

            center.settle(ids.map { FiredSms(it, false, now) })

            assertEquals(
                listOf<ScheduleNotice>(ScheduleNotice.SmsFailed(2, null, "+905550000003" to "two")),
                notices,
            )
        }

    @Test fun aWhatsAppTextGoesToTheGatewayAndNotIntoTheLocalQueue() =
        runTest(UnconfinedTestDispatcher()) {
            val center = center()

            assertEquals(ScheduleOutcome.Scheduled(now + hour), center.schedule(wa, "See you", now + hour))

            assertEquals(listOf("See you"), remote.items.map { it.text })
            assertEquals(
                listOf("See you"),
                center.state.value.remote
                    .map { it.text },
            )
            assertTrue(
                center.state.value.local
                    .isEmpty(),
            )
            assertTrue(alarms.armed().isEmpty())
        }

    @Test fun whatsAppNotLinkedIsSaidSo() =
        runTest(UnconfinedTestDispatcher()) {
            remote.linked = false

            assertEquals(ScheduleOutcome.NotLinked, center().schedule(wa, "See you", now + hour))
        }

    @Test fun cancellingAGatewayItemAlsoWorksWhenItIsGoneAlready() =
        runTest(UnconfinedTestDispatcher()) {
            val center = center()
            center.schedule(wa, "one", now + hour)
            center.schedule(wa, "two", now + 2 * hour)
            val (one, two) =
                center.state.value.remote
                    .map { it.id }

            assertTrue(center.cancel(StripKey.Gateway(one)))
            assertEquals(
                listOf("two"),
                center.state.value.remote
                    .map { it.text },
            )
            // Sent meanwhile: the gateway no longer has it, the stale row goes all the same.
            remote.items.clear()
            assertTrue(center.cancel(StripKey.Gateway(two)))
            assertTrue(
                center.state.value.remote
                    .isEmpty(),
            )
            assertFalse(center.cancel(StripKey.Gateway("unknown")))
        }

    @Test fun theGatewaysEventsSettleItsItems() =
        runTest(UnconfinedTestDispatcher()) {
            val center = center()
            val notices = ArrayList<ScheduleNotice>()
            backgroundScope.launch { center.notices.collect { notices += it } }
            center.schedule(wa, "one", now + hour)
            center.schedule(wa, "two", now + 2 * hour)
            val (one, two) = center.state.value.remote

            live.emit(GatewayEvent.Scheduled(one.id, one.chat, one.text, one.sendAtMs, 0, ScheduledState.Sent("m1")))
            live.emit(
                GatewayEvent.Scheduled(two.id, two.chat, two.text, two.sendAtMs, 0, ScheduledState.Failed("offline")),
            )
            live.emit(
                GatewayEvent.Scheduled(two.id, two.chat, two.text, two.sendAtMs, 0, ScheduledState.Failed("offline")),
            )

            // Sent is history (off the strip); failed stays on it, in red, and is announced once.
            val strip = ScheduleRules.stripItems(wa, center.state.value.local, center.state.value.remote)
            assertEquals(listOf(StripKey.Gateway(two.id)), strip.map { it.key })
            assertTrue(strip.single().failed)
            assertEquals(listOf<ScheduleNotice>(ScheduleNotice.WhatsAppFailed(wa.chat)), notices)
        }

    @Test fun theQueueFileIsReplacedWhole() {
        val dir = Files.createTempDirectory("queue").toFile()
        val store = FileQueueStore(dir)
        assertEquals("", store.read())
        assertTrue(store.write("""{"items":[]}"""))
        assertTrue(store.write("""{"items":[1]}"""))
        assertEquals("""{"items":[1]}""", File(dir, "scheduled.json").readText())
        assertEquals(listOf("scheduled.json"), dir.list()!!.toList())
        dir.deleteRecursively()
    }
}
