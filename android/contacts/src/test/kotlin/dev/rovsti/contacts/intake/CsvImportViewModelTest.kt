package dev.rovsti.contacts.intake

import dev.rovsti.contacts.card.SampleCards
import dev.rovsti.contacts.data.Book
import dev.rovsti.core.model.contacts.TextRequest
import dev.rovsti.core.ops.ContactsOps
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val GERMAN =
    "Vorname,Nachname,Handy,Mail\r\n" +
        "Ada,Lovelace,+44 20 7946 0958,ada@example.org\r\n" +
        "Grace,Hopper,+1 415 555 0100,grace@example.org\r\n"

/** E4.10 C39 on the REAL Rust core: the mapping sheet, the re-reads it causes, the preview after it, and the export. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CsvImportViewModelTest {
    private var rig: IntakeRig? = null
    private val reads = mutableListOf<String>()

    @After fun stop() {
        rig?.stop()
    }

    private fun open(text: String = GERMAN): IntakeRig =
        IntakeRig(
            IntakeRig.csv(),
            csv = text,
            wrap = { real ->
                object : ContactsOps by real {
                    override suspend fun csvImport(req: TextRequest) = real.csvImport(req).also { reads += req.text }
                }
            },
        ).also { rig = it }

    private fun IntakeRig.mapping(predicate: (CsvMappingUi) -> Boolean = { true }) =
        stateWhere { it.phase == IntakePhase.Mapping && it.mapping?.let(predicate) == true }.mapping!!

    @Test fun aFileTheReaderCannotUseOpensTheSheetWithNothingToContinueWith() {
        val r = open()
        val sheet = r.mapping()
        assertEquals(listOf("Vorname", "Nachname", "Handy", "Mail"), sheet.columns.map { it.header })
        assertEquals("Ada", sheet.columns[0].sample)
        assertEquals("No contacts found with these columns", sheet.countLine)
        assertFalse(sheet.canContinue)
        assertEquals("the file is read once, as it is", listOf(GERMAN), reads)
        r.model.onAction(IntakeAction.ConfirmMapping)
        assertEquals("Continue does nothing without contacts", IntakePhase.Mapping, r.model.state.value.phase)
    }

    @Test fun eachMappingChangeReadsTheFileAgainAndTheCountFollows() {
        val r = open()
        r.mapping()
        r.model.onAction(IntakeAction.SetRole(0, CsvRole.FirstName))
        val first = r.mapping { it.columns[0].chosen == CsvRole.FirstName && it.canContinue }
        assertEquals("2 contacts found", first.countLine)
        assertEquals("the first read, and one more for the change", 2, reads.size)
        assertTrue(reads.last().startsWith("First Name,Nachname,Handy,Mail\r\n"))
        r.model.onAction(IntakeAction.SetRole(1, CsvRole.LastName))
        r.model.onAction(IntakeAction.SetRole(2, CsvRole.Phone))
        r.model.onAction(IntakeAction.SetRole(3, CsvRole.Email))
        val done = r.mapping { it.columns.all { c -> c.chosen != null } }
        assertEquals(CsvRole.Email, done.columns[3].shown)
        assertEquals(
            "First Name,Last Name,Phone 100 - Value,E-mail 100 - Value\r\n" +
                "Ada,Lovelace,+44 20 7946 0958,ada@example.org\r\n" +
                "Grace,Hopper,+1 415 555 0100,grace@example.org\r\n",
            reads.last(),
        )
    }

    @Test fun continueOpensThePreviewOfTheMappedCardsAndAddWritesThem() {
        val r = open()
        r.mapping()
        r.model.onAction(IntakeAction.SetRole(0, CsvRole.FirstName))
        r.model.onAction(IntakeAction.SetRole(1, CsvRole.LastName))
        r.model.onAction(IntakeAction.SetRole(2, CsvRole.Phone))
        r.mapping { it.columns[2].chosen == CsvRole.Phone && it.canContinue }
        r.model.onAction(IntakeAction.ConfirmMapping)
        val preview = r.preview()
        assertEquals(listOf("Ada Lovelace", "Grace Hopper"), preview.rows.map { it.name })
        assertEquals("2 new contacts", preview.countLine)
        assertEquals(null, preview.mapping)
        r.model.onAction(IntakeAction.Add)
        assertTrue(r.effect() is IntakeEffect.Undo)
        assertEquals(SampleCards.all.size + 2, r.contacts().size)
    }

    @Test fun ignoringAColumnTakesItsValuesOut() {
        val r = open("First Name,Last Name,Phone 1 - Value\r\nAda,Lovelace,+44 20 7946 0958\r\n")
        val sheet = r.mapping()
        assertEquals("1 contact found", sheet.countLine)
        assertEquals(listOf(CsvRole.FirstName, CsvRole.LastName, CsvRole.Phone), sheet.columns.map { it.detected })
        r.model.onAction(IntakeAction.SetRole(0, CsvRole.Ignore))
        r.model.onAction(IntakeAction.SetRole(1, CsvRole.Ignore))
        val none = r.mapping { it.columns[1].chosen == CsvRole.Ignore && !it.canContinue }
        assertEquals("a number alone is no card", "No contacts found with these columns", none.countLine)
    }

    @Test fun cancelLeavesTheMappingAndWritesNothing() {
        val r = open()
        r.mapping()
        r.model.onAction(IntakeAction.Cancel)
        assertEquals(IntakeEffect.Close, r.effect())
        assertEquals(SampleCards.all.size, r.contacts().size)
    }

    @Test fun aFileWithoutAnyHeaderToastsThatItCannotBeRead() {
        val r = open("\r\n,\r\n")
        assertEquals(IntakeEffect.Toast("Can't read this file"), r.effect())
        assertEquals(IntakeEffect.Close, r.effect())
    }

    @Test fun theExportIsTheCoresCsvOfEveryContactAndReadsBackAsThem() {
        val r = open()
        val ready = runBlocking { r.book.book.first { it is Book.Ready } } as Book.Ready
        val export = runBlocking { CsvExport(r.ops, r.book, clock = { SampleCards.NOW }).build() }!!
        assertEquals(ready.drafts.size, export.count)
        assertTrue(export.text.startsWith("${BYTE_ORDER_MARK}First Name,Middle Name,Last Name,"))
        assertTrue(
            export.fileName,
            Regex("rovsti-contacts-${ready.drafts.size}-\\d{4}-\\d{2}-\\d{2}\\.csv").matches(export.fileName),
        )
        assertTrue("one CRLF row per contact and the header", export.text.split("\r\n").size >= ready.drafts.size + 1)
        val back = runBlocking { (r.ops.csvImport(TextRequest(export.text)) as dev.rovsti.core.Outcome.Ok).value }
        assertEquals(ready.drafts.map { it.given to it.family }, back.contacts.map { it.given to it.family })
    }
}
