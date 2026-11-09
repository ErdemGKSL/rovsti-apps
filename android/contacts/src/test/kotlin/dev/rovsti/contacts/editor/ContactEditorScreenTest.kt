@file:Suppress("ktlint:standard:max-line-length")

package dev.rovsti.contacts.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.TextRange
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.4 on the real shell: the editor sheet over its real model (manual clock). */
@Config(sdk = [35])
class ContactEditorScreenTest : ShotTest() {
    private var session: EditorSession? = null

    @After fun stop() {
        session?.rig?.stop()
    }

    private fun session(rig: EditorRig): EditorSession = EditorSession(this, rig).also { session = it }

    private fun textOf(tag: String): String =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    @Test fun theLabelPopoverGrowsOutOfItsButtonAndTheFieldDoesNotMove() {
        val s = session(EditorRig(id = 1))
        s.open()
        val tag = EditorTags.rowField(EditorList.Numbers, 0)
        val fieldBefore = px(tag)
        val rowBefore = px(EditorTags.row(EditorList.Numbers, 0))
        val button = px(EditorTags.label(EditorList.Numbers, 0))
        compose.onNodeWithTag(EditorTags.label(EditorList.Numbers, 0)).performClick()
        s.until("the popover") { s.exists(EditorTags.LabelPopover) }
        advance(700)
        val popover = px(EditorTags.LabelPopover)
        val near = 48 * density
        assertTrue(
            "the popover starts at its button: $popover vs $button",
            popover.top <= button.bottom + near && popover.bottom >= button.top - near,
        )
        assertTrue(
            "the popover is over the button's column",
            popover.left <= button.right && popover.right >= button.left,
        )
        assertEquals("the field did not move", fieldBefore, px(tag))
        assertEquals("the row did not move", rowBefore, px(EditorTags.row(EditorList.Numbers, 0)))
        compose.onNode(hasText("Work") and hasAnyAncestor(hasTestTag(EditorTags.LabelPopover))).performClick()
        s.until("the label to change") {
            s.rig.model.state.value.draft.numbers[0]
                .kind == FieldKind.Work
        }
        advance(700)
        assertFalse("picking closes the popover", s.exists(EditorTags.LabelPopover))
        assertEquals(fieldBefore, px(tag))
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun px(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun aSelectionInThePhoneFieldIsReplacedByAPaste() {
        val s = session(EditorRig(id = null))
        s.open()
        s.scrollTo(EditorTags.add(EditorList.Numbers))
        compose.onNodeWithTag(EditorTags.add(EditorList.Numbers)).performClick()
        val tag = EditorTags.rowField(EditorList.Numbers, 0)
        s.until("the new phone row") { s.exists(tag) }
        advance(700)
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag(tag).performTextInput("0532 111")
        advance(200)
        assertEquals("0532 111", textOf(tag))
        compose.onNodeWithTag(tag).performTextInputSelection(TextRange(0, 8))
        advance(100)
        assertEquals(
            TextRange(0, 8),
            compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
        )
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "+90 555 000 11 22"))
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.PasteText)
        advance(300)
        assertEquals("+90 555 000 11 22", textOf(tag))
        s.until("the model to hear it") {
            s.rig.model.state.value.draft.numbers
                .firstOrNull()
                ?.value ==
                "+90 555 000 11 22"
        }
    }

    @Test fun theMinusRemovesARowAndAddGrowsANewOne() {
        val s = session(EditorRig(id = 5))
        s.open()
        assertTrue("two numbers", s.exists(EditorTags.rowField(EditorList.Numbers, 1)))
        compose.onNodeWithTag(EditorTags.minus(EditorList.Numbers, 0)).performClick()
        s.until("the row to go") { s.rig.model.state.value.draft.numbers.size == 1 }
        advance(500)
        assertFalse("one number left", s.exists(EditorTags.rowField(EditorList.Numbers, 1)))
        assertEquals("+1 415 555 0199", textOf(EditorTags.rowField(EditorList.Numbers, 0)))
        s.scrollTo(EditorTags.add(EditorList.Emails))
        compose.onNodeWithTag(EditorTags.add(EditorList.Emails)).performClick()
        val tag = EditorTags.rowField(EditorList.Emails, 0)
        s.until("the new email row") { s.exists(tag) }
        val early = px(EditorTags.row(EditorList.Emails, 0)).height
        advance(800)
        val late = px(EditorTags.row(EditorList.Emails, 0)).height
        assertTrue("the new row grows in on a spring: $early -> $late", late > early)
    }

    @Test fun doneIsOffUntilSomethingChangedAndSavingClosesTheSheet() {
        val s = session(EditorRig(id = 1))
        s.open()
        assertFalse(s.rig.model.state.value.canSave)
        compose.onNodeWithTag(EditorTags.field("org")).performClick()
        compose.onNodeWithTag(EditorTags.field("org")).performTextInput(" Ltd")
        s.until("Save to be possible") { s.rig.model.state.value.canSave }
        advance(200)
        compose.onNodeWithTag(EditorTags.Done).performClick()
        s.until("the sheet to close") { s.closed > 0 }
        assertEquals(
            "Acme Yazılım Ltd",
            s.rig
                .contacts()
                .first { it.id == 1L }
                .org,
        )
        assertEquals(listOf(1L), s.savedIds)
        advance(400)
        assertTrue("the Undo pill is on show", existsText("Undo"))
    }

    @Test fun backOnADirtyFormAsksBeforeDiscarding() {
        val s = session(EditorRig(id = 1))
        s.open()
        s.scrollTo(EditorTags.field("nickname"))
        compose.onNodeWithTag(EditorTags.field("nickname")).performClick()
        compose.onNodeWithTag(EditorTags.field("nickname")).performTextInput("Ayo")
        s.until("the form to be dirty") { s.rig.model.state.value.dirty }
        advance(300)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        s.until("the discard question") { existsText("Discard Changes") }
        advance(500)
        assertTrue("the sheet stays open behind the question", s.exists(EditorTags.Screen))
        compose.onNodeWithText("Keep Editing").performClick()
        advance(600)
        assertTrue(s.exists(EditorTags.Screen))
        assertEquals(0, s.closed)
        compose.onNodeWithTag(EditorTags.Cancel).performClick()
        s.until("the question again") { existsText("Discard Changes") }
        advance(500)
        compose.onNodeWithText("Discard Changes").performClick()
        s.until("the sheet to close") { s.closed > 0 }
        assertEquals(
            "nothing was written",
            null,
            s.rig
                .contacts()
                .first { it.id == 1L }
                .nickname
                .takeIf { it == "Ayo" },
        )
    }

    private fun existsText(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun cancelOnACleanFormClosesAtOnce() {
        val s = session(EditorRig(id = 1))
        s.open()
        compose.onNodeWithTag(EditorTags.Cancel).performClick()
        s.until("the sheet to close") { s.closed > 0 }
    }

    @Test fun theFocusedFieldStaysAboveTheKeyboard() {
        val s = session(EditorRig(id = 1))
        s.open()
        val root =
            compose
                .onRoot()
                .fetchSemanticsNode()
                .size.height
        s.scrollTo(EditorTags.field("note"))
        advance(300)
        // As a finger does it: the tap focuses the field, then the keyboard slides up and takes the room below it.
        compose.onNodeWithTag(EditorTags.field("note")).performClick()
        setSimulatedIme(300)
        advance(800)
        assertTrue("the sheet survives the keyboard (closed=${s.closed})", s.exists(EditorTags.Screen))
        val bottom =
            try {
                compose
                    .onNodeWithTag(EditorTags.field("note"))
                    .fetchSemanticsNode()
                    .boundsInRoot.bottom
            } catch (e: AssertionError) {
                throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(14), e)
            }
        val limit = root - 300 * density - 16 * density
        assertTrue(
            "the note field ends at $bottom, the keyboard starts at ${root - 300 * density} (field limit $limit)",
            bottom <= limit + 2,
        )
        // The first field is not under the keyboard either way; the form is still scrollable above it.
        assertTrue(s.exists(EditorTags.Done))
    }

    @Test fun aSeedFillsANewContact() {
        val s =
            session(
                EditorRig(
                    seed = DraftSeed(name = "Ada Lovelace", number = "0555 123 45 67", email = "ada@example.org"),
                ),
            )
        s.open()
        assertEquals("Ada", textOf(EditorTags.field("given")))
        assertEquals("Lovelace", textOf(EditorTags.field("family")))
        assertEquals("0555 123 45 67", textOf(EditorTags.rowField(EditorList.Numbers, 0)))
        assertEquals("ada@example.org", textOf(EditorTags.rowField(EditorList.Emails, 0)))
    }
}
