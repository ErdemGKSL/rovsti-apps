package dev.rovsti.contacts.dupes

import dev.rovsti.contacts.R
import dev.rovsti.contacts.en
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.contacts.ContactResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4.11 (C8): the groups `contacts.dupes` finds, the merged preview of `contacts.merge_plan`, a confirmed merge that
 * keeps copies in the Trash and ends in ONE undo, and Merge All - on the real Rust core.
 */
class DupesViewModelTest {
    private var rig: TidyRig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun rig() = TidyRig().also { rig = it }

    private suspend fun DupesViewModel.until(predicate: (DupesUiState) -> Boolean) =
        withTimeout(TidyRig.TIMEOUT_MS) { state.first(predicate) }

    private suspend fun DupesViewModel.effect(): DupesEffect = withTimeout(TidyRig.TIMEOUT_MS) { effects.first() }

    @Test fun groupsAreFoundOnceAndEachRowHasOneAvatarAndAnAndMoreLine() =
        runBlocking<Unit> {
            val model = rig().dupes()
            val state = model.until { it.phase == DupesPhase.Ready && it.rows.isNotEmpty() }
            assertEquals("3 possible duplicates", (state.summary).en())
            assertEquals(listOf(1L, 4L, 6L), state.rows.map { it.key })
            assertEquals("Ayşe Yılmaz", state.rows[0].name)
            assertEquals("and 2 more · Same number", (state.rows[0].secondary).en())
            assertEquals("and 1 more · Same number", (state.rows[1].secondary).en())
            assertEquals("and 1 more · Same name", (state.rows[2].secondary).en())
            assertEquals("the keeper's picture", "content://contacts/photo/1", state.rows[0].photoUri)
        }

    @Test fun anAddressBookWithoutLookAlikesIsEmpty() =
        runBlocking<Unit> {
            val r = TidyRig(drafts = SampleTidy.drafts().filter { it.id in setOf(1L, 4L, 8L, 9L) }).also { rig = it }
            val state = r.dupes().until { it.phase == DupesPhase.Ready }
            assertTrue(state.isEmpty)
            assertEquals("No duplicates found", (state.summary).en())
        }

    @Test fun tappingARowShowsTheMergedCardWithTheSourcesOnOneLine() =
        runBlocking<Unit> {
            val model = rig().dupes()
            model.until { it.rows.isNotEmpty() }
            model.onAction(DupesAction.Open(1))
            val review = model.until { it.review != null }.review!!
            assertEquals("Ayşe Yılmaz", review.name)
            assertEquals("From Ayşe Yılmaz, A. Yılmaz, Ayşe", (review.sources).en())
            assertEquals("Merge 3 Contacts", (review.mergeLabel).en())
            val phones =
                review.sections
                    .first { it.title?.en() == "Phone" }
                    .lines
                    .map { it.value }
            assertEquals("the same number written twice is kept once", 2, phones.size)
            assertTrue(review.sections.any { it.title?.en() == "Email" })
            model.onAction(DupesAction.CloseReview)
            model.until { it.review == null }
        }

    @Test fun mergingSavesTheMergedCardTrashesTheOthersAndOffersOneUndo() =
        runBlocking<Unit> {
            val r = rig()
            val model = r.dupes()
            model.until { it.rows.isNotEmpty() }
            model.onAction(DupesAction.Open(1))
            model.until { it.review != null }
            model.onAction(DupesAction.MergeOpen)
            val undo = model.effect() as DupesEffect.Undo
            assertEquals("Merged 3 contacts", (undo.message).en())
            val after = r.contacts()
            assertEquals(10, after.size)
            assertTrue(after.none { it.id == 2L || it.id == 3L })
            assertEquals(2, after.single { it.id == 1L }.numbers.size)
            assertEquals(2, r.wait { r.trash.list()!!.entries }.size)
            // The two groups that were not merged are still listed, the merged one is gone.
            val left = model.until { it.rows.size == 2 }
            assertEquals(listOf(4L, 6L), left.rows.map { it.key })
            assertNotNull(undo.token)
        }

    @Test fun undoPutsEverythingBackInOneStep() =
        runBlocking<Unit> {
            val r = rig()
            val before = r.contacts()
            val model = r.dupes()
            model.until { it.rows.isNotEmpty() }
            model.onAction(DupesAction.Open(1))
            model.until { it.review != null }
            model.onAction(DupesAction.MergeOpen)
            val undo = model.effect() as DupesEffect.Undo
            model.onAction(DupesAction.Undo(undo.token))
            assertEquals(DupesEffect.Toast(UiText.Res(R.string.contacts_merge_undone)), model.effect())
            val after = r.contacts()
            assertEquals(before.size, after.size)
            assertEquals("the keeper is as it was", before.single { it.id == 1L }, after.single { it.id == 1L })
            assertEquals(
                "the others came back with the same fields",
                before.filter { it.id == 2L || it.id == 3L }.map { it.copy(id = null) }.toSet(),
                after.filter { it.id !in before.map { b -> b.id } }.map { it.copy(id = null) }.toSet(),
            )
            assertTrue(r.wait { r.trash.list()!!.entries }.isEmpty())
            model.until { it.rows.size == 3 }
        }

    @Test fun mergeAllAsksFirstThenMergesEveryGroupWithOneUndo() =
        runBlocking<Unit> {
            val r = rig()
            val before = r.contacts()
            val model = r.dupes()
            model.until { it.rows.size == 3 }
            model.onAction(DupesAction.AskMergeAll)
            val ask = model.effect() as DupesEffect.Ask
            assertEquals("Merge All", (ask.confirmLabel).en())
            assertEquals(DupesAction.ConfirmMergeAll, ask.then)
            assertEquals("asking changes nothing", before, r.contacts())
            model.onAction(ask.then)
            val undo = model.effect() as DupesEffect.Undo
            assertEquals("Merged 7 contacts", (undo.message).en())
            assertEquals(before.size - 4, r.contacts().size)
            model.until { it.isEmpty }
            // ONE undo brings back all four removed contacts.
            model.onAction(DupesAction.Undo(undo.token))
            assertEquals(DupesEffect.Toast(UiText.Res(R.string.contacts_merge_undone)), model.effect())
            assertEquals(before.size, r.contacts().size)
            assertTrue(r.wait { r.trash.list()!!.entries }.isEmpty())
        }

    @Test fun aRefusedWriteChangesNothingAndSaysSo() =
        runBlocking<Unit> {
            val r = rig()
            val before = r.contacts()
            val model = r.dupes()
            model.until { it.rows.isNotEmpty() }
            r.repo.failWith = ContactResult.Reason.Refused
            model.onAction(DupesAction.AskMergeAll)
            model.effect()
            model.onAction(DupesAction.ConfirmMergeAll)
            assertEquals(DupesEffect.Toast(UiText.Res(R.string.contacts_cant_merge)), model.effect())
            r.repo.failWith = null
            assertEquals(before, r.contacts())
            assertTrue(r.wait { r.trash.list()!!.entries }.isEmpty())
            assertTrue(
                model.state.value.busy
                    .not(),
            )
        }
}
