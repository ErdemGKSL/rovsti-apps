package dev.rovsti.contacts.trash

import dev.rovsti.contacts.card.SampleCards
import dev.rovsti.platform.contacts.ContactResult
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4.5: delete with a copy in the Trash, restore (the undo pill and the Trash page), "deleted N days ago" rows, the
 * 30-day pruning, Delete Now and Empty Trash with their questions - on the real Rust core.
 */
class TrashViewModelTest {
    private var rig: TrashRig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun rig(block: TrashRig.() -> Unit = {}) = TrashRig().also { rig = it }.apply(block)

    // ---- delete and undo

    @Test fun deleteKeepsACopyInTheTrashAndRemovesTheContact() {
        val r = rig()
        val moved = r.wait { r.trash.delete(1) }
        assertEquals("Ayşe Yılmaz", (moved as MoveResult.Done).name)
        assertTrue(r.contacts().none { it.id == 1L })
        val entries = r.wait { r.trash.list()!!.entries }
        assertEquals(1, entries.size)
        assertEquals("Ayşe", entries.single().draft.given)
        assertEquals("Deleted today", entries.single().leftText)
    }

    @Test fun undoRestoresTheSameFieldsAsANewContact() {
        val r = rig()
        val original = r.contacts().first { it.id == 1L }
        val key = (r.wait { r.trash.delete(1) } as MoveResult.Done).key
        val restored = r.wait { r.trash.restore(listOf(key)) } as RestoreResult.Done
        assertEquals("Contact restored", restored.notice)
        val back = r.contacts().single { it.id == restored.ids.single() }
        assertEquals(original.copy(id = back.id), back)
        assertTrue("the new contact has its own id", back.id != 1L)
        assertTrue("the copy left the Trash", r.wait { r.trash.list()!!.entries }.isEmpty())
    }

    @Test fun aRefusedDeleteTakesTheCopyBackOutOfTheTrash() {
        val r = rig()
        r.repo.failWith = ContactResult.Reason.Refused
        assertEquals(MoveResult.Gone, r.wait { r.trash.delete(99) })
        assertEquals(MoveResult.Refused, r.wait { r.trash.delete(1) })
        assertTrue(r.wait { r.trash.list()!!.entries }.isEmpty())
        r.repo.failWith = null
        assertTrue(r.contacts().any { it.id == 1L })
    }

    @Test fun aRefusedRestoreKeepsTheEntryInTheTrash() {
        val r = rig()
        val key = (r.wait { r.trash.delete(1) } as MoveResult.Done).key
        r.repo.failWith = ContactResult.Reason.Refused
        assertEquals(RestoreResult.Refused, r.wait { r.trash.restore(listOf(key)) })
        r.repo.failWith = null
        assertEquals(1, r.wait { r.trash.list()!!.entries }.size)
    }

    @Test fun restoringWhatIsNotThereIsSaid() {
        val r = rig()
        assertEquals(RestoreResult.Missing, r.wait { r.trash.restore(listOf(42)) })
        assertEquals(RestoreResult.Missing, r.wait { r.trash.restore(emptyList()) })
    }

    @Test fun aContactWithoutAnyNameIsStillCalledSomething() {
        val r = rig()
        r.repo.let { repo ->
            r.wait {
                repo.save(
                    dev.rovsti.core.model.contacts
                        .ContactDraft(note = "x"),
                )
            }
        }
        val id = r.contacts().last().id!!
        assertEquals("Contact", (r.wait { r.trash.delete(id) } as MoveResult.Done).name)
    }

    // ---- the page

    @Test fun rowsSayHowLongAgoAndHowManyDaysAreLeft() {
        val r = rig()
        r.now = SampleCards.NOW - 3 * DAY_MS
        r.wait { r.trash.delete(1) }
        r.now = SampleCards.NOW
        r.wait { r.trash.delete(2) }
        val model = r.model()
        val state = r.wait { model.state.first { it.phase == TrashPhase.Ready } }
        assertEquals(listOf("Burak Demir", "Ayşe Yılmaz"), state.rows.map { it.name })
        assertEquals("Deleted today", state.rows[0].detail)
        assertEquals("Deleted 3 days ago · 27 days left", state.rows[1].detail)
        assertEquals(30, state.retentionDays)
    }

