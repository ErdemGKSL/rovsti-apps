package dev.rovsti.people

import android.net.Uri
import dev.rovsti.core.model.people.PersonRow
import dev.rovsti.people.PeopleContract.addPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeopleContractTest {
    @Test fun theNamesAreTheOnesTheManifestsUse() {
        assertEquals("dev.rovsti.contacts.people", PeopleContract.AUTHORITY)
        assertEquals("dev.rovsti.permission.READ_PEOPLE", PeopleContract.PERMISSION)
    }

    @Test fun aRowSurvivesTheCursor() {
        val rows =
            listOf(
                PersonRow(
                    "1@s.whatsapp.net",
                    listOf("+1", "+2"),
                    "9",
                    "Ayse",
                    true,
                    pictureId = "55",
                    pictureStale = true,
                ),
                PersonRow("2@lid", lid = "2"),
            )
        val cursor = PeopleContract.newCursor()
        rows.forEach { cursor.addPerson(it) }
        val back = buildList { while (cursor.moveToNext()) add(PeopleContract.rowOf(cursor)) }
        assertEquals(rows, back)
    }

    @Test fun pictureUrisRoundTripAndForeignUrisAreRefused() {
        val jid = "905321234567@s.whatsapp.net"
        val uri = PeopleContract.pictureUri(jid, "55")
        assertEquals("content", uri.scheme)
        assertEquals(jid to "55", PeopleContract.parsePictureUri(Uri.parse(uri.toString())))
        assertNull(PeopleContract.parsePictureUri(Uri.parse("content://other/picture/a/b")))
        assertNull(PeopleContract.parsePictureUri(Uri.parse("content://dev.rovsti.contacts.people/people")))
    }

    @Test fun theIdIsPartOfTheUriSoACacheNeverShowsAReplacedPicture() {
        assertEquals(false, PeopleContract.pictureUri("a@lid", "1") == PeopleContract.pictureUri("a@lid", "2"))
    }
}
