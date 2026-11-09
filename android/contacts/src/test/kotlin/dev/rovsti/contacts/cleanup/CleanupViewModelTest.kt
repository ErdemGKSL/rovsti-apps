package dev.rovsti.contacts.cleanup

import dev.rovsti.contacts.dupes.SampleTidy
import dev.rovsti.contacts.dupes.TidyRig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4.11 (C24, C54): the filters and counts of `contacts.cleanup`, one filter at a time, select mode of the Contacts list
 * and a delete that goes through the Trash with ONE undo - on the real Rust core.
 */
class CleanupViewModelTest {
    private var rig: TidyRig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun rig() = TidyRig().also { rig = it }

    private suspend fun CleanupViewModel.until(predicate: (CleanupUiState) -> Boolean) =
        withTimeout(TidyRig.TIMEOUT_MS) { state.first(predicate) }

    private suspend fun CleanupViewModel.effect(): CleanupEffect = withTimeout(TidyRig.TIMEOUT_MS) { effects.first() }

    @Test fun theFourFiltersCarryTheirCountsInOneRowOfChips() =
        runBlocking<Unit> {
            val model = rig().cleanup()
            val state = model.until { it.phase == CleanupPhase.Ready }
            assertEquals(listOf("NoNumber", "NoName", "NoPhoto", "NoGroup"), state.chips.map { it.id })
            assertEquals(listOf("No number", "No name", "No photo", "Not in a group"), state.chips.map { it.label })
            assertEquals(listOf(4, 2, 10, 10), state.chips.map { it.count })
            assertEquals("NoNumber", state.filter)
            assertEquals(listOf(3L, 6L, 7L, 12L), state.rows.map { it.id })
        }

    @Test fun switchingTheFilterListsThoseContactsByWhatTheyHave() =
        runBlocking<Unit> {
            val model = rig().cleanup()
            model.until { it.phase == CleanupPhase.Ready }
            model.onAction(CleanupAction.SelectFilter("NoName"))
            val state = model.until { it.filter == "NoName" && it.rows.isNotEmpty() }
            assertEquals(listOf(11L, 12L), state.rows.map { it.id })
            assertEquals("a nameless contact is listed by its number", "0501 111 11 11", state.rows[0].name)
            assertEquals("or by its e-mail", "zed@example.test", state.rows[1].name)
            model.onAction(CleanupAction.SelectFilter("NoPhoto"))
            val noPhoto = model.until { it.filter == "NoPhoto" && it.rows.isNotEmpty() }
            assertTrue("contacts with a picture are not listed", noPhoto.rows.none { it.id == 1L || it.id == 4L })
            assertEquals(10, noPhoto.rows.size)
        }

    @Test fun anEmptyFilterSaysWhyInTheCoresWords() =
        runBlocking<Unit> {
            val r = TidyRig(drafts = SampleTidy.drafts().filter { it.id == 8L }).also { rig = it }
            val model = r.cleanup()
            val state = model.until { it.phase == CleanupPhase.Ready }
            assertTrue(state.isEmpty)
            assertEquals("Every contact has a number", state.emptyText)
        }

    @Test fun selectModeCountsAndOffersDeleteOnlyWithAPick() =
        runBlocking<Unit> {
            val model = rig().cleanup()
            model.until { it.rows.isNotEmpty() }
            assertNull(model.state.value.select)
            model.onAction(CleanupAction.EnterSelect(null))
            val empty = model.until { it.select != null }.select!!
            assertEquals("Select contacts", empty.title)
            assertTrue(!empty.canDelete)
            model.onAction(CleanupAction.Toggle(6))
            val one = model.until { it.select?.selected == setOf(6L) }.select!!
            assertEquals("1 selected", one.title)
            assertTrue(one.canDelete)
            model.onAction(CleanupAction.ToggleAll)
            val all = model.until { it.select?.allSelected == true }.select!!
            assertEquals(setOf(3L, 6L, 7L, 12L), all.selected)
            model.onAction(CleanupAction.ExitSelect)
            model.until { it.select == null }
        }

    @Test fun changingTheFilterDropsThePicks() =
        runBlocking<Unit> {
            val model = rig().cleanup()
            model.until { it.rows.isNotEmpty() }
            model.onAction(CleanupAction.EnterSelect(6))
            model.until { it.select?.selected == setOf(6L) }
            model.onAction(CleanupAction.SelectFilter("NoName"))
            val state = model.until { it.filter == "NoName" && it.rows.isNotEmpty() }
            assertTrue(
                "the picks were made under another filter",
                state.select
                    ?.selected
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test fun deleteAsksThenMovesToTheTrashWithOneUndo() =
        runBlocking<Unit> {
            val r = rig()
            val before = r.contacts()
            val model = r.cleanup()
            model.until { it.rows.isNotEmpty() }
            model.onAction(CleanupAction.EnterSelect(6))
            model.onAction(CleanupAction.Toggle(7))
            model.until { it.select?.selected == setOf(6L, 7L) }
            model.onAction(CleanupAction.AskDelete)
            val ask = model.effect() as CleanupEffect.Ask
            assertTrue(ask.confirm.title.isNotBlank())
            assertEquals("asking deletes nothing", before, r.contacts())
            model.onAction(CleanupAction.ConfirmDelete)
            val deleted = model.effect() as CleanupEffect.Deleted
            assertEquals(2, deleted.keys.size)
            assertTrue(r.contacts().none { it.id == 6L || it.id == 7L })
            assertEquals("every deleted contact has a copy in the Trash", 2, r.wait { r.trash.list()!!.entries }.size)
            val state = model.until { it.rows.map { row -> row.id } == listOf(3L, 12L) }
            assertNull("select mode ended", state.select)
            // ONE undo brings both back.
            model.onAction(CleanupAction.Undo(deleted.keys))
            assertTrue(model.effect() is CleanupEffect.Toast)
            assertEquals(before.size, r.contacts().size)
            assertTrue(r.wait { r.trash.list()!!.entries }.isEmpty())
            assertNotNull(model.until { it.rows.size == 4 })
        }
}
