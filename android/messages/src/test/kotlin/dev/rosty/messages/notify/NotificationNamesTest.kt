package dev.rosty.messages.notify

import dev.rosty.messages.names.NameTier
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.names.WaNames
import dev.rosty.platform.notify.NotificationSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name-order rule on the notification the core builds for a WhatsApp push while the app is closed. */
class NotificationNamesTest {
    private val jid = "905550000001@s.whatsapp.net"
    private val number = "+905550000001"

    private fun names(contact: String?) =
        PersonNames({ if (it == number) contact else null }, MutableStateFlow(Unit), readable = { "+90 555 000 00 01" })

    @Test fun aTitleThatIsANumberSaysNothingAboutThePerson() {
        assertTrue(NotificationNames.isNumber("+90 555 000 00 01"))
        assertFalse(NotificationNames.isNumber("GARANTI"))
        assertEquals(WaNames.None, NotificationNames.waNamesOfTitle("+905550000001"))
        assertEquals(WaNames(push = "Ada"), NotificationNames.waNamesOfTitle("Ada"))
    }

    @Test fun aSavedContactBeatsThePushNameOfThePush() =
        runTest {
            val who = names("Ada Lovelace").ofJid(jid, NotificationNames.waNamesOfTitle("ada 🌊"))
            assertEquals("Ada Lovelace", who.text)
            assertEquals(NameTier.Contact, who.tier)
        }

    @Test fun withoutAContactThePushNameStaysAndABareNumberReadsAsANumber() =
        runTest {
            assertEquals("ada", names(null).ofJid(jid, NotificationNames.waNamesOfTitle("ada")).text)
            val bare = names(null).ofJid(jid, NotificationNames.waNamesOfTitle("905550000001"))
            assertEquals("+90 555 000 00 01", bare.text)
            assertFalse(bare.named)
        }

    @Test fun retitlingKeepsTheLinesAndLeavesTheDigestAloneWithoutAName() {
        val digest = Digest(NotificationSpec("wa:$jid", "ada", listOf("Hi", "Are you there?"), null, true, false))
        val renamed = NotificationNames.retitled(digest, "Ada Lovelace")
        assertEquals("Ada Lovelace", renamed.spec.title)
        assertEquals(digest.spec.lines, renamed.spec.lines)
        assertSame(digest, NotificationNames.retitled(digest, null))
        assertSame(digest, NotificationNames.retitled(digest, " "))
    }
}
