package dev.rovsti.contacts.intake

import dev.rovsti.contacts.en
import dev.rovsti.design.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The column mapping of an imported CSV (E4.10 C39), without the Rust core. */
class CsvMappingTest {
    private val file = "Vorname,Nachname,Handy,Mail,Notiz\r\nAda,Lovelace,+44 20 7946 0958,ada@example.org,\"a, b\"\r\n"

    @Test fun theColumnsAreListedWithTheirFirstValue() {
        val map = CsvMapping(file)
        assertEquals(listOf("Vorname", "Nachname", "Handy", "Mail", "Notiz"), map.columns.map { it.header })
        assertEquals(
            listOf("Ada", "Lovelace", "+44 20 7946 0958", "ada@example.org", "a, b"),
            map.columns.map { it.sample },
        )
    }

    @Test fun anUntouchedFileIsGivenToTheReaderAsItIs() {
        assertEquals(file, CsvMapping(file).rewritten())
    }

    @Test fun theGuessFollowsTheHeaderNameAndIsOnlyShown() {
        val map = CsvMapping("First Name,family name,Mobile Phone,E-mail 1 - Value,Organization Name\r\nA,B,C,D,E\r\n")
        assertEquals(
            listOf(CsvRole.FirstName, CsvRole.LastName, CsvRole.Phone, CsvRole.Email, null),
            map.columns.map { it.detected },
        )
        assertEquals("a guess is not a choice", null, map.columns[0].chosen)
        assertNull(map.columns[4].shown)
        assertEquals("Automatic", map.columns[4].roleLabel().en())
    }

    @Test fun aChosenRoleGetsTheGoogleHeaderAndTheRowsAreKept() {
        val map =
            CsvMapping(file)
                .with(0, CsvRole.FirstName)
                .with(1, CsvRole.LastName)
                .with(2, CsvRole.Phone)
                .with(3, CsvRole.Email)
                .with(4, CsvRole.Ignore)
        assertEquals(
            "First Name,Last Name,Phone 100 - Value,E-mail 100 - Value,Ignored\r\n" +
                "Ada,Lovelace,+44 20 7946 0958,ada@example.org,\"a, b\"\r\n",
            map.rewritten(),
        )
        assertEquals(CsvRole.Phone, map.columns[2].shown)
    }

    @Test fun twoColumnsOfOneRoleGetDifferentNumbers() {
        val text = CsvMapping("a,b\r\nx,y\r\n").with(0, CsvRole.Phone).with(1, CsvRole.Phone).rewritten()
        assertTrue(text, text.startsWith("Phone 100 - Value,Phone 101 - Value\r\n"))
    }

    @Test fun aQuotedHeaderAndABomAreRead() {
        val text = "${BYTE_ORDER_MARK}\"First, Name\",Phone\n\"Ada\",1\n"
        val map = CsvMapping(text)
        assertEquals(listOf("First, Name", "Phone"), map.columns.map { it.header })
        assertEquals(listOf("Ada", "1"), map.columns.map { it.sample })
        assertEquals(
            "\"First, Name\",Phone 100 - Value\r\n\"Ada\",1\n",
            map.with(1, CsvRole.Phone).rewritten(),
        )
    }

    @Test fun aHeaderOnlyFileHasNoSampleAndAnEmptyFileIsEmpty() {
        assertEquals(listOf(""), CsvMapping("a,b\r\n").columns.map { it.sample }.distinct())
        assertTrue(CsvMapping("\r\n,\r\n").isEmpty)
    }
}
