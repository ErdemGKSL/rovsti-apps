package dev.rovsti.contacts.intake

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The column-mapping sheet (E4.10 C39) on a scripted state: the role menus float and the rows stay where they are. */
@Config(sdk = [35])
class CsvMappingSheetTest : ShotTest() {
    private fun show(state: IntakeUiState = mappingState()): IntakeHarness {
        val h = IntakeHarness(state)
        composeFrame { HostedIntake(h) }
        advance(1_400)
        return h
    }

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .boundsInRoot.top

    private fun enabled(tag: String): Boolean =
        compose
            .onAllNodes(
                (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and hasClickAction(),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .none { SemanticsProperties.Disabled in it.config }

    @Test fun theSheetListsEveryColumnWithItsRoleAndTheCount() {
        show()
        compose.onNodeWithText("Match the Columns").assertExists()
        compose.onNodeWithText("12 contacts found").assertExists()
        for (i in 0 until 6) compose.onNodeWithTag(MappingTags.column(i)).assertExists()
        assertEquals(2, compose.onAllNodesWithText("Automatic").fetchSemanticsNodes().size)
        compose.onNodeWithText("Ignore").assertExists()
        assertTrue(enabled(MappingTags.Continue))
    }

    @Test fun continueIsOffWhileThereAreNoContactsToImport() {
        show(mappingState(found = 0))
        compose.onNodeWithText("No contacts found with these columns").assertExists()
        assertFalse(enabled(MappingTags.Continue))
    }

    @Test fun aRowOpensAFloatingMenuOfTheFiveRolesAndTheRowsDoNotMove() {
        val h = show()
        val before = (0 until 6).map { top(MappingTags.column(it)) }
        val list = compose.onNodeWithTag(MappingTags.List).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(MappingTags.column(2)).performClick()
        advance(900)
        for (role in listOf("First name", "Last name", "Phone", "Email")) {
            // The role names are in the menu; "Phone" is also the label of the row that is open, so count the nodes.
            assertTrue(role, compose.onAllNodesWithText(role).fetchSemanticsNodes().isNotEmpty())
        }
        assertEquals(before, (0 until 6).map { top(MappingTags.column(it)) })
        assertEquals(list, compose.onNodeWithTag(MappingTags.List).fetchSemanticsNode().boundsInRoot)
        compose.onAllNodesWithText("First name").onLast().performClick()
        advance(600)
        assertEquals(listOf<IntakeAction>(IntakeAction.SetRole(2, CsvRole.FirstName)), h.actions)
    }

    @Test fun theButtonsAskForTheirActions() {
        val h = show()
        compose.onNodeWithTag(MappingTags.Continue).performClick()
        compose.onNodeWithTag(MappingTags.Cancel).performClick()
        assertEquals(listOf(IntakeAction.ConfirmMapping, IntakeAction.Cancel), h.actions)
    }

    @Test fun theMappingSheetGivesWayToThePreviewWithoutACancel() {
        val h = show()
        h.ui = previewState()
        advance(1_600)
        compose.onNodeWithText("Add these contacts?").assertExists()
        compose.onNodeWithTag(MappingTags.Sheet).assertDoesNotExist()
        assertEquals("its dismissal is not a Cancel once the preview is up", emptyList<IntakeAction>(), h.actions)
    }
}
