package dev.rovsti.phone.details

import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.phone.data.logEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Call details shows the name the log cached only when a contact now owns the number under another name (F29). */
class SavedAsTest {
    private fun now(
        name: String?,
        contactId: Long?,
    ) = logEntry(1, "+905321234567", CallLogKind.Incoming, name = name, contactId = contactId)

    @Test fun aRenamedContactShowsTheOldName() {
        assertEquals("Ayşe eski", savedAs("Ayşe eski", now("Ayşe Kaya", 7)))
    }

    @Test fun nothingWhenTheNamesAgreeOrNothingWasCached() {
        assertNull(savedAs("Ayşe", now("Ayşe", 7)))
        assertNull(savedAs(" Ayşe ", now("Ayşe", 7)))
        assertNull(savedAs(null, now("Ayşe", 7)))
        assertNull(savedAs("  ", now("Ayşe", 7)))
    }

    @Test fun nothingForANumberNoContactOwns() {
        // A deleted contact: the cached name is already the title.
        assertNull(savedAs("Silinen", now("Silinen", null)))
        assertNull(savedAs("Silinen", null))
    }
}
