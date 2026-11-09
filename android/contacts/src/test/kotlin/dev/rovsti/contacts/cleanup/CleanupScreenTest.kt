package dev.rovsti.contacts.cleanup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import dev.rovsti.design.controls.ChipTagPrefix
import dev.rovsti.testing.ShotTest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.11: the Clean up page on scripted states: ONE row of chips that scrolls and never wraps, select mode, Delete. */
@Config(sdk = [35])
class CleanupScreenTest : ShotTest() {
    private fun show(
        state: CleanupUiState,
        fontScale: Float = 1f,
    ): CleanupHarness {
        val h = CleanupHarness(state)
        composeFrame(fontScale = fontScale) { HostedCleanup(h) }
        advance(1_200)
        return h
    }

    private fun chip(id: String) = ChipTagPrefix + id

    @Test fun theChipsAreOneRowWithTheirCounts() {
        show(cleanupState())
        val tops =
            SampleChips.take(3).map {
                compose
                    .onNodeWithTag(chip(it.id))
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        assertEquals("the chips share one line", 1, tops.distinct().size)
        compose.onNodeWithText("No number").assertExists()
        compose.onNodeWithText("12").assertExists()
    }

    @Test fun atTwiceTheFontSizeTheChipsScrollInsteadOfWrapping() {
        show(cleanupState(), fontScale = 2f)
        val first = compose.onNodeWithTag(chip("NoNumber")).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag(chip("NoName")).fetchSemanticsNode().boundsInRoot
        assertEquals("a second chip sits beside the first, not below it", first.top, second.top, 0.5f)
        // The row is wider than the screen: the last chip is not on show until the row is scrolled sideways.
        compose.onNodeWithTag(chip("NoGroup")).assertDoesNotExist()
        repeat(2) {
            compose.onNodeWithTag(CleanupTags.Chips).performTouchInput { swipeLeft() }
            advance(1_000)
        }
        compose.onNodeWithTag(chip("NoGroup")).assertIsDisplayed()
        val last = compose.onNodeWithTag(chip("NoGroup")).fetchSemanticsNode().boundsInRoot
        assertEquals("still on the same line after scrolling", first.top, last.top, 0.5f)
    }

    @Test fun tappingAChipPicksThatFilter() {
        val h = show(cleanupState())
        compose.onNodeWithTag(chip("NoName")).performClick()
        assertEquals(listOf<CleanupAction>(CleanupAction.SelectFilter("NoName")), h.actions)
    }

    @Test fun aTapOnARowStartsSelectingWithIt() {
        val h = show(cleanupState())
        compose.onNodeWithTag(CleanupTags.row(2)).performClick()
        assertEquals(listOf<CleanupAction>(CleanupAction.EnterSelect(2)), h.actions)
    }

    @Test fun selectModeTapsTogglesAndTheTitleBarOffersDelete() {
        val h = show(cleanupState(picked = setOf(1L, 2L)))
        compose.onNodeWithText("2 selected").assertExists()
        compose.onNodeWithTag(CleanupTags.row(3)).performClick()
        compose.onNodeWithTag(CleanupTags.SelectAll).performClick()
        compose.onNodeWithTag(CleanupTags.Delete).performClick()
        compose.onNodeWithTag(CleanupTags.Cancel).performClick()
        assertEquals(
            listOf(
                CleanupAction.Toggle(3),
                CleanupAction.ToggleAll,
                CleanupAction.AskDelete,
                CleanupAction.ExitSelect,
            ),
            h.actions,
        )
    }

    @Test fun deleteIsOffWithoutAPick() {
        val h = show(cleanupState(picked = emptySet()))
        compose.onNodeWithText("Select contacts").assertExists()
        compose.onNodeWithTag(CleanupTags.Delete).performClick()
        assertFalse("a disabled Delete asks for nothing: ${h.actions}", CleanupAction.AskDelete in h.actions)
    }

    @Test fun anEmptyFilterFillsThePageWithTheCoresWords() {
        show(cleanupState(rows = emptyList()))
        compose.onNodeWithTag(CleanupTags.Empty).assertExists()
        compose.onNodeWithText("Every contact has a number").assertExists()
        compose.onNodeWithTag(chip("NoNumber")).assertExists()
    }

    @Test fun theBackButtonLeaves() {
        val h = show(cleanupState())
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, h.backs)
    }

    @Test fun effectsBecomeAnAlertAndOneUndoPill() {
        val h = CleanupHarness(cleanupState(picked = setOf(1L)))
        val effects = Channel<CleanupEffect>(Channel.UNLIMITED)
        composeFrame { HostedCleanupWithEffects(h, effects.receiveAsFlow()) }
        advance(1_200)
        effects.trySend(
            CleanupEffect.Ask(
                dev.rovsti.core.model.contacts
                    .Confirm("Delete 1 contact?", "It goes to the Trash."),
            ),
        )
        advance(1_000)
        compose.onNodeWithText("Delete 1 contact?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        advance(500)
        assertTrue(CleanupAction.ConfirmDelete in h.actions)
        effects.trySend(CleanupEffect.Deleted("1 contact deleted.", listOf(7L)))
        advance(1_000)
        compose.onNodeWithText("1 contact deleted.").assertExists()
        compose.onNodeWithText("Undo").performClick()
        advance(300)
        assertTrue(CleanupAction.Undo(listOf(7L)) in h.actions)
    }
}
