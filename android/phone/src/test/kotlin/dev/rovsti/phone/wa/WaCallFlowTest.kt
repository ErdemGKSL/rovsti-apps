package dev.rovsti.phone.wa

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.NumberDisplay
import dev.rovsti.phone.data.Sims
import dev.rovsti.phone.data.eventually
import dev.rovsti.phone.incall.FakeBlocker
import dev.rovsti.phone.incall.FakeClipboard
import dev.rovsti.phone.incall.FakeInCallBackend
import dev.rovsti.phone.incall.FakeNotes
import dev.rovsti.phone.incall.InCallEffect
import dev.rovsti.phone.incall.InCallViewModel
import dev.rovsti.phone.incall.ToneMemory
import dev.rovsti.phone.incoming.FakeBackend
import dev.rovsti.phone.incoming.FakeReminders
import dev.rovsti.phone.incoming.FakeSource
import dev.rovsti.phone.incoming.IncomingAction
import dev.rovsti.phone.incoming.IncomingEffect
import dev.rovsti.phone.incoming.IncomingViewModel
import dev.rovsti.phone.incoming.RecordingCalls
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.wa.DefaultWaRepository
import dev.rovsti.wa.FakeCallPump
import dev.rovsti.wa.FakeWaGateway
import dev.rovsti.wa.WaLinkStore
import dev.rovsti.wa.calls.WaCallActions
import dev.rovsti.wa.calls.WaCalls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E5.8b: a WhatsApp call from the gateway's event to the screens and back, on the REAL Rust core for the arbitration
 * (`phone.wa_arbitrate`), the fake gateway for the rest. The merged call source folds the call into the registry's
 * snapshot, the router sends the commands, the real Incoming and In-Call models decide.
 */
