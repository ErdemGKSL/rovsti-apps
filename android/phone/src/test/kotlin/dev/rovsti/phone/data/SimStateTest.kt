package dev.rovsti.phone.data

import dev.rovsti.core.model.phone.PhoneAccountInfo
import dev.rovsti.phone.HostRust
import dev.rovsti.platform.telephony.FakeSimRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `SimRepository` through the real `sim.build` and `sim.dot`. */
class SimStateTest {
    private val host by lazy { HostRust("rovsti-sim") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    private val sim1 =
        PhoneAccountInfo("tel/svc#1", "Turkcell", slotIndex = 0, colorArgb = 0xFF2E7D32.toInt(), sim = true)
    private val sim2 =
        PhoneAccountInfo("tel/svc#2", "Vodafone", slotIndex = 1, colorArgb = 0xFFC62828.toInt(), sim = true)

    @Test fun twoSimsWithoutADefaultAskEveryTime() =
        runBlocking {
            val repo = FakeSimRepository(listOf(sim1, sim2))
            val state =
                SimState(
                    repo,
                    dev.rovsti.core.ops
                        .NativePhoneOps(host.core),
                    scope,
                )
            eventually("sims built") { state.sims.value.accounts.accounts.size == 2 }
            val now = state.sims.value
            assertTrue(now.visible)
            assertTrue(now.ask)
            assertEquals(null, now.defaultKey)
            assertEquals(listOf("tel/svc#1", "tel/svc#2"), now.chips.map { it.key })
            assertTrue("chips are short labels", now.chips.all { it.chip.isNotBlank() && it.chip.length <= 8 })
        }

    @Test fun aDefaultSimStopsTheAsking() =
        runBlocking {
            val repo = FakeSimRepository(listOf(sim1, sim2))
            val state =
                SimState(
                    repo,
                    dev.rovsti.core.ops
                        .NativePhoneOps(host.core),
                    scope,
                )
            eventually("asking") { state.sims.value.ask }
            repo.current.value = repo.current.value.copy(defaultKey = "tel/svc#2", ask = false)
            eventually("default taken") { state.sims.value.defaultKey == "tel/svc#2" }
            assertFalse(state.sims.value.ask)
            val chosen =
                state.sims.value.chips
                    .first { it.key == "tel/svc#2" }
            assertTrue(
                "the label names the default SIM",
                state.sims.value.defaultLabel in listOf(chosen.chip, chosen.title),
            )
        }

    @Test fun oneSimHidesTheChip() =
        runBlocking {
            val state =
                SimState(
                    FakeSimRepository(listOf(sim1)),
                    dev.rovsti.core.ops
                        .NativePhoneOps(host.core),
                    scope,
                )
            eventually("built") { state.sims.value.accounts.accounts.size == 1 }
            assertFalse(state.sims.value.visible)
            assertFalse(state.sims.value.ask)
        }

    @Test fun theDotColourIsOpaqueAndReadsAgainstTheFill() =
        runBlocking {
            val state =
                SimState(
                    FakeSimRepository(),
                    dev.rovsti.core.ops
                        .NativePhoneOps(host.core),
                    scope,
                )
            // Pale yellow on white would vanish: the core moves it.
            val dot = state.dot(0xFFFFF59D.toInt(), 0xFFFFFFFF.toInt())
            assertEquals(0xFF, dot ushr 24)
            assertTrue("moved away from the pale colour", dot != 0xFFFFF59D.toInt())
        }
}
