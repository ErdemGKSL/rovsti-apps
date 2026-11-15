package dev.rosty.messages.compose

import dev.rosty.core.model.messages.SmsThreadRow
import dev.rosty.core.model.search.WireContact
import dev.rosty.core.model.search.WireNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** E6.8: the options of New Conversation and the existing thread of a number (pure). */
class NewConversationTest {
    private val ada =
        WireContact(
            1,
            "Ada Lovelace",
            listOf(WireNumber("+90 555 000 00 02", "Work"), WireNumber("+90 555 000 00 01", "Mobile", default = true)),
        )
    private val bo = WireContact(2, "Bo", listOf(WireNumber("0555 000 00 01")))

    @Test fun theTypedNumberComesFirstThenEachNumberOnceDefaultFirst() {
        val options = NewConversationViewModel.options("0555 000 00 03", listOf(ada))
        assertEquals(listOf("typed", "1:550000001", "1:550000002"), options.map { it.key })
        assertEquals("Mobile · +90 555 000 00 01", options[1].detail)
    }

    @Test fun aNumberTwoContactsShareIsListedOnce() {
        val options = NewConversationViewModel.options("", listOf(ada, bo))
        assertEquals(2, options.size)
    }

    @Test fun lettersAreNotATypedNumber() {
        assertEquals(emptyList<String>(), NewConversationViewModel.options("ab1", emptyList()).map { it.key })
    }

    @Test fun theOneToOneThreadOfANumberIsFoundByItsLastDigits() {
        val threads =
            listOf(
                SmsThreadRow(7, listOf("+905550000001"), "x", 0, 0),
                SmsThreadRow(8, listOf("+905550000001", "+905550000002"), "x", 0, 0),
            )
        assertEquals(7L, NewConversationViewModel.threadFor("0555 000 00 01", threads))
        assertNull(NewConversationViewModel.threadFor("0555 000 00 09", threads))
    }
}
