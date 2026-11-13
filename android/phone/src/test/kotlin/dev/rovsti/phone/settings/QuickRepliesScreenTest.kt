package dev.rovsti.phone.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.phone.HostedPhone
import dev.rovsti.phone.PhoneHarness
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.PhoneSample
import dev.rovsti.phone.settings.replies.QuickRepliesAction
import dev.rovsti.phone.settings.replies.QuickRepliesScreen
import dev.rovsti.phone.settings.replies.QuickRepliesTags
import dev.rovsti.phone.settings.replies.QuickRepliesUi
import dev.rovsti.phone.settings.replies.ReplyEditContent
import dev.rovsti.phone.settings.replies.ReplyEditTags
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.9b Quick replies page: the reorder drag with the Compose clock stopped, the row actions and the edit sheet. */
@Config(sdk = [35])
class QuickRepliesScreenTest : ShotTest() {
    private val haptics = RecordingHaptics()
    private val actions = mutableListOf<QuickRepliesAction>()
    private val edits = mutableListOf<Int?>()

    @Composable
    private fun Page(initial: QuickRepliesUi = SettingsFixtures.replies()) {
        var ui by remember { mutableStateOf(initial) }
        CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
            QuickRepliesScreen(
                ui,
                onAction = { action ->
                    actions += action
                    if (action is QuickRepliesAction.Move) {
                        val list = ui.replies.toMutableList()
                        list.add(action.to, list.removeAt(action.from))
                        ui = ui.copy(replies = list.toImmutableList())
                    }
                },
                onEdit = { edits += it },
            )
        }
    }

    private fun show(initial: QuickRepliesUi = SettingsFixtures.replies()) {
        composeFrame {
            HostedPhone(PhoneHarness(), PhoneSample.setup(), push = PhoneRoute.QuickReplies, extra = { Page(initial) })
        }
        advance(500)
    }

    private fun top(text: String) =
        compose
            .onNodeWithText(text)
            .getUnclippedBoundsInRoot()
            .top.value

    private fun handle(index: Int) = compose.onNodeWithTag(QuickRepliesTags.handle(index), useUnmergedTree = true)

    @Test fun draggingTheFirstReplyTwoPlacesDownDropsOnceAndTicks() {
        show()
        val texts = SettingsFixtures.replyTexts
        val pitch = top(texts[2]) - top(texts[0])
        handle(0).performTouchInput { down(center) }
        advance(100)
        val step = pitch * 1.4f / 15f
        repeat(15) {
            handle(0).performTouchInput { moveBy(Offset(0f, step)) }
            advance(120)
        }
        advance(300)
        handle(0).performTouchInput { up() }
        repeat(6) { advance(300) }

        assertEquals(
            "one drop however many rows it passed: $actions",
            1,
            actions.filterIsInstance<QuickRepliesAction.Move>().size,
        )
        val move = actions.filterIsInstance<QuickRepliesAction.Move>().single()
        assertEquals(0, move.from)
        assertTrue("it moved down at least one place", move.to >= 1)
        val order = texts.sortedBy { top(it) }
        assertEquals(texts.toMutableList().also { it.add(move.to, it.removeAt(0)) }, order)
        assertEquals("the lift ticked once", 1, haptics.events.count { it == HapticEvent.Tick })
    }

    @Test fun aTapOnAReplyEditsItAndAddOpensAnEmptySheet() {
        show()
        compose.onNodeWithText(SettingsFixtures.replyTexts[1]).performClick()
        assertEquals(listOf<Int?>(1), edits)
        compose.onNodeWithTag(QuickRepliesTags.Add).performClick()
        assertEquals(listOf<Int?>(1, null), edits)
    }

    @Test fun theCounterAndRestoreRowShowWhenTheListIsTheUsersOwn() {
        show()
        compose.onNodeWithTag(QuickRepliesTags.Count).assertIsDisplayed()
        compose.onNodeWithTag(QuickRepliesTags.Restore).performClick()
        assertEquals(listOf(QuickRepliesAction.RestoreDefaults), actions)
    }

    @Test fun aFullListOffersNoAddRow() {
        show(SettingsFixtures.replies(SettingsFixtures.replyTexts + "A sixth one"))
        assertEquals(0, compose.onAllNodes(hasTestTag(QuickRepliesTags.Add)).fetchSemanticsNodes().size)
    }

    @Test fun theEditSheetSavesTheTypedTextAndShowsTheCounterNearTheLimit() {
        var saved: String? = null
        composeFrame {
            androidx.compose.foundation.layout.Column {
                ReplyEditContent(
                    initial = "Hello",
                    maxChars = 100,
                    counterAt = 5,
                    onSave = { saved = it },
                    onCancel = {},
                    onDelete = {},
                )
            }
        }
        advance(300)
        compose.onNodeWithTag(ReplyEditTags.Counter).assertIsDisplayed()
        compose.onNodeWithTag(ReplyEditTags.Field).performTextInput(" there")
        advance(100)
        compose.onNodeWithTag(ReplyEditTags.Save).performClick()
        assertEquals("Hello there", saved)
        compose.onNodeWithTag(ReplyEditTags.Delete).assertIsDisplayed()
    }
}
