package dev.rosty.messages.backup

import android.app.Application
import android.net.Uri
import dev.rosty.core.FakeRustCore
import dev.rosty.core.model.messages.ScheduleTarget
import dev.rosty.core.model.messages.ScheduledSms
import dev.rosty.core.model.messages.SpamRule
import dev.rosty.core.model.messages.SpamRules
import dev.rosty.core.ops.NativeMessagesOps
import dev.rosty.platform.sms.FakeSmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * AD-MSG7 end to end on fakes: export seals what the phone holds and saves it where the person chose; import asks for
 * the passphrase (again after a wrong one), previews the counts and restores only what is missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val target: Uri = Uri.parse("content://docs/backup.rmb")
    private val now = 1_800_000_000_000L

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private class Rig(
        val model: BackupViewModel,
        val sms: FakeSmsRepository,
        val sources: FakeBackupSources,
        val files: FakeBackupFiles,
        val effects: MutableList<BackupEffect>,
    )

    private fun TestScope.rig(
        rows: List<dev.rosty.core.model.messages.SmsRow>,
        sources: FakeBackupSources = FakeBackupSources(),
    ): Rig {
        val core = FakeRustCore().also { it.fakeBackup() }
        val sms = FakeSmsRepository(rows)
        val files = FakeBackupFiles()
        val engine = BackupEngine(NativeMessagesOps(core), sms, sources, { ByteArray(it) }, { now }, "1.0")
        val model = BackupViewModel(engine, files, { sources.role }, { LocalDate.of(2026, 11, 15) })
        val effects = mutableListOf<BackupEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effect.collect { effects += it } }
        return Rig(model, sms, sources, files, effects)
    }

    private val phone =
        listOf(
            smsRow(1, 1, "Merhaba", 1_000),
            smsRow(2, 1, "Nasılsın?", 2_000),
            smsRow(3, 2, "Kargo yolda", 3_000, "+905550000002"),
        )

    private suspend fun TestScope.export(
        r: Rig,
        pass: String,
    ): ByteArray {
        r.model.onAction(BackupAction.BeginExport)
        r.model.onAction(BackupAction.SubmitExport(pass, pass))
        advanceUntilIdle()
        assertEquals(BackupEffect.SaveAs("rosty-messages-20261115.rmb"), r.effects.last())
        assertEquals(BackupPhase.Busy(BackupBusy.Saving), r.model.state.value.phase)
        r.model.onAction(BackupAction.SaveTo(target))
        advanceUntilIdle()
        assertEquals(BackupEffect.Finished(BackupPhase.Done(BackupDone.Saved)), r.effects.last())
        return r.files.written.getValue(target)
    }

    @Test fun aWeakPassphraseIsWarnedOnceThenUsed() =
        scope.runTest {
            val r = rig(phone)
            r.model.onAction(BackupAction.BeginExport)
            r.model.onAction(BackupAction.SubmitExport("abc12", "abc12"))
            advanceUntilIdle()
            assertEquals(BackupPhase.Export(ExportIssue.Weak), r.model.state.value.phase)
            r.model.onAction(BackupAction.SubmitExport("abc12", "abc12"))
            advanceUntilIdle()
            assertEquals(BackupPhase.Busy(BackupBusy.Saving), r.model.state.value.phase)
        }

    @Test fun backingOutOfSaveAsLeavesNothingBehind() =
        scope.runTest {
            val r = rig(phone)
            r.model.onAction(BackupAction.BeginExport)
            r.model.onAction(BackupAction.SubmitExport("correct horse battery", "correct horse battery"))
            advanceUntilIdle()
            r.model.onAction(BackupAction.SaveTo(null))
            advanceUntilIdle()
            assertEquals(BackupPhase.Idle, r.model.state.value.phase)
            assertTrue(r.files.written.isEmpty())
        }

    @Test fun aRoundTripPreviewsAndRestoresOnlyWhatIsMissing() =
        scope.runTest {
            val ahead = now + 3_600_000
            val item = ScheduledSms(5, ahead, ScheduleTarget.Sms("+905550000001", null), "Doğum günün kutlu olsun", 1)
            val past = item.copy(id = 6, atMs = now - 1)
            val sources =
                FakeBackupSources(
                    rules = SpamRules(rules = listOf(SpamRule.Sender("PROMO*")), allow = listOf("BANK")),
                    waiting = mutableListOf(item, past),
                )
            val r = rig(phone, sources)
            val bytes = export(r, "correct horse battery")
            // A new phone: one message is already there, the rules and the schedule are empty.
            val fresh = FakeBackupSources(rules = SpamRules())
            val other = rig(listOf(phone[0]), fresh)
            other.files.stored = bytes
            other.model.onAction(BackupAction.PickStarted)
            other.model.onAction(BackupAction.Picked(target))
            advanceUntilIdle()
            assertEquals(BackupPhase.ImportAsk(), other.model.state.value.phase)
            assertEquals("rosty-messages-20261115.rmb", other.model.state.value.fileName)
            other.model.onAction(BackupAction.Open("wrong passphrase"))
            advanceUntilIdle()
            assertEquals(BackupPhase.ImportAsk(wrong = true), other.model.state.value.phase)
            other.model.onAction(BackupAction.Open("correct horse battery"))
            advanceUntilIdle()
            assertEquals(
                BackupPhase.Preview(RestorePreview(insert = 2, skipped = 1, scheduled = 1, spamRules = 2)),
                other.model.state.value.phase,
            )
            other.model.onAction(BackupAction.Restore)
            advanceUntilIdle()
            assertEquals(BackupEffect.Finished(BackupPhase.Done(BackupDone.Restored(2, 1))), other.effects.last())
            assertEquals(
                phone.map { it.body }.toSet(),
                other.sms
                    .search("%")
                    .sms
                    .map { it.body }
                    .toSet(),
            )
            assertEquals(listOf(SpamRule.Sender("PROMO*")), fresh.rules.rules)
            assertEquals(listOf("BANK"), fresh.rules.allow)
            assertEquals(listOf(item), fresh.waiting)
            assertEquals(BackupPhase.Idle, other.model.state.value.phase)
        }

    @Test fun withoutTheRoleNothingIsWritten() =
        scope.runTest {
            val r = rig(phone)
            val bytes = export(r, "correct horse battery")
            val fresh = FakeBackupSources(rules = SpamRules(), role = false)
            val other = rig(emptyList(), fresh)
            other.files.stored = bytes
            other.model.onAction(BackupAction.Picked(target))
            advanceUntilIdle()
            other.model.onAction(BackupAction.Open("correct horse battery"))
            advanceUntilIdle()
            assertEquals(false, other.model.state.value.roleHeld)
            other.model.onAction(BackupAction.Restore)
            advanceUntilIdle()
            assertEquals(BackupEffect.Finished(BackupPhase.Failed(BackupFailure.NeedsRole)), other.effects.last())
            assertTrue(
                other.sms
                    .threads()
                    .first()
                    .isEmpty(),
            )
            assertTrue(fresh.rules.rules.isEmpty())
        }

    @Test fun aFileThatIsNotABackupSaysSo() =
        scope.runTest {
            val r = rig(phone)
            r.files.stored = "hello".toByteArray()
            r.model.onAction(BackupAction.Picked(target))
            advanceUntilIdle()
            r.model.onAction(BackupAction.Open("anything"))
            advanceUntilIdle()
            assertEquals(BackupEffect.Finished(BackupPhase.Failed(BackupFailure.NotABackup)), r.effects.last())
            r.files.stored = null
            r.model.onAction(BackupAction.Picked(target))
            advanceUntilIdle()
            assertEquals(BackupEffect.Finished(BackupPhase.Failed(BackupFailure.Unreadable)), r.effects.last())
        }
}
