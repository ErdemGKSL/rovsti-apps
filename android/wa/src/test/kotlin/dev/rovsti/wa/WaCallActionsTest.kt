package dev.rovsti.wa

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.wa.calls.ForgottenCalls
import dev.rovsti.wa.calls.WaCallActions
import dev.rovsti.wa.calls.WaCalls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** E5.8b: Answer and Decline on the WhatsApp call notification take effect once, after the core is ready. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaCallActionsTest {
    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU")
    private val push = MutableSharedFlow<PlatformEvent>(extraBufferCapacity = 8)
    private val mic = MutableStateFlow<PermissionState>(PermissionState.Granted)
    private val pump = FakeCallPump()
    private val peer = "905551112233@s.whatsapp.net"

    private class Rig(
        val fake: FakeWaGateway,
        val calls: WaCalls,
        val actions: WaCallActions,
        val ready: CompletableDeferred<Unit>,
        val forgotten: ForgottenCalls,
        val repo: DefaultWaRepository,
    )

    private fun TestScope.rig(ringing: Boolean = false): Rig {
        val fake =
            FakeWaGateway(link = link).apply {
                status =
                    Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected, ownJid = "905550000000@s.whatsapp.net"))
                if (ringing) calls = listOf(summary("c1", CallPhase.Ringing))
            }
        val repo = DefaultWaRepository(WaLinkStore(fake.ops), backgroundScope, fake.opener, fake.waEvents, push)
        repo.start()
        val forgotten = ForgottenCalls.inMemory()
        val calls = WaCalls(repo, mic, pump, backgroundScope, clock = { 1_000 }, forgotten = forgotten)
        calls.start()
        val ready = CompletableDeferred<Unit>()
        return Rig(
            fake,
            calls,
            WaCallActions(calls, ready = {
                ready.await()
            }, scope = backgroundScope),
            ready,
            forgotten,
            repo,
        )
    }

    private fun summary(
        id: String,
        phase: CallPhase,
        direction: CallDirection = CallDirection.In,
    ) = CallSummary(id, peer, "Ada", direction, phase, isVideo = false, startedMs = 500)

    private fun json(
        id: String,
        action: String,
    ) = """{"call_id":"$id","action":"$action"}"""

    @Test fun aDeclineFromAColdProcessWaitsForTheCoreThenRejectsOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig(ringing = true)
            assertTrue(r.actions.offer(json("c1", "decline")))
            assertEquals("nothing runs before Rust.init is done", 0, r.fake.count("wa.reject_call"))
            r.ready.complete(Unit)
            assertEquals(1, r.fake.count("wa.reject_call"))
            assertFalse("a redelivered intent does nothing", r.actions.offer(json("c1", "decline")))
            assertEquals(1, r.fake.count("wa.reject_call"))
        }

    @Test fun anAnswerFromAColdProcessWaitsForTheCoreAndForTheCallToRing() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            assertTrue(r.actions.offer(json("c1", "answer")))
            r.ready.complete(Unit)
            assertEquals("the core is ready but the call is not known yet", 0, r.fake.count("wa.accept_call"))
            r.fake.emitEvent(
                "inst1",
                1,
                GatewayEvent.Call("c1", peer, "Ada", CallDirection.In, CallPhase.Ringing, false),
            )
            assertEquals(1, r.fake.count("wa.accept_call"))
            assertFalse("a late Decline cannot undo the Answer", r.actions.offer(json("c1", "decline")))
            assertEquals(0, r.fake.count("wa.reject_call"))
        }

    @Test fun anAnswerForACallThatEndedMeanwhileIsDropped() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            r.actions.offer(json("c1", "answer"))
            r.ready.complete(Unit)
            r.fake.emitEvent(
                "inst1",
                1,
                GatewayEvent.Call("c1", peer, "Ada", CallDirection.In, CallPhase.Ended("missed"), false),
            )
            assertEquals(0, r.fake.count("wa.accept_call"))
        }

    @Test fun anAnswerThatWaitsLongerThanTheTtlGivesUp() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            r.ready.complete(Unit)
            r.actions.offer(json("c1", "answer"))
            testScheduler.advanceTimeBy(WaCallActions.TTL_MS + 1)
            r.fake.emitEvent(
                "inst1",
                1,
                GatewayEvent.Call("c1", peer, "Ada", CallDirection.In, CallPhase.Ringing, false),
            )
            assertEquals(0, r.fake.count("wa.accept_call"))
        }

    @Test fun junkPayloadsAreIgnored() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            assertFalse(r.actions.offer("not json"))
            assertFalse(r.actions.offer("""{"call_id":"","action":"answer"}"""))
            assertFalse(r.actions.offer("""{"call_id":"c1","action":"dance"}"""))
        }

    @Test fun deletedCallsStayGoneAfterAReload() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig(ringing = true)
            r.fake.calls = listOf(summary("c1", CallPhase.Ended("missed")), summary("c2", CallPhase.Ended("hangup")))
            r.calls.forget(listOf("c1"))
            r.fake.emitEvent("inst1", 1, GatewayEvent.Resync)
            assertEquals(
                listOf("c2"),
                r.calls.calls.value
                    .map { it.callId },
            )
            assertEquals(setOf("c1"), r.forgotten.ids())
        }

    @Test fun aResyncReadsTheCallsAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val r = rig()
            val before = r.fake.count("wa.calls")
            r.fake.calls = listOf(summary("c9", CallPhase.Ended("missed")))
            r.fake.emitEvent("inst1", 1, GatewayEvent.Resync)
            assertEquals("a resync reads them again", before + 1, r.fake.count("wa.calls"))
            assertEquals(
                listOf("c9"),
                r.calls.calls.value
                    .map { it.callId },
            )
        }
}
