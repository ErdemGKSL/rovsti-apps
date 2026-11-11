package dev.rovsti.phone.incall

import dev.rovsti.core.model.phone.SceneLayout
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.CallLauncher
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.Sims
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E5.7b: what the scenes of `rovsti_core::scene` become on the screen, and which `CallsApi` command each tap sends. */
class MultiCallViewModelTest {
    private val host by lazy { HostRust("rovsti-multicall") }

    private class Launched : CallLauncher {
        val placed = mutableListOf<Pair<String, String?>>()

        override fun place(
            number: String,
            accountKey: String?,
        ): CallLauncher.Launch {
            placed += number to accountKey
            return CallLauncher.Launch.PLACED
        }
    }

    private fun harness(
        snapshot: dev.rovsti.core.model.phone.CallSnapshot,
        launcher: Launched = Launched(),
        real: Boolean = false,
    ): InCallHarness {
        val ops = if (real) NativePhoneOps(host.core) else null
        val placer = ops?.let { CallPlacer(it, MutableStateFlow(Sims()), launcher) }
        return InCallHarness(
            snapshot,
            backend = if (ops != null) CoreSceneBackend(ops) else FakeInCallBackend(),
            placer = placer,
        )
    }

    @Test fun oneCallHasNoHeldCardAndMergeNeedsTwo() {
        val h = harness(liveSnapshot())
        assertNull(h.ui.multi.held)
        assertFalse(h.ui.multi.conference)
        assertEquals("Merge needs two calls", h.ui.multi.merge.reason)
        assertTrue(h.ui.multi.addCall.enabled)
    }

    @Test fun twoCallsShowTheHeldCardAndDimAddCallWithTheCoresSentence() {
        val h = harness(twoCalls())
        assertEquals("Mert Kaya", h.ui.call!!.title)
        assertEquals(
            3,
            h.ui.multi.held
                ?.callId,
        )
        assertEquals(
            "Ayşe Yılmaz",
            h.ui.multi.held
                ?.title,
        )
        assertTrue(h.ui.multi.twoCalls)
        assertEquals("Two calls are already in progress", h.ui.multi.addCall.reason)
        assertTrue(h.ui.multi.merge.enabled)
        assertTrue(h.ui.multi.swap.enabled)
    }

    @Test fun theHoldSlotIsSwapWithASecondCall() {
        val h = harness(twoCalls())
        h.model.onAction(InCallAction.ToggleHold)
        assertEquals(listOf("swap"), h.calls.log)
        h.model.onAction(InCallAction.Swap)
        assertEquals(listOf("swap", "swap"), h.calls.log)
    }

    @Test fun mergeSplitDropAndEndAllEachSendOneCommand() {
        val h = harness(twoCalls())
        h.model.onAction(InCallAction.Merge)
        h.model.onAction(InCallAction.Split(6))
        h.model.onAction(InCallAction.Drop(7))
        h.model.onAction(InCallAction.EndAll)
        assertEquals(listOf("merge", "split 6", "disconnect 7", "endAll"), h.calls.log)
    }

    @Test fun aRefusedCommandSaysSo() {
        val h = harness(twoCalls())
        h.calls.accept = false
        h.model.onAction(InCallAction.Merge)
        assertTrue(h.effects.any { it == InCallEffect.Notice(InCallNotice.CommandFailed) })
    }

    @Test fun aTapOnADimmedAddCallSaysWhyAndOpensNothing() {
        val h = harness(twoCalls())
        h.model.onAction(InCallAction.OpenAddCall)
        assertEquals(listOf<InCallEffect>(InCallEffect.Say("Two calls are already in progress")), h.effects)
    }

    @Test fun addCallOpensTheSheetWithOneCallAndDialPlacesTheNumberOnTheLiveCallsSim() {
        val launcher = Launched()
        val h = harness(liveSnapshot(liveCall(account = "tel/svc#2")), launcher, real = true)
        h.model.onAction(InCallAction.OpenAddCall)
        assertTrue(h.effects.contains(InCallEffect.ShowAddCall))
        h.model.onAction(InCallAction.Dial("+905551112233"))
        eventually { launcher.placed.isNotEmpty() }
        assertEquals("tel/svc#2", launcher.placed.single().second)
        assertEquals("+905551112233", launcher.placed.single().first)
        eventually { h.effects.contains(InCallEffect.Confirm) }
    }

    @Test fun aConferenceListsItsPeopleWithTheirCapabilities() {
        val h = harness(conferenceCall())
        assertTrue(h.ui.multi.conference)
        assertEquals("Conference call", h.ui.call!!.title)
        assertEquals(
            listOf("Mert Kaya", "Ayşe Yılmaz"),
            h.ui.multi.participants
                .map { it.label },
        )
        assertTrue(
            h.ui.multi.participants
                .all { it.canSplit && it.canDrop },
        )
        assertNull(h.ui.multi.held)
        h.model.onAction(InCallAction.OpenConference)
        assertTrue(h.effects.contains(InCallEffect.ShowConference))
    }

    @Test fun aConferenceCanHaveAHeldCallToo() {
        val h = harness(conferenceCall(held = true))
        assertTrue(h.ui.multi.conference)
        assertEquals(
            "Can Demir",
            h.ui.multi.held
                ?.title,
        )
    }

    // ---- The REAL core through the bridge ------------------------------------------------------------------------------

    @Test fun theRealCoreAnswersTheTwoCallScene() {
        val h = harness(twoCalls(), real = true)
        eventually { h.ui.multi.held != null }
        assertEquals(
            3,
            h.ui.multi.held
                ?.callId,
        )
        assertEquals("Two calls are already in progress", h.ui.multi.addCall.reason)
        assertNull(h.ui.multi.merge.reason)
        assertNull(h.ui.multi.swap.reason)
    }

    @Test fun theRealCoreDimsMergeAndSwapWhenTheCarrierDoesNotAllowThem() {
        val h = harness(twoCalls(canMerge = false, canSwap = false), real = true)
        eventually { h.ui.multi.held != null }
        assertEquals("Your carrier doesn't allow merging calls", h.ui.multi.merge.reason)
        assertEquals("Your carrier doesn't allow swapping calls", h.ui.multi.swap.reason)
    }

    @Test fun theRealCoreAnswersAConferenceWithItsRows() {
        val h = harness(conferenceCall(), real = true)
        eventually { h.ui.multi.conference }
        assertEquals(2, h.ui.multi.participants.size)
        val ans =
            runBlocking { CoreSceneBackend(NativePhoneOps(host.core)).scene(conferenceCall()) }
        assertNotNull(ans)
        assertEquals(SceneLayout.Conference, ans!!.layout)
    }

    private fun eventually(check: () -> Boolean) =
        runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                while (!check()) kotlinx.coroutines.delay(10)
            }
        }
}
