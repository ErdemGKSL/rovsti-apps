package dev.rovsti.phone.data

import dev.rovsti.core.model.phone.HoldDigit
import dev.rovsti.core.model.phone.SpeedDialEntry
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.phone.HostRust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speed dial over the preferences file, decided by the real `speed_dial.*` ops. */
class SpeedDialTest {
    private val host by lazy { HostRust("rovsti-speed-dial") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = tempDir("rovsti-speed")

    @After fun stop() = scope.cancel()

    private val deniz = SpeedDialEntry("k-deniz", "+905550001122", "Deniz")

    private fun rig(): Pair<PhoneStore, SpeedDial> {
        val ops = NativePhoneOps(host.core)
        val store = PhoneStore(ops, scope, dir, Dispatchers.IO, debounceMs = 50)
        return store to SpeedDial(store, ops)
    }

    @Test fun assignedSlotsSurviveARestart() =
        runBlocking {
            val (store, dial) = rig()
            store.load()
            assertTrue(dial.assign(4, deniz).changed)
            assertTrue(store.flush())
            val (again, dial2) = rig()
            again.load()
            assertEquals(deniz, dial2.map.getValue(4))
        }

    @Test fun holdingAKeyDoesWhatTheCoreSays() =
        runBlocking {
            val (store, dial) = rig()
            store.load()
            dial.assign(4, deniz)
            assertEquals(HoldDigit.Call("+905550001122", "Deniz"), dial.onHold('4', ""))
            assertEquals(HoldDigit.Unassigned(5), dial.onHold('5', ""))
            assertEquals(HoldDigit.Voicemail, dial.onHold('1', ""))
            assertEquals(
                "a key held in the middle of a number is a plain digit",
                HoldDigit.Ignore,
                dial.onHold('4', "05"),
            )
            assertEquals(HoldDigit.Ignore, dial.onHold('0', ""))
        }

    @Test fun freeDigitsBadgesAndLookupFollowTheMap() =
        runBlocking {
            val (store, dial) = rig()
            store.load()
            assertEquals((2..9).toList(), dial.freeDigits())
            dial.assign(4, deniz)
            assertEquals((2..9).filter { it != 4 }, dial.freeDigits())
            assertEquals(listOf(4), dial.badges().map { it.digit })
            assertEquals("Deniz", dial.badges().single().name)
            assertEquals(4, dial.digitFor(listOf("+905550001122")))
            assertNull(dial.digitFor(listOf("+905559990000")))
        }

    @Test fun unassignAndUndo() =
        runBlocking {
            val (store, dial) = rig()
            store.load()
            dial.assign(4, deniz)
            val gone = dial.unassign(4)
            assertTrue(gone.changed)
            assertTrue(dial.map.isEmpty())
            assertFalse("nothing to remove on a free key", dial.unassign(4).changed)
            dial.restore(gone.previous)
            assertEquals(deniz, dial.map.getValue(4))
        }
}