    @Test fun oneDayIsSingular() {
        val r = rig()
        r.now = SampleCards.NOW - DAY_MS
        r.wait { r.trash.delete(1) }
        r.now = SampleCards.NOW
        val state = r.wait { r.model().state.first { it.phase == TrashPhase.Ready } }
        assertEquals("Deleted 1 day ago · 29 days left", state.rows.single().detail)
    }

    @Test fun entriesPastThirtyDaysAreHiddenAndDroppedFromTheFile() {
        val r = rig()
        r.now = SampleCards.NOW - 40 * DAY_MS
        r.wait { r.trash.delete(1) }
        r.now = SampleCards.NOW - 29 * DAY_MS
        r.wait { r.trash.delete(2) }
        r.now = SampleCards.NOW
        val state = r.wait { r.model().state.first { it.phase == TrashPhase.Ready } }
        assertEquals(listOf("Burak Demir"), state.rows.map { it.name })
        assertEquals("Deleted 29 days ago · 1 day left", state.rows.single().detail)
        // The pruning rewrote the file: even a store that ignores the clock sees one entry.
        r.now = SampleCards.NOW - 50 * DAY_MS
        assertEquals(1, r.wait { r.trash.list()!!.entries }.size)
    }

    @Test fun anEmptyTrashIsEmptyNotLoading() {
        val r = rig()
        val state = r.wait { r.model().state.first { it.phase == TrashPhase.Ready } }
        assertTrue(state.isEmpty)
    }

    @Test fun restoreFromThePageBringsTheContactBackAndSaysSo() {
        val r = rig()
        val key = (r.wait { r.trash.delete(1) } as MoveResult.Done).key
        val model = r.model()
        r.wait { model.state.first { it.rows.isNotEmpty() } }
        model.onAction(TrashAction.Restore(key))
        assertEquals(TrashEffect.Toast("Contact restored"), r.wait { model.effects.first() })
        assertTrue(r.wait { model.state.first { it.rows.isEmpty() } }.isEmpty)
        assertTrue(r.contacts().any { it.given == "Ayşe" })
    }

    @Test fun deleteNowAsksFirstWithTheCoresWordsAndThenDeletesForGood() {
        val r = rig()
        val key = (r.wait { r.trash.delete(1) } as MoveResult.Done).key
        val model = r.model()
        r.wait { model.state.first { it.rows.isNotEmpty() } }
        model.onAction(TrashAction.AskDelete(key))
        val ask = r.wait { model.effects.first() } as TrashEffect.Ask
        assertEquals("Delete forever?", ask.title)
        assertEquals("1 contact will be deleted for good. This can't be undone.", ask.body)
        assertEquals("Delete Now", ask.confirmLabel)
        assertEquals(TrashAction.ConfirmDelete(listOf(key)), ask.then)
        assertEquals("nothing is deleted before the answer", 1, r.wait { r.trash.list()!!.entries }.size)
        model.onAction(ask.then)
        r.wait { model.state.first { it.isEmpty } }
        assertTrue(r.contacts().none { it.id == 1L })
    }

    @Test fun emptyTrashAsksFirstAndThenEmpties() {
        val r = rig()
        r.wait { r.trash.delete(1) }
        r.wait { r.trash.delete(2) }
        val model = r.model()
        r.wait { model.state.first { it.rows.size == 2 } }
        model.onAction(TrashAction.AskEmpty)
        val ask = r.wait { model.effects.first() } as TrashEffect.Ask
        assertEquals("Empty Trash?", ask.title)
        assertEquals("2 contacts will be deleted for good. This can't be undone.", ask.body)
        assertEquals(TrashAction.ConfirmEmpty, ask.then)
        assertEquals(2, r.wait { r.trash.list()!!.entries }.size)
        model.onAction(ask.then)
        r.wait { model.state.first { it.isEmpty } }
        assertTrue(r.wait { r.trash.list()!!.entries }.isEmpty())
    }

    @Test fun emptyTrashOfAnEmptyTrashAsksNothing() {
        val r = rig()
        val model = r.model()
        r.wait { model.state.first { it.phase == TrashPhase.Ready } }
        model.onAction(TrashAction.AskEmpty)
        assertNull(r.wait { kotlinx.coroutines.withTimeoutOrNull(500) { model.effects.first() } })
        assertEquals(SampleCards.all.size, r.contacts().size)
    }
}
