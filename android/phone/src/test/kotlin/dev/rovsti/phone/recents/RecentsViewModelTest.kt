package dev.rovsti.phone.recents

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.phone.CallNote
import dev.rovsti.core.model.phone.Direction
import dev.rovsti.core.model.phone.NoteSetRequest
import dev.rovsti.core.model.phone.Notes
import dev.rovsti.core.model.phone.RecentsFilter
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.T0
import dev.rovsti.phone.data.eventually
import dev.rovsti.phone.data.logEntry
import dev.rovsti.phone.keypad.sim1
import dev.rovsti.phone.keypad.sim2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.3 Recents decisions on the REAL Rust core: filters, notes search, delete and block with Undo, select mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentsViewModelTest {
    private val host by lazy { HostRust("rovsti-recents-vm") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun mainDispatcher() =
        dev.rovsti.phone.UnconfinedMain
            .install()

    @After fun stop() = scope.cancel()

    private suspend fun rig(
        accounts: List<dev.rovsti.core.model.phone.PhoneAccountInfo> = listOf(sim1),
        defaultKey: String? = null,
        log: List<dev.rovsti.core.model.phone.CallLogEntry> = RecentsRig.sampleLog(),
    ) = RecentsRig(host, scope, log, accounts, defaultKey).also { it.ready() }

    /** Collects the state for the test's lifetime (the view model's flows only run with a subscriber). */
    private fun RecentsViewModel.watch() = state.onEach { }.launchIn(scope)

    @Test fun theRowsAreTheCoresRunsWithItsWordsAndDaySections() =
        runBlocking {
            val model = rig().model().also { it.watch() }
            val ui = model.loaded()
            assertEquals("two missed calls of one number are one row", 4, ui.rowCount)
            val first = ui.rows.first()
            assertEquals("Ayse Yilmaz", first.title)
            assertEquals("(2)", first.count)
            assertTrue(first.missed)
            assertEquals(Direction.Missed, first.direction)
            val header = ui.items.first() as RecentsItem.Header
            assertEquals("Today", header.label)
            assertEquals("the unknown German number shows its country", "Germany", ui.rows.first { !it.named }.country)
            assertNull("a named caller shows no country", first.country)
        }

    @Test fun filtersCutTheListAndTheChipsCountWhatTheyWouldShow() =
        runBlocking {
            val model = rig().model().also { it.watch() }
            val all = model.loaded()
            assertEquals(4, all.counts.all)
            assertEquals(1, all.counts.missed)
            assertEquals(2, all.counts.incoming)
            assertEquals(1, all.counts.outgoing)
            assertEquals(0, all.counts.whatsapp)
            model.onAction(RecentsAction.SetFilter(RecentsFilter.Missed))
            val missed = model.loaded { it.filter == RecentsFilter.Missed && it.rowCount == 1 }
            assertEquals("Ayse Yilmaz", missed.rows.single().title)
            model.onAction(RecentsAction.SetFilter(RecentsFilter.Outgoing))
            assertEquals(
                "Mert Kaya",
                model
                    .loaded { it.filter == RecentsFilter.Outgoing && it.rowCount == 1 }
                    .rows
                    .single()
                    .title,
            )
        }

    @Test fun anEmptyFilterSaysWhyThroughTheCore() =
        runBlocking {
            val model = rig().model().also { it.watch() }
            model.loaded()
            model.onAction(RecentsAction.SetFilter(RecentsFilter.WhatsApp))
            val ui = model.loaded { it.filter == RecentsFilter.WhatsApp && it.ready }
            assertEquals(0, ui.rowCount)
            assertFalse("the core's sentence", ui.emptyText.isNullOrBlank())
        }

    @Test fun theSearchTextFindsNamesAndTheNotesWrittenForCalls() =
        runBlocking {
            val rig = rig()
            val model = rig.model().also { it.watch() }
            model.loaded()
            model.onAction(RecentsAction.SetQuery("mert"))
            assertEquals(
                "Mert Kaya",
                model
                    .loaded { it.query == "mert" && it.rowCount == 1 }
                    .rows
                    .single()
                    .title,
            )
            // A note on Deniz's call: the search finds her by the note, and the "With notes" chip counts it.
            val set =
                rig.ops.noteSet(
                    NoteSetRequest(Notes(1, emptyList()), "+905553334455", T0 - 1_500 * 60_000, "bring the pizza", T0),
                )
            rig.store.updateNotes { (set as Outcome.Ok).value.notes }
            model.onAction(RecentsAction.SetQuery("pizza"))
            val found = model.loaded { it.query == "pizza" && it.rowCount == 1 }
            assertEquals("Deniz Aksoy", found.rows.single().title)
            model.onAction(RecentsAction.SetQuery(""))
            model.onAction(RecentsAction.SetFilter(RecentsFilter.WithNotes))
            assertEquals(
                "Deniz Aksoy",
                model
                    .loaded {
                        it.filter == RecentsFilter.WithNotes && it.rowCount == 1
                    }.rows
                    .single()
                    .title,
            )
        }

    @Test fun deleteTakesTheCallsOutAndUndoPutsThemBack() =
        runBlocking {
            val rig = rig()
            val model = rig.model().also { it.watch() }
            val ui = model.loaded()
            model.onAction(RecentsAction.Delete(listOf(ui.rows.first().key)))
            val pill = model.nextEffect<RecentsEffect.Undoable>()
            assertEquals(UndoKind.Deleted, pill.kind)
            val after = model.loaded { it.ready && it.rowCount == 3 }
            assertTrue(after.rows.none { it.title == "Ayse Yilmaz" })
            model.onAction(RecentsAction.Undo(pill.undo))
            val back = model.loaded { it.rowCount == 4 }
            assertEquals(
                "both missed calls came back as one row",
                "(2)",
                back.rows.first { it.title == "Ayse Yilmaz" }.count,
            )
        }

    @Test fun blockAsksFirstThenBlocksAndUndoUnblocks() =
        runBlocking {
            val rig = rig()
            val model = rig.model().also { it.watch() }
            val ui = model.loaded()
            val unknown = ui.rows.first { !it.named }
            model.onAction(RecentsAction.AskBlock(listOf(unknown.key)))
            val ask = model.nextEffect<RecentsEffect.ConfirmBlock>()
            assertEquals(listOf("+4930123456"), ask.numbers)
            assertTrue(
                "nothing is blocked before the confirmation",
                rig.blocklist
                    .numbers()
                    .first()
                    .isEmpty(),
            )
            model.onAction(RecentsAction.ConfirmBlock(ask.numbers))
            val pill = model.nextEffect<RecentsEffect.Undoable>()
            assertEquals(UndoKind.Blocked, pill.kind)
            assertEquals(
                1,
                rig.blocklist
                    .numbers()
                    .first()
                    .size,
            )
            model.onAction(RecentsAction.Undo(pill.undo))
            eventually("unblocked") {
                runBlocking {
                    rig.blocklist
                        .numbers()
                        .first()
                        .isEmpty()
                }
            }
        }

    @Test fun blockingWithoutTheDefaultPhoneRoleSaysSo() =
        runBlocking {
            val rig = rig()
            rig.blocklist.allowed = false
            val model = rig.model().also { it.watch() }
            model.loaded()
            model.onAction(RecentsAction.ConfirmBlock(listOf("+4930123456")))
            assertEquals(RecentsNotice.NotDefaultDialer, model.nextEffect<RecentsEffect.Say>().notice)
        }

    @Test fun selectModeDeletesSeveralRowsWithOneUndo() =
        runBlocking {
            val rig = rig()
            val model = rig.model().also { it.watch() }
            val ui = model.loaded()
            model.onAction(RecentsAction.EnterSelect)
            model.loaded { it.select != null }
            model.onAction(RecentsAction.Toggle(ui.rows[0].key))
            model.onAction(RecentsAction.Toggle(ui.rows[1].key))
            val two = model.loaded { it.select?.selected?.size == 2 }
            assertFalse(two.select!!.allSelected)
            model.onAction(RecentsAction.DeleteSelected)
            val pill = model.nextEffect<RecentsEffect.Undoable>()
            assertEquals("one pill for both rows", 2, pill.count)
            val after = model.loaded { it.rowCount == 2 }
            assertEquals("select mode stays with nothing chosen", 0, after.select?.selected?.size)
            model.onAction(RecentsAction.Undo(pill.undo))
            model.loaded { it.rowCount == 4 }
            model.onAction(RecentsAction.ToggleAll)
            assertTrue(
                model
                    .loaded { it.select?.allSelected == true }
                    .select!!
                    .selected.size == 4,
            )
            model.onAction(RecentsAction.ExitSelect)
            assertNull(model.loaded { it.select == null }.select)
        }

    @Test fun remindMeLaterOffersTheCoresSlotsAndArmsOne() =
        runBlocking {
            val rig = rig()
            val model = rig.model().also { it.watch() }
            val ui = model.loaded()
            val key = ui.rows.first().key
            model.onAction(RecentsAction.AskRemind(key))
            val choose = model.nextEffect<RecentsEffect.ChooseRemind>()
            assertTrue(choose.slots.size >= 2)
            model.onAction(RecentsAction.Remind(key, choose.slots.first().key))
            val done = model.nextEffect<RecentsEffect.Reminded>()
            assertTrue(done.text.startsWith("Reminder"))
            assertEquals(1, rig.reminders.armed.size)
            assertTrue(
                rig.reminders.armed
                    .single()
                    .contains("\"number\":\"+905551112233\""),
            )
        }

    @Test fun openingRecentsMarksTheMissedCallsSeen() =
        runBlocking {
            val rig = rig()
            val model =
                rig.model().also {
                    it.watch()
                    it.missedCount.onEach { }.launchIn(scope)
                }
            model.loaded()
            eventually("badge") { model.missedCount.value == 2 }
            model.onAction(RecentsAction.Opened)
            eventually("seen") { rig.callLog.markedRead == 1 && rig.store.prefs.value.ui.missedSeenMs == T0 + 60_000 }
            eventually("badge gone") { model.missedCount.value == 0 }
        }

    @Test fun callBackPlacesTheCallAndTwoSimsAsk() =
        runBlocking {
            val one = rig().also { it.model().also { m -> m.watch() } }
            val m1 = one.model().also { it.watch() }
            val u1 = m1.loaded()
            m1.onAction(RecentsAction.CallBack(u1.rows[1].key))
            eventually("placed") { one.launcher.placed.isNotEmpty() }
            assertEquals(
                "+905559998877",
                one.launcher.placed
                    .single()
                    .first,
            )

            val two = rig(accounts = listOf(sim1, sim2))
            val m2 = two.model().also { it.watch() }
            val u2 = m2.loaded()
            m2.onAction(RecentsAction.CallBack(u2.rows[1].key))
            val choose = m2.nextEffect<RecentsEffect.ChooseSim>()
            assertEquals(2, choose.choices.size)
            m2.onAction(RecentsAction.CallOn(choose.number, choose.choices[1].key))
            eventually("placed on sim 2") { two.launcher.placed.isNotEmpty() }
            assertEquals(
                choose.choices[1].key,
                two.launcher.placed
                    .single()
                    .second,
            )
        }

    @Test fun aTwoSimPhoneMarksEachRowWithItsSim() =
        runBlocking {
            val probe = rig(accounts = listOf(sim1, sim2))
            val key =
                probe.simState.sims.value.chips[1]
                    .key
            val log = RecentsRig.sampleLog().map { if (it.id == 3L) it.copy(accountKey = key) else it }
            val rig = rig(accounts = listOf(sim1, sim2), log = log)
            val model = rig.model().also { it.watch() }
            val ui = model.loaded { it.dualSim && it.rowCount > 0 }
            val chip = ui.rows.first { it.title == "Mert Kaya" }.sim
            assertNotNull(chip)
            assertTrue(ui.rows.first { it.title == "Ayse Yilmaz" }.sim == null)
        }

    @Test fun copyNumberAndCopyWithCountryUseTheClipboard() =
        runBlocking {
            val rig = rig()
            val model = rig.model().also { it.watch() }
            val ui = model.loaded()
            val mert = ui.rows.first { it.title == "Mert Kaya" }.key
            model.onAction(RecentsAction.CopyNumber(mert, withCountry = false))
            assertEquals(RecentsNotice.Copied, model.nextEffect<RecentsEffect.Say>().notice)
            assertEquals("+905559998877", rig.clipboard.text)
            model.onAction(RecentsAction.CopyNumber(mert, withCountry = true))
            assertEquals(RecentsNotice.CopiedWithCountry, model.nextEffect<RecentsEffect.Say>().notice)
            assertTrue(rig.clipboard.text!!.startsWith("+90"))
        }

    // ---- WhatsApp calls (E5.8b) ------------------------------------------------------------------------------------

    private fun waCall(
        id: String = "c9",
        missed: Boolean = true,
    ) = dev.rovsti.core.model.phone.ExtraCall(
        numberRaw = "+905550001122",
        startedMs = T0 - 10_000,
        durationS = 0,
        missed = missed,
        outgoing = false,
        label = "Wa Person",
        callId = id,
    )

    @Test fun aMissedWhatsAppCallIsARowWithTheWhatsAppFlagAndCallsBackOverWhatsApp() =
        runBlocking {
            val rig = rig()
            val model = rig.model().also { it.watch() }
            model.loaded()
            rig.waExtras.value = listOf(waCall())
            val ui = model.loaded { it.rows.any { r -> r.title == "Wa Person" } }
            val row = ui.rows.first { it.title == "Wa Person" }
            assertTrue("missed WhatsApp calls show in Recents", row.missed)
            assertEquals(1, ui.counts.whatsapp)
            model.onAction(RecentsAction.WhatsAppCall(row.key))
            assertEquals(listOf("+905550001122"), rig.whatsAppPlaced)
            assertTrue("the model says WhatsApp can be placed", model.whatsAppAvailable.value)
        }

    @Test fun deletingAWhatsAppRowForgetsItsCallAndLeavesTheLogAlone() =
        runBlocking {
            val rig = rig()
            val model = rig.model().also { it.watch() }
            model.loaded()
            rig.waExtras.value = listOf(waCall())
            val ui = model.loaded { it.rows.any { r -> r.title == "Wa Person" } }
            val row = ui.rows.first { it.title == "Wa Person" }
            model.onAction(RecentsAction.Delete(listOf(row.key)))
            assertEquals(listOf(listOf("c9")), rig.whatsAppForgotten)
            assertEquals(
                "no log rows were deleted",
                5,
                rig.callLog
                    .entries()
                    .first()
                    .size,
            )
        }
}
