@file:Suppress("ktlint:standard:max-line-length")

package dev.rovsti.contacts.editor

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E4.4 AC1/AC3/AC4: the editor's decisions on the real Rust core (validation sentences, dirty, save, undo). */
class ContactEditorViewModelTest {
    private var rig: EditorRig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun rig(build: () -> EditorRig): EditorRig = build().also { rig = it }

    @Test fun anEmptyNewContactIsNotDirtyAndCannotBeSaved() {
        val r = rig { EditorRig() }
        val state = r.ready()
        assertTrue(state.isNew)
        assertFalse(state.canSave)
        assertEquals("", state.draft.account)
        r.model.onAction(EditorAction.RequestClose)
        assertEquals(EditorEffect.Close, r.effect())
    }

    @Test fun noNameIsAnInlineSentenceOnlyAfterASaveAttempt() {
        val r = rig { EditorRig() }
        r.ready()
        r.model.onAction(EditorAction.Text(EditorText.Note, "x"))
        r.stateWhere { it.dirty }
        assertTrue(
            "not shown before a Save",
            r.model.state.value.issues
                .isEmpty(),
        )
        r.model.onAction(EditorAction.Save)
        assertEquals(EditorEffect.Invalid(EditorIssueKey.NAME), r.effect())
        val issues = r.stateWhere { it.issues.isNotEmpty() }.issues
        assertEquals("Add a name or an organisation.", issues[EditorIssueKey.NAME])
        assertTrue("nothing was saved", r.contacts().none { it.note == "x" })
    }

    @Test fun aBadEmailShowsItsSentenceUnderItsRowOnceLeft() {
        val r = rig { EditorRig() }
        r.ready()
        r.model.onAction(EditorAction.Text(EditorText.Given, "Ada"))
        r.model.onAction(EditorAction.AddRow(EditorList.Emails))
        val key = r.key(EditorList.Emails, 0)
        r.model.onAction(EditorAction.RowValue(EditorList.Emails, key, "ada-at-example"))
        r.stateWhere { it.dirty }
        Thread.sleep(300)
        assertTrue(
            "typing alone shows nothing",
            r.model.state.value.issues
                .isEmpty(),
        )
        r.model.onAction(EditorAction.Left(EditorIssueKey.row(EditorList.Emails, key)))
        val shown = r.stateWhere { it.issues.isNotEmpty() }.issues
        assertEquals("Email 1 needs an @ and a domain.", shown[EditorIssueKey.row(EditorList.Emails, key)])
    }

    @Test fun aBadDateIsNamedAndAGoodOneIsParsedUnderItsField() {
        val r = rig { EditorRig() }
        r.ready()
        r.model.onAction(EditorAction.Text(EditorText.Given, "Ada"))
        r.model.onAction(EditorAction.AddRow(EditorList.Events))
        val key = r.key(EditorList.Events, 0)
        val issueKey = EditorIssueKey.row(EditorList.Events, key)
        r.model.onAction(EditorAction.RowValue(EditorList.Events, key, "31-02"))
        r.model.onAction(EditorAction.Left(issueKey))
        assertEquals("Date 1 is not a real date.", r.stateWhere { it.issues.isNotEmpty() }.issues[issueKey])
        r.model.onAction(EditorAction.RowValue(EditorList.Events, key, "1990-10-03"))
        val state = r.stateWhere { it.hints[issueKey] != null && it.issues.isEmpty() }
        assertEquals("3 Oct 1990", state.hints[issueKey])
    }

    @Test fun anUnchangedContactIsNotDirtyAndAnEditAndItsRevertAre() {
        val r = rig { EditorRig(id = 1) }
        val state = r.ready()
        assertEquals("Ayşe", state.draft.given)
        assertFalse(state.canSave)
        r.model.onAction(EditorAction.Text(EditorText.Given, "Ayşegül"))
        assertTrue(r.stateWhere { it.draft.given == "Ayşegül" && it.dirty }.canSave)
        r.model.onAction(EditorAction.Text(EditorText.Given, "Ayşe"))
        assertFalse(
            "back to the stored values: nothing to save",
            r
                .stateWhere {
                    it.draft.given == "Ayşe" && !it.dirty
                }.canSave,
        )
        r.model.onAction(EditorAction.RequestClose)
        assertEquals(EditorEffect.Close, r.effect())
    }

    @Test fun closingWithChangesAsksFirstAndDiscardCloses() {
        val r = rig { EditorRig(id = 1) }
        r.ready()
        r.model.onAction(EditorAction.Text(EditorText.Org, "Other Co"))
        r.stateWhere { it.dirty }
        r.model.onAction(EditorAction.RequestClose)
        assertEquals(EditorEffect.ConfirmDiscard, r.effect())
        r.model.onAction(EditorAction.Discard)
        assertEquals(EditorEffect.Close, r.effect())
        assertEquals("the stored contact is untouched", "Acme Yazılım", r.contacts().first { it.id == 1L }.org)
    }

