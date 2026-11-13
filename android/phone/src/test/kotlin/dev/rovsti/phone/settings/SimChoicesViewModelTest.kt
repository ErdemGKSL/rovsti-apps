package dev.rovsti.phone.settings

import dev.rovsti.core.model.phone.PhoneAccountInfo
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.UnconfinedMain
import dev.rovsti.phone.data.CallLauncher
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.PlaceResult
import dev.rovsti.phone.data.SimState
import dev.rovsti.phone.data.Sims
import dev.rovsti.phone.data.eventually
import dev.rovsti.phone.settings.sim.MemorySimChoices
import dev.rovsti.phone.settings.sim.SimChoicesAction
import dev.rovsti.phone.settings.sim.SimChoicesEffect
import dev.rovsti.phone.settings.sim.SimChoicesViewModel
import dev.rovsti.platform.telephony.FakeSimRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.9b SIM choices: the remembered SIM per number, forget one / all with Undo, hidden below two SIMs, and the placer. */
class SimChoicesViewModelTest {
    private val host by lazy { HostRust("rovsti-sim-choices") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sim1 = PhoneAccountInfo("tel/svc#1", "Turkcell", slotIndex = 0, sim = true)
    private val sim2 = PhoneAccountInfo("tel/svc#2", "Vodafone", slotIndex = 1, sim = true)

    @Before fun mainDispatcher() = UnconfinedMain.install()

    @After fun stop() = scope.cancel()

    private suspend fun simsOf(vararg accounts: PhoneAccountInfo): SimState {
        val state = SimState(FakeSimRepository(accounts.toList()), NativePhoneOps(host.core), scope)
        eventually("sims built") { state.sims.value.accounts.accounts.size == accounts.size }
        return state
    }

    private fun names(number: String) = if (number.endsWith("3344")) "Deniz Aksoy" else null

    @Test fun rowsNameTheContactAndTheSimAndForgettingGivesUndo() =
        runBlocking {
            val sims = simsOf(sim1, sim2)
            val store = MemorySimChoices(mapOf("+905553333344" to "tel/svc#2", "+905559998877" to "tel/svc#1"))
            val model = SimChoicesViewModel(store, sims.sims, ::names)
            val ui = model.state.until { it.visible && it.rows.size == 2 }
            assertEquals("Deniz Aksoy", ui.rows.first().name)
            assertTrue("the SIM text is the core's, not the key", ui.rows.first().simLabel != "tel/svc#2")
            model.onAction(SimChoicesAction.Forget("+905553333344"))
            val effect = withTimeout(5_000) { model.effect.first() } as SimChoicesEffect.Forgotten
            assertEquals("Deniz Aksoy", effect.name)
            assertEquals(
                1,
                model.state
                    .until { it.rows.size == 1 }
                    .rows.size,
            )
            model.onAction(SimChoicesAction.Undo(effect.previous))
            assertEquals(
                2,
                model.state
                    .until { it.rows.size == 2 }
                    .rows.size,
            )
        }

    @Test fun forgetAllGivesUndo() =
        runBlocking {
            val sims = simsOf(sim1, sim2)
            val store = MemorySimChoices(mapOf("+905553333344" to "tel/svc#2", "+905559998877" to "tel/svc#1"))
            val model = SimChoicesViewModel(store, sims.sims, ::names)
            model.state.until { it.rows.size == 2 }
            model.onAction(SimChoicesAction.ForgetAll)
            val effect = withTimeout(5_000) { model.effect.first() } as SimChoicesEffect.ForgottenAll
            assertEquals(2, effect.count)
            assertTrue(model.state.until { it.rows.isEmpty() }.visible)
            model.onAction(SimChoicesAction.Undo(effect.previous))
            assertEquals(
                2,
                model.state
                    .until { it.rows.size == 2 }
                    .rows.size,
            )
        }

    @Test fun theListIsHiddenOnASingleSimPhone() =
        runBlocking {
            val sims = simsOf(sim1)
            val model = SimChoicesViewModel(MemorySimChoices(mapOf("+905553333344" to "tel/svc#1")), sims.sims, ::names)
            kotlinx.coroutines.delay(100)
            assertFalse(model.state.value.visible)
            assertTrue(
                model.state.value.rows
                    .isEmpty(),
            )
        }

    @Test fun belowTwoSimsNothingIsShownEvenForASimsFlowWithoutChips() =
        runBlocking {
            val model = SimChoicesViewModel(MemorySimChoices(), MutableStateFlow(Sims()), ::names)
            assertFalse(model.state.value.visible)
        }

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

    @Test fun thePlacerRemembersAPickAndUsesItNextTimeUntilItIsForgotten() =
        runBlocking {
            val sims = simsOf(sim1, sim2)
            val remembered = MemorySimChoices()
            val launcher = Launched()
            val placer = CallPlacer(NativePhoneOps(host.core), sims.sims, launcher, remembered = remembered)
            assertTrue("two SIMs and no default ask", placer.place("+905553333344") is PlaceResult.NeedsSim)
            placer.place("+905553333344", simKey = "tel/svc#2")
            assertEquals("tel/svc#2", remembered.keyOf("+90 555 333 33 44"))
            assertEquals(PlaceResult.Placed("+905553333344", "tel/svc#2"), placer.place("+905553333344"))
            remembered.forget("+905553333344")
            assertTrue(placer.place("+905553333344") is PlaceResult.NeedsSim)
        }

    @Test fun anEmergencyNumberIsNeverRemembered() =
        runBlocking {
            val sims = simsOf(sim1, sim2)
            val remembered = MemorySimChoices()
            val placer = CallPlacer(NativePhoneOps(host.core), sims.sims, Launched(), remembered = remembered)
            placer.place("112", simKey = "tel/svc#1")
            assertTrue(remembered.choices.value.isEmpty())
        }
}