class WaCallFlowTest {
    private val host = HostRust("rovsti-wa-flow")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val peer = "905551112233@s.whatsapp.net"
    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU")
    private val mic = MutableStateFlow<PermissionState>(PermissionState.Granted)
    private val pump = FakeCallPump()
    private val fake =
        FakeWaGateway(link = link).apply {
            status =
                Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected, ownJid = "905550000000@s.whatsapp.net"))
        }
    private val repo =
        DefaultWaRepository(
            WaLinkStore(fake.ops),
            scope,
            fake.opener,
            fake.waEvents,
            MutableSharedFlow<PlatformEvent>(extraBufferCapacity = 8),
        ).also { it.start() }
    private val waCalls = WaCalls(repo, mic, pump, scope, clock = { NOW }).also { it.start() }
    private val base = FakeSource(CallSnapshot(keyguardLocked = false))
    private val registry = RecordingCalls()
    private val notices = WaNotices()
    private val source =
        WaMergedCallSource(
            base = base,
            waCalls = waCalls.calls,
            muted = waCalls.mutedCalls,
            speaker = waCalls.speakerOn,
            arbiter = CoreWaArbiter(host.ops),
            ids = WaIdBook(),
            onBusy = { id -> scope.launch { waCalls.reject(id) } },
            scope = scope,
            clock = { NOW },
        )
    private val router = WaCallsRouter(registry, waCalls, source, notices, scope)
    private val incomingEffects = mutableListOf<IncomingEffect>()
    private val incoming =
        IncomingViewModel(
            source = source,
            calls = router,
            backend = FakeBackend(),
            reminders = FakeReminders(),
            // AsIs on purpose: this flow test asserts the wire numbers, the formatting has its own tests.
            display = NumberDisplay.AsIs,
            sims = MutableStateFlow(Sims()),
            savedReplies = { emptyList() },
            unknownLabel = "Unknown",
            scope = scope,
            clock = { NOW },
            tzOffsetS = { 0 },
        ).also { m -> scope.launch { m.effect.collect { incomingEffects += it } } }
    private val inCallEffects = mutableListOf<InCallEffect>()
    private val inCall =
        InCallViewModel(
            source = source,
            calls = router,
            backend = FakeInCallBackend(),
            notes = FakeNotes(),
            blocker = FakeBlocker(),
            clipboard = FakeClipboard(),
            tones = ToneMemory(),
            // AsIs on purpose: this flow test asserts the wire numbers, the formatting has its own tests.
            display = NumberDisplay.AsIs,
            sims = MutableStateFlow(Sims()),
            bluetoothGranted = MutableStateFlow(true),
            unknownLabel = "Unknown",
            scope = scope,
            clock = { NOW },
        ).also { m -> scope.launch { m.effect.collect { inCallEffects += it } } }
    private var seq = 0L

    @After fun stop() {
        scope.cancel()
    }

    private fun event(
        id: String,
        phase: CallPhase,
        direction: CallDirection = CallDirection.In,
    ) = fake.emitEvent("inst1", ++seq, GatewayEvent.Call(id, peer, "Ada", direction, phase, false))

    private fun waCall() =
        source.snapshot.value.calls
            .firstOrNull { WaIdBook.isWa(it.id) }

    private fun summary(
        id: String,
        phase: CallPhase,
    ) = CallSummary(id, peer, "Ada", CallDirection.In, phase, false, startedMs = NOW - 1_000)

    // ---- Ring, answer, audio opened, end ---------------------------------------------------------------------------------

    @Test fun aWhatsAppCallRingsIsAnsweredOpensTheAudioAndEnds() =
        runBlocking {
            event("c1", CallPhase.Ringing)
            eventually("the WhatsApp call rings on Incoming") { incoming.state.value.caller != null }
            val caller = incoming.state.value.caller!!
            assertTrue(caller.whatsapp)
            assertEquals("Ada", caller.title)
            assertEquals(peer, caller.photoJid)
            assertFalse(incoming.state.value.canMessage)

            incoming.onAction(IncomingAction.Answer)
            eventually("the gateway got the answer") { fake.count("wa.accept_call") == 1 }
            assertEquals("answered, not connected: no audio yet", 0, pump.starts.get())
            event("c1", CallPhase.Connecting)
            event("c1", CallPhase.Active)
            eventually("the audio opened") { pump.starts.get() == 1 }
            eventually("Incoming hands over to In-Call") { incomingEffects.any { it == IncomingEffect.Leave(true) } }
            eventually("the In-Call screen shows the WhatsApp call") {
                inCall.state.value.call
                    ?.whatsapp == true
            }
            eventually("a connect time for the timer") {
                (
                    inCall.state.value.call
                        ?.connectTimeMs ?: 0
                ) > 0
            }
            assertTrue(inCall.state.value.whatsapp != null)

            inCall.onAction(dev.rovsti.phone.incall.InCallAction.ToggleMute)
            eventually("mute reached the gateway") { fake.count("wa.set_muted") == 1 }
            eventually("the screen shows muted") { inCall.state.value.muted }
            inCall.onAction(dev.rovsti.phone.incall.InCallAction.End)
            eventually("the gateway got the hang up") { fake.count("wa.end_call") == 1 }
            event("c1", CallPhase.Ended("hangup"))
            eventually("the call is gone from the screens") { waCall() == null }
            eventually("the audio stopped") { pump.stops.get() >= 1 }
            assertEquals(
                "no cellular command was sent (a tone stop on hang-up is harmless)",
                emptyList<String>(),
                registry.log - "dtmfStop",
            )
            assertTrue(inCallEffects.none { it is InCallEffect.PostCall })
        }

    @Test fun declineRejectsOnTheGatewayAndTheScreenCloses() =
        runBlocking {
            event("c2", CallPhase.Ringing)
            eventually("rings") { incoming.state.value.caller != null }
            incoming.onAction(IncomingAction.Decline)
            eventually("the gateway got the reject") { fake.count("wa.reject_call") == 1 }
            assertEquals(0, fake.count("wa.accept_call"))
            event("c2", CallPhase.Ended("rejected"))
            eventually("the screen leaves") { incomingEffects.any { it == IncomingEffect.Leave(false) } }
            assertEquals(0, pump.starts.get())
        }

    // ---- Arbitration, both ways ---------------------------------------------------------------------------------------

    private fun cellular(
        id: Int,
        state: WireCallState,
        name: String,
    ) = dev.rovsti.core.model.phone.WireCall(
        id = id,
        state = state,
        numberRaw = "+90555000$id",
        displayName = name,
        canHold = true,
        isIncoming = state == WireCallState.Ringing,
    )

    @Test fun aWhatsAppCallDuringACellularCallShowsTheWaitingCardAndEndAndAnswerEndsTheCellularCall() =
        runBlocking {
            base.set(
                CallSnapshot(
                    calls = listOf(cellular(2, WireCallState.Active, "Mert")),
                    foregroundCallId = 2,
                    keyguardLocked = false,
                ),
            )
            event("c3", CallPhase.Ringing)
            eventually("the WhatsApp offer is the waiting call") { incoming.state.value.waiting != null }
            val waiting = incoming.state.value.waiting!!
            assertTrue(waiting.whatsapp)
            assertFalse("WhatsApp cannot be held", waiting.canHold)
            assertNull("the screen is not taken over", incoming.state.value.caller)
            assertEquals("the cellular call stays the foreground call", 2, source.snapshot.value.foregroundCallId)

            incoming.onAction(IncomingAction.EndAndAnswer)
            assertEquals(listOf("endAll"), registry.log)
            eventually("the WhatsApp call was answered") { fake.count("wa.accept_call") == 1 }
        }

    @Test fun aCellularCallDuringAWhatsAppCallIsTheWaitingCardAndEndAndAnswerEndsTheWhatsAppCall() =
        runBlocking {
            event("c4", CallPhase.Ringing)
            eventually("rings") { incoming.state.value.caller != null }
            incoming.onAction(IncomingAction.Answer)
            event("c4", CallPhase.Active)
            eventually("active") {
                inCall.state.value.call
                    ?.whatsapp == true
            }
            base.set(
                CallSnapshot(
                    calls = listOf(cellular(5, WireCallState.Ringing, "Deniz")),
                    foregroundCallId = 5,
                    keyguardLocked = false,
                ),
            )
            eventually("the cellular call is the waiting call") {
                incoming.state.value.waiting
                    ?.callId == 5
            }
            assertFalse(
                "a WhatsApp call cannot be put on hold",
                incoming.state.value.waiting!!
                    .canHold,
            )
            assertNull(incoming.state.value.caller)

            incoming.onAction(IncomingAction.EndAndAnswer)
            eventually("the WhatsApp call was hung up") { fake.count("wa.end_call") == 1 }
            assertEquals(listOf("answer 5"), registry.log)
        }

    @Test fun aCellularRingDeclinesAWhatsAppOfferOncePerCall() =
        runBlocking {
            base.set(
                CallSnapshot(
                    calls = listOf(cellular(7, WireCallState.Ringing, "Deniz")),
                    foregroundCallId = 7,
                    keyguardLocked = false,
                ),
            )
            event("c5", CallPhase.Ringing)
            eventually("the WhatsApp offer is declined") { fake.count("wa.reject_call") == 1 }
            event("c5", CallPhase.Ringing)
            fake.calls = listOf(summary("c5", CallPhase.Ringing))
            fake.emitEvent("inst1", ++seq, GatewayEvent.Resync)
            Thread.sleep(300)
            assertEquals("once per call id", 1, fake.count("wa.reject_call"))
            assertNull("the offer never reached the screen", waCall())
            assertEquals(
                7,
                incoming.state.value.caller
                    ?.callId,
            )
        }

    // ---- A cold start answer ------------------------------------------------------------------------------------------

    @Test fun anAnswerFromTheNotificationAfterAColdStartWaitsForTheCoreAndTheRingingCall() =
        runBlocking {
            val ready = CompletableDeferred<Unit>()
            val actions = WaCallActions(waCalls, ready = { ready.await() }, scope = scope)
            assertTrue(actions.offer("""{"call_id":"c6","action":"answer"}"""))
            event("c6", CallPhase.Ringing)
            eventually("the call rings on Incoming") { incoming.state.value.caller != null }
            assertEquals("Rust.init is not done: nothing was answered", 0, fake.count("wa.accept_call"))
            ready.complete(Unit)
            eventually("answered once the core is ready") { fake.count("wa.accept_call") == 1 }
            assertFalse(actions.offer("""{"call_id":"c6","action":"answer"}"""))
        }
}

private const val NOW = 1_700_000_000_000L
