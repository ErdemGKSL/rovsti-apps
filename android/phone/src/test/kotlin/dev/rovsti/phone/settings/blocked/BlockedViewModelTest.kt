package dev.rovsti.phone.settings.blocked

import dev.rovsti.core.model.phone.BlockReason
import dev.rovsti.core.model.phone.BlockedNumber
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.phone.DateOrder
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.TimeFormat
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.UnconfinedMain
import dev.rovsti.phone.data.BlockRepo
import dev.rovsti.phone.data.T0
import dev.rovsti.phone.data.eventually
import dev.rovsti.phone.data.logEntry
import dev.rovsti.platform.calllog.BlockPrefix
import dev.rovsti.platform.calllog.FakeBlocklistRepository
import dev.rovsti.platform.calllog.FakeCallLogRepository
import dev.rovsti.platform.calllog.MemoryPrefixStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * E5.9a: the Blocked numbers page's logic on the REAL core (`blocklist.lines`, `parse_many`, `parse_file`, `check`,
 * `recents.blocked_history`) with an in-memory system blocklist: the paste preview, a prefix range, unblock with Undo,
 * a file import, Unblock all with Undo, and the Blocked calls list.
 */
class BlockedViewModelTest {
    private val host by lazy { HostRust("rovsti-blocked-vm") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locale = LocaleTime(0, TimeFormat(hour12 = false, dateOrder = DateOrder.DayMonthYear))

    @Before fun mainDispatcher() = UnconfinedMain.install()

    @After fun stop() = scope.cancel()

    private class Rig(
        val model: BlockedViewModel,
        val fake: FakeBlocklistRepository,
        val repo: BlockRepo,
        val log: FakeCallLogRepository,
        val prefixes: MemoryPrefixStore,
    )

    private fun rig(
        rows: List<BlockedNumber> = emptyList(),
        calls: FakeCallLogRepository = FakeCallLogRepository(),
        allowed: Boolean = true,
    ): Rig {
        val fake = FakeBlocklistRepository(rows, allowed = allowed)
        val prefixes = MemoryPrefixStore()
        val ops = NativePhoneOps(host.core)
        val repo = BlockRepo(fake, ops, scope, clock = { T0 }, prefixStore = prefixes)
        val model = BlockedViewModel(repo, ops, calls, { locale }, { T0 })
        model.state.onEach { }.launchIn(scope)
        return Rig(model, fake, repo, calls, prefixes)
    }

    private suspend fun Rig.ui(
        what: String,
        check: (BlockedUi) -> Boolean,
    ): BlockedUi {
        eventually(what) { check(model.state.value) }
        return model.state.value
    }

    private suspend fun Rig.next(): BlockedEffect = withTimeout(10_000) { model.effect.first() }

    private val known = BlockedNumber(1, "+49 151 2345678", "+491512345678")

    @Test fun theListShowsOneFormattedRowPerBlockedNumber() =
        runBlocking<Unit> {
            val r = rig(listOf(known, BlockedNumber(2, "0850 333 44 55", "08503334455")))
            val ui = r.ui("two rows") { it.ready && it.numbers.size == 2 }
            // The core keeps the digits of a non-Turkish number as they are (AD-11); only Turkish layouts are grouped.
            assertEquals("+491512345678", ui.numbers[0].title)
            assertNull("a bare number has no second line", ui.numbers[0].subtitle)
            assertEquals(listOf("+49 151 2345678"), ui.numbers[0].numbers)
            assertTrue(ui.canWrite)
        }

    @Test fun pastingManyShowsTheCoresPreviewThenBlocksTheNew() =
        runBlocking<Unit> {
            val r = rig(listOf(known))
            r.ui("ready") { it.ready }
            val text = "Spam: 0532 111 22 33, +49 151 2345678, 112\nand 0532-111-22-33 again, +90 850 333 44 55"
            r.model.onAction(BlockedAction.PasteTyped(text))
            val ui = r.ui("preview") { it.paste?.found == true }
            val preview = ui.paste!!
            assertEquals("two new numbers (the repeat counts once)", 2, preview.toBlock)
            assertEquals("112 is never blocked", 1, preview.emergency)
            assertEquals("one is on the list", 1, preview.alreadyBlocked)

            r.model.onAction(BlockedAction.PasteConfirm)
            val effects = listOf(r.next(), r.next())
            assertTrue("the sheet closes", effects.any { it === BlockedEffect.CloseSheet })
            val pill = effects.filterIsInstance<BlockedEffect.Undoable>().single()
            assertEquals(BlockedUndoKind.Blocked, pill.kind)
            assertEquals(2, pill.count)
            r.ui("three rows") { it.numbers.size == 3 }
            assertNull("the paste is reset", r.model.state.value.paste)
        }

    @Test fun pastingOnlyEmergencyNumbersIsRefused() =
        runBlocking<Unit> {
            val r = rig()
            r.model.onAction(BlockedAction.PasteTyped("112, 911"))
            r.ui("preview") { it.paste != null }
            r.model.onAction(BlockedAction.PasteConfirm)
            assertEquals(BlockedEffect.Say(BlockedNotice.EmergencyOnly), r.next())
            assertTrue(
                r.fake
                    .numbers()
                    .first()
                    .isEmpty(),
            )
        }

    @Test fun aPrefixRangeIsAddedAndCoversNumbersThatStartWithIt() =
        runBlocking<Unit> {
            val r = rig()
            r.model.onAction(BlockedAction.PrefixTyped("+90 (850)"))
            val ui = r.ui("preview") { it.prefix?.display != null }
            assertEquals("+90 850", ui.prefix!!.display)
            assertNull(ui.prefix!!.problem)

            r.model.onAction(BlockedAction.PrefixConfirm)
            val effects = listOf(r.next(), r.next())
            val pill = effects.filterIsInstance<BlockedEffect.Undoable>().single()
            assertEquals(BlockedUndoKind.RangeAdded, pill.kind)
            val shown = r.ui("range row") { it.ranges.size == 1 }
            assertEquals("+90 850 *", shown.ranges[0].title)
            assertTrue(BlockPrefix.matches(BlockPrefix.key("+90 850"), "+90 850 333 44 55"))
            assertTrue(BlockPrefix.matches(BlockPrefix.key("+90 850"), "00908503334455"))
            assertFalse(BlockPrefix.matches(BlockPrefix.key("+90 850"), "0850 333 44 55"))
            assertTrue(
                "a national range matches the national form",
                BlockPrefix.matches(BlockPrefix.key("0850"), "0850 333 44 55"),
            )

            // Undo takes the range off again.
            r.model.onAction(BlockedAction.Undo(pill.undo))
            r.ui("range gone") { it.ranges.isEmpty() }
        }

    @Test fun aPrefixThatIsAnEmergencyNumberOrTooShortIsRefused() =
        runBlocking<Unit> {
            val r = rig()
            r.model.onAction(BlockedAction.PrefixTyped("112"))
            assertEquals(
                PrefixProblem.Emergency,
                r.ui("emergency") { it.prefix?.problem == PrefixProblem.Emergency }.prefix!!.problem,
            )
            r.model.onAction(BlockedAction.PrefixTyped("85"))
            assertEquals(
                PrefixProblem.TooShort,
                r.ui("short") { it.prefix?.problem == PrefixProblem.TooShort }.prefix!!.problem,
            )
            r.model.onAction(BlockedAction.PrefixTyped("abc"))
            assertEquals(
                PrefixProblem.Invalid,
                r.ui("invalid") { it.prefix?.problem == PrefixProblem.Invalid }.prefix!!.problem,
            )
        }

    @Test fun anEmergencyPrefixIsNotAddedByTheRepo() =
        runBlocking<Unit> {
            val r = rig()
            assertEquals(dev.rovsti.phone.data.PrefixResult.Emergency, r.repo.addPrefix("112"))
            assertTrue(r.prefixes.all().isEmpty())
        }

    @Test fun unblockingAnEntryAndUndoPutsItBack() =
        runBlocking<Unit> {
            val r = rig(listOf(known))
            val key = r.ui("row") { it.numbers.size == 1 }.numbers[0].key
            r.model.onAction(BlockedAction.Unblock(key))
            val pill = r.next() as BlockedEffect.Undoable
            assertEquals(BlockedUndoKind.Unblocked, pill.kind)
            r.ui("row gone") { it.numbers.isEmpty() }
            r.model.onAction(BlockedAction.Undo(pill.undo))
            r.ui("row back") { it.numbers.size == 1 }
        }

    @Test fun importingTheFixtureBlocksTheNewNumbersAndSkipsTheRest() =
        runBlocking<Unit> {
            val r = rig(listOf(known))
            r.ui("ready") { it.ready }
            val text = File("src/test/resources/blocked-import.csv").readText()
            r.model.onAction(BlockedAction.Import(text))
            val pill = r.next() as BlockedEffect.Undoable
            assertEquals("0532 and the 0850 number are new; Known is on the list; 112 never", 2, pill.count)
            r.ui("three rows") { it.numbers.size == 3 }
            // Importing the same file again has nothing new.
            r.model.onAction(BlockedAction.Import(text))
            assertEquals(BlockedEffect.Say(BlockedNotice.NothingNew), r.next())
            // Undo unblocks the two again.
            r.model.onAction(BlockedAction.Undo(pill.undo))
            r.ui("back to one") { it.numbers.size == 1 }
        }

    @Test fun unblockAllAsksFirstThenClearsNumbersAndRangesAndUndoRestoresBoth() =
        runBlocking<Unit> {
            val r = rig(listOf(known, BlockedNumber(2, "0850 333 44 55", "08503334455")))
            r.ui("two rows") { it.numbers.size == 2 }
            r.repo.addPrefix("+90 850")
            r.ui("range") { it.ranges.size == 1 }
            r.model.onAction(BlockedAction.AskUnblockAll)
            assertEquals(BlockedEffect.ConfirmUnblockAll(3), r.next())
            assertEquals("nothing changed yet", 2, r.model.state.value.numbers.size)

            r.model.onAction(BlockedAction.ConfirmUnblockAll)
            val pill = r.next() as BlockedEffect.Undoable
            assertEquals(3, pill.count)
            r.ui("empty") { it.isEmpty }

            r.model.onAction(BlockedAction.Undo(pill.undo))
            r.ui("all back") { it.numbers.size == 2 && it.ranges.size == 1 }
        }

    @Test fun shareAndExportComeFromTheCoresTextAndCsv() =
        runBlocking<Unit> {
            val r = rig(listOf(known))
            r.ui("row") { it.numbers.size == 1 }
            r.model.onAction(BlockedAction.Share)
            val share = r.next() as BlockedEffect.Share
            assertTrue(share.text.startsWith("Blocked numbers"))
            r.model.onAction(BlockedAction.Export)
            val export = r.next() as BlockedEffect.Export
            assertTrue(export.csv.startsWith("number,name"))
            assertTrue(export.fileName.endsWith(".csv"))
        }

    @Test fun anEmptyListSharesAndExportsNothing() =
        runBlocking<Unit> {
            val r = rig()
            r.ui("ready") { it.ready }
            r.model.onAction(BlockedAction.Share)
            r.model.onAction(BlockedAction.Export)
            r.model.onAction(BlockedAction.AskUnblockAll)
            r.model.onAction(BlockedAction.FileNotice(BlockedNotice.TooLarge))
            assertEquals("only the notice came", BlockedEffect.Say(BlockedNotice.TooLarge), r.next())
        }

    @Test fun blockedCallsOfListedNumbersShowWithTheirReasonAndATapOpensTheNumber() =
        runBlocking<Unit> {
            val calls =
                FakeCallLogRepository(
                    listOf(
                        logEntry(
                            1,
                            "+49 151 2345678",
                            CallLogKind.Blocked,
                            ago = 5,
                        ).copy(blockReason = BlockReason.User),
                        logEntry(
                            2,
                            "0555 999 88 77",
                            CallLogKind.Blocked,
                            ago = 9,
                        ).copy(blockReason = BlockReason.System),
                        logEntry(3, "+49 151 2345678", CallLogKind.Incoming, ago = 20),
                    ),
                )
            val r = rig(listOf(known), calls)
            val ui = r.ui("one call") { it.calls.isNotEmpty() }
            assertEquals("only calls of numbers on the list", 1, ui.calls.size)
            assertEquals(BlockReason.User, ui.calls[0].reason)
            assertTrue(ui.calls[0].whenText.isNotBlank())
            r.model.onAction(BlockedAction.OpenCall(ui.calls[0].key))
            assertEquals(BlockedEffect.OpenNumber("+49 151 2345678"), r.next())
        }

    @Test fun withoutTheDialerRoleTheStateSaysSoAndABlockFails() =
        runBlocking<Unit> {
            val r = rig(allowed = false)
            r.ui("no write") { it.ready && !it.canWrite }
            r.model.onAction(BlockedAction.PasteTyped("0532 111 22 33"))
            r.ui("preview") { it.paste?.toBlock == 1 }
            r.model.onAction(BlockedAction.PasteConfirm)
            assertEquals(BlockedEffect.Say(BlockedNotice.Unavailable), r.next())
            assertNotNull(r.model.state.value.paste)
        }
}
