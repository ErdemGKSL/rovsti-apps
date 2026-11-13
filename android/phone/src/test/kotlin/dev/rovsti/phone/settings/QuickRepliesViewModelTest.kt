package dev.rovsti.phone.settings

import dev.rovsti.phone.HostRust
import dev.rovsti.phone.UnconfinedMain
import dev.rovsti.phone.settings.replies.QuickRepliesAction
import dev.rovsti.phone.settings.replies.QuickRepliesEffect
import dev.rovsti.phone.settings.replies.QuickRepliesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.9b Quick replies on the REAL core: the defaults, add / edit / delete with Undo, reorder, restore, the limits. */
class QuickRepliesViewModelTest {
    private val host by lazy { HostRust("rovsti-quick-replies") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun mainDispatcher() = UnconfinedMain.install()

    @After fun stop() = scope.cancel()

    private suspend fun model(): Pair<QuickRepliesViewModel, SettingsRig> {
        val rig = SettingsRig(host, scope)
        rig.store.load()
        val model = QuickRepliesViewModel(rig.store, rig.ops)
        model.state.until { it.loaded }
        return model to rig
    }

    private suspend fun QuickRepliesViewModel.effectNow() = withTimeout(10_000) { effect.first() }

    @Test fun theDefaultsShowUntilTheUserSavesAList() =
        runBlocking {
            val (model, rig) = model()
            val ui = model.state.value
            assertFalse(ui.custom)
            assertTrue("the core has defaults", ui.replies.isNotEmpty())
            assertTrue(ui.max > 0 && ui.maxChars > 0)
            assertEquals("the preferences hold the core's defaults", ui.replies, rig.store.prefs.value.quickReplies)
        }

    @Test fun addingMakesTheListTheUsersOwnAndSurvivesARestart() =
        runBlocking {
            val (model, rig) = model()
            val defaults = model.state.value.replies
            model.onAction(QuickRepliesAction.Add("  On my way  "))
            val ui = model.state.until { it.custom }
            assertEquals(defaults + "On my way", ui.replies)
            assertTrue(rig.store.flush())
            val again = rig.reloaded(scope)
            again.load()
            assertEquals(defaults + "On my way", again.prefs.value.quickReplies)
        }

    @Test fun editReplacesTheTextAndBlankOrRepeatedTextIsRefused() =
        runBlocking {
            val (model, _) = model()
            val first =
                model.state.value.replies
                    .first()
            model.onAction(QuickRepliesAction.Edit(0, "Call you in ten"))
            assertEquals(
                "Call you in ten",
                model.state
                    .until { it.replies.first() == "Call you in ten" }
                    .replies
                    .first(),
            )
            model.onAction(QuickRepliesAction.Edit(0, "   "))
            assertEquals(QuickRepliesEffect.Rejected, model.effectNow())
            val second = model.state.value.replies[1]
            model.onAction(QuickRepliesAction.Add(second.uppercase()))
            assertEquals("a repeat is refused", QuickRepliesEffect.Rejected, model.effectNow())
            assertTrue(first !in model.state.value.replies)
        }

    @Test fun aReplyLongerThanTheCoreAllowsIsCut() =
        runBlocking {
            val (model, _) = model()
            val max = model.state.value.maxChars
            model.onAction(QuickRepliesAction.Add("x".repeat(max + 40)))
            val ui = model.state.until { it.custom }
            assertEquals(max, ui.replies.last().length)
            assertTrue(ui.custom)
        }

    @Test fun deleteGivesUndoAndUndoPutsTheSavedListBack() =
        runBlocking {
            val (model, rig) = model()
            val before = model.state.value.replies
            model.onAction(QuickRepliesAction.Delete(1))
            val effect = model.effectNow() as QuickRepliesEffect.Deleted
            assertEquals(before[1], effect.text)
            assertEquals(
                before.filterIndexed {
                    i,
                    _,
                    ->
                    i != 1
                },
                model.state.until { it.replies.size == before.size - 1 }.replies,
            )
            model.onAction(QuickRepliesAction.Undo(effect.previous))
            assertEquals(before, model.state.until { it.replies.size == before.size }.replies)
            assertFalse("undo of a delete from the defaults is the defaults again", model.state.value.custom)
            assertEquals(before, rig.store.prefs.value.quickReplies)
        }

    @Test fun theLastReplyCannotBeDeleted() =
        runBlocking {
            val (model, _) = model()
            val n = model.state.value.replies.size
            var size = n
            while (size > 1) {
                model.onAction(QuickRepliesAction.Delete(0))
                size--
                model.state.until { it.replies.size == size }
            }
            repeat(n - 1) { assertTrue(model.effectNow() is QuickRepliesEffect.Deleted) }
            model.onAction(QuickRepliesAction.Delete(0))
            assertEquals(QuickRepliesEffect.Rejected, model.effectNow())
            assertEquals(1, model.state.value.replies.size)
        }

    @Test fun aDropMovesTheReply() =
        runBlocking {
            val (model, _) = model()
            val before = model.state.value.replies
            model.onAction(QuickRepliesAction.Move(0, 2))
            val expected = before.toMutableList().also { it.add(2, it.removeAt(0)) }
            assertEquals(expected, model.state.until { it.replies == expected }.replies)
        }

    @Test fun restoreDefaultsClearsTheListAndUndoBringsItBack() =
        runBlocking {
            val (model, _) = model()
            model.onAction(QuickRepliesAction.Add("Later"))
            val mine = model.state.until { it.custom && "Later" in it.replies }.replies
            model.onAction(QuickRepliesAction.RestoreDefaults)
            val effect = model.effectNow() as QuickRepliesEffect.DefaultsRestored
            assertFalse(model.state.until { !it.custom }.custom)
            model.onAction(QuickRepliesAction.Undo(effect.previous))
            assertEquals(mine, model.state.until { it.custom }.replies)
        }

    @Test fun theListIsFullAtTheCoresMaximum() =
        runBlocking {
            val (model, _) = model()
            var i = 0
            while (model.state.value.canAdd) {
                model.onAction(QuickRepliesAction.Add("Reply number ${i++}"))
                val want = i
                model.state.until { !it.canAdd || it.replies.any { r -> r == "Reply number ${want - 1}" } }
            }
            assertEquals(model.state.value.max, model.state.value.replies.size)
            model.onAction(QuickRepliesAction.Add("One too many"))
            assertEquals(QuickRepliesEffect.Rejected, model.effectNow())
        }
}
