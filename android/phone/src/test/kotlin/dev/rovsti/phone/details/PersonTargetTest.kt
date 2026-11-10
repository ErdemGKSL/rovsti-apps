package dev.rovsti.phone.details

import dev.rovsti.core.model.phone.CallType
import dev.rovsti.core.model.phone.EntryRef
import dev.rovsti.core.model.phone.RecentsRow
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.model.search.WireNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which numbers the sheet of a Recents row is about. */
class PersonTargetTest {
    private fun row(
        number: String,
        name: String?,
        contactId: Long?,
    ) = RecentsRow(
        numberRaw = number,
        normalized = number,
        callType = CallType.Missed,
        count = 1,
        firstMs = 0,
        lastMs = 0,
        name = name,
        label = "mobile",
        contactId = contactId,
        callable = true,
        accountKey = null,
        whatsapp = false,
        refs = listOf(EntryRef.Log(1)),
    )

    private val ayse =
        WireContact(
            id = 1,
            displayName = "Ayse Yilmaz",
            numbers = listOf(WireNumber("0555 111 22 33", "mobile"), WireNumber("+90 212 555 01 01", "work")),
        )

    @Test fun aRowOfAContactOpensAllTheirNumbers() {
        val target = personTarget(row("+905551112233", "Ayse Yilmaz", 1), listOf(ayse))
        assertEquals("Ayse Yilmaz", target.name)
        assertEquals(listOf("0555 111 22 33", "+90 212 555 01 01"), target.numbers.map { it.raw })
        assertEquals(1L, target.contactId)
    }

    @Test fun aNumberTheBookWritesDifferentlyIsNotListedTwice() {
        val target = personTarget(row("+90 555 111 22 33", "Ayse Yilmaz", 1), listOf(ayse))
        assertEquals(2, target.numbers.size)
    }

    @Test fun aRowNumberTheBookDoesNotHaveComesFirst() {
        val target = personTarget(row("+49 30 123456", "Ayse Yilmaz", 1), listOf(ayse))
        assertEquals("+49 30 123456", target.numbers.first().raw)
        assertEquals(3, target.numbers.size)
    }

    @Test fun anUnknownNumberIsTheNumberSheetWithOneNumber() {
        val target = personTarget(row("+49 30 123456", null, null), listOf(ayse))
        assertNull(target.name)
        assertEquals(listOf("+49 30 123456"), target.numbers.map { it.raw })
    }

    @Test fun aContactTheBookLostKeepsTheNameTheLogKnew() {
        val target = personTarget(row("+905551112233", "Ayse Yilmaz", 7), null)
        assertEquals("Ayse Yilmaz", target.name)
        assertEquals(1, target.numbers.size)
    }
}
