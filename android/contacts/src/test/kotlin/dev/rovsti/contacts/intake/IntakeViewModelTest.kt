package dev.rovsti.contacts.intake

import dev.rovsti.contacts.card.SampleCards
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** E4.10 on the REAL Rust core: what the import page reads, plans, writes and undoes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntakeViewModelTest {
    private var rig: IntakeRig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun open(
        source: (IntakeRig) -> IntakeSource = IntakeRig.file(),
        file: String? = TWO_NEW,
        clip: String? = null,
    ): IntakeRig = IntakeRig(source, file = file, clip = clip).also { rig = it }

    @Test fun aTwoCardFilePreviewsBothCardsWithTheirCounts() {
        val r = open()
        val state = r.preview()
        assertEquals(listOf("Ada Lovelace", "Grace Hopper"), state.rows.map { it.name })
        assertEquals("2 new contacts", state.countLine)
        assertEquals("Add 2 Contacts", state.addLabel)
        assertEquals("one line under the name: the number", "+44 20 7946 0958", state.rows[0].secondary)
        assertEquals("a card with a company and a number lists the number", "+1 415 555 0100", state.rows[1].secondary)
        assertFalse("two cards cannot be added to one contact", state.canAddToExisting)
        assertTrue(state.canAdd)
        assertEquals("the file is read as a document, with the contacts cap", 1, r.files.reads.size)
    }

    @Test fun aCardTheBookAlreadyHasIsLeftOut() {
        val r = open(file = TWO_AND_A_COPY)
        val state = r.preview()
        assertEquals(listOf("Ada Lovelace", "Grace Hopper"), state.rows.map { it.name })
        assertEquals("2 new contacts, 1 already in your contacts", state.countLine)
        r.model.onAction(IntakeAction.Add)
        val undo = r.effect() as IntakeEffect.Undo
        assertEquals("Added 2 contacts", undo.message)
        assertEquals(SampleCards.all.size + 2, r.contacts().size)
        assertEquals("Burak is not added twice", 1, r.contacts().count { it.given == "Burak" })
    }

    @Test fun addWritesTheCardsAndUndoMovesThemToTheTrash() {
        val r = open()
        r.preview()
        r.model.onAction(IntakeAction.Add)
        val undo = r.effect() as IntakeEffect.Undo
        assertEquals(IntakeEffect.Close, r.effect())
        assertEquals(IntakePhase.Done, r.model.state.value.phase)
        val added = r.contacts().filter { it.given in setOf("Ada", "Grace") }
        assertEquals(2, added.size)
        assertEquals(
            "the number is kept",
            "+44 20 7946 0958",
            added
                .first { it.given == "Ada" }
                .numbers
                .single()
                .value,
        )
        val toast = runBlocking { undo.undo() }
        assertEquals("2 contacts moved to the Trash", toast)
        assertEquals(SampleCards.all.size, r.contacts().size)
        val inTrash = runBlocking { r.trash.list() }
        assertEquals(2, inTrash?.entries?.size)
    }

    @Test fun aFileOfCardsTheBookAlreadyHasOnlyToasts() {
        val r = open(file = vcard("Burak Demir", "Demir", "Burak", "0532 123 45 67"))
        assertEquals(IntakeEffect.Toast("Already in your contacts"), r.effect())
        assertEquals(IntakeEffect.Close, r.effect())
    }

    @Test fun aFileWithoutContactsToastsTheCoresWords() {
        val r = open(file = "just some words in a file")
        assertEquals(IntakeEffect.Toast("This file has no contacts"), r.effect())
        assertEquals(IntakeEffect.Close, r.effect())
    }

    @Test fun cancelClosesOnceAndWritesNothing() {
        val r = open()
        r.preview()
        r.model.onAction(IntakeAction.Cancel)
        r.model.onAction(IntakeAction.Cancel)
        assertEquals(IntakeEffect.Close, r.effect())
        assertEquals(SampleCards.all.size, r.contacts().size)
        r.model.onAction(IntakeAction.Add)
        assertEquals("a closed page adds nothing", SampleCards.all.size, r.contacts().size)
    }

    @Test fun tappingARowOpensTheReadOnlyCardAndBackReturns() {
        val r = open()
        r.preview()
        r.model.onAction(IntakeAction.OpenCard(1))
        val open = r.stateWhere { it.preview != null }.preview!!
        assertEquals("Grace Hopper", open.name)
        assertEquals("US Navy", open.subtitle)
        assertEquals(listOf("Phone"), open.sections.map { it.title })
        assertEquals(
            "+1 415 555 0100",
            open.sections
                .single()
                .lines
                .single()
                .value,
        )
        r.model.onAction(IntakeAction.CloseCard)
        assertEquals(null, r.stateWhere { it.preview == null }.preview)
    }

    @Test fun theCardsPictureComesFromTheVcard() {
        val r = open(file = ONE_WITH_PHOTO)
        val row = r.preview().rows.single()
        assertEquals("Katherine Johnson", row.name)
        assertNotNull("the PHOTO of the card is decoded for the row", row.photo)
    }

    @Test fun plainTextWithANumberAndANameIsADraftToAdd() {
        val r = open(IntakeRig.paste, file = null, clip = "Ada Lovelace\n+44 20 7946 0958")
        val state = r.preview()
        assertEquals("Ada Lovelace", state.rows.single().name)
        assertTrue("one card can go into a contact that exists", state.canAddToExisting)
        assertEquals(0, r.files.reads.size)
    }

    @Test fun aBareNumberOpensTheEditorWithIt() {
        val r = open(IntakeRig.paste, file = null, clip = "0555 111 22 33")
        val effect = r.effect() as IntakeEffect.OpenEditor
        assertEquals("0555 111 22 33".filter { it.isDigit() }, effect.seed.number.filter { it.isDigit() })
        assertEquals(IntakeEffect.Close, r.effect())
    }

    @Test fun anEmptyClipboardToastsNothingToPaste() {
        val r = open(IntakeRig.paste, file = null, clip = null)
        assertEquals(IntakeEffect.Toast("Nothing to paste"), r.effect())
        assertEquals(IntakeEffect.Close, r.effect())
        assertEquals("nothing is read from a file", 0, r.files.reads.size)
    }

    @Test fun textThatIsNoContactToastsNothingToPaste() {
        val r = open(IntakeRig.paste, file = null, clip = "see you tomorrow at the station")
        assertEquals(IntakeEffect.Toast("Nothing to paste"), r.effect())
    }

    @Test fun addToExistingMergesTheNumberAndUndoPutsTheOldValuesBack() {
        val r = open(file = ONE_NEW)
        val state = r.preview()
        assertTrue(state.canAddToExisting)
        r.model.onAction(IntakeAction.AddToExisting)
        val chooser = r.effect() as IntakeEffect.ChooseExisting
        assertEquals(SampleCards.all.size, chooser.people.size)
        val before = r.contacts().first { it.id == 2L }
        r.model.onAction(IntakeAction.PickExisting(2L))
        val undo = r.effect() as IntakeEffect.Undo
        assertEquals(IntakeEffect.Close, r.effect())
        val after = r.contacts().first { it.id == 2L }
        assertEquals(before.numbers.size + 1, after.numbers.size)
        assertTrue(after.numbers.any { it.value == "+44 20 7946 0958" })
        assertEquals("nothing was added as a new contact", SampleCards.all.size, r.contacts().size)
        assertEquals("Put back", runBlocking { undo.undo() })
        assertEquals(
            before.numbers.map { it.value },
            r
                .contacts()
                .first { it.id == 2L }
                .numbers
                .map { it.value },
        )
    }
}
