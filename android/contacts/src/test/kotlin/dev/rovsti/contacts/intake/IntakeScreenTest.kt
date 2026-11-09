package dev.rovsti.contacts.intake

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.10: the import page and its sheet on a scripted state: what is enabled when, and what a tap asks for. */
@Config(sdk = [35])
class IntakeScreenTest : ShotTest() {
    private fun show(state: IntakeUiState): IntakeHarness {
        val h = IntakeHarness(state)
        composeFrame { HostedIntake(h) }
        advance(1_200)
        return h
    }

    /** The button tagged [tag] draws its own clickable inside the tag; that inner node says whether it is on. */
    private fun enabled(tag: String): Boolean {
        val inner =
            compose
                .onAllNodes(
                    (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and hasClickAction(),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        check(inner.isNotEmpty()) { "no clickable inside $tag" }
        return inner.none { SemanticsProperties.Disabled in it.config }
    }

    @Test fun whileReadingThereIsAStatusLineAndNoSheet() {
        show(IntakeUiState(phase = IntakePhase.Reading))
        compose.onNodeWithText("Reading Contacts").assertExists()
        compose.onNodeWithTag(IntakeTags.Sheet).assertDoesNotExist()
    }

    @Test fun theSheetListsEveryCardAndTheButtonsStayBelowTheLastRow() {
        val h = show(previewState())
        compose.onNodeWithText("Add these contacts?").assertExists()
        compose.onNodeWithText("9 new contacts, 2 already in your contacts").assertExists()
        compose.onNodeWithTag(IntakeTags.row(0)).assertExists()
        val add = compose.onNodeWithTag(IntakeTags.Add).fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag(IntakeTags.List).fetchSemanticsNode().boundsInRoot
        assertTrue("the list ($list) ends above the buttons ($add)", list.bottom <= add.top + 0.5f)
        assertTrue(enabled(IntakeTags.Add))
        assertEquals(emptyList<IntakeAction>(), h.actions)
    }

    @Test fun addIsOffWhileImportingAndOnlyCancelLeavesOtherwise() {
        val h = show(previewState())
        h.ui = h.ui.copy(phase = IntakePhase.Importing)
        advance(300)
        assertFalse(enabled(IntakeTags.Add))
        assertFalse(enabled(IntakeTags.Cancel))
        compose.onNodeWithText("Adding…").assertExists()
    }

    @Test fun addIsOffWhileThereIsNothingToAdd() {
        val h = show(previewState())
        h.ui = h.ui.copy(rows = persistentListOf())
        advance(300)
        assertFalse(enabled(IntakeTags.Add))
    }

    @Test fun theButtonsAndRowsAskForTheirActions() {
        val h = show(previewState(count = 1, duplicates = 0, single = true))
        compose.onNodeWithTag(IntakeTags.row(0)).performClick()
        compose.onNodeWithTag(IntakeTags.AddToExisting).performClick()
        compose.onNodeWithTag(IntakeTags.Add).performClick()
        compose.onNodeWithTag(IntakeTags.Cancel).performClick()
        assertEquals(
            listOf(
                IntakeAction.OpenCard(0),
                IntakeAction.AddToExisting,
                IntakeAction.Add,
                IntakeAction.Cancel,
            ),
            h.actions,
        )
    }

    @Test fun twoCardsOfferNoAddToExisting() {
        show(previewState(count = 2, duplicates = 0))
        compose.onNodeWithTag(IntakeTags.AddToExisting).assertDoesNotExist()
    }

    @Test fun theBackButtonOfThePageCancels() {
        val h = show(IntakeUiState(phase = IntakePhase.Reading))
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf<IntakeAction>(IntakeAction.Cancel), h.actions)
    }

    @Test fun dismissingTheSheetByBackIsCancel() {
        val h = show(previewState())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(1_200)
        assertTrue("the sheet's dismissal is a Cancel: ${h.actions}", IntakeAction.Cancel in h.actions)
    }

    @Test fun anOpenCardShowsItsFieldsReadOnlyAndBackReturns() {
        val h = show(previewState())
        h.ui =
            h.ui.copy(
                preview =
                    CardPreview(
                        0,
                        "Ada Lovelace",
                        "Analytical Engines",
                        null,
                        persistentListOf(
                            PreviewSection("Phone", persistentListOf(PreviewLine("Mobile", "+44 20 7946 0958"))),
                        ),
                    ),
            )
        advance(300)
        compose.onNodeWithTag(IntakeTags.Card).assertExists()
        compose.onNodeWithText("+44 20 7946 0958").assertExists()
        compose.onNodeWithText("Analytical Engines").assertExists()
        compose.onNodeWithTag(IntakeTags.CardBack).performClick()
        assertEquals(listOf<IntakeAction>(IntakeAction.CloseCard), h.actions)
    }
}
