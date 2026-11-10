package dev.rovsti.phone.details

import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.eventually
import dev.rovsti.phone.keypad.sim1
import dev.rovsti.phone.keypad.sim2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.4 Contact and Number sheet decisions on the REAL Rust core. */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonSheetViewModelTest {
    private val host by lazy { HostRust("rovsti-person-vm") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun mainDispatcher() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun stop() = scope.cancel()

    private suspend fun rig(accounts: List<dev.rovsti.core.model.phone.PhoneAccountInfo> = listOf(sim1)) =
        DetailsRig(host, scope, accounts = accounts).also { it.ready() }

    @Test fun theSheetShowsTheNumbersTheHistoryAndTheTotalsOfOnePerson() =
        runBlocking {
            val ui = rig().person(Ayse).loaded()
            assertEquals("Ayse Yilmaz", ui.title)
            assertTrue(ui.named)
            assertEquals(2, ui.numbers.size)
            assertEquals("mobile", ui.numbers[0].label)
            assertEquals("two missed calls of Ayse and nothing else", 2, ui.history.size)
            assertTrue(ui.history.all { it.missed })
            assertTrue(
                "the core worded the line",
                ui.history
                    .first()
                    .title
                    .isNotBlank(),
            )
            val stats = ui.stats!!
            assertEquals(2, stats.calls)
            assertEquals(2, stats.missed)
            assertEquals(0, stats.incoming)
            assertTrue("the core's sentences follow the header line", stats.lines.isNotEmpty())
        }

    @Test fun anUnknownNumberIsTheNumberSheetWithItsCountry() =
        runBlocking {
            val ui = rig().person(Stranger).loaded()
            assertFalse(ui.named)
            assertEquals("Germany", ui.subtitle)
            assertEquals(1, ui.history.size)
            assertEquals(
                "the title is the number the core wrote",
                ui.numbers
                    .single()
                    .display,
                ui.title,
            )
        }

    @Test fun aPersonWithoutCallsHasNoHistoryAndNoStats() =
        runBlocking {
            val model = rig().person(Nobody)
            val ui = model.loaded { it.ready }
            assertTrue(ui.history.isEmpty())
            assertNull(ui.stats)
        }

    @Test fun callingGoesThroughThePlacerAndHoldingAsksForASimWhenThereAreTwo() =
        runBlocking {
            val rig = rig(listOf(sim1, sim2))
            val model = rig.person(Ayse)
            model.loaded()
            eventually("two SIM chips") { rig.base.simState.sims.value.visible }
            model.onAction(PersonAction.AskSim("+905551112233"))
            val choose = model.nextEffect<PersonEffect.ChooseSim>()
            assertEquals(2, choose.choices.size)
            model.onAction(PersonAction.CallOn(choose.number, choose.choices.last().key))
            eventually("the call was placed") {
                rig.base.launcher.placed
                    .isNotEmpty()
            }
            assertEquals(
                "+905551112233" to sim2.key,
                rig.base.launcher.placed
                    .single(),
            )
        }

    @Test fun aSingleSimCallsAtOnceAndAHoldDoesNotAskTheresNothingToChoose() =
        runBlocking {
            val rig = rig()
            val model = rig.person(Ayse)
            model.loaded()
            model.onAction(PersonAction.AskSim("+902125550101"))
            eventually("the call was placed") {
                rig.base.launcher.placed
                    .isNotEmpty()
            }
            assertEquals(
                "+902125550101",
                rig.base.launcher.placed
                    .single()
                    .first,
            )
        }

    @Test fun copyAllPutsEveryNumberOnTheClipboardOnePerLine() =
        runBlocking {
            val rig = rig()
            val model = rig.person(Ayse)
            val ui = model.loaded()
            model.onAction(PersonAction.CopyAll)
            val copied = rig.base.clipboard.text!!
            assertEquals(ui.numbers.map { it.display }, copied.lines())
            assertEquals(PersonNotice.CopiedAll, model.nextEffect<PersonEffect.Say>().notice)
        }

    @Test fun shareDetailsAndShareStatsHandTheTextToTheHost() =
        runBlocking {
            val model = rig().person(Ayse)
            val ui = model.loaded()
            model.onAction(PersonAction.ShareDetails)
            val details = model.nextEffect<PersonEffect.ShareText>().text
            assertTrue(details.startsWith("Ayse Yilmaz"))
            assertTrue(ui.numbers.all { it.display in details })
            model.onAction(PersonAction.ShareStats)
            val stats = model.nextEffect<PersonEffect.ShareText>().text
            assertTrue("the core's sentence", stats.startsWith("Calls with"))
        }

    @Test fun theQrCodeIsTheVCardTheCoreFittedAndTheModulesItEncoded() =
        runBlocking {
            val model = rig().person(Ayse)
            model.loaded()
            model.onAction(PersonAction.ShowQr)
            val qr = model.nextEffect<PersonEffect.ShowQr>().qr
            assertEquals("Ayse Yilmaz", qr.name)
            assertTrue(qr.payload.contains("BEGIN:VCARD"))
            assertTrue(qr.matrix.side >= 21)
            assertTrue("a code has dark modules", qr.matrix.darkCount > 0)
        }

    @Test fun exportingWritesThePersonsCallsAsCsvAndNobodyHasNothingToExport() =
        runBlocking {
            val rig = rig()
            val model = rig.person(Ayse)
            model.loaded()
            model.onAction(PersonAction.ExportCsv)
            val csv = model.nextEffect<PersonEffect.SaveCsv>()
            assertTrue(csv.fileName.endsWith(".csv"))
            assertTrue(csv.csv.lines().size >= 3)
            val empty = rig.person(Nobody)
            empty.loaded { it.ready }
            empty.onAction(PersonAction.ExportCsv)
            assertEquals(PersonNotice.NoCallsToExport, empty.nextEffect<PersonEffect.Say>().notice)
        }

    @Test fun quickReplyListsTheSavedRepliesAndSendsTheChosenOne() =
        runBlocking {
            val model = rig().person(Ayse)
            model.loaded()
            model.onAction(PersonAction.AskQuickReply)
            val choices = model.nextEffect<PersonEffect.ChooseReply>().choices
            assertTrue(choices.isNotEmpty())
            model.onAction(PersonAction.QuickReply(choices.first()))
            val sent = model.nextEffect<PersonEffect.SendMessage>()
            assertEquals("+905551112233", sent.number)
            assertEquals(choices.first(), sent.body)
        }

    @Test fun deletingTheHistoryAsksFirstThenDeletesWithOneUndoThatPutsTheCallsBack() =
        runBlocking {
            val rig = rig()
            val model = rig.person(Ayse)
            model.loaded()
            model.onAction(PersonAction.AskDeleteHistory)
            val ask = model.nextEffect<PersonEffect.ConfirmDeleteHistory>()
            assertEquals(2, ask.calls)
            assertEquals(
                "asking changes nothing",
                2,
                rig.base.callLog
                    .snapshot()
                    .count { it.contactId == 1L },
            )
            model.onAction(PersonAction.ConfirmDeleteHistory)
            val undoable = model.nextEffect<PersonEffect.Undoable>()
            assertEquals(PersonUndoKind.HistoryDeleted, undoable.kind)
            assertEquals(2, undoable.count)
            model.loaded { it.ready && it.history.isEmpty() }
            assertEquals(
                0,
                rig.base.callLog
                    .snapshot()
                    .count { it.contactId == 1L },
            )
            model.onAction(PersonAction.Undo(undoable.undo))
            val back = model.loaded { it.history.size == 2 }
            assertEquals(2, back.history.size)
        }

    @Test fun blockingAsksFirstThenBlocksEveryNumberWithAnUndo() =
        runBlocking {
            val rig = rig()
            val model = rig.person(Stranger)
            model.loaded()
            model.onAction(PersonAction.AskBlock)
            assertNotNull(model.nextEffect<PersonEffect.ConfirmBlock>())
            model.onAction(PersonAction.ConfirmBlock)
            val undoable = model.nextEffect<PersonEffect.Undoable>()
            assertEquals(PersonUndoKind.Blocked, undoable.kind)
            assertTrue(rig.base.blocklist.isBlocked("+4930123456"))
            model.onAction(PersonAction.Undo(undoable.undo))
            eventually("unblocked") { !runBlocking { rig.base.blocklist.isBlocked("+4930123456") } }
        }

    @Test fun aHistoryLineOpensItsCallDetails() =
        runBlocking {
            val model = rig().person(Ayse)
            val ui = model.loaded()
            model.onAction(PersonAction.OpenCall(ui.history.first().key))
            val open = model.nextEffect<PersonEffect.OpenCall>()
            assertEquals(ui.history.first().line, open.line)
            assertEquals("Ayse Yilmaz", open.who)
        }

    @Test fun aNewCallWhileTheSheetIsOpenAppearsInItsHistory() =
        runBlocking {
            val rig = rig()
            val model = rig.person(Ayse)
            model.loaded()
            rig.base.callLog.restore(
                listOf(
                    dev.rovsti.phone.data.logEntry(
                        90,
                        "+905551112233",
                        CallLogKind.Incoming,
                        ago = 1,
                        name = "Ayse Yilmaz",
                        contactId = 1,
                    ),
                ),
            )
            val ui = model.loaded { it.history.size == 3 }
            assertEquals(1, ui.stats!!.incoming)
        }
}
