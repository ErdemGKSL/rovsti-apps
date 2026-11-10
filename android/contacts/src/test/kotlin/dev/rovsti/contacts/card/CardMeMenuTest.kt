package dev.rovsti.contacts.card

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rovsti.contacts.R
import dev.rovsti.contacts.en
import dev.rovsti.design.text.UiText
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * E4.14 on the card: the "..." menu's This Is Me / Not Me (C38) and Add to / Remove from Emergency Contacts (C43), the
 * "My Card" badge, on the real Rust core. The words are Rust's (`contacts.me_state`, `contacts.me_toggle`).
 */
@Config(sdk = [35])
class CardMeMenuTest : ShotTest() {
    private var rig: CardRig? = null

    @After fun stop() {
        rig
            ?.scope
            ?.coroutineContext
            ?.get(kotlinx.coroutines.Job)
            ?.cancel()
    }

    private fun rig(id: Long = 1): CardRig = CardRig(id).also { rig = it }

    private fun toast(r: CardRig) = (r.effect() as ContactCardEffect.Toast).text

    @Test fun thisIsMeSetsTheCardAndTheBadgeShowsAndNotMeClearsIt() {
        val r = rig()
        assertFalse(r.ready().header!!.isMyCard)
        r.model.onAction(ContactCardAction.ToggleMyCard)
        assertEquals("Set as my card", (toast(r)).en())
        assertTrue(r.stateWhere { it.header?.isMyCard == true }.header!!.isMyCard)
        assertEquals("k-1", r.store.myCard)
        r.model.onAction(ContactCardAction.ToggleMyCard)
        assertEquals("My card removed", (toast(r)).en())
        assertFalse(r.stateWhere { it.header?.isMyCard == false }.header!!.isMyCard)
    }

    @Test fun emergencyAddsAndRemovesAndTheStateFollows() {
        val r = rig()
        assertFalse(r.ready().header!!.isEmergency)
        r.model.onAction(ContactCardAction.ToggleEmergency)
        assertEquals("Added to emergency contacts", (toast(r)).en())
        assertTrue(r.stateWhere { it.header?.isEmergency == true }.header!!.isEmergency)
        assertEquals(listOf("k-1"), r.store.emergency.value)
        r.model.onAction(ContactCardAction.ToggleEmergency)
        assertEquals("Removed from emergency contacts", (toast(r)).en())
        assertTrue(r.stateWhere { it.header?.isEmergency == false }.header != null)
        assertTrue(
            r.store.emergency.value
                .isEmpty(),
        )
    }

    @Test fun aContactWithoutANumberIsRefusedWithRustsSentence() {
        // Mehmet (4) has no number.
        val r = rig(4)
        r.ready()
        r.model.onAction(ContactCardAction.ToggleEmergency)
        assertEquals("This contact has no number", (toast(r)).en())
        assertTrue(
            r.store.emergency.value
                .isEmpty(),
        )
    }

    @Test fun theMenuOffersTheEntryThatFitsTheState() {
        val r = rig()
        val h = CardHarness(r.ready())
        composeFrame { HostedCard(h) }
        advance(400)
        compose.onNodeWithTag("toolbar-overflow").performClick()
        advance(700)
        compose.onNodeWithText("This Is Me").assertIsDisplayed()
        compose.onNodeWithText("Add to Emergency Contacts").assertIsDisplayed()
    }

    @Test fun theMenuOffersNotMeAndRemoveOnceSet() {
        val r = rig()
        r.ready()
        r.model.onAction(ContactCardAction.ToggleMyCard)
        toast(r)
        r.model.onAction(ContactCardAction.ToggleEmergency)
        toast(r)
        val state = r.stateWhere { it.header?.isMyCard == true && it.header?.isEmergency == true }
        val h = CardHarness(state)
        composeFrame { HostedCard(h) }
        advance(400)
        compose.onNodeWithText("My Card").assertIsDisplayed()
        compose.onNodeWithTag("toolbar-overflow").performClick()
        advance(700)
        compose.onNodeWithText("Not Me").assertIsDisplayed()
        compose.onNodeWithText("Remove from Emergency Contacts").assertIsDisplayed()
    }

    @Test fun tappingThisIsMeInTheMenuAsksTheModel() {
        val r = rig()
        val h = CardHarness(r.ready())
        composeFrame { HostedCard(h) }
        advance(400)
        compose.onNodeWithTag("toolbar-overflow").performClick()
        advance(700)
        compose.onNodeWithText("This Is Me").performClick()
        advance(300)
        assertTrue(h.actions.contains(ContactCardAction.ToggleMyCard))
    }
}
