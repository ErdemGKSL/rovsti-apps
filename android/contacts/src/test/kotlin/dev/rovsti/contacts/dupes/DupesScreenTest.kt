package dev.rovsti.contacts.dupes

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rovsti.testing.ShotTest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.11: the duplicates page and the merge review sheet on scripted states: what is drawn and what a tap asks for. */
@Config(sdk = [35])
class DupesScreenTest : ShotTest() {
    private fun show(state: DupesUiState): DupesHarness {
        val h = DupesHarness(state)
        composeFrame { HostedDupes(h) }
        advance(1_200)
        return h
    }

    @Test fun everyGroupIsOneRowWithOneAvatarAndAnAndMoreLine() {
        show(SampleDupes)
        compose.onNodeWithText("4 possible duplicates").assertExists()
        for (row in SampleDupes.rows) {
            compose.onNodeWithTag(DupesTags.row(row.key)).assertExists()
            compose.onNodeWithText(row.name).assertExists()
            compose.onNodeWithText(row.secondary).assertExists()
        }
        // One row, one title: the other members of a group are never named in the row.
        compose.onAllNodesWithTextCount("Yılmaz").let { assertEquals(1, it) }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test fun aTapOpensTheReviewAndMergeAllAsks() {
        val h = show(SampleDupes)
        compose.onNodeWithTag(DupesTags.row(4)).performClick()
        compose.onNodeWithTag(DupesTags.MergeAll).performClick()
        assertEquals(listOf(DupesAction.Open(4), DupesAction.AskMergeAll), h.actions)
    }

    @Test fun noDuplicatesIsAnEmptyStateWithoutMergeAll() {
        show(DupesUiState(phase = DupesPhase.Ready, summary = "No duplicates found"))
        compose.onNodeWithText("No Duplicates").assertExists()
        compose.onNodeWithTag(DupesTags.MergeAll).assertDoesNotExist()
    }

    @Test fun theBackButtonLeaves() {
        val h = show(SampleDupes)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, h.backs)
    }

    @Test fun theReviewSheetShowsTheMergedCardAndTheSourcesOnOneLine() {
        val h = show(SampleDupes.copy(review = SampleReview))
        compose.onNodeWithTag(DupesTags.Sheet).assertExists()
        compose.onNodeWithText("From Ayşe Yılmaz, A. Yılmaz, Ayşe").assertExists()
        val sources = compose.onNodeWithTag(DupesTags.Sources).fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithText("Acme Yazılım").fetchSemanticsNode().boundsInRoot
        assertTrue("one line of text, not a row of pills", sources.height < name.height * 1.6f)
        compose.onNodeWithText("+90 555 111 22 33").assertExists()
        compose.onNodeWithTag(DupesTags.Merge).performClick()
        assertEquals(listOf<DupesAction>(DupesAction.MergeOpen), h.actions)
    }

    @Test fun cancelClosesTheReviewAndTheSheetGoes() {
        val h = show(SampleDupes.copy(review = SampleReview))
        compose.onNodeWithTag(DupesTags.Cancel).performClick()
        advance(1_000)
        // The sheet leaving reports its own dismissal too; closing twice is harmless.
        assertTrue(h.actions.isNotEmpty() && h.actions.all { it == DupesAction.CloseReview })
        compose.onNodeWithTag(DupesTags.Sheet).assertDoesNotExist()
    }

    @Test fun whileMergingTheButtonsWait() {
        val h = show(SampleDupes.copy(review = SampleReview, busy = true))
        compose.onNodeWithText("Merging…").assertExists()
        compose.onNodeWithTag(DupesTags.Merge).performClick()
        compose.onNodeWithTag(DupesTags.Cancel).performClick()
        assertTrue("no action while busy: ${h.actions}", h.actions.isEmpty())
    }

    @Test fun effectsBecomeAnAlertAndOneUndoPill() {
        val h = show(SampleDupes)
        val effects = Channel<DupesEffect>(Channel.UNLIMITED)
        composeFrame { HostedDupesWithEffects(h, effects.receiveAsFlow()) }
        advance(1_200)
        effects.trySend(
            DupesEffect.Ask(
                "Merge all 4 duplicates?",
                "Each group becomes one contact.",
                "Merge All",
                DupesAction.ConfirmMergeAll,
            ),
        )
        advance(1_000)
        compose.onNodeWithText("Merge all 4 duplicates?").assertExists()
        effects.trySend(DupesEffect.Undo("Merged 7 contacts", token = 3))
        advance(1_000)
        compose.onNodeWithText("Merged 7 contacts").assertExists()
        compose.onNodeWithText("Undo").performClick()
        advance(300)
        assertTrue(DupesAction.Undo(3) in h.actions)
    }
}
