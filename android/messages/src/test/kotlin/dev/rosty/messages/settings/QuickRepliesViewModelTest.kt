package dev.rosty.messages.settings

import dev.rosty.core.ops.NativeChatOps
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.settings.replies.QuickRepliesAction
import dev.rosty.messages.settings.replies.QuickRepliesEffect
import dev.rosty.messages.settings.replies.QuickRepliesViewModel
import dev.rosty.messages.settings.replies.ReplyProblem
import dev.rosty.messages.spam.SpamCore
import dev.rosty.messages.spam.prefsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E6.12 M11: the Quick replies page's actions go through `quick_replies.edit` and save the list it answers. */
class QuickRepliesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private class Rig(
        val model: QuickRepliesViewModel,
        val core: SpamCore,
    )

    private fun TestScope.rig(replies: List<String> = listOf("A", "B", "C")): Rig {
        val core = SpamCore(prefsWith().copy(quickReplies = replies))
        core.core.fakeQuickReplies()
        val store = MessagesStore(core.ops, backgroundScope, io = dispatcher, debounceMs = 0)
        return Rig(QuickRepliesViewModel(store, NativeChatOps(core.core)), core)
    }

    private fun Rig.saved() = core.saved?.quickReplies

    @Test fun theListAndTheLimitsComeFromTheCore() =
        scope.runTest {
            val r = rig()
            advanceUntilIdle()
            val ui = r.model.state.value
            assertTrue(ui.loaded && ui.canAdd)
            assertEquals(listOf("A", "B", "C"), ui.replies)
            assertEquals(20, ui.max)
            assertEquals(500, ui.maxChars)
        }

    @Test fun addTrimsAndARefusalSaysWhy() =
        scope.runTest {
            val r = rig()
            advanceUntilIdle()
            var answer: ReplyProblem? = ReplyProblem.Failed
            r.model.onAction(QuickRepliesAction.Add("  Tamamdır  ") { answer = it })
            advanceUntilIdle()
            assertNull(answer)
            assertEquals(listOf("A", "B", "C", "Tamamdır"), r.saved())
            r.model.onAction(QuickRepliesAction.Add("b") { answer = it })
            advanceUntilIdle()
            assertEquals(ReplyProblem.Duplicate, answer)
            r.model.onAction(QuickRepliesAction.Add("   ") { answer = it })
            advanceUntilIdle()
            assertEquals(ReplyProblem.Empty, answer)
        }

    @Test fun anEditKeepsItsPlaceAndIsJudgedAgainstTheOthers() =
        scope.runTest {
            val r = rig()
            advanceUntilIdle()
            var answer: ReplyProblem? = ReplyProblem.Failed
            r.model.onAction(QuickRepliesAction.Edit(1, " Bee ") { answer = it })
            advanceUntilIdle()
            assertNull(answer)
            assertEquals(listOf("A", "Bee", "C"), r.saved())
            // Editing a reply into itself is fine; into another is a duplicate and nothing changes.
            r.model.onAction(QuickRepliesAction.Edit(1, "bee") { answer = it })
            advanceUntilIdle()
            assertNull(answer)
            r.model.onAction(QuickRepliesAction.Edit(0, "c") { answer = it })
            advanceUntilIdle()
            assertEquals(ReplyProblem.Duplicate, answer)
            assertEquals("A", r.model.state.value.replies[0])
        }

    @Test fun deleteOffersUndoThatPutsItBack() =
        scope.runTest {
            val r = rig()
            advanceUntilIdle()
            val effects = mutableListOf<QuickRepliesEffect>()
            backgroundScope.launch { r.model.effect.collect { effects += it } }
            r.model.onAction(QuickRepliesAction.Delete(1))
            advanceUntilIdle()
            assertEquals(listOf("A", "C"), r.saved())
            assertEquals(listOf(QuickRepliesEffect.Deleted(1, "B")), effects)
            r.model.onAction(QuickRepliesAction.Restore(1, "B"))
            advanceUntilIdle()
            assertEquals(listOf("A", "B", "C"), r.saved())
        }

    @Test fun aDropMovesStepByStepBothWays() =
        scope.runTest {
            val r = rig(listOf("A", "B", "C", "D"))
            advanceUntilIdle()
            r.model.onAction(QuickRepliesAction.Move(3, 0))
            advanceUntilIdle()
            assertEquals(listOf("D", "A", "B", "C"), r.saved())
            r.model.onAction(QuickRepliesAction.Move(0, 2))
            advanceUntilIdle()
            assertEquals(listOf("A", "B", "D", "C"), r.saved())
            assertEquals(
                listOf("A", "B", "D", "C"),
                r.model.state
                    .first { it.replies[2] == "D" }
                    .replies,
            )
        }
}
