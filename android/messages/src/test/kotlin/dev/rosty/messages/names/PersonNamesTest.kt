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

    private fun names() =
        PersonNames(
            contactName = { book.value[it] },
            contactChanges = book,
            live = live,
            stored = { stored[it] },
            readable = { "read:$it" },
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
            assertEquals(PersonName("77700000000001", NameTier.Number), names.ofJid("77700000000001@lid"))
            assertTrue(live.asked.isEmpty())
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
