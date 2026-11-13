package dev.rovsti.phone.settings

import dev.rovsti.core.model.search.WireContact
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.UnconfinedMain
import dev.rovsti.phone.data.SpeedDial
import dev.rovsti.phone.settings.speeddial.SpeedDialAction
import dev.rovsti.phone.settings.speeddial.SpeedDialEffect
import dev.rovsti.phone.settings.speeddial.SpeedDialViewModel
import dev.rovsti.phone.settings.speeddial.speedDialShareText
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.9b Speed dial on the REAL `speed_dial.*` ops: keys 2 to 9, key 1 the voicemail, Undo, share text. */
class SpeedDialViewModelTest {
    private val host by lazy { HostRust("rovsti-speed-dial-vm") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val people =
        MutableStateFlow<List<WireContact>?>(
            listOf(
                person(1, "Ayse Yilmaz", "+90 555 111 22 33"),
                person(3, "Deniz Aksoy", "+90 555 333 44 55"),
                person(5, "Burak Ozturk", "+90 555 555 66 77"),
            ),
        )

    @Before fun mainDispatcher() = UnconfinedMain.install()

    @After fun stop() = scope.cancel()

    private suspend fun model(): SpeedDialViewModel {
        val rig = SettingsRig(host, scope)
        rig.store.load()
        val model = SpeedDialViewModel(rig.store, SpeedDial(rig.store, rig.ops), people)
        model.state.until { it.loaded }
        return model
    }

    private suspend fun SpeedDialViewModel.effectNow() = withTimeout(10_000) { effect.first() }

    @Test fun keyOneIsTheVoicemailAndTwoToNineAreFree() =
        runBlocking {
            val ui = model().state.value
            assertEquals((1..9).toList(), ui.rows.map { it.digit })
            assertTrue(ui.rows.first().voicemail)
            assertTrue(ui.rows.drop(1).none { it.assigned })
            assertEquals("everybody with a number can be picked", 3, ui.pickables.size)
        }

    @Test fun assigningPutsThePersonOnTheKeyWithTheDefaultNumberAndUndoTakesItBack() =
        runBlocking {
            val model = model()
            model.onAction(SpeedDialAction.Assign(4, 3))
            val effect = model.effectNow() as SpeedDialEffect.Assigned
            assertEquals(4, effect.digit)
            val row = model.state.until { it.rows[3].assigned }.rows[3]
            assertEquals("Deniz Aksoy", row.name)
            assertEquals("+90 555 333 44 55", row.number)
            model.onAction(SpeedDialAction.Undo(effect.previous))
            assertFalse(
                model.state
                    .until { !it.rows[3].assigned }
                    .rows[3]
                    .assigned,
            )
        }

    @Test fun clearingOneGivesUndoAndClearAllGivesUndo() =
        runBlocking {
            val model = model()
            model.onAction(SpeedDialAction.Assign(2, 1))
            model.effectNow()
            model.onAction(SpeedDialAction.Assign(5, 5))
            model.effectNow()
            model.state.until { it.assignedCount == 2 }
            model.onAction(SpeedDialAction.Clear(2))
            val cleared = model.effectNow() as SpeedDialEffect.Cleared
            assertEquals("Ayse Yilmaz", cleared.name)
            model.state.until { it.assignedCount == 1 }
            model.onAction(SpeedDialAction.Undo(cleared.previous))
            model.state.until { it.assignedCount == 2 }
            model.onAction(SpeedDialAction.ClearAll)
            val all = model.effectNow() as SpeedDialEffect.ClearedAll
            assertEquals(2, all.count)
            model.state.until { it.assignedCount == 0 }
            model.onAction(SpeedDialAction.Undo(all.previous))
            assertEquals(2, model.state.until { it.assignedCount == 2 }.assignedCount)
        }

    @Test fun anUnknownContactOrAnEmptyKeyIsRefused() =
        runBlocking {
            val model = model()
            model.onAction(SpeedDialAction.Assign(4, 999))
            assertEquals(SpeedDialEffect.Rejected, model.effectNow())
            model.onAction(SpeedDialAction.Clear(7))
            assertEquals(SpeedDialEffect.Rejected, model.effectNow())
        }

    @Test fun theShareTextListsTheVoicemailAndEachAssignedKey() =
        runBlocking {
            val model = model()
            model.onAction(SpeedDialAction.Assign(3, 3))
            model.effectNow()
            val ui = model.state.until { it.assignedCount == 1 }
            val text = speedDialShareText("Speed dial", "Voicemail", ui)
            assertEquals("Speed dial\n1  Voicemail\n3  Deniz Aksoy  +90 555 333 44 55", text)
            assertNull(ui.rows.first().name)
        }
}
