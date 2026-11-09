package dev.rovsti.contacts.emergency

import dev.rovsti.contacts.me.PeopleRig
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.CardRequest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4.14 Emergency Contacts (C43) on the real Rust core: the order is the preference `emergency`, a drop and the row
 * menu's steps go through `contacts.favorites_move` and are saved, the Call button dials the stored number.
 */
class EmergencyViewModelTest {
    private var rig: PeopleRig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun rig(keys: List<String> = emptyList()) =
        PeopleRig().also {
            rig = it
            if (keys.isNotEmpty()) it.wait { it.store.update { p -> p.copy(emergency = keys) } }
        }

    private fun PeopleRig.ids(): List<Long> = stateWhere { it.phase == EmergencyPhase.Ready }.people.map { it.id }

    private fun PeopleRig.stateWhere(predicate: (EmergencyUiState) -> Boolean) =
        wait { emergency.state.first { predicate(it) } }

    private fun PeopleRig.effect() = wait { emergency.effects.first() }

    @Test fun rowsFollowTheSavedOrderAndShowTheNumberInTheDisplayFormat() {
        val r = rig(listOf("k-7", "k-2", "k-11"))
        val state = r.stateWhere { it.people.size == 3 }
        assertEquals(listOf(7L, 2L, 11L), state.people.map { it.id })
        val ready = r.waitBook() as dev.rovsti.contacts.data.Book.Ready
        for (person in state.people) {
            val wire = ready.contacts.first { it.id == person.id }
            val card = r.wait { r.host.ops.card(CardRequest(wire, r.store.prefs.value.nameFormat)) } as Outcome.Ok
            assertEquals(
                card.value.numbers
                    .first()
                    .display,
                person.secondary,
            )
        }
    }

    @Test fun aDeletedContactIsDroppedFromTheList() {
        val r = rig(listOf("k-7", "k-gone", "k-2"))
        assertEquals(listOf(7L, 2L), r.stateWhere { it.people.size == 2 }.people.map { it.id })
    }

    @Test fun aDropIsSavedAndSurvivesARestart() {
        val r = rig(listOf("k-1", "k-2", "k-3"))
        r.stateWhere { it.people.size == 3 }
        r.emergency.onAction(EmergencyAction.Move(from = 0, to = 2))
        r.stateWhere { it.people.map { p -> p.id } == listOf(2L, 3L, 1L) }
        val fresh = r.reloaded()
        r.wait { fresh.load() }
        assertEquals(listOf("k-2", "k-3", "k-1"), fresh.emergency.value)
    }

    @Test fun moveEarlierAndLaterAreButtonMoves() {
        val r = rig(listOf("k-1", "k-2", "k-3"))
        r.stateWhere { it.people.size == 3 }
        r.emergency.onAction(EmergencyAction.MoveStep(index = 2, up = true))
        r.stateWhere { it.people.map { p -> p.id } == listOf(1L, 3L, 2L) }
        r.emergency.onAction(EmergencyAction.MoveStep(index = 0, up = false))
        r.stateWhere { it.people.map { p -> p.id } == listOf(3L, 1L, 2L) }
        assertEquals(listOf("k-3", "k-1", "k-2"), r.store.emergency.value)
    }

    @Test fun movingTheFirstRowEarlierChangesNothing() {
        val r = rig(listOf("k-1", "k-2"))
        r.stateWhere { it.people.size == 2 }
        r.emergency.onAction(EmergencyAction.MoveStep(index = 0, up = true))
        Thread.sleep(300)
        assertEquals(listOf("k-1", "k-2"), r.store.emergency.value)
    }

    @Test fun removeTakesThePersonOffTheList() {
        val r = rig(listOf("k-1", "k-2"))
        r.stateWhere { it.people.size == 2 }
        r.emergency.onAction(EmergencyAction.Remove(1))
        assertEquals(EmergencyEffect.Toast("Removed from emergency contacts"), r.effect())
        assertEquals(listOf(2L), r.stateWhere { it.people.size == 1 }.people.map { it.id })
    }

    @Test fun addAllStopsAtTheLimitAndSaysWhy() {
        val r = rig(listOf("k-1", "k-2", "k-3"))
        r.stateWhere { it.people.size == 3 }
        r.emergency.onAction(EmergencyAction.AddAll(setOf(4, 5, 6)))
        assertEquals(EmergencyEffect.Toast("Up to 5 emergency contacts"), r.effect())
        val state = r.stateWhere { it.people.size == 5 }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), state.people.map { it.id })
        assertFalse(state.canAdd)
    }

    @Test fun thePickerOffersOnlyPeopleWithANumberWhoAreNotOnTheList() {
        val r = rig(listOf("k-1"))
        val noNumber = r.add("Nora", "Numara")
        val state = r.stateWhere { s -> s.pickables.any { it.name == "Nora Numara" }.not() && s.pickables.size == 39 }
        assertTrue(state.pickables.none { it.id == 1L || it.id == noNumber })
    }

    @Test fun callDialsTheStoredNumber() {
        val r = rig(listOf("k-2"))
        r.stateWhere { it.people.size == 1 }
        r.emergency.onAction(EmergencyAction.Call(2))
        assertEquals(EmergencyEffect.Dial("0532 123 45 67"), r.effect())
        r.emergency.onAction(EmergencyAction.Call(99))
        assertEquals(EmergencyEffect.Toast("This contact has no number."), r.effect())
    }

    @Test fun shareHandsOverRustsNumberedList() {
        val r = rig(listOf("k-2", "k-7"))
        r.stateWhere { it.people.size == 2 }
        r.emergency.onAction(EmergencyAction.Share)
        val text = (r.effect() as EmergencyEffect.Share).text
        assertTrue(text, text.startsWith("Emergency contacts"))
        assertTrue(text, text.contains("1. Burak Demir: 0532 123 45 67"))
        assertTrue(text, text.contains("2. Ahmet Çelik: 0505 222 33 44"))
    }

    @Test fun theHeaderOffersAddOnAnEmptyListAndEditNeedsTwo() {
        val empty = rig().stateWhere { it.phase == EmergencyPhase.Ready }
        assertEquals(listOf(EmergencyHeaderAction.Add), empty.header)
        assertTrue(empty.people.isEmpty())
        val two = rig(listOf("k-1", "k-2")).stateWhere { it.people.size == 2 }
        assertEquals(listOf(EmergencyHeaderAction.Edit, EmergencyHeaderAction.Add), two.header)
        val full = rig(listOf("k-1", "k-2", "k-3", "k-4", "k-5")).stateWhere { it.people.size == 5 }
        assertEquals(listOf(EmergencyHeaderAction.Edit), full.header)
    }

    @Test fun editModeIsOffUnderTwoPeopleAndDoneIsTheOnlyButtonWhileEditing() {
        val r = rig(listOf("k-1", "k-2"))
        r.stateWhere { it.people.size == 2 }
        r.emergency.onAction(EmergencyAction.ToggleEdit)
        val editing = r.stateWhere { it.editing }
        assertEquals(listOf(EmergencyHeaderAction.Done), editing.header)
        r.emergency.onAction(EmergencyAction.Remove(1))
        r.effect()
        assertFalse(r.stateWhere { it.people.size == 1 }.editing)
    }
}
