package dev.rovsti.contacts.card

import dev.rovsti.core.model.contacts.WaLookup
import dev.rovsti.core.model.contacts.WaLookupUser
import dev.rovsti.core.model.contacts.WaPresence
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E4.3 on the REAL Rust core: rows, dates and age, the default number, the call history gate and the WhatsApp lookup.
 * Robolectric like the screen tests: one JVM may load `librovsti_ffi` into only one class loader, so every test of this
 * package that needs the library runs in the same (sandbox) loader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactCardViewModelTest {
    private var rig: CardRig? = null

    @After fun stop() {
        rig
            ?.scope
            ?.coroutineContext
            ?.get(kotlinx.coroutines.Job)
            ?.cancel()
    }

    private fun rig(
        id: Long,
        build: () -> CardRig = { CardRig(id) },
    ): CardRig = build().also { rig = it }

    @Test fun theBirthdayRowCarriesTheDateAndTheAge() {
        val r = rig(1)
        val date = r.row(r.ready(), "date-0")
        assertEquals("Birthday · 35 years old", date.label)
        assertTrue("the date and when: ${date.title}", date.title.startsWith("3 Oct 1990"))
        assertTrue("and when it is next: ${date.title}", date.title.contains("days"))
        assertNotNull("a birthday offers to send a wish", date.wish)
        assertTrue(date.wish!!.contains("36th"))
    }

    @Test fun theDefaultNumberComesFirstWithTheDefaultTag() {
        val r = rig(1)
        val numbers =
            r
                .ready()
                .sections
                .first { it.kind == RowKind.Number }
                .rows
        assertEquals(2, numbers.size)
        assertTrue("the default number is on top: ${numbers[0].title}", numbers[0].title.contains("532"))
        assertTrue(numbers[0].isDefault)
        assertFalse(numbers[1].isDefault)
        assertEquals("Mobile", numbers[0].label?.substringBefore(" ·"))
        assertEquals("a foreign number names its country", "Work · Germany", numbers[1].label)
    }

    @Test fun aSingleNumberHasNoDefaultTag() {
        val r = rig(2)
        val state = r.ready()
        val only =
            state.sections
                .single { it.kind == RowKind.Number }
                .rows
                .single()
        assertFalse(only.isDefault)
        assertEquals(listOf(CapsuleKind.Call, CapsuleKind.Message), state.capsules.map { it.kind })
        assertEquals("a name and a number are all there is", listOf(RowKind.Number), state.sections.map { it.kind })
    }

    @Test fun theNumberMenuOffersTheCopyFormsShareAndTheDefault() {
        val r = rig(1)
        val menu = r.row(r.ready(), "number-1").menu.map { it.label }
        assertEquals("Call", menu.first())
        assertEquals("Message", menu[1])
        assertTrue("two or more forms to copy: $menu", menu.count { it.startsWith("Copy") } >= 2)
        assertTrue(menu.contains("Share Number"))
        assertTrue("a sibling number offers the default: $menu", menu.contains("Set as default"))
        val first = r.row(r.ready(), "number-0").menu.map { it.label }
        assertTrue("the default one offers to clear it: $first", first.contains("Clear default"))
    }

    @Test fun addressWebsiteAndRelationRowsCarryWhatTheyOpen() {
        val r = rig(1)
        val state = r.ready()
        val address = r.row(state, "address-0")
        assertTrue((address.tap as FieldOp.OpenUrl).url.startsWith("geo:"))
        assertTrue(address.menu.map { it.label }.containsAll(listOf("Copy Address", "Share Address", "Open in Maps")))
        val site = r.row(state, "website-0")
        assertEquals("https://acme.example/ayse", (site.tap as FieldOp.OpenUrl).url)
        assertEquals("Burak Demir", r.row(state, "relation-0").relationName)
        assertEquals("Brother", r.row(state, "relation-0").label)
    }

    @Test fun historyIsHiddenWithoutThePermission() {
        val calls = listOf(CardRig.call(1, "+90 532 111 22 33", CallLogKind.Outgoing, 3_600_000, 125))
        val r =
            rig(
                1,
            ) {
                CardRig(
                    1,
                    permission = kotlinx.coroutines.flow.MutableStateFlow(PermissionState.DeniedOnce),
                    calls = calls,
                )
            }
        val recent = r.ready().recent
        assertTrue("a permission row, no calls: $recent", recent is CardRecent.NeedsPermission)
        assertTrue((recent as CardRecent.NeedsPermission).text.title.isNotBlank())
        // Granting it shows the calls without reopening the card.
        r.permission.value = PermissionState.Granted
        val shown = r.stateWhere { it.recent is CardRecent.Calls }.recent as CardRecent.Calls
        assertEquals("1 call, last call today", shown.summary)
        assertEquals(1, shown.rows.size)
    }

    @Test fun onlyTheCallsOfThisContactAreListed() {
        val calls =
            listOf(
                CardRig.call(1, "+90 532 111 22 33", CallLogKind.Missed, 7_200_000),
                CardRig.call(2, "0532 123 45 67", CallLogKind.Incoming, 3_600_000, 60),
                CardRig.call(3, "+49 30 1234567", CallLogKind.Outgoing, 86_400_000 * 2, 300),
            )
        val r = rig(1) { CardRig(1, calls = calls) }
        val recent = r.stateWhere { it.recent is CardRecent.Calls }.recent as CardRecent.Calls
        assertEquals(2, recent.rows.size)
        assertEquals("2 calls, last call today", recent.summary)
    }

    @Test fun oneWhatsAppLookupPerOpenCard() {
        val wa = FakeCardWa(linked = true, chatUsers = emptyList())
        val r = rig(3) { CardRig(3, wa = wa) }
        r.stateWhere { it.waJid != null }
        assertEquals("one lookup with every international number of the contact", 1, wa.lookups.size)
        assertEquals(2, wa.lookups.single().size)
        // Changes on both sides of the card do not ask again: a chat arrives, the contact is edited.
        wa.chats.value = listOf("49151000000")
        runBlocking { r.repo.setStarred(3, true) }
        r.stateWhere { it.header?.starred == true }
        wa.chats.value = listOf("49151000001")
        Thread.sleep(300)
        assertEquals("still one lookup", 1, wa.lookups.size)
    }

    @Test fun theChipIsOnlyOnTheMatchingNumber() {
        // The lookup finds the mobile number only; the work number gets no chip.
        val wa =
            FakeCardWa(linked = true, answer = {
                WaLookup(
                    WaLookup.DONE,
                    listOf(
                        WaLookupUser("902125550011@s.whatsapp.net", false),
                        WaLookupUser("905337654321@s.whatsapp.net", true),
                    ),
                )
            })
        val r = rig(3) { CardRig(3, wa = wa) }
        val state = r.stateWhere { it.waJid != null }
        val rows = state.sections.first { it.kind == RowKind.Number }.rows
        assertNull("work number: ${rows[0].title}", rows[0].waJid)
        assertEquals("905337654321@s.whatsapp.net", rows[1].waJid)
        assertEquals(
            listOf(CapsuleKind.Call, CapsuleKind.Message, CapsuleKind.WhatsApp),
            state.capsules.map { it.kind },
        )
    }

    @Test fun aMatchingChatNeedsNoLookupAndShowsPresence() {
        val wa = FakeCardWa(linked = true, chatUsers = listOf("905337654321"))
        val r = rig(3) { CardRig(3, wa = wa) }
        wa.updates.tryEmit(WaPresence(available = true))
        val state = r.stateWhere { it.header?.presence != null }
        assertEquals("Online", state.header?.presence)
        assertTrue("no lookup when a chat already matches", wa.lookups.isEmpty())
        assertEquals(listOf("905337654321@s.whatsapp.net"), wa.subscriptions.distinct())
        assertEquals("905337654321@s.whatsapp.net", state.waJid)
        val chips =
            state.sections
                .first { it.kind == RowKind.Number }
                .rows
                .map { it.waJid }
        assertEquals(listOf(null, "905337654321@s.whatsapp.net"), chips)
    }

    @Test fun withoutALinkNothingGoesToWhatsApp() {
        val wa = FakeCardWa(linked = false)
        val r = rig(3) { CardRig(3, wa = wa) }
        val state = r.ready()
        Thread.sleep(300)
        assertTrue(wa.lookups.isEmpty())
        assertNull(state.waJid)
        assertNull(state.header?.presence)
        assertTrue(state.sections.none { it.kind == RowKind.Whatsapp })
    }

    @Test fun profileLinesComeFromTheProfile() {
        val wa =
            FakeCardWa(
                linked = true,
                chatUsers = listOf("905337654321"),
                profile =
                    dev.rovsti.core.model.contacts.WaProfileInput(
                        "905337654321@s.whatsapp.net",
                        pushName = "Elif K.",
                        about = "At work",
                    ),
            )
        val r = rig(3) { CardRig(3, wa = wa) }
        val section =
            r.stateWhere { s -> s.sections.any { it.kind == RowKind.Whatsapp } }.sections.first {
                it.kind ==
                    RowKind.Whatsapp
            }
        assertEquals(listOf("WhatsApp name", "About"), section.rows.map { it.label })
        assertEquals("At work", section.rows.last().title)
    }

    @Test fun callDialsTheDefaultNumberOrAsksWhichOne() {
        val r = rig(1)
        r.ready()
        r.model.onAction(ContactCardAction.Call)
        assertEquals(ContactCardEffect.Dial("+90 532 111 22 33"), r.effect())
        val two = rig(5)
        two.ready()
        two.model.onAction(ContactCardAction.Call)
        val pick = two.effect() as ContactCardEffect.PickNumber
        assertEquals(2, pick.choices.size)
        two.model.onAction(ContactCardAction.Message)
        assertTrue("with no default the first number is messaged", two.effect() is ContactCardEffect.Sms)
    }

    @Test fun starringAndTheDefaultWriteTheContact() {
        val r = rig(1)
        r.ready()
        r.model.onAction(ContactCardAction.ToggleStar)
        val unstarred = r.stateWhere { it.header?.starred == false }
        assertFalse(unstarred.header!!.starred)
        // Make the work number the default.
        r.model.onAction(
            ContactCardAction.Field(FieldOp.SetDefault(emails = false, value = "+49 30 1234567", clear = false)),
        )
        assertEquals(ContactCardEffect.Toast("Default number set"), r.effect())
        val state =
            r.stateWhere { s ->
                s.sections
                    .first { it.kind == RowKind.Number }
                    .rows[0]
                    .title
                    .contains("30")
            }
        assertTrue(
            state.sections
                .first { it.kind == RowKind.Number }
                .rows[0]
                .isDefault,
        )
        // And clear it again.
        r.model.onAction(
            ContactCardAction.Field(FieldOp.SetDefault(emails = false, value = "+49 30 1234567", clear = true)),
        )
        assertEquals(ContactCardEffect.Toast("Default cleared"), r.effect())
    }

    @Test fun copyWritesTheClipboardAndAnswersWithTheNotice() {
        val r = rig(1)
        r.ready()
        r.model.onAction(ContactCardAction.Field(FieldOp.Copy("ayse@acme.example", "Email copied")))
        assertEquals(ContactCardEffect.Toast("Email copied"), r.effect())
        assertEquals(listOf("ayse@acme.example" to false), r.clipboard.copies)
    }

    @Test fun aRelationOpensTheCardOfThePersonOrSearches() {
        val r = rig(1)
        r.ready()
        r.model.onAction(ContactCardAction.Relation("Burak Demir"))
        assertEquals(ContactCardEffect.OpenCard(2), r.effect())
        r.model.onAction(ContactCardAction.Relation("Nobody Atall"))
        assertEquals(ContactCardEffect.SearchFor("Nobody Atall"), r.effect())
    }

    @Test fun aDeletedContactClosesThePage() {
        val r = rig(2)
        r.ready()
        runBlocking { r.repo.delete(2) }
        assertEquals(ContactCardEffect.Close, r.effect())
        runBlocking { withTimeout(CardRig.TIMEOUT_MS) { r.model.state.first { it.phase == CardPhase.Gone } } }
    }
}
