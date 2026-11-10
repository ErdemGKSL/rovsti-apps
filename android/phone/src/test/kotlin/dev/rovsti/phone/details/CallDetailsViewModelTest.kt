package dev.rovsti.phone.details

import dev.rovsti.core.model.phone.CallType
import dev.rovsti.core.model.phone.EntryRef
import dev.rovsti.core.model.phone.HistoryLine
import dev.rovsti.phone.HostRust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * E5.4 Call details decisions on the REAL Rust core: the core's words, the note with Undo, delete with Undo,
 * remind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallDetailsViewModelTest {
    private val host by lazy { HostRust("rovsti-call-vm") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun mainDispatcher() =
        dev.rovsti.phone.UnconfinedMain
            .install()

    @After fun stop() = scope.cancel()

    /** The model of Ayse's newest call and the rig it runs on. */
    private suspend fun rig(): Pair<DetailsRig, CallDetailsViewModel> {
        val rig = DetailsRig(host, scope).also { it.ready() }
        val line =
            rig
                .person(Ayse)
                .loaded()
                .history
                .first()
                .line
        return rig to rig.call(CallDetailsTarget(line, "Ayse Yilmaz"))
    }

    @Test fun theSheetSaysWhatTheCoreSaysAboutTheCall() =
        runBlocking {
            val (_, model) = rig()
            val ui = model.loaded()
            assertTrue(ui.title.isNotBlank())
            assertTrue(ui.whenText.isNotBlank())
            assertTrue(ui.number.isNotBlank())
            assertTrue(ui.deletable)
            assertEquals("", ui.note)
        }

    @Test fun copyPutsTheCoresTextOnTheClipboardAndShareHandsItsShareText() =
        runBlocking {
            val (rig, model) = rig()
            val ui = model.loaded()
            model.onAction(CallDetailsAction.Copy)
            assertEquals(ui.copyText, rig.base.clipboard.text)
            assertEquals(CallDetailsNotice.Copied, model.nextEffect<CallDetailsEffect.Say>().notice)
            model.onAction(CallDetailsAction.Share)
            val share = model.nextEffect<CallDetailsEffect.ShareText>().text
            assertTrue("the share text names the person", share.startsWith("Call with Ayse Yilmaz"))
        }

    @Test fun aSavedNoteIsStoredAndComesBackInTheCopyText() =
        runBlocking {
            val (rig, model) = rig()
            model.loaded()
            model.onAction(CallDetailsAction.SaveNote("  Ask about the invoice  "))
            assertEquals(CallDetailsNotice.NoteSaved, model.nextEffect<CallDetailsEffect.Say>().notice)
            val ui = model.loaded { it.note.isNotEmpty() }
            assertEquals("the core trims the note", "Ask about the invoice", ui.note)
            assertTrue(ui.copyText.contains("Ask about the invoice"))
            assertEquals(1, rig.base.store.notes.value.notes.size)
        }

    @Test fun deletingTheNoteHasAnUndoThatPutsTheTextBack() =
        runBlocking {
            val (_, model) = rig()
            model.loaded()
            model.onAction(CallDetailsAction.SaveNote("Call the bank"))
            model.loaded { it.note == "Call the bank" }
            model.onAction(CallDetailsAction.DeleteNote)
            val undoable = model.nextEffect<CallDetailsEffect.Undoable>()
            assertEquals(CallDetailsUndoKind.NoteDeleted, undoable.kind)
            model.loaded { it.note.isEmpty() && it.ready }
            model.onAction(CallDetailsAction.Undo(undoable.undo))
            assertEquals("Call the bank", model.loaded { it.note == "Call the bank" }.note)
        }

    @Test fun savingABlankNoteRemovesTheNote() =
        runBlocking {
            val (_, model) = rig()
            model.loaded()
            model.onAction(CallDetailsAction.SaveNote("keep"))
            model.loaded { it.note == "keep" }
            model.onAction(CallDetailsAction.SaveNote("   "))
            model.nextEffect<CallDetailsEffect.Undoable>()
            assertEquals("", model.loaded { it.note.isEmpty() }.note)
        }

    @Test fun deletingTheCallClosesTheSheetAndUndoRestoresTheCall() =
        runBlocking {
            val (rig, model) = rig()
            model.loaded()
            val before =
                rig.base.callLog
                    .snapshot()
                    .size
            model.onAction(CallDetailsAction.DeleteCall)
            val undoable = model.nextEffect<CallDetailsEffect.Undoable>()
            assertEquals(CallDetailsUndoKind.CallDeleted, undoable.kind)
            model.nextEffect<CallDetailsEffect.Close>()
            assertEquals(
                before - 1,
                rig.base.callLog
                    .snapshot()
                    .size,
            )
            model.onAction(CallDetailsAction.Undo(undoable.undo))
            kotlinx.coroutines.delay(100)
            assertEquals(
                before,
                rig.base.callLog
                    .snapshot()
                    .size,
            )
        }

    @Test fun aWhatsAppCallIsNotDeletedFromTheLog() =
        runBlocking {
            val rig = DetailsRig(host, scope).also { it.ready() }
            val line =
                HistoryLine(
                    EntryRef.WhatsApp("wa-1"),
                    blocked = false,
                    callType = CallType.Missed,
                    whatsapp = true,
                    dateMs = 1_700_000_000_000,
                    durationS = 0,
                    number = "+905551112233",
                )
            val model = rig.call(CallDetailsTarget(line, "Ayse Yilmaz"))
            assertFalse(model.loaded().deletable)
            model.onAction(CallDetailsAction.DeleteCall)
            assertEquals(CallDetailsNotice.NothingToDelete, model.nextEffect<CallDetailsEffect.Say>().notice)
        }

    @Test fun remindMeOffersTheCoresSlotsAndArmsTheChosenOne() =
        runBlocking {
            val (rig, model) = rig()
            model.loaded()
            model.onAction(CallDetailsAction.AskRemind)
            val slots = model.nextEffect<CallDetailsEffect.ChooseRemind>().slots
            assertTrue(slots.isNotEmpty())
            model.onAction(CallDetailsAction.Remind(slots.first().key))
            val set = model.nextEffect<CallDetailsEffect.Reminded>().text
            assertTrue(set.startsWith("Reminder"))
            assertEquals(1, rig.base.reminders.armed.size)
        }
}
