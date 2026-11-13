package dev.rovsti.platform.identity

import dev.rovsti.core.model.search.WantedNumber
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.platform.contacts.ContactsQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerIdsTest {
    /** A book that knows numbers by exact text, as the core's answer arrives. */
    private class Book(
        var known: Map<String, WireContact>,
        override var ready: Boolean = true,
    ) : ContactBook {
        val asked = ArrayList<List<WantedNumber>>()
        override val revision = MutableStateFlow(1L)

        override suspend fun owners(
            numbers: List<WantedNumber>,
            homeCc: String?,
        ): List<WireContact?>? {
            if (!ready) return null
            asked += numbers
            return numbers.map { known[it.e164 ?: it.raw] }
        }
    }

    private val ayse = WireContact(7, "Ayşe Yılmaz", emptyList(), starred = true, lookupKey = "k7", photoUri = "p7")

    @Test fun aContactNamesItsNumberWithEverythingX1Needs() =
        runTest {
            val book = Book(mapOf("+905321234567" to ayse))
            val ids =
                CallerIds(
                    listOf(
                        DeviceContactsSource(book, e164 = {
                            if (it ==
                                "05321234567"
                            ) {
                                "+905321234567"
                            } else {
                                null
                            }
                        }),
                    ),
                )
            val who = ids.resolve("05321234567")!!
            assertEquals(7L, who.contactId)
            assertEquals("Ayşe Yılmaz", who.name)
            assertEquals("k7", who.lookupKey)
            assertEquals("p7", who.photoUri)
            assertTrue(who.isContact)
            assertNull(ids.resolve("05329999999"))
        }

    @Test fun answersAreCachedUntilTheContactsChange() =
        runTest {
            val book = Book(mapOf("1" to ayse))
            val ids = CallerIds(listOf(DeviceContactsSource(book, e164 = { null })))
            ids.resolve(listOf("1", "2"))
            ids.resolve(listOf("1", "2"))
            assertEquals(1, book.asked.size)
            assertEquals("Ayşe Yılmaz", ids.peek("1")?.name)
            // A rename: the next answer is the new name.
            book.known = mapOf("1" to ayse.copy(displayName = "Ayşe Kaya"))
            book.revision.value = 2
            assertNull(ids.peek("1"))
            assertEquals("Ayşe Kaya", ids.resolve("1")?.name)
            // A deleted contact: the number is nobody's.
            book.known = emptyMap()
            book.revision.value = 3
            assertNull(ids.resolve("1"))
        }

    @Test fun nothingIsRememberedWhileTheBookIsNotReady() =
        runTest {
            val book = Book(mapOf("1" to ayse), ready = false)
            val ids = CallerIds(listOf(DeviceContactsSource(book, e164 = { null })))
            assertFalse(ids.ready)
            assertNull(ids.resolve("1"))
            book.ready = true
            assertEquals(7L, ids.resolve("1")?.contactId)
        }

    @Test fun laterSourcesAddWhatTheContactLacks() =
        runTest {
            val wa =
                object : IdentitySource {
                    override val revision: StateFlow<Long> = MutableStateFlow(1L)
                    override val ready = true

                    override suspend fun lookup(numbers: List<String>) =
                        numbers.associateWith { CallerIdentity(waPushName = "Ayse WA", waPhotoUri = "wa.jpg") }
                }
            val ids = CallerIds(listOf(DeviceContactsSource(Book(mapOf("1" to ayse)), e164 = { null }), wa))
            val contact = ids.resolve("1")!!
            assertEquals("Ayşe Yılmaz", contact.title)
            assertEquals("wa.jpg", contact.waPhotoUri)
            val stranger = ids.resolve("2")!!
            assertFalse(stranger.isContact)
            assertEquals("Ayse WA", stranger.title)
        }

    @Test fun phoneLookupPicksTheSameContactAsTheIndex() {
        fun row(
            id: Long,
            starred: Boolean = false,
            updated: Long = 0,
        ) = ContactsQuery.LookupRow(id, "C$id", null, starred, updated, null, null)
        assertEquals(3L, ContactsQuery.best(listOf(row(7), row(3)))?.id)
        assertEquals(7L, ContactsQuery.best(listOf(row(7, updated = 2), row(3, updated = 1)))?.id)
        assertEquals(3L, ContactsQuery.best(listOf(row(7, updated = 2), row(3, starred = true)))?.id)
        assertNull(ContactsQuery.best(emptyList()))
    }
}
