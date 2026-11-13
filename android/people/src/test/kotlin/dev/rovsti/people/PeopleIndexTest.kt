package dev.rovsti.people

import dev.rovsti.core.model.people.PersonRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleIndexTest {
    private val ayse =
        PersonRow(
            jid = "905321234567@s.whatsapp.net",
            numbers = listOf("+905321234567"),
            name = "Ayse",
            onWhatsapp = true,
            pictureId = "55",
        )
    private val cem = PersonRow(jid = "4477@lid", lid = "4477", name = "Cem", onWhatsapp = true)

    private fun uri(row: PersonRow) = "content://pics/${row.jid}/${row.pictureId}"

    @Test fun aChangeBumpsTheVersionAndAnIdenticalReloadDoesNot() {
        val index = PeopleIndex.forRegion("TR")
        assertEquals(0L, index.version.value)
        assertTrue(index.replace(listOf(ayse), ::uri))
        assertEquals(1L, index.version.value)
        assertFalse("same rows", index.replace(listOf(ayse), ::uri))
        assertEquals(1L, index.version.value)
        assertTrue(index.replace(listOf(ayse.copy(pictureId = "56")), ::uri))
        assertEquals(2L, index.version.value)
        assertEquals("content://pics/905321234567@s.whatsapp.net/56", index.byNumber("+905321234567")?.pictureUri)
    }

    @Test fun nationalAndInternationalFormsFindTheSamePerson() {
        val index = PeopleIndex.forRegion("TR")
        index.replace(listOf(ayse, cem), ::uri)
        for (raw in listOf("+905321234567", "05321234567", "5321234567", "0090 532 123 45 67", "+90 (532) 123-45-67")) {
            assertEquals(raw, "Ayse", index.byNumber(raw)?.row?.name)
        }
        assertNull(index.byNumber("+905329999999"))
        assertNull("a short code is nobody", index.byNumber("112"))
    }

    @Test fun anotherRegionReadsTheNationalFormDifferently() {
        val index = PeopleIndex.forRegion("DE")
        index.replace(listOf(ayse), ::uri)
        assertNull(index.byNumber("05321234567"))
        assertEquals("Ayse", index.byNumber("+90 532 123 45 67")?.row?.name)
    }

    @Test fun jidLidAndBareLidAreOnePerson() {
        val index = PeopleIndex.forRegion("TR")
        index.replace(listOf(ayse, cem), ::uri)
        assertEquals("Ayse", index.byJid("905321234567@s.whatsapp.net")?.row?.name)
        assertEquals("Ayse", index.byJid("905321234567:12@s.whatsapp.net")?.row?.name)
        assertEquals("Cem", index.byJid("4477@lid")?.row?.name)
        assertEquals("Cem", index.byJid("4477")?.row?.name)
        assertNull(index.byJid("999@lid"))
    }

    @Test fun aPersonWithoutPictureHasNoUri() {
        val index = PeopleIndex.forRegion("TR")
        index.replace(listOf(cem), ::uri)
        assertNull(index.byJid("4477@lid")?.pictureUri)
    }

    @Test fun attachLoadsOnceAndAgainOnEveryChange() =
        runTest {
            val changes = MutableSharedFlow<Unit>()
            var loads = 0
            var rows = listOf(ayse)
            val source =
                object : PeopleSource {
                    override val kind = PeopleSource.Kind.Provider
                    override val changes = changes

                    override suspend fun load(): List<PersonRow> {
                        loads++
                        return rows
                    }

                    override fun pictureUri(row: PersonRow) = uri(row)
                }
            val index = PeopleIndex.forRegion("TR")
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val job = index.attach(scope, source)
            scope.advanceUntilIdle()
            assertEquals(1, loads)
            assertEquals(1L, index.version.value)
            rows = listOf(ayse, cem)
            changes.emit(Unit)
            scope.advanceUntilIdle()
            assertEquals(2, loads)
            assertEquals(2, index.size)
            assertEquals(2L, index.version.value)
            job.cancel()
        }
}
