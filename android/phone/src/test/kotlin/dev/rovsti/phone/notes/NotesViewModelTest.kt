package dev.rovsti.phone.notes

import dev.rovsti.core.model.phone.CallNote
import dev.rovsti.core.model.phone.DateOrder
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.Notes
import dev.rovsti.core.model.phone.TimeFormat
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.T0
import dev.rovsti.phone.data.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.11 decisions on the REAL Rust core with the stats call log and three notes (Tue 14 Nov 2023, UTC). */
class NotesViewModelTest {
    private val host by lazy { HostRust("rovsti-notes-vm") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun mainDispatcher() =
        dev.rovsti.phone.UnconfinedMain
            .install()

    @After fun stop() = scope.cancel()

    private suspend fun rig() = NotesRig(host, scope).also { it.ready() }

    @Test fun anUnnamedRowIsTitledByTheNumberAsPeopleReadIt() =
        runBlocking {
            val notes = Notes(schema = 1, notes = listOf(CallNote(TURK, T0 - 60 * 60_000L, "Parcel at the door", T0)))
            val ui = NotesRig(host, scope, notes = notes).also { it.ready() }.model.loaded()
            assertEquals("F38: no raw +905557771122", listOf("+90 555 777 11 22"), ui.rows.map { it.title })
            assertNull(ui.rows[0].name)
            assertEquals("the number stays raw for the key and the call lookup", TURK, ui.rows[0].number)
        }

    @Test fun theRowsAreNewestFirstWithNamesAndTheDateInThePhonesClock() =
        runBlocking {
            val ui = rig().model.loaded()
            assertEquals(3, ui.total)
            assertEquals(listOf("Ayse Yilmaz", "Mert Kaya", STRANGER), ui.rows.map { it.title })
            assertEquals("a saved number carries its name", "Ayse Yilmaz", ui.rows[0].name)
            assertNull("a stranger has none", ui.rows[2].name)
            assertTrue(ui.rows[0].subtitle, ui.rows[0].subtitle.contains("21:13"))
            assertTrue("the first words follow the date", ui.rows[0].subtitle.endsWith("Fatura icin geri ara"))
            assertTrue("a long note is cut to its first line", ui.rows[1].subtitle.endsWith("Bring the invoice…"))
            assertEquals(
                "number and start time make the key",
                NotesViewModel.rowKey(AYSE, T0 - 3_600_000L),
                ui.rows[0].key,
            )
        }

    @Test fun theDateFollowsTheDevicesTwelveHourClock() =
        runBlocking {
            val rig = rig()
            rig.locale = LocaleTime(0, TimeFormat(hour12 = true, dateOrder = DateOrder.DayMonthYear))
            // A new query rebuilds the rows with the new clock.
            rig.model.onAction(NotesAction.SetQuery("ayse "))
            val ui = rig.model.loaded { it.query == "ayse " && it.rows.isNotEmpty() }
            assertTrue(
                ui.rows[0].subtitle,
                ui.rows[0].subtitle.contains("9:13") && ui.rows[0].subtitle.contains("pm", true),
            )
        }

    @Test fun theSearchIsTheCoresByTextNameAndDigits() =
        runBlocking {
            val model = rig().model
            model.loaded()
            model.onAction(NotesAction.SetQuery("INVOICE"))
            assertEquals(listOf(MERT), model.loaded { it.query == "INVOICE" }.rows.map { it.number })
            model.onAction(NotesAction.SetQuery("ayse"))
            assertEquals(listOf(AYSE), model.loaded { it.query == "ayse" }.rows.map { it.number })
            model.onAction(NotesAction.SetQuery("30 123"))
            assertEquals(listOf(STRANGER), model.loaded { it.query == "30 123" }.rows.map { it.number })
            model.onAction(NotesAction.SetQuery("zzz"))
            val none = model.loaded { it.query == "zzz" }
            assertTrue(none.empty)
            assertTrue(none.searching)
            assertEquals("the total still counts every note", 3, none.total)
            model.onAction(NotesAction.SetQuery(""))
            assertEquals(3, model.loaded { it.query.isEmpty() && it.rows.size == 3 }.rows.size)
        }

    @Test fun deleteRemovesTheNoteAndUndoPutsItBack() =
        runBlocking {
            val rig = rig()
            val model = rig.model
            val before = model.loaded()
            val key = before.rows[1].key
            model.onAction(NotesAction.Delete(key))
            val undoable = model.nextEffect<NotesEffect.Undoable>()
            assertEquals(MERT, undoable.note.number)
            assertEquals("Bring the invoice\nand the keys", undoable.note.text)
            val after = model.loaded { it.total == 2 }
            assertEquals(listOf("Ayse Yilmaz", STRANGER), after.rows.map { it.title })
            assertEquals(2, rig.base.store.notes.value.notes.size)

            model.onAction(NotesAction.Undo(undoable.note))
            val back = model.loaded { it.total == 3 }
            assertEquals("the note is back in its place", before.rows.map { it.key }, back.rows.map { it.key })
            eventually("the file holds it again") { rig.base.store.notes.value.notes.size == 3 }
        }

    @Test fun shareAllAndExportCarryTheNamesAndTheCoresCsvHeader() =
        runBlocking {
            val model = rig().model
            model.loaded()
            model.onAction(NotesAction.ShareAll)
            val text = model.nextEffect<NotesEffect.ShareText>().text
            assertTrue(text, text.startsWith("Ayse Yilmaz, "))
            assertTrue(text.contains("Mert Kaya, "))
            assertTrue("a stranger is written as the number", text.contains("$STRANGER, "))
            assertTrue(text.contains("Fatura icin geri ara"))

            model.onAction(NotesAction.ExportCsv)
            val csv = model.nextEffect<NotesEffect.SaveCsv>()
            assertEquals("rovsti-call-notes-2023-11-14.csv", csv.fileName)
            assertTrue(csv.csv, csv.csv.startsWith("date,time,name,number,note\r\n"))
            assertEquals("header and three notes, CRLF rows", 4, Regex("\r\n").findAll(csv.csv).count())
            assertTrue(csv.csv.contains("Ayse Yilmaz,$AYSE,Fatura icin geri ara"))
        }

    @Test fun noNotesMeansNothingToShare() =
        runBlocking {
            val rig = rig()
            rig.base.store.updateNotes { it.copy(notes = emptyList()) }
            val ui = rig.model.loaded { it.ready && it.total == 0 }
            assertTrue(ui.empty)
            assertEquals(false, ui.searching)
            rig.model.onAction(NotesAction.ShareAll)
            rig.model.onAction(NotesAction.ExportCsv)
            assertNull("no effect for an empty file", withTimeoutOrNull(400) { rig.model.effect.first() })
        }

    @Test fun aRowOpensTheLoggedCallOfItsNote() =
        runBlocking {
            val model = rig().model
            val ui = model.loaded()
            model.onAction(NotesAction.Open(ui.rows[0].key))
            val open = model.nextEffect<NotesEffect.OpenCall>()
            assertEquals(AYSE, open.target.line.number)
            assertEquals(T0 - 3_600_000L, open.target.line.dateMs)
            assertEquals("Ayse Yilmaz", open.target.who)
        }

    @Test fun aNoteWhoseCallLeftTheLogSaysSoInsteadOfOpening() =
        runBlocking {
            val rig = NotesRig(host, scope, log = emptyList()).also { it.ready() }
            val ui = rig.model.loaded()
            rig.model.onAction(NotesAction.Open(ui.rows[0].key))
            assertEquals(NotesNotice.CallGone, rig.model.nextEffect<NotesEffect.Say>().notice)
        }

    @Test fun theKeyIsNullSafeForARowThatIsGone() =
        runBlocking {
            val model = rig().model
            model.loaded()
            model.onAction(NotesAction.Delete("nobody@1"))
            kotlinx.coroutines.delay(200)
            assertNotNull(model.state.value)
            assertEquals(3, model.state.value.total)
        }
}
