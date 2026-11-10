package dev.rovsti.phone.data

import dev.rovsti.core.model.phone.PhoneAccountInfo
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.phone.HostRust
import dev.rovsti.platform.telephony.FakeSimRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SIM rule of `apps/phone/src/sim_ui.rs` (`sim_needs_choice`) on the real `dial.*` ops. */
class CallPlacerTest {
    private val host by lazy { HostRust("rovsti-placer") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    private class Launched : CallLauncher {
        val placed = mutableListOf<Pair<String, String?>>()
        var result = CallLauncher.Launch.PLACED

        override fun place(
            number: String,
            accountKey: String?,
        ): CallLauncher.Launch {
            placed += number to accountKey
            return result
        }
    }

    private val sim1 = PhoneAccountInfo("tel/svc#1", "Turkcell", slotIndex = 0, sim = true)
    private val sim2 = PhoneAccountInfo("tel/svc#2", "Vodafone", slotIndex = 1, sim = true)

    private suspend fun placer(
        repo: FakeSimRepository,
        launcher: Launched,
    ): CallPlacer {
        val ops = NativePhoneOps(host.core)
        val state = SimState(repo, ops, scope)
        eventually("sims built") { state.sims.value.accounts.accounts.size == repo.current.value.accounts.size }
        return CallPlacer(ops, state.sims, launcher)
    }

    @Test fun anEmergencyNumberNeverAsksForASim() =
        runBlocking {
            val launcher = Launched()
            val placer = placer(FakeSimRepository(listOf(sim1, sim2)), launcher)
            val result = placer.place("112")
            assertEquals(PlaceResult.Placed("112", null), result)
            assertEquals(listOf("112" to null), launcher.placed)
        }

    @Test fun anEmergencyNumberIgnoresAChosenSimToo() =
        runBlocking {
            val launcher = Launched()
            val placer = placer(FakeSimRepository(listOf(sim1, sim2), defaultKey = "tel/svc#2"), launcher)
            assertEquals(PlaceResult.Placed("112", null), placer.place("112", simKey = "tel/svc#1"))
        }

    @Test fun twoSimsWithAskNeedTheChooser() =
        runBlocking {
            val launcher = Launched()
            val placer = placer(FakeSimRepository(listOf(sim1, sim2)), launcher)
            val result = placer.place("+905551112233")
            assertTrue(result is PlaceResult.NeedsSim)
            result as PlaceResult.NeedsSim
            assertEquals("+905551112233", result.number)
            assertEquals(listOf("tel/svc#1", "tel/svc#2"), result.choices.map { it.key })
            assertTrue("no call was placed", launcher.placed.isEmpty())
            // The chooser answers with a key and the same call goes through.
            assertEquals(PlaceResult.Placed("+905551112233", "tel/svc#2"), placer.place("+905551112233", "tel/svc#2"))
        }

    @Test fun aDefaultSimIsUsedWithoutAsking() =
        runBlocking {
            val launcher = Launched()
            val placer = placer(FakeSimRepository(listOf(sim1, sim2), defaultKey = "tel/svc#2"), launcher)
            assertEquals(PlaceResult.Placed("+905551112233", "tel/svc#2"), placer.place("+905551112233"))
            assertEquals(listOf("+905551112233" to "tel/svc#2"), launcher.placed)
        }

    @Test fun oneSimLetsTheSystemPick() =
        runBlocking {
            val launcher = Launched()
            val placer = placer(FakeSimRepository(listOf(sim1)), launcher)
            assertEquals(PlaceResult.Placed("+905551112233", null), placer.place("+905551112233"))
        }

    @Test fun textThatIsNoNumberIsInvalid() =
        runBlocking {
            val launcher = Launched()
            val placer = placer(FakeSimRepository(listOf(sim1)), launcher)
            assertEquals(PlaceResult.Invalid("nothing to dial"), placer.place(""))
            assertEquals(
                PlaceResult.Invalid("no digit, '*' or '#' in the number"),
                placer.place("call me"),
            )
            assertTrue(launcher.placed.isEmpty())
        }

    @Test fun withoutCallPhoneTheDialerIsOpened() =
        runBlocking {
            val launcher = Launched().apply { result = CallLauncher.Launch.DIALER_OPENED }
            val placer = placer(FakeSimRepository(listOf(sim1)), launcher)
            assertEquals(PlaceResult.HandedToDialer("+905551112233"), placer.place("+905551112233"))
        }

    @Test fun theCoreKnowsTheEmergencyNumbers() =
        runBlocking {
            val placer = placer(FakeSimRepository(), Launched())
            assertTrue(placer.isEmergency("112"))
            assertTrue(placer.isEmergency("911"))
            assertTrue(!placer.isEmergency("+905551112233"))
        }
}
