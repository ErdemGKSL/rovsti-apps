package dev.rosty.messages.names

import dev.rosty.core.model.wa.ChatMeta
import dev.rosty.core.model.wa.RichChat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one name-order rule: contact, WhatsApp name (verified, push), stored chat name, number. */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonNamesTest {
    private val number = "+905550000001"
    private val jid = "905550000001@s.whatsapp.net"
    private val group = "120363000000000001@g.us"
    private val book = MutableStateFlow(emptyMap<String, String>())
    private val live = FakeLiveNames()
    private val stored = HashMap<String, WaNames>()

    /** What the offline store knows: the numbers of the person behind an id, the current one first. */
    private val numbers = HashMap<String, List<String>>()

    private fun names() =
        PersonNames(
            contactName = { book.value[it] },
            contactChanges = book,
            live = live,
            stored = { stored[it] },
            readable = { "read:$it" },
            numbers = { numbers[it].orEmpty() },
            unknown = { "WhatsApp kullanıcısı" },
        )

    @Test fun eachTierWinsInOrder() {
        val all = WaNames(verified = "Ada Ltd", push = "ada", chat = "Ada (work)")
        assertEquals(PersonName("Ada Lovelace", NameTier.Contact), PersonNames.pick("Ada Lovelace", all, "live"))
        assertEquals(PersonName("Ada Ltd", NameTier.Verified), PersonNames.pick(null, all, "live"))
        assertEquals(PersonName("ada", NameTier.Push), PersonNames.pick(null, all.copy(verified = null), "live"))
        // The name asked from WhatsApp is a push (or verified) name too: it beats the stored chat name.
        assertEquals(PersonName("live", NameTier.Push), PersonNames.pick(null, WaNames(chat = "Ada (work)"), "live"))
        assertEquals(
            PersonName("Ada (work)", NameTier.Chat),
            PersonNames.pick(null, WaNames(chat = "Ada (work)"), null),
        )
        assertNull(PersonNames.pick(null, WaNames.None, null))
    }

    @Test fun blankNamesAreSkipped() {
        val blanks = WaNames(verified = "  ", push = "", chat = " Ada ")
        assertEquals(PersonName("Ada", NameTier.Chat), PersonNames.pick(" ", blanks, "\t"))
        assertNull(PersonNames.pick("", WaNames(verified = " ", push = " ", chat = ""), " "))
    }

    @Test fun aContactBeatsEveryWhatsAppName() =
        runTest {
            book.value = mapOf(number to "Ada Lovelace")
            live.land(number, "ada live")
            val who = names().of(number, WaNames(verified = "Ada Ltd", push = "ada", chat = "ada.l"))
            assertEquals(PersonName("Ada Lovelace", NameTier.Contact), who)
            assertTrue(who.saved)
            assertTrue(live.asked.isEmpty())
        }

    @Test fun verifiedBeatsPushAndTheStoreFillsWhatTheCallerLacks() =
        runTest {
            stored[number] = WaNames(verified = "Ada Ltd", push = "stored push")
            // The caller holds a push name only: the store's verified name still comes first.
            assertEquals(PersonName("Ada Ltd", NameTier.Verified), names().of(number, WaNames(push = "ada")))
            stored[number] = WaNames(push = "stored push")
            assertEquals(PersonName("ada", NameTier.Push), names().of(number, WaNames(push = "ada")))
            assertEquals(PersonName("stored push", NameTier.Push), names().of(number))
            assertTrue(live.asked.isEmpty())
        }

    @Test fun aStoredChatNameIsShownWhenItIsAllThereIsAndNothingIsAsked() =
        runTest {
            assertEquals(PersonName("ada.l", NameTier.Chat), names().of(number, WaNames(chat = "ada.l")))
            assertTrue(live.asked.isEmpty())
        }

    @Test fun anUnknownNumberIsShownAtOnceAskedOnceAndNamedWhenTheRevisionMoves() =
        runTest(UnconfinedTestDispatcher()) {
            val names = names()
            val moves = ArrayList<Long>()
            val watching = launch { names.changes.toList(moves) }
            val shown = ArrayList<PersonName>()

            shown += names.of(number)
            assertEquals(PersonName("read:$number", NameTier.Number), shown.last())
            assertFalse(shown.last().named)
            assertEquals(listOf(number), live.asked)

            val before = moves.size
            live.land(number, "Ada")
            assertEquals("the revision moved", before + 1, moves.size)
            shown += names.of(number)
            assertEquals(PersonName("Ada", NameTier.Push), shown.last())
            // Named now: nothing more is asked.
            assertEquals(listOf(number), live.asked)
            watching.cancel()
        }

    @Test fun aContactSavedLaterBeatsTheWhatsAppName() =
        runTest(UnconfinedTestDispatcher()) {
            val names = names()
            live.land(number, "Ada")
            val moves = ArrayList<Long>()
            val watching = launch { names.changes.toList(moves) }
            assertEquals("Ada", names.of(number).text)

            val before = moves.size
            book.value = mapOf(number to "Ada Lovelace")
            assertEquals("the address book change moved the names", before + 1, moves.size)
            assertEquals(PersonName("Ada Lovelace", NameTier.Contact), names.of(number))
            watching.cancel()
        }

    @Test fun aNumberTheCallerKnowsIsUnsavedSkipsTheAddressBook() =
        runTest {
            book.value = mapOf(number to "Ada Lovelace")
            assertEquals(PersonName("ada", NameTier.Push), names().unsaved(number, WaNames(push = "ada")))
        }

    @Test fun aJidIsNamedByItsNumberAndALidByWhatWhatsAppCarries() =
        runTest {
            val names = names()
            book.value = mapOf(number to "Ada Lovelace")
            assertEquals("Ada Lovelace", names.ofJid(jid, WaNames(push = "ada")).text)
            assertEquals("Ada Lovelace", names.sender(jid, "ada"))
            // A LID carries no number: only WhatsApp's names can name it, and nothing can be asked.
            assertEquals(
                PersonName("Mert K.", NameTier.Push),
                names.ofJid("77700000000001@lid", WaNames(push = "Mert K.")),
            )
            assertTrue(live.asked.isEmpty())
        }

    @Test fun aLidNobodyNamesShowsTheWhatsAppUserLabelAndNeverItsDigits() =
        runTest {
            val names = names()
            // The digits of the LID are a contact's number: a LID is not a number, whatever its digits.
            book.value = mapOf("+905550000001" to "Ada Lovelace")
            for (lid in listOf("77700000000001@lid", "77700000000001:4@lid", "905550000001@lid")) {
                val who = names.ofJid(lid)
                assertEquals(lid, PersonName("WhatsApp kullanıcısı", NameTier.Unknown), who)
                assertFalse(lid, who.named || who.saved)
                assertEquals(lid, "WhatsApp kullanıcısı", names.sender(lid, null))
                assertEquals(lid, "WhatsApp kullanıcısı", names.chatTitle(lid, null as RichChat?))
            }
            // The push name a message carried still names them.
            assertEquals("Mert K.", names.sender("77700000000001@lid", " Mert K. "))
            // A blank id (a line without a sender) gets the label too, never an empty name.
            assertEquals("WhatsApp kullanıcısı", names.sender(null, null))
            // What is neither a person nor a group has nothing but its id.
            assertEquals(PersonName("1700", NameTier.Number), names.ofJid("1700@broadcast"))
            assertTrue(live.asked.isEmpty())
        }

    @Test fun aLidWhoseNumberTheStoreKnowsIsNamedByThatNumber() =
        runTest {
            val names = names()
            val lid = "77700000000001@lid"
            numbers[lid] = listOf(number)
            // Nobody names the number: the number as people read it, and WhatsApp is asked for its name.
            assertEquals(PersonName("read:$number", NameTier.Number), names.ofJid(lid))
            assertEquals(listOf(number), live.asked)
            stored[number] = WaNames(push = "ada")
            assertEquals(PersonName("ada", NameTier.Push), names.ofJid(lid))
            book.value = mapOf(number to "Ada Lovelace")
            assertEquals(PersonName("Ada Lovelace", NameTier.Contact), names.ofJid(lid))
            assertEquals(listOf(number), names.numbersOf(lid))
        }

    @Test fun aContactThatOnlyHoldsTheFormerNumberStillNamesThePerson() =
        runTest {
            val names = names()
            val former = "+905550000009"
            val lid = "77700000000001@lid"
            // The person moved from `former` to `number`; the store lists the current number first.
            numbers[jid] = listOf(number, former)
            numbers[lid] = listOf(number, former)
            numbers["905550000009@s.whatsapp.net"] = listOf(number, former)
            book.value = mapOf(former to "Ada (old number)")
            for (id in listOf(jid, lid, "905550000009@s.whatsapp.net")) {
                val who = names.ofJid(id, WaNames(push = "ada"))
                assertEquals(id, PersonName("Ada (old number)", NameTier.Contact), who)
                assertTrue(id, who.saved)
            }
            // A contact that holds the current number comes first.
            book.value = mapOf(former to "Ada (old number)", number to "Ada Lovelace")
            assertEquals("Ada Lovelace", names.ofJid(lid).text)
            assertEquals("Ada Lovelace", names.chatTitle(jid, null as RichChat?))
            // Without a contact the rule goes on over the current number, never the former one.
            book.value = emptyMap()
            assertEquals(PersonName("read:$number", NameTier.Number), names.ofJid(jid))
            assertEquals(listOf(number), live.asked)
        }

    @Test fun withoutAStoreAPhoneIdIsLookedUpByTheNumberItCarries() =
        runTest {
            val names = names()
            assertEquals(listOf(number), names.numbersOf("905550000001:7@s.whatsapp.net"))
            assertEquals(emptyList<String>(), names.numbersOf("77700000000001@lid"))
            assertEquals(emptyList<String>(), names.numbersOf(null))
            book.value = mapOf(number to "Ada Lovelace")
            assertEquals("Ada Lovelace", names.ofJid("905550000001:7@s.whatsapp.net").text)
        }

    @Test fun aGroupKeepsItsSubjectAndAPersonIsNamedByTheRule() =
        runTest {
            val names = names()
            book.value = mapOf(number to "Ada Lovelace")
            assertEquals("Book club", names.chatTitle(group, RichChat(group, "Book club", null, 0, true)))
            assertEquals("120363000000000001", names.chatTitle(group, null as RichChat?))
            val chat = RichChat(jid, "ada.l", null, 0, false, ChatMeta(pushName = "ada"))
            assertEquals("Ada Lovelace", names.chatTitle(jid, chat))
            book.value = emptyMap()
            // No contact: the push name comes before the stored chat name.
            assertEquals("ada", names.chatTitle(jid, chat))
            assertEquals("ada", PersonNames.storedTitle(chat))
            assertEquals("ada.l", PersonNames.storedTitle(chat.copy(meta = ChatMeta())))
            assertEquals("Book club", PersonNames.storedTitle(RichChat(group, "Book club", null, 0, true)))
        }
}
