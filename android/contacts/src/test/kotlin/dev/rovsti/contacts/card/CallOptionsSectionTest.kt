package dev.rovsti.contacts.card

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.13: the "Calls" section draws its rows from the first frame; flipping the switch moves nothing around it. */
@Config(sdk = [35])
class CallOptionsSectionTest : ShotTest() {
    private var calls by mutableStateOf(
        CallOptionsUiState(loaded = true, ringtone = "Beacon", block = BlockRow.Available(false)),
    )
    private val actions = mutableListOf<CallOptionsAction>()

    private fun show(initial: CallOptionsUiState = calls) {
        calls = initial
        composeFrame {
            LazyColumn(state = rememberLazyListState()) {
                item { RovstiText("Above", Modifier.testTag("above")) }
                callOptionsItem(calls) { action ->
                    actions += action
                    // The view model answers a "off" at once; "on" waits for its question.
                    if (action is CallOptionsAction.SetVoicemail) calls = calls.copy(voicemail = action.on)
                }
                item { RovstiText("Below", Modifier.testTag("below")) }
            }
        }
        advance(300)
    }

    private fun voicemailOn() =
        compose
            .onNodeWithTag(CallOptionsTags.Voicemail)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ToggleableState] == ToggleableState.On

    @Test fun theRowsShowTheirFinalWordsAtOnce() {
        show()
        compose.onNodeWithText("Calls").assertIsDisplayed()
        compose
            .onNodeWithTag(
                CallOptionsTags.Ringtone,
            ).assertIsDisplayed()
            .assertTextContains("Beacon", substring = true)
        compose.onNodeWithTag(CallOptionsTags.Voicemail).assertIsDisplayed()
        compose.onNodeWithTag(CallOptionsTags.Block).assertIsDisplayed().assertTextContains("Block This Caller")
    }

    @Test fun theSwitchSpringsWithoutMovingTheSectionOrWhatIsBelow() {
        show()
        val section = compose.onNodeWithTag(CallOptionsTags.Section).getUnclippedBoundsInRoot()
        val below = compose.onNodeWithTag("below").getUnclippedBoundsInRoot()
        val block = compose.onNodeWithTag(CallOptionsTags.Block).getUnclippedBoundsInRoot()
        assertFalse(voicemailOn())
        compose.onNodeWithTag(CallOptionsTags.Voicemail).performSemanticsAction(SemanticsActions.OnClick)
        for (ms in listOf(16L, 60L, 120L, 500L)) {
            advance(ms)
            assertEquals(
                "the section keeps its box at +$ms ms",
                section,
                compose.onNodeWithTag(CallOptionsTags.Section).getUnclippedBoundsInRoot(),
            )
            assertEquals(
                "the item below stays at +$ms ms",
                below,
                compose.onNodeWithTag("below").getUnclippedBoundsInRoot(),
            )
            assertEquals(
                "the block row stays at +$ms ms",
                block,
                compose.onNodeWithTag(CallOptionsTags.Block).getUnclippedBoundsInRoot(),
            )
        }
        assertEquals(listOf<CallOptionsAction>(CallOptionsAction.SetVoicemail(true)), actions)
        assertTrue("the switch is on", voicemailOn())
    }

    @Test fun theBlockRowSwitchesToUnblockWithoutChangingHeight() {
        show()
        val before = compose.onNodeWithTag(CallOptionsTags.Section).getUnclippedBoundsInRoot()
        calls = calls.copy(block = BlockRow.Available(true))
        advance(300)
        compose.onNodeWithTag(CallOptionsTags.Block).assertTextContains("Unblock This Caller")
        assertEquals(before, compose.onNodeWithTag(CallOptionsTags.Section).getUnclippedBoundsInRoot())
    }

    @Test fun withoutTheRoleTheRowExplainsAndATapAsksToToggleTheBlock() {
        show(calls.copy(block = BlockRow.NeedsRole))
        compose.onNodeWithText(NEEDS_ROLE_TEXT).assertIsDisplayed()
        compose.onNodeWithTag(CallOptionsTags.Block).performClick()
        assertEquals(listOf<CallOptionsAction>(CallOptionsAction.ToggleBlock), actions)
    }

    @Test fun aContactWithoutNumbersHasNoBlockRowAndTheRingtoneRowOpensThePicker() {
        show(calls.copy(block = BlockRow.Hidden))
        assertTrue(count(CallOptionsTags.Block) == 0)
        compose.onNodeWithTag(CallOptionsTags.Ringtone).performClick()
        assertEquals(listOf<CallOptionsAction>(CallOptionsAction.ChooseRingtone), actions)
    }

    @Test fun nothingIsDrawnBeforeTheOptionsAreRead() {
        show(CallOptionsUiState(loaded = false))
        assertTrue(count(CallOptionsTags.Section) == 0)
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
}