    @Test fun aNewContactIsSavedToTheChosenAccountAndRemembered() {
        val r = rig { EditorRig() }
        r.ready()
        r.model.onAction(EditorAction.SetAccount(GOOGLE.key))
        r.stateWhere { it.draft.account == GOOGLE.key }
        r.model.onAction(EditorAction.ToggleGroup(11))
        r.model.onAction(EditorAction.Text(EditorText.Given, "Ada"))
        r.stateWhere { it.draft.account == GOOGLE.key && it.dirty && it.groups.map { g -> g.id } == listOf(11L) }
        r.model.onAction(EditorAction.Save)
        val first = r.effect()
        assertTrue("$first ${r.model.state.value.issues}", first is EditorEffect.Saved)
        val saved = first as EditorEffect.Saved
        assertEquals(EditorEffect.Close, r.effect())
        assertFalse("a new contact has nothing to undo", saved.undoable)
        val stored = r.contacts().first { it.id == saved.id }
        assertEquals(GOOGLE.key, stored.account)
        assertEquals("Ada", stored.given)
        assertTrue("AD-CT2: a contact without a number is allowed", stored.numbers.isEmpty())
        assertEquals(listOf(11L), stored.groups)
        assertEquals("the next new contact starts in the same account", GOOGLE.key, r.store.prefs.value.saveTo)
        assertEquals(
            saved.id,
            r.store.prefs.value.added
                .first()
                .id,
        )
    }

    @Test fun anAccountChangeDropsTheGroupsOfTheOldAccount() {
        val r = rig { EditorRig() }
        r.ready()
        r.model.onAction(EditorAction.ToggleGroup(10))
        r.stateWhere { 10L in it.draft.groups }
        r.model.onAction(EditorAction.SetAccount(GOOGLE.key))
        val state = r.stateWhere { it.draft.account == GOOGLE.key && it.draft.groups.isEmpty() }
        assertTrue(state.draft.groups.isEmpty())
        assertEquals(listOf(11L), state.groups.map { it.id })
    }

    @Test fun theSavedAccountOfPreferencesIsTheDefaultOfANewContact() {
        val r = rig { EditorRig(saveTo = GOOGLE.key) }
        assertEquals(GOOGLE.key, r.ready().draft.account)
        assertEquals(
            "ayse@example.org",
            r.model.state.value.accountLabel
                .substringAfter(", "),
        )
    }

    @Test fun savingAnEditNamesTheChangeAndUndoPutsTheOldValuesBack() {
        val r = rig { EditorRig(id = 1) }
        r.ready()
        r.model.onAction(EditorAction.Text(EditorText.Org, "New Co"))
        r.stateWhere { it.draft.org == "New Co" && it.dirty }
        r.model.onAction(EditorAction.Save)
        val saved = r.effect() as EditorEffect.Saved
        assertEquals("Saved · Company changed", saved.notice)
        assertTrue(saved.undoable)
        assertEquals("New Co", r.contacts().first { it.id == 1L }.org)
        assertTrue(runBlocking { r.model.undo() })
        assertEquals("Acme Yazılım", r.contacts().first { it.id == 1L }.org)
        assertFalse("a second Undo has nothing to do", runBlocking { r.model.undo() })
    }

    @Test fun rowsAreAddedRemovedLabelledAndTheDefaultMoves() {
        val r = rig { EditorRig(id = 5) }
        r.ready()
        val first = r.key(EditorList.Numbers, 0)
        val second = r.key(EditorList.Numbers, 1)
        r.model.onAction(EditorAction.ToggleDefault(EditorList.Numbers, second))
        assertEquals(
            listOf(false, true),
            r
                .stateWhere { s ->
                    s.draft.numbers.any { it.primary }
                }.draft.numbers
                .map { it.primary },
        )
        r.model.onAction(EditorAction.ToggleDefault(EditorList.Numbers, second))
        assertTrue(
            r
                .stateWhere { s -> s.draft.numbers.none { it.primary } }
                .draft.numbers
                .none { it.primary },
        )
        r.model.onAction(
            EditorAction.Label(
                EditorList.Numbers,
                first,
                RowLabel.Field(dev.rovsti.core.model.contacts.FieldKind.Home),
            ),
        )
        r.model.onAction(EditorAction.CustomLabel(EditorList.Numbers, second, "Boat"))
        val state = r.stateWhere { it.draft.numbers[1].label == "Boat" }
        assertEquals(dev.rovsti.core.model.contacts.FieldKind.Home, state.draft.numbers[0].kind)
        assertEquals(dev.rovsti.core.model.contacts.FieldKind.Custom, state.draft.numbers[1].kind)
        r.model.onAction(EditorAction.RemoveRow(EditorList.Numbers, first))
        val after = r.stateWhere { it.draft.numbers.size == 1 }
        assertEquals(
            "+1 415 555 0199",
            after.draft.numbers
                .single()
                .value,
        )
        assertEquals(listOf(second), after.keys.numbers)
    }

    @Test fun theHalfTypedFormComesBackFromTheSavedState() {
        val handle = SavedStateHandle()
        val first = rig { EditorRig(saved = handle) }
        first.ready()
        first.model.onAction(EditorAction.Text(EditorText.Given, "Half"))
        first.stateWhere { it.draft.given == "Half" }
        first.stop()
        val second = rig { EditorRig(saved = handle) }
        val state = second.stateWhere { it.phase == EditorPhase.Ready }
        assertEquals("Half", state.draft.given)
        assertTrue(second.stateWhere { it.dirty }.canSave)
        assertNull(handle.get<String>("missing"))
    }

    @Test fun aGoneContactClosesThePage() {
        val r = rig { EditorRig(id = 99) }
        assertEquals(EditorEffect.Close, r.effect())
        assertEquals(EditorPhase.Gone, r.stateWhere { it.phase == EditorPhase.Gone }.phase)
    }
}
