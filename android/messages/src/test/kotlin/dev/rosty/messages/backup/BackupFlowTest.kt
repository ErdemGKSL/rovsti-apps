package dev.rosty.messages.backup

import dev.rosty.core.model.messages.Strength
import org.junit.Assert.assertEquals
import org.junit.Test

/** AD-MSG7: the export form's checks and the phases, as the GPUI app's `backup_ui.rs` tests them. */
class BackupFlowTest {
    private val form = BackupFlow.beginExport(BackupPhase.Idle)

    @Test fun exportNeedsAPassphraseTypedTwice() {
        assertEquals(BackupPhase.Export(ExportIssue.Empty), BackupFlow.submitExport(form, "", "", null))
        assertEquals(
            BackupPhase.Export(ExportIssue.Mismatch),
            BackupFlow.submitExport(form, "correct horse", "something else", Strength.Strong),
        )
        assertEquals(
            BackupPhase.Busy(BackupBusy.Sealing),
            BackupFlow.submitExport(form, "correct horse", "correct horse", Strength.Strong),
        )
    }

    @Test fun aWeakPassphraseIsWarnedAboutOnceAndEditingReArmsIt() {
        val warned = BackupFlow.submitExport(form, "abc12", "abc12", Strength.Weak)
        assertEquals(BackupPhase.Export(ExportIssue.Weak), warned)
        assertEquals(
            BackupPhase.Busy(BackupBusy.Sealing),
            BackupFlow.submitExport(warned, "abc12", "abc12", Strength.Weak),
        )
        val edited = BackupFlow.edited(warned)
        assertEquals(BackupPhase.Export(), edited)
        assertEquals(
            BackupPhase.Export(ExportIssue.Weak),
            BackupFlow.submitExport(edited, "abc12", "abc12", Strength.Weak),
        )
    }

    @Test fun aMismatchBeatsAWeakWarning() {
        assertEquals(
            BackupPhase.Export(ExportIssue.Mismatch),
            BackupFlow.submitExport(form, "abc12", "abc13", Strength.Weak),
        )
    }

    @Test fun onlyTheFormSubmitsAndBusyWorkCannotBeCancelled() {
        assertEquals(BackupPhase.Idle, BackupFlow.submitExport(BackupPhase.Idle, "x", "x", Strength.Strong))
        val busy = BackupPhase.Busy(BackupBusy.Restoring)
        assertEquals(busy, BackupFlow.cancel(busy))
        assertEquals(busy, BackupFlow.beginExport(busy))
        assertEquals(BackupPhase.Idle, BackupFlow.cancel(BackupPhase.ImportAsk(wrong = true)))
    }

    @Test fun aPickedFileAsksForItsPassphrase() {
        assertEquals(BackupPhase.ImportAsk(), BackupFlow.picked(true))
        assertEquals(BackupPhase.Failed(BackupFailure.Unreadable), BackupFlow.picked(false))
    }

    @Test fun fileNamesCarryTheDay() {
        assertEquals("rosty-messages-20261105.rmb", BackupEngine.fileName(java.time.LocalDate.of(2026, 11, 5)))
    }
}
