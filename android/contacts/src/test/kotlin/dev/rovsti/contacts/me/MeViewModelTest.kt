package dev.rovsti.contacts.me

import dev.rovsti.contacts.R
import dev.rovsti.contacts.en
import dev.rovsti.design.text.UiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4.14 My card (C38) and the emergency list (C43) on the real Rust core: the header row is `contacts.me_state`, This Is
 * Me / Not Me and the emergency toggle write the preferences, and every sentence is Rust's.
 */
class MeViewModelTest {
    private var rig: PeopleRig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun rig() = PeopleRig().also { rig = it }

    private fun PeopleRig.ready(predicate: (MeUiState) -> Boolean = { it.myCard != null }): MeUiState =
        wait { me.state.first(predicate) }

    private fun PeopleRig.toast(): String = wait { (me.effects.first() as MeEffect.Toast).text.en() }

    @Test fun withNoCardTheRowOffersToSetOneUp() {
        val r = rig()
        val row = r.ready().myCard!!
        assertNull(row.contactId)
        assertEquals("Set up my card", row.title)
        assertEquals("Choose the contact that is you", row.detail)
    }

    @Test fun thisIsMeMakesTheRowTheContactAndSurvivesARestart() {
        val r = rig()
        r.ready()
        r.me.onAction(MeAction.SetMyCard(5))
        assertEquals("Set as my card", r.toast())
        val row = r.ready { it.myCard?.contactId == 5L }.myCard!!
        assertEquals("My card", row.title)
        assertEquals("Özgür Şahin", row.detail)
        val fresh = r.reloaded()
        r.wait { fresh.load() }
        assertEquals("k-5", fresh.myCard)
    }

    @Test fun notMeClearsTheCardAgain() {
        val r = rig()
        r.ready()
        r.me.onAction(MeAction.SetMyCard(5))
        r.toast()
        r.ready { it.myCard?.contactId == 5L }
        r.me.onAction(MeAction.ClearMyCard(5))
        assertEquals("My card removed", r.toast())
        assertNull(r.ready { it.myCard != null && it.myCard?.contactId == null }.myCard?.contactId)
        assertNull(r.store.myCard)
    }

    @Test fun aCardWhoseContactWasDeletedReadsAsUnset() {
        val r = rig()
        r.ready()
        r.wait { r.store.update { it.copy(myCard = "k-999") } }
        val row = r.ready { it.myCard != null && it.myCard?.title == "Set up my card" }.myCard!!
        assertNull(row.contactId)
    }

    @Test fun anotherChoiceReplacesTheCard() {
        val r = rig()
        r.ready()
        r.me.onAction(MeAction.SetMyCard(5))
        r.toast()
        r.me.onAction(MeAction.SetMyCard(11))
        r.toast()
        assertEquals("Ada Lovelace", r.ready { it.myCard?.contactId == 11L }.myCard?.detail)
    }

    @Test fun emergencyTogglesAddAndRemoveAndSaveTheKeys() {
        val r = rig()
        r.ready()
        r.me.onAction(MeAction.ToggleEmergency(2))
        assertEquals("Added to emergency contacts", r.toast())
        r.me.onAction(MeAction.ToggleEmergency(7))
        r.toast()
        val two = r.ready { it.emergencyIds.size == 2 }
        assertEquals(setOf(2L, 7L), two.emergencyIds)
        assertEquals("2 of 5", (two.emergencyDetail).en())
        assertEquals(listOf("k-2", "k-7"), r.store.emergency.value)
        r.me.onAction(MeAction.ToggleEmergency(2))
        assertEquals("Removed from emergency contacts", r.toast())
        assertEquals(setOf(7L), r.ready { it.emergencyIds.size == 1 }.emergencyIds)
    }

    @Test fun theSixthIsRefusedWithRustsSentenceAndRemovingStillWorksWhenFull() {
        val r = rig()
        r.ready()
        for (id in listOf(1L, 2L, 3L, 4L, 5L)) {
            r.me.onAction(MeAction.ToggleEmergency(id))
            r.toast()
        }
        r.ready { it.emergencyIds.size == 5 }
        r.me.onAction(MeAction.ToggleEmergency(6))
        assertEquals("Up to 5 emergency contacts", r.toast())
        assertEquals(5, r.store.emergency.value.size)
        r.me.onAction(MeAction.ToggleEmergency(3))
        assertEquals("Removed from emergency contacts", r.toast())
        assertEquals(4, r.ready { it.emergencyIds.size == 4 }.emergencyIds.size)
    }

    @Test fun aContactWithoutANumberCannotJoin() {
        val r = rig()
        val id = r.add("Nora", "Numara")
        r.waitBook()
        r.wait {
            withTimeout(PeopleRig.TIMEOUT_MS) {
                r.book.book.first { b ->
                    (b as? dev.rovsti.contacts.data.Book.Ready)?.contacts?.any {
                        it.id ==
                            id
                    } ==
                        true
                }
            }
        }
        r.me.onAction(MeAction.ToggleEmergency(id))
        assertEquals("This contact has no number", r.toast())
        assertTrue(
            r.store.emergency.value
                .isEmpty(),
        )
    }

    @Test fun theChooserListsEverybodyInTheListsOrder() {
        val r = rig()
        r.ready()
        val choices = r.wait { r.me.choices() }
        assertEquals(40, choices.size)
        assertEquals("Ada Lovelace", choices.first().name)
    }
}
